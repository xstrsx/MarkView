/*!
 * MarkView viewer runtime.
 *
 * The single page that the WebView ever loads. Everything it needs is bundled in
 * the APK; nothing is fetched from a CDN and the document itself can never inject
 * script (markdown-it runs with `html: false`, and all raw HTML/SVG in the document
 * therefore ends up as plain text).
 *
 * Contract with the native layer (see MarkViewBridge.kt / ViewerController.kt):
 *   MarkView.setTheme("light" | "dark")
 *   MarkView.render(markdownText)            -> Promise<void>
 *   MarkView.showMessage(title, detail)      -> Promise<void>
 *
 * Calls made from the page back into native land:
 *   MarkViewBridge.resolveResource(rawSrc)   -> {"url":...} | {"error":...}
 *   MarkViewBridge.openExternal(url)
 *   MarkViewBridge.openRelativeLink(rawHref)
 *   MarkViewBridge.reportMessage(text)
 */
(function (global) {
  "use strict";

  var BRIDGE_NAME = "MarkViewBridge";
  var CONTENT_ID = "mk-content";
  var STATUS_ID = "mk-status";

  var SVG_NAMESPACE = "http://www.w3.org/2000/svg";
  var REPORT_LIMIT = 40;

  // SMILES geometry (MV-05 / MV-06).
  //
  // SmilesDrawer draws at a fixed scale of 1 unit = 1 layout unit, with a bond length
  // of 30 units and a 11pt label. Two of its defaults then misbehave:
  //   * `scale: 0` forces a SQUARE viewBox, so a wide molecule (an 825x35 chain) is
  //     padded to 825x825 and a small one is magnified when the element is stretched —
  //     the cause of the huge OH2 water label and the large vertical gaps;
  //   * the viewBox is derived from atom positions only, while the label group is placed
  //     at the atom and the text is drawn *from* that point (only `direction: left` is
  //     right-anchored), at 11pt instead of the 11 units the layout assumes. A multi-glyph
  //     label such as NH4+ or COOH therefore extends far past the atom's 10-unit padding
  //     and is cut off by the SVG viewport — and no amount of `overflow-x` can bring
  //     clipped ink back, which is why the fix has to correct the drawing range.
  //
  // A positive `scale` gives a tight, non-squared viewBox plus an explicit pixel size;
  // the ink bounds are then measured on the live element and the viewBox/size are
  // recomputed from them, so labels, charges and subscripts always fit while the display
  // size stays at a fixed, readable unit scale instead of following the container width.
  var SMILES_DRAW_SIZE = 500;
  var SMILES_UNIT_SCALE = 1.2;        // CSS px per drawing unit (bond ~36px, label ~17px)
  var SMILES_MIN_MARGIN_UNITS = 10;   // guaranteed breathing room around the ink
  var SMILES_MARGIN_RATIO = 0.12;     // plus a share of the structure size
  var SMILES_MAX_DISPLAY_UNITS = 4000; // guard against pathological input only

  var reportedMessages = 0;

  // Formula-engine options. Unchanged from the original project book (§6.3 of
  // MarkView-一次落地项目书.md): throwOnError stays false for document formulas, so a
  // parse failure arrives as *markup* rather than as an exception — see
  // katexErrorTitle(). trust stays false, which is what keeps \href{javascript:…} and
  // friends inert; the chemistry extension does not need (and must not get) trust.
  var KATEX_OPTIONS = {
    throwOnError: false,
    trust: false,
    strict: false,
    output: "htmlAndMathml",
    maxSize: 40,
  };

  // \ce{...} comes from the bundled mhchem extension (vendor/katex-mhchem.min.js).
  var CHEMISTRY_MACRO = /\\ce\s*\{/;
  // Version of the bundled Mermaid build (vendor/mermaid.min.js), used only in the
  // user-facing fallback text for a diagram type this build does not implement. The offline
  // regression suite (tools/js-smoke) compares it with the version string inside the bundle,
  // so replacing vendor/mermaid.min.js without updating this line fails the suite instead of
  // telling the user a wrong version.
  var MERMAID_VERSION = "11.17.2";

  // MV-15: layout width of a Gantt chart. Mermaid sizes that diagram from its container
  // (`parentElement.offsetWidth`) while choosing the time-axis ticks from the date span alone,
  // so on a phone the same seven weekly `%Y-%m-%d` labels ended up 29px apart and overlapped.
  // 720px keeps ~79px between ticks (labels are ~60-70px wide) for the common case and the
  // diagram container scrolls horizontally when that is wider than the screen. Pinned by
  // tools/js-smoke/smoke7.js.
  var MERMAID_GANTT_WIDTH = 720;

  var state = {
    theme: "light",
    generation: 0,
    mermaidInitialised: false,
    mermaidTheme: null,
    // null = not probed yet, true/false = extension usable or not.
    chemistryReady: null,
    chemistryFailure: null,
  };

  // ---------------------------------------------------------------- small utils

  function escapeHtml(value) {
    return String(value == null ? "" : value)
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;")
      .replace(/'/g, "&#39;");
  }

  function nativeBridge() {
    try {
      return global[BRIDGE_NAME] || null;
    } catch (error) {
      return null;
    }
  }

  /**
   * Reports a diagnostic stage failure to the native layer (which keeps it in
   * logcat only) and to the local console. Bounded so that a document with hundreds
   * of broken references cannot flood the bridge.
   */
  function report(message) {
    if (reportedMessages >= REPORT_LIMIT) return;
    reportedMessages += 1;
    var text = String(message);
    try {
      if (global.console && typeof global.console.warn === "function") {
        global.console.warn("[MarkView] " + text);
      }
    } catch (error) {
      /* console is optional */
    }
    var api = nativeBridge();
    if (api && typeof api.reportMessage === "function") {
      try {
        api.reportMessage(text);
      } catch (error) {
        /* diagnostics only — never let this break rendering */
      }
    }
  }

  function contentRoot() {
    return document.getElementById(CONTENT_ID);
  }

  function statusNode() {
    return document.getElementById(STATUS_ID);
  }

  function setStatus(text) {
    var node = statusNode();
    if (!node) return;
    if (!text) {
      node.hidden = true;
      node.textContent = "";
      return;
    }
    node.hidden = false;
    node.textContent = text;
  }

  function slugify(text) {
    var value = String(text == null ? "" : text).trim().toLowerCase();
    value = value.replace(/[\s\u3000]+/g, "-");
    value = value.replace(/[^\w\u4e00-\u9fff\-]/g, "");
    value = value.replace(/-{2,}/g, "-").replace(/^-+|-+$/g, "");
    return value;
  }

  // ------------------------------------------------------------- markdown setup

  var md = null;

  function buildRenderer() {
    var markdownIt = global.markdownit;
    if (typeof markdownIt !== "function") {
      throw new Error("markdown-it is missing from the APK assets");
    }

    var instance = markdownIt({
      html: false, // raw HTML / SVG in a document is never executed, only escaped
      xhtmlOut: false,
      breaks: false,
      linkify: true,
      typographer: false,
      langPrefix: "language-",
    });

    if (typeof global.markdownitTaskLists === "function") {
      // Read-only task lists: the checkbox is rendered disabled.
      instance.use(global.markdownitTaskLists, { enabled: false, label: false, labelAfter: false });
    }

    installHeadingAnchors(instance);
    installMathRules(instance);

    instance.renderer.rules.mv_math_inline = function (tokens, index) {
      return renderFormula(tokens[index].content, false);
    };
    instance.renderer.rules.mv_math_block = function (tokens, index) {
      return renderFormula(tokens[index].content, true);
    };
    return instance;
  }

  function installHeadingAnchors(instance) {
    instance.core.ruler.push("markview_heading_anchors", function (coreState) {
      var tokens = coreState.tokens;
      var seen = Object.create(null);
      for (var i = 0; i < tokens.length; i++) {
        if (tokens[i].type !== "heading_open") continue;
        var inline = tokens[i + 1];
        if (!inline || inline.type !== "inline") continue;
        var slug = slugify(inline.content);
        if (!slug) continue;
        if (seen[slug] !== undefined) {
          seen[slug] += 1;
          slug = slug + "-" + seen[slug];
        } else {
          seen[slug] = 0;
        }
        tokens[i].attrSet("id", slug);
      }
    });
  }

  function installMathRules(instance) {
    // Inline: $...$ and $$...$$ that does not start a block.
    instance.inline.ruler.before("escape", "markview_math_inline", function (inlineState, silent) {
      var source = inlineState.src;
      var start = inlineState.pos;
      if (source.charCodeAt(start) !== 0x24) return false; // '$'

      var displayMode = source.charCodeAt(start + 1) === 0x24;
      var openLength = displayMode ? 2 : 1;
      var position = start + openLength;

      while (position < inlineState.posMax) {
        var code = source.charCodeAt(position);
        if (code === 0x0a) break; // formulas do not span lines outside a $$ block
        if (code === 0x5c) {
          position += 2;
          continue;
        }
        if (code === 0x24) break;
        position += 1;
      }
      if (position >= inlineState.posMax || source.charCodeAt(position) !== 0x24) return false;

      var closeLength = displayMode ? 2 : 1;
      if (displayMode) {
        if (source.charCodeAt(position + 1) !== 0x24) return false;
      }
      var tex = source.slice(start + openLength, position);
      if (tex.length === 0) return false;
      if (/^\s/.test(tex) || /\s$/.test(tex)) return false;
      if (/^\d/.test(tex) && !displayMode && /^\d+(\.\d*)?$/.test(tex)) return false;

      if (!silent) {
        var token = inlineState.push("mv_math_inline", "math", 0);
        token.content = tex;
        token.displayMode = displayMode;
        token.markup = displayMode ? "$$" : "$";
      }
      inlineState.pos = position + closeLength;
      return true;
    });

    // Block: a line whose content is exactly $$ ... $$ (optionally multi-line).
    //
    // MV-09: the rule is registered with an `alt` list so it may also *interrupt* a
    // paragraph, a list item or a blockquote. Without it, `$$…$$` written on the line
    // directly after text (化学.md: "钠与水反应：\n$$…$$") is swallowed as a paragraph
    // continuation, reaches the inline rule and is laid out inline; with it the line ends the
    // paragraph and is parsed as a real display block.
    instance.block.ruler.before("fence", "markview_math_block", function (blockState, startLine, endLine, silent) {
      var begin = blockState.bMarks[startLine] + blockState.tShift[startLine];
      var max = blockState.eMarks[startLine];
      if (begin + 2 > max) return false;
      if (blockState.src.slice(begin, begin + 2) !== "$$") return false;

      var firstLine = blockState.src.slice(begin + 2, max);
      var inlineClose = firstLine.indexOf("$$");
      if (inlineClose >= 0) {
        if (firstLine.slice(inlineClose + 2).trim() !== "") return false;
        if (silent) return true;
        var inlineToken = blockState.push("mv_math_block", "math", 0);
        inlineToken.content = firstLine.slice(0, inlineClose);
        inlineToken.map = [startLine, startLine + 1];
        blockState.line = startLine + 1;
        return true;
      }
      if (firstLine.trim() !== "") return false;
      if (silent) return true;

      var lines = [];
      var line = startLine + 1;
      var closed = false;
      while (line < endLine) {
        var lineBegin = blockState.bMarks[line] + blockState.tShift[line];
        var lineMax = blockState.eMarks[line];
        var text = blockState.src.slice(lineBegin, lineMax);
        if (text.trim() === "$$") {
          closed = true;
          break;
        }
        lines.push(text);
        line += 1;
      }
      if (!closed) return false;

      var token = blockState.push("mv_math_block", "math", 0);
      token.content = lines.join("\n");
      token.map = [startLine, line + 1];
      blockState.line = line + 1;
      return true;
    }, {
      // MV-09: `alt` is what gives the rule the chance to run inside those containers.
      alt: ["paragraph", "reference", "blockquote", "list"],
    });
  }

  function katexOptions(displayMode) {
    return {
      displayMode: !!displayMode,
      throwOnError: KATEX_OPTIONS.throwOnError,
      trust: KATEX_OPTIONS.trust,
      strict: KATEX_OPTIONS.strict,
      output: KATEX_OPTIONS.output,
      maxSize: KATEX_OPTIONS.maxSize,
    };
  }

  /**
   * `throwOnError: false` makes KaTeX report a parse failure as an error *marker* instead
   * of throwing, so a returned string is not proof that the formula rendered (project book
   * §6). Returns the error message when the markup carries KaTeX's error span, else null.
   */
  function katexErrorTitle(html) {
    var index = html.indexOf("katex-error");
    if (index < 0) return null;
    var end = html.indexOf(">", index);
    var tag = html.slice(index, end < 0 ? html.length : end);
    var title = /title="([^"]*)"/.exec(tag);
    return title ? decodeHtmlAttribute(title[1]) : "KaTeX error";
  }

  function decodeHtmlAttribute(value) {
    return String(value)
      .replace(/&#x27;/g, "'")
      .replace(/&#39;/g, "'")
      .replace(/&quot;/g, '"')
      .replace(/&lt;/g, "<")
      .replace(/&gt;/g, ">")
      .replace(/&amp;/g, "&");
  }

  /** Escaped fallback: the original formula text plus the failure reason as a title. */
  function mathFallback(tex, displayMode, reason) {
    var title = reason ? ' title="' + escapeHtml(reason) + '"' : "";
    var code = '<code class="mk-math-error"' + title + ">" + escapeHtml(tex) + "</code>";
    return displayMode
      ? '<div class="mk-math-block mk-math-block-error">' + code + "</div>"
      : '<span class="mk-math-inline mk-math-inline-error">' + code + "</span>";
  }

  /**
   * Decides once, at start-up, whether \ce{...} is available, and records the real failure.
   *
   * The probe deliberately renders with `throwOnError: true`: with the document setting
   * (false) KaTeX silently renders an unknown macro as literal text (`\ceH2O`), which
   * would be indistinguishable from a formula that merely looks odd. Throwing is the only
   * reliable signal that the extension is missing, and it is used for this one probe only.
   */
  function probeChemistryExtension() {
    if (state.chemistryReady !== null) return state.chemistryReady;
    var katex = global.katex;
    if (!katex || typeof katex.renderToString !== "function") {
      state.chemistryReady = false;
      state.chemistryFailure = "formula engine unavailable";
      report("chemistry support: unavailable (formula engine missing)");
      return false;
    }
    try {
      katex.renderToString("\\ce{H2O}", {
        displayMode: false,
        throwOnError: true,
        trust: KATEX_OPTIONS.trust,
        strict: KATEX_OPTIONS.strict,
        output: KATEX_OPTIONS.output,
        maxSize: KATEX_OPTIONS.maxSize,
      });
      state.chemistryReady = true;
      state.chemistryFailure = null;
      report("chemistry support: \\ce ready (mhchem extension loaded)");
      return true;
    } catch (error) {
      state.chemistryReady = false;
      state.chemistryFailure = error && error.message ? error.message : String(error);
      report("chemistry support: unavailable — " + state.chemistryFailure);
      return false;
    }
  }

  function renderFormula(tex, displayMode) {
    var katex = global.katex;
    if (!katex || typeof katex.renderToString !== "function") {
      return mathFallback(tex, displayMode, "formula engine unavailable");
    }

    var chemistry = CHEMISTRY_MACRO.test(tex);
    if (chemistry && state.chemistryReady === null) {
      // Defensive: the probe normally runs during renderer construction, but a formula
      // must never be rendered before it, otherwise a missing extension would silently
      // print raw TeX instead of a marked fallback.
      probeChemistryExtension();
    }
    if (chemistry && state.chemistryReady === false) {
      // Detected at start-up: without the extension KaTeX would quietly print the raw
      // text, so this is reported as an extension problem, not as a chemistry error.
      report("chemistry formula skipped: extension unavailable (" + state.chemistryFailure + ") in: " + tex.slice(0, 120));
      return mathFallback(tex, displayMode, "chemistry extension unavailable");
    }

    var html;
    try {
      html = katex.renderToString(tex, katexOptions(displayMode));
    } catch (error) {
      var thrown = error && error.message ? error.message : String(error);
      return mathFallback(tex, displayMode, describeFormulaFailure(tex, thrown, chemistry));
    }

    var failure = katexErrorTitle(html);
    if (failure) {
      return mathFallback(tex, displayMode, describeFormulaFailure(tex, failure, chemistry));
    }

    return displayMode
      ? '<div class="mk-math-block">' + html + "</div>"
      : '<span class="mk-math-inline">' + html + "</span>";
  }

  /**
   * One place that decides how a formula failure is reported, so an extension problem and a
   * single bad formula are never conflated in the log (project book §6).
   */
  function describeFormulaFailure(tex, message, chemistry) {
    var extensionMissing = /Undefined control sequence/i.test(message) && message.indexOf("\\ce") !== -1;
    if (extensionMissing) {
      state.chemistryReady = false;
      state.chemistryFailure = message;
      report("chemistry formula failed: extension unavailable (" + message + ") in: " + tex.slice(0, 120));
    } else if (chemistry) {
      report("chemistry formula failed: " + message + " in: " + tex.slice(0, 120));
    } else {
      report("formula failed: " + message + " in: " + tex.slice(0, 120));
    }
    return message;
  }

  // ---------------------------------------------------------- post-processing

  /**
   * MV-03: a link whose protocol MarkView does not handle must not be presented as a
   * clickable blue anchor. The text is kept, only the anchor element is replaced by a
   * plain span, so nothing can be activated and nothing looks actionable.
   *
   * Still allowed as real links: in-document `#anchors`, `http(s)://` (handed to the
   * system browser) and relative references (resolved by the native SAF layer).
   * Everything with any other scheme — `javascript:`, `data:`, `file:`, `content:`,
   * `intent:`, `mailto:`, … — is downgraded. The native navigation guard stays in
   * place as the second line of defence.
   */
  var ALLOWED_LINK_SCHEME = /^https?:\/\//i;
  var ANY_LINK_SCHEME = /^[a-z][a-z0-9+.\-]*:/i;

  function isDisallowedLink(href) {
    var value = String(href == null ? "" : href).trim();
    if (value === "") return true;
    if (value.charAt(0) === "#") return false;
    if (ALLOWED_LINK_SCHEME.test(value)) return false;
    if (value.indexOf("//") === 0) return true;
    if (ANY_LINK_SCHEME.test(value)) return true;
    return false;
  }

  function neutralizeLinks(root) {
    var anchors = root.querySelectorAll("a[href]");
    var downgraded = 0;
    for (var i = 0; i < anchors.length; i++) {
      var anchor = anchors[i];
      if (!isDisallowedLink(anchor.getAttribute("href"))) continue;
      var plain = document.createElement("span");
      plain.className = "mk-plain-link";
      while (anchor.firstChild) plain.appendChild(anchor.firstChild);
      if (anchor.parentNode) anchor.parentNode.replaceChild(plain, anchor);
      downgraded += 1;
    }
    if (downgraded > 0) {
      report("downgraded " + downgraded + " unsupported-protocol link(s) to plain text");
    }
  }

  function wrapTables(root) {
    var tables = root.querySelectorAll("table");
    for (var i = 0; i < tables.length; i++) {
      var table = tables[i];
      if (table.parentNode && table.parentNode.classList && table.parentNode.classList.contains("mk-table-scroll")) {
        continue;
      }
      var wrapper = document.createElement("div");
      wrapper.className = "mk-table-scroll";
      table.parentNode.insertBefore(wrapper, table);
      wrapper.appendChild(table);
    }
  }

  /**
   * MV-09: an inline formula becomes a scroll container only when it is genuinely wider than
   * the line it sits in. `overflow` on an inline-block moves the box's baseline down to its
   * bottom margin edge (CSS 2.1 §10.8.1), which is what lifted every formula above the
   * surrounding text, so the default wrapper keeps `overflow: visible` and the rare formula
   * that really does not fit opts in here — measured on the live element, never guessed from
   * the formula's length.
   */
  function adjustInlineMath(root) {
    var nodes = root.querySelectorAll(".mk-math-inline");
    var scrolling = 0;
    for (var i = 0; i < nodes.length; i++) {
      var node = nodes[i];
      // The wrapper is `display: inline-block; max-width: 100%`: an over-wide formula is
      // clamped to the line box, so its own content shows up as scrollable overflow. One
      // pixel of slack absorbs sub-pixel rounding at exactly the line width.
      var overflows = node.scrollWidth - node.clientWidth > 1;
      if (overflows) scrolling += 1;
      node.classList.toggle("mk-math-inline-scroll", overflows);
    }
    return { total: nodes.length, scrolling: scrolling };
  }

  function buildFallback(label, code) {
    var box = document.createElement("div");
    box.className = "mk-diagram-fallback";
    var title = document.createElement("div");
    title.className = "mk-diagram-fallback-title";
    title.textContent = label;
    var pre = document.createElement("pre");
    var codeNode = document.createElement("code");
    codeNode.textContent = code;
    pre.appendChild(codeNode);
    box.appendChild(title);
    box.appendChild(pre);
    return box;
  }

  /** Exact class-token match so that `language-mermaid-extra` is never picked up. */
  function hasLanguageClass(node, language) {
    var classes = String(node.className || "").split(/\s+/);
    var wanted = "language-" + language;
    for (var i = 0; i < classes.length; i++) {
      if (classes[i] === wanted) return true;
    }
    return false;
  }

  function collectCodeBlocks(root, language) {
    var nodes = root.querySelectorAll("pre > code");
    var result = [];
    for (var i = 0; i < nodes.length; i++) {
      if (!nodes[i].parentNode) continue;
      if (!hasLanguageClass(nodes[i], language)) continue;
      result.push(nodes[i]);
    }
    return result;
  }

  function mermaidTheme() {
    return state.theme === "dark" ? "dark" : "default";
  }

  function ensureMermaid() {
    var mermaid = global.mermaid;
    if (!mermaid || typeof mermaid.render !== "function") return null;
    var desired = mermaidTheme();
    if (state.mermaidInitialised && state.mermaidTheme === desired) return mermaid;
    try {
      mermaid.initialize({
        startOnLoad: false,
        securityLevel: "strict",
        theme: desired,
        fontFamily: "inherit",
        altFontFamily: "inherit",
        logLevel: "fatal",
        suppressErrorRendering: true,
        // `useMaxWidth: false` keeps every diagram at its natural size; the diagram
        // container (`overflow-x: auto`) then stretches narrow diagrams to the screen
        // width and scrolls wide ones, instead of shrinking their labels into
        // unreadable type (project book §8: 图表按可用宽度缩放, MV-04).
        flowchart: { htmlLabels: true, useMaxWidth: false, curve: "linear" },
        sequence: { useMaxWidth: false },
        // MV-15: a Gantt chart is the one diagram whose *layout* width is not its content
        // width — mermaid measures `parentElement.offsetWidth` and lays the whole chart out
        // for that, while the axis ticks are chosen from the time span alone (`%Y-%m-%d`
        // labels are ~60-70px wide, seven weekly ticks landed 29px apart on a 360px phone,
        // so the date labels overlapped). `useWidth` fixes the canvas at a width that leaves
        // room for the labels; the container above then scrolls it horizontally instead of
        // squeezing it, exactly like the other diagram types (MV-04).
        gantt: { useMaxWidth: false, useWidth: MERMAID_GANTT_WIDTH },
        journey: { useMaxWidth: false },
        class: { useMaxWidth: false },
        state: { useMaxWidth: false },
        er: { useMaxWidth: false },
        pie: { useMaxWidth: false },
        mindmap: { useMaxWidth: false },
        timeline: { useMaxWidth: false },
        gitGraph: { useMaxWidth: false },
        // `swimlane-beta` is implemented by the bundled build (it was absent from the
        // 11.12.0 bundle this project shipped before), listed for the same reason as the
        // others: keep the diagram at its natural size instead of shrinking its labels.
        swimlane: { useMaxWidth: false },
      });
      state.mermaidInitialised = true;
      state.mermaidTheme = desired;
    } catch (error) {
      report("mermaid.initialize failed: " + error);
      state.mermaidInitialised = false;
      state.mermaidTheme = null;
    }
    return mermaid;
  }

  /**
   * MV-11: "this build has no such diagram type" and "this diagram is broken" are different
   * situations for the reader, and Mermaid reports them as different errors. No detector
   * matching the text raises `UnknownDiagramError` ("No diagram type detected matching given
   * configuration for text: …"), while a diagram type that *is* implemented and has broken
   * syntax raises a plain `Error` ("Parse error on line N"). Both keep the original code and
   * the error stays in the log; only the headline differs.
   */
  function mermaidFailureLabel(error) {
    var name = error && error.name ? String(error.name) : "";
    var message = error && error.message ? String(error.message) : String(error);
    if (name === "UnknownDiagramError" || /No diagram type detected/i.test(message)) {
      return "Mermaid 不支持该图表类型（mermaid " + MERMAID_VERSION + "），已保留原始代码";
    }
    return "Mermaid 图渲染失败，已保留原始代码";
  }

  function renderMermaidBlock(mermaid, codeNode, index, generation) {
    var source = codeNode.textContent == null ? "" : codeNode.textContent;
    var pre = codeNode.parentNode;
    var host = document.createElement("div");
    host.className = "mk-diagram mk-mermaid";
    var identifier = "mk-mermaid-" + generation + "-" + index;
    return mermaid
      .render(identifier, source)
      .then(function (result) {
        if (state.generation !== generation) return;
        host.innerHTML = result && result.svg ? result.svg : "";
        if (result && typeof result.bindFunctions === "function") {
          try {
            result.bindFunctions(host);
          } catch (error) {
            /* interaction is not required */
          }
        }
        if (pre.parentNode) pre.parentNode.replaceChild(host, pre);
      })
      .catch(function (error) {
        if (state.generation !== generation) return;
        report("mermaid render failed: " + error);
        var fallback = document.createElement("div");
        fallback.className = "mk-diagram mk-diagram-error";
        fallback.appendChild(buildFallback(mermaidFailureLabel(error), source));
        if (pre.parentNode) pre.parentNode.replaceChild(fallback, pre);
      });
  }

  function renderMermaidBlocks(root, generation) {
    var nodes = collectCodeBlocks(root, "mermaid");
    if (nodes.length === 0) return Promise.resolve();
    var mermaid = ensureMermaid();
    if (!mermaid) {
      for (var i = 0; i < nodes.length; i++) {
        var pre = nodes[i].parentNode;
        var fallback = document.createElement("div");
        fallback.className = "mk-diagram mk-diagram-error";
        fallback.appendChild(buildFallback("Mermaid 未加载，已保留原始代码", nodes[i].textContent || ""));
        if (pre && pre.parentNode) pre.parentNode.replaceChild(fallback, pre);
      }
      return Promise.resolve();
    }
    var jobs = [];
    for (var index = 0; index < nodes.length; index++) {
      jobs.push(renderMermaidBlock(mermaid, nodes[index], index, generation));
    }
    return Promise.all(jobs);
  }

  /**
   * SMILES fence → inline SVG.
   *
   * SmilesDrawer 2.1.7 does export a `Drawer` (canvas) facade, but its `draw()`
   * forwards its arguments to `SvgDrawer.draw()` in the wrong order: `infoOnly`
   * lands in the `weights` parameter, and the `infoOnly` parameter then receives
   * the (truthy) `highlight_atoms` array. `SvgDrawer.draw()` therefore skips
   * creating its `svgWrapper` and immediately throws
   * `TypeError: ... reading 'determineDimensions'` — which is why *every* SMILES
   * block failed, including `CCO` and `O`.
   *
   * Drawing through `SvgDrawer` with the correct signature avoids that facade
   * entirely, and it also removes the canvas → `toDataURL()` → `Image.onload`
   * detour (that conversion is asynchronous, so the PNG was read before the
   * structure had been drawn, and the resulting bitmap was empty).
   */
  /**
   * Ink bounds of a drawn structure: the union of the geometry of every group the
   * library produced (bonds, atom labels, charges, subscripts). The engine measures the
   * real text, including the 11pt size the library renders but does not account for.
   */
  function diagramInkBounds(svg) {
    var bounds = null;
    var children = svg.children || [];
    for (var i = 0; i < children.length; i++) {
      var node = children[i];
      if (!node.tagName || String(node.tagName).toLowerCase() !== "g") continue;
      if (typeof node.getBBox !== "function") continue;
      var box = null;
      try {
        box = node.getBBox();
      } catch (error) {
        box = null;
      }
      if (!box || !isFinite(box.width) || !isFinite(box.height)) continue;
      if (box.width <= 0 && box.height <= 0) continue;
      bounds = bounds ? unionBounds(bounds, box) : {
        x: box.x,
        y: box.y,
        width: box.width,
        height: box.height,
      };
    }
    // Fallback for engines that cannot measure (or an empty structure): keep the box the
    // library computed and let the margin below cover the label ink.
    return bounds || viewBoxBounds(svg);
  }

  function viewBoxBounds(svg) {
    var raw = String(svg.getAttribute("viewBox") || "").split(/[\s,]+/);
    if (raw.length !== 4) return null;
    var values = raw.map(parseFloat);
    for (var i = 0; i < 4; i++) {
      if (!isFinite(values[i])) return null;
    }
    return { x: values[0], y: values[1], width: values[2], height: values[3] };
  }

  function unionBounds(a, b) {
    var minX = Math.min(a.x, b.x);
    var minY = Math.min(a.y, b.y);
    var maxX = Math.max(a.x + a.width, b.x + b.width);
    var maxY = Math.max(a.y + a.height, b.y + b.height);
    return { x: minX, y: minY, width: maxX - minX, height: maxY - minY };
  }

  /**
   * Pads the viewBox around the measured ink and gives the SVG an explicit pixel size at
   * a fixed unit scale. Wide structures therefore overflow their container (which scrolls)
   * instead of being shrunk, and small ones keep their natural proportions instead of
   * being magnified to the container width.
   *
   * Must run after the SVG is in the document, so that text can be measured.
   */
  function fitSmilesDiagram(svg) {
    var bounds = diagramInkBounds(svg);
    if (!bounds) return;

    // Scale the margin with the *smaller* side: basing it on the longest side would give
    // a wide molecule (825x35 units) hundreds of units of vertical padding, which is the
    // wasted space MV-06 reported.
    var margin = Math.max(SMILES_MIN_MARGIN_UNITS, SMILES_MARGIN_RATIO * Math.min(bounds.width, bounds.height));
    var x = bounds.x - margin;
    var y = bounds.y - margin;
    var width = bounds.width + 2 * margin;
    var height = bounds.height + 2 * margin;
    if (!(width > 0) || !(height > 0)) return;

    var scale = Math.min(SMILES_UNIT_SCALE, SMILES_MAX_DISPLAY_UNITS / Math.max(width, height));
    var displayWidth = Math.max(1, Math.round(width * scale));
    var displayHeight = Math.max(1, Math.round(height * scale));

    svg.setAttribute("viewBox", x + " " + y + " " + width + " " + height);
    svg.setAttribute("width", String(displayWidth));
    svg.setAttribute("height", String(displayHeight));
    svg.style.width = displayWidth + "px";
    svg.style.height = displayHeight + "px";
  }

  function renderSmilesBlock(codeNode) {
    var source = (codeNode.textContent || "").replace(/\s+/g, " ").trim();
    var pre = codeNode.parentNode;

    function fail(stage, error) {
      report(
        "smiles " + stage + " failed" +
          (error ? " (" + (error.message || error) + ")" : "") +
          (error && error.stack ? " stack: " + String(error.stack).slice(0, 300) : "") +
          " for: " + source.slice(0, 120)
      );
      var fallback = document.createElement("div");
      fallback.className = "mk-diagram mk-diagram-error";
      fallback.appendChild(buildFallback("SMILES 结构式渲染失败，已保留原始代码", codeNode.textContent || ""));
      if (pre && pre.parentNode) pre.parentNode.replaceChild(fallback, pre);
    }

    var drawerNamespace = global.SmilesDrawer;
    if (!drawerNamespace ||
        typeof drawerNamespace.parse !== "function" ||
        typeof drawerNamespace.SvgDrawer !== "function") {
      fail("library", null);
      return;
    }

    var themeName = state.theme === "dark" ? "dark" : "light";

    try {
      drawerNamespace.parse(
        source,
        function (tree) {
          try {
            var svg = document.createElementNS(SVG_NAMESPACE, "svg");
            svg.setAttribute("xmlns", SVG_NAMESPACE);
            svg.setAttribute("xmlns:xlink", "http://www.w3.org/1999/xlink");
            svg.setAttribute("role", "img");
            svg.setAttribute("aria-label", source);

            var drawer = new drawerNamespace.SvgDrawer({
              width: SMILES_DRAW_SIZE,
              height: SMILES_DRAW_SIZE,
              // > 0: tight (non-squared) viewBox and an explicit pixel size.
              scale: SMILES_UNIT_SCALE,
            });
            // 2.1.7 signature: draw(data, target, themeName, weights, infoOnly, ...)
            drawer.draw(tree, svg, themeName);

            var host = document.createElement("div");
            host.className = "mk-diagram mk-smiles";
            host.appendChild(svg);
            if (pre && pre.parentNode) pre.parentNode.replaceChild(host, pre);

            // Sizing needs the element to be in the document so text can be measured;
            // a failure here must never turn a drawn structure into an error block.
            try {
              fitSmilesDiagram(svg);
            } catch (error) {
              report("smiles sizing failed (" + (error && error.message ? error.message : error) + ") for: " + source.slice(0, 120));
            }
          } catch (error) {
            fail("draw", error);
          }
        },
        function (error) {
          fail("parse", error);
        }
      );
    } catch (error) {
      fail("call", error);
    }
  }

  function renderSmilesBlocks(root) {
    var nodes = collectCodeBlocks(root, "smiles");
    for (var i = 0; i < nodes.length; i++) {
      renderSmilesBlock(nodes[i]);
    }
  }

  function parseResourceResponse(raw) {
    if (raw == null) return null;
    if (typeof raw === "object") return raw;
    try {
      return JSON.parse(String(raw));
    } catch (error) {
      return null;
    }
  }

  function replaceImageWithPlaceholder(image, label) {
    var placeholder = document.createElement("span");
    placeholder.className = "mk-missing-image";
    placeholder.textContent = label || "图片不可用";
    if (image.parentNode) image.parentNode.replaceChild(placeholder, image);
  }

  function resolveImages(root) {
    var api = nativeBridge();
    var images = root.querySelectorAll("img");
    for (var i = 0; i < images.length; i++) {
      var image = images[i];
      var source = image.getAttribute("src") || "";
      var alt = image.getAttribute("alt") || "";

      if (source === "") {
        replaceImageWithPlaceholder(image, alt || "图片不可用");
        continue;
      }
      if (/^https?:\/\//i.test(source)) {
        attachImageErrorFallback(image, alt);
        continue;
      }
      if (/^data:/i.test(source)) {
        continue;
      }
      if (!api || typeof api.resolveResource !== "function") {
        replaceImageWithPlaceholder(image, alt || source);
        continue;
      }

      var response = null;
      try {
        response = parseResourceResponse(api.resolveResource(source));
      } catch (error) {
        report("resolveResource failed: " + error);
      }
      if (response && response.url) {
        image.setAttribute("src", response.url);
        attachImageErrorFallback(image, alt);
      } else {
        replaceImageWithPlaceholder(image, alt || source);
      }
    }
  }

  function attachImageErrorFallback(image, alt) {
    image.addEventListener("error", function () {
      report("image failed to load: " + String(image.getAttribute("src") || "").slice(0, 200));
      replaceImageWithPlaceholder(image, alt || "图片加载失败");
    });
  }

  function fallbackText(label, text) {
    var box = document.createElement("div");
    box.className = "mk-diagram mk-diagram-error";
    box.appendChild(buildFallback(label, text));
    return box;
  }


  // ------------------------------------------------------------------ rendering


  function setTheme(theme) {
    state.theme = theme === "dark" ? "dark" : "light";
    var html = document.documentElement;
    html.setAttribute("data-theme", state.theme);
    try {
      html.style.colorScheme = state.theme;
    } catch (error) {
      /* cosmetic only */
    }
    state.mermaidTheme = null;
  }

  function render(markdownText) {
    state.generation += 1;
    var generation = state.generation;
    var root = contentRoot();
    var text = markdownText == null ? "" : String(markdownText);

    try {
      if (!md) md = buildRenderer();
    } catch (error) {
      report("markdown-it init failed: " + error);
      if (root) root.innerHTML = "";
      setStatus("");
      if (root) {
        root.appendChild(
          fallbackText("Markdown 渲染器初始化失败", String(error && error.message ? error.message : error))
        );
      }
      return Promise.resolve();
    }

    setStatus("正在渲染…");
    if (root) {
      try {
        root.innerHTML = md.render(text);
      } catch (error) {
        report("markdown render failed: " + error);
        root.innerHTML = "";
        root.appendChild(fallbackText("Markdown 解析失败，已保留原始文本", text));
        setStatus("");
        return Promise.resolve();
      }
    }

    wrapTables(root);
    neutralizeLinks(root);
    resolveImages(root);
    adjustInlineMath(root);

    var mermaidTask = renderMermaidBlocks(root, generation);
    var smilesTask = Promise.resolve().then(function () {
      renderSmilesBlocks(root);
    });

    return Promise.all([mermaidTask, smilesTask])
      .catch(function (error) {
        // A single failing renderer must never blank the document.
        report("post processing failed: " + error);
      })
      .then(function () {
        if (state.generation === generation) setStatus("");
      });
  }

  function showMessage(title, detail) {
    state.generation += 1;
    var root = contentRoot();
    setStatus("");
    if (!root) return Promise.resolve();
    root.innerHTML = "";
    var box = document.createElement("div");
    box.className = "mk-message";
    var heading = document.createElement("div");
    heading.className = "mk-message-title";
    heading.textContent = title == null ? "" : String(title);
    box.appendChild(heading);
    if (detail) {
      var body = document.createElement("div");
      body.className = "mk-message-detail";
      body.textContent = String(detail);
      box.appendChild(body);
    }
    root.appendChild(box);
    return Promise.resolve();
  }

  /**
   * Re-measures every drawn structure without re-parsing the document.
   *
   * Called after a configuration change (system font scale keeps its own zoom, which can
   * change how large the labels actually render), so the drawing range is always derived
   * from the current ink instead of a stale measurement.
   */
  function refit() {
    var root = contentRoot();
    if (!root) return;
    var diagrams = root.querySelectorAll(".mk-smiles > svg");
    for (var i = 0; i < diagrams.length; i++) {
      try {
        fitSmilesDiagram(diagrams[i]);
      } catch (error) {
        report("smiles refit failed (" + (error && error.message ? error.message : error) + ")");
      }
    }
    // A configuration change can also change the system font scale, which changes both the
    // rendered width of a formula and the width of the line it sits in.
    adjustInlineMath(root);
  }

  // -------------------------------------------------------------------- events

  document.addEventListener(
    "click",
    function (event) {
      var anchor = event.target && event.target.closest ? event.target.closest("a") : null;
      if (!anchor) return;
      var href = anchor.getAttribute("href") || "";
      if (href === "" || href.charAt(0) === "#") return; // in-document anchor

      var api = nativeBridge();
      event.preventDefault();
      event.stopPropagation();

      if (/^https?:\/\//i.test(href)) {
        if (api && typeof api.openExternal === "function") api.openExternal(href);
        return;
      }
      if (/^[a-z][a-z0-9+.\-]*:/i.test(href)) {
        // mailto:, tel:, content:, javascript: ... — MarkView never handles these.
        return;
      }
      if (api && typeof api.openRelativeLink === "function") api.openRelativeLink(href);
    },
    true
  );

  // Extension readiness is established once, at page initialisation, so the status is in
  // logcat before any document is opened and long before the first formula is rendered
  // (project book §4.3). renderFormula keeps a defensive probe for the same reason.
  probeChemistryExtension();
  // KaTeX ships its own webfonts (assets/css/fonts), so a first render can briefly measure a
  // formula with the fallback font. Re-measure once the fonts have settled: a formula whose
  // real width crosses the line width still ends up with its own scroll container (MV-09).
  if (document.fonts && document.fonts.ready && typeof document.fonts.ready.then === "function") {
    document.fonts.ready
      .then(function () {
        var readyRoot = contentRoot();
        if (readyRoot) adjustInlineMath(readyRoot);
      })
      .catch(function () {
        /* measuring is best effort */
      });
  }

  global.MarkView = {
    setTheme: setTheme,
    render: render,
    refit: refit,
    showMessage: showMessage,
    // Internal hook used by the offline regression suite (tools/js-smoke) to exercise one
    // formula at a time. It is never reachable from document content.
    __renderFormula: renderFormula,
    // Same kind of internal hook: lets the suite drive the inline-formula measurement with
    // stubbed metrics, because jsdom has no layout engine. Not reachable from content.
    __adjustInlineMath: adjustInlineMath,
    __ready: true,
  };

  setTheme(state.theme);
})(typeof window !== "undefined" ? window : this);

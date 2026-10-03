/*
 * MarkView — regression tests for the third round of real-device findings.
 *
 *   MV-08  the page declared `user-scalable=yes` while the WebView refused zooming
 *   MV-09  `$$…$$` written on the line after text was swallowed as a paragraph continuation
 *          and laid out inline; the inline wrapper's `overflow` moved every formula's
 *          baseline to its bottom margin edge (CSS 2.1 §10.8.1)
 *   MV-10  `display: flex` (1.0.6) shrank `.katex` to the formula width, so `\tag{…}` landed
 *          on top of the formula instead of on the container's right edge
 *   MV-11  a diagram type this Mermaid build has no implementation for and a broken diagram
 *          reported the same headline
 *   MV-07  every non-document intent (including the launcher's ACTION_MAIN) could raise
 *          "no shared file"; the launch intent was also consumed again after a
 *          configuration change, discarding the restored document stack
 *
 * What this can and cannot prove
 *   - it CAN prove: the rendered markup of every writing style in `test/化学.md`, the CSS
 *     contract the two fixes rely on, the class toggling of the inline-formula measurement
 *     (with stubbed metrics — jsdom has no layout engine), the failure classification of the
 *     bundled Mermaid build, and that the Android-side guards are still in the source;
 *   - it CANNOT prove: any geometry (real baselines, real scroll widths, the tag's pixel
 *     position, pinch gestures) and none of the device behaviour of MV-07 — those need a
 *     device, see BUILD-NOTES.md §"交给用户验证的范围".
 */
const fs = require("fs");
const path = require("path");
const { JSDOM } = require("jsdom");

// Defaults to the assets of this checkout, so the suite runs from anywhere.
const ASSETS = process.argv[2] || path.resolve(__dirname, "../../app/src/main/assets/markview");
const PROJECT = path.resolve(ASSETS, "../../../../..");
const failures = [];
const notes = [];

function check(condition, message) {
  if (condition) {
    console.log("  ok   " + message);
  } else {
    console.log("  FAIL " + message);
    failures.push(message);
  }
}

const SCRIPTS = [
  "js/polyfill.js",
  "vendor/markdown-it.min.js",
  "vendor/markdown-it-task-lists.min.js",
  "vendor/katex.min.js",
  "vendor/katex-mhchem.min.js",
  "vendor/mermaid.min.js",
  "vendor/smiles-drawer.min.js",
];

/** Builds a page in the index.html order. */
function buildWindow() {
  const html = fs.readFileSync(path.join(ASSETS, "index.html"), "utf8");
  const dom = new JSDOM(html.replace(/<script[^>]*><\/script>/g, ""), {
    url: "https://appassets.androidplatform.net/assets/markview/index.html",
    runScripts: "dangerously",
    pretendToBeVisual: true,
  });
  const { window } = dom;
  const document = window.document;
  window.HTMLCanvasElement.prototype.getContext = () => ({
    font: "",
    measureText: () => ({ width: 10, actualBoundingBoxLeft: 1, actualBoundingBoxRight: 10, actualBoundingBoxAscent: 8, actualBoundingBoxDescent: 2 }),
  });
  window.MarkViewBridge = {
    resolveResource: () => JSON.stringify({ error: "not-found" }),
    openExternal: () => {},
    openRelativeLink: () => {},
    reportMessage: (message) => notes.push(String(message)),
  };
  for (const script of SCRIPTS) {
    const element = document.createElement("script");
    element.textContent = fs.readFileSync(path.join(ASSETS, script), "utf8");
    document.head.appendChild(element);
  }
  const viewer = document.createElement("script");
  viewer.textContent = fs.readFileSync(path.join(ASSETS, "js/markview.js"), "utf8");
  document.head.appendChild(viewer);
  return { window, document };
}

/** Renders `markdown` and returns the content root. */
async function renderInto(window, markdown) {
  await window.MarkView.render(markdown);
  return window.document.getElementById("mk-content");
}

function count(node, selector) {
  return node.querySelectorAll(selector).length;
}

/** Occurrences of `needle` in `source`; used to pin Kotlin call sites the page cannot reach. */
function countCalls(source, needle) {
  return source.split(needle).length - 1;
}

const css = fs.readFileSync(path.join(ASSETS, "css/markview.css"), "utf8");
const katexCss = fs.readFileSync(path.join(ASSETS, "css/katex.min.css"), "utf8");
const indexHtml = fs.readFileSync(path.join(ASSETS, "index.html"), "utf8");
const viewerJs = fs.readFileSync(path.join(ASSETS, "js/markview.js"), "utf8");
const mermaidBundle = fs.readFileSync(path.join(ASSETS, "vendor/mermaid.min.js"), "utf8");
const mainActivity = fs.readFileSync(
  path.join(PROJECT, "app/src/main/java/com/markview/viewer/MainActivity.kt"),
  "utf8",
);
const webViewFactory = fs.readFileSync(
  path.join(PROJECT, "app/src/main/java/com/markview/viewer/WebViewFactory.kt"),
  "utf8",
);
const documentResolver = fs.readFileSync(
  path.join(PROJECT, "app/src/main/java/com/markview/viewer/SafDocumentResolver.kt"),
  "utf8",
);
const contentIdentity = fs.readFileSync(
  path.join(PROJECT, "app/src/main/java/com/markview/viewer/ContentIdentity.kt"),
  "utf8",
);

(async () => {
  const { window, document } = buildWindow();

  console.log("\nMV-08 zoom gesture (page and WebView must agree):");
  const viewport = (indexHtml.match(/content="width=device-width[^"]*"/) || [""])[0];
  check(/maximum-scale=1(\D|$)/.test(viewport), "the viewport caps the scale at 1 (" + viewport.slice(0, 70) + ")");
  check(/user-scalable=no/.test(viewport), "the viewport refuses user scaling");
  check(!/user-scalable=yes|maximum-scale=5/.test(viewport), "the old zoom-allowing viewport is gone");
  check(/settings\.setSupportZoom\(false\)/.test(webViewFactory), "the WebView still refuses zoom (setSupportZoom(false))");
  check(/builtInZoomControls\s*=\s*false/.test(webViewFactory), "the built-in zoom controls stay off");
  check(
    /settings\.textZoom = textZoomPercent\(context\)/.test(webViewFactory),
    "system font scale still reaches the page through textZoom (accessibility untouched)",
  );

  console.log("\nMV-09 formula classification (the writing styles of test/化学.md):");
  let root = await renderInto(window, "水分子合成：$$\\ce{2H2 + O2 -> 2H2O}$$");
  check(count(root, ".mk-math-inline") === 1 && count(root, ".mk-math-block") === 0, "a $$…$$ on a text line stays inline (documented)");

  root = await renderInto(window, "钠与水反应（包含气体符号）：\n$$\\ce{2Na + 2H2O -> 2NaOH + H2 ^}$$");
  check(count(root, ".mk-math-block") === 1, "a $$…$$ on the next line becomes a display block (alt fix)");
  check(count(root, ".mk-math-inline") === 0, "and never falls through to the inline rule");

  root = await renderInto(window, "- 乙醇的结构式是：\n  $$\\ce{C2H5OH}$$");
  check(count(root, ".mk-math-block") === 1, "a $$…$$ inside a list item becomes a display block too");

  root = await renderInto(window, "> 说明：\n> $$\\ce{H2O}$$");
  check(count(root, ".mk-math-block") === 1, "a $$…$$ inside a blockquote becomes a display block too");

  root = await renderInto(window, "$$\n\\ce{2H2 + O2 -> 2H2O}\n$$");
  check(count(root, ".mk-math-block") === 1, "the multi-line form still works");
  check(count(root, ".mk-math-error") === 0, "no formula degraded to an error fallback");

  const inlineRule = (css.match(/\.mk-math-inline\s*\{[^}]*\}/) || [""])[0];
  const scrollRule = (css.match(/\.mk-math-inline-scroll\s*\{[^}]*\}/) || [""])[0];
  check(/max-width:\s*100%/.test(inlineRule), "the inline wrapper still limits its width");
  check(!/overflow/.test(inlineRule), "the default inline wrapper sets no overflow (baseline stays KaTeX's)");
  check(/overflow-x:\s*auto/.test(scrollRule), "the overflow opt-in wrapper scrolls horizontally");
  check(/overflow-y:\s*hidden/.test(scrollRule), "the overflow opt-in wrapper keeps the line height");

  root = await renderInto(window, "短公式 $a=b$ 与长公式 $\\ce{2KMnO4(aq) + 16HCl(aq) -> 2KCl(aq) + 2MnCl2(aq) + 5Cl2(g) + 8H2O(l)}$");
  const inlineNodes = Array.from(root.querySelectorAll(".mk-math-inline"));
  check(inlineNodes.length === 2, "both inline formulas rendered");
  for (const node of inlineNodes) {
    Object.defineProperty(node, "scrollWidth", { value: 900, configurable: true });
    Object.defineProperty(node, "clientWidth", { value: 300, configurable: true });
  }
  let measured = window.MarkView.__adjustInlineMath(root);
  check(measured.total === 2 && measured.scrolling === 2, "the measurement counts what overflows (" + JSON.stringify(measured) + ")");
  check(
    inlineNodes.every((node) => node.classList.contains("mk-math-inline-scroll")),
    "an over-wide formula gets the scroll container",
  );
  for (const node of inlineNodes) {
    Object.defineProperty(node, "scrollWidth", { value: 300, configurable: true });
  }
  window.MarkView.__adjustInlineMath(root);
  check(
    inlineNodes.every((node) => !node.classList.contains("mk-math-inline-scroll")),
    "and loses it again once the formula fits (re-measured, not sticky)",
  );

  console.log("\nMV-10 formula tags (\\tag must not sit on the formula):");
  root = await renderInto(window, "$$E=mc^2\\tag{EQ-01}$$\n\n$$a=b\\tag*{custom}$$");
  check(count(root, ".mk-math-block") === 2, "both tagged formulas are display blocks");
  const tags = Array.from(root.querySelectorAll(".mk-math-block .tag"));
  check(tags.length === 2, "KaTeX produced two tags");
  check(
    tags.every((tag) => tag.parentNode && tag.parentNode.classList.contains("katex-html")),
    "each tag is positioned inside .katex-html (its containing block)",
  );
  check(
    tags.map((tag) => (tag.textContent || "").replace(/\s+/g, "")).join("|") === "(EQ-01)|custom",
    "both tags kept their text",
  );
  const displayRule = (css.match(/\.mk-math-block\s+\.katex-display\s*\{[^}]*\}/) || [""])[0];
  check(/display:\s*block/.test(displayRule), "the display box is a block again");
  check(/width:\s*max-content/.test(displayRule), "the display box is sized by its content");
  check(/min-width:\s*100%/.test(displayRule), "and never narrower than the container (tag goes to the right edge)");
  check(!/display:\s*flex/.test(displayRule), "the flex box of 1.0.6 is gone");
  check(
    !/\.mk-math-block\s+\.katex-display\s*>\s*\.katex\s*\{/.test(css),
    "so is the `flex: 0 0 auto` rule that shrank .katex to the formula width",
  );
  check(
    !/\.mk-math[\s\S]{0,400}?display:\s*flex/.test(css.replace(/\/\*[\s\S]*?\*\//g, "")),
    "no flex rule survives anywhere in the math section",
  );
  check(
    /\.katex-display>\.katex>\.katex-html>\.tag\{position:absolute;right:0\}/.test(katexCss),
    "KaTeX still anchors the tag to the right edge of .katex-html (the assumption the fix rests on)",
  );
  check(/\.mk-math-block\s*\{[^}]*overflow-x:\s*auto/.test(css), "a wide display formula still scrolls inside its own container");

  console.log("\nMV-11 diagram failures are classified:");
  const declaredVersion = (viewerJs.match(/var MERMAID_VERSION = "([^"]+)"/) || [])[1];
  const bundledVersions = new Set(
    Array.from(mermaidBundle.matchAll(/version:"(\d+\.\d+\.\d+)"/g)).map((match) => match[1]),
  );
  check(!!declaredVersion, "markview.js declares the bundled Mermaid version (" + declaredVersion + ")");
  check(
    !!declaredVersion && bundledVersions.has(declaredVersion),
    "the shipped bundle really carries that version (drift guard for a silent vendor swap): " +
      Array.from(bundledVersions).sort().join(", "),
  );

  // Mermaid decides which diagrams it can draw only after `initialize`, which the page runs
  // lazily with the first diagram — so render one and then ask.
  await renderInto(window, "```mermaid\ngraph TD; A-->B;\n```");
  const swimlaneSource = "swimlane-beta LR\n  subgraph Lane\n    A[Start]\n  end";
  let detected = null;
  try {
    detected = await window.mermaid.detectType(swimlaneSource);
  } catch (error) {
    detected = null;
  }
  check(!!detected, "the bundled build really does implement the type the message claims (" + detected + ")");

  const unsupportedSource = "swimlanes LR\n  subgraph Lane\n    A[Start]\n  end";
  root = await renderInto(window, "```mermaid\n" + unsupportedSource + "\n```");
  const unsupportedTitle = root.querySelector(".mk-diagram-fallback-title");
  check(!!unsupportedTitle, "an unsupported diagram degrades to the fallback box");
  check(
    !!unsupportedTitle && /不支持该图表类型/.test(unsupportedTitle.textContent) && unsupportedTitle.textContent.indexOf(declaredVersion) !== -1,
    "the headline says the type is unsupported and names the version: " + (unsupportedTitle ? unsupportedTitle.textContent : ""),
  );
  const unsupportedCode = root.querySelector(".mk-diagram-fallback pre code");
  check(
    !!unsupportedCode && unsupportedCode.textContent.indexOf("swimlanes LR") !== -1,
    "the original code is still shown (nothing hidden)",
  );

  root = await renderInto(window, "```mermaid\ngraph TD; A-->\n```");
  const brokenTitle = root.querySelector(".mk-diagram-fallback-title");
  check(!!brokenTitle, "a broken diagram also degrades to the fallback box");
  check(
    !!brokenTitle && /渲染失败/.test(brokenTitle.textContent) && !/不支持该图表类型/.test(brokenTitle.textContent),
    "a broken *implemented* diagram keeps the generic headline: " + (brokenTitle ? brokenTitle.textContent : ""),
  );
  check(
    notes.some((note) => /mermaid render failed/.test(note)),
    "the underlying error is still reported to the log in both cases",
  );

  console.log("\nMV-07 intent handling (source invariants; device behaviour still needs a device):");
  check(
    /IntentParser\.Result\.None ->[\s\S]{0,400}?intent\?\.action == Intent\.ACTION_SEND/.test(mainActivity),
    "the 'no shared file' message is only raised for a share intent",
  );
  check(
    /intent\?\.action == Intent\.ACTION_SEND\)\s*\{\s*toast\(getString\(R\.string\.toast_no_shared_file\)\)/.test(mainActivity),
    "…and only around the message itself (the launcher's ACTION_MAIN stays silent)",
  );
  check(/recreated = savedInstanceState != null/.test(mainActivity), "the activity records whether it is a re-creation");
  check(
    /if \(!recreated\) \{\s*launchIntentConsumed = true\s*handleIntent\(intent, fromNewIntent = false\)/.test(mainActivity),
    "and consumes the launch intent only on a fresh launch (the restored stack survives)",
  );
  check(
    /onNewIntent: action=\$\{intent\.action\} categories=\$\{intent\.categories\}" \+[\s\S]{0,400}?hasStream=\$\{intent\.hasExtra\(Intent\.EXTRA_STREAM\)\}/.test(mainActivity),
    "onNewIntent logs action, categories and whether a file came with it",
  );
  check(/documentsOnStack=\$\{documentStack\.size\}/.test(mainActivity), "…plus the document stack depth at the second entry point");

  console.log("\nMV-14 cross-provider documents (source invariants; device behaviour still needs a device):");
  check(
    /CONTENT_IDENTITY_CROSS_PROVIDER/.test(documentResolver) &&
      /contentIdentityAssociations\(trees, metadata, documentUri\)/.test(documentResolver),
    "the resolver has a content-identity pass for documents from another provider",
  );
  check(
    /contentIdentityAssociations[\s\S]{0,3000}?ContentIdentityPolicy\.isSameContent\(targetFingerprint, candidateFingerprint\)/.test(documentResolver),
    "…and binds only after the bytes of both sides were compared",
  );
  check(
    /MessageDigest\.getInstance\("SHA-256"\)/.test(documentResolver) &&
      /IDENTITY_DIGEST_BYTES/.test(documentResolver) &&
      /IDENTITY_READ_BUDGET/.test(documentResolver),
    "the comparison is a SHA-256 over a bounded window, with a cap on how many files are read",
  );
  check(
    /\.filter \{ DocumentsContract\.isTreeUri\(it\) \}/.test(documentResolver),
    "only real tree grants are used as a resolution base (single-document grants are skipped)",
  );
  check(
    (contentIdentity.match(/fun (?:<[^>]+> )?(namesAgree|sizesAgree|isSameContent|uniqueOrNull)/g) || []).length === 4,
    "the matching rules live in the testable ContentIdentityPolicy",
  );
  check(
    countCalls(mainActivity, "tryTakePersistablePermission(") === 2,
    "the persisted grant is taken in exactly one place (its definition plus one call site)",
  );
  const rejectedAt = mainActivity.indexOf("open($entry) rejected");
  const persistAt = mainActivity.indexOf("needsOwnPersistableGrant(document.uri, document.treeUri)");
  check(
    rejectedAt > 0 && persistAt > rejectedAt,
    "…after the document was accepted as Markdown, and only when no tree grant covers it",
  );
  check(
    /private fun releaseLeftStackGrants[\s\S]{0,600}?DocumentsContract\.isTreeUri\(uri\)[\s\S]{0,300}?needsOwnPersistableGrant[\s\S]{0,200}?releasePersistableGrant\(uri\)/.test(mainActivity),
    "a document that left the stack releases its own grant (never a tree, never a stacked document)",
  );
  check(
    countCalls(mainActivity, "releaseLeftStackGrants(") === 3,
    "…from both places where documents leave the stack (its definition, replaceStack and back)",
  );

  console.log("\ndiagnostics (" + notes.length + "):");
  for (const note of notes.slice(0, 10)) console.log("  - " + note.slice(0, 150));

  console.log("\nresult: " + (failures.length === 0 ? "PASS" : "FAIL (" + failures.length + ")"));
  process.exit(failures.length === 0 ? 0 : 1);
})().catch((error) => {
  console.log("threw: " + (error && error.stack ? error.stack : error));
  process.exit(2);
});

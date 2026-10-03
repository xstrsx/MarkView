/*
 * MarkView — chemistry (mhchem) regression suite, project book §7.
 *
 * Loads the **exact assets that ship in the APK** (index.html order included) into jsdom
 * and renders the §7.1–§7.4 fixtures through MarkView.render, so the assertions cover the
 * same scripts, the same load order and the same markdown-it/KaTeX pipeline as the device.
 *
 * What this can and cannot prove
 *  - it CAN prove: the extension is picked up, CE01–CE08 produce real KaTeX output with the
 *    expected subscripts / charges / arrows / Chinese condition / state marks, fallbacks are
 *    isolated, code regions are untouched, and the "extension missing" path reports a
 *    different diagnostic than a single bad formula;
 *  - it CANNOT prove: anything geometric (jsdom has no layout engine) — scrolling, clipping,
 *    font fallback for CJK and dark mode remain device checks (see the fixture §7.6).
 */
const fs = require("fs");
const path = require("path");
const { JSDOM } = require("jsdom");

const ASSETS = process.argv[2] || "/home/xstrsx/project/MarkView/app/src/main/assets/markview";
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

/** Builds a page in the index.html order; `withoutChemistry` drops the mhchem script. */
function buildWindow(withoutChemistry) {
  const html = fs.readFileSync(path.join(ASSETS, "index.html"), "utf8");
  const dom = new JSDOM(html.replace(/<script[^>]*><\/script>/g, ""), {
    url: "https://appassets.androidplatform.net/assets/markview/index.html",
    runScripts: "dangerously",
    pretendToBeVisual: true,
  });
  const { window } = dom;
  const document = window.document;
  window.HTMLCanvasElement.prototype.getContext = () => ({ font: "", measureText: () => ({ width: 10, actualBoundingBoxLeft: 1, actualBoundingBoxRight: 10, actualBoundingBoxAscent: 8, actualBoundingBoxDescent: 2 }) });
  window.MarkViewBridge = {
    resolveResource: () => JSON.stringify({ error: "not-found" }),
    openExternal: () => {},
    openRelativeLink: () => {},
    reportMessage: (message) => notes.push(String(message)),
  };
  for (const script of SCRIPTS) {
    if (withoutChemistry && script.indexOf("mhchem") !== -1) continue;
    const element = document.createElement("script");
    element.textContent = fs.readFileSync(path.join(ASSETS, script), "utf8");
    document.head.appendChild(element);
  }
  const viewer = document.createElement("script");
  viewer.textContent = fs.readFileSync(path.join(ASSETS, "js/markview.js"), "utf8");
  document.head.appendChild(viewer);
  return { window, document };
}

/** Text actually rendered by a KaTeX subtree, ignoring its <annotation> (source echo). */
function renderedText(node) {
  const clone = node.cloneNode(true);
  clone.querySelectorAll("annotation").forEach((annotation) => annotation.remove());
  clone.querySelectorAll(".katex-mathml").forEach((mathml) => mathml.remove());
  return (clone.textContent || "").replace(/\s+/g, "");
}

const inline = (window, tex) => {
  const span = window.document.createElement("span");
  span.innerHTML = window.MarkView.__renderFormula(tex, false);
  return span;
};

const display = (window, tex) => {
  const div = window.document.createElement("div");
  div.innerHTML = window.MarkView.__renderFormula(tex, true);
  return div;
};

(async () => {
  const page = buildWindow(false);
  const { window, document } = page;

  console.log("\n§4.2 扩展加载与初始化顺序:");
  check(typeof window.katex === "object" && typeof window.katex.renderToString === "function", "KaTeX is available");
  check(typeof window.MarkView === "object", "viewer script initialised");
  check(
    notes.some((n) => n.indexOf("chemistry support") !== -1 && n.indexOf("ready") !== -1),
    "start-up probe reports the chemistry extension as ready",
  );

  console.log("\n§3 CE01–CE08 逐个渲染（含上标/下标/箭头/中文条件/物态）:");
  const cases = [
    ["CE01 化学式 \\ce{H2SO4}", "\\ce{H2SO4}", { sub: 2, text: ["H", "S", "O", "2", "4"] }],
    ["CE02 正离子 \\ce{NH4+}", "\\ce{NH4+}", { sub: 1, sup: 1, text: ["N", "H", "4", "+"] }],
    ["CE03 多电荷 \\ce{SO4^2-}", "\\ce{SO4^2-}", { sub: 1, sup: 1, text: ["S", "O", "4", "2", "−"] }],
    ["CE04 系数与单向箭头", "\\ce{2H2 + O2 -> 2H2O}", { sub: 3, text: ["2", "H", "O", "+", "→"] }],
    ["CE05 可逆反应", "\\ce{N2 + 3H2 <=> 2NH3}", { sub: 3, text: ["N", "H", "3", "⇌"] }],
    ["CE06 中文条件", "\\ce{CaCO3 ->[加热] CaO + CO2}", { sub: 2, text: ["C", "a", "O", "3", "→", "加热"] }],
    ["CE07 物态", "\\ce{CaCO3(s) -> CaO(s) + CO2(g)}", { sub: 2, text: ["(s)", "(g)", "→", "+"] }],
    ["CE08 溶液与液态", "\\ce{HCl(aq) + NaOH(aq) -> NaCl(aq) + H2O(l)}", { sub: 1, text: ["(aq)", "(l)", "→", "N", "a"] }],
    ["§7.2 宽方程式", "\\ce{2KMnO4(aq) + 16HCl(aq) -> 2KCl(aq) + 2MnCl2(aq) + 5Cl2(g) + 8H2O(l)}", { sub: 3, text: ["2", "K", "Mn", "(aq)", "(g)", "(l)", "→"] }],
  ];
  for (const [label, tex, expect] of cases) {
    const node = display(window, tex);
    const mathml = node.querySelector(".katex-mathml");
    const html = node.querySelector(".katex-html");
    const error = node.querySelector(".katex-error, .mk-math-error");
    const text = mathml ? renderedText(mathml) : "";
    const counts = {
      sub: (mathml ? mathml.querySelectorAll("msub, msubsup, munderover").length : 0),
      sup: (mathml ? mathml.querySelectorAll("msup, msubsup, munderover").length : 0),
    };
    const missing = (expect.text || []).filter((fragment) => text.indexOf(fragment) === -1);
    const ok =
      !error &&
      !!mathml && !!html &&
      counts.sub >= (expect.sub || 0) &&
      counts.sup >= (expect.sup || 0) &&
      missing.length === 0;
    check(ok, label + " → \"" + text.slice(0, 46) + "\"" + (missing.length ? " MISSING " + JSON.stringify(missing) : ""));
  }

  console.log("\n§5 容器结构（几何需真机确认，这里只查结构）:");
  const blockNode = display(window, "\\ce{2H2 + O2 -> 2H2O}");
  check(!!blockNode.querySelector(".mk-math-block"), "display formula is wrapped in .mk-math-block");
  const inlineNode = inline(window, "\\ce{H2SO4}");
  check(!!inlineNode.querySelector(".mk-math-inline"), "inline formula is wrapped in .mk-math-inline");
  const wideInline = inline(window, "\\ce{2KMnO4(aq) + 16HCl(aq) -> 2KCl(aq) + 2MnCl2(aq) + 5Cl2(g) + 8H2O(l)}");
  check(
    !!wideInline.querySelector(".mk-math-inline > .katex"),
    "a wide inline formula renders inside the inline wrapper (MV-09: it becomes scrollable only once measured as overflowing)",
  );
  const css = fs.readFileSync(path.join(ASSETS, "css/markview.css"), "utf8");
  const inlineRule = (css.match(/\.mk-math-inline\s*\{[^}]*\}/) || [""])[0];
  const blockRule = (css.match(/\.mk-math-block\s*\{[^}]*\}/) || [""])[0];
  const displayRule = (css.match(/\.mk-math-block\s+\.katex-display\s*\{[^}]*\}/) || [""])[0];
  const scrollRule = (css.match(/\.mk-math-inline-scroll\s*\{[^}]*\}/) || [""])[0];
  check(/max-width:\s*100%/.test(inlineRule), "inline wrapper still limits its width");
  check(!/overflow/.test(inlineRule), "MV-09: the inline wrapper sets no overflow by default, so its baseline stays KaTeX's");
  check(/overflow-x:\s*auto/.test(scrollRule), "MV-09: the opt-in wrapper (.mk-math-inline-scroll) scrolls instead");
  check(/display:\s*inline-block/.test(inlineRule), "inline wrapper stays in the text flow");
  check(/overflow-x:\s*auto/.test(blockRule), "display container scrolls horizontally");
  check(/padding:\s*0?\.?\d*em 0/.test(blockRule), "display container keeps vertical slack so nothing is clipped");
  check(!/font-size/.test(blockRule), "the display container never shrinks the type size");
  check(
    /display:\s*block/.test(displayRule) &&
      /width:\s*max-content/.test(displayRule) &&
      /min-width:\s*100%/.test(displayRule) &&
      !/display:\s*flex/.test(displayRule),
    "MV-10: the display box is a block sized to max-content (never flex-shrunk to the formula)",
  );
  check(
    !/text-align/.test(displayRule),
    "the display rule leaves KaTeX's own centring alone",
  );
  check(!/touch-action\s*:/.test(css), "no touch-action restriction is applied anywhere");
  const cjkRule = (css.match(/\.mk-math-block\s+\.katex,\s*\n?\.mk-math-inline\s+\.katex\s*\{[^}]*\}/) || [""])[0];
  check(
    /^[\s\S]*font-family:\s*KaTeX_Main\s*,/.test(cjkRule) && /Noto Sans CJK SC/.test(cjkRule),
    "CJK fallback is declared after the KaTeX math fonts (not instead of them)",
  );
  check(
    !/\.katex\s*\{[^}]*font-family/.test(css.replace(cjkRule, "")),
    "no global .katex font override is added anywhere else",
  );

  console.log("\n§6 失败隔离与诊断区分:");
  const badChem = display(window, "\\ce{H2O");
  check(!!badChem.querySelector(".mk-math-error"), "an unbalanced \\ce{ degrades to the original text");
  check(
    (badChem.querySelector(".mk-math-error").textContent || "").indexOf("\\ce{H2O") !== -1,
    "the fallback keeps the original formula text (textContent, not HTML)",
  );
  const badNonChem = display(window, "\\frac{1}{");
  check(!!badNonChem.querySelector(".mk-math-error"), "a broken ordinary formula degrades the same way");
  const goodAfterBad = display(window, "\\ce{2H2 + O2 -> 2H2O}");
  check(!!goodAfterBad.querySelector(".katex-html"), "a valid formula after a broken one still renders");
  const plainMath = display(window, "a^2+b^2=c^2");
  check(!!plainMath.querySelector(".katex-html") && !plainMath.querySelector(".mk-math-error"), "ordinary maths is unaffected");
  check(
    notes.some((n) => /chemistry formula failed/.test(n)) &&
      !notes.some((n) => /chemistry formula failed[\s\S]*extension unavailable/.test(n)),
    "a bad chemistry formula is logged as a formula error, not as a missing extension",
  );

  console.log("\n§6 扩展缺失时（同版本资产，但去掉 mhchem）:");
  const beforeMissing = notes.length;
  const missingPage = buildWindow(true);
  const noExt = missingPage.window.document.createElement("div");
  noExt.innerHTML = missingPage.window.MarkView.__renderFormula("\\ce{H2O}", false);
  const missingNotes = notes.slice(beforeMissing);
  check(
    missingNotes.some((n) => n.indexOf("chemistry support: unavailable") !== -1),
    "the missing extension is detected and reported as unavailable",
  );
  check(
    !!noExt.querySelector(".mk-math-error") &&
      noExt.querySelector(".katex-error") === null,
    "\\ce{...} becomes a marked fallback instead of invisible raw text",
  );
  const noExtPlain = missingPage.window.document.createElement("div");
  noExtPlain.innerHTML = missingPage.window.MarkView.__renderFormula("a^2+b^2=c^2", true);
  check(
    !!noExtPlain.querySelector(".katex-html"),
    "ordinary maths still renders when the chemistry extension is missing",
  );
  check(
    missingNotes.some((n) => n.indexOf("chemistry formula skipped") !== -1),
    "a \\ce formula is reported as skipped when the extension is unavailable",
  );
  check(
    !missingNotes.some((n) => n.indexOf("chemistry formula failed") !== -1),
    "the missing extension is not misreported as a chemistry syntax error",
  );

  console.log("\n§7.4 代码边界（整篇渲染）:");
  const fixture = [
    "行内代码：`\\ce{H2O}` 和 `$\\ce{H2O}$` 都应保持代码。",
    "",
    "```text",
    "\\ce{2H2 + O2 -> 2H2O}",
    "```",
    "",
    "```chem",
    "2H2 + O2 -> 2H2O",
    "```",
    "",
    "普通正文 H2O 不应被自动改写；裸 \\ce{H2O} 不应触发渲染。",
  ].join("\n");
  await window.MarkView.render(fixture);
  const content = document.getElementById("mk-content");
  check(content.querySelectorAll(".katex").length === 0, "no formula is produced from code regions or plain text");
  check(content.querySelectorAll("pre code.language-chem").length === 1, "an unknown 'chem' fence stays a plain code block");
  check(
    (content.textContent || "").indexOf("\\ce{H2O}") !== -1 &&
      (content.textContent || "").indexOf("$\\ce{H2O}$") !== -1,
    "inline code keeps \\ce literal",
  );
  check(
    (content.textContent || "").indexOf("裸 \\ce{H2O}") !== -1,
    "a bare \\ce{H2O} in prose is left untouched",
  );
  check(content.querySelectorAll(".mk-math-error").length === 0, "the code fixture produces no error fallback");

  console.log("\n§7.3 整篇渲染：坏公式后继续（含夹具原文）:");
  const fixturePath = path.resolve(ASSETS, "../../../../../test/化学方程式测试.md");
  const wholeDoc = fs.existsSync(fixturePath) ? fs.readFileSync(fixturePath, "utf8") : null;
  if (wholeDoc) {
    await window.MarkView.render(wholeDoc);
    const rendered = document.getElementById("mk-content");
    const chemistry = rendered.querySelectorAll(".mk-math-block .katex-html, .mk-math-inline .katex-html").length;
    check(chemistry >= 12, "the fixture renders " + chemistry + " formula bodies (chemical + ordinary)");
    check(
      rendered.querySelectorAll(".mk-math-error").length === 1,
      "exactly the deliberately broken formula falls back (" + rendered.querySelectorAll(".mk-math-error").length + ")",
    );
    check((rendered.textContent || "").indexOf("AFTER_BAD_CHEM_OK") !== -1, "text after the broken formula is still rendered");
    check(
      Array.from(rendered.querySelectorAll(".mk-mermaid, .mk-diagram-error")).some(
        (node) => node.textContent.indexOf("graph TD") !== -1 || node.querySelector("svg") !== null,
      ),
      "Mermaid in the fixture is still handled by the diagram pipeline",
    );
    check(rendered.querySelectorAll(".mk-smiles svg, .mk-diagram-error").length >= 1, "SMILES in the fixture is still processed");
    check((rendered.textContent || "").indexOf("加热") !== -1, "the Chinese reaction condition reaches the rendered page");
  } else {
    check(false, "fixture test/化学方程式测试.md not found at " + fixturePath);
  }

  console.log("\ndiagnostics (" + notes.length + "):");
  for (const note of notes.slice(0, 8)) console.log("  - " + note.slice(0, 130));

  console.log("\nresult: " + (failures.length === 0 ? "PASS" : "FAIL (" + failures.length + ")"));
  process.exit(failures.length === 0 ? 0 : 1);
})().catch((error) => {
  console.log("threw: " + (error && error.stack ? error.stack : error));
  process.exit(2);
});

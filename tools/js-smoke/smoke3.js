/*
 * MarkView — regression tests for the first round of real-device findings.
 *
 *   MV-02  SMILES: every valid sample failed ("已保留原始代码")
 *   MV-03  content:// and intent: links were rendered as clickable blue anchors
 *   MV-04  wide Mermaid diagrams were scaled down until their labels were unreadable
 *
 * jsdom has no canvas backend, so a minimal 2D context stub is installed; SmilesDrawer
 * only needs `measureText` from it to lay a structure out. Mermaid still cannot draw
 * (no SVG layout engine), so only its configuration is asserted here.
 */
const fs = require("fs");
const path = require("path");
const { JSDOM } = require("jsdom");

// Defaults to the assets of this checkout, so the suite runs from anywhere.
const ASSETS = process.argv[2] || path.resolve(__dirname, "../../app/src/main/assets/markview");
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

const html = fs.readFileSync(path.join(ASSETS, "index.html"), "utf8");
const dom = new JSDOM(html.replace(/<script[^>]*><\/script>/g, ""), {
  url: "https://appassets.androidplatform.net/assets/markview/index.html",
  runScripts: "dangerously",
  pretendToBeVisual: true,
});
const { window } = dom;
const document = window.document;

// jsdom has no 2D context; SmilesDrawer's text measurement needs one.
window.HTMLCanvasElement.prototype.getContext = function () {
  return {
    font: "",
    measureText: (text) => ({
      width: String(text).length * 8,
      actualBoundingBoxLeft: 1,
      actualBoundingBoxRight: String(text).length * 8,
      actualBoundingBoxAscent: 8,
      actualBoundingBoxDescent: 2,
    }),
  };
};

window.MarkViewBridge = {
  resolveResource: () => JSON.stringify({ error: "not-found" }),
  openExternal: () => {},
  openRelativeLink: () => {},
  reportMessage: (message) => notes.push(String(message)),
};

const scripts = [
  "js/polyfill.js",
  "vendor/markdown-it.min.js",
  "vendor/markdown-it-task-lists.min.js",
  "vendor/katex.min.js",
  "vendor/mermaid.min.js",
  "vendor/smiles-drawer.min.js",
];
for (const script of scripts) {
  const element = document.createElement("script");
  element.textContent = fs.readFileSync(path.join(ASSETS, script), "utf8");
  document.head.appendChild(element);
}

// Observe the Mermaid configuration the page actually applies.
let mermaidConfig = null;
const realInitialize = window.mermaid.initialize;
window.mermaid.initialize = function (config) {
  mermaidConfig = config;
  return realInitialize.call(window.mermaid, config);
};

const viewerScript = document.createElement("script");
viewerScript.textContent = fs.readFileSync(path.join(ASSETS, "js/markview.js"), "utf8");
document.head.appendChild(viewerScript);

const markdown = [
  "```smiles",
  "CCO",
  "```",
  "",
  "```smiles",
  "c1ccccc1",
  "```",
  "",
  "```smiles",
  "CC(=O)Oc1ccccc1C(=O)O",
  "```",
  "",
  "```smiles",
  "O",
  "```",
  "",
  "```smiles",
  "C1(",
  "```",
  "",
  "```smiles",
  "[NH4+]",
  "```",
  "",
  "```text",
  "CCO",
  "```",
  "",
  "[web](https://example.com/page)",
  "[mail](mailto:test@example.com)",
  "[content](content://com.markview.test/document/1)",
  "[intent](intent://scan/#Intent;scheme=zxing;end)",
  "[script](javascript:alert(1))",
  "[data](data:text/html,<b>x</b>)",
  "[anchor](#title)",
  "[relative](./other.md)",
  "",
  "```mermaid",
  "graph TD; A-->B;",
  "```",
  "",
].join("\n");

(async () => {
  await window.MarkView.render(markdown);
  const root = document.getElementById("mk-content");
  const out = root.innerHTML;

  console.log("\nMV-02 SMILES:");
  const diagrams = root.querySelectorAll(".mk-smiles svg");
  check(diagrams.length === 5, "5 valid structures produced an inline SVG (" + diagrams.length + ")");
  let drawn = 0;
  diagrams.forEach((svg) => {
    const markup = svg.outerHTML;
    if ((markup.match(/<line/g) || []).length + (markup.match(/<text/g) || []).length > 0) drawn += 1;
    if (svg.getAttribute("viewBox")) drawn += 0;
  });
  check(drawn === 5, "every structure contains drawing primitives (" + drawn + ")");
  check(
    Array.prototype.every.call(diagrams, (svg) => !!svg.getAttribute("viewBox")),
    "every structure has a viewBox so it scales with the page",
  );
  const smilesErrors = Array.from(root.querySelectorAll(".mk-diagram-error")).filter(
    (node) => node.textContent.indexOf("SMILES 结构式渲染失败") !== -1,
  );
  check(smilesErrors.length === 1, "only the deliberately broken C1( fell back (" + smilesErrors.length + ")");
  check(
    smilesErrors.length === 1 && smilesErrors[0].textContent.indexOf("C1(") !== -1,
    "the broken block kept its original code",
  );
  check(
    root.querySelectorAll(".mk-smiles svg").length === 5,
    "the structure after the broken block still rendered (failure isolation)",
  );
  check(
    root.querySelectorAll("pre > code.language-smiles").length === 0,
    "no valid SMILES block was left as raw code",
  );
  check(out.indexOf("CCO") !== -1, "the text fence still shows CCO as code");

  console.log("\nMV-03 unsupported protocols:");
  const plainLinks = Array.from(root.querySelectorAll(".mk-plain-link")).map((node) => node.textContent);
  check(plainLinks.length === 3, "the 3 downgradable links became plain text (" + plainLinks.length + ")");
  check(plainLinks.indexOf("mail") !== -1, "mailto: was downgraded");
  check(plainLinks.indexOf("content") !== -1, "content:// was downgraded");
  check(plainLinks.indexOf("intent") !== -1, "intent: was downgraded");
  // markdown-it already refuses to link javascript:/data: destinations, so those never
  // reach the neutraliser; all that matters is that they are not links either way.
  check(
    root.querySelectorAll("a").length === 3,
    "javascript: and data: never became anchors (" + root.querySelectorAll("a").length + " anchors)",
  );
  check(out.indexOf("javascript:alert(1)") !== -1, "the javascript: reference is kept as text");
  const hrefs = Array.from(root.querySelectorAll("a[href]")).map((node) => node.getAttribute("href"));
  check(hrefs.length === 3, "three real links remain (" + hrefs.length + ")");
  check(hrefs.indexOf("https://example.com/page") !== -1, "http(s) still goes to the system browser");
  check(hrefs.indexOf("#title") !== -1, "in-document anchors still work");
  check(hrefs.indexOf("./other.md") !== -1, "relative Markdown links are still handled natively");
  check(
    hrefs.every((href) => !/^(content|intent|javascript|data|mailto):/i.test(href)),
    "no unsupported protocol survived as a link",
  );

  console.log("\nMV-04 Mermaid sizing:");
  check(!!mermaidConfig, "the page applied a Mermaid configuration");
  check(
    !!mermaidConfig &&
      mermaidConfig.flowchart && mermaidConfig.flowchart.useMaxWidth === false &&
      mermaidConfig.sequence && mermaidConfig.sequence.useMaxWidth === false &&
      mermaidConfig.class && mermaidConfig.class.useMaxWidth === false,
    "each diagram type is laid out at its natural size (useMaxWidth: false)",
  );
  check(
    mermaidConfig && mermaidConfig.securityLevel === "strict",
    "the strict security level is unchanged",
  );
  const css = fs.readFileSync(path.join(ASSETS, "css/markview.css"), "utf8");
  check(/\.mk-mermaid\s*\{[^}]*overflow-x:\s*auto/.test(css), "the Mermaid container scrolls horizontally");
  check(/\.mk-mermaid\s*>\s*svg\s*\{[^}]*min-width:\s*100%/.test(css), "a narrow diagram still fills the width");
  check(/\.mk-mermaid\s*>\s*svg\s*\{[^}]*max-width:\s*none/.test(css), "a wide diagram keeps its natural size");
  check(/\.mk-plain-link\s*\{[^}]*color:\s*inherit/.test(css), "downgraded links are styled as ordinary text");

  console.log("\ndiagnostics (" + notes.length + "):");
  for (const note of notes.slice(0, 10)) console.log("  - " + note.slice(0, 150));

  console.log("\nresult: " + (failures.length === 0 ? "PASS" : "FAIL (" + failures.length + ")"));
  process.exit(failures.length === 0 ? 0 : 1);
})().catch((error) => {
  console.log("threw: " + (error && error.stack ? error.stack : error));
  process.exit(2);
});

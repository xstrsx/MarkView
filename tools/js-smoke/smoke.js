/*
 * MarkView — headless smoke test of the bundled viewer page.
 *
 * Loads the exact assets that ship in the APK (assets/markview/**) into a jsdom
 * window, stubs the native bridge, and renders a document that exercises every
 * feature MarkView claims: CommonMark, GFM tables/strikethrough/task lists,
 * autolinks, raw HTML escaping, headings + anchors, inline/block LaTeX, mermaid and
 * smiles fences, relative images and relative Markdown links.
 *
 * Mermaid and SmilesDrawer cannot draw in jsdom (no layout engine, no 2D canvas);
 * this test asserts the documented fallback instead: they must degrade to a
 * "kept the original code" block without breaking the rest of the document.
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

// Native side: only the two relative references below resolve; everything else is
// "not found", which must produce a placeholder rather than a broken page.
const resolvedRequests = [];
window.MarkViewBridge = {
  resolveResource: (raw) => {
    resolvedRequests.push(raw);
    if (raw === "images/pic.png") {
      return JSON.stringify({ url: "https://appassets.androidplatform.net/res/" + "a".repeat(32) });
    }
    return JSON.stringify({ error: "not-found" });
  },
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
  "js/markview.js",
];

const document = window.document;

// Injected as real classic <script> elements, exactly like the packaged page does,
// so that top-level `var` declarations really become window globals.
for (const script of scripts) {
  const source = fs.readFileSync(path.join(ASSETS, script), "utf8");
  try {
    const element = document.createElement("script");
    element.textContent = source;
    document.head.appendChild(element);
  } catch (error) {
    failures.push("loading " + script + " threw: " + error);
    console.log("  FAIL loading " + script + ": " + error);
  }
}

const markdown = [
  "# Title",
  "",
  "Plain paragraph with **bold**, _emphasis_, `code` and ~~struck~~ text.",
  "",
  "## Title",
  "",
  "| a | b |",
  "| - | - |",
  "| 1 | 2 |",
  "",
  "- [ ] open task",
  "- [x] done task",
  "",
  "Inline math $a^2+b^2=c^2$ and a price: $5 and $10 today, and a lone $ sign.",
  "",
  "$$",
  "\\int_0^1 x^2 \\, dx = \\frac{1}{3}",
  "$$",
  "",
  "```mermaid",
  "graph TD; A-->B;",
  "```",
  "",
  "```smiles",
  "CCO",
  "```",
  "",
  "![local](images/pic.png)",
  "![missing](images/nope.png)",
  "![remote](https://example.com/remote.png)",
  "",
  "[relative](./other.md)",
  "[external](https://example.com/page)",
  "[anchor](#title)",
  "",
  "<div>raw html must stay text</div>",
  "<script>window.__pwned = true;</script>",
  "<svg onload=\"window.__pwned = true\"></svg>",
  "",
].join("\n");

(async () => {
  await window.MarkView.render(markdown);
  const root = document.getElementById("mk-content");
  const out = root.innerHTML;

  console.log("\nassertions:");
  check(window.__pwned === undefined, "document cannot inject script (no window.__pwned)");
  check(out.indexOf("&lt;div&gt;raw html must stay text&lt;/div&gt;") !== -1, "raw HTML is escaped to text");
  check(out.indexOf("&lt;script&gt;") !== -1, "raw <script> is escaped");
  check(out.indexOf("&lt;svg") !== -1, "raw <svg> is escaped");
  check(out.indexOf("&lt;div&gt;") !== -1 && out.indexOf("<div>raw html") === -1, "no raw div element was created");
  check(out.indexOf("<table>") !== -1, "GFM tables render");
  check(out.indexOf("mk-table-scroll") !== -1, "tables are wrapped for local horizontal scrolling");
  check(out.indexOf("<s>struck</s>") !== -1 || out.indexOf("<del>struck</del>") !== -1, "strikethrough renders");
  check(out.indexOf('type="checkbox"') !== -1, "task list checkboxes render");
  check(out.indexOf("disabled") !== -1, "task list checkboxes are disabled (read only)");
  check(out.indexOf('id="title"') !== -1, "headings get anchors");
  check(out.indexOf('id="title-1"') !== -1, "duplicate headings get unique anchors");
  check(out.indexOf("katex") !== -1, "LaTeX renders through KaTeX");
  check(out.indexOf("katex-display") !== -1, "block LaTeX renders in display mode");
  check(out.indexOf("$5 and $10 today") !== -1, "currency amounts with unpaired dollars are not treated as math");
  check(out.indexOf("a lone $ sign") !== -1, "a lone dollar sign is not treated as math");
  check(resolvedRequests.length === 2, "only relative images were sent to the native resolver (" + resolvedRequests.length + ")");
  check(out.indexOf("https://appassets.androidplatform.net/res/" + "a".repeat(32)) !== -1, "resolved relative image uses the tokenised URL");
  check(out.indexOf("mk-missing-image") !== -1, "an unresolvable relative image degrades to a placeholder");
  check(out.indexOf('src="https://example.com/remote.png"') !== -1, "remote images are left to the network");
  check(out.indexOf("mk-diagram") !== -1, "mermaid/smiles blocks are handled by the diagram pipeline");
  check(
    out.indexOf("graph TD; A--&gt;B;") !== -1 || out.indexOf("graph TD;") !== -1,
    "mermaid fallback keeps the original code when it cannot draw"
  );
  check(out.indexOf("CCO") !== -1, "smiles fallback keeps the original code when it cannot draw");
  check(out.indexOf("mk-message") === -1, "no top-level error page was produced");
  check((document.getElementById("mk-status") || {}).hidden !== false, "the status line is hidden after rendering");

  console.log("\ndiagnostics reported to native (" + notes.length + "):");
  for (const note of notes.slice(0, 8)) console.log("  - " + note.slice(0, 160));

  console.log("\nresult: " + (failures.length === 0 ? "PASS" : "FAIL (" + failures.length + ")"));
  process.exit(failures.length === 0 ? 0 : 1);
})().catch((error) => {
  console.log("smoke test threw: " + (error && error.stack ? error.stack : error));
  process.exit(2);
});

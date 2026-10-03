/* Second pass: fence protection, theme switching and the error message path. */
const fs = require("fs");
const path = require("path");
const { JSDOM } = require("jsdom");
const ASSETS = process.argv[2] || "/home/xstrsx/project/MarkView/app/src/main/assets/markview";
const failures = [];
function check(c, m) { console.log((c ? "  ok   " : "  FAIL ") + m); if (!c) failures.push(m); }

const html = fs.readFileSync(path.join(ASSETS, "index.html"), "utf8");
const dom = new JSDOM(html.replace(/<script[^>]*><\/script>/g, ""), {
  url: "https://appassets.androidplatform.net/assets/markview/index.html",
  runScripts: "dangerously",
  pretendToBeVisual: true,
});
const { window } = dom;
const document = window.document;
window.MarkViewBridge = {
  resolveResource: () => JSON.stringify({ error: "not-found" }),
  openExternal: () => {}, openRelativeLink: () => {}, reportMessage: () => {},
};
for (const s of ["js/polyfill.js","vendor/markdown-it.min.js","vendor/markdown-it-task-lists.min.js","vendor/katex.min.js","vendor/mermaid.min.js","vendor/smiles-drawer.min.js","js/markview.js"]) {
  const el = document.createElement("script");
  el.textContent = fs.readFileSync(path.join(ASSETS, s), "utf8");
  document.head.appendChild(el);
}

const md = [
  "```js",
  "const price = $x$; // $$ not math",
  "```",
  "",
  "    indented $y$ code",
  "",
  "text with `inline $z$ code` here",
  "",
].join("\n");

(async () => {
  window.MarkView.setTheme("dark");
  check(document.documentElement.getAttribute("data-theme") === "dark", "setTheme('dark') switches the palette");
  window.MarkView.setTheme("light");
  check(document.documentElement.getAttribute("data-theme") === "light", "setTheme('light') switches back");

  await window.MarkView.render(md);
  const out = document.getElementById("mk-content").innerHTML;
  check(out.indexOf("katex") === -1, "no LaTeX is produced from fenced, indented or inline code");
  check(out.indexOf("$x$") !== -1, "a fenced code block keeps $...$ literally");
  check(out.indexOf("$$ not math") !== -1, "a fenced code block keeps $$ literally");
  check(out.indexOf("$y$") !== -1, "an indented code block keeps $...$ literally");
  check(out.indexOf("$z$") !== -1, "an inline code span keeps $...$ literally");

  await window.MarkView.showMessage("无法读取文件", "示例.md");
  const message = document.getElementById("mk-content");
  check(message.textContent.indexOf("无法读取文件") !== -1, "showMessage renders the native error text");
  check(message.textContent.indexOf("示例.md") !== -1, "showMessage renders the detail line");
  check((document.getElementById("mk-status") || {}).hidden !== false, "the status line stays hidden");

  console.log("\nresult: " + (failures.length === 0 ? "PASS" : "FAIL (" + failures.length + ")"));
  process.exit(failures.length === 0 ? 0 : 1);
})().catch((e) => { console.log("threw: " + (e && e.stack ? e.stack : e)); process.exit(2); });

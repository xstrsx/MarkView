/*
 * MarkView — regression tests for the second round of real-device findings.
 *
 *   MV-05  a wide structure was clipped (labels/subscripts cut off) and could not be
 *          scrolled to; the clipping happened in the *drawing* geometry, not in CSS
 *   MV-06  small molecules were magnified to fill the container (giant OH2) with large
 *          empty vertical space, because the library's square viewBox was stretched into
 *          a 500x500 element
 *
 * Two rendering passes are compared:
 *   A) with a `getBBox` stub  -> exercises the ink-measurement path a device uses
 *   B) without it             -> exercises the viewBox fallback path
 * Both must produce a padded viewBox, an explicit pixel size at a fixed unit scale, and
 * must leave Mermaid / KaTeX / ordinary images untouched.
 */
const fs = require("fs");
const path = require("path");
const { JSDOM } = require("jsdom");

// Defaults to the assets of this checkout, so the suite runs from anywhere.
const ASSETS = process.argv[2] || path.resolve(__dirname, "../../app/src/main/assets/markview");
const failures = [];
const notes = [];

function check(condition, message) {
  if (condition) console.log("  ok   " + message);
  else {
    console.log("  FAIL " + message);
    failures.push(message);
  }
}

function buildWindow(measureInk) {
  const html = fs.readFileSync(path.join(ASSETS, "index.html"), "utf8");
  const dom = new JSDOM(html.replace(/<script[^>]*><\/script>/g, ""), {
    url: "https://appassets.androidplatform.net/assets/markview/index.html",
    runScripts: "dangerously",
    pretendToBeVisual: true,
  });
  const { window } = dom;
  const document = window.document;

  // Canvas is only used by SmilesDrawer for text measurement.
  window.HTMLCanvasElement.prototype.getContext = () => ({
    font: "",
    measureText: (text) => ({
      width: String(text).length * 8,
      actualBoundingBoxLeft: 1,
      actualBoundingBoxRight: String(text).length * 8,
      actualBoundingBoxAscent: 8,
      actualBoundingBoxDescent: 2,
    }),
  });

  if (measureInk) {
    // jsdom implements no SVG layout, so emulate the engine's geometry query.
    //
    // The emulation mirrors what SmilesDrawer actually writes (verified against the
    // library source): every label is a <g style="transform: translateX(..) translateY(..)">
    // holding a <text> that has *no* x attribute, so the glyphs start at the atom and run
    // to the right unless the label is right-anchored (`text-anchor: end`). The rendered
    // font is 11pt (~14.7 user units) while the library's own bounds assume 11 units —
    // which is exactly why labels were cut off.
    const TEXT_EM = 14.7;
    const GLYPH = 8.8;
    const offsetOf = (node) => {
      const style = String(node.getAttribute("style") || "");
      const mx = /translateX\(([-\d.eE+]+)px\)/.exec(style);
      const my = /translateY\(([-\d.eE+]+)px\)/.exec(style);
      return { x: mx ? parseFloat(mx[1]) : 0, y: my ? parseFloat(my[1]) : 0 };
    };
    const boxOf = (node, tx = 0, ty = 0) => {
      const tag = String(node.tagName || "").toLowerCase();
      const offset = offsetOf(node);
      const x0 = tx + offset.x;
      const y0 = ty + offset.y;
      if (tag === "text") {
        const width = String(node.textContent || "").length * GLYPH;
        const anchoredEnd = node.getAttribute("text-anchor") === "end";
        const central = node.getAttribute("dominant-baseline") === "central";
        return {
          x: anchoredEnd ? x0 - width : x0,
          y: central ? y0 - TEXT_EM / 2 : y0 - TEXT_EM * 0.75,
          width: width,
          height: TEXT_EM,
        };
      }
      if (tag === "line") {
        const x1 = (parseFloat(node.getAttribute("x1")) || 0) + x0;
        const y1 = (parseFloat(node.getAttribute("y1")) || 0) + y0;
        const x2 = (parseFloat(node.getAttribute("x2")) || 0) + x0;
        const y2 = (parseFloat(node.getAttribute("y2")) || 0) + y0;
        return { x: Math.min(x1, x2), y: Math.min(y1, y2), width: Math.abs(x2 - x1), height: Math.abs(y2 - y1) };
      }
      if (tag === "rect") {
        return {
          x: (parseFloat(node.getAttribute("x")) || 0) + x0,
          y: (parseFloat(node.getAttribute("y")) || 0) + y0,
          width: parseFloat(node.getAttribute("width")) || 0,
          height: parseFloat(node.getAttribute("height")) || 0,
        };
      }
      if (tag === "circle") {
        const r = parseFloat(node.getAttribute("r")) || 0;
        return {
          x: (parseFloat(node.getAttribute("cx")) || 0) + x0 - r,
          y: (parseFloat(node.getAttribute("cy")) || 0) + y0 - r,
          width: 2 * r,
          height: 2 * r,
        };
      }
      if (tag === "g") {
        let union = null;
        Array.from(node.children).forEach((child) => {
          const box = boxOf(child, x0, y0);
          if (!box) return;
          if (!union) union = box;
          else {
            const minX = Math.min(union.x, box.x);
            const minY = Math.min(union.y, box.y);
            const maxX = Math.max(union.x + union.width, box.x + box.width);
            const maxY = Math.max(union.y + union.height, box.y + box.height);
            union = { x: minX, y: minY, width: maxX - minX, height: maxY - minY };
          }
        });
        return union;
      }
      return null;
    };
    window.SVGElement.prototype.getBBox = function () {
      const box = boxOf(this);
      if (!box) throw new Error("no geometry");
      return box;
    };
  } else {
    delete window.SVGElement.prototype.getBBox;
  }

  window.MarkViewBridge = {
    resolveResource: () => JSON.stringify({ error: "not-found" }),
    openExternal: () => {},
    openRelativeLink: () => {},
    reportMessage: (message) => notes.push(String(message)),
  };

  for (const script of [
    "js/polyfill.js",
    "vendor/markdown-it.min.js",
    "vendor/markdown-it-task-lists.min.js",
    "vendor/katex.min.js",
    "vendor/mermaid.min.js",
    "vendor/smiles-drawer.min.js",
    "js/markview.js",
  ]) {
    const element = document.createElement("script");
    element.textContent = fs.readFileSync(path.join(ASSETS, script), "utf8");
    document.head.appendChild(element);
  }
  return { window, document };
}

const markdown = [
  "```smiles",
  "O",
  "```",
  "",
  "```smiles",
  "[NH4+]",
  "```",
  "",
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
  "C".repeat(32),
  "```",
  "",
  "```smiles",
  "C1(",
  "```",
  "",
  "```smiles",
  "O",
  "```",
  "",
  "```mermaid",
  "graph TD; A-->B;",
  "```",
  "",
  "Formula $a^2+b^2=c^2$ stays inline.",
  "",
  "![doc](images/none.png)",
  "",
  "![remote](https://example.com/x.png)",
  "",
].join("\n");

function geometryOf(root) {
  return Array.from(root.querySelectorAll(".mk-smiles svg")).map((svg) => {
    const viewBox = String(svg.getAttribute("viewBox") || "").split(/[,\s]+/).map(Number);
    return {
      viewBox: { x: viewBox[0], y: viewBox[1], width: viewBox[2], height: viewBox[3] },
      display: {
        width: parseFloat(svg.style.width) || parseFloat(svg.getAttribute("width")) || 0,
        height: parseFloat(svg.style.height) || parseFloat(svg.getAttribute("height")) || 0,
      },
      labels: Array.from(svg.querySelectorAll("text")).map((node) => node.textContent).join("|"),
    };
  });
}

(async () => {
  const inkRun = buildWindow(true);
  await inkRun.window.MarkView.render(markdown);
  const ink = geometryOf(inkRun.document.getElementById("mk-content"));

  const fallbackRun = buildWindow(false);
  await fallbackRun.window.MarkView.render(markdown);
  const fallback = geometryOf(fallbackRun.document.getElementById("mk-content"));

  const names = ["O", "[NH4+]", "CCO", "benzene", "aspirin", "chain32", "broken C1(", "O after C1("];
  console.log("\nMV-06 small molecules keep a natural size:");
  check(ink.length === 7, "the seven valid structures are drawn, the broken one is not (" + ink.length + ")");
  names.slice(0, 5).forEach((name, index) => {
    const shape = ink[index];
    check(
      !!shape && shape.display.width > 0 && shape.display.width <= 220,
      name + " renders at a natural size, not stretched to the container (" +
        (shape ? shape.display.width : "?") + "px)",
    );
  });
  check(
    !!ink[0] && ink[0].display.height <= ink[0].display.width * 1.4 + 20,
    "water is not a giant square box (" + (ink[0] ? ink[0].display.width + "x" + ink[0].display.height : "?") + ")",
  );

  console.log("\nMV-05 wide structures keep a readable size and can scroll:");
  const chain = ink[5];
  check(!!chain && chain.display.width >= 700, "the 32-carbon chain keeps a readable width (" +
    (chain ? chain.display.width : "?") + "px, wider than any phone screen)");
  check(
    !!chain && chain.viewBox.height < chain.viewBox.width * 0.2,
    "the wide chain is not padded into a square viewBox (" +
      (chain ? Math.round(chain.viewBox.width) + "x" + Math.round(chain.viewBox.height) : "?") + " units)",
  );
  check(
    !!chain && Math.abs(chain.display.width - chain.viewBox.width * 1.2) <= 2,
    "the display size follows a fixed unit scale (viewBox x 1.2), not the container width",
  );

  console.log("\nMV-05 label ink is inside the drawing range:");
  check(
    !!ink[1] && ink[1].labels.indexOf("N") !== -1,
    "the ammonium label is present (" + (ink[1] ? JSON.stringify(ink[1].labels) : "?") + ")",
  );
  // What SmilesDrawer alone would use: bounds from atom positions only (the pre-fix state).
  const rawBox = (smiles) => {
    let tree = null;
    inkRun.window.SmilesDrawer.parse(smiles, (t) => { tree = t; }, () => {});
    const svg = inkRun.window.document.createElementNS("http://www.w3.org/2000/svg", "svg");
    new inkRun.window.SmilesDrawer.SvgDrawer({ width: 500, height: 500, scale: 1.2 })
      .draw(tree, svg, "light");
    const values = String(svg.getAttribute("viewBox")).split(/[,\s]+/).map(Number);
    return { width: values[2], height: values[3] };
  };
  [["[NH4+]", 1], ["O", 0], ["CCO", 2]].forEach(([smiles, index]) => {
    const raw = rawBox(smiles);
    check(
      !!ink[index] && ink[index].viewBox.width > raw.width + 0.5,
      "the '" + smiles + "' label ink expands the viewBox past the library's atom-only box (" +
        Math.round(raw.width) + " -> " + Math.round(ink[index].viewBox.width) + " units)",
    );
  });
  check(
    !!fallback[1] && fallback[1].viewBox.width > rawBox("[NH4+]").width,
    "the no-measurement fallback still adds padding around the library's box",
  );
  check(
    ink.every((shape) => shape.viewBox.width > 0 && shape.viewBox.height > 0 && shape.display.width > 0),
    "every structure has a valid viewBox and display size (fallback path included)",
  );
  check(
    fallback.every((shape) => shape.display.width > 0 && shape.viewBox.width > 0),
    "the fallback path also sizes every structure",
  );

  console.log("\nfailure isolation and no collateral damage:");
  const root = inkRun.document.getElementById("mk-content");
  const smileErrors = Array.from(root.querySelectorAll(".mk-diagram-error")).filter(
    (node) => node.textContent.indexOf("SMILES 结构式渲染失败") !== -1,
  );
  check(smileErrors.length === 1, "only the deliberately broken C1( fell back (" + smileErrors.length + ")");
  check(
    root.querySelectorAll(".mk-smiles svg").length === 7,
    "the structure after the broken block still rendered (failure isolation)",
  );
  const mermaidNodes = root.querySelectorAll(".mk-mermaid, .mk-diagram-error");
  check(
    Array.from(mermaidNodes).some((node) => node.textContent.indexOf("graph TD") !== -1 ||
      node.querySelector("svg") !== null),
    "the Mermaid block is still handled by the diagram pipeline",
  );
  check(root.querySelectorAll(".katex").length > 0, "KaTeX output is untouched");
  const image = root.querySelector("img");
  check(!!image && !image.closest(".mk-smiles"), "ordinary document images are not inside the SMILES rules");
  check(
    root.querySelector(".mk-missing-image") !== null,
    "an unresolvable local image still degrades to a placeholder",
  );

  const css = fs.readFileSync(path.join(ASSETS, "css/markview.css"), "utf8");
  const smilesSvgRule = (css.match(/\.mk-smiles\s*>\s*svg\s*\{[^}]*\}/) || [""])[0];
  check(/max-width:\s*none/.test(smilesSvgRule), "the SMILES svg is never capped to the container width");
  check(!/max-width:\s*100%/.test(smilesSvgRule), "no leftover max-width: 100% on the SMILES svg");
  check(/margin-left:\s*auto/.test(smilesSvgRule), "a structure narrower than the container is centred");
  check(/\.mk-smiles\s*\{[^}]*overflow-x:\s*auto/.test(css), "the SMILES container scrolls horizontally");
  check(!/touch-action\s*:/.test(css), "no touch-action restriction is declared for any diagram");
  check(/\.mk-mermaid\s*>\s*svg\s*\{[^}]*min-width:\s*100%/.test(css), "the Mermaid rules are unchanged");

  console.log("\nre-fit entry point (used after configuration changes):");
  check(
    typeof inkRun.window.MarkView.refit === "function",
    "MarkView.refit is exposed so geometry can be re-measured without re-parsing",
  );
  const before = geometryOf(root).map((shape) => shape.display.width + "x" + shape.display.height).join(",");
  inkRun.window.MarkView.refit();
  const after = geometryOf(root).map((shape) => shape.display.width + "x" + shape.display.height).join(",");
  check(before === after, "re-fitting unchanged content is idempotent (" + after.split(",").slice(0, 3).join(" ") + " …)");
  await inkRun.window.MarkView.showMessage("t", "d");
  let refitSafe = true;
  try {
    inkRun.window.MarkView.refit();
  } catch (error) {
    refitSafe = false;
  }
  check(refitSafe, "re-fitting a document with no structures does not throw");

  console.log("\ndiagnostics (" + notes.length + "):");
  for (const note of notes.slice(0, 6)) console.log("  - " + note.slice(0, 140));

  console.log("\nresult: " + (failures.length === 0 ? "PASS" : "FAIL (" + failures.length + ")"));
  process.exit(failures.length === 0 ? 0 : 1);
})().catch((error) => {
  console.log("threw: " + (error && error.stack ? error.stack : error));
  process.exit(2);
});

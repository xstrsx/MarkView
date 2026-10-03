/*
 * MarkView — regression tests for MV-15: the Gantt chart's date axis overlapped on narrow
 * screens.
 *
 * Why it happened: a Gantt chart is the one Mermaid diagram whose *layout* width is not its
 * content width. The renderer measures `parentElement.offsetWidth` and lays the whole chart
 * out for that width, while the time-axis ticks are chosen from the date span alone — so on a
 * 360px phone the same seven weekly `%Y-%m-%d` labels ended up 29px apart (labels are ~60-70px
 * wide) and printed on top of each other. `useMaxWidth: false` (MV-04) did not help, because it
 * only decides whether the finished SVG is stretched, and the container never scrolls when the
 * "natural" width equals the container width.
 *
 * The fix pins the canvas width (`gantt.useWidth`), so the container's own
 * `overflow-x: auto` policy takes over — the chart is drawn wide and scrolled, never squeezed.
 *
 * What this can prove headlessly
 *   - that the page really passes the configured width to Mermaid, and that the chart is laid
 *     out for that width instead of the container width;
 *   - that the axis ticks therefore end up far enough apart for `%Y-%m-%d` labels, twice over
 *     (360px container versus 1200px container);
 *   - that the CSS still lets a wider-than-screen diagram scroll instead of shrinking.
 * With a stubbed `offsetWidth` and `getBBox` (jsdom has neither), the tick *positions* come
 * from Mermaid itself; the label width is an approximation, and the real rendering on a device
 * still has to be looked at.
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

/** The Gantt diagram from test/MarkView-极全面功能与安全测试.md (about six weeks, weekly ticks). */
const GANTT = [
  "gantt",
  "    title A Gantt Diagram",
  "    dateFormat YYYY-MM-DD",
  "    section Section",
  "        A task          :a1, 2014-01-01, 30d",
  "        Another task    :after a1, 20d",
  "    section Another",
  "        Task in Another :2014-01-12, 12d",
  "        another task    :24d",
].join("\n");

/**
 * Builds a page the way index.html does, with the two measurements jsdom lacks: the container
 * width Mermaid reads for a Gantt chart, and text metrics for its labels.
 */
function buildWindow(containerWidth) {
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
  Object.defineProperty(window.HTMLElement.prototype, "offsetWidth", {
    get() {
      return containerWidth;
    },
    configurable: true,
  });
  // ~7px per character at the 11px axis font: the width the labels really need.
  Object.defineProperty(window.Element.prototype, "getBBox", {
    value() {
      const text = String(this.textContent || "");
      return { x: 0, y: 0, width: Math.max(1, text.length * 7), height: 14 };
    },
    configurable: true,
  });
  window.MarkViewBridge = {
    resolveResource: () => JSON.stringify({ error: "not-found" }),
    openExternal: () => {},
    openRelativeLink: () => {},
    reportMessage: (message) => notes.push(String(message)),
  };
  const configs = [];
  for (const script of SCRIPTS) {
    const element = document.createElement("script");
    element.textContent = fs.readFileSync(path.join(ASSETS, script), "utf8");
    document.head.appendChild(element);
  }
  if (window.mermaid && typeof window.mermaid.initialize === "function") {
    const realInitialize = window.mermaid.initialize;
    window.mermaid.initialize = function (config) {
      configs.push(config);
      return realInitialize.call(window.mermaid, config);
    };
  }
  const viewer = document.createElement("script");
  viewer.textContent = fs.readFileSync(path.join(ASSETS, "js/markview.js"), "utf8");
  document.head.appendChild(viewer);
  return { window, document, configs };
}

/** Renders the Gantt diagram and returns what the resulting <svg> says. */
async function renderGantt(containerWidth) {
  const { window, document, configs } = buildWindow(containerWidth);
  await window.MarkView.render("```mermaid\n" + GANTT + "\n```");
  const svg = document.querySelector(".mk-mermaid svg");
  if (!svg) return { configs, svg: null, ticks: [], spacing: NaN };
  const ticks = Array.from(svg.querySelectorAll(".tick")).map((tick) => {
    const match = /translate\((-?[\d.]+)/.exec(tick.getAttribute("transform") || "");
    return match ? parseFloat(match[1]) : NaN;
  }).filter((value) => isFinite(value));
  const steps = ticks.slice(1).map((value, index) => Math.abs(value - ticks[index]));
  return {
    configs,
    svg,
    labels: Array.from(svg.querySelectorAll(".tick text")).map((node) => node.textContent),
    ticks,
    spacing: steps.length ? Math.min(...steps) : NaN,
  };
}

(async () => {
  const phone = await renderGantt(360);
  const wide = await renderGantt(1200);

  console.log("\nMV-15 Gantt canvas width (the diagram must not be squeezed to the screen):");
  check(!!phone.svg, "the Gantt chart renders");
  if (!phone.svg) {
    console.log("\nresult: FAIL (" + failures.length + ")");
    process.exit(1);
  }

  const config = phone.configs.length ? phone.configs[phone.configs.length - 1] : null;
  check(
    !!config && !!config.gantt && config.gantt.useMaxWidth === false,
    "the Gantt keeps its natural-size policy (useMaxWidth: false)",
  );
  check(
    !!config && config.gantt.useWidth === 720,
    "and a fixed layout width for the date axis (useWidth: 720), not the container width",
  );

  const width = Number(phone.svg.getAttribute("width"));
  check(width === 720, "a 360px screen still gets a 720px-wide chart (was 360px wide)");
  check(
    Number(wide.svg.getAttribute("width")) === 720,
    "and a 1200px screen gets the same 720px chart, so a document looks the same everywhere",
  );

  const labels = phone.labels || [];
  check(labels.length >= 5 && /^\d{4}-\d{2}-\d{2}$/.test(labels[0] || ""), "the axis still shows full %Y-%m-%d dates (" + labels.length + " ticks)");
  check(isFinite(phone.spacing), "the tick positions come from the renderer (" + phone.spacing.toFixed(1) + "px apart)");
  check(
    isFinite(phone.spacing) && phone.spacing >= 70,
    "adjacent date labels now have room: " + phone.spacing.toFixed(1) + "px apart, labels ~70px wide",
  );
  check(
    isFinite(phone.spacing) && isFinite(wide.spacing) && phone.spacing >= wide.spacing * 0.9,
    "the canvas width, not the screen, decides the tick spacing (" + phone.spacing.toFixed(1) + "px vs " + wide.spacing.toFixed(1) + "px)",
  );

  const css = fs.readFileSync(path.join(ASSETS, "css/markview.css"), "utf8");
  check(/\.mk-mermaid\s*\{[^}]*overflow-x:\s*auto/.test(css), "a chart wider than the screen still scrolls horizontally");
  check(/\.mk-mermaid\s*>\s*svg\s*\{[^}]*min-width:\s*100%/.test(css), "a narrow chart still fills the width");
  check(/\.mk-mermaid\s*>\s*svg\s*\{[^}]*max-width:\s*none/.test(css), "…and is never shrunk to fit (no clipped labels)");

  console.log("\ndiagnostics (" + notes.length + "):");
  for (const note of notes.slice(0, 6)) console.log("  - " + note.slice(0, 140));

  console.log("\nresult: " + (failures.length === 0 ? "PASS" : "FAIL (" + failures.length + ")"));
  process.exit(failures.length === 0 ? 0 : 1);
})().catch((error) => {
  console.log("threw: " + (error && error.stack ? error.stack : error));
  process.exit(2);
});

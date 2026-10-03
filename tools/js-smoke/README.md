# Viewer page smoke tests (jsdom)

These two scripts load the **exact** assets that ship inside the APK
(`app/src/main/assets/markview/**`) into a headless DOM and render a document that
exercises every feature MarkView claims. They are not part of the Gradle build
(they need `jsdom`), but they are the cheapest way to prove that the bundled page
really works before touching a device.

```bash
# jsdom is not vendored: install it once, anywhere, and point NODE_PATH at it.
mkdir -p /tmp/mv-jsdom && cd /tmp/mv-jsdom
npm init -y && npm install jsdom@26.1.0
export NODE_PATH=/tmp/mv-jsdom/node_modules

ASSETS=/path/to/MarkView/app/src/main/assets/markview
for suite in smoke smoke2 smoke3 smoke4 smoke5; do
  node /path/to/MarkView/tools/js-smoke/$suite.js "$ASSETS"
done
```

(The assets path argument is optional; it defaults to the project's own
`app/src/main/assets/markview`, so the suites always test what the APK ships.)

`smoke.js` — one document covering CommonMark, GFM tables / strikethrough / task
lists, autolinks, raw HTML + `<script>` + `<svg>` escaping, heading anchors
(including duplicates), inline and block LaTeX, a `mermaid` fence, a `smiles` fence,
a resolved relative image, an unresolvable relative image and a remote image. It
asserts the produced HTML, that the document cannot inject script, and that the
native resolver is only asked about relative images.

`smoke2.js` — fence protection (`$…$` inside fenced / indented / inline code is never
turned into LaTeX), `setTheme` switching the palette, and the native error-message
path.

`smoke3.js` — regression tests for the first round of real-device findings: SMILES
rendering (`CCO`, benzene, aspirin, `O`, `[NH4+]` must produce an inline SVG with
drawing primitives while `C1(` falls back and does not break the next block),
unsupported link protocols (`mailto:` / `content://` / `intent:` become plain text,
`javascript:` / `data:` never become anchors, http(s) / `#anchor` / relative links
survive), and the Mermaid sizing configuration (`useMaxWidth: false` plus the
scrolling rules in `markview.css`). jsdom has no 2D canvas, so a minimal
`getContext('2d')` stub is installed for SmilesDrawer's text measurement — that is the
same code path the device uses, only the rasteriser is a stub.

`smoke4.js` — regression tests for the second round: SMILES geometry. It renders the same
document twice, once with a faithful `getBBox` emulation (the code path a device uses) and
once without it (the fallback path), and asserts that **both** produce a padded viewBox and
an explicit pixel size, that label ink expands the drawing range past the library's
atom-only box (the clipping fix), that a 32-carbon chain keeps a readable width and a tight
height instead of being padded into a square, that the display size follows a fixed unit
scale rather than the container width, and that Mermaid/KaTeX/ordinary images are
unaffected. The emulation mirrors what the library actually writes: each label is a
`<g style="transform: translateX(..)">` around a `<text>` with no `x` attribute, drawn at
11pt while the library's own bounds assume 11 units — the mechanism behind the clipping.

`smoke5.js` — chemistry (mhchem) regression, project book §7: loads the same asset order as
`index.html` (KaTeX **then** the mhchem companion extension) and renders CE01–CE08 plus the
wide equation, asserting the MathML that is actually produced — subscripts, charges,
`→` / `⇌`, the Chinese condition 加热 and the `(s)`/`(aq)` state marks. It also covers the
code-region boundaries (§7.4), the whole `fixtures/化学方程式测试.md` fixture (§7.1–7.3, failure
isolation included), the container CSS that is checkable without a layout engine, and — with
a second page built **without** the mhchem script — that a missing extension is reported
differently from a single bad formula.

Fixtures live in `tools/js-smoke/fixtures/` and are part of the repository, so the suites run
from a fresh clone. `smoke5.js` prefers a *local* `test/化学方程式测试.md` when one exists (that
directory is review material and is deliberately not tracked), and falls back to the shipped copy
otherwise.

`smoke6.js` — third round of real-device findings (MV-07 / MV-08 / MV-09 / MV-10 / MV-11).
It renders every writing style of `test/化学.md` and asserts which ones are display blocks and
which stay inline, that the inline wrapper only becomes a scroll container when the measurement
says it overflows (driven through the page's internal `__adjustInlineMath` hook with stubbed
metrics, because jsdom has no layout engine), the CSS contract behind the `\tag` fix, the two
failure headlines the bundled Mermaid produces (unsupported diagram type vs broken diagram —
including a drift guard that `MERMAID_VERSION` matches the version string inside the vendor
bundle), and the Android-side invariants of the intent handling, which are read straight from
`MainActivity.kt` / `WebViewFactory.kt` because they are Kotlin, not page code.

Mermaid and SmilesDrawer cannot draw in jsdom (no SVG layout engine, no 2D canvas),
so both scripts assert the documented *fallback* instead: the original code is kept
and the rest of the document still renders. That is exactly the failure isolation
required by project book §6.6.

Last run on the delivered build (1.0.7): **PASS** (25 assertions in `smoke.js`, 10 in
`smoke2.js`, 26 in `smoke3.js`, 32 in `smoke4.js`, 49 in `smoke5.js`, 48 in `smoke6.js`
— 190 in total).

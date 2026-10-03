# MarkView 第三轮问题查证记录（MV-07 ~ MV-13）

- 日期：2026-10-03
- 性质：**查证记录 + 实施记录**。第四节的"修法概要"已按你的答复落地，落地细节见
  `BUILD-NOTES.md` §12；本文保留查证证据以供复核。
- 当前状态：**MV-07 / MV-08（确定项）/ MV-09 / MV-10 / MV-11 已修复并出包 1.0.7；
  Mermaid 已升级到 11.17.2；MV-12 / MV-13 按指示不做**（结论与理由见 §7、§8、§11）。
- 当前交付基线：`MarkView-1.0.6-release.apk`（versionCode 7，versionName 1.0.6，
  SHA-256 `bb07f9931187239ef118d9b541aad7f7e0e3896790aa6f4584925762a7cbdf8d`，签名与 1.0.0 ~ 1.0.5 同一密钥）。
- 本轮输入：
  - `test/MarkView-第三轮测试错误报告.md`（问题定义与验收要求）
  - `test/MarkView-极全面功能与安全测试.md`（126,941 字节，MA-xxx 系列，用于 MV-08/MV-10 定位）
  - `test/化学.md`（2,540 字节，化学与分子结构测试文档，用于 MV-09/MV-12/MV-13 定位）
  - 5 张真机截图（`Screenshot_2026-10-03-*.jpg`）
- 查证手段：源码/资产审阅、`sha256sum` 版本比对、jsdom 加载 **APK 内完全相同的资产** 做无头复现
  （markdown-it / KaTeX+mhchem / Mermaid / SmilesDrawer 都是真实库），以及 computed style 读取。
  jsdom 无排版引擎，因此**几何/触摸类结论一律标注为"需真机"**。

---

## 1. 结论总表

| 编号 | 问题 | 查证结论 | 根因（具体位置） | 修复规模 | 本轮处理 |
| --- | --- | --- | --- | --- | --- |
| MV-07 | 桌面启动误弹分享提示 | **确认** | `MainActivity.kt` 对"非文档的新 Intent"一律弹分享失败提示 | 小 | 待你点头 |
| MV-08 | 整篇文档横向移动 | **未复现**（真实文档结构审计通过） | 唯一代码级冲突：viewport 允许缩放 vs WebView 禁缩放 | 小（若即此因） | 需设备侧数据 |
| MV-09 | 行内公式与正文基线不协调 | **确认**（两个原因） | ①`$$` 写成段落续行 → 落进行内层 ②行内层 `overflow` 改变基线 | 小（两项） | 待你点头 |
| MV-10 | 公式编号与主体重叠 | **确认 = 1.0.6 我的回归** | `.mk-math-block .katex-display{display:flex}` 使 `.katex` 收缩到公式宽度 | 小 | 待你点头 |
| MV-11 | Mermaid 泳道图失败 | **版本能力限制，非缺陷** | 打包的 mermaid 11.12.0 内没有 swimlane 模块 | 小（提示分类）／升级=大 | 提示分类待定 |
| MV-12 | 复杂分子图形拥挤 | **输入与标题不符**（"紫杉醇"输入实际不是紫杉醇） | 库的布局参数对该分子完全无效 | 大 | **不考虑** |
| MV-13 | 多行/带名称 SMILES | **合同边界**：当前 = 单块单分子 | `markview.js` 把整块文本拼成一个 SMILES | 新增契约=中 | **不考虑**（待你决定是否新增） |

---

## 2. MV-07 桌面启动误弹分享提示（确认，小改）

### 证据（代码路径唯一）

```kotlin
// MainActivity.kt:108-111
override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); handleIntent(intent, fromNewIntent = true) }

// MainActivity.kt:235-250
private fun handleIntent(intent: Intent?, fromNewIntent: Boolean) {
    when (val parsed = IntentParser.parse(intent)) {
        …
        IntentParser.Result.None ->
            if (fromNewIntent) toast(getString(R.string.toast_no_shared_file))   // ← 这里
    }
}
```

`IntentParser.parse` 只认 `ACTION_VIEW` / `ACTION_SEND`，其余（含桌面 `ACTION_MAIN`）返回 `None`。
`launchMode="singleTop"`：应用在后台/栈顶时点桌面图标即走 `onNewIntent` → 弹
`分享内容中没有可打开的文件`。这解释了报告里的"**有时**"。

### 关联发现（同属报告点名的"重复消费"）

`onCreate` 无条件 `handleIntent(getIntent())`，而配置变化（深色模式切换等）会重建 Activity 并**再次拿到同一个
ACTION_SEND / ACTION_VIEW**，`openDocumentUri(..., replaceStack = true)` 会清空文档栈 → 现象是"切换深色后
返回键丢失之前打开的文档"。报告要求检查的"历史 Intent 被当作新请求"确实存在。

### 修法概要（未实施，规模：小）

1. 分享失败提示只在 `intent.action == ACTION_SEND`（且确实没有可用附件）时给。
2. `onCreate` 仅在 `savedInstanceState == null` 时消费一次 Intent；或在实例上记录"该 Intent 已消费"。
3. 补一条 `onNewIntent` 日志：action、categories、是否有 data/ClipData/EXTRA_STREAM、生命周期入口、是否已消费
   （现有 `onCreate` 日志已包含 action/type/data/clipItems/restoredDocuments）。

---

## 3. MV-08 整篇文档横向移动（未复现，需设备数据）

### 已做的结构审计（真实文档整篇渲染，computed 无关，纯 DOM 结构）

```
输入：test/MarkView-极全面功能与安全测试.md（文件 126,941 字节；读取为 82,450 字符）
渲染后顶层块：1,596
.mk-math-block = 120   .mk-math-inline = 10（其中内含 display 公式者 = 0）
table = 7   .mk-table-scroll = 7            （相等：每个表格都已包裹）
pre = 74   .mk-mermaid = 7   .mk-smiles svg = 78
公式失败框 = 4   Mermaid/SMILES 失败框 = 52  （文档本身含大量故意失败样例）
img = 6   .mk-missing-image = 28
★ 带 nowrap 行内样式且不在滚动容器内的元素 = 0
根元素未使用 overflow-x:hidden（符合报告"不得以它为唯一修复"的要求）
```

≥45 字符的不可断 token 共 130 个，逐个核对后**没有**真正的撑宽候选：

| 长度 | 位置 | 判定 |
| --- | --- | --- |
| 272 | KaTeX 的隐藏 `<annotation>`（在 1px 裁剪的 `.katex-mathml` 内，且位于 `.mk-math-block`） | 不参与布局 |
| 136 / 106 | mermaid 渲染出的 SVG 内 `<style>` 元素文本 | 非渲染文本 |
| 113 | 中文段落 | 可换行 |

结论：**所有可能变宽的内容（公式/表格/代码/图表/SMILES/图片）都有各自的 `overflow-x: auto` 容器；
滚动容器的最小内容宽度为 0，不会撑宽根页面。** 因此在本机无法复现"整页可向左滑"，也无法凭空指出撑宽元素。

### 唯一找到的代码级不一致（正是报告排查项 3）

```
index.html:7            content="width=device-width, initial-scale=1, maximum-scale=5, user-scalable=yes"
WebViewFactory.kt:51-53 setSupportZoom(false) / builtInZoomControls(false) / displayZoomControls(false)
```

页面显式**允许**缩放，而 WebView 设置本意禁止缩放。若某 WebView 版本以 viewport 为准，双指缩放后即可整页平移——
与"无缩放假设下正文超宽"矛盾（报告本身也要求先区分"WebView 缩放后的平移"和"系统侧滑手势"）。

### 需要的输入（二选一）

1. 复现信息：是否**未缩放**即溢出？哪个文档/哪一段？最好附最小复现；或
2. 允许我加一条**只写日志**的探针（不改变任何布局）：输出
   `document.scrollingElement.scrollWidth / clientWidth`，并列出
   `getBoundingClientRect().right > documentElement.clientWidth` 的最宽元素，跑一次给 logcat。

**在拿到其中之一前不做盲目的 CSS 改动。** 可先做的独立小改：viewport 改为
`maximum-scale=1, user-scalable=no`（与 `setSupportZoom(false)` 语义一致；可访问性仍由系统字体缩放
`textZoom` 提供，符合原项目书"正文跟随系统字体缩放"）。

---

## 4. MV-09 行内公式基线偏高（确认，两个原因，均小改）

### 触发条件（`化学.md` 的实际写法）

```markdown
水分子合成：$$\ce{2H2 + O2 -> 2H2O}$$

钠与水反应（包含气体符号）：
$$\ce{2Na + 2H2O -> 2NaOH + H2 ^}$$
```

后一种是 `$$…$$` **紧跟在文字行的下一行且没有空行**。markdown-it 的段落规则把该行当作**段落续行**吞掉，
而块级公式规则只在"块起点"被调用 → 走不到块级路径。四种写法实测（真实资产）：

| 写法 | `.mk-math-block` | `.mk-math-inline` | 结果 |
| --- | --- | --- | --- |
| A 上一行是文字，`$$…$$` 独占下一行（**化学.md 的写法**） | 0 | 1 | 与文字同一段，落入行内层 |
| B 前面有空行（标准块级写法） | 1 | 0 | 正常走块级 |
| C 同行 `文字：$$…$$` | 0 | 1 | 行内层 |
| D 同行 `文字：$…$` | 0 | 1 | 行内层 |

整篇 `化学.md` 渲染结果：`.mk-math-block = 0`、`.mk-math-inline = 15`（该文档共 15 个公式，
含 §3 列表项内的公式）、**行内层内含 `.katex-display` = 0**。

### 根因

1. **主因（抬高）**：`.mk-math-inline { display:inline-block; overflow-x:auto; overflow-y:hidden; padding:0.15em 0 }`
   （**1.0.6 才加的 overflow**）。CSS 2.1 §10.8.1：inline-block 的基线是其最后一个行盒的基线，
   **但当 `overflow` 计算值不是 `visible` 时，基线取下外边距边缘**。抬高量 ≈ KaTeX 深度 0.2em
   + 底部 padding 0.15em + 半行距 ≈ 0.4em，与截图中"化学式明显高于相邻中文"一致，也解释了
   标签与公式挤在同一视觉行。
2. **次因（版式降级）**：`mv_math_inline` 的 renderer 忽略 token 上记录的 `displayMode`，一律
   `renderFormula(content, false)` → 行内 `$$` 被静默当**行内**版式排版（原增量项目书把 `$$` 定为块级）。

### 修法概要（未实施，规模：小）

1. **分类修正（已验证可行，一行）**：给现有块级规则注册加 alt 链：

   ```js
   instance.block.ruler.before("fence", "markview_math_block", rule,
     { alt: ["paragraph", "reference", "blockquote", "list"] });
   ```

   实测：加 `alt` 前 0 个块级公式、加 `alt` 后 **2/2 全部识别为块级**（同一版 markdown-it）。
2. **行内基线修正**：默认行内包装层**不用滚动容器**（`display: inline`，基线自然）；渲染后在同一帧内
   （`innerHTML` 之后、绘制之前）测量 `scrollWidth > clientWidth`，只给真正超宽的公式加
   `.mk-math-inline-scroll`，保留化学增量书 §5.1"超宽可局部查看"的要求。
3. **可选**（若保留"同行 `$$` 走行内"）：让 renderer 尊重 `displayMode`，并配
   `.mk-math-inline .katex-display { margin: 0 }`，避免 display 版式撑坏行高。

---

## 5. MV-10 公式编号与主体重叠（确认 = 1.0.6 我的回归，小改）

### 证据（无头渲染 + computed style）

```
输入                                   .mk-math-block  .tag      tag 文本       tag 父元素
E=mc^2\tag{EQ-01}                      true            true      "(EQ-01)"      .katex-html
a=b\tag*{custom}                       true            true      "custom"       .katex-html

.tag           computed: position=absolute  right=0px
tag 父元素      computed: .katex-html → display=block  position=relative
.katex-display computed: display=flex                    ← 来自我的 CSS（1.0.6）
.katex         computed: flex=0 0 auto  margin-left=auto ← 来自我的 CSS（1.0.6）
```

KaTeX 自身规则（`css/katex.min.css`）：

```
.katex-display { display:block; margin:1em 0; text-align:center }
.katex-display>.katex { display:block; text-align:center; white-space:nowrap }
.katex-display>.katex>.katex-html { display:block; position:relative }
.katex-display>.katex>.katex-html>.tag { position:absolute; right:0 }
```

tag 的定位基准是 `.katex-html` 的右缘，而 `.katex-html` 宽度来自 `.katex`。我的 flex 规则（1.0.6 为修
"居中溢出左侧滚不回来"而加）使 `.katex` 收缩为 **max-content（公式自身宽度）** → `right:0` 的 tag
恰好落在公式右端 → 叠字。截图 MA-045（`EQ-01` 压 `E=mc^2`）、MA-046（`custom` 压 `a=b`）完全吻合；
1.0.5 及更早无 flex，tag 落在**容器**右缘，所以不叠。

### 修法概要（未实施，规模：小）

删除这两条 flex 规则：

```css
.mk-math-block .katex-display { display: flex }                 /* 删除 */
.mk-math-block .katex-display > .katex { flex: 0 0 auto; margin-left: auto; margin-right: auto }  /* 删除 */
```

改为：

```css
.mk-math-block .katex-display { display: block; width: max-content; min-width: 100%; margin: 0.3em 0 }
```

- 装得下：`.katex` 仍占满可用宽度 → tag 靠容器右缘、公式居中，不叠；
- 装不下：按内容宽度**只向右**溢出 → 最左端仍可滚到（保住增量书 §5.3 的要求）。

长公式 + tag 的窄屏策略需单独定（可选：JS 检测到 `.tag` 存在时给公式预留右侧空间）。
MA-047 与 `mathllap/mathrlap/mathclap`：本工程没有任何规则会作用于它们，同意报告判定（非缺陷）。

---

## 6. MV-11 Mermaid 泳道图（版本能力限制；升级属大改，已排除）

### 证据（加载 APK 内真实 `mermaid.min.js`，先 `initialize` 与 App 内一致）

```
bundle 内版本字符串：11.12.0
bundle 内 "swimlane" / "swimlanes" / "lane" 出现次数：0 / 0 / 0

detectType("graph TD; A-->B;")        -> "flowchart"        （对照，正常）
detectType("flowchart LR …")          -> "flowchart-v2"     （对照，正常）
detectType("sequenceDiagram …")       -> "sequence"         （对照，正常）
detectType("swimlane-beta LR …")      -> null
detectType("swimlanes LR …")          -> null

mermaid.render(泳道图) 抛：No diagram type detected matching given configuration for text: swimlane-beta LR
mermaid.render(普通 graph) 抛：u.getBBox is not a function   （jsdom 无排版，属另一类失败）
```

结论：**打包的 11.12.0 构建里没有泳道图模块**（不是配置或语法问题）；两类失败的错误文本**可区分**。

### 处理

- 升级 mermaid = 大改（全量图表回归 + 主题/安全等级复核）→ 按指示**不做**。
- 可做小改：回退标题区分"当前版本不支持该图表类型（mermaid 11.12.0）"与"语法/渲染失败"。
- 交付说明需列明：mermaid 实际版本 11.12.0、不支持的类型清单（泳道图 swimlanes/swimlane-beta）。

---

## 7. MV-12 复杂分子交叉拥挤（输入与标题不符；布局改进属大改，已排除）

### 输入已取得（`化学.md` §2.6），但**不是紫杉醇**

```smiles
CC1=C2[C@@]([C@]([C@H]([C@@H]([C@H](C2(C)C)OC(=O)C)O)OC(=O)C)(C(=O)C3=CC=CC=C3)O)([C@H](C1)OC(=O)C)C
```

按 SMILES 字面统计（非完整化学解析）：长度 100、**C≈27、O=9、N=0 → 约 36 个重原子**。
紫杉醇 paclitaxel 为 **C47H51NO14（62 个重原子，必含 1 个 N）**。
→ 报告列出的"原始输入本身不对应标题中的分子名称"这一可能性成立；标题与输入不一致也使
"结构是否正确"失去参照，因此**不据截图判定连接正确性**。

### 布局定量（真实库，`smiles-drawer` 包版本 2.1.7；`SmilesDrawer.Version` 返回 "1.0.0" 是库内部遗留常量）

| 参数 | ok | 重叠分 | bonds | labels | viewBox |
| --- | --- | --- | --- | --- | --- |
| 默认（当前调用只传 width/height/scale） | true | **7.19** | 110 | 13 | ≈240×189 |
| `overlapResolutionIterations=5` | true | **7.19** | 111 | 13 | 同默认 |
| `overlapResolutionIterations=20` | true | **7.19** | 111 | 13 | 同默认 |
| `overlapSensitivity=0.3` | true | **7.19** | 110 | 13 | 同默认 |
| `compactDrawing=false` | true | **7.19** | 110 | 13 | 同默认 |
| `bondLength=45` | true | **7.19** | 110 | 13 | ≈349×271（仅等比放大） |

小分子对照（CCO / 苯 / 顺反二氟乙烯 / L-丙氨酸）：重叠分 0.00，`iterations=20` 后仍 0.00。

结论：**库的重叠消解与灵敏度参数对该分子毫无影响，单纯放大只等比缩放而不消除碰撞**（与报告预判一致）。
要真正改善布局质量需换算法/换库 = **大改 → 不考虑**。渲染本身成功、未裁切；拥挤属库的布局质量限制。

---

## 8. MV-13 多行 / 带名称 SMILES（合同边界；逐行支持属新契约，已排除）

当前实现（`app/src/main/assets/markview/js/markview.js:725`）：

```js
var source = (codeNode.textContent || "").replace(/\s+/g, " ").trim();   // 整块 → 一个 SMILES
```

即 **一个 `smiles` 围栏 = 一个分子**；多行块必然整块失败（截图中的 3 行块、5 行块一致）。
原需求/增量项目书只写"识别 `smiles` 围栏并渲染结构"，**未承诺**逐行记录或名称列。

### 按报告 §9 完成的逐条复测（每条独立围栏，真实库）

| 输入 | 结果 |
| --- | --- |
| `CCO` / `c1ccccc1` / 阿司匹林 / 咖啡因 / 布洛芬（含 `[C@@H]` 手性） | 解析并绘出 |
| `[Na+].[Cl-]`（盐，点号两组分） | 解析并绘出 |
| `[13CH4]`（同位素） | 解析并绘出 |
| `c1ccccc1O`（苯酚） | 解析并绘出 |
| `F/C=C\F` / `F/C=C/F`（顺反） | 解析并绘出 |
| `C1CC`（缺环闭合） | **库不报错**，也绘出 → 报告"第一块应局部失败"在库层面不成立 |
| `CCO 乙醇` / `c1ccccc1 苯`（SMILES + 名称同一行） | 失败（名称不是 SMILES） |

结论：合并输入失败**不能**证明盐/同位素/立体化学不受支持（单独都能画）。

### 若要做逐行 + 名称列（新契约，规模：中 → 本轮不考虑）

要点：按行拆分、首列为 SMILES、其余为普通文本名称（`textContent` 插入，禁 HTML 注入）、
每条独立解析/绘图/降级、兼容 CRLF/空行/中文名称、**不把表示断开组分的 `.` 拆成多条记录**。
需要你明确是否新增该契约；不新增则维持现状并在交付说明中写明"单块单分子"。

---

## 9. 待决清单与处理结果

你的答复是："mermaid 升级 11.17.2，其余按本文小改修改，大改不动，页面关闭缩放手势。"逐条落点：

| # | 待决项 | 处理 |
| --- | --- | --- |
| 1 | MV-07 / MV-09 / MV-10 三项小改 | **已修**（改动点见 §11） |
| 2 | MV-08：先做 viewport 对齐 | **已做**（viewport 改为 `maximum-scale=1, user-scalable=no`，与 `setSupportZoom(false)` 一致；`textZoom` 保留）。**根因仍待设备侧数据**，见 §3 的"需要的输入" |
| 3 | MV-11 提示分类 | **已做**，并**升级 mermaid 到 11.17.2**（升级后 `swimlane-beta` 直接可用，不再只是提示） |
| 4 | MV-13 逐行 + 名称列契约 | **不做**（大改/新契约，按"大改不动"） |
| 5 | 把复现脚本固化到 `tools/` | **不做**：改为新增 `tools/js-smoke/smoke6.js`（第三轮断言，随工程一并交付），临时目录里的一次性排查脚本不入库 |

---

## 10. 未测 / 未复现声明

- **无设备**：所有真机触摸滚动与裁切、字体回退观感、旋转/深色/大字体下的实际布局，本轮均未验证；
  上述"结构"结论来自 DOM/计算样式与真实库的无头复现，**不等于**真机几何已验证。
- **MV-08 未复现**：结构审计通过（没有"滚动容器之外的不可断行块"），但 jsdom 无排版，
  不能据此断言真机不存在溢出；需要 §3 所列的输入或探针数据。
- 本轮**没有**采用报告禁止的手段：未隐藏错误提示、未裁掉溢出内容、未删除公式标签、
  未放松安全策略（`trust:false`、CSP、协议拦截、SAF 边界均未动）、未用根级 `overflow-x:hidden` 掩盖溢出。
- 附：本轮所有结论的复现方式（可在有 Node 的环境重跑）——把 APK 内
  `assets/markview/**` 加载进 jsdom，依次验证：`detectType/parse/render`（Mermaid 类型能力）、
  `SmilesDrawer.parse + SvgDrawer.draw + getTotalOverlapScore`（SMILES 能力与布局参数）、
  `MarkView.render` 后统计 `.mk-math-block/.mk-math-inline/.mk-table-scroll/...` 与
  `getComputedStyle`（公式分类、tag 定位、行内包装层 overflow）。

---

## 11. 本轮实施结果（1.0.7 / versionCode 8）

| 项 | 文件 | 改动 |
| --- | --- | --- |
| MV-07 | `MainActivity.kt` | 分享提示只在 `ACTION_SEND` 时给出；`recreated`（配置变化重建）时不再消费启动 Intent；`onNewIntent` 补 action / categories / 附件 / 重建标志 / 文档栈深度日志 |
| MV-08 | `index.html` | viewport → `width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no`，并加注释说明它与 `WebViewFactory.setSupportZoom(false)` 的分工 |
| MV-09 | `js/markview.js`、`css/markview.css` | 块级公式规则注册加 `alt` 链；行内包装层去掉 `overflow`，新增 `.mk-math-inline-scroll` 与 `adjustInlineMath()`（渲染后、字体就绪、`refit()` 各测一次） |
| MV-10 | `css/markview.css` | 删除 `.katex-display{display:flex}` 与 `>.katex{flex:0 0 auto;margin:auto}`，改为 `display:block; width:max-content; min-width:100%` |
| MV-11 | `vendor/mermaid.min.js`、`js/markview.js` | mermaid 11.12.0 → 11.17.2（npm tarball，逐字节比对外加 registry 完整性校验）；配置加 `swimlane:{useMaxWidth:false}`；新增 `mermaidFailureLabel()` 与 `MERMAID_VERSION` |
| MV-12 / MV-13 | — | 未做（大改 / 新契约） |

本机验证（**无设备**）：

```text
./gradlew test             → 单元测试 66 次执行，0 失败
./gradlew lint             → 0 error，1 warning（OldTargetApi，与上一版一致）
./gradlew assembleRelease  → MarkView-1.0.7-release.apk（3,503,238 字节，versionCode 8）
zipalign -c 4              → Verification successful
apksigner verify           → v2 + v3 + v4（v4 需显式给 .idsig）
sha256sum                  → b08efd3785c5f9be22e042f326dde16db8765612778f5dd14af72fa0ca366658
tools/js-smoke/*（6 套）    → 190 条断言全部通过（新增 smoke6 = 48 条，专测本轮五项）
mermaid 11.17.2 tarball    → 与 npm registry 的 sha512/sha1 一致，且与 APK 内文件逐字节相同
```

**仍未验证（需真机）**：MV-07 的实际行为（提示是否消失、文档栈是否保住）、
MV-08 的根因与"未缩放仍溢出"是否消失、MV-09 的真实基线、MV-10 的 tag 实际位置、
MV-11 泳道图的实际渲染与升级后其它图表的观感。清单见 `BUILD-NOTES.md` §9。

**本轮明确未做**：mermaid 升级到 12.x（体积翻倍、引入 `new Worker`、用到需 Chrome 117+ 的
`Object.groupBy`）；MV-12 的布局算法替换；MV-13 的逐行 + 名称契约；MV-08 在拿到设备数据前的盲目 CSS 改动。

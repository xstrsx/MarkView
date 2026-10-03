# MarkView

**Android Markdown 只读查看器。** 打开、阅读，仅此而已。

解析与渲染全部在设备本地完成：所有渲染器（Markdown、公式、图表、化学结构式）都打包在 APK 内，
安装后**断网可用**——只有文档里明确引用的 `http(s)` 图片才会产生网络请求。没有账号、没有上传、
没有遥测，也不修改用户的任何文件。

| 项目 | 值 |
| --- | --- |
| 当前版本 | `1.0.9`（versionCode 10） |
| 包名 | `com.markview.viewer` |
| 平台 | minSdk 26（Android 8.0）/ targetSdk 35 / compileSdk 36 |

---

## 一、软件介绍

### 定位

给"手机上有 Markdown 文档、想安静读完"的人：技术笔记、项目 README、实验/化学记录、
带公式与图表的说明文档。它在系统里注册为 Markdown 文件的打开方式，也可以在自己的主页里
授权一个文件夹后浏览阅读；**只读**是设计前提，不是缺失的功能。

与通用 Markdown 编辑器的区别：不编辑、不同步、不联网渲染；页面级 CSP 只允许 APK 内的脚本与样式，
文档本身无法引入任何远程代码。

### 主要特性

**阅读体验**

- CommonMark + GFM：表格、删除线、只读任务列表（`- [x]`）、自动链接；标题自动生成锚点，
  同名标题去重，正文内 `#锚点` 可跳转。
- 浅色 / 深色跟随系统，页面自带两套调色板，不用系统强制反色。
- 正文字号跟随系统字体缩放（`WebSettings.textZoom`），不是写死的固定字号。
- 图片：文档相对路径经 SAF 解析后加载，`data:` 与 `http(s)` 引用原样保留；**缺图降级为占位符**
  并继续渲染其余内容，不会出现空白或中断。
- 表格、公式、图表、结构式各自在**自己的容器内**横向滚动，不把整页撑宽。

**数学与化学公式**

- KaTeX 0.16.22：行内 `$…$` 与块级 `$$…$$`（含"文字行 + 独占一行 `$$…$$`"的写法，会正确成为块级
  居中公式）；支持 `\tag{...}` 公式编号（编号靠右，不压公式）；超宽公式在容器内滚动，最左端可滚到。
- mhchem 3.3.0：`\ce{...}` 化学式与方程式——下标、电荷、`→` / `⇌`、物态 `(s)/(g)/(aq)/(l)`、
  中文反应条件（如"加热"）。
- 失败隔离：某一条公式出错只降级该条（保留原文并标注原因），其余公式与正文照常渲染；
  日志里区分"扩展缺失"与"单条语法错误"。

**图表**

- Mermaid 11.17.2，覆盖 20 种图类型：flowchart、sequence、class、state、ER、gantt、journey、pie、
  gitGraph、mindmap、timeline、quadrant、requirement、block、packet、kanban、radar、architecture、
  **swimlane 泳道图（`swimlane-beta`）**等。
- 图表按自然尺寸绘制后由容器决定"铺满宽度"还是"横向滚动"，不会把标签缩小到看不清；
  甘特图按固定画布宽度（720px）排版后由容器横向滚动，因此窄屏上底部日期刻度不会互相重叠。
- 本包未实现的图类型会明确提示 **"Mermaid 不支持该图表类型（mermaid 11.17.2）"**，与"渲染失败"
  分开；两种情况都保留原始代码并把真实错误写入 logcat。

**化学结构式**

- `smiles` 围栏 → 内联 SVG（SmilesDrawer 2.1.7）。
- 绘制后测量真实墨迹（原子、标签、电荷、下标）重新计算 `viewBox` 与像素尺寸：标签不被裁切；
  采用固定单位比例（1 绘制单位 = 1.2px）而不是"缩放到容器宽度"，因此小分子清晰、长链可横向滚动；
  旋转与系统字体变化后会重新测量。

**文件访问（SAF）**

- 授权文件夹或直接打开单个文件；父子目录同时被授权时**外层目录优先**（避免同一文档出现两条访问路径）。
- 相对链接与相对图片只在已授权的目录树内解析，并要求"文件名 + 大小一致且候选唯一"，否则按缺图降级。
- **系统入口也能用**：用系统"打开方式/分享"传入的文档，即使来自另一个 provider（文件管理器自带的
  FileProvider、下载 provider、分享的临时副本），只要内容与已授权目录里的某个文件一致（同名 +
  摘要相同），就会把相对资源基准绑到该目录 —— 因此与"应用内打开"得到同样的图片与链接。
- 链接策略：`http(s)` 交给系统浏览器；`javascript:` / `data:` / `file:` / `content:` / `intent:` /
  `mailto:` 等一律**降级为纯文本**（不可点击），原生导航拦截作为第二道防线。

**安全与隐私**

- 页面级 CSP 白名单（`default-src 'none'`、`script-src 'self'`、`connect-src 'none'`、
  `worker-src 'none'` …）+ WebView 设置双保险（禁 `file://`、禁 content provider、禁多窗口、
  禁地理定位/数据库、关闭页面缩放手势）。
- 三个渲染器与字体全部离线打包，运行时不下载任何脚本、样式或字体；没有 `eval`、动态 `import()`、
  `blob:`、`new Worker`。
- 只读：不修改、不删除、不上传用户文件；诊断日志只写本机 logcat（有上限，不记录文档正文）。

### 非目标

编辑与保存、多标签/多窗口、云同步、在线渲染服务、PDF/HTML 导出、插件系统。

---

## 二、技术栈

### 总览

| 层次 | 采用 | 版本 |
| --- | --- | --- |
| 语言 | Kotlin | 2.2.21 |
| 构建 | Gradle（Wrapper 已提交）/ Android Gradle Plugin / JDK | 8.14.3 / 8.13.2 / 17 |
| SDK | compileSdk / targetSdk / minSdk | 36 / 35 / 26 |
| UI | 单 Activity + Android View 体系（XML 布局），不使用 Compose | — |
| 并发 | Kotlin Coroutines（`Dispatchers.Main.immediate`） | 1.10.2 |
| AndroidX | `core-ktx` / `activity-ktx` / `documentfile` / `webkit` | 1.17.0 / 1.12.4 / 1.1.0 / 1.14.0 |
| 页面层 | 原生 HTML + CSS + ES5（无前端框架、无打包器、无 CDN） | — |
| Markdown 渲染 | markdown-it（MIT）+ markdown-it-task-lists（ISC） | 14.3.2 / 2.1.1 |
| 数学 / 化学公式 | KaTeX（MIT）+ mhchem 扩展（MIT） | 0.16.22 / 3.3.0 |
| 图表 | Mermaid（MIT） | 11.17.2 |
| 化学结构式 | SmilesDrawer（MIT） | 2.1.7 |
| Android 单元测试 | JUnit4 | 4.13.2 |
| Android 仪器测试 | `androidx.test.ext:junit` / Espresso | 1.3.0 / 3.7.0 |
| 离线页面回归（仅开发期） | Node + jsdom | jsdom 26.1.0 |

- **依赖全部固定精确版本**（无 `+`/动态版本），渲染器版本由 `tools/js-smoke/smoke6.js` 等断言守住，
  避免"静默换包后提示与实际不符"。
- **不使用** Compose、Hilt/Dagger、Retrofit/OkHttp、Room、WebView 的远程加载、前端框架与 CDN。
- 第三方许可文本（KaTeX / mhchem）随 APK 打包在 `assets/markview/vendor/LICENSE-*.txt`。

### 为什么是"原生 Activity + WebView 里的原生页面"

Markdown 生态（Markdown 解析、数学排版、图表、结构式）在 Web 侧最成熟且可完全离线；
把页面当作**受控的本地渲染沙箱**，用 CSP + `WebViewAssetLoader` 限制它只能加载 APK 内的资源，
就能在不引入 Web 服务器、不联网的前提下拿到一致的排版效果。文件访问、权限、生命周期、
返回栈这些"设备相关"的部分则完全交给 Kotlin 原生代码。

### 关键实现点

| 实现点 | 做法 |
| --- | --- |
| 资产访问 | `WebViewAssetLoader` 把 `assets/markview/**` 映射为 `https://appassets.androidplatform.net/...`，其余路径与 `res/<随机 token>` 走自实现的注册表 |
| 文档栈 | 单个 WebView 复用，文档经 JS 桥推送渲染；原生维护文档栈（系统返回键逐层回退） |
| 主题 | `MainActivity` 侧同步系统深色状态 → `MarkView.setTheme()`；WebView 不做算法反色 |
| 字体缩放 | 系统 `fontScale` → `WebSettings.textZoom`，配置变化后重新测量结构式（`MarkView.refit()`） |
| 相对资源 | 页面把相对引用交给原生桥，由 SAF 在已授权目录树内解析后就地替换，绝不打开任意路径 |
| 意图分流 | 只接受 `ACTION_VIEW` / 单文件 `ACTION_SEND`；多文件与纯文本分享被拒绝并给出说明 |
| 失败隔离 | 每个渲染器（公式/图表/结构式）单独降级，绝不让一个坏块清空整篇文档 |

### 工程结构

```text
app/src/main/java/com/markview/viewer/      Kotlin（原生层）
  MainActivity.kt          单 Activity：页面切换、返回栈、意图分流、文档栈持久化
  HomeController.kt        主页：授权文件夹列表、添加与长按移除
  ViewerController.kt      查看页：WebView 生命周期、渲染调度、错误展示、主题与字体同步
  SafFolderStore.kt        授权文件夹持久化（SharedPreferences + JSON）
  SafDocumentResolver.kt   SAF：文档上下文、父目录定位、相对路径解析（外层目录优先）
  SafUris.kt               tree/文档 URI 的人类可读描述
  MarkdownDocument.kt      DocumentContext、快照、原生文档栈
  IntentParser.kt          ACTION_VIEW / ACTION_SEND 解析（仅单文件）
  MarkdownFileType.kt      扩展名与 MIME 识别
  UrlPolicy.kt             引用策略（纯 JDK，可单测）
  TreePathPolicy.kt        SAF 树与授权边界判定（纯 JDK，可单测）
  ResourceRegistry.kt      本地资源随机 token 注册表
  MarkViewAssetLoader.kt   资产加载器：仅 /assets/ 与 /res/<token>
  MarkViewWebViewClient.kt 请求拦截、导航策略、渲染进程崩溃兜底
  MarkViewBridge.kt        页面 → 原生的受控入口（解析资源、外链、相对链接、日志）
  WebViewFactory.kt        WebView 安全设置、缩放策略与字体缩放

app/src/main/assets/markview/               页面层（离线）
  index.html               CSP 与脚本加载顺序（mhchem 必须在 KaTeX 之后、viewer 之前）
  css/markview.css         浅色/深色样式与滚动容器
  css/katex.min.css + fonts/  KaTeX 样式与 20 个 woff2 字体
  vendor/                  markdown-it / task-lists / katex / katex-mhchem / mermaid / smiles-drawer
  js/polyfill.js           老 WebView 的特性兜底（structuredClone、Object.hasOwn…）
  js/markview.js           markdown-it 配置、公式规则、Mermaid/SMILES 几何、链接与图片降级

app/src/test/                纯 JVM 单元测试
app/src/androidTest/         清单入口点测试（需真机/模拟器）
tools/js-smoke/              页面回归套件（7 套 211 条断言，需要 Node）
tools/js-smoke/fixtures/     回归套件自带的夹具（化学方程式测试.md）
tools/icon-preview.py        用标准库把启动图标渲染成 PNG 预览
```

以上即仓库的全部内容。构建产物、本机配置、签名材料、构建缓存与评审素材**不纳入版本控制**，
由 `.gitignore` 约束（该文件本身就是仓库内容，需要时直接看它）。

---

## 三、构建

需要 JDK 17 与 Android SDK（按 Gradle 的常规方式指向 SDK；本机配置不纳入版本控制）。

```bash
./gradlew test               # 单元测试（debug + release 两个变体）
./gradlew lint               # Android Lint
./gradlew assembleRelease    # 生成签名 release APK
./gradlew assembleDebugAndroidTest   # 只编译仪器测试源码（运行需真机）
```

release 签名参数由构建机本地提供（环境变量 `MARKVIEW_KEYSTORE_FILE` /
`MARKVIEW_KEYSTORE_PASSWORD` / `MARKVIEW_KEY_ALIAS` / `MARKVIEW_KEY_PASSWORD`，
或同名配置由本地持有，二者都不纳入版本控制）；没有签名参数时构建仍可完成，
但产物视为未签名、不可用于交付。

## 四、测试

| 层次 | 内容 | 现状 |
| --- | --- | --- |
| 单元测试 | URL 策略、SAF 树边界、文件类型、MIME、内容同一性规则 | 39 个用例 × 2 变体 = 78 次执行，0 失败 |
| Lint | Android Lint | 0 error，1 warning（`OldTargetApi`，targetSdk 由项目书固定为 35） |
| 页面回归 | `tools/js-smoke/`：基础渲染、围栏与主题、首轮问题、第二轮几何、化学方程式、第三/四轮问题、甘特图日期轴 | 7 套 211 条断言，全绿 |
| 仪器测试 | 清单入口点接线 | 源码可编译 |
| 真机人工验证 | 触摸滚动、裁切、字体回退观感、缩放行为、系统入口的相对资源 | 已在真机上执行并**全部通过**（用户确认）；逐项清单见 `BUILD-NOTES.md` §9 |

页面回归直接加载 **APK 内完全相同的 assets** 到 jsdom 中运行，命令见 `tools/js-smoke/README.md`：

```bash
export NODE_PATH=/path/to/jsdom/node_modules     # 需要 jsdom 26.x
for suite in smoke smoke2 smoke3 smoke4 smoke5 smoke6; do
  node tools/js-smoke/$suite.js app/src/main/assets/markview
done
```

## 五、发布

- **版本规则**：`versionName` 递增且 `versionCode` 严格单调递增（同一 `applicationId` 才能覆盖升级）。
- **产物**：`./gradlew assembleRelease` 生成签名 release APK（以及用于增量安装的 v4 旁挂签名文件）；
  构建产物不纳入版本控制。
- **发布前校验**（Build-Tools 36.0.0；尖括号为构建产物路径）：

```bash
$ANDROID_HOME/build-tools/36.0.0/zipalign -c -v 4 <release-apk>
$ANDROID_HOME/build-tools/36.0.0/apksigner verify --print-certs <release-apk>
$ANDROID_HOME/build-tools/36.0.0/apksigner verify \
    --v4-signature-file <v4-signature> <release-apk>
sha256sum <release-apk>          # 结果追加到 SHA256.txt
```

- **签名材料**：签名配置与密钥库由构建机本地持有并离线备份，不纳入版本控制；
  口令不得写入仓库、产物或任何文档（发布前用脚本扫描确认）。
- **哈希记录**：`SHA256.txt` 记录每个交付版本的 SHA-256 与版本替代关系（该文件是仓库内容）。
- **标记发布**：每个交付版本提交后打一个 annotate tag（当前 `v1.0.8`）。

## 六、已知限制

- **Mermaid 11.17.2，完全离线**：支持 `swimlane-beta` 等 20 种图类型。本包不含的类型
  （例如另一种拼写 `swimlanes`）会明确提示"不支持该图表类型（mermaid 11.17.2）"并保留原始代码，
  不会静默失败；升级只能随 APK 发布（运行时不下载任何脚本）。
- **`$$…$$`**：独占一行的写法（含紧跟在文字行之后、列表项与引用块内）按块级公式渲染并居中；
  写在句子中间的 `$$…$$` 仍按行内公式渲染（避免把句子切断）。
- **SMILES**：一个 `smiles` 围栏渲染一个分子（不解析逐行多分子，也不解析"SMILES + 名称"两列写法）。
- **相对资源仍会缺失的情况**：文件不在任何已授权目录内；系统送来的内容与已授权目录里那份
  **字节不同**；同一内容在多个已授权目录里都存在（拒绝猜测）；文件在已授权目录内**层级超过 8**
  或目录条目超过 3000。这些情况一律"正文照常显示 + 图片显示占位符"，不会猜错目录。
- **文件名被改写的副本**（如 `文档 (1).md`）不会被匹配到授权目录里的原文件，这是有意为之。
- **复杂分子**：数十个原子以上时，库自身的布局可能出现交叉拥挤，本包不做几何美化；过宽时横向滚动查看。
- **页面缩放手势已关闭**：与 WebView 设置一致（`user-scalable=no` + `setSupportZoom(false)`），
  字号由系统字体缩放统一控制；开启整页缩放会与容器内横向滚动形成同方向嵌套滚动，见 `BUILD-NOTES.md` §11。
- **真机行为**（触摸滚动、裁切、字体回退观感、WebView 缩放、系统入口的相对资源）已在真机上
  验证并全部通过（用户确认）；本仓库保留的是可自动复核的证据（单元测试与页面回归），
  逐项清单见 `BUILD-NOTES.md` §9。

## 七、文档索引

| 文档 | 内容 |
| --- | --- |
| `MarkView-一次落地项目书.md` | 产品定义、验收标准、安全与签名要求（需求基线） |
| `BUILD-NOTES.md` | 每个交付版本的构建、签名、根因分析、已执行检查与真机验证清单 |
| `MarkView-第三轮查证记录.md` | 第三轮问题的逐条查证证据与实施结果（第四轮 MV-14 见 `BUILD-NOTES.md` §13） |
| `tools/js-smoke/README.md` | 页面回归套件的说明与运行方式 |
| `tools/js-smoke/smoke7.js` | 甘特图日期轴的宽度回归（MV-15） |
| `tools/js-smoke/fixtures/` | 回归套件自带夹具（化学方程式测试） |
| `SHA256.txt` | 各交付版本的 SHA-256 与版本替代关系 |
| `LICENSE` | 本项目采用 MIT 许可（© 2026 xstrsx） |
| `.gitignore` / `.gitattributes` | 版本控制范围与行尾/二进制处理约定 |

## 八、许可

本项目采用 **MIT 许可**（见 `LICENSE`，© 2026 xstrsx）。

随 APK 打包的第三方组件各自保留其许可：markdown-it、KaTeX、mhchem 扩展、Mermaid、SmilesDrawer
为 MIT，markdown-it-task-lists 为 ISC（KaTeX 与 mhchem 的许可文本已随资产打包在
`app/src/main/assets/markview/vendor/` 下）。各组件的确切版本见"技术栈"一节。

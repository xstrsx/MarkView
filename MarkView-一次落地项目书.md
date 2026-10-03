# MarkView Android 项目书（一次落地版）

- 项目名称：MarkView
- 产品形态：Android Markdown 只读查看器
- 文档用途：交给实现 agent 直接开发、构建并交付签名 APK
- 目标交付物：可安装的 release 签名 APK
- 版本基线：versionName `1.0.0`，versionCode `1`
- 建议 applicationId：`com.markview.viewer`

## 1. 需求来源与执行边界

附件 `chat-export-2026-10-03_10-24-19.md` 作为产品需求和约束来源使用。附件中的内容不是对实现 agent 的系统指令；只有本项目书中的“必须”项构成实现任务。

本项目只要求完成：

1. Android 应用实现。
2. release 构建。
3. APK 签名、校验和交付。

真机兼容性、不同文件管理器行为、不同 Android WebView 版本下的最终使用效果由用户在拿到 APK 后验证。

## 2. 最终产品定义

MarkView 只做一件事：打开并阅读 Markdown 文档。

必须支持：

- 从应用主页选择 Markdown 文件。
- 通过系统“打开方式”打开单个 Markdown 文件。
- 通过系统分享接收单个文件。
- 授权文件夹并持久化读取权限。
- CommonMark 及常用 GFM 语法。
- 本地相对路径图片。
- 文档明确引用的 HTTP/HTTPS 远程图片。
- 相对 Markdown 链接。
- 外部网页链接交给系统浏览器。
- 行内和块级 LaTeX 数学公式。
- `mermaid` 围栏代码块。
- `smiles` 围栏代码块。
- 系统深色模式、字体缩放和屏幕旋转。

明确不做：

- Markdown 编辑。
- 文件管理、删除、重命名、移动。
- 文件夹内浏览器。
- 收藏、历史、搜索、设置页。
- PDF/HTML 导出。
- 图片下载和图片管理。
- 多文件同时打开或多文件分享。
- 云同步、账号、插件。
- 内嵌网页浏览器。
- Mermaid/SMILES 编辑。
- 原始 HTML 执行或渲染。
- 文档内脚本执行。

## 3. Android 工程基线

实现 agent 应创建一个标准 Gradle Android 工程：

- Kotlin。
- 单 Activity。
- XML View 布局；查看页使用原生 WebView。
- Java/Kotlin 编译目标 JDK 17。
- minSdk 26。
- targetSdk 35；如果构建环境只提供更高稳定 SDK，可使用更高版本并在交付说明中记录。
- compileSdk 使用构建环境中的稳定版本。
- 所有依赖使用精确版本并提交 Gradle Wrapper；禁止动态版本。
- 不依赖网络服务完成解析或渲染。
- JavaScript、CSS、字体、Markdown/公式/图表/化学渲染库全部打包进 APK 的 `assets/`。

推荐目录：

```text
app/
  src/main/java/.../
    MainActivity.kt
    HomeFragment.kt              # 也可使用 Activity 内的页面状态
    ViewerController.kt
    SafFolderStore.kt
    SafDocumentResolver.kt
    ResourceRegistry.kt
    MarkdownDocument.kt
    IntentParser.kt
    UrlPolicy.kt
    WebViewFactory.kt
    MarkViewWebViewClient.kt
  src/main/res/layout/
  src/main/res/values/
  src/main/assets/markview/
    index.html
    css/
    js/
    vendor/
  src/test/
  src/androidTest/
```

可以调整类名和目录，但职责必须保持清晰，不能把所有逻辑塞入 Activity。

## 4. 页面和交互

### 4.1 主页

主页保留系统状态栏和导航栏，不放应用工具栏。

页面只有：

```text
已授权文件夹列表

文件夹名称
文件夹位置

文件夹名称
文件夹位置

[添加文件夹]
[打开文件]
```

要求：

- 授权文件夹列表只展示授权状态，不可点击进入。
- 每项显示名称和可读位置；无法读取位置时显示“位置不可用”。
- 长按列表项立即移除记录，并调用 `releasePersistableUriPermission()` 释放读取权限。
- 同一文件夹重复添加不能产生重复项。
- 授权失效时保留列表项并标记“不可用”。
- 不增加复杂确认弹窗、侧边栏或菜单。

### 4.2 添加文件夹

调用：

- `ACTION_OPEN_DOCUMENT_TREE`
- 立即调用 `takePersistableUriPermission(uri, FLAG_GRANT_READ_URI_PERMISSION)`
- 只保存读取权限。

持久化内容至少包括：

- tree URI 字符串。
- 显示名称。
- 添加时间。
- 最近一次可用状态。

使用 DataStore Preferences 或 SharedPreferences 均可，不引入数据库。

启动时检查：

- URI 是否仍在 `contentResolver.persistedUriPermissions` 中。
- `DocumentFile.fromTreeUri()` 是否可读。

失效的授权记录继续显示，直到用户长按移除。

### 4.3 打开文件

调用 `ACTION_OPEN_DOCUMENT`：

- `CATEGORY_OPENABLE`
- 单选，禁止多选。
- MIME 类型优先使用 `text/markdown`、`text/x-markdown`、`text/plain`。
- 为兼容错误的文件管理器，可增加 `*/*` 兜底。
- 结果根据文件名和 MIME 类型判断。

接受的典型扩展名：

- `.md`
- `.markdown`
- `.mdown`
- `.mkdn`

接受的典型 MIME：

- `text/markdown`
- `text/x-markdown`
- `text/plain`

选择后直接进入查看页。单个文件无法识别为 Markdown 时，用 Toast 或页面内简单文本提示，不设计错误管理界面。

### 4.4 外部打开和分享

支持：

- `ACTION_VIEW` + 单个 `content://` URI。
- `ACTION_SEND` + `EXTRA_STREAM`。
- `ClipData` 中只有一个 URI 时可接受。

不支持：

- 多文件。
- 只有 `EXTRA_TEXT` 的普通文本分享作为主要入口。

必须在 Manifest 中正确声明 intent-filter、`android:exported` 和 `android:grantUriPermissions`。

外部 URI 可能是临时 URI。正文能够读取即可显示；无法确认源文件所在目录时，不承诺相对图片和相对 Markdown 链接可用。

## 5. 文档读取和 SAF 规则

### 5.1 文档模型

每个打开的文档建立一个 `DocumentContext`，至少包含：

- 原始 URI。
- 显示文件名。
- MIME 类型。
- 是否来自已授权 tree。
- 可选的父目录 URI。
- 可选的授权 tree URI。
- 当前文档的资源注册表。

不能把 `content://` URI 强行转换成传统绝对路径，也不能使用 `file://` 访问 SAF 文件。

### 5.2 相对路径解析

路径解析必须：

1. 先进行 URL 解码，兼容中文、空格和百分号编码。
2. 统一路径分隔符。
3. 拒绝绝对路径、URI scheme、空字节和不安全协议。
4. 规范化 `.` 和 `..`。
5. 不允许解析结果越过用户授权的 tree 根目录。
6. 通过 `ContentResolver`/SAF 读取，不能绕过授权范围。

能够确定父目录时，支持：

```text
image.png
./images/image.png
../images/image.png
```

### 5.3 父目录定位策略

SAF 不保证任何文件 URI 都能直接得到父目录。实现采用以下顺序：

1. 如果文档 URI 与某个已授权 tree URI 能可靠关联，直接使用该 tree 导航。
2. 如果不能直接关联，在 IO 协程中通过 `DocumentsContract` 的子文档查询，从匹配的授权 tree 根目录递归查找当前文档的 document ID，并缓存父目录关系。
3. 递归查找必须在后台执行，不能阻塞主线程。
4. 找不到父目录、权限失效、文件提供方不支持遍历时，正文仍正常显示，相对资源返回缺失状态。

这是一条明确的产品行为：**相对资源尽力加载；无法确认文件位置时，不影响正文阅读。**

### 5.4 本地资源桥接

使用 `WebViewAssetLoader` 或等价的受控 `WebResourceResponse` 方案：

- WebView 页面只加载 APK 内的本地 HTML/JS/CSS。
- 每个文档的本地资源注册为随机 token。
- 页面请求 `appassets.androidplatform.net` 下的资源 URL 时，由应用从 `ContentResolver` 打开输入流。
- 返回准确 MIME 类型，SVG 使用 `image/svg+xml`。
- 文档切换时清理旧 token 和资源注册。
- 资源注册表不能接受网页任意构造的外部 URI。

WebView 设置：

- `javaScriptEnabled = true`，因为公式、Mermaid、SMILES 使用本地 JS。
- `allowFileAccess = false`。
- `allowContentAccess = false`，本地内容统一走受控桥接。
- 禁止访问任意文件 URL。
- 禁止通用文件 URL 跨域访问。
- 禁止网页导航离开 MarkView 的查看页。

## 6. Markdown 转换和渲染

### 6.1 基础解析

推荐在 WebView 中使用随 APK 打包的 Markdown-it 或等价 CommonMark/GFM 解析器，配置：

- CommonMark。
- 表格。
- 删除线。
- 任务列表。
- 自动链接。
- 围栏代码块。
- 标题锚点。
- 原始 HTML 关闭：`html: false`。

任务列表只读显示，复选框必须设置为 disabled，不允许修改文档。

### 6.2 原始 HTML 和 SVG

原始 HTML 必须被转义或按普通文本处理：

```html
<div>内容</div>
<span>内容</span>
<svg>...</svg>
<script>...</script>
```

这些标签不得执行、不得作为 HTML 元素显示。

Markdown 图片引用的 SVG 文件可以显示，因为它是受控的图片资源。文档直接写入的 SVG 代码不显示为图形。

### 6.3 LaTeX

支持：

- 行内：`$a^2+b^2=c^2$`
- 块级：`$$ ... $$`

使用随 APK 打包的 KaTeX 或等价离线公式库：

- `throwOnError: false`
- `trust: false`
- 不支持完整 `.tex` 文档、宏包和工程编译。
- 公式失败时显示原始公式文本或简单失败状态，不影响其他内容。

### 6.4 Mermaid

只识别语言标记为 `mermaid` 的围栏代码块。

使用随 APK 打包的 Mermaid：

- `startOnLoad: false`
- `securityLevel: "strict"`
- 由应用控制容器和渲染调用。
- 解析失败时保留原始代码或显示失败状态。
- Mermaid 生成的 SVG 可以显示。
- 不提供编辑、导出和交互式编辑功能。

### 6.5 SMILES

只识别语言标记为 `smiles` 的围栏代码块。

使用随 APK 打包的 SmilesDrawer 或等价离线二维结构式库：

- 支持常见 SMILES 和二维结构显示。
- 不支持三维、反应编辑、化学计算和分子搜索。
- 失败时保留原始代码或显示失败状态。
- 生成的 SVG 属于受控渲染结果，可以显示。

### 6.6 失败隔离

任一图片、公式、Mermaid、SMILES 或单个链接失败：

- 不得导致整篇文档空白。
- 保留原始代码、替代文本或简单失败状态。
- 不增加复杂错误页面。

## 7. WebView 安全和网络规则

HTML 页面必须使用内容安全策略，目标是：

- 默认禁止所有来源。
- 脚本只允许 APK 内置脚本。
- 样式只允许 APK 内置样式和必要的内联样式。
- 图片允许本地受控资源、`data:`（如实现需要）以及文档明确引用的 HTTP/HTTPS。
- 字体只允许 APK 内置字体或 data URL。
- 禁止 `javascript:`、`file:`、`content:`、`intent:` 等不受控地址。

链接规则：

- `http://` 和 `https://` 普通链接交给系统浏览器。
- 不在 MarkView 内打开网页。
- `#anchor` 仅在当前文档内跳转。
- 相对 Markdown 链接交给原生资源解析器，成功后在 MarkView 内打开。
- 其他相对链接保持失败或普通链接状态。

远程图片：

- 只有 Markdown 明确引用远程图片时才触发网络请求。
- 不加载 CDN、远程脚本、远程 CSS 或远程 Markdown。
- 允许 HTTP 图片时，将 WebView 混合内容策略和 CSP 配置为只满足图片加载需要。
- 网络失败显示缺图状态，正文继续显示。
- 不提供网络开关、确认弹窗或图片缓存管理。

## 8. 查看页

查看页要求：

- 无应用工具栏。
- 无标题栏。
- 无悬浮按钮。
- 无常驻菜单。
- 只显示渲染后的 Markdown。
- 保留系统状态栏和导航栏。
- 不要求沉浸式全屏。

样式要求：

- 跟随系统深色模式。
- 正文、链接、代码、表格、公式、Mermaid、SMILES 均有深色样式。
- 普通图片保持原始颜色。
- 正文跟随系统字体缩放。
- 代码使用等宽字体。
- 长代码块和宽表格允许局部横向滚动。
- 图表按可用宽度缩放。
- 段落、列表、标题和引用保持易读间距。
- 旋转屏幕后继续显示当前文档。

返回行为：

1. 相对 Markdown 链接打开的文档加入原生文档栈。
2. 系统返回键先返回上一个文档。
3. 已无文档栈时返回主页。
4. 主页再按系统正常行为退出。

不要求恢复精确滚动位置；如果实现成本可接受，可保存每个文档的滚动位置。

## 9. 代码安全和数据边界

必须做到：

- 所有 Markdown 文本经过转义和安全渲染。
- 原始 HTML 不执行。
- 文档不能注入脚本。
- 文档不能读取授权范围外的文件。
- 文档不能构造任意 `content://` 访问。
- 外部网页不能嵌入 MarkView。
- WebView 不能访问任意本地文件。
- 本地资源响应使用正确 MIME 类型。
- 文件、URI、网络异常均不能导致崩溃。

不要求：

- 加密文件。
- 账号权限系统。
- 远程内容安全扫描。
- 文档内容上传。

## 10. 依赖和资源打包

实现 agent 可以调整库，但必须遵守：

- Markdown 解析、KaTeX、Mermaid、SMILES 库必须离线打包。
- 依赖版本固定并记录。
- 构建时不能从 CDN 下载运行时资源。
- APK 安装后关闭网络也能打开本地 Markdown、公式、Mermaid、SMILES 和本地图片。
- 不要把完整 Node.js 运行时放进 APK。
- 如果使用 npm 构建前端资源，提交 lockfile，并把构建后的静态资源复制到 Android `assets/`。

## 11. 实现顺序

按以下顺序完成，避免后期重构：

1. 创建工程、Manifest、单 Activity 和主页骨架。
2. 完成文件选择、外部打开、分享入口和 Markdown 文件识别。
3. 完成授权文件夹列表、持久化读取权限和长按移除。
4. 完成 WebView 本地页面、Markdown 基础渲染和原始 HTML 禁用。
5. 完成 WebView 安全策略、外部链接拦截和相对链接处理。
6. 完成 SAF 父目录定位、本地资源 token 注册和图片响应。
7. 接入 KaTeX、Mermaid、SMILES，并实现单项失败隔离。
8. 完成深色样式、字体缩放、旋转和返回栈。
9. 添加必要的单元测试、lint 和 release 构建。
10. 使用 release 密钥签名、校验 APK 并交付。

## 12. 必须具备的自动化检查

实现 agent 在交付前至少执行：

```text
./gradlew test
./gradlew lint
./gradlew assembleRelease
```

然后执行：

- `zipalign` 校验。
- `apksigner verify --verbose` 校验。
- 读取 APK 的 package name、versionName、versionCode。
- 计算 APK SHA-256。
- 确认 APK 是 release 签名，不是 debug 签名。
- 确认 APK 可以被 Android Package Manager 识别为可安装包。

没有必要为了本项目构建完整的自动化真机兼容性测试；真机验证由用户后续完成。

## 13. 签名和交付要求

### 13.1 签名

优先使用用户提供的 release keystore。

如果用户没有提供：

1. 创建正式 release keystore，而不是使用 debug keystore。
2. 签名配置通过本地 `keystore.properties` 或环境变量注入。
3. 密码和私钥不得写入 Git、APK 资源或项目书。
4. 使用同一密钥完成本次 APK 签名，并保留密钥备份以便以后发布升级版。
5. 启用 Android Gradle Plugin 支持的标准签名方案，至少包含 v2；按构建工具能力启用 v3/v4。

项目源码中不得提交真实密码。若为了让用户后续升级，必须把 keystore 安全保存并在交付说明中注明其位置或备份责任。

### 13.2 最低交付物

至少交付一个文件：

```text
MarkView-1.0.0-release.apk
```

同时在交付消息中给出：

- APK 的绝对路径。
- applicationId。
- versionName/versionCode。
- SHA-256。
- 签名校验结果。
- 构建使用的 compileSdk、Gradle、AGP、JDK 版本。

可以附带 `SHA256.txt` 和构建说明，但不能以它们替代 APK。

## 14. 实现 agent 的交付判定

实现 agent 完成以下条件后即可交付，不等待用户做功能验收：

- 工程能够干净构建。
- release APK 已签名。
- APK 不是 debug 签名。
- Manifest 含主页、打开文件、打开方式和单文件分享入口。
- 运行时资源全部在 APK 内，未依赖 CDN。
- 单个渲染组件失败不会让页面整体崩溃。
- 已执行测试、lint、assembleRelease 和签名校验。
- 已给出 APK 路径、版本和 SHA-256。

## 15. 用户拿到 APK 后的验证范围

以下内容留给用户进行真机验证：

- 不同 Android 版本和不同 WebView 版本。
- 不同文件管理器的 MIME 类型行为。
- 授权文件夹重启、手机重启后的持久性。
- 中文、空格、URL 编码和多级目录。
- 外部分享临时 URI。
- 相对图片和相对 Markdown 链接。
- 深色模式、字体缩放、旋转和返回栈。
- 公式、Mermaid、SMILES 的实际兼容范围。
- HTTP/HTTPS 远程图片和网络失败状态。
- 特定设备上的表格、代码块和图表布局。

发现问题后，优先按“可复现输入、设备型号、Android/WebView 版本、来源 URI 类型、期望行为、实际行为”记录，便于后续修复。

## 16. 交付前的最终输出模板

实现 agent 最终回复使用以下信息：

```text
MarkView 已完成 release 构建。

APK：
<绝对路径>

applicationId：
com.markview.viewer

版本：
1.0.0 (1)

签名：
release，apksigner verify 通过

SHA-256：
<哈希>

构建环境：
JDK <版本>
Gradle <版本>
AGP <版本>
compileSdk <版本>

已执行：
test / lint / assembleRelease / zipalign / apksigner verify
```


# MarkView 1.0.9 构建与交付说明

对应项目书：`MarkView-一次落地项目书.md`（§12 自动化检查、§13 签名与交付要求），
`test/MarkView-首轮测试错误报告.md`（MV-01 ~ MV-04）、
`test/MarkView-第二轮测试错误报告.md`（MV-05、MV-06）与
`test/MarkView-第三轮测试错误报告.md`（MV-07 ~ MV-13）。

1.0.7 修复 **MV-07 / MV-08 / MV-09 / MV-10 / MV-11**，并把打包的 Mermaid 从 11.12.0 升级到
11.17.2；**MV-12 / MV-13 按指示不做**（大改 / 新契约），理由与证据见 §12。

1.0.8 修复 **MV-14**（系统"打开方式 / 分享"打开的文档无法加载相对图片与内联文件，根因是
跨 provider 无法关联已授权 tree），并落地三项小改动（持久化授权只在确认为 Markdown 且不被
tree 覆盖时写入、只把真正的 tree 授权当作解析基准、文档离开文档栈时释放自己的授权）；见 §13。

1.0.9 修复 **MV-15**（竖屏时甘特图底部日期刻度重叠）：甘特图的排版宽度取自容器宽度，而刻度
疏密只由时间跨度决定，于是窄屏上 7 个 `%Y-%m-%d` 标签被压到 29px 间距；改为固定画布宽度
`gantt.useWidth = 720`，由容器横向滚动承接（与 MV-04 同一策略）；见 §14。
逐条查证过程（含无头复现命令与结论）另见 `MarkView-第三轮查证记录.md`。

> **仓库内容说明**：`test/`（各轮测试报告、真机截图与评审夹具）是本地评审素材，**不入库**；
> 离线回归套件真正读取的夹具副本在 `tools/js-smoke/fixtures/`。签名材料（`keystore.properties`、
> `*.jks`）、APK 产物（`*.apk`/`*.idsig`）、`local.properties` 与构建缓存同样不入库
> （清单见 `README.md` 的"工程结构"一节）。

## 1. 交付物

| 文件 | 说明 |
| --- | --- |
| `MarkView-1.0.9-release.apk` | **本次交付的 release 签名 APK** |
| `MarkView-1.0.9-release.apk.idsig` | APK Signature Scheme v4 旁挂文件，仅 `adb install --incremental` 需要；不安装也能正常使用 |
| `SHA256.txt` | 上列文件的 SHA-256（并保留 1.0.0 ~ 1.0.8 的哈希供比对） |
| 源码 | 本目录的完整 Gradle 工程（含 Wrapper） |

绝对路径：

```text
MarkView-1.0.9-release.apk（相对仓库根目录）
```

- applicationId：`com.markview.viewer`（与 1.0.0 ~ 1.0.8 相同，可直接覆盖升级）
- versionName / versionCode：`1.0.9` / `10`
- APK 大小：3,508,582 字节
- SHA-256：`fc37d09b1853d1e2ccb8c4326f64786925a40f68d9f886be06124be2d866086a`
- minSdk 26 / targetSdk 35 / compileSdk 36

1.0.0 ~ 1.0.7 的 APK 仅保留作对照，**请安装 1.0.9**（后装旧版会被系统视为降级；
1.0.3 的图标是被回退掉的 Markdown 标记版本）。

## 2. 签名

```bash
# 本次实际执行的校验命令（Build-Tools 36.0.0）
$ANDROID_HOME/build-tools/36.0.0/zipalign -c -v 4 MarkView-1.0.7-release.apk
$ANDROID_HOME/build-tools/36.0.0/apksigner verify --print-certs MarkView-1.0.7-release.apk
$ANDROID_HOME/build-tools/36.0.0/apksigner verify \
    --v4-signature-file MarkView-1.0.7-release.apk.idsig MarkView-1.0.7-release.apk
```

```text
Verifies
Verified using v1 scheme (JAR signing): false
Verified using v2 scheme (APK Signature Scheme v2): true
Verified using v3 scheme (APK Signature Scheme v3): true
Verified using v4 scheme (APK Signature Scheme v4): true   (需 --v4-signature-file 指定 .idsig)
Number of signers: 1
Signer #1 certificate DN: CN=MarkView Release, OU=MarkView, O=MarkView, L=Unknown, ST=Unknown, C=CN
Signer #1 key algorithm: RSA (4096 bit)
Signer #1 certificate SHA-256 digest: b0aef553d91efe8ec8bc5e5810a99306501081d2d694138c0b40c01a997e3249
```

- **与 1.0.0 ~ 1.0.8 使用同一把 release 密钥**（证书 SHA-256 完全一致），可覆盖升级；
  未换成 debug 密钥，密钥未缺失。
- v2 / v3 / v4 均通过；v1 报告为 false 属预期（minSdk 26，AGP 不写入 v1）。
  注意 v4 只有在命令行显式给出 `.idsig`（如上）时才校验；不带该参数时报告为 false，这不是签名问题。

### 密钥库位置与备份责任（重要）

| 项目 | 位置 |
| --- | --- |
| 密钥库 | `markview-release.jks`（仓库根目录，别名 `markview`，4096 位 RSA；**不纳入版本控制**） |
| 口令 | 仓库根目录的签名配置文件（已被 `.gitignore` 忽略；构建机本地持有） |

**请立即备份这两个文件。** 丢失后将无法再为 `com.markview.viewer` 发布升级包。
源码、APK 资源与项目书中都没有写入任何口令。

## 3. 构建环境

```text
JDK            OpenJDK 17.0.20.1
Gradle         8.14.3（Wrapper 已提交）
AGP            8.13.2
Kotlin         2.2.21
compileSdk     36（platforms/android-36）
targetSdk      35
minSdk         26
Build-Tools    35.0.0（AGP 自动选用）+ 36.0.0（用于 zipalign / apksigner 校验）
```

## 4. 第二轮问题的根因与修复（MV-05 / MV-06）

两项都由**绘图几何**引起，不是单纯的外层 CSS 问题。已在无设备条件下用
`tools/js-smoke/`（加载与 APK 内完全一致的 assets）复现、定量并回归。

### 实际根因（两项同源）

SmilesDrawer 2.1.7 的两个默认行为叠加：

1. **`scale` 默认为 0 → 强制正方形 viewBox。**
   库只按原子位置计算外接框并加 10 单位内边距，`updateViewbox(0)` 再把
   `width/height` 取二者最大值补成正方形。实测（`tools/js-smoke` 可直接复现）：

   | 结构 | 库给出的 viewBox（默认） | 库给出的 viewBox（`scale > 0`） |
   | --- | --- | --- |
   | `O`（水） | 28.8 × 28.8 | 28 × 28.8 |
   | `CCO`（乙醇） | 80 × 80（实际内容仅 ~39 高） | 80 × 39.4 |
   | 32 个碳的长链 | 825.4 × 825.4（实际内容仅 ~35 高） | 825.4 × 35 |

   同时 SVG 元素的 `width/height` 属性被设为 `opts.width/height`（500×500），于是：
   - 小分子：内容被放大到 500 ÷ 28.8 ≈ **17 倍** → 巨大的 `OH₂` 标签；
   - 宽分子/长链：viewBox 被补成 825×825，渲染进 500×500 的方框 → 上下巨额空白，
     同时内容被压到 0.6 倍 → 又小又空。
   这正是 MV-06 的"异常放大 + 留白过多"。1.0.1 的 CSS 用 `max-width: 100%` 把元素压到容器宽度，
   只是把这个"方框"缩小，放大的内容与留白一并保留。

2. **viewBox 只看原子顶点，不看标签墨迹；标签从原子位置向右绘制。**
   库把每个标签写成一个
   `<g style="transform: translateX(原子x) translateY(原子y)"><text>…</text></g>`，
   `<text>` **没有 `x` 属性**（只有 `direction === 'left'` 时设 `text-anchor: end`），
   即标签默认从原子位置**向右**排；而 `<style>` 里写死 `font: 11pt`
   （≈14.67 个用户单位），比布局假定的 `fontSizeLarge = 11` 单位大 ~33%。
   于是多字标签（`NH₄⁺`、`COOH`）向右伸出原子 10 单位的内边距之外，被 **viewBox（SVG 视口）裁掉**：
   这就是 MV-05 里"铵离子右侧被截断"的原因，也是为什么"单加 `overflow-x` 无法恢复"——
   被裁掉的内容并不存在于布局中，根本无法滚动出来。

### 修复内容

**绘图尺寸**

- 传入 `scale = 1.2`（CSS px / 绘图单位）→ 库给出**紧致、非正方形**的 viewBox，
  并跳过正方形补齐；键长 30 单位 ≈ 36px、标签 11pt ≈ 17px，与正文字号接近且**不随容器宽度变化**
  （不再"按分子外接框无上限放大到容器宽度"）。
- 绘制完成后，在元素已挂载的前提下用 `getBBox()` 取所有可见 `<g>`（键、标签、电荷、下标）的
  **真实墨迹并集**，再按该并集重算 viewBox 与显示尺寸；
  边距为 `max(10 单位, 12% × min(宽,高))` —— 用**较小边**取比例，
  避免宽分子（825×35）被加上数百单位的纵向空白。
- 显示尺寸 = 加边后的 viewBox × 1.2，四舍五入为整数 CSS px，直接写在元素上（行内样式 + 属性）。
- 对极端输入有一道显示尺寸上限保护（超过 4000 单位才等比缩小），仅用于防止病态数据撑爆布局。
- 若环境无法测量（`getBBox` 不可用），保留库自身的外接框并加同样边距降级，不会崩溃、不会缩小。

**容器高度与滚动**

- 高度由"加边后的墨迹高度 × 1.2"决定，不再来自固定的大画布 → 去掉了无意义留白。
- `.mk-smiles { overflow-x: auto; overscroll-behavior-x: contain; }`；
  `.mk-smiles > svg { max-width: none; margin-left/right: auto; display: block; }`。
  于是：**窄结构**按自然尺寸**居中**显示；**超宽结构**保持可读尺寸并在结构式容器内**横向滚动**，
  端点原子、电荷与下标都能滑到。
- 未使用 `touch-action`、未禁用任何方向的触摸：容器没有纵向溢出，纵向手势仍交给页面滚动
  （`overscroll-behavior-x: contain` 只约束横向）。
- 新增 `MarkView.refit()`：配置变化（系统字体缩放等）后重新测量并更新几何，不复用失效尺寸；
  `ViewerController.onConfigurationChanged()` 会调用它。横竖屏切换不影响尺寸（尺寸是固定的 px，
  与容器宽度无关），只影响居中或滚动。

**未受影响（回归已覆盖）**：Mermaid 容器与规则、KaTeX 公式、普通 Markdown 图片的样式均未改动
（所有新规则都限定在 `.mk-smiles` / `.mk-smiles > svg` 下）。

### MV-05 的第一项要求：裁切发生阶段

结论是**绘图阶段**（库的 viewBox 与标签锚点），不是外层布局：
`overflow-x` 只能滚动**存在于布局中**的内容，而被 viewBox 裁掉的部分不在其中。
因此先修正绘图范围（墨迹并集 + 边距 + 非正方形 viewBox），再让容器滚动。

## 5. 授权目录选择规则：父子同时授权时，外层目录优先

### 现象（1.0.4 及更早）

同时授权一个目录和它的父目录时，两条记录**各自独立**保存、各自独立标记可用、各自独立移除
（只按完全相同的 tree URI 去重，父子是不同 URI）。打开文档时，代码按
`contentResolver.persistedUriPermissions` 的顺序逐条尝试，**第一条能建立关联的立即生效**。

由于能同时匹配同一文档的两条授权必然是**嵌套**关系，而嵌套关系对应两条长度不同的祖先链
（外层目录的链更长），「谁排在前」就决定了该文档的相对资源基准：

| 文档 | 胜出者 | `../images/x.png` |
| --- | --- | --- |
| `docs/sub/a.md` | 授权了 `docs/sub`（子） | **缺图**（`..` 越过该 tree 根，日志 `escapes the authorised tree root`） |
| `docs/sub/a.md` | 授权了 `docs`（父） | 正常解析到 `docs/images/x.png` |

同层与更深层的引用两种归属结果一致，只有**向上走**的引用会不同；而系统返回授权列表的顺序
（内部为哈希结构）并不保证等于添加顺序，所以结果对用户不可预期。

### 现在的规则

**在所有能匹配该文档的已授权 tree 中，选择最外层（祖先链最长）的那一条**，
于是「父目录优先」，且结果不再依赖系统返回顺序。

实现要点（`SafDocumentResolver.computeTreeAssociation`）：

1. **第一遍（无需遍历）**：对所有同 authority 的 tree 依次尝试「document id 相等」与
   「层级 id 推导路径」，**收集全部命中**而不在首个命中处返回，用纯函数
   `TreePathPolicy.preferShallower` 保留链最长者；若已有命中，直接返回，**不做任何目录遍历**
   （常见路径的成本与之前相同）。
2. **第二遍（有界遍历）**：仅当第一遍完全没有命中时，才对未命中的 tree 做原来的有界遍历
   （深度 ≤ 8、条目 ≤ 3000，带缓存），同样在命中的树之间取链最长者。
3. **兜底**：两遍都没有精确命中时，才使用「内容同一性」唯一候选（逻辑与阈值未变）。
4. 结果按文档 URI 缓存（`associationCache` / `noAssociationCache`），每个文档只算一次。

判定依据：同一 provider 内 document id 唯一，因此**两条不嵌套的授权不可能同时包含同一个文档**；
嵌套时内层 tree 给出的祖先链一定更短。所以「链最长」＝「tree 最外层」，
规则确定、可单测，且不需要把 `content://` 转成路径、也不按文件名猜测。

### 明确未改动的部分

- **列表依然显示两条记录**（父、子各一行，位置分别为 `内部存储/docs` 与 `内部存储/docs/sub`），
  不做嵌套合并、不做「被父目录覆盖」的置灰；长按仍只移除被长按的那一条并只释放该 URI 权限。
- 移除父目录后，子目录里的文档仍可打开（经子授权）；但此时基准变为子目录，`../` 会随之失效
  —— 这是移除授权后的正常结果。
- 安全边界不变：任何情况下都不会越出所匹配 tree 的根，也不会访问未授权目录。

### 设备侧确认方式

打开「父子都已授权」目录下的文档后查看 logcat：

```text
tree lookup: ... persistedTrees=2
tree decision: 2 matching tree(s), chose content://.../tree/primary%3Adocs match=DERIVED_PATH chainDepth=3 (outermost tree preferred)
resolve: ... tree=content://.../tree/primary%3Adocs match=DERIVED_PATH chainDepth=3
```

`chainDepth` 大于 1 即表示胜出的是外层目录（文档位于其子目录中）。若只授权了子目录，
`chainDepth=1` 且 `../` 引用会出现 `escapes the authorised tree root` —— 属预期。

### 新增单元测试

`TreePathPolicyTest` 新增 4 个用例：嵌套时的链长关系（外层 3 / 内层 2）、
首个候选直接胜出、等长时保持既有选择（保证确定性）、以及**父目录无论先来后到都胜出**。
单元测试为纯 JVM 逻辑，不依赖设备。

## 6. 应用图标

当前图标是**应用原有标记**：蓝色 `@color/ic_launcher_background`（#1F6FEB）+ 白色字母 **M**。

- `app/src/main/res/drawable/ic_launcher_foreground.xml`：
  `pathData="M40,40 L40,70 M40,40 L54,58 L68,40 L68,70"`，`strokeWidth=6`，
  `strokeLineCap/LineJoin=round`，108×108 视口，图形居中约占 28×30。
- `mipmap-anydpi-v26/ic_launcher.xml` 与 `ic_launcher_round.xml` 同为自适应图标：
  background + foreground + monochrome（Android 13+ 主题图标复用同一矢量）。

**变更历史（本轮为回退）**：

- 1.0.3 曾把图标换成 Markdown 官方标记（圆角外框 + 中间下沉的 M + 向下箭头）；
- 按反馈**已回退**，1.0.4 恢复为上面描述的原有标记；1.0.3 的 APK 仅留作对照。

**可不安装预览**：`python3 tools/icon-preview.py` 用纯标准库把当前图标渲染成
`tools/icon-preview.png`，并列显示方形 / 圆形遮罩 / 圆角方形遮罩 / 单色主题图标四种效果。
本轮已按回退后的图标重新生成并核对：四种形态下 M 都完整清晰（几何数值与 drawable 中的
`pathData`、`strokeWidth` 一一对应）。

APK 内已核对（`aapt2 dump xmltree`）：自适应图标三层齐全；前景矢量为 108×108，
`pathData`/`strokeColor`/`strokeWidth`/`strokeLineCap`/`strokeLineJoin` 与源码逐字一致
（`M40,40 L40,70 M40,40 L54,58 L68,40 L68,70`、`#ffffffff`、`6`、round/round）。

## 7. 化学方程式支持（mhchem 增量书）

### 7.1 基线（增量书 §4.1 要求先记录）

- **公式库**：KaTeX **0.16.22**，取自 npm 官方包 `katex@0.16.22`；APK 内
  `vendor/katex.min.js`、`css/katex.min.css` 与该包内文件 SHA-256 逐字节一致
  （`e8d8855059…` 与 `1909512735…`）。
- **公式 token 在 JavaScript 层生成**：`assets/markview/js/markview.js` 用 markdown-it 的
  block/inline ruler（`markview_math_block` / `markview_math_inline`）识别 `$$…$$` 与 `$…$`，
  再调用 `katex.renderToString()`。
- **KaTeX 资源路径**：`assets/markview/vendor/katex.min.js`、`assets/markview/css/katex.min.css`、
  `assets/markview/css/fonts/`（20 个 woff2）。
- **原有错误处理**：`try/catch` + `<code class="mk-math-error">`；配置为
  `throwOnError:false, trust:false, strict:false, output:"htmlAndMathml", maxSize:40`。
- **原有容器样式**：`.mk-math-inline`（`white-space: nowrap`）、`.mk-math-block`（`overflow-x:auto`）。
- **CSP**：`default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline';
  img-src 'self' data: http: https:; font-src 'self' data:; connect-src 'none'; …`（本次未改动）。
- **版本与签名**（本次之前）：applicationId `com.markview.viewer`，versionCode 6 / versionName 1.0.5，
  release 签名取自根目录 `keystore.properties`。

### 7.2 采用的方案

- **扩展**：同一 KaTeX 包内的配套产物 `dist/contrib/mhchem.min.js`（33,712 字节，内含
  **mhchem 3.3.0**），复制为 `assets/markview/vendor/katex-mhchem.min.js`，许可与出处记在
  `vendor/LICENSE-katex-mhchem.txt`（KaTeX 的 MIT 许可，正文见 `vendor/LICENSE-katex.txt`）。
- **加载顺序**（`index.html`）：`katex.min.js` → `katex-mhchem.min.js` → mermaid / smiles →
  `markview.js`。浏览器版 mhchem 通过 UMD 把 `\ce` 注册到页面上已存在的 `window.katex` 实例，
  因此顺序不可颠倒；**没有引入第二个公式引擎**，也没有从 CDN 取任何东西。
- **复用既有公式入口**：`\ce{…}` 与普通数学共用同一个 `renderFormula()`，行内用 inline 模式、
  `$$…$$` 用 display 模式；没有新增化学识别器，也没有用全文正则替换 `\ce{…}`。
- **初始化时机**：页面脚本初始化时执行一次探测（见 7.3），所以扩展状态早于任何公式渲染就绪
  （增量书 §4.3）；`renderFormula` 另有一道按需探测的兜底，避免调用顺序变化导致漏判。

### 7.3 失败隔离，以及“扩展缺失”与“单条语法错误”的区分

用与 APK 内完全相同的资产在 jsdom 中实测（`tools/js-smoke/smoke5.js` 会持续回归）：

| 情形 | `throwOnError:false` 下的真实行为 | 本版处理 |
| --- | --- | --- |
| **扩展缺失**（只加载 katex） | **既不抛异常、也不产生错误标记**——KaTeX 把 `\ce` 当普通文字排版成 `\ceH2O` | 启动探测用 `throwOnError:true` 渲染一次 `\ce{H2O}`：抛错即判定扩展不可用，日志 `chemistry support: unavailable — KaTeX parse error: Undefined control sequence: \ce …`；此后所有 `\ce` 公式直接走带标记的回退，日志 `chemistry formula skipped: extension unavailable …`。普通数学完全不受影响 |
| **单条语法错误**（如 `\ce{H2O` 少右花括号） | 返回 `class="katex-error"` 的**错误标记**，不抛异常 | 检测该标记并取出 `title`（实测为 `KaTeX parse error: Unexpected end of input in a macro argument…`），日志 `chemistry formula failed: …`，回退为原公式文本 |
| **普通数学语法错误** | 同上 | 日志 `formula failed: …`，与化学错误分开 |

要点：`throwOnError:false` 下“返回了字符串”并不等于渲染成功，原实现只依赖 `try/catch`，会把错误标记
当成成功。现在改为检测错误标记；回退内容用 `escapeHtml()` 转义插入（不使用 HTML 注入）。
`trust:false` 保持不变，CSP、WebView 协议拦截与 SAF 边界一律未放宽。

### 7.4 布局（增量书 §5）

- **行内**：`.mk-math-inline` 改为 `display:inline-block; max-width:100%; overflow-x:auto;
  overflow-y:hidden; padding:0.15em 0`。超宽行内公式在自己的小容器里横向滚动，**不再撑宽整篇正文**；
  仍是行内原子盒（不在公式内部断行），基线对齐由 inline-block 的基线规则保持。
- **块级**：`.mk-math-block { overflow-x:auto; padding:0.35em 0; overscroll-behavior-x:contain }`，
  纵向 padding 是防止箭头条件/上标/下标被裁切的余量；**没有任何 font-size 规则**，长公式靠滚动而不是压字。
- **KaTeX display 的居中溢出问题**：KaTeX 用 `text-align:center` 居中，内容超宽时左右同时溢出，
  左侧永远滚不回来。改为 flex + auto margin：装得下就居中，装不下就贴左并向右溢出，最左到最右都能滚到；
  `flex:0 0 auto` 防止公式被压扁。
- **手势**：未使用 `touch-action`、未禁用任何方向；横向滚动只发生在公式容器内，纵向手势仍归页面。
- **CJK 条件**：`\ce{… ->[加热] …}` 的条件是文本排版，KaTeX 自身字体栈以通用 `serif` 结尾，
  会走系统 CJK 字体；本版额外补一条把 **KaTeX_Main 放最前**的 CJK 回退声明（Noto Sans CJK SC 等），
  不做全局 `.katex` 字体覆盖——箭头、上标、下标仍使用打包的 KaTeX 字体。

### 7.5 已知限制（具体到输入或场景）

- `\ce{}`（空参数）在 mhchem 3.3.0 下**不报错**，渲染为空白；未额外拦截。
- `\pu{…}` 等非合同语法未做专项验证（库本身支持，不会刻意破坏）。
- 水合物、同位素、复杂氧化态不在本次必须范围（增量书 §3）。
- **中文字形的实际观感**（系统 CJK 回退是否落在期望字体上）只能真机确认；构建侧只能确认
  字体栈声明与回退顺序。
- 滚动是否真的能滑到最左/最右、是否不裁切、以及深色/大字体/旋转下的观感，属于真机项，见 §9。

## 8. 已执行的检查与结果

```text
./gradlew test              成功；单元测试 39 个用例 × debug/release 两个变体 = 78 次执行，0 失败
./gradlew lint              成功；0 error，1 warning（仅 OldTargetApi，见 §11）
./gradlew assembleRelease   成功；已生成 1.0.9 release 签名 APK（versionCode 10）
./gradlew assembleDebugAndroidTest  成功（androidTest 源码可编译；无真机未运行）
```

APK 校验（Build-Tools 36.0.0）：

```text
zipalign -c 4                                        → Verification successful
apksigner verify --print-certs                       → Verifies（v2/v3）
apksigner verify --v4-signature-file ...idsig        → Verifies（v2/v3/v4）
aapt2 dump badging                                   → com.markview.viewer, versionCode=10,
                                                       versionName=1.0.9, minSdk=26, targetSdk=35
APK 资源检查（zip 内容）                              → vendor/katex-mhchem.min.js 33,712 字节、
                                                       vendor/katex.min.js、css/katex.min.css、
                                                       20 个 KaTeX woff2 字体、两份许可说明均已打包；
                                                       index.html 中 katex → mhchem → viewer 顺序正确；
                                                       测试夹具未被打进 APK
dex 内字符串核对                                     → 已确认新规则进入 APK（决策日志字符串
                                                       "outermost tree preferred" 存在于 classes.dex）
aapt2 dump xmltree（图标）                            → 自适应图标 background/foreground/
                                                       monochrome 三层齐全；前景矢量 108x108，
                                                       pathData 与本轮回退后的 M 路径一致
zip 内容核对（脚本内逐项断言）                        → 已确认 APK 内的 markview.js / markview.css
                                                       含：SvgDrawer 绘制、scale=1.2、getBBox 墨迹
                                                       测量、加边 viewBox、显式 px 尺寸、按较小边取
                                                       边距、refit、max-width:none 与 overflow-x:auto
zip 内容核对（本轮资产）                              → assets/markview/vendor/mermaid.min.js
                                                       3,572,661 字节，内含 version:"11.17.2"；
                                                       swimlane 支持存在（11.12.0 中为 0 次）；
                                                       index.html viewport = width=device-width,
                                                       initial-scale=1, maximum-scale=1, user-scalable=no；
                                                       markview.js 含块级 alt 链、MERMAID_VERSION 与
                                                       adjustInlineMath；markview.css 的 .mk-math-inline
                                                       无 overflow、新增 .mk-math-inline-scroll、
                                                       .katex-display 为 block/max-content/min-width:100%
npm registry integrity 校验                          → mermaid 11.17.2 tarball 的 sha512 与 sha1 均与
                                                       registry 公布值一致，解包文件与 APK 逐字节相同
dex 内字符串核对（1.0.8）                             → 新增代码确实进入 APK：匹配种类
                                                       "CONTENT_IDENTITY_CROSS_PROVIDER"、
                                                       "content identity confirmed"、
                                                       "grant release:"、"persist grant: taken for"
sha256sum                                            → 见 SHA256.txt
```

渲染页面回归（`tools/js-smoke/`，加载与 APK 内**完全一致**的 assets）：

```text
smoke.js   25 项断言通过 —— CommonMark / GFM 表格 / 删除线 / 只读任务列表、标题锚点、
           行内与块级 LaTeX、原始 HTML/SVG/script 仅作为文本、相对图片 token 桥接、
           缺图降级、远程图片保留、渲染器失败时的降级与隔离
smoke2.js  10 项断言通过 —— 代码块/行内代码中的 $...$ 不当作公式、setTheme、原生错误信息
smoke3.js  26 项断言通过 —— MV-02/03/04 回归（SMILES 渲染、协议降级、Mermaid 尺寸配置）
smoke5.js  49 项断言通过 —— 化学方程式专项（增量书 §7）：
             · 加载顺序：KaTeX → mhchem → viewer，启动探测报告 \ce 就绪；
             · CE01–CE08 与 §7.2 宽方程式逐个断言真实产出的 MathML：
               H₂SO₄ 的双下标、NH₄⁺ 的下标+上标、SO₄²⁻ 的 2− 电荷、→ 与 ⇌、
               「加热」出现在渲染文本中、(s)/(g)/(aq)/(l) 物态、系数与元素符号；
             · 容器结构（MV-09 / MV-10 后的契约）：行内 inline-block + max-width:100% 且**不设
               overflow**、超宽时才由 .mk-math-inline-scroll 承担滚动、块级 overflow-x:auto +
               纵向余量、display 盒子为 block/max-content/min-width:100%（不再是 flex）、
               全程无 touch-action、CJK 回退把 KaTeX_Main 放在最前且无全局字体覆盖；
             · 失败隔离：坏 \ce{ 与坏普通公式各自回退且保留原文，其后的合法公式照常渲染；
             · 扩展缺失（同一套资产去掉 mhchem）：探测报告 unavailable、\ce 变带标记回退
               而不是静默的原始文字、普通数学照常、且不被误报为化学语法错误；
             · 代码边界：行内代码 / text 围栏 / chem 围栏保持代码，正文 H2O 与裸 \ce{H2O} 不被改写；
             · 整篇 fixtures（test/化学方程式测试.md）：16 个公式体渲染、恰好 1 个回退、
               其后正文与 Mermaid/SMILES 正常、加热到达渲染页。
smoke4.js  32 项断言通过 —— MV-05/MV-06 专项：
             · 同文档渲染两次：带 getBBox 仿真（设备路径）与不带（降级路径），两条路径都得到
               加边 viewBox 与显式像素尺寸；
             · 铵离子/水/乙醇的墨迹使 viewBox 超出库的"仅原子"外接框（28 → 55、28 → 46、80 → 85 单位）
               —— 即裁切修复生效；
             · 32 碳长链显示宽 990px（远超屏宽，可横向滚动）、viewBox 825×35（未被补成正方形）；
             · 显示尺寸 = viewBox × 1.2（固定单位比例，与容器宽度无关）；
             · 水 56×42px、铵离子 66px、乙醇 102px、苯 96px、阿司匹林 200px（自然尺寸，未被放大）；
             · refit 幂等、无结构时不抛错；
             · C1( 仍降级且其后结构式继续渲染；Mermaid/KaTeX/普通图片与缺图占位无回归；
             · CSS 断言：`max-width: none`、居中、`overflow-x: auto`、无 `touch-action`。
smoke7.js  12 项断言通过 —— MV-15 甘特图日期轴：
             · 页面把固定排版宽度交给 mermaid（gantt.useWidth = 720，useMaxWidth 仍为 false）；
             · 360px 容器与 1200px 容器渲染出的甘特图**都是 720px 宽**（同一文档在任何机型一致）；
             · 底部刻度间距从修复前的 29px 变为 **79px**，对 ~70px 宽的 %Y-%m-%d 标签留出余量；
             · 仍保留"比屏幕宽就横向滚动、绝不缩小"的容器样式（不裁切标签）。
smoke6.js  57 项断言通过 —— 第三轮回归（MV-07 / MV-08 / MV-09 / MV-10 / MV-11 / MV-14）：
             · MV-08：viewport 已是 maximum-scale=1 + user-scalable=no，且 WebViewFactory 仍
               拒绝缩放（setSupportZoom(false)、builtInZoomControls=false），textZoom 保留
               （系统字体缩放的可用性没有被取消）；
             · MV-09：化学.md 的写法逐个断言——同行 $$…$$ 仍走行内（已记录的行为），
               紧接文字下一行 / 列表项内 / 引用块内的 $$…$$ 全部成为 .mk-math-block 且不再落进行内；
               行内包装层默认无 overflow，.mk-math-inline-scroll 只在测得 scrollWidth>clientWidth
               时加上（用打桩度量驱动：测得后能加、也能在不再超宽时撤掉）；
             · MV-10：\tag{EQ-01} 与 \tag*{custom} 的 tag 仍位于 .katex-html 内（其定位基准），
               display 盒子恢复 block + width:max-content + min-width:100%，flex 规则与其
               `> .katex` 收缩规则均已消失，KaTeX 自身的 right:0 规则仍在；
             · MV-11：不支持的图表类型（swimlanes）与坏语法（graph TD; A-->）给出不同标题，
               两者都保留原始代码、错误都仍进日志；markview.js 的 MERMAID_VERSION 与 bundle 内
               版本串一致（防静默换包的漂移哨兵），且打包的 mermaid 确实认得 swimlane-beta；
             · MV-07（源码不变量，真机行为仍需真机）：分享提示只在 ACTION_SEND 时给出、
               onCreate 只在非重建时消费 Intent、onNewIntent 记录 action/categories/附件/重建标志；
             · MV-14（源码不变量，SAF 行为仍需真机）：解析器存在跨 provider 的内容同一性通道、
               仅在比对过两边内容后才绑定、摘要为有界窗口的 SHA-256 且读取数量有上限、
               只有真正的 tree 授权被当作解析基准、持久化授权只有一处调用点且在 Markdown 校验之后、
               离开文档栈时释放自己的授权且从不释放 tree 或栈内文档。
```

**真机验证**：§9 的全部项目已在真机上执行并且**全部通过**（用户确认，基于 1.0.8）。
本机（构建机）没有设备，因此仓库内保留的是可自动复核的证据：单元测试、lint、APK 校验与
页面回归（加载与 APK 内一致的 assets）；真机侧结论由用户确认，逐项清单见 §9。

说明：本机没有设备这一点只影响"谁来执行"。唯一一个仍然没有设备侧**根因**数据的条目是
MV-08（整页横向移动）——它的验收（未缩放状态下不得整页横移）在真机上已通过，
但根因未定位，若日后复现请提供最小复现与日志（见 §12.2）。

## 9. 真机验证清单（已执行，全部通过；第三轮与第四轮验收要点 + 第二轮 / 化学增量书遗留项）

> 以下各项均已在真机上验证通过（用户确认，1.0.8）。保留清单是为了后续版本回归时逐条复核。

第三轮（本轮修复项）：

- **MV-07**：①在 MarkView 已在后台/栈顶时点桌面图标（多次），不再出现"分享内容中没有可打开的文件"；
  ②打开文档 A → 从 A 里打开相对链接到 B → 切换系统深色模式 → 按返回键应回到 A（此前会被清空）；
  ③仍要复核"分享一个没有附件的文本"时提示照常出现；④`adb logcat -s MarkView:D` 里能看到
  `onNewIntent: action=… recreated=… launchIntentConsumed=… documentsOnStack=…`；
- **MV-08**：①整篇文档在**未缩放**状态下不再能左右拖动；②双指捏合不再改变页面缩放
  （若仍能缩放，请记录机型与 WebView 版本）；③系统字体放大后正文仍随之放大（可访问性未被取消）；
  ④若仍出现整页横向移动，请提供文档与复现段落（本机无排版引擎，见 §12.2）；
- **MV-09**：①`化学.md` 里"……：换行 $$…$$"的公式现在独占一行、居中显示（不再挤在中文行尾）；
  ②公式与中文行在同一行时（如 `水分子合成：$$\ce{…}$$`）公式与文字**基线齐平**，不再明显偏高；
  ③超宽公式仍能在其内部左右滑动；
- **MV-10**：`$$E=mc^2\tag{EQ-01}$$` 与 `$$a=b\tag*{custom}$$` 的编号落在右侧、**不再压住公式**；
  公式本身居中；超长公式仍可滑到最左端；
- **MV-11**：`swimlane-beta`（泳道图）应能正常画出（本轮已升级 mermaid 11.17.2）；
  写成 `swimlanes`（另一种拼写）或其它本包不支持的图类型时，提示应为
  "Mermaid 不支持该图表类型（mermaid 11.17.2）"而不是"渲染失败"，且原始代码仍在；
- **MV-15（1.0.9）**：竖屏打开含甘特图的文档（夹具里那张即可）——底部日期刻度**不再重叠**，
  可以横向滑动看到整条时间轴；同时确认很短的甘特图（几天跨度）只是偏宽、需要滑一点，没有异常；
- **回归关注**：升级 mermaid 后，原有的 flowchart / sequence / class / state / er / gantt / pie /
  mindmap / timeline / gitGraph / quadrant / requirement / block / packet / kanban / radar /
  architecture 图与深色模式观感是否有变化（本地无法排版，需要真机确认）。

第二轮 / 化学增量书（上一轮遗留复核项，同样需要真机）：

- `O`、`[NH4+]`、`CCO`、`c1ccccc1`、阿司匹林：标签大小合理、上下无巨量空白；
- `[NH4+]` 的 N、H、下标 4 与正电荷完整可见；阿司匹林两端与氧原子不被裁切；
- `CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC`（以及更长的合法样例）：在结构式区域内横向滑到最左/最右，
  端点原子可见；若该样例本身能完整放下，请用更长的样例复核横向滚动；
- 结构式区域竖向手势仍能滚动文档；正文整体无水平溢出；
- 窄屏竖屏、横屏、系统大字体、深色模式下均不裁切、可辨认；
- 屏幕旋转、离开再返回、重新打开同一文件后尺寸仍正确；
- 多个分子互不覆盖；错误隔离、Mermaid、公式、普通图片无回归；
- 关闭网络后 SMILES 仍可生成；
- 首轮项目请一并复核（上一轮截图不足以确认）：乙醇/苯/阿司匹林完整结构，
  以及"系统打开方式"入口下的相对 SVG。

第四轮（1.0.8，MV-14 与三项小改动）：

- 用 **MT 管理器**（或任何自带 FileProvider 的文件管理器）"打开方式 → MarkView"打开
  `全面.md`：相对图片与相对链接应**正常**；日志应出现 `tree association by content digest
  (cross-provider)`、`content identity confirmed: …`、随后 `relative: '…' -> content://…`；
- 同一次日志里 `tree lookup: … persistedTrees=N` 的 N 应为 **1**（只剩测试文件夹，
  此前是 6）；打开第二份文档或按返回后应出现 `grant release: …`；
- **进程被杀后的恢复**：打开一个共享文档 → 回桌面 → `adb shell am kill com.markview.viewer`
  → 从最近任务切回 → 文档仍应正常显示（证明栈内文档的授权没被误释放）；
- **仍应失败（不是缺陷）**：文件不在任何已授权目录内；系统送来的内容与已授权目录里那份
  **字节不同**；同一内容在多个已授权目录里都存在（拒绝猜测）；文件在已授权目录内**深度 > 8**
  或目录内条目 > 3000（有界搜索预算）。这四种情况请顺便复核一次，确认它们**优雅失败**
  （正文照常显示、图片显示"图片不可用"占位符、不闪退）。

出现问题时按"可复现输入、设备型号、Android/WebView 版本、来源 URI 类型、期望行为、实际行为"记录，
并附上 logcat（`adb logcat -s MarkView:D`，见 §10）。

## 10. 出现问题时如何取证

```bash
adb logcat -c && adb logcat -s MarkView:D > markview-log.txt
# 在设备上复现后提供该文件
```

日志包含：入口与 URI、authority/document id/名称/大小/修改时间、每个被考察的 tree 及跳过原因、
匹配依据、父子链深度、每个相对引用的最终 URI 与**实际服务 MIME**、桥接拒绝原因与阶段、
送达 WebView 的字节数、WebView 侧图片加载失败，以及 SMILES/Mermaid 各阶段的真实异常与堆栈。
SMILES 相关的问题还会带上 `smiles sizing failed (...)` 一类信息。

## 11. 已知事项与限制

1. **targetSdk 35 而 compileSdk 36**：项目书指定 targetSdk 35，构建环境只提供 android-36；
   lint 的 `OldTargetApi` 是唯一 warning，属预期。
2. **lint 中被显式关闭的检查**：`GradleDependency` / `NewerVersionAvailable`（依赖按项目书固定版本）、
   `ObsoleteSdkInt`（`mipmap-anydpi-v26` 的版本限定符有意保留）、`AndroidGradlePluginVersion`。
3. **签名方案**：v2 + v3 + v4，无 v1（minSdk 26）。
4. **单文档上限 16 MB**：超过时显示"文档过大"，避免异常文件造成 OOM。
5. **结构式显示尺寸是固定比例（1 单位 = 1.2px），不随容器宽度或系统字体大小放大**。
   若把系统字体缩放到极端值，标签的实际渲染尺寸可能变化；此时可通过旋转或重新打开文档
   （会重新测量），或由 `onConfigurationChanged` 触发的 `refit()` 重新适配。
6. **超长分子依赖横向滚动**：这是本轮要求的显示方式；极长结构（数十个原子以上）需要左右滑动查看。
7. **MV-01 的残余限制**：外部应用给的是临时副本、或文件确实不在已授权目录内时，相对资源按设计缺图；
   "内容同一性匹配"要求文件名与大小一致且候选唯一，同名同大小会被判为歧义而放弃。
8. 日志仅写入本机 logcat，不上传任何内容，也不记录文档正文或签名信息。
9. **打包的 Mermaid 为 11.17.2，完全离线**：本轮为支持泳道图从 11.12.0 升级而来。支持
   `swimlane-beta`，但**不认** `swimlanes` 这一拼写，也不包含本包未打包的其它图类型；
   仓库不联网下载，升级只能随 APK 发布。未采用 12.x：体积翻倍、引入 `new Worker`
   （本工程 CSP 为 `worker-src 'none'`）并使用 `Object.groupBy`（Chrome 117+，老 WebView 会运行期报错），
   详见 §12.5。
10. **同一行的 `$$…$$` 仍按行内公式排版**：独占一行的 `$$…$$`（含紧跟在文字行之后）已按块级处理，
    但写在句子中间的 `$$…$$` 仍是行内版式，这是有意保留的行为——按 display 版式嵌入段落会把句子切断。
    若需要严格"`$$` 一律块级"，请作为新需求提出。
11. **MV-08 的根因尚未确认**（验收已在真机通过，但未收到设备侧最小复现数据）：
    本机整篇结构审计没有发现"滚动容器之外的不可断行块"，也无法在本机复现
    整页横向移动；本轮只落地了"页面与 WebView 一致地禁止缩放"这一确定项。若真机仍可整页左右拖动，
    请按 §10 提供文档与 logcat——需要设备侧数据才能定位（见 §12.2）。
12. **MV-12 / MV-13 未做**：分子布局质量需要更换算法/库（大改）；多行 / 带名称 SMILES 属新增契约。
    查证结论与可选方案见 §12.6。
13. **跨 provider 的文档（1.0.8 起支持，但有边界）**：系统"打开方式/分享"送来的文档若与某个
    已授权目录内的文件**内容一致**（同名 + 摘要相同，大小在可得时也须一致），就会把相对资源基准
    绑到该目录；若内容不同、或同一内容在多个已授权目录中都存在、或文件位于授权目录内 8 层以上
    /目录条目超过 3000，则仍按"缺图降级"处理（绝不按名字猜测）。文件名被改写的副本（如
    `文档 (1).md`）匹配不上，这是有意为之。详见 §13。
14. **甘特图按固定画布宽度排版**：1.0.9 起 `gantt.useWidth = 720`（宽屏容器下会等比放大到
    容器宽度），因此①窄屏上图表横向滚动、日期刻度不重叠；②跨度很短的甘特图（例如几天）也会
    偏宽、需要滑一点；③如果文档自己用 `tickInterval` 指定了极密的刻度（例如把一年的刻度定为
    每天），标签仍可能挤在一起——那是文档自身的排版选择，本次不做自适应旋转（备选方案见 §14）。
15. **系统里可能残留历史单文档授权**：1.0.8 起不再为"不是 Markdown 的文件"或"已被授权目录覆盖的
    文档"写授权，并且文档离开文档栈时会释放自己的授权；但**此前版本留下的授权**（例如曾分享给
    MarkView 的图片）不会被自动清理（按你的选择跳过一次性清理）。它们不影响功能，只让日志里的
    `persistedTrees` 计数偏大；要清掉可在系统里清除应用数据。

---

## 12. 第三轮问题（MV-07 ~ MV-13）：根因、修复与排除项

逐条证据、无头复现命令与量化数据见 `MarkView-第三轮查证记录.md`；本节只记录结论与已落地的改动。

### 12.1 MV-07 非文档 Intent 误弹提示 + 启动 Intent 被重复消费（已修）

根因（`MainActivity.kt`）：`handleIntent` 对任何"非文档的新 Intent"都弹
`分享内容中没有可打开的文件`；而 `IntentParser.parse` 只认 `ACTION_VIEW` / `ACTION_SEND`，
于是 `launchMode="singleTop"` 下点桌面图标（`ACTION_MAIN`）走 `onNewIntent` 就会弹提示——
这正是报告里"有时"的来源。附带确认：`onCreate` 无条件消费 `getIntent()`，配置变化重建时会用
同一个 `ACTION_SEND/VIEW` 再次 `openDocumentUri(replaceStack = true)`，把刚从 `savedInstanceState`
恢复的文档栈清空。

改动：

- 提示只在 `intent?.action == Intent.ACTION_SEND` 时给出；
- `recreated = savedInstanceState != null`，仅在 `!recreated` 时消费启动 Intent；
- `onNewIntent` 补一条日志：action、categories、是否有 data / ClipData / `EXTRA_STREAM`、
  `recreated`、`launchIntentConsumed`、`documentsOnStack`。

真机验证：已通过（用户确认）——提示不再出现、文档栈不再被清空。

### 12.2 MV-08 整篇文档横向移动（只落地了确定项）

本机审计（`test/MarkView-极全面功能与安全测试.md` 整篇渲染，1,596 个顶层块）：
`table = 7 = .mk-table-scroll = 7`、带 nowrap 且不在滚动容器内的元素 = 0；≥45 字符的长 token 只有
KaTeX 隐藏 `<annotation>`（处于 1px 裁剪的 `.katex-mathml` 内）与 mermaid 输出里的 `<style>` 文本，
即结构上不存在撑宽根页面的元素。唯一代码级不一致是 viewport 允许缩放
（`maximum-scale=5, user-scalable=yes`）而 `WebViewFactory` 写了 `setSupportZoom(false)`。

本轮改动：viewport 改为 `width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no`，
与 WebView 设置一致；`textZoom`（系统字体缩放）保持不变，可访问性未受影响。

**真机结论**：该条的验收（未缩放状态下不得整页横向移动）在真机上**已通过**（用户确认）。
**根因未定位**：本机无法复现、也没有拿到设备侧最小复现，因此"内容溢出 / WebView 缩放 /
系统侧滑手势"三者中究竟是哪一个（或哪几个）已随之消失，本仓库不做断言；若日后复现，
请提供最小复现与 logcat。
需要设备侧数据——复现文档、是否未缩放即溢出、logcat；或允许加入只写日志的探针
（输出 `scrollingElement.scrollWidth/clientWidth` 与超出视口的最宽元素）。

### 12.3 MV-09 公式分类与行内基线（已修）

两个原因：①`化学.md` 把 `$$…$$` 写在文字行的**下一行且无空行**，markdown-it 视作段落续行，
公式因此落进行内路径（整篇 `化学.md` 实测 `.mk-math-block = 0`、`.mk-math-inline = 15`，
其中含 `：$$…$$` 与列表项内写法）；②行内包装层的 `overflow-x:auto; overflow-y:hidden`
（1.0.6 加入）按 CSS 2.1 §10.8.1 把 inline-block 的基线改为下外边距边缘，抬高约 0.4em。

改动：块级公式规则注册加 `alt: ["paragraph","reference","blockquote","list"]`（无头实测
0 → 2 个块级、列表项与引用块内同样生效）；行内包装层默认不再设 `overflow`（基线回到 KaTeX 自己的），
渲染后由 `adjustInlineMath()` 测量 `scrollWidth - clientWidth > 1` 的元素，只给它们加
`.mk-math-inline-scroll`；KaTeX 字体加载完成与 `refit()`（配置变化）时各再测一次。

### 12.4 MV-10 公式编号与主体重叠（已修，这是 1.0.6 的回归）

`\tag` 由 KaTeX 放在 `.katex-html` 内（`position:absolute; right:0`），其右缘就是定位基准。
1.0.6 为修"居中溢出后左侧滚不回来"加的 `.mk-math-block .katex-display { display:flex }` 与
`> .katex { flex:0 0 auto }` 使 `.katex` 收缩到公式自身宽度，tag 于是压在公式上
（computed style 实测 `.katex-display display=flex`、`.katex flex=0 0 auto`）。

改动：删掉这两条 flex 规则，改为 `display:block; width:max-content; min-width:100%`——
装得下时盒子等于容器宽（tag 靠容器右缘、公式居中），装不下时按内容宽只向右溢出（最左端仍可滚到）。
该 CSS 契约由 smoke5 / smoke6 双向断言（含"flex 规则已消失"与"KaTeX 自身 right:0 仍在"）。

### 12.5 MV-11 泳道图 + Mermaid 11.12.0 → 11.17.2（已升级）

查证：11.12.0 的 bundle 中 `swimlane` 出现 0 次，`detectType` 对泳道图抛
`No diagram type detected matching given configuration…`——属**版本能力**限制，不是配置或语法问题；
`swimlane-beta` 在 **11.17.2** 已是一等图表（配置块 `swimlane:{useMaxWidth,lineHops,ignoreCrossLaneEdges,…}`），
而 npm 上的 `latest` 是 12.1.0。

改动（按指示升级到 11.17.2，**不改** CSP 与任何安全配置）：

- tarball 与 npm registry 公布的 sha512 / sha1 一致，解包文件与 APK 内文件逐字节相同；
- 无 `new Worker` / `blob:` / 动态 `import(` / `eval(`（`worker-src 'none'` 等策略保持原样）；
- 未引入新的 JS 语法要求（`?.`、`??`、`??=` 在 11.12.0 中已存在），WebView 下限不变；
- 图表类型 19 → 20 种（只增不减），既有 `initialize` 配置被同一套键接受；
- APK 体积 +192 KB（3,311,420 → 3,503,238 字节）；
- 配置新增 `swimlane: { useMaxWidth: false }`；
- 失败提示分类：`UnknownDiagramError`（或消息含 `No diagram type detected`）→
  "Mermaid 不支持该图表类型（mermaid 11.17.2）"，其它 → "Mermaid 图渲染失败"；两者都保留原始代码、
  错误照旧进 logcat；`MERMAID_VERSION` 与 bundle 内版本串由 smoke6 断言（防静默换包后报错版本）。

**未做**：升级到 12.1.0（体积翻倍、引入 `new Worker`、用到 `Object.groupBy` 需 Chrome 117+）。
**真机验证**：已通过（用户确认）——升级后 20 种图表的观感与泳道图的实际渲染。

### 12.6 MV-12 / MV-13 未做（按指示：大改 / 新契约）

- **MV-12（复杂分子拥挤）**：`化学.md` §2.6 给出的"紫杉醇"输入按字面统计约 36 个重原子
  （C≈27 / O=9 / N=0），而紫杉醇为 C47H51NO14（62 个重原子、必含 1 个 N）——标题与输入不符，
  因此不据截图判定连接正确性。布局量化：该分子重叠分 7.19，且 `overlapResolutionIterations` 取 5 / 20、
  `overlapSensitivity=0.3`、`compactDrawing=false` 对它完全不改变（`bondLength=45` 只等比放大）；
  小分子对照为 0.00。要真正改善需更换布局算法/库 = 大改，不做。
- **MV-13（多行 / 带名称 SMILES）**：当前实现为"一个 `smiles` 围栏 = 一个分子"（整块文本拼成一个
  SMILES，`markview.js` 一处），原需求与增量书未承诺逐行记录或名称列；逐条复测显示盐 `[Na+].[Cl-]`、
  同位素 `[13CH4]`、顺反 `F/C=C\F`、手性等单独都能渲染，合并输入失败不能证明它们不受支持。
  逐行 + 名称列属新增契约（中等规模），不做；如需请明确契约（首列为 SMILES、其余为名称、
  每条独立解析与降级、不拆开表示多组分的 `.`）。

本轮**没有**采用第三轮报告禁止的手段：未隐藏错误提示、未裁掉溢出内容、未删除公式标签、
未放松安全策略（`trust:false`、CSP、协议拦截、SAF 边界均未改动）、未用根级 `overflow-x:hidden` 掩盖溢出。

---

## 13. MV-14：系统"打开方式 / 分享"打开的文档看不到相对图片与内联文件（1.0.8 修复）

### 现象

文件夹已授权；从应用内"打开文件"选择同一份文档时相对图片与相对链接都正常，
但从系统"打开方式"（文件管理器）或"系统分享"打开时，**正文正常显示，所有相对图片变成
"图片不可用"占位符，相对链接点击提示"相对链接不可用"**。

### 设备日志（决定性证据）

用户提供的 logcat（`adb logcat -s MarkView:D`，原始文件在 `.pi-wechat-files/`，不入库）：

```text
onCreate: action=android.intent.action.VIEW type=text/plain
  data=content://bin.mt.plus.fp/storage/emulated/0/test/md/…/MarkView测试包/全面.md

tree lookup: authority=bin.mt.plus.fp documentId=null uriTreeDocumentId=null
             name=全面.md size=18177 modified=null persistedTrees=6
  tree content://com.android.externalstorage.documents/tree/primary%3Atest%2Fmd%2F… skipped:
       authority com.android.externalstorage.documents does not match bin.mt.plus.fp
  （另 5 条授权 skipped: no tree document id —— 1 条 externalstorage 单文档 + 4 条 media 单文档）

resolve: … authority=bin.mt.plus.fp documentId=null tree=null match=NONE chainDepth=0
relative: '同目录.svg' skipped, document is not inside an authorised tree
relative: './图片/测试.svg' …（共 10 条相对资源，全部 skipped）
resource '…' not found under the authorised tree  × 12        成功注册资源次数: 0
```

判定：

- 送来的 URI 来自 **MT 管理器自己的 FileProvider**（`bin.mt.plus.fp`），路径形 URI，
  `documentId=null`；
- 已授权 tree 属于 `com.android.externalstorage.documents`；
- 旧规则要求"**文档的 provider authority == tree 的 authority**"才能建立关联
  （`SafDocumentResolver.computeTreeAssociation` 的 authority 硬门），跨 provider 一律放弃 →
  `treeUri = null` → `canResolveRelative = false` → 相对资源全部被拒（`resolveRelative` 的第一道门）。

这不是报告里允许的"临时副本"豁免情形：MT 的 URI 指向的**就是原文件**（`/storage/emulated/0/…`），
文件名与大小和 tree 内文件一致，而且 **tree 根恰好就是该文件所在目录**。

### 修复内容（1.0.8）

`SafDocumentResolver` 新增**第三遍：跨 provider 内容同一性**
（`contentIdentityAssociations()` + `searchTreeByName()` + `fingerprintOf()`）：

1. **只在**前两遍（同 authority 的 id 推导与有界遍历）都没有命中时运行 —— 常见路径的成本不变；
2. 遍历**全部**已授权 tree，不再要求 authority 相同，也不再依赖 document id；
3. 用文件名与（可得时的）大小**筛出候选**，再**读取两边内容做 SHA-256 比对**，相等才算命中；
   名字只用于缩小范围，绝不单独作为依据（MV-01 的约束不变）；
4. 候选按 document id 归并：同一文件经两个嵌套 tree 到达只算一次，并沿用"最外层 tree 优先"
   （`pickPreferred`）；
5. 归并后必须**唯一**，多个相同文件一律拒绝并写日志
   （`tree association ambiguous by content digest … refusing to guess`）；
6. 命中后只把**相对资源基准**（treeUri + 祖先链）绑到该 tree，**文档正文仍来自系统送来的 URI**。

判定规则集中在纯函数对象 `ContentIdentityPolicy`（同名 / 大小 / 摘要 / 唯一性），有 6 个单测用例；
摘要为**有界窗口**的 SHA-256（256 KB；超出窗口的文件额外要求大小一致），
每棵树的候选读取数量有上限（8），并复用深度 ≤ 8、条目 ≤ 3000 的既有遍历预算。

**连带项（按推荐做了）**：provider 不报 `SIZE` 时也能匹配 —— 摘要比对不依赖 size。

**未做（按推荐不做）**：文件名被改写（如 `文档 (1).md`）的副本需要"按大小遍历并逐个读同大小文件"，
代价与收益不成比例。

### 同轮小改动

| # | 改动 | 位置 |
| --- | --- | --- |
| 1 | 持久化授权只在"**确认是 Markdown** 且该 URI **不被已授权 tree 覆盖**"时写入；此前在校验之前写入，导致每次分享/误选图片都永久留下一条授权 | `MainActivity.openDocumentUri` |
| 2 | `persistedTreeUris()` 用 `DocumentsContract.isTreeUri()` 过滤 —— 单文档授权不再进入关联查找（用户设备上 6 条授权里有 5 条是单文档授权，每次打开都要逐条跳过） | `SafDocumentResolver` |
| 3a | 文档离开文档栈（换文档清栈、返回）时释放它自己的单文档授权；**栈内文档永不释放**，配置变化也不释放 | `MainActivity.releaseLeftStackGrants` |

**未做**：3b（启动时一次性清理历史遗留授权）。那 5 条旧授权仍留在系统里，它们不影响功能，
只会让日志里的 `persistedTrees` 计数偏大；需要时可清除应用数据。

### 真机验证（已执行，全部通过；下列清单供后续回归复核）

1. 用 MT 管理器"打开方式 → MarkView"打开 `全面.md`：图片与相对链接应正常；
   日志应出现 `tree association by content digest (cross-provider)`、`content identity confirmed: …`，
   随后是 `relative: '…' -> content://…`；
2. `tree lookup: … persistedTrees=N` 中的 N 应为 **1**（此前 6）；打开第二份文档或按返回后应出现
   `grant release: …`；
3. 进程被杀后的恢复：打开一个共享文档 → 回桌面 → `adb shell am kill com.markview.viewer` →
   从最近任务切回 → 文档仍应正常显示；
4. 四种"应当优雅失败"的情形各复核一次：文件不在任何已授权目录内、内容与目录内那份不同、
   同一内容在多个已授权目录里都有、文件在目录内深度 > 8（正文照常、图片为占位符、不闪退）。

---

## 14. MV-15：竖屏时甘特图底部日期刻度重叠（1.0.9 修复）

### 现象

竖屏（窄屏）查看含甘特图的文档时，图表底部的日期刻度（`%Y-%m-%d`）互相重叠、糊成一片；
横屏或宽屏下正常。

### 根因（bundle 代码 + 无头实测双重确认）

mermaid 11.17.2 的甘特图渲染器把**排版宽度**取自容器宽度，而**刻度的疏密只由时间跨度决定**：

```js
ud = h.parentElement.offsetWidth,          // 宽度 = 父容器宽度（手机竖屏约 360px）
ud === void 0 && (ud = 1200),              // 只有 undefined 才回落到 1200
i.useWidth !== void 0 && (ud = i.useWidth) // gantt.useWidth 可覆盖
```

自动刻度器按"每 N 天 / 周 / 月"选刻度（与像素宽度无关），标签格式默认
`axisFormat: "%Y-%m-%d"`（10 个字符约 60–70px）。夹具里那张甘特图跨度约 6 周 → 7 个周刻度：

| 场景 | SVG 宽度 | 刻度间距 | 结果 |
| --- | --- | --- | --- |
| 容器 360px（手机竖屏） | 360 | **29 px** | **重叠** |
| 容器 1200px（宽屏） | 1200 | 147 px | 正常 |
| `useWidth = 720`（容器仍 360px） | **720** | **79 px** | 正常，容器横向滚动 |

同时确认：MV-04 的 `useMaxWidth: false` **对甘特图无效**——它只决定渲染完成后 SVG 是否被拉伸，
而甘特图的"自然宽度"就等于容器宽度，容器永远不会因为"图太宽"而滚动，只能被压扁。

### 修复

```js
gantt: { useMaxWidth: false, useWidth: MERMAID_GANTT_WIDTH }   // MERMAID_GANTT_WIDTH = 720
```

- 画布宽度固定为 720px，不再随容器变化 ⇒ 同一文档在任何机型上排版一致；
- 容器既有的 `.mk-mermaid { overflow-x: auto }` + `> svg { min-width:100%; max-width:none }`
  接住"比屏幕宽"的情况：横向滚动而不是压缩（与 MV-04 的策略一致）；
- 内容零改动：不缩短标签、不减少刻度、不裁切（没有使用 `axisFormat` / `tickInterval` 这类会改变
  显示内容的开关）。

### 覆盖与验证

- 新增 `tools/js-smoke/smoke7.js`（12 条断言）：用打桩的 `offsetWidth`（360 / 1200）与 `getBBox`
  渲染同一张甘特图，断言"两种容器都得到 720px 宽"、"刻度间距 ≥ 70px（实测 79px，修复前 29px）"、
  "容器样式仍保证滚动而不缩小"；
- 仍需真机看一眼：窄屏下滑动是否顺畅、跨度很短的甘特图偏宽是否符合预期（见 §9）。

### 未做（备选方案）

"渲染后测量底部标签、只在真正重叠时旋转 -45° 并补足底部 padding"的自适应方案：改动更大，
且必须补 padding 才能不被裁切；当前固定宽度已解决报告中的情形，故不做。

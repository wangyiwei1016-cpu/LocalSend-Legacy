# 验证报告（v1.1）

本报告汇总 **LocalSend 老机版 1.1**（versionCode 2）的构建、签名、单元测试、静态检查与端到端验证结论。
验证在 Windows 宿主 + Android SDK 上完成；端到端分别在 Android 4.0.3 模拟器与一台 Android 4.2.1 真机上执行。

## 1. 结论总览

| 维度 | 结果 | 说明 |
| --- | --- | --- |
| 构建（release） | PASS | Gradle 8.13 / AGP 8.7.3，一次构建成功 |
| 签名 | PASS | v1（JAR）+ v2 均校验通过 |
| 单元测试 | PASS | 48 项，0 失败，0 跳过 |
| Android Lint | PASS | 0 Error / 0 NewApi |
| DEX / API 兼容（静态） | PASS | Dalvik 035，无非 API 8 框架引用 |
| 模拟器端到端（API 15） | PASS | 收、发、拒绝、设置持久化全部通过 |
| 真机端到端（API 17） | PASS | 安装、发现、接收、自动接收、设置全部通过 |
| Android 2.2 实机运行 | 未验证 | 无 API 8 设备/镜像，见第 8 节 |

## 2. 构建与签名

| 项 | 值 |
| --- | --- |
| 包名 | `com.legacy.localsend` |
| versionCode / versionName | 2 / `1.1` |
| minSdk / targetSdk / compileSdk | 8 / 30 / 34 |
| 启动 Activity | `.ui.MainActivity` |
| 签名方案 | v1（JAR）= true，v2 = true |
| release APK 大小 | 119,919 B |
| release APK SHA-256 | `4A6762FB931AF9543824DDF6C7B788EC72A64B0882860F8B4142D20B0B17BE3B` |

- 构建：`.\build.ps1 -Release`（默认只构建 debug，release 需显式加 `-Release`）。
- 校验：`aapt2 dump badging` 核对包名 / minSdk / targetSdk / 版本号 / 启动 Activity；`apksigner verify -v` 要求 **v1 必须为 true**——Android 2.2/2.3 只认 JAR 签名，因此 `app/build.gradle.kts` 显式 `enableV1Signing = true`。
- 同一脚本也产出 debug 包（208,925 B，debug 证书签名），二者 badging 一致。

## 3. 单元测试（JUnit 4）

`.\gradlew.bat testDebugUnitTest`：**48 项，0 failures，0 errors，0 skipped**。

| 测试类 | 用例数 | 覆盖内容 |
| --- | --- | --- |
| `JsonTest` | 9 | JSON 解析/生成、转义、类型容错、异常路径 |
| `LsServerV2Test` | 27 | v2 全套端点、上传限流、chunked、100-continue、422、路径穿越、并行上传、v1 兼容 |
| `LsClientServerTest` | 4 | `LsClient` ↔ `LsServer` 本机回环收发 |
| `DiscoveryTest` | 8 | announce 编解码、组播包过滤、自发现过滤 |

关键断言（抽查确认非空转）：

- `prepareAndUploadHappyPath`：落盘字节、进度总数、接收目录、发送方信息逐项断言。
- `chunkedUploadExceedingDeclaredSizeReturns400AndAborts`：声明 1 KiB 却推送超量数据 → HTTP 400、会话释放、临时目录**无半成品**。
- `checksumMismatchReturns422AndFailsSession`：声明错误 SHA-256 → 422 且半成品不落盘。
- `pathTraversalFileNameIsSanitized`：`../../evil.txt` 被清洗，最终文件父目录等于接收目录。
- `uploadWithChunkedBodyWorks` / `uploadWithExpect100ContinueWorks`：手工构造 chunked 帧与 `100 Continue` 交互。
- `parallelUploadsOfTwoFilesCompleteSession`：两线程并行上传不同文件均 200。
- `v1EndpointsWorkWithHttpURLConnection`：用 JDK `HttpURLConnection` 模拟 LocalSend 1.x 老客户端全流程。

## 4. 静态检查（Android Lint）

| 项 | 结果 |
| --- | --- |
| Error | **0** |
| NewApi | **0** |
| Fatal | 1：`ExpiredTargetSdkVersion` |
| Warning | 31（图标/本地化/未用资源等，无运行风险） |

- `ExpiredTargetSdkVersion` 是 Google Play 上架政策（要求 target ≥ 33），本项目 targetSdk=30 是刻意选择（保持老系统兼容、避开新版本前台服务限制）；**侧载分发不受影响**。
- NewApi 为 0 依赖一处**记录在案的设计取舍**：`core/NetUtil` 使用 API 9 的 `NetworkInterface` 枚举本机 IPv4，在 API 8 上抛 `NoClassDefFoundError` 并被 `catch(Throwable)` 捕获后返回空表（组播走 API 1 的 `MulticastSocket`，IP 显示走 `WifiManager` 兜底）。`app/lint.xml` 仅对该文件定向抑制 `NewApi`，其他文件不受影响。

## 5. DEX 与字节码兼容性

- APK 内 `classes.dex` 魔数为 **Dalvik 035**（非 ART-only 的 037/038/039），Android 2.2 的 Dalvik 可直接加载。
- 反汇编全量扫描未出现 `java.nio.file`、`java.util.Objects`、`Base64`、`invoke`（lambda/MethodHandle）、`java.util.function`、`java.util.stream`、`java.time`、`ArrayDeque`、`StandardCharsets`、`Optional` 等 Java 7/8 或高版本 API 符号。
- 源码为 Java 6 风格：无 lambda、无 try-with-resources、无 diamond、无 String switch；`sourceCompatibility 1.8` 仅用于编译，D8 以 min-api 8 反糖，无残留字节码特性。
- `core/` 包**零** `android.*` / `androidx.*` 引用（可纯 JVM 单测）；全项目无 AndroidX、Kotlin、native so 依赖。
- 所有 API > 8 的调用均隔离在 `Build.VERSION.SDK_INT` 分支 + `@TargetApi` 嵌套类中（剪贴板 API 16、多选 API 18、权限 API 23、通知渠道 API 26、MediaStore API 29）；API 15 Dalvik 的 soft-verify 告警全部落在“受保护、不执行”的分支上，实测运行正常。

## 6. 模拟器端到端（Android 4.0.3 / API 15，x86 AVD）

| 场景 | 结果 | 校验点 |
| --- | --- | --- |
| 安装 / 启动 / 崩溃检查 | PASS | 无 FATAL、无 ANR，服务监听 53317，组播锁获取成功 |
| PC → App 单文件 | PASS | prepare 200、upload 200，落盘 SHA-256 与源一致 |
| PC → App 多文件 | PASS | 5 个文件（中文名、66 字符长名、0 字节、1 KB 二进制、普通文本）全部 200，逐个 SHA-256 一致 |
| 用户拒绝 | PASS | 返回 403 `Rejected`，不落盘；UI 不可见且未开自动接收时服务端自动拒绝 |
| App → PC 发送 | PASS | 对端日志中 `sender` 为本机别名（证明是 App 主动发起），字节数与 SHA-256 一致 |
| 设置保存 + 服务重启 | PASS | SharedPreferences 持久化，服务停止后重新监听 53317 |
| 手动添加 + register 回退 | PASS | `/v2/info` 404 → `/v1/info` 404 → `POST /v2/register` 200，请求体字段完整 |

## 7. 真机验证（Xiaomi 2013022 / Android 4.2.1 / API 17，armeabi-v7a）

| 场景 | 结果 | 校验点 |
| --- | --- | --- |
| 安装 release APK 并启动 | PASS | 安装成功，`MainActivity` 前台，服务启动，无 FATAL/ANR |
| 局域网发现 | PASS | 发现 Windows 主机（TUF）与 Oppo 设备，协议标记 HTTP |
| 真实对端 → 手机接收 | PASS | 会话开始/完成日志齐全，文件落在公共下载目录 |
| 受控 PC → 手机（单文件） | PASS | prepare/upload 均 200，落盘 SHA-256 与源一致 |
| 受控 PC → 手机（多文件） | PASS | 中文名、66 字符长名、0 字节、1 KB 二进制逐个 SHA-256 一致 |
| 拒绝 | PASS | 返回 403，不落盘 |
| 自动接收 | PASS | 开关开启后无需确认弹窗直接落盘 |
| 设置改别名 + 重启 | PASS | `/info` 返回新别名，服务重启后生效 |
| UI 1.1 三 tab | PASS | 接收/发送/设置三页截图采集完成（720×1280） |

## 8. 未覆盖与环境限制

1. **无 API 8 运行环境**：本地没有 Android 2.2 真机，也没有可用的 API 8 模拟器镜像。Android 2.2 的兼容性结论由三项交叉证据支撑：lint 0 NewApi、DEX 反汇编无高版本符号、API 15 Dalvik 实际运行（soft-verify 告警均在受保护分支）。建议在条件允许时用真实 2.2/2.3 设备冒烟一次。
2. **真机 → PC 的 v1.1 完整闭环未完成**：受 MIUI 文件选择器不稳定影响，真机上只走到“已选文件 → 设备列表”，未能完成整包发送。该方向由 `core/` 协议单测（`LsClientServerTest` 回环）与模拟器链路覆盖，v1.1 未改动 `core/`。
3. **PIN 相关分支未做 UI 场景验证**：服务端实现了 401/429（同 IP 连续 3 次错误后限速），但设置页当前未开放 PIN 配置项，故默认不启用。
4. **409 并发冲突未覆盖**：单一活动会话是协议固有设计，测试中未构造并发对端。
5. **API 29+ 分支未实机验证**：MediaStore 落盘、运行时权限、通知渠道、Android 13 通知权限仅做静态检查与 lint 检查。
6. **未与真实官方客户端双向互通**：协议一致性依据官方 app 1.18.2 源码逐行核对 + 独立探针实测，接收方向证据充分。

## 9. 证据与截图

- 截图位于仓库内 `docs/screenshots/`：模拟器 480×800（接收/发送/设置）与真机 720×1280（接收/发送/设置），以及确认框、接收结果、发送进度等过程截图。
- 原始验证报告、日志、测试数据、接收落盘副本、脚本等**不随仓库发布**；本文件只保留结论与判定依据。

## 10. 结论

v1.1 release 包在构建、签名（v1+v2）、单元测试（48/48）、静态检查（0 Error / 0 NewApi）与模拟器/真机接收链路上均验证通过；
发送链路在模拟器完成完整闭环，真机受厂商文件选择器限制未闭环但底层协议未改动。
唯一实质性缺口是**没有 API 8 运行环境**，已用 lint + DEX + API 15 Dalvik 三重交叉验证，风险可控，但应在发布说明中如实标注。

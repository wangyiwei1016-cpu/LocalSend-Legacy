# 独立审计摘要

对 **LocalSend 老机版 1.1** 的独立审计结论。审计只读业务源码，独立执行 lint / 单测 / 构建与产物校验，
并逐端点对照官方 LocalSend 源码，不修改项目代码。

## 1. 结论

**可交付**：无阻塞级（P0）、无高危（P1）问题；3 项中危（P2）已全部修复并复核通过；12 项低危（P3）以记录形式保留，
均不影响交付。

| 级别 | 数量 | 状态 |
| --- | --- | --- |
| P0 阻塞 | 0 | — |
| P1 高危 | 0 | — |
| P2 中危 | 3 | 已修复并复核通过 |
| P3 低危/建议 | 12 | 记录，不阻塞 |

依据：`lintDebug` 0 Error / 0 NewApi；`classes.dex` 为 Dalvik 035 且无高版本符号；48 项单测全绿；
release APK 独立复核 v1+v2 签名、minSdk 8、targetSdk 30、包名与启动 Activity 均正确；
接收方向 wire format 与官方 1.18.2 逐项一致。唯一硬性缺口是**没有 API 8 运行环境**，已用静态与交叉证据补位。

## 2. 审计方法

- **静态检查**：`gradlew lintDebug`，全量解析 lint XML（按 severity 与 issue id 分类）。
- **源码审计**：`core/` 与 `ui/` 人工扫描全部 API > 8 调用与 Java 7/8 语法特性。
- **字节码审计**：解包 APK 的 `classes.dex`，用 `dexdump -d` 全量反汇编，检索高版本符号。
- **协议审计**：对照官方 LocalSend app 1.18.2（协议 2.2）的 Rust/Dart 源码，逐端点核对请求/响应与错误码。
- **独立复现**：独立执行单测、debug/release 构建，用 `aapt2` / `apksigner` 复核产物。
- **端到端**：只读引用验证环节的模拟器与真机记录（不重复执行）。

## 3. P2 问题与修复

| 编号 | 问题 | 修复 |
| --- | --- | --- |
| M1 | chunked 上传不按 prepare 声明的大小提前截断，最多可写满 8 GB 上限，存在存储耗尽面 | 写盘前按声明大小严格计数，超出立即 400 并中止会话；新增单文件上限 `maxFileBytes`，prepare 阶段超限直接 413，不建会话、不分配磁盘 |
| M2 | 交付文档与实际实现不符（不存在的 `/24` 扫描、构建脚本默认行为、API ≤8 措辞、测试计数） | 已按实现订正文档；本仓库文档已按实际行为重写 |
| M3 | 控制请求体超限或被截断时统一返回 500，应为 413/400 | 新增带状态码的 `HttpStatusException`，控制体超 2 MiB → 413，声明长度后断流 / chunked 截断 → 400，其余 `IOException` 仍为 500 |

复核确认 M1 的“停止”发生在 `write` 之前，失败路径会删除 `.part` 临时文件或 MediaStore pending 行，不留半成品。

## 4. Android 2.2 兼容性

| 检查项 | 结论 |
| --- | --- |
| lint NewApi | **0**（`core/NetUtil` 一处为记录在案的设计取舍，`app/lint.xml` 定向抑制） |
| DEX 版本 | Dalvik **035**，无 Java 7/8 语法，无 `java.nio.file` / `Objects` / `Base64` / `invoke` / `stream` / `time` / `Optional` 等引用 |
| API > 8 调用 | 全部隔离在 `Build.VERSION.SDK_INT` 分支或 `@TargetApi` 嵌套类中 |
| core 依赖 | `core/` **零** `android.*` 引用，可纯 JVM 单测 |
| NetUtil 取舍 | API 9 的 `NetworkInterface` 在 API 8 上抛 `NoClassDefFoundError`，由 `catch(Throwable)` 兜底返回空表；组播走 `MulticastSocket`（API 1），IP 显示走 `WifiManager`（API 1）兜底，功能降级但不崩溃 |
| 其它 | 无 AndroidX / Kotlin / native 库；`WRITE_EXTERNAL_STORAGE` 限 `maxSdkVersion=28`，Android 10+ 走 MediaStore 免权限 |

## 5. 协议一致性

基线：官方 LocalSend app 1.18.2，协议版本 2.2。

**接收方向（官方发送端 → 本应用）** 的 wire format 与官方 `v2.rs` / `dto_v2.rs` 逐项一致：

- 端点：`GET /api/localsend/v2/info`、`POST /register`、`POST /prepare-upload`、`POST /upload`、`POST /cancel`。
- 字段：`register` 请求/响应、`prepare-upload` 的 `info`+`files`、`{sessionId, files:{id:token}}` 响应、announce 字段均可被官方 serde 解析（多余的 `port`/`protocol` 被忽略）。
- 状态码语义：200 / 204（仅发送端处理，本应用从不部分接收）/ 400 / 401 / 403 / 409 / 422 / 429 与官方一致；PIN 同 IP 连续 3 次错误后 429，与官方 `MAX=3` 对齐。
- 传输层：chunked 请求体、`Expect: 100-continue`、`Connection: close`、无 body 的 200 响应均正确实现。

**发送方向（本应用 → 官方）**：请求结构与官方 server 路由一致，`register`/`prepare-upload`/`upload`/`cancel`/`info` 字段齐全；
本应用不发送 `sha256`（协议允许 nullable），官方接收端据此跳过校验。
**v1 兼容**：`/v1/info`、`/register`、`/send-request`、`/send`、`/cancel` 已实现，供真正的 1.x 老客户端使用；
当前官方版本已不实际服务 v1，故此项属于附加兼容能力。

## 6. 安全

| 检查项 | 结论 |
| --- | --- |
| 路径穿越 / 文件名清洗 | 通过。去除路径分隔符、控制字符、Windows 保留字符、前导点并限长，单测断言文件父目录等于接收目录 |
| 会话与令牌 | 32 位十六进制随机串（UUIDv4），session 与每文件 token 独立；上传校验 sessionId + fileId + token + 来源 IP |
| 取消 | 校验来源 IP，不带 sessionId 时只作用于当前活动会话 |
| 单活动会话 | 冲突返回 409，完成/失败/取消均释放会话 |
| 请求体防护 | 控制体 2 MiB 上限；单文件按声明大小限流；全局 8 GB 兜底；超限 413、截断 400 |
| 组件暴露 | 仅启动 Activity 导出，设置页与服务均 `exported=false`；无多余危险权限 |
| 明文传输 | 协议本身无加密、无身份认证；token/IP 校验只防串话，不防同网嗅探，已在文档声明仅限可信局域网 |
| 并发/资源 | 每连接 30 秒读超时 + 64 连接上限；极端并发下连接计数非原子（低危记录） |

## 7. 遗留与边界差异（不阻塞）

1. **422 语义**：本应用收到 SHA-256 不匹配时返回 422 并**终止整个会话**；官方发送端会尝试重试同一文件（最多 3 次），此时重试会拿到 403/409。触发条件极苛刻（TCP 传输中校验不符通常意味着源文件被并发修改）。
2. **cancel 语义**：v2 `/cancel` 不带 sessionId 时会取消当前活动会话（仅限同源 IP）；官方只对 pending 会话这样处理。
3. **无 HTTPS**：老系统只有 TLS 1.0，无法与只开 TLS 1.2 的对端握手；发往官方默认加密端口时直接提示对方关闭加密。
4. **无浏览器下载页**：`download:false`，`/prepare-download` 与 `/download` 未实现，请求返回 404。
5. **announce 版本与字段**：公告 `version:"2.0"`、缺 v1 的 `announcement` 字段，导致 LocalSend 1.x 无法自动发现（v1 端点仍可用，手动添加可用）；官方客户端只看版本是否等于 `"1.0"`，故仍走 v2。
6. **announce 频率**：每 5 秒重播一次（官方为启动/刷新时短促广播），老机功耗略高。
7. **JSON 解析无嵌套深度上限**：恶意深嵌套包可使单个连接线程抛 `StackOverflowError`（进程存活，连接计数正常回收）。
8. **发送端取消时效**：`prepare-upload` 等待期间取消不能立即中断，最坏等待读超时。
9. **待确认会话阻塞**：发送端在用户确认前断开时，服务端仍会等满确认超时并占用唯一会话槽。
10. **lint 未设门禁**：`abortOnError=false`，lint 错误不会阻断构建（当前为 0 Error）。
11. **其余记录项**：通知小图标复用彩色 launcher、应用内无首次使用明文提示、API 29+ 分支（MediaStore / 运行时权限 / 通知渠道）未实机验证、连接计数非原子。

## 8. 审计缺口

- **无 API 8 运行环境**：模拟器为 Android 4.0.3 / API 15，API 8 兼容性结论来自 lint + DEX 反汇编 + Dalvik soft-verify 交叉验证，未做 API 8 实机冒烟。
- **未与真实官方客户端互通**：接收方向有源码逐行核对与探针实测双重证据，发送方向字节流兼容风险低，但缺一次真实双向互发。
- 发布前应在说明中如实标注以上两点。

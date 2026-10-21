# 协议与实现说明

本项目实现 [LocalSend](https://localsend.org) 协议 **v2.2** 的接收方向（HTTP 服务端）与 v2 发送方向（HTTP 客户端），
并额外提供 LocalSend 1.x 的接收端点兼容。设计前提是 **Android 2.2（API 8）及以上**，因此协议只使用明文 HTTP。

实现位于 `app/src/main/java/com/legacy/localsend/core/`：`LsServer`（v2/v1 接收端点、会话与令牌、SHA-256 校验）、
`LsClient`（register/info 发现与 prepare-upload/upload/cancel 发送）、`HttpServer`+`HttpRequest`+`HttpResponse`（自研 HTTP/1.1，
支持 Content-Length、chunked、`Expect: 100-continue`）、`Discovery`（UDP 组播）、`Json`（无依赖 JSON）、`FileMeta`/`DeviceInfo`（DTO）。

## 1. 默认值

| 项 | 值 |
| --- | --- |
| HTTP 端口 | 53317（设置页可改，范围 1024–65535） |
| 组播地址 / 端口 | `224.0.0.167:53317` |
| 传输协议 | 明文 HTTP（announce 中 `protocol:"http"`） |
| 设备类型 | `mobile`（`deviceType`） |
| 下载 API | 不启用（`download:false`） |

## 2. v2 端点

| 方法 | 路径 | 作用 | 成功响应 |
| --- | --- | --- | --- |
| GET | `/api/localsend/v2/info` | 返回本机设备信息 | 200，DeviceInfo JSON |
| POST | `/api/localsend/v2/register` | 双向发现：对方注册后回注册 | 200，DeviceInfo JSON |
| POST | `/api/localsend/v2/prepare-upload` | 只发元数据，请求接收 | 200，`{"sessionId":…,"files":{fileId:token}}` |
| POST | `/api/localsend/v2/upload?sessionId=&fileId=&token=` | 上传单个文件（二进制 body） | 200，无 body |
| POST | `/api/localsend/v2/cancel?sessionId=` | 取消会话 | 200，无 body |

- 路径尾部 `/` 会被容忍；未实现的路径或方法返回 404。
- 响应统一 `Connection: close`，204/304 与 upload 成功响应不写 body；请求体支持 `Content-Length` 与 chunked，并响应 `Expect: 100-continue`。

## 3. v1 兼容端点

为兼容仍在运行的 LocalSend 1.x 客户端，服务端同时挂载 v1 路由（`ServerConfig.enableV1`，默认开启）：

| v1 端点 | 对应 v2 端点 | 差异 |
| --- | --- | --- |
| `GET /api/localsend/v1/info` | `/v2/info` | 只返回 alias / deviceModel / deviceType |
| `POST /api/localsend/v1/register` | `/v2/register` | 响应字段更少 |
| `POST /api/localsend/v1/send-request` | `/v2/prepare-upload` | 响应为平铺的 `{fileId: token}`，无 `sessionId` |
| `POST /api/localsend/v1/send` | `/v2/upload` | 文件名/MIME 归一处理 |
| `POST /api/localsend/v1/cancel` | `/v2/cancel` | 不校验 sessionId |

说明：v1 只是**接收**兼容，发送方向只实现 v2；且组播公告不含 v1 的 `announcement` 字段，1.x 客户端需手动添加地址才能发现本机。

## 4. 设备公告（announce）

启动时向组播组发送，之后每 5 秒重播一次：

```json
{
  "alias": "老手机",
  "version": "2.0",
  "deviceModel": "Xiaomi 2013022",
  "deviceType": "mobile",
  "fingerprint": "随机字符串",
  "port": 53317,
  "protocol": "http",
  "download": false,
  "announce": true
}
```

| 字段 | 说明 |
| --- | --- |
| `version` | 固定 `"2.0"`。官方客户端只在 `"1.0"` 时走 v1 路由，其余版本一律走 v2，故不影响新版互通 |
| `protocol` | 固定 `"http"`，这是官方客户端用明文 HTTP 连本机的依据 |
| `download` | 固定 `false`，表示不提供浏览器下载页 |
| `announce` | `true` 表示这是公告而非回复；未知字段会被官方实现忽略 |
| `fingerprint` | 随机生成，仅用于避免自发现与记住设备（明文模式下非证书指纹） |

收到 `announce:true` 的公告后，本机用 `POST /api/localsend/v2/register` 回注册，实现双向发现；
组播不可用时可用「手动添加 `IP:端口`」触发 `GET /v2/info`（404 时依次回退 `/v1/info`、`POST /v2/register`）。

## 5. 为什么只有明文 HTTP

- Android 2.2–4.3 的系统 TLS 实现只有 TLS 1.0；Android 4.4 虽支持 TLS 1.2 但默认关闭，Android 5.0+ 才默认开启。因此老系统无法与只提供 TLS 1.2 的现代对端完成握手。
- 官方客户端按**接收端公告的 `protocol`** 选择 http/https，所以官方 → 本应用**无需任何设置**。
- 本应用 → 官方默认（`https`）时，`LsClient` 直接抛出说明性错误，提示对方在「设置 → 高级设置 → 网络」关闭加密后再发送。
- 代价是传输内容不加密，详见第 8 节。

## 6. 文件传输流程

以一次 PC → 手机接收为例：

1. **发现**：收到组播公告 → `POST /v2/register` 回注册；或用户手动添加地址。
2. **prepare-upload**：发送端提交 `{"info":{…},"files":{id:{fileName,size,fileType,sha256?}}}`。
   - 控制体上限 2 MiB，超限 413、JSON 非法 400；单文件声明大小超过上限（默认 8 GB）同样 413，且不建立会话、不分配磁盘。
   - 同一时刻只允许一个会话，冲突 409；用户拒绝 403；需要 PIN 时校验 `?pin=`，缺失/错误 401，同 IP 连续 3 次错误后 429。
   - 通过后分配 `sessionId` 与每文件独立 `token`，返回 `{"sessionId":…,"files":{id:token}}`。
3. **upload**：`POST /v2/upload?sessionId=&fileId=&token=`，body 为原始二进制。
   - 校验 sessionId + fileId + token + 来源 IP，任一不符返回 403/409。
   - 支持 `Content-Length` 与 chunked；按 prepare 声明的 `size` 严格计数，**超过声明大小立即中止且不写盘**（400），截断同样 400。
   - 流式写盘的同时增量计算 SHA-256；prepare 提供了 `sha256` 且不匹配时返回 422，会话失败并清理半成品；成功返回 200 空 body，同一文件重复上传返回 400/409。
4. **并行上传**：不同文件可由发送端并发上传，每个文件有独立 token 与写锁；全部完成后会话结束。
5. **cancel**：任一步失败时发送端尽力调用 `/v2/cancel`；服务端校验来源 IP 后中止会话、删除未完成文件。用户确认超时同样视为取消。

HTTP 层防护：单连接 30 秒读超时、64 并发连接上限、控制体 2 MiB 上限、单文件与单请求各 8 GB 硬上限。

## 7. 状态码

| 码 | 场景 |
| --- | --- |
| 200 | 成功（upload/cancel 无 body） |
| 204 | 无需传输（本应用从不部分接收，不会主动返回） |
| 400 | 请求体非法、参数缺失、大小不符、取消导致中止 |
| 401 | 需要 PIN / PIN 错误 |
| 403 | 用户拒绝、token 无效、IP 不匹配 |
| 409 | 已被其它会话占用、同一文件并发上传 |
| 413 | 控制体或文件超过上限 |
| 422 | SHA-256 不匹配 |
| 429 | PIN 连续错误过多 |
| 500 | 存储分配失败等服务端内部错误 |

## 8. 存储与安全注意

- **文件名清洗**：接收前对 `fileName` 去除 `/`、`\`、控制字符与 Windows 保留字符，去掉前导 `.` 并限制长度；落盘路径只来自存储层分配，用户无法控制目录。
- **存储位置**：Android 10+ 通过 MediaStore 写入 `Download/LocalSendLegacy`（免存储权限）；旧版本写入公共下载目录；无外部存储时降级到应用私有目录。
- **会话隔离与明文风险**：单活动会话 + 每文件令牌 + 来源 IP 校验可防串话与误连，但这**不是身份认证**；协议本身无加密无签名，同网段嗅探者可读明文内容，仅建议在可信局域网使用。
- **PIN**：服务端已实现 PIN 校验与限速，但设置页当前未开放配置项，默认不启用；官方客户端若对端要求 PIN，会提示输入。
- **未实现**：浏览器下载页（`/prepare-download`、`/download`）、断点续传、文件夹传输。

## 9. 与官方的已知差异

摘要见 [AUDIT.md](AUDIT.md) 第 7 节：422 时整会话失败（官方允许同一文件重试 3 次）、`/cancel` 对活动会话的宽松处理、
公告版本字符串 `"2.0"` 与缺失 v1 `announcement` 字段、公告每 5 秒重播、无 HTTPS 与浏览器下载页；均不影响与官方 v2.2 客户端正常收发。

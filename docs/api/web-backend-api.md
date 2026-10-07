# Web 后端 API（本机官方登录方案）

基础路径 `/api/v1`。除注册、登录、刷新和下面单列的设备上报接口外，所有接口都要求本系统 access token；`/admin/**` 只允许管理员。响应不含官方 Codex 登录凭证。

## 身份与用户

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/auth/register` | 创建系统用户并登录 |
| POST | `/auth/login` | 登录系统账户 |
| POST | `/auth/refresh` | 轮换 refresh token |
| POST | `/auth/logout` | 退出本系统会话 |
| GET/PATCH | `/users/me` | 查询/修改当前系统用户 |
| GET | `/admin/dashboard` | 管理端用户概况 |
| GET/POST | `/admin/users` | 分页查询/创建用户 |
| GET/PATCH/DELETE | `/admin/users/{id}` | 查询/修改/删除用户 |
| POST | `/admin/users/{id}/force-logout` | 使该用户的**本系统会话**失效，不影响官方 Codex 登录 |

## 团队与个人额度

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET/POST | `/admin/teams` | 查询/创建团队 |
| GET/PATCH/DELETE | `/admin/teams/{id}` | 查询/修改/删除团队 |
| PUT | `/admin/teams/{id}/token-limits` | 设置团队默认 5 小时/7 天 Token 上限 |
| PUT | `/admin/users/{id}/quota-policy` | 设置用户团队归属 |
| PUT | `/admin/users/{id}/token-limits` | 设置用户个人 Token 上限（可覆盖团队） |
| GET | `/client/quota/overview` | 当前系统用户的个人上限、已用、剩余和提醒状态 |
| GET | `/admin/users/{id}/quota-overview` | 管理员查看指定用户个人额度 |
| POST | `/client/usage/events` | 客户端幂等上报本机 Codex 会话中的 Token 事件 |

`POST /client/usage/events` 只接受当前系统用户的事件，不接受账户 ID 或用户 ID。请求示例：

```json
{"events":[{"eventId":"codex-local-usage:v1:<64位十六进制摘要>","occurredAt":"2026-10-06T12:00:00Z","totalTokens":120,"source":"TOKEN_USAGE_RECORD"}]}
```

中央服务按系统用户加事件 ID 去重，按服务端接收时间计算滚动 5 小时和 7 天用量。数据来自客户端日志，不是官方精确账单；`allowed` 仅表示本系统额度状态，不会阻断官方应用。

## Windows Rust 计费服务

| 方法 | 路径 | 身份 | 说明 |
| --- | --- | --- | --- |
| POST | `/client/meter/enroll` | 系统用户 access token | 以 `deviceId` 配对一个 Windows 用户设备，返回一次设备令牌；响应 `no-store` |
| POST | `/meter/heartbeat` | `X-Quota-Guard-Meter` 设备令牌 | 更新最后心跳，不需要桌面窗口保持打开 |
| POST | `/meter/usage/events` | `X-Quota-Guard-Meter` 设备令牌 | 与客户端上报相同的事件格式、幂等去重 |
| GET | `/admin/users/{id}/meter-devices` | 管理员 | 查看该用户的设备 ID、在线状态和最后心跳，不返回令牌 |
| DELETE | `/admin/users/{id}/meter-devices/{deviceId}` | 管理员 | 撤销设备令牌；此后服务不能继续上报或心跳 |

设备令牌只能访问 `/meter/**`，不能访问普通用户或管理员接口，也不是官方 Codex 凭证。一个 `deviceId` 一旦与系统用户绑定，其他系统用户不能重新配对；撤销后该 ID 也不能自行恢复，改绑需管理员重新登记新设备。客户端关闭不影响已配对的 Rust 服务。心跳中断时后台显示离线，但官方 Codex 不经本系统，因此不会被阻断。

## 官方额度安全摘要

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| PUT | `/client/official-quota` | 客户端上传官方额度百分比的安全摘要 |
| GET | `/admin/users/{id}/official-quota` | 管理员查看指定用户最近一次摘要；尚未上报时返回 204 |

上传内容仅包括 `planType`、`fetchedAt` 和 `windows[]`（`limitId`、`slot`、`windowDurationMins`、`usedPercent`、`resetsAt`）。不得上传 `auth.json`、access token、refresh token、cookie 或请求头。管理员界面必须展示最后上报时间，不能把过期快照标成实时值。官方未返回 5 小时窗口时不显示虚构的 5 小时额度。

## 已移除的旧功能

以下路径不再注册：`/admin/codex-accounts/**`、`/admin/teams/{id}/codex-account`、`/client/codex/credential`、`/client/codex/rate-limits`、`/client/account/overview`。旧版本客户端必须升级。

旧托管账户数据库在迁移前需备份。V9 清除活动库中的旧托管凭证，保留按系统用户记录的 Token 事件；V10 增加官方额度安全摘要；V11 增加独立设备心跳与绑定。更多边界见[本机官方登录与个人 Token 统计](../architecture/local-codex-token-quota.md)。

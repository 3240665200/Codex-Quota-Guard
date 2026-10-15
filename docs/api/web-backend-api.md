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
| PUT | `/admin/teams/{id}/members` | 管理员批量加入、转入或移出指定用户；只修改显式提交的成员变更 |
| PUT | `/admin/teams/{id}/token-limits` | 设置团队 5 小时/7 天共享上限、累计总额及只读官方账户关联 |
| PUT | `/admin/users/{id}/quota-policy` | 设置用户团队归属 |
| PUT | `/admin/users/{id}/token-limits` | 设置独立的个人 Token 上限 |
| GET | `/client/quota/overview` | 当前系统用户的个人上限、已用、剩余和提醒状态 |
| GET | `/admin/users/{id}/quota-overview` | 管理员查看指定用户个人额度 |
| GET | `/admin/users/{id}/usage-events?page=0&size=20` | 管理员分页查看该用户已入库的 Token 用量流水；`size` 为 1–100，按服务端接收时间倒序 |
| POST | `/client/usage/events` | 客户端幂等上报本机 Codex 会话中的 Token 事件 |
| GET | `/client/usage/events?page=0&size=10` | 登录用户分页查看自己的已接收流水；`page` 为 0–100000，`size` 为 1–100，不接受用户 ID 切换查询归属，响应 `Cache-Control: no-store` |

### 团队成员批量管理

团队页面的“管理成员”使用已有 `GET /admin/users?page=0&size=20&q=...` 分页搜索姓名/邮箱，并以用户返回的 `teamId` 作为操作前快照。保存时向 `PUT /admin/teams/{id}/members` 提交增量变更，不发送当前页或筛选结果作为全量成员列表，未提交的用户不受影响。

例如目标团队为 `aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa`：

```json
{
  "changes": [
    {"userId":"10000000-0000-4000-8000-000000000001","expectedTeamId":null,"selected":true},
    {"userId":"10000000-0000-4000-8000-000000000002","expectedTeamId":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa","selected":false},
    {"userId":"10000000-0000-4000-8000-000000000003","expectedTeamId":"bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb","selected":true}
  ]
}
```

- `selected=true` 表示加入目标团队，已属于其他团队时为转入；一个用户仍只属于一个团队。`selected=false` 表示移出目标团队，不能据此清除其他团队的成员归属。
- 每次 1–100 项，用户 ID 必须唯一且存在；格式无效、重复、空列表或超过上限返回 400。目标团队不存在返回 404。
- 每一项的实际团队必须与 `expectedTeamId` 相同（`null` 表示原来无团队），否则整批返回 409 `TEAM_MEMBERSHIP_CONFLICT`。前端须刷新后重新选择，不能自动用新快照重试覆盖。
- 停用团队不允许新增或从其他团队转入，返回 409 `TEAM_UNAVAILABLE`；允许移出现有成员。已处于所选状态且快照匹配的操作为无变化，不撤销会话。
- 同一事务内先校验所有用户，再保存全部变更。只改变团队关联，保留个人 Token 上限、重置起点、已有百分比兼容字段、角色和状态，不改团队限额或已入库流水。
- 仅实际变化用户的本系统 access/refresh 会话失效，官方 Codex 登录不受影响；若管理员也修改自己的团队，需要重新登录本系统。

成功返回 200 和最新 `TeamResponse`（包含 `memberCount`）；失败不会出现部分用户已加入、部分用户未加入的中间提交。

### 用量上报

`POST /client/usage/events` 只接受当前系统用户的事件，不接受账户 ID 或用户 ID。请求示例：

```json
{"events":[{"eventId":"codex-local-usage:v1:<64位十六进制摘要>","occurredAt":"2026-10-06T12:00:00Z","totalTokens":120,"source":"TOKEN_USAGE_RECORD"}]}
```

中央服务按系统用户加事件 ID 去重，校验时间后按事件发生时间计算滚动 5 小时和 7 天用量。事件记录接收时所属团队；个人剩余只扣个人上限，团队剩余单独扣全体成员共享上限，团队累计总额从上次重置后计算。5 小时 `null` 表示不设限；7 天基础上限仍须配置。`GET /admin/teams/{id}/quota-overview` 返回团队三档上限、已用、剩余、成员数和最近上报时间；用户的 `/client/quota/overview` 与管理员用户额度接口同时返回 `teamQuota`。数据来自客户端日志，不是官方精确账单；`allowed` 仅表示本系统额度状态，不会阻断官方应用。
管理员可通过 `POST /admin/users/{id}/quota-reset` 和 `POST /admin/teams/{id}/quota-reset` 分别重置个人或团队共享用量。响应为 204，不接受请求体。服务端只更新统计起点，不删除流水，也不修改 Token 上限；计费窗口同时要求事件发生和服务端接收时间晚于重置点，防止重置前事件迟到后重新扣额。团队重置不会清除成员个人用量，个人重置也不会清除团队用量。
流水接口只返回安全摘要：服务端散列事件 ID、`occurredAt`、`receivedAt`、`totalTokens`、`source` 及分页信息；不返回原始响应 ID、会话内容或任何登录令牌。普通用户不可查询管理员流水接口，响应禁止缓存。管理页在“额度查看 → 用户详情”中分页显示。

管理员流水新增可空的 `model` 与 `reasoningEffort`，原字段全部保留。上报事件可选携带这两项；旧客户端不传仍可正常入库。模型名称最多 96 个安全 ASCII 字符，思考等级为 `none/minimal/low/medium/high/xhigh/max/ultra`；缺失元数据不会推断默认值，历史流水不追溯改写。
为兼容严格解析旧响应的已安装客户端，`GET /client/usage/events` 默认仍返回原五项事件字段；`includeMetadata=true` 可显式请求新增元数据。管理员接口始终返回新增字段。

流水可追加 `inputTokens`、`cachedInputTokens`、`cacheWriteInputTokens`、`outputTokens`、`reasoningOutputTokens` 五个可空分项，以及 `estimatedCostUsd`、`costStatus`。管理员流水始终包含这些字段；客户端须传 `includeTokenBreakdown=true`，此选项也包含模型与思考等级。单独使用 `includeMetadata=true` 不增加分项或费用，保留旧响应兼容性。

客户端与设备的 `POST .../usage/events` 均支持上述五个分项，不支持上报费用或单价。例如：

```json
{
  "events": [{
    "eventId": "codex-local-usage:v1:<64位十六进制摘要>",
    "occurredAt": "2026-10-10T00:00:00Z",
    "totalTokens": 1200,
    "source": "TOKEN_USAGE_RECORD",
    "model": "example-model",
    "reasoningEffort": "high",
    "inputTokens": 1000,
    "cachedInputTokens": 300,
    "cacheWriteInputTokens": 100,
    "outputTokens": 200,
    "reasoningOutputTokens": 150
  }]
}
```

分项缺失不影响原有总量入库；费用由中央后端按当前模型单价计算。状态为 `PRICED/MISSING_BREAKDOWN/INVALID_BREAKDOWN/MISSING_PRICE`，无法估价时金额为 `null`。

桌面端通过固定的本机 `GET /api/v1/usage/events?page=0&size=10` 转发自己的查询，不能传入中央令牌、目标 URL 或其他用户 ID。
本机 `GET /api/v1/usage/logs` 返回 `{state, entries, skippedLines}`，只投影固定运行摘要，不返回原始日志。两者均需要桌面桥接令牌及系统登录态；服务日志权限不足时返回 `ADMIN_REQUIRED`，缺失时返回 `NOT_AVAILABLE`，读取失败返回 `READ_FAILED`。

## 用量统计与 USD 模型定价

| 方法 | 路径 | 身份与说明 |
| --- | --- | --- |
| GET | `/admin/usage/analytics` | 管理员；汇总、UTC 趋势、Top 20 分布和分页流水 |
| GET | `/admin/usage/options` | 管理员；模型、用户、团队筛选项，各最多 1000 项 |
| GET | `/admin/usage/model-prices` | 管理员；当前模型单价，金额单位 USD / 百万 Token |
| PUT | `/admin/usage/model-prices` | 管理员；按模型名称新增或覆盖四个分项单价 |
| DELETE | `/admin/usage/model-prices?model=example-model` | 管理员；删除定价，成功返回 204 |
| GET | `/client/usage/analytics?days=7` | 登录系统用户；只返回自己的汇总和趋势，允许 1、7、30 天 |

管理统计参数：`from`、`to` 为带时区的 ISO-8601 时间；缺省为近 7 天，区间 `[from,to)`。`granularity=DAY/HOUR`，默认 `DAY`；最大查询跨度为 93 天，小时粒度最多 31 天。可选 `model`、`reasoningEffort`、`userId`、`teamId` 均精确匹配。`page` 为 0–100000，`size` 为 1–100、默认 20，仅影响流水分页，不影响汇总；流水按发生时间、接收时间、事件 ID 倒序。

响应固定 `currency: "USD"`、`timezone: "UTC"`，包含 `from/to/granularity/summary/trend`。`summary` 为事件数、总 Token、五个可空分项总和、`breakdownEventCount`、`costedEventCount`、`estimatedCostUsd` 和 `cacheHitRatePercent`；`trend` 为 `{bucket, stats}[]`。管理响应另有 `models/reasoningEfforts/users/teams` 分组和 `events` 分页；个人响应不包含其他用户、团队分组或管理流水。响应禁止缓存。

模型定价请求：

```json
{"model":"example-model","inputPrice":2,"outputPrice":8,"cacheReadPrice":0.5,"cacheWritePrice":3}
```

四项单价必填，范围 0–1000000，最多 6 位小数。`model` 最多 96 个安全 ASCII 字符，与用量记录精确匹配。响应包含这五项及 `currency: "USD"`、`updatedAt`、`priceSource`（`CC_SWITCH`/`MANUAL`）、可空的 `sourceReference` 与 `sourceCheckedAt`。V20 为常用 OpenAI/Codex 模型导入 CC Switch 固定版本快照，不覆盖已有定价；PUT 编辑后来源变为 `MANUAL`。修改单价按新单价重新估算历史查询，Token 额度不变；详情见 [计费口径](../architecture/usage-pricing.md)。

桌面端通过固定的本机 `GET /api/v1/usage/analytics?days=7` 和 Tauri `usage_analytics` 命令查询个人统计；需要桥接令牌与系统登录态，不接受其他用户 ID、目标地址或中央令牌。

## Windows Rust 统计程序

| 方法 | 路径 | 身份 | 说明 |
| --- | --- | --- | --- |
| POST | `/client/meter/enroll` | 系统用户 access token | 以 `deviceId` 配对一个 Windows 用户设备，返回设备令牌及服务端确认的 `userId`；响应 `no-store` |
| POST | `/meter/heartbeat` | `X-Quota-Guard-Meter` 设备令牌 | 更新最后心跳并延长有效设备授权 30 天，不需要桌面窗口保持打开 |
| POST | `/meter/credential/renew` | `X-Quota-Guard-Meter` 设备令牌 | 独立续期；返回 `deviceId`、`userId`、`expiresAt`；过期后最多 30 天恢复宽限，撤销或用户禁用不恢复 |
| POST | `/meter/usage/events` | `X-Quota-Guard-Meter` 设备令牌 | 与客户端上报相同的事件格式、幂等去重，仅返回 `acceptedEvents` 回执 |
| GET | `/admin/users/{id}/meter-devices` | 管理员 | 查看该用户的设备 ID、在线状态和最后心跳，不返回令牌 |
| DELETE | `/admin/users/{id}/meter-devices/{deviceId}` | 管理员 | 撤销设备令牌；此后服务不能继续上报或心跳 |

设备令牌只能访问 `/meter/**`，不能访问普通用户或管理员接口，也不是官方 Codex 凭证。一个 `deviceId` 一旦与系统用户绑定，其他系统用户不能重新配对；撤销后该 ID 也不能自行恢复，改绑需管理员重新登记新设备。客户端关闭不影响已配对的 Rust 统计程序。心跳中断时后台显示离线，但官方 Codex 不经本系统，因此不会被阻断。

## 桌面绑定访问校验

`GET /client/access` 使用系统用户 access token 和 `X-Quota-Guard-Device-ID`，返回 `{allowed, bindingId, reason}`。安装 ID 由本机 Java 从固定设备文件读取，不接受 renderer 指定另一个设备。后台检查数据库中的真实绑定与当前身份；受保护的 `/client/**` 接口缺少有效绑定时返回 403 / `SESSION_BINDING_REQUIRED`。

登录、设备登记、登录辅助及绑定本身仅开放必要的启动接口；管理员接口仍要求 ADMIN。`/meter/**` 通过独立的设备认证上报，不因桌面门禁关闭而停止。`POST /client/usage/events` 也只返回 `acceptedEvents`，不再夹带可用于绕过读门禁的额度对象；额度需通过受保护的 GET 接口读取。

## 官方额度安全摘要

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| PUT | `/client/official-quota` | 客户端上传官方额度百分比的安全摘要 |
| GET | `/admin/users/{id}/official-quota` | 管理员查看指定用户最近一次摘要；尚未上报时返回 204 |
| GET/POST | `/admin/official-accounts` | 管理员列出/登记只读官方账户来源与估算总 Token |
| PUT/DELETE | `/admin/official-accounts/{id}` | 修改或删除登记；已被团队关联时不可删除 |

上传内容仅包括 `planType`、`fetchedAt` 和 `windows[]`（`limitId`、`slot`、`windowDurationMins`、`usedPercent`、`resetsAt`）。不得上传 `auth.json`、access token、refresh token、cookie 或请求头。管理员界面必须展示最后上报时间，不能把过期快照标成实时值。官方未返回 5 小时窗口时不显示虚构的 5 小时额度。
官方账户登记只保存名称、估算总 Token 和上报用户邮箱；同一系统用户只能绑定一条登记账户。团队关联后累计上限与该估算值同步。快照由管理员指定的系统用户从其本机官方登录上报，服务端不会主动登录或验证该用户是否确实使用所登记的官方账户。

## 已移除的旧功能

以下路径不再注册：`/admin/codex-accounts/**`、`/admin/teams/{id}/codex-account`、`/client/codex/credential`、`/client/codex/rate-limits`、`/client/account/overview`。旧版本客户端必须升级。

旧托管账户数据库在迁移前需备份。V9 清除活动库中的旧托管凭证，保留按系统用户记录的 Token 事件；V10 增加官方额度安全摘要；V11 增加独立设备心跳与绑定。更多边界见[本机官方登录与个人 Token 统计](../architecture/local-codex-token-quota.md)。

# Web 后端 REST API 契约

## 通用约定

- 基础路径：`/api/v1`。
- 请求和成功响应均使用 `application/json`，字符集 UTF-8。
- 除注册、登录、刷新外，接口使用 `Authorization: Bearer <accessToken>`。
- 时间使用 ISO-8601 UTC，例如 `2026-09-21T12:00:00Z`；ID 使用 UUID。
- 未列出的字段不得被客户端依赖；服务端可向响应增加兼容字段。
- 注册和修改密码（如未来开放）使用至少 12 个字符的密码规则；本期注册请求不接受少于 12 位的密码。

### 统一错误

```json
{
  "code": "AUTH_INVALID_CREDENTIALS",
  "message": "邮箱或密码不正确",
  "requestId": "7c1f3b5e-...",
  "details": []
}
```

`code` 为稳定机器码，`message` 可本地化，`requestId` 用于排查，`details` 默认为空数组。校验错误可包含 `details: [{"field":"email","reason":"invalid"}]`，不得包含密码或 token。

## 认证接口

### POST `/api/v1/auth/register`

创建普通用户并直接建立登录会话。

请求：

```json
{"email":"user@example.com","password":"correct horse battery staple","displayName":"小明"}
```

成功 `201 Created`：返回 `AuthResponse`。重复邮箱返回 `409 Conflict` / `AUTH_EMAIL_EXISTS`；字段不合法或密码少于 12 位返回 `400` / `VALIDATION_ERROR`。

### POST `/api/v1/auth/login`

请求：`{"email":"user@example.com","password":"..."}`。

成功 `200 OK`：返回 `AuthResponse`。凭据错误统一返回 `401` / `AUTH_INVALID_CREDENTIALS`，不得透露“邮箱不存在”；禁用用户必须即时返回 `403` / `AUTH_ACCOUNT_DISABLED`，不得签发 token。

### POST `/api/v1/auth/refresh`

请求：`{"refreshToken":"..."}`。

成功 `200 OK`：返回新的 `AuthResponse`，旧 refresh token 必须原子、立即撤销，新 token 的过期时间重新计算。过期、撤销、格式错误或重放返回 `401` / `AUTH_REFRESH_INVALID`；禁用用户即时返回 `403` / `AUTH_ACCOUNT_DISABLED`。重放检测必须撤销该用户的 refresh session 链。

### POST `/api/v1/auth/logout`

需要 access token。请求体：`{"refreshToken":"..."}`。服务只撤销属于当前用户的 refresh 会话族；未知、已撤销或不属于当前用户的 token 均按幂等退出处理。成功 `204 No Content`，重复退出仍返回 `204`。

### AuthResponse

```json
{
  "accessToken":"eyJ...",
  "tokenType":"Bearer",
  "expiresIn":900,
  "refreshToken":"random-opaque-token",
  "refreshExpiresIn":2592000,
  "user": {
    "id":"uuid",
    "email":"user@example.com",
    "displayName":"小明",
    "role":"USER",
    "status":"ACTIVE",
    "assignedCodexAccountId":null,
    "teamId":null,
    "fiveHourQuotaPercent":null,
    "weeklyQuotaPercent":null,
    "createdAt":"2026-09-21T12:00:00Z",
    "updatedAt":"2026-09-21T12:00:00Z"
  }
}
```

## 个人资料接口

### GET `/api/v1/users/me`

需要 access token。成功 `200 OK`，返回 `UserResponse`。不存在或已禁用的主体返回 `401` / `AUTH_TOKEN_INVALID` 或 `AUTH_ACCOUNT_DISABLED`。

### PATCH `/api/v1/users/me`

需要 access token。只允许修改 `displayName`、`email`；未传或显式 `null` 的字段保持不变，但请求中至少要有一个非空更新值。

请求示例：`{"displayName":"新昵称","email":"new@example.com"}`。

成功 `200 OK`，返回更新后的 `UserResponse`。邮箱重复返回 `409` / `AUTH_EMAIL_EXISTS`；校验失败返回 `400` / `VALIDATION_ERROR`。已禁用用户即时返回 `403` / `AUTH_ACCOUNT_DISABLED`。修改邮箱不触发本期未实现的邮箱验证流程。

### UserResponse

```json
{
  "id":"uuid",
  "email":"user@example.com",
  "displayName":"小明",
  "role":"USER",
  "status":"ACTIVE",
  "assignedCodexAccountId":null,
  "teamId":null,
  "fiveHourQuotaPercent":null,
  "weeklyQuotaPercent":null,
  "createdAt":"2026-09-21T12:00:00Z",
  "updatedAt":"2026-09-21T12:00:00Z"
}
```

## 管理接口

管理接口统一位于 `/api/v1/admin`，必须携带有效 access token，并由 Java 后端
实时确认当前用户为 `ADMIN` 且状态为 `ACTIVE`。普通用户返回 `403 / AUTH_FORBIDDEN`，
未登录或 token 无效返回 `401`。

### GET `/api/v1/admin/dashboard`

返回管理首页统计：

```json
{
  "totalUsers": 120,
  "activeUsers": 116,
  "disabledUsers": 4,
  "adminUsers": 2,
  "newUsersLast7Days": 18
}
```

### GET `/api/v1/admin/users`

支持以下查询参数：

- `page`：从 `0` 开始，默认 `0`。
- `size`：每页数量，范围 `1..100`，默认 `20`。
- `q`：按邮箱或昵称进行不区分大小写的包含搜索。
- `status`：可选 `ACTIVE`、`DISABLED`。
- `role`：可选 `USER`、`ADMIN`。

结果按 `createdAt` 倒序并使用 `id` 作为稳定次级排序：

```json
{
  "items": [],
  "page": 0,
  "size": 20,
  "totalElements": 0,
  "totalPages": 0
}
```

### GET `/api/v1/admin/users/{id}`

返回指定用户的 `UserResponse`。目标不存在返回 `404 / USER_NOT_FOUND`。

### POST `/api/v1/admin/users`

由管理员直接创建应用用户：

```json
{
  "email": "user@example.com",
  "displayName": "小明",
  "password": "correct horse battery staple",
  "role": "USER",
  "status": "ACTIVE"
}
```

成功返回 `201 Created`、新用户的 `UserResponse` 和 `Location` 响应头。密码至少
12 位；重复邮箱返回 `409 / AUTH_EMAIL_EXISTS`。

### PATCH `/api/v1/admin/users/{id}`

修改邮箱、昵称、密码、状态或角色，请求至少包含一个字段：

```json
{
  "email": "new@example.com",
  "displayName": "新昵称",
  "password": "new password at least 12 chars",
  "status": "DISABLED",
  "role": "USER"
}
```

成功返回更新后的 `UserResponse`。目标不存在返回 `404 / USER_NOT_FOUND`；管理员
不得禁用自己或把自己降为普通用户，系统也始终保留至少一个启用中的管理员。违反这些
约束时返回 `409`；移除最后一个启用管理员会返回 `ADMIN_LAST_ACTIVE`。前端隐藏操作
按钮只能改善体验，不能替代后端授权和保护规则。修改密码或禁用用户会使该用户的全部
现有会话立即失效。

### DELETE `/api/v1/admin/users/{id}`

永久删除应用用户，成功返回 `204 No Content`。管理员不能删除自己，也不能删除最后
一个启用中的管理员；违反约束返回 `409`。用户关联的 refresh session 由数据库外键
级联删除。

### POST `/api/v1/admin/users/{id}/force-logout`

递增用户会话版本并撤销其全部 refresh session，成功返回 `204 No Content`。该用户
此前签发的 access token 会在下一次请求鉴权时立即失效，而不是等待自然过期。

### PUT `/api/v1/admin/users/{id}/codex-account`

把后台托管的 Codex 账户分配给系统用户。请求：

```json
{"codexAccountId":"uuid"}
```

仅允许分配启用状态的 Codex 账户。成功响应只包含 `id`、`displayName`、`status`、
`updatedAt`，不返回 Codex 邮箱、凭据、密文或 IV。变更分配会立即递增该用户的
`sessionVersion` 并撤销全部 refresh session，因此客户端需要重新登录。

### DELETE `/api/v1/admin/users/{id}/codex-account`

取消系统用户的 Codex 账户分配，成功返回 `204 No Content`，并立即使该用户现有会话失效。

### PUT `/api/v1/admin/users/{id}/quota-policy`

完整替换系统用户的团队归属和两个窗口的个人额度覆盖值；未提供的字段按 `null`
处理，因此调用方宜显式提供三个字段。个人值为
`null` 时继承团队默认，没有团队时继承系统默认 `100%`，`0` 表示禁止使用该窗口：

```json
{"teamId":"uuid","fiveHourQuotaPercent":50,"weeklyQuotaPercent":null}
```

额度值范围为 `0..100`。成功返回更新后的 `UserResponse`；策略变化立即撤销该用户的旧
系统会话。该接口只设置策略，不接受客户端上传用量，也不会下发 Codex 凭据。

### 团队管理 `/api/v1/admin/teams`

- `GET /api/v1/admin/teams`：返回团队数组；`GET /api/v1/admin/teams/{id}`：返回单个团队。
- `POST /api/v1/admin/teams`：创建团队，必填 `name`，可提供 `description`、`status`、
  `fiveHourQuotaPercent`、`weeklyQuotaPercent`；两个额度缺省时为 `100`，状态缺省为 `ACTIVE`。
- `PATCH /api/v1/admin/teams/{id}`：更新提供的字段，可传 `version` 防止覆盖其他管理员的
  并发修改；版本不符返回 `409 / TEAM_VERSION_CONFLICT`。
- `DELETE /api/v1/admin/teams/{id}`：删除团队并清除成员的团队归属。

团队响应只包含元数据、两个默认额度、`memberCount`、审计时间和 `version`，不包含任何
Codex 凭据。团队停用、额度或成员归属变化会使受影响成员的旧系统会话失效。

## 桌面客户端接口

### GET `/api/v1/client/account/overview`

使用系统用户的 access token 查询自己的安全账户投影。未分配是正常状态，返回
`200 OK`、`assigned:false` 和 `codexAccount:null`；已分配时只返回展示所需元数据：

```json
{
  "assigned": true,
  "user": {
    "id": "uuid",
    "email": "user@example.com",
    "displayName": "小明",
    "role": "USER",
    "status": "ACTIVE"
  },
  "codexAccount": {
    "id": "uuid",
    "displayName": "Primary Codex",
    "status": "ACTIVE",
    "updatedAt": "2026-09-22T01:00:00Z"
  }
}
```

该接口没有凭据解密或下载路径，只提供账户分配状态。

### GET `/api/v1/client/quota/overview`

使用系统用户 access token 获取当前用户的团队、有效额度策略、服务端用量和限制状态：

```json
{
  "assigned": true,
  "user": {"id":"uuid","email":"user@example.com","displayName":"小明","role":"USER","status":"ACTIVE"},
  "codexAccount": {"id":"uuid","displayName":"Primary Codex","status":"ACTIVE","updatedAt":"2026-09-22T01:00:00Z"},
  "team": {"id":"uuid","name":"研发团队"},
  "policy": {
    "fiveHourLimitPercent": 50,
    "weeklyLimitPercent": 80,
    "fiveHourSource": "USER",
    "weeklySource": "TEAM"
  },
  "usage": {
    "measurementStatus": "NOT_MEASURED",
    "fiveHourUsedPercent": null,
    "weeklyUsedPercent": null,
    "fiveHourRemainingPercent": null,
    "weeklyRemainingPercent": null,
    "fiveHourResetsAt": null,
    "weeklyResetsAt": null,
    "measuredAt": null
  },
  "allowed": false,
  "blockReason": "USAGE_NOT_MEASURED"
}
```

`policy` 是管理员配置的上限，不等于已经测得的个人消耗。未通过服务器受控执行链路逐人
记账时，`usage.measurementStatus` 为 `NOT_MEASURED`，数值为 `null`，不得解释为 0 或
允许执行。策略来源只可能为 `USER`、`TEAM` 或 `DEFAULT`；服务端测量超过 5 分钟、
所属 Codex 账户已变化或窗口已重置时，返回 `NOT_MEASURED`。上游 Codex 账户的共享
使用率不能冒充个人用量；客户端也没有用量写入接口。

## Codex 账号管理接口

这些接口仅供管理员使用，由本项目 Java 后端直接实现。凭据使用 AES-GCM 加密后
保存到 PostgreSQL；响应只包含账户元数据，不包含凭据明文、密文或加密 IV。

### GET `/api/v1/admin/codex-accounts`

支持 `page`、`size`、`q`、`status` 参数，规则与用户列表一致。`q` 按邮箱或显示
名称不区分大小写搜索；`status` 可选 `ACTIVE`、`DISABLED`。返回分页结构：

```json
{
  "items": [{
    "id": "uuid",
    "email": "codex@example.com",
    "displayName": "Primary Codex",
    "status": "ACTIVE",
    "hasCredential": true,
    "createdAt": "2026-09-22T01:00:00Z",
    "updatedAt": "2026-09-22T01:00:00Z"
  }],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1
}
```

### GET `/api/v1/admin/codex-accounts/{id}`

按 UUID 查询一个 Codex 账号。不存在返回 `404 / CODEX_ACCOUNT_NOT_FOUND`。响应不
返回凭据。

### POST `/api/v1/admin/codex-accounts`

添加 Codex 账号：

```json
{
  "email": "codex@example.com",
  "displayName": "Primary Codex",
  "status": "ACTIVE",
  "credential": {
    "access_token": "...",
    "refresh_token": "..."
  }
}
```

`credential` 必须是 JSON object，序列化后最大 1 MiB。成功返回 `201 Created`、
账户元数据和 `Location`；重复邮箱返回 `409 / CODEX_ACCOUNT_EMAIL_EXISTS`。服务端
为每次新增或替换生成独立 96-bit 随机 IV，使用 AES-256-GCM 加密后才写入数据库。
新增或替换凭据必须配置独立 `CODEX_CREDENTIAL_ENCRYPTION_KEY`，否则返回
`503 / CODEX_ENCRYPTION_KEY_REQUIRED`。JSON 导入不代表已向上游验证凭据有效。

### PATCH `/api/v1/admin/codex-accounts/{id}`

可修改 `email`、`displayName`、`status`，也可传新的 `credential` 替换凭据；至少
包含一个字段。未传 `credential` 时保留原密文。成功返回更新后的账户元数据。

### DELETE `/api/v1/admin/codex-accounts/{id}`

永久删除账户及其加密凭据，成功返回 `204 No Content`。删除后无法恢复。

### POST `/api/v1/admin/codex-accounts/authorizations`

请求：`{"displayName":"开发账户","mode":"BROWSER"}`（名称去空白后 1..120 字符）。
`mode` 允许 `BROWSER`（官方网页登录）和 `DEVICE_CODE`（设备码）；省略或 null 时
兼容旧客户端，默认 `DEVICE_CODE`；其他值返回 400。使用服务器官方 Codex CLI。
仅允许管理员，同一管理员至多一个进行中的授权，全局至多四个；
15 秒内重复发起、容量已满返回 `429 / CODEX_AUTHORIZATION_LIMIT`。

网页登录全局至多一个；重复浏览器会话返回 `409 / CODEX_BROWSER_LOGIN_BUSY`。
后台与管理员浏览器必须同机（或已有经管理员配置的安全回调隧道）；官方回调固定在
`http://localhost:1455/auth/callback`，远程服务器应使用设备码登录。端口被占用时返回
`FAILED / CODEX_BROWSER_LOGIN_BUSY`，不会主动终止占用者。

成功创建授权会话后返回 `200`（上游启动失败也返回安全的 `FAILED` 状态，不包含原始错误）：

```json
{
  "id": "uuid",
  "status": "PENDING",
  "verificationUrl": "https://auth.openai.com/codex/device",
  "userCode": "ABCD-1234",
  "expiresAt": "2026-09-22T12:10:00Z",
  "account": null,
  "errorCode": null,
  "message": null
}
```

响应另含 `mode` 和 `authUrl`：设备码模式 `authUrl=null`，网页登录模式返回经过官方域名、
HTTPS、PKCE 和本机回调校验的 `authUrl`，此时 `verificationUrl`、`userCode` 均为 null。
前端打开新窗口时使用 `noopener,noreferrer`，弹窗被阻止则显示手动打开链接。

`userCode` 为一次性官方登录验证码，`authUrl` 含此次 OAuth 状态，均不能转交普通客户端用户。此接口不会返回
上游 access/refresh token。响应使用 `Cache-Control: no-store`。

### GET `/api/v1/admin/codex-accounts/authorizations/{id}`

仅发起者可查看，其他管理员或不存在的会话均返回 `404 / CODEX_AUTHORIZATION_NOT_FOUND`。
建议每 2 秒串行查询一次。状态为 `STARTING`、`PENDING`、`SUCCEEDED`、`FAILED`、
`CANCELLED`、`EXPIRED`；终态不再返回 `userCode`、`verificationUrl` 或 `authUrl`。

成功后 `account` 为自动创建的 Codex 账户元数据，邮箱取自官方授权结果；重复邮箱
以 `FAILED / CODEX_ACCOUNT_EMAIL_EXISTS` 结束，不覆盖原账户。`FAILED` 时 `message`
是经过过滤的提示。维护任务会独立推进状态，浏览器断开不影响 10 分钟超时清理。
管理员被禁用、降权、删除或强制下线后，待完成授权不再入库。

### DELETE `/api/v1/admin/codex-accounts/authorizations/{id}`

仅发起者可取消，幂等返回 `204`；已成功的账户不会因取消而删除。临时文件清理失败
返回 `503 / CODEX_AUTHORIZATION_CLEANUP_FAILED` 并由维护任务重试。授权状态仅在服务器
内存保存，不跨重启恢复；多实例部署必须保持授权请求路由到同一实例。

## 状态码与 Spring Boot 对照

| HTTP | 使用场景 | 建议实现映射 |
| --- | --- | --- |
| `200` | 登录、刷新、查询/更新资料 | `@RestController` 正常返回 DTO |
| `201` | 注册或管理员创建成功 | `ResponseEntity.status(CREATED)` |
| `204` | 退出、删除或强制下线成功 | 空响应 |
| `400` | JSON/字段校验失败 | `@Valid` + `@RestControllerAdvice` |
| `401` | 未认证、access/refresh 无效 | Spring Security entry point 或异常处理器 |
| `403` | 账号已禁用或权限不足 | access denied handler；每次鉴权实时检查用户状态 |
| `409` | 邮箱唯一约束冲突 | 捕获唯一约束/业务异常 |
| `429` | 登录/注册/刷新限流 | 限流过滤器或网关 |
| `500` | 未预期服务错误 | 统一异常处理，不返回堆栈 |

建议包结构：`auth`（controller/service/token/dto）、`user`（controller/service/entity/repository/dto）、`security`（过滤器、JWT、异常处理）、`config`、`migration`（Flyway SQL 位于 `src/main/resources/db/migration`）。控制器只编排请求，密码哈希、轮换和禁用判断放在 service 层，数据库访问放在 repository 层。

## 统一错误与 CORS 验收

应用控制器、认证与鉴权产生的 `4xx/5xx` 均返回统一 error envelope：

```json
{"code":"AUTH_ACCOUNT_DISABLED","message":"账号已禁用","requestId":"uuid","details":[]}
```

不得返回 Spring 默认错误页、堆栈、密码或 token。CORS 只接受 `CORS_ALLOWED_ORIGINS` 的精确匹配来源；禁止 `*`，预检仅允许契约所需的 `GET, PATCH, POST, PUT, DELETE`、`Authorization, Content-Type`，并设置有限的 `Access-Control-Max-Age`。CORS 层直接拒绝非法 Origin 时由框架返回 `403`。

## Flyway 初始迁移契约

- `V1__create_users.sql`：创建 `users`、枚举/约束和规范化邮箱唯一索引。
- `V2__create_refresh_sessions.sql`：创建 `refresh_sessions`、用户外键、token hash 索引和撤销/过期字段。
- `V3__add_user_session_version.sql`：为用户和 refresh session 增加会话版本，用于立即强制下线。
- `V4__create_codex_accounts.sql`：创建原生 Codex 账户表、唯一邮箱约束和加密凭据字段。
- `V5__assign_codex_accounts_to_system_users.sql`：添加系统用户到 Codex 账户的可空外键；删除 Codex 账户时自动取消关联。
- `V6__create_teams_and_quota_policies.sql`：创建团队、用户个人策略和服务端个人用量记录，范围由数据库约束校验。
- 后续迁移只追加新版本，不修改已执行的迁移；生产启动必须启用校验。

## 明确不提供的端点

本期不提供 `/verify-email`、`/forgot-password`、`/reset-password`、Codex 凭据下发、普通客户端用量写入或远程 Codex 任务执行接口。

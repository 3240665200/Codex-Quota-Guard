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
  "createdAt":"2026-09-21T12:00:00Z",
  "updatedAt":"2026-09-21T12:00:00Z"
}
```

## 状态码与 Spring Boot 对照

| HTTP | 使用场景 | 建议实现映射 |
| --- | --- | --- |
| `200` | 登录、刷新、查询/更新资料 | `@RestController` 正常返回 DTO |
| `201` | 注册成功 | `ResponseEntity.status(CREATED)` |
| `204` | 退出成功 | 空响应 |
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

不得返回 Spring 默认错误页、堆栈、密码或 token。CORS 只接受 `CORS_ALLOWED_ORIGINS` 的精确匹配来源；禁止 `*`，预检仅允许契约所需的 `GET, PATCH, POST`、`Authorization, Content-Type`，并设置有限的 `Access-Control-Max-Age`。CORS 层直接拒绝非法 Origin 时由框架返回 `403`。

## Flyway 初始迁移契约

- `V1__create_users.sql`：创建 `users`、枚举/约束和规范化邮箱唯一索引。
- `V2__create_refresh_sessions.sql`：创建 `refresh_sessions`、用户外键、token hash 索引和撤销/过期字段。
- 后续迁移只追加新版本，不修改已执行的迁移；生产启动必须启用校验。

## 明确不提供的端点

本期不提供 `/verify-email`、`/forgot-password`、`/reset-password`、`/admin/*`、Codex OAuth 代理或 Codex 额度接口。新增前端页面或管理员功能前，应先扩展本契约和权限模型。

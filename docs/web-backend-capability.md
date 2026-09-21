# Web 后端能力契约

## 目标

新增一个可部署到服务器的联网用户服务，为桌面客户端提供应用账号注册、登录和个人资料管理。该服务与 Codex 官方账号完全分离：

- 应用账号只保存本服务的用户标识、邮箱、昵称、密码哈希、角色、禁用状态和审计时间。
- Codex 官方账号仍由桌面端按官方设备授权流程登录；Web 服务不接收、不保存 Codex access token、refresh token 或 `auth.json`。
- 当前桌面端本机 Java 服务仍可保留，用于额度读取和共享房间；联网用户服务是独立 Spring Boot 应用和独立进程。

## 已纳入能力

| 能力 | 契约 |
| --- | --- |
| 注册 | 使用唯一邮箱和至少 12 位密码创建普通用户；密码只保存 BCrypt（cost 12）结果。 |
| 登录 | 校验账号密码，签发短期 access token 和一次性 refresh token。 |
| 刷新轮换 | refresh token 每次使用后作废并换发新 token；重复使用或失效 token 令全部关联会话失效。 |
| 退出 | access token 验证当前用户后，撤销请求体中 refresh token 所属的会话族；access token 通过短过期时间自然失效。 |
| 个人资料 | `GET` 查看当前用户；`PATCH` 修改昵称和邮箱，邮箱必须重新校验唯一性。 |
| 角色 | `USER`、`ADMIN` 两个角色；本期仅保留数据模型和鉴权扩展点，未提供管理界面。 |
| 禁用 | `DISABLED` 用户不可注册新会话、不可刷新；已有 access token 在请求鉴权时也必须拒绝。 |
| 数据库 | PostgreSQL 为运行数据库，Flyway 管理版本化迁移；启动时校验迁移状态。 |
| 安全错误 | 所有 API 错误使用统一 JSON 格式，不回显密码、token 或内部堆栈。 |

## 数据模型最小要求

- `users`：`id`（UUID）、`email`（规范化后唯一）、`display_name`、`password_hash`、`role`、`status`、`created_at`、`updated_at`。
- `refresh_sessions`：`id`、`user_id`、只保存 refresh token 的哈希、`expires_at`、`revoked_at`、`created_at`、可选 `ip`/`user_agent`。
- refresh token 原文只在签发响应中出现一次；数据库和日志均不得保存原文。
- 禁止软删除复用邮箱；如未来需要删除账号，应另行设计生命周期契约。

## 安全边界

- Web API 必须使用 HTTPS；生产环境禁止明文 HTTP 暴露。
- 生产环境启用 `prod` profile 后强制安全通道并读取代理转发头；应用 HTTP 端口必须只对可信反向代理开放。
- access token 使用签名 JWT 或等价的不可伪造短期令牌；默认 15 分钟有效期。refresh token 使用高熵随机不透明值，默认 30 天有效期；每次刷新必须原子撤销旧 token 并签发新 token。
- 登录、注册和刷新接口按来源 IP 与端点执行单实例限流；多实例部署还应在网关增加全局限流。邮箱比较大小写不敏感并使用统一规范化规则。
- CORS 只允许 `CORS_ALLOWED_ORIGINS` 中明确配置的来源，禁止通配符 `*`，禁止未配置来源；若启用凭据则响应必须回显匹配的具体 Origin，并仅允许必要方法和请求头。
- 日志不得记录密码、Authorization 头、refresh token、数据库连接串或 Codex 凭据。
- 本服务不代表用户调用 Codex API，也不代管共享目录密钥；桌面本机服务与此服务的令牌体系不能混用。

## 环境变量

| 变量 | 必填 | 说明 |
| --- | --- | --- |
| `WEB_SERVER_PORT` | 否 | HTTP 监听端口，默认 `8080`。 |
| `DATABASE_URL` | 是 | PostgreSQL JDBC URL。 |
| `DATABASE_USERNAME` | 是 | 数据库用户。 |
| `DATABASE_PASSWORD` | 是 | 数据库密码。 |
| `JWT_ISSUER` | 否 | access token 的 issuer，默认 `codex-quota-guard`。 |
| `JWT_SECRET` | 是 | 生产环境使用密钥管理系统注入的签名密钥。 |
| `ACCESS_TOKEN_TTL` | 否 | access token 有效期，默认 `15m`。 |
| `REFRESH_TOKEN_TTL` | 否 | refresh token 有效期，默认 `30d`。 |
| `CORS_ALLOWED_ORIGINS` | 否 | 逗号分隔的精确允许来源，默认仅 `http://localhost:1420`。 |
| `AUTH_RATE_LIMIT_PER_MINUTE` | 否 | 每个 IP、每个认证端点的分钟请求上限，默认 20。 |
| `FLYWAY_VALIDATE_ON_MIGRATE` | 否 | 默认开启迁移校验。 |

## 非目标（本期不实现）

- 邮箱验证、找回密码、改密邮件和多因素认证。
- Web 管理界面；`ADMIN` 仅作为后续管理 API 的角色基础。
- Codex OAuth 代理、Codex token 云端存储或云端额度采集。
- 房间文件同步、成员策略发布、自动退出 Codex；这些仍属于桌面端/共享房间能力。

## 验收标准

1. Spring Boot 服务可用 PostgreSQL 启动，并由 Flyway 创建/升级所需表。
2. 注册、登录、刷新轮换、退出、个人资料 GET/PATCH 均返回本文件约定的状态码和 JSON 结构。
3. 禁用用户必须在登录、刷新和每次受保护请求鉴权时即时拒绝；refresh token 重放会撤销会话链。
4. 任何响应、异常和日志都不包含密码原文、token 原文或 Codex 凭据。

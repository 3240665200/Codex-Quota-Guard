# Web 后端能力契约

## 目标

新增一个可部署到服务器的联网用户服务，为桌面客户端提供应用账号注册、登录和个人资料管理。应用用户与 Codex 账号保持独立：

- 应用账号只保存本服务的用户标识、邮箱、昵称、密码哈希、角色、禁用状态和审计时间。
- 管理端由本项目 Java 后端原生实现 Codex 账号查询、添加、修改、启停和删除。
- Codex 凭据使用 AES-256-GCM 和每条记录独立随机 IV 加密后保存到 PostgreSQL；加密密钥只存在于 Java 后端环境变量中。
- 当前桌面端本机 Java 服务仍可保留，用于额度读取和共享房间；联网用户服务是独立 Spring Boot 应用和独立进程。

## 已纳入能力

| 能力 | 契约 |
| --- | --- |
| 注册 | 使用唯一邮箱和至少 12 位密码创建普通用户；密码只保存 BCrypt（cost 12）结果。 |
| 登录 | 校验账号密码，签发短期 access token 和一次性 refresh token。 |
| 刷新轮换 | refresh token 每次使用后作废并换发新 token；重复使用或失效 token 令全部关联会话失效。 |
| 退出 | access token 验证当前用户后，撤销请求体中 refresh token 所属的会话族。 |
| 个人资料 | `GET` 查看当前用户；`PATCH` 修改昵称和邮箱，邮箱必须重新校验唯一性。 |
| 角色 | `USER`、`ADMIN` 两个角色；管理 API 由 Java 后端强制执行 `ADMIN` 授权。 |
| 禁用 | `DISABLED` 用户不可注册新会话、不可刷新；已有 access token 在请求鉴权时也必须拒绝。 |
| 用户管理 | 管理员可创建、查看、修改和删除应用用户，并可强制其全部会话立即下线。 |
| Codex 账号 | Ant Design Pro 管理端通过本项目 Java API 支持列表/详情、新增、修改、JSON 凭据替换、启停和删除。 |
| 管理后台 | Ant Design Pro 管理端提供登录、用户统计、搜索分页与上述管理能力。 |
| 数据库 | PostgreSQL 为运行数据库，Flyway 管理版本化迁移；启动时校验迁移状态。 |
| 安全错误 | 所有 API 错误使用统一 JSON 格式，不回显密码、token 或内部堆栈。 |

## 数据模型最小要求

- `users`：`id`（UUID）、`email`（规范化后唯一）、`display_name`、`password_hash`、`role`、`status`、`session_version`、`created_at`、`updated_at`。
- `refresh_sessions`：`id`、`user_id`、只保存 refresh token 的哈希、`session_version`、`expires_at`、`revoked_at`、`created_at`、可选 `ip`/`user_agent`。
- `codex_accounts`：`id`、规范化唯一邮箱、显示名称、状态、AES-GCM 密文、随机 IV、审计时间和乐观锁版本。
- refresh token 原文只在签发响应中出现一次；数据库和日志均不得保存原文。
- 本期管理员删除为硬删除，关联 refresh session 由外键级联清理；不能删除自己或最后一个启用中的管理员。
- Codex 凭据明文只在新增或替换请求的内存中短暂存在，响应、日志和普通查询均不返回明文。

## 安全边界

- Web API 必须使用 HTTPS；生产环境禁止明文 HTTP 暴露。
- 生产环境启用 `prod` profile 后强制安全通道并读取代理转发头；应用 HTTP 端口必须只对可信反向代理开放。
- access token 使用签名 JWT 或等价的不可伪造短期令牌；默认 15 分钟有效期。refresh token 使用高熵随机不透明值，默认 30 天有效期；每次刷新必须原子撤销旧 token 并签发新 token。两者都绑定用户 `session_version`，强制下线、禁用或管理员重置密码会递增版本并立即拒绝旧 token。
- 登录、注册和刷新接口按来源 IP 与端点执行单实例限流；多实例部署还应在网关增加全局限流。邮箱比较大小写不敏感并使用统一规范化规则。
- CORS 只允许 `CORS_ALLOWED_ORIGINS` 中明确配置的来源，禁止通配符 `*`，禁止未配置来源；若启用凭据则响应必须回显匹配的具体 Origin，并仅允许必要方法和请求头。
- 日志不得记录密码、Authorization 头、refresh token、数据库连接串、凭据加密密钥或 Codex 凭据。
- 本服务原生管理 Codex 账号，但不在查询响应中暴露凭据，也不代管共享目录密钥；桌面本机服务与此服务的令牌体系不能混用。

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
| `CORS_ALLOWED_ORIGINS` | 否 | 逗号分隔的精确允许来源，默认 `http://localhost:1420,http://localhost:8000`。 |
| `AUTH_RATE_LIMIT_PER_MINUTE` | 否 | 每个 IP、每个认证端点的分钟请求上限，默认 20。 |
| `FLYWAY_VALIDATE_ON_MIGRATE` | 否 | 默认开启迁移校验。 |
| `ADMIN_BOOTSTRAP_EMAIL` | 否 | 与密码同时设置时，仅用于首次创建管理员。 |
| `ADMIN_BOOTSTRAP_PASSWORD` | 否 | 至少 12 位；不得写入源码、日志或版本库。 |
| `ADMIN_BOOTSTRAP_DISPLAY_NAME` | 否 | 首个管理员昵称，未设置时使用安全默认值。 |
| `CODEX_CREDENTIAL_ENCRYPTION_KEY` | 否 | 推荐配置至少 32 字节的独立密钥；未配置时回退到 `JWT_SECRET`。密钥轮换前需先迁移已有密文。 |

## 非目标（本期不实现）

- 邮箱验证、找回密码、改密邮件和多因素认证。
- 从管理查询接口导出 Codex 凭据明文、自动刷新凭据或云端额度采集。
- 房间文件同步、成员策略发布、自动退出 Codex；这些仍属于桌面端/共享房间能力。

## 验收标准

1. Spring Boot 服务可用 PostgreSQL 启动，并由 Flyway 创建/升级所需表。
2. 注册、登录、刷新轮换、退出、个人资料和管理员用户 CRUD 均返回约定的状态码和 JSON 结构。
3. 禁用或强制下线用户必须在刷新和每次受保护请求鉴权时即时拒绝；refresh token 重放会撤销会话链。
4. 任何响应、异常和日志都不包含密码原文、token 原文或 Codex 凭据。
5. 普通用户不能访问任何 `/api/v1/admin/**` 接口；管理员不能禁用或降级自己，系统必须始终保留至少一个启用中的管理员。
6. 可完成 Codex 账号增删改查和启停；数据库中只出现 AES-GCM 密文和随机 IV，浏览器查询响应拿不到凭据原文或加密密钥。

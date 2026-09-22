# Admin Backend

这是可独立部署的 Java 17 + Spring Boot 4 用户与管理员服务。它同时管理 Codex
Quota Guard 的应用用户和独立的 Codex 账号。Codex 凭据使用 AES-GCM 加密后保存，
管理接口只返回账户元数据，不返回凭据原文。

## 已提供的接口

- `POST /api/v1/auth/register`：注册并登录
- `POST /api/v1/auth/login`：密码登录
- `POST /api/v1/auth/refresh`：一次性 refresh token 轮换
- `POST /api/v1/auth/logout`：撤销 refresh 会话，需要 access token
- `GET /api/v1/users/me`：查询当前用户
- `PATCH /api/v1/users/me`：修改邮箱或昵称
- `GET /api/v1/admin/dashboard`：查询用户统计
- `GET /api/v1/admin/users`：按分页、邮箱/昵称、状态和角色筛选用户
- `GET /api/v1/admin/users/{id}`：查询用户详情
- `POST /api/v1/admin/users`：创建用户
- `PATCH /api/v1/admin/users/{id}`：修改邮箱、昵称、密码、状态或角色
- `DELETE /api/v1/admin/users/{id}`：删除用户
- `POST /api/v1/admin/users/{id}/force-logout`：强制用户所有会话下线
- `GET /api/v1/admin/codex-accounts`：分页查询 Codex 账号
- `GET /api/v1/admin/codex-accounts/{id}`：查询 Codex 账号详情
- `POST /api/v1/admin/codex-accounts`：添加 Codex 账号和凭据 JSON
- `PATCH /api/v1/admin/codex-accounts/{id}`：修改资料、状态或替换凭据
- `DELETE /api/v1/admin/codex-accounts/{id}`：删除 Codex 账号

密码使用 BCrypt 保存；JWT access token 默认有效 15 分钟；refresh token
原文只在响应中出现，数据库只保存 SHA-256 摘要。刷新时使用数据库悲观锁，
旧 token 重放会撤销同一会话族。登录、注册、刷新接口还包含单实例限流，
部署时仍建议在反向代理或网关增加全局限流。
每个 access token 和 refresh session 都绑定用户的会话版本；管理员执行强制下线、
禁用账号或重置密码后，旧 token 会在下一次请求时立即失效。

所有 `/api/v1/admin/**` 接口均由服务端强制要求 `ROLE_ADMIN`：未登录请求返回
`401`，普通用户返回 `403`。管理员不能通过管理员接口禁用自己或把自己降级为
`USER`，系统也会阻止禁用或降级最后一个启用中的管理员；这些冲突返回 `409`，后者
使用错误码 `ADMIN_LAST_ACTIVE`。用户列表的 `page` 从 0 开始，`size` 范围为 1 到
100，按 `createdAt DESC, id DESC` 稳定排序。

## 必填环境变量

| 变量 | 示例 |
| --- | --- |
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/pingche` |
| `DATABASE_USERNAME` | `pingche` |
| `DATABASE_PASSWORD` | 使用数据库专用强密码 |
| `JWT_SECRET` | 至少 32 字节的随机密钥 |

生产部署还应设置 `SPRING_PROFILES_ACTIVE=prod`。该 profile 会信任标准反向代理
转发头并强制安全通道；反向代理必须写入正确的 `Forwarded` 或
`X-Forwarded-Proto: https`，且应用的 8080 端口只能在私有网络中访问。

可选变量：

- `JWT_ISSUER`，默认 `codex-quota-guard`
- `WEB_SERVER_PORT`，默认 `8080`
- `ACCESS_TOKEN_TTL`，默认 `15m`
- `REFRESH_TOKEN_TTL`，默认 `30d`
- `CORS_ALLOWED_ORIGINS`，默认 `http://localhost:1420,http://localhost:8000`，多个精确来源用逗号分隔
- `AUTH_RATE_LIMIT_PER_MINUTE`，默认每个 IP、每个认证端点 20 次/分钟
- `REQUIRE_HTTPS`，默认 false；`prod` profile 会强制设为 true
- `CODEX_CREDENTIAL_ENCRYPTION_KEY`：推荐单独配置，至少 32 字节，用于加密 Codex
  凭据；未设置时兼容性回退到 `JWT_SECRET`，更换该密钥前必须先完成凭据轮换
- `ADMIN_BOOTSTRAP_EMAIL` 与 `ADMIN_BOOTSTRAP_PASSWORD`：可选的首次管理员
  bootstrap。必须同时配置；密码至少 12 个字符。邮箱会去空白并转为小写，
  密码会以 BCrypt cost 12 保存且不会被记录。若该邮箱已属于非管理员，或仅配置
  其中一个变量，应用会拒绝启动而不会静默提权。已有管理员时不会再创建额外的
  bootstrap 管理员。可选 `ADMIN_BOOTSTRAP_DISPLAY_NAME` 默认 `Administrator`；
  它会去空白，且必须为 1 到 120 个字符。

## 运行和验证

先创建 PostgreSQL 数据库与用户，并把上述变量注入当前终端，然后运行：

```bash
mvn -f apps/admin/backend/pom.xml spring-boot:run
```

Flyway 会自动执行 `V1` 到 `V4` 迁移。生产环境必须通过 HTTPS 或可信反向
代理对外提供服务，并启用 `prod` profile；不要把应用的 HTTP 监听端口直接
暴露到公网。

运行测试和覆盖率门槛：

```bash
mvn -f apps/admin/backend/pom.xml verify
```

测试使用 H2 的 PostgreSQL 兼容模式，但不会绕过 Flyway；核心认证与用户服务
的行覆盖率门槛为 80%。

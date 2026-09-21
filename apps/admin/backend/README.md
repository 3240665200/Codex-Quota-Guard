# Admin Backend

这是可独立部署的 Java 17 + Spring Boot 4 用户与管理员服务。它管理 Codex
Quota Guard 自己的应用账号，不接收或保存 Codex 官方账号凭据。

## 已提供的接口

- `POST /api/v1/auth/register`：注册并登录
- `POST /api/v1/auth/login`：密码登录
- `POST /api/v1/auth/refresh`：一次性 refresh token 轮换
- `POST /api/v1/auth/logout`：撤销 refresh 会话，需要 access token
- `GET /api/v1/users/me`：查询当前用户
- `PATCH /api/v1/users/me`：修改邮箱或昵称
- `GET /api/v1/admin/dashboard`：查询用户统计
- `GET /api/v1/admin/users`：按分页、邮箱/昵称、状态和角色筛选用户
- `PATCH /api/v1/admin/users/{id}`：修改用户状态或角色

密码使用 BCrypt 保存；JWT access token 默认有效 15 分钟；refresh token
原文只在响应中出现，数据库只保存 SHA-256 摘要。刷新时使用数据库悲观锁，
旧 token 重放会撤销同一会话族。登录、注册、刷新接口还包含单实例限流，
部署时仍建议在反向代理或网关增加全局限流。

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

Flyway 会自动执行 `V1`、`V2` 迁移。生产环境必须通过 HTTPS 或可信反向
代理对外提供服务，并启用 `prod` profile；不要把应用的 HTTP 监听端口直接
暴露到公网。

运行测试和覆盖率门槛：

```bash
mvn -f apps/admin/backend/pom.xml verify
```

测试使用 H2 的 PostgreSQL 兼容模式，但不会绕过 Flyway；核心认证与用户服务
的行覆盖率门槛为 80%。

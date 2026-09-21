# Web Backend

这是可独立部署的 Java 17 + Spring Boot 4 用户服务。它管理 Codex Quota Guard
自己的应用账号，不接收或保存 Codex 官方账号凭据。

## 已提供的接口

- `POST /api/v1/auth/register`：注册并登录
- `POST /api/v1/auth/login`：密码登录
- `POST /api/v1/auth/refresh`：一次性 refresh token 轮换
- `POST /api/v1/auth/logout`：撤销 refresh 会话，需要 access token
- `GET /api/v1/users/me`：查询当前用户
- `PATCH /api/v1/users/me`：修改邮箱或昵称

密码使用 BCrypt 保存；JWT access token 默认有效 15 分钟；refresh token
原文只在响应中出现，数据库只保存 SHA-256 摘要。刷新时使用数据库悲观锁，
旧 token 重放会撤销同一会话族。登录、注册、刷新接口还包含单实例限流，
部署时仍建议在反向代理或网关增加全局限流。

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
- `CORS_ALLOWED_ORIGINS`，默认 `http://localhost:1420`，多个来源用逗号分隔
- `AUTH_RATE_LIMIT_PER_MINUTE`，默认每个 IP、每个认证端点 20 次/分钟
- `REQUIRE_HTTPS`，默认 false；`prod` profile 会强制设为 true

## 运行和验证

先创建 PostgreSQL 数据库与用户，并把上述变量注入当前终端，然后运行：

```bash
mvn -f services/web-backend/pom.xml spring-boot:run
```

Flyway 会自动执行 `V1`、`V2` 迁移。生产环境必须通过 HTTPS 或可信反向
代理对外提供服务，并启用 `prod` profile；不要把应用的 HTTP 监听端口直接
暴露到公网。

运行测试和覆盖率门槛：

```bash
mvn -f services/web-backend/pom.xml verify
```

测试使用 H2 的 PostgreSQL 兼容模式，但不会绕过 Flyway；核心认证与用户服务
的行覆盖率门槛为 80%。

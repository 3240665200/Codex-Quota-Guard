# Admin Backend

这是可独立部署的 Java 17 + Spring Boot 4 用户与管理员服务。它管理本系统用户、
团队和个人 Token 配额，不再管理或下发官方 Codex 登录凭据。

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
- `GET /api/v1/client/quota/overview`：当前系统用户的个人用量、团队共享额度和可用量
- `POST /api/v1/client/usage/events`：客户端幂等上报本机 Codex Token 事件
- `PUT /api/v1/client/official-quota`：客户端上报不含凭据的官方额度摘要
- `GET /api/v1/admin/users/{id}/quota-overview`：管理员查看个人 Token 用量
- `GET /api/v1/admin/teams/{id}/quota-overview`：管理员查看团队成员合计的 5 小时/7 天已用与剩余 Token
- `POST /api/v1/admin/users/{id}/quota-reset`：重置该用户的个人用量统计起点，不改变团队共享用量
- `POST /api/v1/admin/teams/{id}/quota-reset`：重置团队共享用量统计起点，不改变成员个人用量
- `GET /api/v1/admin/users/{id}/usage-events`：管理员分页查看该用户已入库的安全 Token 流水，不返回会话原文或设备令牌
- `GET /api/v1/admin/users/{id}/official-quota`：管理员查看最后一次客户端上报的官方额度
- `POST /api/v1/client/meter/enroll`：已登录系统用户配对本机 Rust 统计程序；一次性返回仅能上报的设备令牌
- `POST /api/v1/meter/heartbeat`、`POST /api/v1/meter/usage/events`：Rust 服务独立心跳和用量上报，使用设备令牌而非用户会话
- `GET /api/v1/admin/users/{id}/meter-devices`、`DELETE /api/v1/admin/users/{id}/meter-devices/{deviceId}`：管理员查看设备在线状态及撤销绑定

密码使用 BCrypt 保存；JWT access token 默认有效 15 分钟；refresh token
原文只在响应中出现，数据库只保存 SHA-256 摘要。刷新时使用数据库悲观锁，
旧 token 重放会撤销同一会话族。登录、注册、刷新接口还包含单实例限流，
部署时仍建议在反向代理或网关增加全局限流。
每个 access token 和 refresh session 都绑定用户的会话版本；管理员执行强制下线、
禁用账号或重置密码后，旧 token 会在下一次请求时立即失效。
设备令牌单独存储 SHA-256 摘要，只允许 `/api/v1/meter/**`，默认 30 天失效；管理员撤销后不能通过再次登录自行恢复同一设备 ID。

团队 Token 上限由全体成员共享；个人 Token 上限可作为额外约束。上报事件固定记录接收时的团队 ID，成员换队或删除后不会退回旧团队在当前滚动窗口内的已用量。V12 会按迁移时的成员归属回填旧事件；历史成员变动无法从旧数据中精确还原。
额度重置只保存新的统计起点，旧事件和审计流水不会删除或改写。重置前发生的事件即使延迟补报，也不计入重置后的额度；客户端之后产生的新事件照常计费。管理员操作需要确认，两个重置接口均受 `ROLE_ADMIN` 保护。

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
- `SERVER_ADDRESS`，默认 `0.0.0.0`（监听所有网卡；Windows 上可能显示为 `::` 双栈监听）
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

Flyway 会自动执行数据库迁移。V9 会清除旧托管账户与加密凭据，V11 新增计费设备表；上线前先完成并校验
受保护的数据库备份。生产环境必须通过 HTTPS 或可信反向
代理对外提供服务，并启用 `prod` profile；不要把应用的 HTTP 监听端口直接
暴露到公网。

运行测试和覆盖率门槛：

```bash
mvn -f apps/admin/backend/pom.xml verify
```

### Windows 本地固定启动

`scripts/start-local.ps1` 会先构建并测试，再按内容哈希复制独立运行包，避免修改了
源码但继续运行旧的 `admin-backend.jar`。它不会覆盖旧运行包或自动终止占用端口的进程。
默认监听 `0.0.0.0`，因此本机可用 `127.0.0.1:8080`，局域网设备可用本机的
WLAN IPv4（例如 `192.168.0.102:8080`）访问；`0.0.0.0` 不能作为客户端访问地址。
可通过 `-BindAddress` 改为 `127.0.0.1` 或本机已分配的私有局域网 IPv4。
Windows 防火墙入站规则应只允许可信局域网子网访问 TCP 8080。
局域网 HTTP 会明文传输系统密码，仅适合临时可信网络。

```powershell
& .\apps\admin\backend\scripts\start-local.ps1
& .\apps\admin\backend\scripts\start-local.ps1 -BindAddress 192.168.0.102
```

需要 Java 17 和 Maven 位于 PATH，或传入 `-JavaCommand` / `-MavenCommand` 完整路径。
后台不需要安装官方 Codex CLI；官方登录与额度读取在各用户电脑本地完成。
`-SkipBuild` 只用于已完成验证的构建；`-Port 18080` 可在切换前验证备用服务。
空库初始化管理员时，可一次性传入 `-BootstrapEmail` 与 `-BootstrapPassword`（后者为
`SecureString`，至少 12 位）；脚本不会把密码写入受保护配置。创建后应关闭该临时
启动的进程，正常启动无需再次传入这两个参数。

本机配置保存在 `D:\BuildCache\pingche\private\admin-backend.clixml`，该目录仅授予
当前 Windows 用户访问权限。文件是环境变量名到 `SecureString` 的哈希表，使用
`Export-Clixml` / `Import-Clixml` 和 Windows DPAPI 保存、读取；不会提交到 Git。
如需将现有配置切换到本机 `127.0.0.1:5432/pingche`，先创建空的 `pingche` 数据库，
再运行以下命令；脚本只替换三个数据库字段，并在同目录保留旧配置备份：

```powershell
$dbPassword = Read-Host 'PostgreSQL 密码' -AsSecureString
& .\apps\admin\backend\scripts\use-local-postgres.ps1 -DatabasePassword $dbPassword
```

本机数据库无需向局域网开放 5432；新空库会由 Flyway 创建表，但不会自动恢复旧库的
用户。正式部署不要长期使用 `postgres` 超级用户或弱密码。
已有部署必须保留原数据库和 JWT 密钥；V9 清除旧托管账户前必须校验备份。
旧 DPAPI 配置中若仍有凭据加密密钥，启动脚本仅保留其兼容读取能力，不再注入新服务进程；
备份到期后应按密钥保留策略一并清理。
该文件仅能由本机同一 Windows 用户解密，不能直接当作跨机器备份；正式部署应使用
受控密钥管理系统并制定恢复流程。此脚本不自动创建或重置管理员。

测试使用 H2 的 PostgreSQL 兼容模式，但不会绕过 Flyway；核心认证与用户服务
的行覆盖率门槛为 80%。

后台不发起官方 OpenAI 登录，不运行官方 Codex CLI，也不下发凭证。
个人 Token 用量来自用户电脑的会话日志上报，官方百分比由客户端读取后上报安全摘要。
详见[本机官方登录与个人 Token 统计](../../../docs/architecture/local-codex-token-quota.md)。

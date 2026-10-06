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
- `POST /api/v1/admin/codex-accounts/authorizations`：启动官方网页 / 设备码授权
- `GET /api/v1/admin/codex-accounts/authorizations/{id}`：仅发起者查询进度
- `DELETE /api/v1/admin/codex-accounts/authorizations/{id}`：取消授权并清理临时文件

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
- `CODEX_CREDENTIAL_ENCRYPTION_KEY`：新增/替换 Codex 凭据、启动授权时必需，至少 32 字节，
  且不能与 JWT 密钥相同；原先使用 JWT 回退密钥的旧记录需要先规划重加密，不能直接更换密钥
- `CODEX_LOGIN_COMMAND`：官方 Codex CLI 可执行文件，默认 `codex`；Windows 建议指定完整 `codex.exe` 路径
- `CODEX_AUTH_WORK_DIR`：服务器私有临时父目录，默认系统临时目录，可设为 D 盘的受保护目录
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

### Windows 本地固定启动

`scripts/start-local.ps1` 会先构建并测试，再按内容哈希复制独立运行包，避免修改了
源码但继续运行旧的 `admin-backend.jar`。它不会覆盖旧运行包或自动终止占用端口的进程。
默认只绑定 `127.0.0.1`；可通过 `-BindAddress` 指定本机已分配的私有局域网 IPv4，
并配合限制为本地子网的防火墙规则。局域网 HTTP 会明文传输系统密码，仅适合临时可信网络。

```powershell
& .\apps\admin\backend\scripts\start-local.ps1
& .\apps\admin\backend\scripts\start-local.ps1 -BindAddress 192.168.0.102
```

需要 Java 17 和 Maven 位于 PATH，或传入 `-JavaCommand` / `-MavenCommand` 完整路径。
Codex 桌面程序升级后，其带版本号的 `codex.exe` 路径可能变化。受保护配置中的
`CODEX_LOGIN_COMMAND` 若失效，本机启动脚本只会回退到当前 PATH 中位于
`%LOCALAPPDATA%\OpenAI\Codex\bin` 下、且具有有效 OpenAI 签名的 `codex.exe`；
找不到时会拒绝启动，不会改写受保护配置或改用不明来源程序。
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
用户或 Codex 账户。正式部署不要长期使用 `postgres` 超级用户或弱密码。
已有部署必须保留原数据库、JWT 与凭据加密密钥，不要通过生成新密钥解决启动故障。
该文件仅能由本机同一 Windows 用户解密，不能直接当作跨机器备份；正式部署应使用
受控密钥管理系统并制定恢复流程。此脚本不自动创建或重置管理员。

测试使用 H2 的 PostgreSQL 兼容模式，但不会绕过 Flyway；核心认证与用户服务
的行覆盖率门槛为 80%。

授权调用固定的官方 App Server 登录方法，正常结束会删除独立目录中的临时凭据。
`mode=BROWSER` 返回官方 `authUrl`，浏览器与后台需同机，使用 `localhost:1455` 回调；
同一实例至多一个网页登录，端口被占用时拒绝启动，不会主动取消其他应用的登录。
远程服务器使用 `mode=DEVICE_CODE`（省略 mode 时的兼容默认值），无须开放 1455 端口。
授权进程关闭插件功能，避免登录时自动下载插件缓存；Windows 清理会处理临时目录中的
只读文件，并对短暂文件占用做有限重试，失败时仍禁止保存凭据。
不启动任务执行、不使用 CLIProxyAPI，也不提供向客户端下发凭据的接口。
部署限制、异常退出清理和后续客户端架构见
[Codex 授权与凭据保管](../../../docs/architecture/codex-credential-custody.md)。

可手动启用真实官方进程的冒烟测试（获取并取消网页登录和设备码，不确认 OpenAI 登录）：

```powershell
mvn -f apps/admin/backend/pom.xml '-Dtest=OfficialCodexLoginLiveTest' '-Dcodex.login.live.command=C:\path\to\codex.exe' test
```

默认测试不会联网，此测试仅在显式提供上述参数时运行。

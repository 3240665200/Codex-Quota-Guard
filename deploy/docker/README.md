# Linux 服务器一键部署

一次启动 **中央 Java 后端 + 管理网页（Nginx）+ PostgreSQL**，默认使用 `http://服务器IP:8080`。网页和 Windows 客户端填写同一个地址；不需要再开放开发环境的 8000 端口。

桌面程序、本地 Java 和 Rust 统计服务仍安装在用户电脑，**不在服务器容器内运行**。

## 1. 准备服务器

- Linux，已安装 Docker Engine、Docker Compose **v2.24+**、Bash、OpenSSL；当前用户有权访问 Docker。
- 建议至少 2 核、4 GB 内存及 10 GB 可用磁盘，首次源码编译会占用额外内存和空间。
- 允许拉取 Docker 镜像、Maven 和 npm 依赖；邮箱辅助还需要服务器可访问邮箱 IMAPS（通常 993）、DNS 和 HTTPS（OAuth/备用 DNS）。脚本不修改代理或系统 DNS。
- 将**完整项目源码**上传服务器，保留 `apps/`、根目录的包清单/锁文件及 `deploy/docker/`；不要只上传这一个脚本。也不需要上传 `node_modules`、`target`、本机私密配置或 Windows 安装包。
- 防火墙/云安全组只需放行你选定的 HTTP 端口，并尽量限制访问来源。PostgreSQL 5432、Java 内部 8080 不单独映射到宿主机。

> 这是用户明确选择的 IP + HTTP 方案，不会重定向 HTTPS。HTTP 会明文传输密码、系统令牌及邮箱验证码，只适合可信网络或受控联调；公网正式使用请增加 HTTPS 入口。

## 2. 首次初始化并启动

在项目根目录执行，替换服务器 IP 和管理员邮箱：

```bash
bash deploy/docker/deploy.sh init http://192.0.2.10:8080 admin@example.com
bash deploy/docker/deploy.sh up
```

第一条命令会让你输入两次管理员密码（不回显），并生成 `deploy/docker/.env`：

- 数据库管理员、应用数据库账户和 JWT 分别使用独立随机密钥。
- 邮箱加密密钥为独立随机 32 字节，Base64 保存。
- `.env` 权限为仅当前用户可读写；已有文件不会被覆盖。
- 初始管理员邮箱只是本系统的登录名，**不是邮箱监听的连接配置**。邮箱授权仍在后台「官方账户 → 邮箱配置」里设置。

第二条命令在容器内编译后端、管理前端，启动数据库并自动执行 Flyway 迁移，等待服务健康检查通过。首次下载/编译可能较慢。构建不需要服务器安装 Java、Node.js、Maven 或 pnpm；构建镜像中固定 pnpm 10.12.3 并使用项目锁文件。

完成后：

- 后台网页：`http://192.0.2.10:8080`
- 桌面客户端的中央服务器地址：`http://192.0.2.10:8080`
- 后台管理员：初始化时输入的邮箱和密码。

脚本可以在任意工作目录调用，只需给出脚本的实际路径。初次初始化不要填写 `0.0.0.0`，另一台电脑也不要填写 `127.0.0.1`。

首次确认管理员可正常登录后，可以编辑 `.env` 清空 `ADMIN_BOOTSTRAP_EMAIL` 和 `ADMIN_BOOTSTRAP_PASSWORD`，再执行 `up` 移除容器中的初始密码。**不会删除或重设已创建的管理员**。忘记密码时修改这两个值也不会自动重置已有管理员。

## 3. 常用操作

```bash
# 查看容器及健康状态
bash deploy/docker/deploy.sh status

# 持续查看后端日志；Ctrl+C 只退出日志查看，不停止服务
bash deploy/docker/deploy.sh logs backend

# 也可以查看 web / postgres；省略服务名则查看全部
bash deploy/docker/deploy.sh logs web

# 备份数据库，同时保存解密所需的部署配置
bash deploy/docker/deploy.sh backup

# 停止服务，保留所有数据库数据
bash deploy/docker/deploy.sh stop

# 再次启动，或替换源码后重新构建升级
bash deploy/docker/deploy.sh up
```

容器配置 `unless-stopped`：Docker 服务开机运行时会恢复容器；手动 `stop` 后不会自行恢复，需再次 `up`。数据库持久卷默认名 `codex-quota-guard_pgdata`。日志每个容器最多保留 3 份、每份 10 MB；长期审计请另做日志归档。

`backup` 生成 `deploy/docker/backups/<时间>-<随机后缀>/`，内含 `database.dump` 和 `deployment.env`。目录仅当前用户可访问；备份失败只留下 `.incomplete.*`，不会被标记为成功备份。**备份含邮箱密文及其解密密钥，必须加密离线保存，不能发到聊天或上传 GitHub。** 脚本不自动清理旧备份，请监控磁盘空间。

## 4. 修改端口、访问地址

编辑 `deploy/docker/.env` 后执行 `up`：

```dotenv
HTTP_BIND=0.0.0.0
HTTP_PORT=8080
PUBLIC_ORIGIN=http://192.0.2.10:8080
```

`PUBLIC_ORIGIN` 是浏览器实际访问的完整来源地址，不包含路径或尾部斜杠；它用于 CORS。更换端口时同时修改 `HTTP_PORT` 和地址中的端口。IPv6 或高级拓扑可手动配置，不在初始化脚本的简易地址输入范围内。

Compose 默认读取指定 `.env`，但 Docker 官方规则允许同名 shell 环境变量覆盖它。**不要从其他项目导出同名密码/密钥后执行本脚本**，也不要运行会打印完整密钥的 `docker compose config` 或将 `docker inspect` 输出发到公共渠道。

以后接域名 HTTPS：由同一宿主机的宝塔/1Panel/Nginx 终止 TLS，转发到 `127.0.0.1:8080`；将 `HTTP_BIND` 改为 `127.0.0.1`、`PUBLIC_ORIGIN` 改为实际 `https://域名`，再 `up`。外部入口负责 HTTP→HTTPS，**不要单独打开后端端口**。本配置的内部链路仍是 HTTP，保持 `APP_REQUIRE_HTTPS=false`；不能仅切换该值而不同时设计可信转发链，否则会产生重定向问题。多容器反代不能把容器自己的 `127.0.0.1` 当成宿主机，需另行配置私有网络。

## 5. 升级与迁移现有数据

**现有电脑的数据不会自动出现在服务器。** 新卷会创建空库；如需保留原用户、团队、流水和邮箱配置，必须迁移旧数据库，不能只复制 JAR 或 Windows 的 `.clixml` 文件。

升级前运行 `backup`，另外保存当前 Git 提交/版本和镜像信息，再更新源码并执行 `up`。不要重新 `init`，不要更换 `JWT_SECRET`、`JWT_ISSUER` 或 `MAIL_ASSISTANCE_ENCRYPTION_KEY`。只修改 `.env` 中数据库密码并不能同步修改持久卷内已有的 PostgreSQL 角色密码。

从旧服务器迁入需要管理员先备份完整 PostgreSQL 数据库（含 `flyway_schema_history`），安全迁移原 JWT 和邮箱加密密钥，停止应用写入，再由数据库管理员恢复到目标库。使用本方案时应用角色为非超级用户 `quota_guard`，需确认还原后的表/序列所有权适配它。Windows DPAPI 的私密 `.clixml` 不能在 Linux 直接解密使用；不要将它打入镜像。

Flyway 默认开启迁移和校验，历史 V9 包含旧托管凭证表删除，**不要跳过备份、关闭校验或自动 repair**。发生有数据结构变化的升级后，回退不一定只换旧镜像就够，可能必须恢复升级前数据库及对应 `.env`。脚本故意不提供一键清库或自动恢复，避免误覆盖。

**禁止使用 `docker compose down -v` 删除生产持久卷；Docker 卷不是备份。** 同一宿主机默认只能用本脚本部署这一套实例，项目名固定，避免工作目录变化误建第二个数据库。

## 6. 排障和验证边界

- `up` 报 Docker 无权限：检查 Docker 服务和当前用户权限；不要对数据目录随意 `chmod 777`。
- 拉取镜像/下载依赖失败：检查服务器访问镜像仓库、Maven Central、npm 的网络，不要将 Windows 代理的 Fake-IP 写进容器 DNS。
- 后端不健康：先看 `logs backend`。常见原因是旧数据库密码与 `.env` 不匹配、迁移失败、内存不足或 bootstrap 配置不合法；不要删除数据库卷重试。
- `/api/v1/users/me` 未登录返回 **401 是预期结果**，可检查反向代理是否通畅；不要期待 200，也不要因此关闭认证。
- `healthz` 只检测 Nginx；后端检查验证 HTTP 认证链存活、数据库另用 `pg_isready`。这些探针不验证真实登录、业务查询或邮箱连通；部署后仍须用自己的管理员账户完成一次登录和测试邮箱配置。
- 邮件监听失败：确认 Linux 服务器可访问邮件服务商，已有 `.env` 邮箱密钥与数据库匹配。容器时区为 UTC，浏览器按本地时间显示。

开发者可在仓库执行 `node --test deploy/docker/tests/deploy.test.mjs`（需要 Node、Bash、OpenSSL，仅用于脚本测试，不是部署依赖）。测试使用模拟 Docker，不连接真实数据库或邮箱，**不等于 Docker 镜像构建和容器运行通过**。

部署配置参考：[Docker Compose 启动等待](https://docs.docker.com/reference/cli/docker/compose/up/)、[环境变量优先级](https://docs.docker.com/compose/how-tos/environment-variables/envvars-precedence/)、[官方镜像清单](https://github.com/docker-library/official-images/tree/master/library)。基础镜像按明确版本系列选取、未使用 `latest`，系列标签仍会随安全补丁更新；需严格复现时应另外记录并固定镜像 digest。

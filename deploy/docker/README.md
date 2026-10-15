# Linux 服务器一键部署

一次启动 **中央 Java 后端 + 管理网页（Nginx）+ PostgreSQL**，默认使用 `http://服务器IP:8080`。网页和 Windows 客户端填写同一个地址；不需要再开放开发环境的 8000 端口。

桌面程序、本地 Java 和 Rust 统计服务仍安装在用户电脑，**不在服务器容器内运行**。

本次服务器发布目标为 Linux amd64 的后端/网页 `0.3.19` 和可选后端 `0.3.19-browser`，源码标签为 `server-v0.3.19`；旧 `0.3.18` 镜像保持可用。只想拉取镜像时使用[registry 部署说明](./registry/README.md)，本页的 `--build` 命令用于源码部署。桌面源码版本仍为 `0.3.18`，配套桌面端需 0.3.14+（建议 0.3.18）；本次不更新桌面或新增安装包 Release。

## 服务器网页登录（可选）

管理页支持在网页中操作服务器独立浏览器，完成官方登录并由后台获取限定 Cookie。HTTP 与 HTTPS 均可使用；HTTP 会明文传输密码、授权及浏览器输入，推荐 HTTPS。默认普通后台镜像不安装浏览器，启用网页浏览器需使用下面的 overlay。

源码构建的 HTTP 浏览器模式组合两个配置（Compose 2.24.4+），保留 `.env` 中的 HTTP `PUBLIC_ORIGIN`，不需要 TLS 证书：

```sh
cd deploy/docker
docker compose --env-file .env -f compose.yaml -f compose.web-login.yaml config --quiet
docker compose --env-file .env -f compose.yaml -f compose.web-login.yaml up -d --build --wait
```

需要强制 HTTPS 时，先按下文配置 `TLS_CERT_DIRECTORY`、可信证书和 HTTPS `PUBLIC_ORIGIN`，再在以上两条命令中追加 `-f compose.https.yaml`（放在 `config` 或 `up` 前）。所有模式都保留现有数据库及加密密钥。浏览器目标增加 Chromium 和中文字体，后台内存上限 2 GiB、共享内存 256 MiB、临时目录 512 MiB，最多两个临时浏览器；建议整机至少 4 GiB 可用内存。后续操作继续传入所选全部 overlay；`deploy.sh up` 只读取基础 Compose。

`chromium-seccomp.json` 基于 Playwright v1.64.0 派生，出处、修改记录及 Apache-2.0 许可位于 `licenses/`。它保留默认拒绝策略，额外为 Chromium 子用户命名空间沙箱允许 `chroot` 系统调用，不授予容器 `CAP_SYS_CHROOT`；仍搭配非 root、capabilities 全部移除及 no-new-privileges。不要关闭浏览器沙箱、使用 privileged/SYS_ADMIN 或公开调试端口。宿主机需允许非特权用户命名空间；Docker/内核兼容仍需在目标服务器验证。

Cookie 登录目前支持邮箱方式；外部 SSO 会明确提示不支持。官方验证码、人机验证仍由管理员本人完成，不能保证官方接受所有服务器网络。无论页面还是测试都不代替用户登录。新子菜单为“官方账户 → 账户管理 / 会话管理”。V28 扩展任务来源和管理员版本信息，不清除旧授权；部署重启会使未完成的服务器浏览器任务失效。

普通 `runtime` 镜像不含 Chromium，不能仅通过增加环境变量获得浏览器。拉取部署使用本次目标 `0.3.19-browser` 及 [registry 专用 overlay](./registry/compose.web-login.yaml)，其中不包含 `build` 配置。基础 `APP_REQUIRE_HTTPS=false` 允许 HTTP 和 HTTPS 官方管理操作，不限 localhost；HTTPS overlay 设置为 `true` 时仍只接受 HTTPS。页面提示 HTTP 风险但不因协议禁用操作；上游官方 TLS 校验、密钥加密、鉴权及沙箱不变。

## 1. 准备服务器

- Linux amd64，已安装 Docker Engine、Docker Compose **v2.24.4+**、Bash、OpenSSL；当前用户有权访问 Docker。
- 建议至少 2 核、4 GB 内存及 10 GB 可用磁盘，首次源码编译会占用额外内存和空间。
- 允许拉取 Docker 镜像、Maven 和 npm 依赖；邮箱辅助还需要服务器可访问邮箱 IMAPS（通常 993）、DNS 和 HTTPS（OAuth/备用 DNS）。脚本不修改代理或系统 DNS。
- 将**完整项目源码**上传服务器，保留 `apps/`、根目录的包清单/锁文件及 `deploy/docker/`；不要只上传这一个脚本。也不需要上传 `node_modules`、`target`、本机私密配置或 Windows 安装包。
- 防火墙/云安全组只需放行选定的网站 HTTP/HTTPS 入口端口，并尽量限制访问来源。PostgreSQL 5432、Java 内部 8080 不单独映射到宿主机。

> 基础配置使用 IP + HTTP，不重定向 HTTPS。HTTP 会明文传输密码、系统令牌、官方授权、浏览器输入及邮箱验证码；推荐通过可选 HTTPS 入口保护传输。

## 2. 首次初始化并启动

在项目根目录执行，替换服务器 IP 和管理员邮箱：

```bash
bash deploy/docker/deploy.sh init http://192.0.2.10:8080 admin@example.com
bash deploy/docker/deploy.sh up
```

第一条命令会让你输入两次管理员密码（不回显），并生成 `deploy/docker/.env`：

- 数据库管理员、应用数据库账户和 JWT 分别使用独立随机密钥。
- 邮箱、官方会话与代理各自使用独立随机 32 字节加密密钥，Base64 保存。
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

`backup` 生成 `deploy/docker/backups/<时间>-<随机后缀>/`，内含 `database.dump` 和 `deployment.env`。目录仅当前用户可访问；备份失败只留下 `.incomplete.*`，不会被标记为成功备份。**备份含邮箱、官方会话、代理密文及其解密密钥，必须加密离线保存，不能发到聊天或上传 GitHub。** 脚本不自动清理旧备份，请监控磁盘空间；overlay、证书与自定义配置需另行备份。

## 4. 修改端口、访问地址

编辑 `deploy/docker/.env` 后执行 `up`：

```dotenv
HTTP_BIND=0.0.0.0
HTTP_PORT=8080
PUBLIC_ORIGIN=http://192.0.2.10:8080
```

`PUBLIC_ORIGIN` 是浏览器实际访问的完整来源地址，不包含路径或尾部斜杠；它用于 CORS。更换端口时同时修改 `HTTP_PORT` 和地址中的端口。IPv6 或高级拓扑可手动配置，不在初始化脚本的简易地址输入范围内。

Compose 默认读取指定 `.env`，但 Docker 官方规则允许同名 shell 环境变量覆盖它。**不要从其他项目导出同名密码/密钥后执行本脚本**，也不要运行会打印完整密钥的 `docker compose config` 或将 `docker inspect` 输出发到公共渠道。

本仓库提供 `compose.https.yaml` 与 `nginx.https.conf`：由网页容器终止 TLS、覆盖可信转发头，并将后端 `APP_REQUIRE_HTTPS` 设为 `true`。准备域名与可信证书，在原 `.env` 中配置：

```dotenv
PUBLIC_ORIGIN=https://quota.example.com
TLS_CERT_DIRECTORY=/etc/codex-quota-guard/tls
HTTPS_BIND=0.0.0.0
```

证书目录必须为服务器上实际存在的绝对路径，包含 `fullchain.pem`、`privkey.pem`，且可由容器内非 root 网页进程读取；不要把证书私钥提交 Git。放行 443 后，在 `deploy/docker` 中执行：

```bash
docker compose --env-file .env -f compose.yaml -f compose.https.yaml config --quiet
docker compose --env-file .env -f compose.yaml -f compose.https.yaml up -d --build --wait
```

同时启用服务器网页登录时，在这两条 HTTPS 命令中加入 `-f compose.web-login.yaml`（放在 `config` 或 `up` 前）。后续操作必须携带相同 overlay；`deploy.sh up` 不自动加载 HTTPS 配置。该模式将 HTTP 业务入口重定向到 HTTPS；数据库、后端与浏览器调试端口不对外发布。若使用宝塔/1Panel/其他外部反代，需建立可信转发链；仅修改 `PUBLIC_ORIGIN` 不会启用 TLS，仅设 `APP_REQUIRE_HTTPS=true` 而未提供可信 HTTPS 入口会拒绝 HTTP 或产生重定向问题。

## 5. 升级与迁移现有数据

**现有电脑的数据不会自动出现在服务器。** 新卷会创建空库；如需保留原用户、团队、流水和邮箱配置，必须迁移旧数据库，不能只复制 JAR 或 Windows 的 `.clixml` 文件。

从 `0.3.18` 升级到本次 `0.3.19` **没有新增数据库迁移**，不更新桌面客户端；按所选 HTTP/HTTPS 模式同时更新后端与管理网页，保留原 `.env`、数据库和全部密钥，并先备份。HTTP 浏览器用两个配置，强制 HTTPS 浏览器用三个配置；详见[拉取部署升级说明](./registry/README.md#从-0318-升级)。

从 `0.3.11` 升级到本次 `0.3.19` 会执行 **V22–V30**。**V29 迁移前必须停止所有旧后端、队列 worker、定时任务及其他写入副本，禁止新旧 writer 混跑。** 先保存当前 Git 提交/版本、镜像及配置，在维护窗口停止应用、保留 PostgreSQL 运行，然后运行 `backup` 保存原数据库和 `.env`；确认备份成功后再更新源码、合并配置并执行所选组合的 `up`。

例如在 `deploy/docker` 中先执行 `docker compose --env-file .env -f compose.yaml stop web backend`（如已有 overlay，同样带上全部 `-f` 参数），再执行 `bash deploy.sh backup`。其他机器或非 Compose 方式启动的旧写入进程也要停止。不要先使用会停止全部容器的 `deploy.sh stop`，否则备份时数据库不可用。

不要重新 `init`，不要更换 `JWT_SECRET`、`JWT_ISSUER`、`MAIL_ASSISTANCE_ENCRYPTION_KEY`、`OFFICIAL_SESSION_ENCRYPTION_KEY` 或 `MANAGED_PROXY_ENCRYPTION_KEY`。已有 `.env` 不会自动补新密钥；官方会话密钥用于建立官方连接及新绑定，代理密钥用于保存代理节点。只有从未配置且数据库中没有相应密文的缺失项才可分别生成新密钥（`openssl rand -base64 32`），原值丢失时必须找回匹配备份。缺密钥时健康检查可能通过，但相应业务不可用。只修改 `.env` 中数据库密码并不能同步修改持久卷内已有的 PostgreSQL 角色密码。

从旧服务器迁入需要管理员停止应用写入后备份完整 PostgreSQL 数据库（含 `flyway_schema_history`），安全迁移原 JWT 和所有加密密钥，再由数据库管理员恢复到目标库。使用本方案时应用角色为非超级用户 `quota_guard`，需确认还原后的表/序列所有权适配它。Windows DPAPI 的私密 `.clixml` 不能在 Linux 直接解密使用；不要将它打入镜像。

Flyway 默认开启迁移和校验，历史 V9 包含旧托管凭证表删除，**不要跳过备份、关闭校验或自动 repair**。从 `0.3.11` 跨越的 V22–V30 改变结构和绑定/退出策略，不能假定只换回 `0.3.11` 镜像即可安全回退。优先前向修复；必须回退时，在停写状态下恢复升级前数据库及匹配 `.env`、配置和旧镜像，并接受备份之后数据的损失。脚本不提供一键清库或自动恢复。升级完成需确认 Flyway 到 V30，并验收管理员登录、客户端设备配对与访问核验。

**禁止使用 `docker compose down -v` 删除生产持久卷；Docker 卷不是备份。** 同一宿主机默认只能用本脚本部署这一套实例，项目名固定，避免工作目录变化误建第二个数据库。

## 6. 排障和验证边界

- `up` 报 Docker 无权限：检查 Docker 服务和当前用户权限；不要对数据目录随意 `chmod 777`。
- 拉取镜像/下载依赖失败：检查服务器访问镜像仓库、Maven Central、npm 的网络，不要将 Windows 代理的 Fake-IP 写进容器 DNS。
- 后端不健康：先看 `logs backend`。常见原因是旧数据库密码与 `.env` 不匹配、迁移失败、内存不足或 bootstrap 配置不合法；不要删除数据库卷重试。
- `/api/v1/users/me` 未登录返回 **401 是预期结果**，可检查反向代理是否通畅；不要期待 200，也不要因此关闭认证。
- `healthz` 只检测 Nginx；后端检查验证 HTTP 认证链存活、数据库另用 `pg_isready`。这些探针不验证真实登录、业务查询或邮箱连通；部署后仍须用自己的管理员账户完成一次登录和测试邮箱配置。
- 邮件监听失败：确认 Linux 服务器可访问邮件服务商，已有 `.env` 邮箱密钥与数据库匹配。容器时区为 UTC，浏览器按本地时间显示。

开发者可在仓库执行 `node --test deploy/docker/tests/*.test.mjs`（需要 Node、Bash、OpenSSL，仅用于测试，不是部署依赖）。设置 `COMPOSE_BINARY` 为独立 `docker-compose` 可执行文件的完整路径后，还会通过实际 Compose 解析器检查 registry 基础/浏览器的 HTTP 与 HTTPS，以及源码浏览器两种模式；这些检查仅运行 `config`，使用合成配置，不启动容器。其他脚本测试使用模拟 Docker，不连接真实数据库或邮箱，**不等于 Docker 镜像构建和容器运行通过**。

部署配置参考：[Docker Compose 启动等待](https://docs.docker.com/reference/cli/docker/compose/up/)、[环境变量优先级](https://docs.docker.com/compose/how-tos/environment-variables/envvars-precedence/)、[官方镜像清单](https://github.com/docker-library/official-images/tree/master/library)。基础镜像按明确版本系列选取、未使用 `latest`，系列标签仍会随安全补丁更新；需严格复现时应另外记录并固定镜像 digest。

# Docker Hub 拉取部署

本次服务器发布目标为 `0.3.19`，源码标签为 `server-v0.3.19`，面向 **Linux amd64 / x86_64**。以下下载命令在对应标签和镜像发布完成后使用；生产部署建议固定版本。

| 组件 | 镜像 |
| --- | --- |
| 普通后端，不含 Chromium | `minjm/codex-quota-guard-backend:0.3.19` |
| 管理网页 | `minjm/codex-quota-guard-web:0.3.19` |
| 可选服务器网页登录后端 | `minjm/codex-quota-guard-backend:0.3.19-browser` |
| 数据库 | 官方 `postgres:17-alpine` |

镜像仓库：[后端](https://hub.docker.com/r/minjm/codex-quota-guard-backend) · [管理网页](https://hub.docker.com/r/minjm/codex-quota-guard-web)。服务器不需要源码、Java、Node.js 或 Maven；需要 Docker Engine、Compose **v2.24.4+**、Bash、OpenSSL，以及访问 Docker Hub 的网络。下载配置另外需要 `curl`。这不是 Windows 容器或 ARM 镜像。

保留既有 `0.3.18`、`0.3.18-browser` 和 `0.3.11` 镜像，不覆盖旧标签。不要依赖 `latest` 判断版本或自动升级；数据库变化后，保留旧镜像也不表示可以直接切回。

## 首次部署

已拿到拉取部署包时，先解压到服务器。如果没有部署包，可直接下载这 5 个小配置文件，无需下载源码；下载步骤额外需要 `curl`：

```bash
(
  set -e
  mkdir codex-quota-guard-server
  cd codex-quota-guard-server
  qg_base="https://raw.githubusercontent.com/3240665200/Codex-Quota-Guard/server-v0.3.19/deploy/docker"
  curl -fSL --fail-early --remote-name-all \
    "$qg_base/registry/compose.yaml" \
    "$qg_base/registry/start.sh" \
    "$qg_base/deploy.sh" \
    "$qg_base/.env.example" \
    "$qg_base/init-db.sql"
)
```

下载地址固定到与 `0.3.19` 镜像配套的 `server-v0.3.19` 服务器源码标签，与 Windows 安装包版本独立。已有同名目录时下载步骤会停止，不覆盖已有部署。

进入包含 `start.sh` 的目录（上述下载方式为 `cd codex-quota-guard-server`），替换服务器 IP 和管理员邮箱后执行：

```bash
bash start.sh init http://服务器IP:8080 管理员邮箱
bash start.sh up
```

初始化提示设置管理员密码，生成独立数据库密码、JWT、邮箱、官方会话及代理加密密钥，保存在权限受限的 `.env`；已有 `.env` 不会被覆盖或自动补字段。启动自动拉取固定镜像并等待健康检查，不在服务器编译。初始化入口只接受 HTTP 地址；需要 HTTPS 时，先初始化，再按下文配置证书与 `PUBLIC_ORIGIN`，最后用组合命令启动。

管理网页和桌面端使用同一个中央服务地址。基础 Compose 设置 `APP_REQUIRE_HTTPS=false`，允许 HTTP 和 HTTPS，包括官方授权导入、会话管理与服务器网页登录，不再仅限 localhost。HTTP 页面只提示风险，不因协议禁用功能；**HTTP 会明文传输系统密码、授权及浏览器输入，推荐使用 HTTPS**。服务器到官方站点的 TLS 校验、鉴权、密钥加密和沙箱规则不变。数据库、Java 内部端口和 Chromium 调试端口都不单独公开。

桌面端应为支持设备标识的 **0.3.14 或更高版本，建议 0.3.18**。本次发布范围为源码与服务器镜像，0.3.18 Windows 本地测试安装包尚未作为新的 GitHub Release 上传；请先准备配套客户端再升级服务器，不能假定既有公开安装包兼容新绑定流程。

## 从 0.3.18 升级

`0.3.19` 调整管理入口的传输策略，**没有新增数据库迁移**，不更新桌面客户端。升级前停止应用并备份数据库与原 `.env`，保留项目名、数据库卷、全部密钥和自定义配置。后端、网页同时改为 `0.3.19`；启用服务器浏览器时同步更新 registry overlay 至 `0.3.19-browser`。不要覆盖原 `.env` 或重新执行 `init`。

继续使用 HTTP 时，保留基础 Compose 的 `APP_REQUIRE_HTTPS=false`；浏览器只需基础配置加 browser overlay。继续强制 HTTPS 时，保留 `compose.https.yaml`，其 `APP_REQUIRE_HTTPS=true` 与 HTTP→HTTPS 重定向保持生效。启动命令见下一节；升级后验收管理员登录、官方管理入口和客户端访问核验。

## 从 0.3.11 或更早版本升级

`0.3.19` 包含此前的自动绑定与交接、取得名额后 30 分钟登录期限、至少间隔 10 秒的官方会话监测、官方账户与会话管理，以及区分系统用户退出和官方会话退出的管理操作。从 `0.3.11` 升级会执行 **V22–V30 数据库迁移**。

**V29 要求所有旧应用写入进程先停止，禁止新旧队列 writer 混跑。** 迁移增加代际校验字段，旧程序不会检查它们。按以下顺序在原部署目录操作：

1. 记录现有镜像版本、Compose 项目名、卷名和自定义配置。安排维护窗口，停止所有连接这套数据库的旧后端、定时任务和副本；本部署可执行下列 `stop web backend`，PostgreSQL 保持运行。
2. 停写后执行 `backup`，确认生成非空 `database.dump` 和原始 `deployment.env`，加密保存到服务器之外。另存原 Compose、overlay 和证书配置，便于恢复。
3. 合并新版本配置，保留原 `.env`、项目名 `codex-quota-guard`、`pgdata` 卷及所有已有密钥。后端和网页镜像必须一起更新，旧 `compose.yaml` 还需补齐下述环境变量映射。
4. 用与实际部署一致的基础/可选组合启动新版本，确认 Flyway 完成 V30、容器健康、管理员登录、客户端设备配对与访问核验正常，再结束维护窗口。

```bash
# 使用原配置停止应用，不停止或删除数据库。
docker compose --project-name codex-quota-guard --env-file .env -f compose.yaml stop web backend
bash start.sh backup
```

如果旧部署已有 overlay，停止命令也应带上原来的全部 `-f` 参数；另外停止其他机器或非 Compose 方式启动的旧写入进程。`backup` 需要 PostgreSQL 运行，不要先执行停止全部容器的 `bash start.sh stop`。

普通部署的两个镜像改为：

```text
minjm/codex-quota-guard-backend:0.3.19
minjm/codex-quota-guard-web:0.3.19
```

旧 Compose 的 `services.backend.environment` 合并下列映射（若文件为 JSON，使用等价的 JSON 键值格式）：

```yaml
OFFICIAL_SESSION_ENCRYPTION_KEY: "${OFFICIAL_SESSION_ENCRYPTION_KEY:-}"
MANAGED_PROXY_ENCRYPTION_KEY: "${MANAGED_PROXY_ENCRYPTION_KEY:-}"
MANAGED_PROXY_MAX_PROCESSES: "${MANAGED_PROXY_MAX_PROCESSES:-4}"
MANAGED_PROXY_ALLOW_PRIVATE_NODES: "${MANAGED_PROXY_ALLOW_PRIVATE_NODES:-false}"
```

核对 `.env`：`OFFICIAL_SESSION_ENCRYPTION_KEY` 用于建立官方连接及新绑定，`MANAGED_PROXY_ENCRYPTION_KEY` 用于保存代理节点。缺失密钥不一定使健康检查失败，但相应业务不可用。只有从未配置过对应密钥、数据库也没有对应密文时，才为缺失项分别生成独立的 32 字节 Base64 密钥（`openssl rand -base64 32`），通过安全编辑器保存；配置过的密钥必须沿用原值，丢失时应找回原备份，不要生成替代值。不要将 `.env` 当脚本 `source` 执行或把密钥贴到日志、聊天、GitHub。

普通基础部署完成配置后执行：

```bash
bash start.sh up
```

启用 HTTPS 或服务器浏览器的部署使用下一节完整的组合命令，不能用只读取基础配置的 `start.sh up` 代替。不要重跑 `init`、用 `.env.example` 替换真实 `.env`、更换数据库密码/JWT/任何加密密钥，或执行 `docker compose down -v`。

升级失败时先保持停写并检查日志。**不可假定把两个镜像换回 `0.3.11` 就安全回退**；表结构和绑定/退出策略已经改变。优先修复前向升级；需要恢复旧版本时，应在停写状态下由数据库管理员恢复升级前数据库及匹配的 `.env`、配置和旧镜像，明确接受备份之后数据的损失，再验证业务。不要自动 `flyway repair` 或跳过迁移校验。

## HTTPS 与服务器网页登录（可选）

普通后端镜像来自 `runtime` 目标，**不含 Chromium**。服务器网页登录使用独立的 `0.3.19-browser` 镜像及本目录的 `compose.web-login.yaml`；不要使用上一层包含 `build.target` 的源码部署 overlay。HTTP 与 HTTPS 均需这套浏览器镜像和安全参数，HTTPS overlay 是可选的传输策略。

在部署目录取得以下附加文件，与基础 `compose.yaml` 放在同一层：

```bash
(
  set -e
  qg_base="https://raw.githubusercontent.com/3240665200/Codex-Quota-Guard/server-v0.3.19/deploy/docker"
  curl -fSL --fail-early --remote-name-all \
    "$qg_base/registry/compose.web-login.yaml" \
    "$qg_base/chromium-seccomp.json" \
    "$qg_base/compose.https.yaml" \
    "$qg_base/nginx.https.conf"
  mkdir -p licenses
  curl -fSL "$qg_base/licenses/README.md" -o licenses/README.md
  curl -fSL "$qg_base/licenses/playwright-LICENSE" -o licenses/playwright-LICENSE
)
```

HTTP 浏览器模式保留 `.env` 中的 `PUBLIC_ORIGIN=http://服务器IP:8080`，不需要证书或 `TLS_CERT_DIRECTORY`，执行：

```bash
docker compose --project-name codex-quota-guard --env-file .env -f compose.yaml -f compose.web-login.yaml config --quiet
docker compose --project-name codex-quota-guard --env-file .env -f compose.yaml -f compose.web-login.yaml up -d --no-build --pull always --wait --wait-timeout 240
docker compose --project-name codex-quota-guard --env-file .env -f compose.yaml -f compose.web-login.yaml ps
```

推荐的 HTTPS 模式另外准备可由容器内非 root 网页进程读取的可信 TLS 证书，目录内包含 `fullchain.pem` 和 `privkey.pem`。证书目录通过只读挂载提供，不放进镜像或 Git。使用安全编辑器在原 `.env` 中设置：

```dotenv
PUBLIC_ORIGIN=https://quota.example.com
TLS_CERT_DIRECTORY=/etc/codex-quota-guard/tls
HTTPS_BIND=0.0.0.0
```

域名需解析到服务器并放行 443；`TLS_CERT_DIRECTORY` 必须是实际存在的绝对路径。此 HTTPS overlay 在容器内终止 TLS、覆盖可信转发头，并将 `APP_REQUIRE_HTTPS=true` 设为只接受 HTTPS；HTTP 业务入口重定向 HTTPS。不要只修改该值而不配置可信反向代理链。

```bash
docker compose --project-name codex-quota-guard --env-file .env -f compose.yaml -f compose.web-login.yaml -f compose.https.yaml config --quiet
docker compose --project-name codex-quota-guard --env-file .env -f compose.yaml -f compose.web-login.yaml -f compose.https.yaml up -d --no-build --pull always --wait --wait-timeout 240
docker compose --project-name codex-quota-guard --env-file .env -f compose.yaml -f compose.web-login.yaml -f compose.https.yaml ps
```

仅需 HTTPS、不启用服务器浏览器时，从这三条命令中移除 `-f compose.web-login.yaml`。后续启动、升级、查看日志和停止应用都使用同样的组合；`start.sh` 只读取基础 `compose.yaml`，不会自动加载 overlay。`bash start.sh backup` 仍可备份同一项目的 PostgreSQL 与 `.env`，overlay 和证书需另外备份。

浏览器模式保留非 root、`cap_drop: ALL`、只读根文件系统和 `no-new-privileges`，通过 `chromium-seccomp.json` 保持 Chromium 命名空间沙箱。该配置基于 Playwright 派生，只增加子用户命名空间沙箱所需的 `chroot` 系统调用许可，不授予容器 `CAP_SYS_CHROOT`；出处、修改记录与 Apache-2.0 许可保留在 `licenses/`。不要添加 `--no-sandbox`、privileged、SYS_ADMIN 或公开 Chromium 调试端口。宿主机须允许非特权用户命名空间，兼容性需在目标服务器验证；后端限制 2 GiB 内存、256 MiB 共享内存、512 MiB 临时目录，建议整机至少 4 GiB 可用内存。

传输策略与浏览器隔离独立：允许 HTTP 不会关闭沙箱、上游 TLS 校验或管理员权限检查。HTTP 风险提示不会额外阻止操作，管理员仍须本人完成官方验证码或人机验证；当前邮箱登录支持不代表支持外部 SSO，也不能保证官方接受服务器网络。重启会令未完成的浏览器任务失效。

## 日常操作

以下简写适用于普通基础部署：

```bash
bash start.sh status
bash start.sh logs backend
bash start.sh backup
bash start.sh stop
```

`stop` 停止全部服务但保留数据库卷；`.env` 与备份含敏感信息，不要公开。原电脑的数据不会自动迁入服务器。健康检查只验证进程、认证入口和数据库基本存活，不代替真实登录、绑定、官方连接、邮箱或代理连通性验收。

基础分发包包含 `compose.yaml`、`start.sh`、`deploy.sh`、`.env.example`、`init-db.sql` 和本说明。组装时从上一层复制公共脚本/模板；可选功能按上文添加 overlay、seccomp、Nginx 配置及许可证。不要分发真实 `.env`、证书私钥或数据库。

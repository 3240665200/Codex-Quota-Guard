# Docker Hub 拉取部署

本配置面向服务器 `0.3.28`，后端与管理网页版本一致，平台为 **Linux amd64 / x86_64**，配套源码标签为 `server-v0.3.28`。生产部署固定版本，不依赖 `latest`；变更范围见 [服务器 0.3.28 发布说明](../../../docs/releases/server-v0.3.28.md)。

| 组件 | 镜像 |
| --- | --- |
| 普通后端，不含 Chromium | `minjm/codex-quota-guard-backend:0.3.28` |
| 管理网页 | `minjm/codex-quota-guard-web:0.3.28` |
| 数据库 | 官方 `postgres:17-alpine` |

镜像仓库：[后端](https://hub.docker.com/r/minjm/codex-quota-guard-backend) · [管理网页](https://hub.docker.com/r/minjm/codex-quota-guard-web)。服务器不需要源码、Java、Node.js 或 Maven；需要 Docker Engine、Compose **v2.24.4+**、Bash、OpenSSL，以及访问 Docker Hub 的网络。下载配置另外需要 `curl`。这不是 Windows 容器或 ARM 镜像。

0.3.25 新增官方额度定时轮询、Google Authenticator 兼容验证码、团队成员用量进度条及两端额度显示设置；升级新增 V35/V36 数据表。0.3.25 只有普通后端，保留 sing-box，无内置 Chromium 或浏览器专用镜像，不需要额外 seccomp 权限。支持持久订阅、每账户前置／出口链和无需重填授权的链更新。每个官方账户必须选择已启用出口，任一所选节点缺失、禁用或故障时不会回退直连。Trojan URI 的证书例外默认关闭，只能按订阅明确许可；范围见[订阅说明](../../../docs/proxy-subscriptions-and-chains.md)。旧版镜像与历史源码标签保持原样。不要依赖 `latest` 判断版本或自动升级；数据库变化后，保留旧镜像也不表示可以直接切回。

## 首次部署

已拿到拉取部署包时，先解压到服务器。也可直接下载这 5 个小配置文件，无需下载源码；下载步骤额外需要 `curl`：

```bash
(
  set -e
  mkdir codex-quota-guard-server
  cd codex-quota-guard-server
  qg_base="https://raw.githubusercontent.com/3240665200/Codex-Quota-Guard/server-v0.3.28/deploy/docker"
  curl -fSL --fail-early --remote-name-all \
    "$qg_base/registry/compose.yaml" \
    "$qg_base/registry/start.sh" \
    "$qg_base/deploy.sh" \
    "$qg_base/.env.example" \
    "$qg_base/init-db.sql"
)
```

下载地址固定到与 `0.3.28` 镜像配套的 `server-v0.3.28` 源码标签。已有同名目录时下载步骤会停止，不覆盖已有部署。

进入包含 `start.sh` 的目录（上述下载方式为 `cd codex-quota-guard-server`），替换服务器 IP 和管理员邮箱后执行：

```bash
bash start.sh init http://服务器IP:8080 管理员邮箱
bash start.sh up
```

初始化提示设置管理员密码，生成独立数据库密码、JWT、邮箱、官方会话及代理加密密钥，保存在权限受限的 `.env`；已有 `.env` 不会被覆盖或自动补字段。初始化及 `up` 创建缺失的空 `secrets` 目录，不读取预置内容、不修改既有文件所有者。启动自动拉取固定镜像并等待健康检查，不在服务器编译。初始化入口只接受 HTTP 地址；需要 HTTPS 时，先初始化，再按下文配置证书与 `PUBLIC_ORIGIN`，最后用组合命令启动。

管理网页和桌面端使用同一个中央服务地址。基础 Compose 设置 `APP_REQUIRE_HTTPS=false`，允许 HTTP 和 HTTPS，包括私有预置授权、授权文件导入与会话管理，不再仅限 localhost。HTTP 页面只提示风险，不因协议禁用功能；**HTTP 会明文传输系统密码、导入的官方授权，推荐使用 HTTPS**。服务器到官方站点的 TLS 校验、鉴权和密钥加密规则不变。数据库和 Java 内部端口不单独公开。

配套桌面为 **0.3.28**，附带独立统计服务 **0.2.7**；新版修复额度空值语义、成员布局，并包含桌面连接安全策略修复。新版业务请求由 JS Axios 直接访问中央后台，安装包不包含 Java 或本地 HTTP 后端；安装统计服务需要批准 Windows 管理员授权。

## 升级到 0.3.28

从 `0.3.25` 升级不新增数据库迁移，本次统一个人和团队的空上限为无限制；明确设置为 `0` 的上限保持用尽。已有实际用量继续保留。

从 `0.3.23` 升级执行 **V35–V36**：新增官方额度轮询状态表和验证器密钥表。从 `0.3.22` 升级还会执行 V32–V34 的前置节点、加密订阅及 Trojan 许可迁移。升级前停止旧应用、保留 PostgreSQL 运行并备份数据库与原 `.env`；保留项目名、数据库卷、全部密钥和自定义配置。后端与网页同时改为 `0.3.28`，桌面使用 `0.3.28`；不要覆盖原 `.env` 或重新生成密钥。

升级后确认 Flyway 到 **V36**。先验证原单出口账户与手动节点，再按需导入订阅、测试“前置 → 出口”并保存账户链；保存链后手动刷新官方账户与会话。订阅仅首次导入及手动“更新节点”时下载，没有后台定时更新。下载、解析或版本检查失败保留原节点，同订阅按唯一节点名保留 ID；来源缺失或曾主动停用的节点不会因更新而自动启用。

**不能将直接换回旧镜像当作等价回滚。** 旧应用不理解前置链，可能绕过所选前置。降级到不理解 Trojan 许可的代码前，还应先在新版关闭该订阅例外，确认已保存节点恢复证书校验；该操作会停用相关节点并清除旧出口测试结果，保留 ID 和账户引用。需要恢复升级前状态时，应停止官方请求并恢复相互匹配的数据库、应用、配置和密钥备份。不要改写已执行迁移、执行 Flyway repair 或清库，详见[迁移边界](../../../docs/proxy-subscriptions-and-chains.md#api)。

从 `0.3.18/0.3.19/0.3.20` 升级还会执行 **V31 代理出口测试元数据**，并启用已在 `0.3.22` 提供的官方账户必选代理规则。升级后先添加或导入所需节点、测试出口，再在各官方账户的“授权与会话”选择已启用出口。原先直连的账户在完成选择前无法发起官方请求；节点禁用或故障同样拒绝，不会回退直连。旧无状态 `/proxy-nodes/import` 仍返回 410，新订阅使用 `/proxy-subscriptions`；旧节点不会被迁移删除。

从启动、日志和停止命令中移除 `-f compose.web-login.yaml`，不再使用旧 `-browser` 镜像。新版本删除了浏览器 overlay 与 `chromium-seccomp.json`，历史 `server-v0.3.19` 标签仍保留它们；旧文件可留作备份，但不能再叠加到新 Compose。若有自定义副本，也移除其浏览器环境变量和 seccomp 设置，沿用基础配置的非 root、`cap_drop: ALL`、只读文件系统与 `no-new-privileges`。

`start.sh up` 会为旧安装创建缺失的 `secrets` 目录。若直接使用 Compose，请先执行 `mkdir -p secrets`，再合并新的只读挂载及 `OFFICIAL_AUTHORIZATION_PRESET_FILE` 映射。不要对原部署目录递归改 owner 或权限。预置不存在时可正常启动，授权文件导入仍可使用。

HTTP 部署保留基础 `APP_REQUIRE_HTTPS=false`；强制 HTTPS 部署继续使用 `-f compose.https.yaml`，其中 `APP_REQUIRE_HTTPS=true` 与 HTTP→HTTPS 重定向保持生效。两者使用相同业务代码和授权入口。验收管理员登录、代理出口与完整链路测试、订阅手动更新、每个官方账户的代理选择、预置可用状态／手工文件导入、官方会话管理和客户端访问核验。

## 从 0.3.11 或更早版本升级

`0.3.25` 包含此前的自动绑定与交接、取得名额后 30 分钟登录期限、至少间隔 10 秒的官方会话监测、官方账户与会话管理，以及区分系统用户退出和官方会话退出的管理操作。从 `0.3.11` 升级会执行 **V22–V36 数据库迁移**，并启用上述官方账户必选代理规则。

**V29 要求所有旧应用写入进程先停止，禁止新旧队列 writer 混跑。** 迁移增加代际校验字段，旧程序不会检查它们。按以下顺序在原部署目录操作：

1. 记录现有镜像版本、Compose 项目名、卷名和自定义配置。安排维护窗口，停止所有连接这套数据库的旧后端、定时任务和副本；本部署可执行下列 `stop web backend`，PostgreSQL 保持运行。
2. 停写后执行 `backup`，确认生成非空 `database.dump` 和原始 `deployment.env`，加密保存到服务器之外。另存原 Compose、overlay 和证书配置，便于恢复。
3. 合并新版本配置，保留原 `.env`、项目名 `codex-quota-guard`、`pgdata` 卷及所有已有密钥。后端和网页镜像必须一起更新，旧 `compose.yaml` 还需补齐下述环境变量映射。
4. 用与实际部署一致的基础/可选组合启动新版本，确认 Flyway 完成 V36、容器健康、管理员登录、每个官方账户已选启用代理、客户端设备配对与访问核验正常，再结束维护窗口。

```bash
# 使用原配置停止应用，不停止或删除数据库。
docker compose --project-name codex-quota-guard --env-file .env -f compose.yaml stop web backend
bash start.sh backup
```

如果旧部署已有 overlay，停止命令也应带上原来的全部 `-f` 参数；另外停止其他机器或非 Compose 方式启动的旧写入进程。`backup` 需要 PostgreSQL 运行，不要先执行停止全部容器的 `bash start.sh stop`。

普通部署的两个镜像改为：

```text
minjm/codex-quota-guard-backend:0.3.28
minjm/codex-quota-guard-web:0.3.28
```

旧 Compose 的 `services.backend.environment` 合并下列映射（若文件为 JSON，使用等价的 JSON 键值格式）：

```yaml
OFFICIAL_SESSION_ENCRYPTION_KEY: "${OFFICIAL_SESSION_ENCRYPTION_KEY:-}"
MANAGED_PROXY_ENCRYPTION_KEY: "${MANAGED_PROXY_ENCRYPTION_KEY:-}"
MANAGED_PROXY_MAX_PROCESSES: "${MANAGED_PROXY_MAX_PROCESSES:-4}"
MANAGED_PROXY_ALLOW_PRIVATE_NODES: "${MANAGED_PROXY_ALLOW_PRIVATE_NODES:-false}"
OFFICIAL_AUTHORIZATION_PRESET_FILE: /run/secrets/official-authorization.json
```

同时为后端添加 `./secrets:/run/secrets:ro` 目录挂载（推荐复制当前 Compose 的长格式、`create_host_path: false`），启动前创建 `secrets` 目录。保留原数据库挂载，不能用这项替换数据库卷。

核对 `.env`：`OFFICIAL_SESSION_ENCRYPTION_KEY` 用于建立官方连接及新绑定，`MANAGED_PROXY_ENCRYPTION_KEY` 用于保存代理节点。缺失密钥不一定使健康检查失败，但相应业务不可用。只有从未配置过对应密钥、数据库也没有对应密文时，才为缺失项分别生成独立的 32 字节 Base64 密钥（`openssl rand -base64 32`），通过安全编辑器保存；配置过的密钥必须沿用原值，丢失时应找回原备份，不要生成替代值。不要将 `.env` 当脚本 `source` 执行或把密钥贴到日志、聊天、GitHub。

普通基础部署完成配置后执行：

```bash
bash start.sh up
```

启用 HTTPS 的部署使用下一节完整的组合命令，不能用只读取基础配置的 `start.sh up` 代替。不要重跑 `init`、用 `.env.example` 替换真实 `.env`、更换数据库密码/JWT/任何加密密钥，或执行 `docker compose down -v`。

升级失败时先保持停写并检查日志。**不可假定把两个镜像换回 `0.3.11` 就安全回退**；表结构和绑定/退出策略已经改变。优先修复前向升级；需要恢复旧版本时，应在停写状态下由数据库管理员恢复升级前数据库及匹配的 `.env`、配置和旧镜像，明确接受备份之后数据的损失，再验证业务。不要自动 `flyway repair` 或跳过迁移校验。

## 私有授权预置

管理员界面提供“使用预置授权”和授权文件导入。预置通过运行时文件提供，开发、测试与发布使用同一功能，不根据 profile 隐藏入口。预置仅在管理员主动提交时验证并保存，不会自动绑定账户或执行官方 OAuth 登录。

源码与 registry Compose 均把同目录 `./secrets` 只读挂载为 `/run/secrets`，固定设置 `OFFICIAL_AUTHORIZATION_PRESET_FILE=/run/secrets/official-authorization.json`。因此主机文件为 `secrets/official-authorization.json`。这是最多 **64 KiB** 的 UTF-8 JSON，必需字段为 `requestedAccountId`、`authorization`，`cookie` 可选；仅接受本模块支持的 Bearer 授权及限定登录 Cookie，不能直接使用完整浏览器导出、curl 命令、Codex `auth.json` 或 API Key。格式和管理员操作见 [官方账户说明](../../../docs/official-account-management.md)。

公开包只提供挂载约定，不含真实授权或浏览器扩展。没有预置文件、文件无法读取或格式不合要求时，服务可以启动，但页面显示预置不可用，仍可手工选择自己的授权文件导入。状态接口不会回显授权原文；失效凭据仍需正常更新与验证。打开 [ChatGPT 官网](https://chatgpt.com/) 是在用户自己的浏览器操作，跨站限制使本系统不能仅靠跳转读取官网 Cookie，公开版本不提供自动采集。

容器后端以 **UID/GID 10001** 运行，必须能遍历 `secrets` 目录并读取预置文件。初始化创建的空目录为 0700；放入私有文件后，部署者应为 UID 10001 授予最小权限。例如支持 POSIX ACL 的 Linux 可在部署目录对这两个精确路径执行：

```bash
setfacl -m u:10001:rx secrets
setfacl -m u:10001:r secrets/official-authorization.json
```

ACL 不改变现有 owner。没有 ACL 支持时请按主机权限策略配置该目录与该文件，确保其他普通用户不能读取；不要对整个项目、数据库卷、证书目录递归 `chown` 或 `chmod`。脚本不自动调整既有权限。文件换成新的 inode 后须重新确认权限。rootless Docker 或用户命名空间重映射需要按实际映射后的 UID 设置。

真实文件不得进入 Git、Docker context、镜像层、网页资源或公开归档；仓库已忽略 `secrets/`，根 `.dockerignore` 排除所有 `**/secrets`。不要把凭据直接写入 Compose、`.env.example` 或命令行。私有部署文件单独传递和加密备份；`backup` 只包含数据库与 `.env`，不自动复制这份预置。

## HTTPS（可选）

HTTP 与 HTTPS 使用相同镜像、预置和文件导入。需要强制 HTTPS 时，在部署目录取得 `compose.https.yaml` 和 `nginx.https.conf`（按以下方式从固定版本下载）：

```bash
(
  set -e
  qg_base="https://raw.githubusercontent.com/3240665200/Codex-Quota-Guard/server-v0.3.28/deploy/docker"
  curl -fSL --fail-early --remote-name-all \
    "$qg_base/compose.https.yaml" \
    "$qg_base/nginx.https.conf"
)
```

准备可由容器内非 root Nginx 进程读取的可信证书，目录内包含 `fullchain.pem` 和 `privkey.pem`。证书目录只读挂载，不进入镜像或 Git。用安全编辑器在原 `.env` 设置：

```dotenv
PUBLIC_ORIGIN=https://quota.example.com
TLS_CERT_DIRECTORY=/etc/codex-quota-guard/tls
HTTPS_BIND=0.0.0.0
```

域名需解析到服务器并放行 443；`TLS_CERT_DIRECTORY` 是实际存在的绝对路径。HTTPS overlay 在网页容器终止 TLS、覆盖可信转发头，显式设置 `APP_REQUIRE_HTTPS=true` 并把 HTTP 业务入口重定向 HTTPS。仅修改 `PUBLIC_ORIGIN` 不会建立 TLS 入口。

```bash
mkdir -p secrets
docker compose --project-name codex-quota-guard --env-file .env -f compose.yaml -f compose.https.yaml config --quiet
docker compose --project-name codex-quota-guard --env-file .env -f compose.yaml -f compose.https.yaml up -d --no-build --pull always --wait --wait-timeout 240
docker compose --project-name codex-quota-guard --env-file .env -f compose.yaml -f compose.https.yaml ps
```

后续启动、升级、日志和停止使用同样的组合；`start.sh` 只读取基础 `compose.yaml`，不会自动加载 overlay。`bash start.sh backup` 仍可备份同一项目的 PostgreSQL 与 `.env`，证书、overlay 和预置需另外加密备份。

## 日常操作

以下简写适用于普通基础部署：

```bash
bash start.sh status
bash start.sh logs backend
bash start.sh backup
bash start.sh stop
```

`stop` 停止全部服务但保留数据库卷；`.env`、私有预置与备份含敏感信息，不要公开。原电脑的数据不会自动迁入服务器。健康检查只验证进程、认证入口和数据库基本存活，不代替真实登录、绑定、官方连接、邮箱或代理连通性验收。

基础分发包包含 `compose.yaml`、`start.sh`、`deploy.sh`、`.env.example`、`init-db.sql` 和本说明。组装时从上一层复制公共脚本/模板；可选 HTTPS 按上文添加 overlay 和 Nginx 配置。公开包不得带入真实 `.env`、官方授权预置、证书私钥或数据库。

# 离线镜像部署包（Linux amd64）

将整个目录上传到 x86_64 Linux 服务器。服务器需要 Docker Engine、Docker Compose v2.24.4+、Bash、OpenSSL、sha256sum；不需要 Java、Node.js、Maven，也不需要从镜像仓库拉取应用。

后端和管理网页统一使用 `0.3.22`，后端保留 sing-box，不含 Chromium，也没有 `-browser` 变体。离线包与开发、测试采用相同授权功能，私有授权预置是单独的运行时文件，不进入 `images.tar`；公开包不包含浏览器扩展或真实授权。

## 首次部署

在本目录依次执行，替换 IP 和管理员邮箱：

```bash
# 校验文件并导入后端、管理网页、PostgreSQL 三个镜像
bash start.sh load

# 设置管理员密码，并生成本服务器独立的数据库/JWT/邮箱加密密钥
bash start.sh init http://服务器IP:8080 管理员邮箱

# 检查镜像和配置后启动；禁止构建或联网拉取镜像
bash start.sh up
```

浏览器和桌面客户端都使用 `http://服务器IP:8080`。按需在防火墙/云安全组开放所选端口；数据库端口不对外开放。HTTP 不加密，请限制在可信网络使用。

初始化和 `up` 会创建缺失的空 `secrets` 目录，不生成官方授权或更改既有文件的所有者。Compose 只读挂载 `./secrets:/run/secrets`，后台读取 `official-authorization.json`；没有该文件时仍能启动，管理员可导入授权文件。若单独收到私有部署文件，请在受限目录中保存，并确保容器 UID 10001 能遍历目录、读取文件，详见包内授权说明或 [权限要求](../registry/README.md#私有授权预置)。

首次部署会创建空数据库，不包含你原电脑上的用户、额度、流水、邮箱授权或其他数据。已有数据库需要单独迁移，不能把这个包当作数据库备份。

## 日常操作

```bash
bash start.sh status
bash start.sh logs backend
bash start.sh backup
bash start.sh stop
```

`stop` 只停止容器，保留数据库。`up` 可重新启动。容器会随 Docker 服务恢复运行（手动 stop 后除外）。

`.env`、`secrets/` 和 `backups/` 包含敏感信息，不要公开发送或上传到 GitHub。管理员首次登录成功后可以清空 `.env` 的 `ADMIN_BOOTSTRAP_EMAIL` 和 `ADMIN_BOOTSTRAP_PASSWORD`，再 `up` 移除容器中的初始密码；现有管理员不会因此删除或重置。

包内 `images.tar` 是真实 Docker 镜像归档；`SHA256SUMS` 用于检测文件损坏，不替代可信下载来源。`images.txt` 是必须存在的三个镜像标签。运行入口为 `start.sh`，不要使用源码版的构建命令。

## 升级和迁移

升级前先 `backup`，保留旧版本镜像、数据库备份和对应 `.env`。新版本解压到另一个目录，校验导入新镜像后，安全复制原 `.env` 与私有预置文件，使用同一固定 Compose 项目名和数据库卷启动。保留所有原有密钥及其值，不能重新生成 JWT、邮箱、官方会话或代理加密密钥，也不要更改 PostgreSQL 主版本后直接复用旧卷。

从 `0.3.18/0.3.19/0.3.20` 升级到 `0.3.22` 会新增 V31 代理出口测试元数据，不改写已有代理凭据。官方账户必须先选择已启用代理节点；缺失、禁用或故障均拒绝官方请求，不回退直连。节点使用名称、HTTP／HTTPS／SOCKS5、主机、端口和可选认证字段添加，订阅导入已停用。移除启动命令中的 `-f compose.web-login.yaml` 和旧 `-browser` 镜像引用；HTTPS 部署继续使用原 `compose.https.yaml`、证书和 HTTPS `PUBLIC_ORIGIN`。HTTPS overlay 仅显式强制传输加密，授权预置和手工文件导入功能相同。直接使用 Compose 前先创建 `secrets` 目录，并将启动参数保留为 `--no-build --pull never`。详细升级步骤见[拉取部署说明](../registry/README.md#升级到-0322)。

`backup` 备份数据库和 `.env`；私有预置文件、证书和 overlay 需另行加密备份。不要将私有预置与公开离线包混发。

**不要执行 `docker compose down -v`，它会删除数据库。** 默认固定项目名为 `codex-quota-guard`，同一 Docker 引擎上只部署这一套实例。数据库卷不会包含在镜像归档中。

离线指安装和容器启动不依赖镜像仓库；「登录辅助」连接外部邮箱仍需服务器能访问对应 IMAPS、DNS 和 OAuth HTTPS 服务。Windows 桌面客户端及 Rust 统计服务仍安装在用户电脑。

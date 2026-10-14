# 离线镜像部署包（Linux amd64）

将整个目录上传到 x86_64 Linux 服务器。服务器需要 Docker Engine、Docker Compose v2.24+、Bash、OpenSSL、sha256sum；不需要 Java、Node.js、Maven，也不需要从镜像仓库拉取应用。

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

首次部署会创建空数据库，不包含你原电脑上的用户、额度、流水、邮箱授权或其他数据。已有数据库需要单独迁移，不能把这个包当作数据库备份。

## 日常操作

```bash
bash start.sh status
bash start.sh logs backend
bash start.sh backup
bash start.sh stop
```

`stop` 只停止容器，保留数据库。`up` 可重新启动。容器会随 Docker 服务恢复运行（手动 stop 后除外）。

`.env` 和 `backups/` 包含敏感信息，不要公开发送或上传到 GitHub。管理员首次登录成功后可以清空 `.env` 的 `ADMIN_BOOTSTRAP_EMAIL` 和 `ADMIN_BOOTSTRAP_PASSWORD`，再 `up` 移除容器中的初始密码；现有管理员不会因此删除或重置。

包内 `images.tar` 是真实 Docker 镜像归档；`SHA256SUMS` 用于检测文件损坏，不替代可信下载来源。`images.txt` 是必须存在的三个镜像标签。运行入口为 `start.sh`，不要使用源码版的构建命令。

## 升级和迁移

升级前先 `backup`，保留旧版本镜像、数据库备份和对应 `.env`。新版本解压到另一个目录，校验导入新镜像后，安全复制原 `.env`，使用同一固定 Compose 项目名启动。不要重新生成 JWT 或邮箱加密密钥，也不要更改 PostgreSQL 主版本后直接复用旧卷。

**不要执行 `docker compose down -v`，它会删除数据库。** 默认固定项目名为 `codex-quota-guard`，同一 Docker 引擎上只部署这一套实例。数据库卷不会包含在镜像归档中。

离线指安装和容器启动不依赖镜像仓库；「登录辅助」连接外部邮箱仍需服务器能访问对应 IMAPS、DNS 和 OAuth HTTPS 服务。Windows 桌面客户端及 Rust 统计服务仍安装在用户电脑。

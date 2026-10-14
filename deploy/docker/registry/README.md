# Docker Hub 拉取部署

镜像仓库：

- 后端：[minjm/codex-quota-guard-backend](https://hub.docker.com/r/minjm/codex-quota-guard-backend)
- 管理网页：[minjm/codex-quota-guard-web](https://hub.docker.com/r/minjm/codex-quota-guard-web)
- 数据库：官方 `postgres:17-alpine`，不重新发布数据库镜像。

此版本面向 **Linux amd64 / x86_64**，不是 Windows 容器，也不是 ARM 镜像。公开镜像可直接拉取；不需要源码、Java、Node.js、Maven 或离线镜像文件。服务器仍需安装 Docker Engine、Compose v2.24+、Bash、OpenSSL，并能访问 Docker Hub。

## 首次部署

已拿到拉取部署包时，先解压到服务器。如果没有部署包，可直接下载这 5 个小配置文件，无需下载源码；下载步骤额外需要 `curl`：

```bash
(
  set -e
  mkdir codex-quota-guard-server
  cd codex-quota-guard-server
  qg_base="https://raw.githubusercontent.com/3240665200/Codex-Quota-Guard/5a3e2b09db918edcab56c18c89288da36e6e2a80/deploy/docker"
  curl -fSL --fail-early --remote-name-all \
    "$qg_base/registry/compose.yaml" \
    "$qg_base/registry/start.sh" \
    "$qg_base/deploy.sh" \
    "$qg_base/.env.example" \
    "$qg_base/init-db.sql"
)
```

下载地址固定到与 `0.3.10` 镜像配套的部署配置提交；这里不使用 `v0.3.10` 源码标签，因为部署文件是在该标签之后加入的。已有同名目录时下载步骤会停止，不覆盖已有部署。

进入包含 `start.sh` 的目录（上述下载方式为 `cd codex-quota-guard-server`），替换服务器 IP 和管理员邮箱后执行：

```bash
bash start.sh init http://服务器IP:8080 管理员邮箱
bash start.sh up
```

初始化会提示设置管理员密码，并生成独立数据库密码、JWT 和邮箱加密密钥；已有 `.env` 不会覆盖。启动会自动拉取指定版本镜像，等待数据库、后端和网页健康检查通过，不在服务器编译。

访问 `http://服务器IP:8080`。桌面客户端也填写同一个中央服务地址。HTTP 不加密，请限制可信网络访问；公网正式部署应在前面配置 HTTPS 反向代理。数据库和 Java 内部端口不单独开放。

默认 Compose 固定应用版本 `0.3.10`，不跟随 `latest` 自动跨版本升级。`latest` 只是最新发布镜像的别名；升级前备份并明确选择版本。

## 日常操作

```bash
bash start.sh status
bash start.sh logs backend
bash start.sh backup
bash start.sh stop
```

`stop` 保留数据库；`.env` 与备份包含敏感信息，不要公开。原有电脑数据不会自动迁移到这里。升级时保留相同项目名、数据库卷和原密钥；不要运行 `docker compose down -v`。

完整部署包只需 `compose.yaml`、`start.sh`、`deploy.sh`、`.env.example`、`init-db.sql` 和本说明。仓库内此目录存放拉取版模板；组装时从上一层复制 `deploy.sh`、`.env.example`、`init-db.sql` 到相同目录。不要将本机真实 `.env` 或数据库打进分发包。

只想单独下载应用镜像时：

```bash
docker pull minjm/codex-quota-guard-backend:0.3.10
docker pull minjm/codex-quota-guard-web:0.3.10
```

下载镜像本身不会完成数据库初始化、设置管理员或启动完整系统，请使用配套 Compose 和脚本。

# 服务器 0.3.18 发布记录

发布日期：2026-10-15。平台：Linux amd64 / x86_64。配套配置固定于 `server-v0.3.18` 源码标签。

## 公开镜像

| 镜像 | OCI 索引 SHA-256 |
| --- | --- |
| `minjm/codex-quota-guard-backend:0.3.18`、`latest` | `81a5576331a7e5f2ad5173fb5d0fc74588045795636e6c55b2c57aafa3ca8e84` |
| `minjm/codex-quota-guard-web:0.3.18`、`latest` | `ffdb73c3f2477fa2c4773df09c75304bc65d07449fb0582077a895474148f3d0` |
| `minjm/codex-quota-guard-backend:0.3.18-browser` | `44f40dce7c3d83247f1ca637cc57e5912d8f0efdf1d36b84d77e545c600dfd15` |

仓库：[后端](https://hub.docker.com/r/minjm/codex-quota-guard-backend) · [管理网页](https://hub.docker.com/r/minjm/codex-quota-guard-web)。已核对远程摘要和匿名 manifest 访问；索引中的 `unknown/unknown` 项是构建证明，不是其他运行架构。

后端两种镜像的应用构建 revision 为 `dd2a399f52a1e9d188e4bb09cc9818153820d183`；网页为 `6a39b3c13e3b030c5a17c60109c45535b87cdcd5`。后续 `55d5d4f` 修正的是宿主 Docker 加载的浏览器 seccomp 配置，不改变镜像内二进制，因此浏览器模式必须同时使用本发布的配置文件。

## 本次内容

- 自动排队、登录前后会话差异监测与后台复核绑定；成功、取消、超时和确定失败后自动交接。
- 邮件监听状态恢复、SSE 通知、独立统计服务与设备访问核验。
- 官方账户、代理节点和会话管理；用户管理区分系统下线与该用户当前绑定的官方会话下线。
- 普通后端显式选择 `runtime`，浏览器版选择 `browser-runtime`。补齐固定 sing-box 上游程序在 Alpine 所需的 `gcompat`，构建时以非 root 执行版本检查。
- 浏览器部署保留非 root、capabilities 全部移除、no-new-privileges、只读根文件系统和沙箱。seccomp 是有说明的上游派生配置，见[来源与变更](../../deploy/docker/licenses/README.md)。

## 实际验证

- 38 项部署脚本和配置测试通过，基础/HTTPS/浏览器 HTTPS 三组 Compose 合并检查通过。
- 隔离 PostgreSQL 17 中，实际旧镜像 `0.3.11` 初始化 V21，再升级到本次后端并成功迁移至 V30；合成用户、团队和用量记录逐字段保持一致。
- Nginx 到后台的页面、健康检查、合成管理员登录正常；未登录返回 401，缺设备标识时拒绝访问，受保护的额度接口返回 403，敏感管理操作拒绝不安全 HTTP。
- 非 root 的 sing-box 1.13.14 版本、配置检查及无外网的本机代理握手通过。
- 浏览器镜像在正式安全参数及无外网条件下成功加载合成页面。普通 UID 10001 的 `chroot /tmp` 仍被拒绝，effective/bounding capabilities 均为零，NoNewPrivs 为 1。
- 所有验证仅使用隔离的合成数据，临时测试容器和网络已清理；没有调用真实官方账户或邮箱。

## 升级前必读

从 `0.3.11` 升级会执行 V22–V30。先停止全部旧应用写入进程，保留数据库运行以完成数据库和原 `.env` 备份，再启动新版；尤其不得混跑不检查 V29 代际字段的旧队列程序。保留原数据库卷、密码和加密密钥，缺失新密钥时按[升级说明](../../deploy/docker/registry/README.md#从-0311-或更早版本升级)处理。迁移后不能假定只切换旧镜像即可安全回退。

新服务器要求支持设备标识的桌面端（0.3.14+，建议 0.3.18）。本次只发布源码和服务器镜像，未新增 Windows 安装包 Release。桌面端首次真实访问请求失败的底层原因仍待复测，不属于本次已确认解决的网络问题。

浏览器合成页面验证不代表官方登录、验证码或人机验证一定成功；管理员仍需本人验收。使用浏览器功能须部署专用镜像、对应 seccomp/资源配置和 HTTPS，并验证目标宿主机支持非特权用户命名空间。

<p align="center">
  <img src="./apps/client/frontend/assets/app-icon.svg" alt="Codex Quota Guard 图标" width="104" height="104">
</p>

<h1 align="center">Codex Quota Guard</h1>

<p align="center">本机记录 Codex Token 用量，集中查看个人与团队额度。</p>

<p align="center">
  <a href="https://github.com/3240665200/Codex-Quota-Guard/releases/latest"><img src="https://img.shields.io/github/v/release/3240665200/Codex-Quota-Guard?label=Release&color=1677ff" alt="最新版本"></a>
  <img src="https://img.shields.io/badge/安装包-Windows%20x64-1677ff?logo=windows11&logoColor=white" alt="Windows x64 安装包">
  <img src="https://img.shields.io/badge/桌面端-Tauri%202-24c8db?logo=tauri&logoColor=white" alt="Tauri 2">
  <img src="https://img.shields.io/badge/后端-Java%2017-ed8b00?logo=openjdk&logoColor=white" alt="Java 17">
  <a href="https://hub.docker.com/r/minjm/codex-quota-guard-backend"><img src="https://img.shields.io/badge/Docker%20Hub-公开镜像-2496ed?logo=docker&logoColor=white" alt="Docker Hub 公开镜像"></a>
</p>

<p align="center">
  <a href="https://github.com/3240665200/Codex-Quota-Guard/releases/latest">下载 Windows 安装包</a>
  · <a href="#快速开始">快速开始</a>
  · <a href="#docker-服务器部署">Docker 部署</a>
  · <a href="#功能概览">功能概览</a>
  · <a href="#界面预览">界面预览</a>
  · <a href="./docs/README.md">项目文档</a>
</p>

---

Codex Quota Guard 由桌面客户端、中央 Java 服务和 Web 管理后台组成。用户在**官方 Codex 应用或 CLI 自行登录**，桌面端不替换本机 `auth.json`。管理员可使用运行时私有授权预置或导入授权文件，验证官方账户后加密保存限定凭据。网页通过用户自己的浏览器打开官网，服务器镜像不安装浏览器。HTTP 与 HTTPS 都可用，推荐 HTTPS 保护密码与授权。Windows 统计程序从本机可读取的 Codex 会话日志采集 Token 事件，上报中央服务；管理员在后台配置团队与个人额度、查看用量和设备状态。

桌面客户端当前版本为 **0.3.29**，独立统计程序版本为 **0.2.7**。桌面使用 JS Axios 直连中央 API，计量由 Windows 服务独立执行，完全退出桌面后仍持续采集、上报和发送心跳；历史待核对记录单独提示。额度留空即无限制，成员进度条支持窄窗口布局；新版修复未设置 7 天上限时客户端拒绝额度响应的问题，详见[0.3.29 发布说明](./docs/releases/v0.3.29.md)。

服务器当前版本为 **0.3.28**，后端与管理网页使用相同版本，源码与配套配置标签为 `server-v0.3.28`；发布状态与升级范围见[服务器 0.3.28 发布说明](./docs/releases/server-v0.3.28.md)。每个官方账户必须绑定已启用出口节点，并可选择前置节点；任一所选节点缺失、停用或故障时拒绝官方请求，不回退直连。代理页面可手动添加 HTTP、HTTPS、SOCKS5 节点，也可管理持久订阅并手动更新节点；测试可查看最终出口 IP、国家、地区和城市。授权仍可使用运行时私有预置或手工导入文件；公开发布不包含浏览器扩展、个人授权文件或自动 Cookie 采集功能。打开 `https://chatgpt.com/` 不会自动读取跨站 Cookie，也不会自动完成 OAuth。HTTP 页面保留风险提示，HTTPS overlay 可显式强制加密传输。

服务器 `0.3.23` 加入[订阅与双节点代理链](./docs/proxy-subscriptions-and-chains.md)：支持 Clash/Mihomo YAML、URI/Base64 订阅，每个账户可单独保存“前置 → 出口”，无需重填已有授权。Trojan URI 支持 `peer`／`sni` 兼容和默认关闭、按订阅确认的节点证书例外。旧 `0.3.22` 镜像不包含这些功能，需要同时升级后端与管理网页；桌面客户端与统计程序继续使用上述版本。

在 Windows 本机测试这项新功能，请按[管理网页本地启动与测试指南](./docs/local-admin-test.md)从当前源码工作区启动。双击 `一键启动-管理后台.cmd` 会启动回环管理网页（8000）和中央 API（18080），并显示本次运行的停止命令；无需同时启动桌面客户端。

客户端保留「登录辅助」：管理员可为团队关联的官方账户配置自有邮箱和最多两个精确发件地址，用户点击监听后领取一封新到登录邮件中的验证码。邮箱授权只在中央服务加密保存，官方登录仍由用户完成；详见[邮箱配置与使用说明](./docs/architecture/login-assistance.md)。

## 功能概览

| | 能做什么 |
| --- | --- |
| 📊 **额度看板** | 桌面端查看官方账户返回的额度窗口百分比，以及本系统单独配置的个人、团队 Token 额度。管理后台可查看已上报的官方额度摘要。 |
| 🧮 **用量与费用** | 查看输入、输出、缓存、思考 Token、模型名称、思考等级、趋势和逐笔流水；按可配置的模型单价估算 USD。 |
| 👥 **团队管理** | 在团队页面搜索并批量选择成员，加入、移出或跨团队转移；管理个人/团队额度及累计总额，支持额度重置、设备状态查看和系统账户强制下线。 |
| 🔗 **账户与绑定** | 管理官方账户连接和会话，自动排队、识别绑定与交接，取得名额后有 30 分钟完成登录；系统用户退出与官方会话退出分别确认，支持 HTTP 与 HTTPS，推荐 HTTPS。 |
| 🛠️ **常驻统计与诊断** | Windows Rust 统计程序独立于桌面窗口运行，增量上报用量与心跳；桌面端可查看消耗流水和运行诊断。 |
| 🎨 **独立工作区** | v0.3.0 桌面端采用可收起侧栏，分开查看额度、统计、流水与诊断；支持浅色、深色、跟随系统，流水明细在弹窗中展开。 |

> [!IMPORTANT]
> **官方额度、本系统 Token 额度和估算 USD 是三套不同的数据。** 官方百分比不能精确换算为 Token，估算费用不是官方账单。本系统可统计并提示限额状态，但官方 Codex 请求不经过本系统，无法据此强制阻断官方应用。

## 界面预览

桌面客户端支持浅色、深色与跟随系统。点击截图可查看大图。

<table>
  <tr>
    <th width="50%">浅色模式</th>
    <th width="50%">深色模式</th>
  </tr>
  <tr>
    <td><a href="./docs/images/desktop-light.png"><img src="./docs/images/desktop-light.png" alt="Codex Quota Guard 桌面客户端浅色模式：个人、官方和团队剩余额度" width="600"></a></td>
    <td><a href="./docs/images/desktop-dark.png"><img src="./docs/images/desktop-dark.png" alt="Codex Quota Guard 桌面客户端深色模式：个人、官方和团队剩余额度" width="600"></a></td>
  </tr>
</table>

截图中的账户名和邮箱已由提供者遮挡；额度数值仅为截图时的状态，不代表默认配置或固定套餐额度。

## Docker 服务器部署

服务器镜像使用以下 Docker Hub 仓库，**公开拉取，无需上传源码或在服务器编译**：

- [后端 · minjm/codex-quota-guard-backend](https://hub.docker.com/r/minjm/codex-quota-guard-backend)
- [管理网页 · minjm/codex-quota-guard-web](https://hub.docker.com/r/minjm/codex-quota-guard-web)

部署配置固定后端和网页 `0.3.28`，支持 **Linux amd64 / x86_64**，数据库使用官方 `postgres:17-alpine`。后端保留 sing-box，不含 Chromium，也没有浏览器变体，不需要浏览器 seccomp 或额外容器权限；HTTPS overlay 可选，详见[部署说明](./deploy/docker/registry/README.md)。私有授权通过 `./secrets:/run/secrets` 只读挂载，真实凭据不进入源码、公开镜像或公开包。Windows 桌面客户端和 Rust 统计服务仍安装在用户电脑。服务器版本范围见[0.3.28 发布说明](./docs/releases/server-v0.3.28.md)；旧版本标签保持不变。

服务器准备好 **Docker Engine、Docker Compose v2.24.4+、Bash、curl 和 OpenSSL** 后，可使用镜像或配套离线包部署。以下配置固定在 `server-v0.3.28` 标签，公开拉取前确认该标签和两个镜像已经发布，替换服务器 IP 和管理员邮箱：

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
  bash start.sh init http://服务器IP:8080 管理员邮箱
  bash start.sh up
)
```

以上下载地址固定到 `server-v0.3.28` 源码标签，只下载 5 个小文件，不下载整个项目；已有同名目录时会停止，避免覆盖旧部署。初始化会提示设置管理员密码，保存独立密钥到 `.env` 并创建空 `secrets` 目录；预置文件不存在时服务仍可启动，管理员可手工导入授权文件。启动后，**管理网页和桌面客户端都使用 `http://服务器IP:8080`**。基础 Compose 的 `APP_REQUIRE_HTTPS=false` 允许 HTTP 和 HTTPS 官方管理操作；启用 HTTPS overlay 后则强制 HTTPS。按需放行网站入口端口，不要单独开放数据库或后端端口。

> [!WARNING]
> HTTP 会明文传输密码、系统令牌和导入的官方授权，推荐使用 HTTPS。允许 HTTP 不改变上游官方 TLS 校验、鉴权或密钥加密。新部署不会带入原电脑的用户和流水，需要另行迁移数据库。升级前先备份，保留原 `.env` 和数据库卷，**不要执行 `docker compose down -v`**。`latest` 后续可能变化，生产部署建议固定版本。

进入 `codex-quota-guard-server` 目录后，可用 `bash start.sh status` 查看状态、`bash start.sh logs backend` 查看日志、`bash start.sh backup` 备份、`bash start.sh stop` 停止并保留数据。

从服务器 `0.3.25` 升级到 `0.3.28` 不新增数据库迁移。从 `0.3.23` 升级会执行 **V35–V36**，新增官方额度轮询和验证器密钥表；更早版本还需执行其间的迁移。升级前备份，保留原 `.env`、数据库卷和全部密钥，同时更新后端与网页，并使用桌面 `0.3.28`。从 `0.3.11` 升级会执行 **V22–V36**，V29 前必须停止全部旧应用写入进程；不要重新初始化或删除数据库卷，详见[升级步骤](./deploy/docker/registry/README.md#升级到-0328)。

详细说明：[Docker Hub 拉取部署](./deploy/docker/registry/README.md) · [离线镜像部署](./deploy/docker/offline/README.md) · [从源码构建镜像](./deploy/docker/README.md)。

## 快速开始

当前桌面 **0.3.29**：[Windows x64 安装包](https://github.com/3240665200/Codex-Quota-Guard/releases/tag/v0.3.29)。桌面 Axios 直连中央 API，独立 Rust 服务负责计量。较早的 `dist/desktop-v0.3.24` 产物仍使用旧架构，请使用 `0.3.29` 安装包。

1. 部署中央 Java API、PostgreSQL 和管理网页，管理员配置系统用户、团队和额度。本次桌面需要包含 Tauri CORS 支持的中央后台代码。
2. 新版安装程序会强制安装、启动统计服务，并配置 Windows 自动启动和故障恢复。安装时按当前 Windows 用户批准必要的管理员权限；服务安装或启动失败时不能完成安装。
3. 正常打开桌面，在服务器设置中填写中央地址并登录系统账户，完成设备配对。修改地址会同步已有统计服务；服务缺失时重新运行安装器。
4. 在「登录辅助」申请绑定，按引导在官方 Codex 应用或 CLI 自行登录。通过访问核验后，查看团队/官方 Token、成员进度条、流水、统计和服务运行信息。

关闭界面或从托盘完全退出都不会停止统计服务。计量由服务独立记录和上报，故障恢复交给 Windows SCM；桌面不运行 Java、本地 HTTP 后端或第二个统计扫描器。安装包仍带 WebView2 离线安装器，客户端用户不需要额外安装开发工具。

### 桌面自启动

设置中的“开机自启动”只控制登录 Windows 后是否打开界面；统计服务自动启动由 Windows 管理。正常桌面运行无需为了启动统计而再次提权。服务和桌面界面分别启停，详细说明见[当前系统架构](./docs/architecture/current-system-architecture.md)。

### 旧版升级兼容（0.3.5 起）

安装器同时检查“当前用户”和“所有用户”的旧安装记录，包括 32/64 位注册表视图；软件名称和应用 ID 保持不变。当前用户安装默认沿用原自选目录；发现所有用户版本时，先显示旧版本、原目录和目标目录，经确认后通过 Windows UAC 卸载旧程序，再安装当前用户版本。取消授权、卸载失败或旧版记录残留都会停止安装，不会继续创建第二份。

迁移不清除应用数据；更新阶段保留独立统计服务，由安装器升级并验证，原桌面自启动选择保持。若发现多个安装目录或记录损坏，会提示处理，不猜测要删除哪份。**已发布的 0.3.4 安装包不包含这次兼容修复，源码更新不会自动改变旧安装包。**

## 系统如何协作

```mermaid
flowchart LR
    Codex[官方 Codex CLI / 桌面端] -->|本机会话日志| Meter[Windows Rust 统计程序]
    Meter -->|Token 事件与心跳| API[中央 Java API]
    Desktop[React + Tauri 桌面端] -->|Axios| API
    Codex -.->|本机 CLI stdio RPC| Desktop
    Admin[Ant Design Pro 管理端] --> API
    API --> DB[(PostgreSQL)]
```

桌面直接请求中央 Java API；原生层仅处理必要本机操作，中央 API 管理用户、团队、额度和已入库的用量事件。更完整的数据流见[当前系统架构](./docs/architecture/current-system-architecture.md)。

## 从源码运行

桌面开发需要 Node.js 22+、pnpm 10.12.3、Rust 1.85+ 和对应平台的 Tauri 依赖。中央后端开发另需 Java 17、Maven 3.9+ 和 PostgreSQL。先参照 [`apps/admin/backend/.env.example`](./apps/admin/backend/.env.example) 配置中央后端；桌面启动与独立服务测试见[本机桌面测试指南](./docs/local-desktop-test.md)。

Windows 可使用仓库根目录的 [一键启动-管理后台.cmd](./一键启动-管理后台.cmd) 和 [一键启动-桌面客户端.cmd](./一键启动-桌面客户端.cmd)。桌面开发启动不安装、启动或修复统计服务，首次独立服务测试按[架构中的源码验证步骤](./docs/architecture/current-system-architecture.md#从源码验证)安装。也可分别在终端运行：

```bash
pnpm install
```

然后在**三个不同终端**分别运行 `pnpm dev:admin-backend`（中央 API，默认 8080）、`pnpm dev:admin`（管理页，默认 `http://localhost:8000`）和 `pnpm dev:client`（桌面端，普通终端）。统计服务由安装器或显式开发安装命令准备。普通用户电脑请使用发布的安装包，不必配置源码开发环境。

## 构建与验证

```bash
pnpm verify
```

在 Windows 上打包桌面安装程序：

```powershell
& .\scripts\windows\package-client.ps1
```

默认产物目录为项目内 `dist\build-target\release\bundle\nsis`。也可传入 `-TargetDirectory 'C:\BuildCache\codex-quota-guard'` 自行指定目录；首次构建需要联网下载并校验随包运行时。也可双击 [一键打包-桌面客户端.cmd](./一键打包-桌面客户端.cmd) 使用默认设置。

## 项目结构

```text
apps/
  admin/
    frontend/   Ant Design Pro 管理界面
    backend/    中央 Java API
  client/
    frontend/   React + Tauri 桌面端
    backend/    已退役 Java 迁移参考，不编译或打包
    meter/      Windows Rust 统计程序
docs/          架构、API 与产品文档
deploy/docker/ 镜像拉取、离线与源码构建部署
scripts/       启动、打包与诊断脚本
```

## 使用边界

- 统计只覆盖**本机可读取且格式受支持**的 Codex 会话日志；ChatGPT 桌面聊天、其他设备或自定义日志位置不保证计入。首次配对只建立旧日志游标，不追溯旧用量；未配对或上报失败时显示的 `0 Token` 不代表真实用量为零。
- 统计程序有独立于桌面客户端的版本号；升级统计程序不会自动补齐旧版本未采集的流水或 Token 分项。
- Windows 常驻服务已实现；macOS/Linux 尚无对应常驻服务安装器和已发布安装包，当前桌面启动门禁会明确提示平台暂不支持。拥有设备管理员/root 权限的人仍可停止服务或修改本机日志。
- `0.3.22` 桌面端与 `0.2.4` 统计程序默认允许 CC Switch 共存。只有当前账户明确使用 API Key、处于 `PROXY_MANAGED` 且没有 OAuth，同时本机活动 CC Switch 数据库记录证明正在接管 Codex，才构成冲突。统计服务还需核验受保护 `ProgramData` 证据及同一 SID／SessionId 的精确进程身份，才会结束冲突进程并弹出提示；未保存内容可能丢失。普通网络失败、仅安装或运行 CC Switch 不会触发结束进程。
- 冲突后的连接修复仅使用可验证的标准目录备份或明确选中的原供应商配置，保留现有 OAuth 与其他设置。来源不可靠、自定义目录或写入失败时会阻止继续启动并支持“重新检查”。修复不能代替官方账户归属校验，Codex 可能需要重启或重新打开会话以重载连接。
- 管理员“强制下线系统账户”只撤销 **Quota Guard 系统会话**，不会退出官方 Codex；“强制下线所有账户”和官方会话登出需分别预览并明确确认目标。个人额度用尽不能强制停止官方 Codex 的请求。
- 模型费用基于可配置的单价与已采集的 Token 分项估算；内置价格是 [CC Switch](https://github.com/farion1231/cc-switch) 的固定版本快照，**不是实时官方报价**。详见[统计与定价口径](./docs/architecture/usage-pricing.md)。

## 文档

- [文档索引](./docs/README.md) · [当前系统架构](./docs/architecture/current-system-architecture.md)
- [个人 Token 统计](./docs/architecture/local-codex-token-quota.md) · [团队额度策略](./docs/architecture/team-quota-policy.md)
- [用量与 USD 估算](./docs/architecture/usage-pricing.md) · [Web API 契约](./docs/api/web-backend-api.md)

管理端界面基于 [Ant Design Pro](https://github.com/ant-design/ant-design-pro) 定制；README 的首页排版参考 [CC Switch](https://github.com/farion1231/cc-switch) 与 [NextDevTpl](https://github.com/evepupil/NextDevTpl)。

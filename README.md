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
</p>

<p align="center">
  <a href="https://github.com/3240665200/Codex-Quota-Guard/releases/latest">下载 Windows 安装包</a>
  · <a href="#快速开始">快速开始</a>
  · <a href="#功能概览">功能概览</a>
  · <a href="./docs/README.md">项目文档</a>
</p>

---

Codex Quota Guard 由桌面客户端、中央 Java 服务和 Web 管理后台组成。用户在**官方 Codex 应用或 CLI 自行登录**；本项目不托管、下发或替换官方凭证。Windows 计费服务从本机可读取的 Codex 会话日志采集 Token 事件，上报中央服务；管理员在后台配置团队与个人额度、查看用量和设备状态。

## 功能概览

| | 能做什么 |
| --- | --- |
| 📊 **额度看板** | 桌面端查看官方账户返回的额度窗口百分比，以及本系统单独配置的个人、团队 Token 额度。管理后台可查看已上报的官方额度摘要。 |
| 🧮 **用量与费用** | 查看输入、输出、缓存、思考 Token、模型名称、思考等级、趋势和逐笔流水；按可配置的模型单价估算 USD。 |
| 👥 **团队管理** | 管理系统用户、团队、个人/团队额度及团队累计总额；支持额度重置、设备状态查看和系统账户强制下线。 |
| 🛠️ **常驻计费与诊断** | Windows Rust 服务独立于桌面窗口运行，增量上报用量与心跳；桌面端可查看消耗流水和运行诊断。 |

> [!IMPORTANT]
> **官方额度、本系统 Token 额度和估算 USD 是三套不同的数据。** 官方百分比不能精确换算为 Token，估算费用不是官方账单。本系统可统计并提示限额状态，但官方 Codex 请求不经过本系统，无法据此强制阻断官方应用。

## 快速开始

当前发布的安装包为 **Windows x64**。它已经包含本地 Java 运行时、WebView2 离线安装器和 Rust 计费程序；用户电脑**不需要**安装 Java、Maven、Node.js、pnpm 或 Rust。

1. 部署中央 Java API、PostgreSQL 和管理后台。管理员创建系统用户、团队并设置额度。部署说明见[管理端后端文档](./apps/admin/backend/README.md)。
2. 从 [GitHub Releases 下载最新安装包](https://github.com/3240665200/Codex-Quota-Guard/releases/latest)，在用户电脑安装。在登录页的“服务器设置”中保存中央服务地址，例如 `https://your-server.example.com`；可信局域网开发测试可临时填写 `http://<服务器 IP>:8080`。保存后从**系统托盘菜单退出客户端**，不要只关闭窗口。`0.0.0.0` 是监听地址，不能作为其他电脑的连接地址。
3. 再以**管理员身份重新运行安装后的桌面程序**，让客户端安装并启动 `CodexQuotaGuardMeter` Windows 服务；然后登录 Quota Guard **系统账户**完成设备配对。
4. 在该电脑的**官方 Codex 应用或 CLI** 中自行登录官方账户。回到 Quota Guard 查看官方额度、个人/团队用量、逐笔流水和计费服务状态。

安装程序本身不会注册计费服务；若服务安装失败，可在客户端查看失败步骤，或参照[架构文档的排障章节](./docs/architecture/current-system-architecture.md#日志与排障)。正式部署应使用 HTTPS；局域网 HTTP 会明文传输本系统登录信息。

## 系统如何协作

```mermaid
flowchart LR
    Codex[官方 Codex CLI / 桌面端] -->|本机会话日志| Meter[Windows Rust 计费服务]
    Meter -->|Token 事件与心跳| API[中央 Java API]
    Desktop[React + Tauri 桌面端] --> Local[本地 Java 服务]
    Codex -.->|官方额度摘要| Local
    Local -->|系统登录与额度查询| API
    Admin[Ant Design Pro 管理端] --> API
    API --> DB[(PostgreSQL)]
```

桌面端的本地 Java 服务仅监听回环地址；中央 Java API 管理用户、团队、额度和已入库的用量事件。更完整的数据流见[当前系统架构](./docs/architecture/current-system-architecture.md)。

## 从源码运行

开发电脑需要 Java 17、Maven 3.9+、Node.js 22+、pnpm 10.12.3；运行或构建桌面端还需要 Rust 1.85+ 和对应平台的 Tauri 依赖。中央服务需要 PostgreSQL。先参照 [`apps/admin/backend/.env.example`](./apps/admin/backend/.env.example) 将数据库和 JWT 配置注入启动环境。

Windows 可直接使用仓库根目录的 [一键启动-管理后台.cmd](./一键启动-管理后台.cmd) 和 [一键启动-桌面客户端.cmd](./一键启动-桌面客户端.cmd)。也可以分别在终端运行：

```bash
pnpm install
```

然后在**三个不同终端**分别运行 `pnpm dev:admin-backend`（中央 API，默认 8080）、`pnpm dev:admin`（管理页，默认 `http://localhost:8000`）和 `pnpm dev:client`（桌面端）。Windows 一键启动桌面脚本还会检查、安装或启动独立计费服务，并在需要时请求管理员授权。普通用户电脑请使用发布的安装包，不必配置源码开发环境。

## 构建与验证

```bash
pnpm verify
```

在 Windows 上打包桌面安装程序：

```powershell
& .\scripts\windows\package-client.ps1
```

默认产物目录为 `D:\BuildCache\pingche\desktop-package-target\release\bundle\nsis`。打包电脑没有 D 盘时，可传入 `-TargetDirectory 'C:\BuildCache\codex-quota-guard'` 自行指定目录；首次构建需要联网下载并校验随包运行时。也可双击 [一键打包-桌面客户端.cmd](./一键打包-桌面客户端.cmd) 使用默认设置。

## 项目结构

```text
apps/
  admin/
    frontend/   Ant Design Pro 管理界面
    backend/    中央 Java API
  client/
    frontend/   React + Tauri 桌面端
    backend/    本地 Java 服务
    meter/      Windows Rust 计费服务
docs/          架构、API 与产品文档
scripts/       启动、打包与诊断脚本
```

## 使用边界

- 计费只覆盖**本机可读取且格式受支持**的 Codex 会话日志；ChatGPT 桌面聊天、其他设备或自定义日志位置不保证计入。首次配对只建立旧日志游标，不追溯旧用量；未配对或上报失败时显示的 `0 Token` 不代表真实用量为零。
- Windows 常驻服务已实现；macOS/Linux 尚无对应常驻服务安装器和已发布安装包。目前在这两个系统上需自行构建桌面端并提供可再分发的 Java 17 运行时，且只能在桌面客户端打开时使用本地 Java 回退扫描。拥有设备管理员/root 权限的人仍可停止服务或修改本机日志。
- 管理员“强制下线”只撤销 **Quota Guard 系统会话**，不会退出官方 Codex。个人额度用尽也不能强制停止官方 Codex 的请求。
- 模型费用基于可配置的单价与已采集的 Token 分项估算；内置价格是 [CC Switch](https://github.com/farion1231/cc-switch) 的固定版本快照，**不是实时官方报价**。详见[统计与定价口径](./docs/architecture/usage-pricing.md)。

## 文档

- [文档索引](./docs/README.md) · [当前系统架构](./docs/architecture/current-system-architecture.md)
- [个人 Token 统计](./docs/architecture/local-codex-token-quota.md) · [团队额度策略](./docs/architecture/team-quota-policy.md)
- [用量与 USD 估算](./docs/architecture/usage-pricing.md) · [Web API 契约](./docs/api/web-backend-api.md)

管理端界面基于 [Ant Design Pro](https://github.com/ant-design/ant-design-pro) 定制；README 的首页排版参考 [CC Switch](https://github.com/farion1231/cc-switch) 与 [NextDevTpl](https://github.com/evepupil/NextDevTpl)。

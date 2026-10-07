# Codex Quota Guard

Codex Quota Guard 是一个同时包含跨平台桌面客户端与 Web 管理后台的单仓库项目。

## 技术栈

- 客户端界面：React 18、TypeScript、Vite、Tailwind CSS、shadcn/ui
- 客户端桌面壳：Tauri 2 + Rust
- 客户端本地后端：Java 17 + Spring Boot 4
- 管理端界面：Ant Design Pro 6、React 19、Umi Max 4、Ant Design 6、Pro Components 3
- 管理端后端：Java 17 + Spring Boot 4、Spring Security、PostgreSQL、Flyway

客户端本地后端负责桌面壳与中央服务之间的安全会话和固定接口转发。
管理端后端负责系统用户、团队与个人 Token 策略及管理员权限。管理员在用户设备上通过官方
ChatGPT/Codex 应用手动登录；Quota Guard 不再保存或下发官方凭证。桌面端登录本系统账户后，
在 Windows 上配对独立的 Rust 常驻服务，读取本机官方 Codex 会话日志并向中央 Java 服务上报个人 Token 用量；官方额度百分比从本机
Codex app-server 读取，安全摘要会显示在桌面端和管理端。个人限额只用于统计与提醒，
不能强制阻止官方客户端继续使用。整体关系见[当前系统架构](docs/architecture/current-system-architecture.md)，计量细节见[本机官方登录与个人 Token 统计](docs/architecture/local-codex-token-quota.md)。

## 目录结构

```text
apps/
  admin/
    frontend/   Ant Design Pro 管理界面
    backend/    Java Web 用户与管理 API
  client/
    frontend/   React + Tauri 桌面客户端
    backend/    Java 桌面本地服务
    meter/      Rust 常驻计费服务（Windows Service）
docs/
  architecture/ 架构方案与能力边界
  api/          对外 API 契约
  product/      产品流程与用户旅程
  README.md     文档索引
package.json    前端工作区统一命令
pom.xml         Java 后端 Maven 聚合入口
pnpm-workspace.yaml  pnpm 工作区声明
```

每个应用的前端与后端都放在同一个应用目录下，同时保持独立构建和部署边界。
仓库根目录只保留工作区入口；依赖目录和构建产物由工具生成，不属于源码层级。

## 环境要求

- Java 17
- Maven 3.9+
- Node.js 22+
- pnpm 10.12.3
- Rust 1.85+ 与 Tauri 对应平台依赖（只开发管理端时不需要 Rust）
- PostgreSQL（运行管理端后端时需要）

安装前端依赖：

```bash
pnpm install
```

## 运行管理后台

Windows 可直接双击项目根目录的 `一键启动-管理后台.cmd`。它会避免重复启动，
按需构建 Java API，默认监听 `0.0.0.0:8080`，启动管理网页并打开登录页；日志保存到
`D:\BuildCache\pingche\logs`。局域网 HTTP 仅适合临时可信网络，正式使用应配置 HTTPS。
本机访问 `http://127.0.0.1:8080`，同一局域网的其他设备访问
`http://192.168.0.102:8080`（IP 变化时以本机 `ipconfig` 的 WLAN IPv4 为准）；
`0.0.0.0` 只是监听地址，不能填在客户端。Windows 防火墙入站规则应只允许可信局域网子网。

先按 `apps/admin/backend/.env.example` 配置 PostgreSQL、JWT 和管理员初始化变量，再启动 Java API：

```bash
pnpm dev:admin-backend
```

另开一个终端启动 Ant Design Pro：

```bash
pnpm dev:admin
```

默认管理页面为 `http://localhost:8000`，一键启动脚本会把 `/api` 代理到所选后台
监听地址。直接使用 `pnpm dev:admin` 时，仍默认代理到 `http://127.0.0.1:8080`。

## 运行桌面客户端

Windows 可直接双击项目根目录的 `一键启动-桌面客户端.cmd`。它会自动定位 Java，
构建本地 Java 服务并打开 Tauri 客户端；重复双击只会激活已有窗口。客户端默认连接
`http://127.0.0.1:8080` 的中央服务。安装版可在登录页的“服务器设置”中更改地址；
保存后需要重启客户端。推荐 HTTPS；仅在可信局域网临时使用 HTTP 时，须勾选风险确认。

```bash
pnpm dev:client
```

该命令会构建 `apps/client/backend` 的 Java JAR，把它复制到 Tauri 资源目录，
然后启动 Vite 与 Tauri。Java 服务只监听回环地址，并由 Tauri 管理生命周期。启动后
必须先登录本系统账号；官方 Codex 登录应在官方桌面应用或 CLI 中自行完成。
本程序不会读取、覆盖或下发官方凭证。开发阶段的局域网 HTTP 会传输本系统密码，仅用于可信测试环境。

Windows 常驻统计需要在每台用户电脑上以管理员身份运行
`scripts/windows/install-meter-service.ps1 -ServerUrl http://192.168.0.102:8080`。
脚本会注册自动启动的 `CodexQuotaGuardMeter` 服务；然后重开客户端并登录本系统账号完成设备配对。
安装脚本不会重启电脑。客户端“计费程序状态”会显示运行、配对和上报结果；后台“用户管理 → 查看额度”
会显示最近心跳。当前一个 Windows 登录用户只对应一个系统账户，不能在同一设备 ID 上改绑其他账户；
管理员撤销后该设备 ID 也不可自行重新配对，改绑需要管理员重新登记新设备。服务已安装但未配对时，不应把 0 Token 当作实际用量为 0。
打包版安装包包含 Rust 可执行文件和安装脚本；在目标电脑安装桌面程序后，可从安装目录的 `meter` 子目录
运行上述脚本（安装包本身不会静默安装系统服务）。

新版 Rust 服务的本地运行日志位于 `%ProgramData%\CodexQuotaGuard\Meter\logs\meter.jsonl`，
管理员 PowerShell 可用 `Get-Content "$env:ProgramData\CodexQuotaGuard\Meter\logs\meter.jsonl" -Tail 50`
查看。日志只保存运行状态、心跳、已上报事件数/Token 合计及固定错误码，不保存凭证或原始会话内容；
单文件最大 2 MiB，另保留 5 份轮转备份。源码更新后必须重新打包并升级已安装服务，旧可执行文件不会自动获得日志功能。

## 验证与构建

```bash
mvn verify
pnpm test:client
pnpm test:admin
pnpm build:client
pnpm build:admin
cargo test --manifest-path apps/client/frontend/src-tauri/Cargo.toml
cargo test --manifest-path apps/client/meter/Cargo.toml
```

也可以执行聚合验证：

```bash
pnpm verify
```

桌面安装包需要分别在 Windows、macOS、Linux 上构建。管理端前端的生产构建产物
位于 `apps/admin/frontend/dist`，应与启用 HTTPS 的 Java API 或反向代理一起部署。

## 安全边界

- 管理 API 的管理员权限由 Java 后端强制校验，前端菜单隐藏不是权限控制。
- 密码只保存 BCrypt 哈希；refresh token 在数据库中只保存 SHA-256 摘要。
- 强制下线仅使本系统的 access token 与 refresh token 失效，不会退出官方 ChatGPT/Codex 应用。
- 旧托管账户及凭据在受保护备份后从活动 PostgreSQL 库清除；备份仍应按敏感数据管理。
- Windows Rust 服务在客户端关闭后仍会继续读取可用的官方会话日志并上报心跳；用户或管理员可在后台看到设备离线状态。首次启动只建立旧日志游标，不追溯计费。
- 个人用量仍依赖本机可读取的会话日志，无法保证覆盖官方应用全部活动，也不能阻止拥有本机管理员权限的人停止服务、篡改日志或继续使用官方客户端。macOS/Linux 常驻服务尚未实现；当前仅有客户端打开时的 Java 扫描回退。
- 生产环境必须使用 HTTPS，并从环境变量或密钥管理服务注入数据库和 JWT 密钥。
- 桌面端临时 bearer token、Web access token、refresh token 和数据库密码均不得提交到 Git。

管理端前端基于 [Ant Design Pro](https://github.com/ant-design/ant-design-pro) 官方模板定制。

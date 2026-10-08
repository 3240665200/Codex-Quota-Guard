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

## 开发和打包电脑的环境要求

- Java 17
- Maven 3.9+
- Node.js 22+
- pnpm 10.12.3
- Rust 1.85+ 与 Tauri 对应平台依赖（只开发管理端时不需要 Rust）
- PostgreSQL（运行管理端后端时需要）

Windows **安装版目标电脑**不需要 Java、Maven、Node.js、pnpm 或 Rust。打包脚本会从
Eclipse Adoptium 下载经 SHA-256 校验的 Temurin Java 17 运行时，并随安装包附带
WebView2 离线安装程序；下载和编译只在打包电脑进行。目标电脑仍需受支持的 Windows，
并能连接中央服务。查看官方 Codex 额度或统计 Codex 会话时，还需该电脑上的官方 Codex
应用或 CLI 已自行登录；这不是本程序的开发环境依赖。

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
支持直接填写 HTTP 或 HTTPS 地址，保存后需要重启客户端，无需确认勾选。
`0.0.0.0` 是服务端监听地址，客户端填写它时会自动改为 `127.0.0.1`；其他电脑应填写服务器的实际 IP 或域名。

```bash
pnpm dev:client
```

使用 `scripts/windows/start-client.ps1` 启动时，脚本会先准备 `CodexQuotaGuardMeter`，再编译或打开桌面窗口。
服务未安装时会询问是否安装；确认后请求 Windows 管理员授权。已安装但停止时立即启动。
脚本采用客户端保存的服务器地址，并同步计费服务配置；也可传入 `-ControlPlaneUrl` 同时更新两者。源码中的计费程序更新后，脚本会检测已安装版本并请求管理员授权升级。
取消安装或服务启动失败时脚本会停止启动流程并显示原因。已有服务的设备 ID、绑定账户和统计状态会保留。

该命令会构建 `apps/client/backend` 的 Java JAR，把它复制到 Tauri 资源目录，
然后启动 Vite 与 Tauri。Java 服务只监听回环地址，并由 Tauri 管理生命周期。启动后
必须先登录本系统账号；官方 Codex 登录应在官方桌面应用或 CLI 中自行完成。
本程序不会读取、覆盖或下发官方凭证。开发阶段的局域网 HTTP 会传输本系统密码，仅用于可信测试环境。

Windows 常驻统计由每台用户电脑上的 `CodexQuotaGuardMeter` 服务负责。安装 0.2.4 或更新版后，
先在客户端保存正确的中央服务器地址并重启，再右键以**管理员身份运行安装后的桌面程序**。
首次启动时，若服务未安装、打包资源齐全且中央服务可连接，客户端会自动安装并启动服务；
然后登录本系统账号完成设备配对。普通权限、地址错误或服务器不可连接时不会执行安装，
客户端会给出对应提示，可在修正后于仪表盘点击“安装计费服务”重试。
已有服务若停止，客户端会请求 Windows 启动；权限不足时只针对固定服务发起一次 UAC 授权。
关闭桌面窗口不会停止 Rust 服务；它仍由 Windows 服务管理器负责开机启动和进程异常退出后的恢复。
客户端不能绕过管理员主动停用或 Windows 权限控制。也可在管理员 PowerShell 手动运行
`scripts/windows/install-meter-service.ps1 -ServerUrl http://192.168.0.102:8080` 作为故障排查方式。
安装过程不会重启电脑。客户端“计费程序状态”会显示运行、配对和上报结果；后台“用户管理 → 查看额度”
会显示最近心跳。当前一个 Windows 登录用户只对应一个系统账户，不能在同一设备 ID 上改绑其他账户；
管理员撤销后该设备 ID 也不可自行重新配对，改绑需要管理员重新登记新设备。服务已安装但未配对时，不应把 0 Token 当作实际用量为 0。
打包版包含本地 Java 服务、Temurin Java 17 运行时、WebView2 离线安装程序、Rust
计费可执行文件和安装脚本。安装包本身不注册计费服务；由首次以管理员身份运行的桌面程序执行安装。

新版 Rust 服务的本地运行日志位于 `%ProgramData%\CodexQuotaGuard\Meter\logs\meter.jsonl`，
管理员 PowerShell 可运行 `& .\scripts\windows\show-meter-logs.ps1 -Tail 50` 查看最近记录，
或加 `-ErrorsOnly` 只看错误。后台“用户管理 → 查看额度 → Token 用量流水”提供该用户**已入库事件**的分页明细。
本机日志只保存运行状态、心跳、已上报事件数/Token 合计及固定错误码，不保存凭证或原始会话内容；
单文件最大 2 MiB，另保留 5 份轮转备份。源码更新后必须重新打包并升级已安装服务，旧可执行文件不会自动获得日志功能。

## 验证与构建

Windows 一键打包可运行 `& .\scripts\windows\package-client.ps1`，或使用
`pnpm build:client`。首次打包需要联网下载固定版本的 Temurin JRE 和 WebView2
离线安装程序，之后 Temurin 压缩包缓存在
`D:\BuildCache\pingche\desktop-package-target\java-runtime-cache`。
运行时随安装包放在 `java/` 资源目录，正式版不会调用系统 `PATH` 中的 Java。
安装包输出到 `D:\BuildCache\pingche\desktop-package-target\release\bundle\nsis`。
macOS/Linux 打包需通过 `QUOTA_GUARD_BUNDLE_JAVA_HOME` 指定可再分发的 Java 17
运行时，并在对应系统上构建。

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

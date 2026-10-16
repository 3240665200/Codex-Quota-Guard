# Windows 本地管理网页测试

本指南用于当前源码的代理订阅与官方账户前置／出口链测试，只需管理网页和中央 API。无需启动桌面客户端、统计程序或服务器浏览器。页面测试会使用所连接数据库中的数据，保存订阅或账户链会写入该数据库。

## 先确认工作区

本轮续做目录是 `C:\Users\32406\.codex\worktrees\cfe6\pingche`，分支为 `codex/unified-auth-server-v0.3.20`。分支名保留历史版本号，发布基线已到 `0.3.22`，当前工作区还有其后的订阅与代理链改动。

`E:\project\pingche` 的 `main` 是较早的 `0.3.18` 阶段代码。请从续做目录启动，避免运行到旧页面或旧后端。已发布 `0.3.22` 镜像也尚不包含这里的持久订阅和前置链，不能把本地源码通过测试视作线上已更新。

## 启动前准备

使用保存配置时的同一个 Windows 用户打开 PowerShell，确认下列条件：

| 项目 | 本机默认值或要求 |
| --- | --- |
| 工具 | Java 17、Maven、Node.js、pnpm 在 PATH 中可用 |
| 依赖 | 当前工作区已安装前端依赖；Maven 依赖已缓存，预览使用 `mvn -o` 离线启动 |
| 数据库 | PostgreSQL 已运行；实际库地址和账号取自已有受保护配置，不因监听 5432 就假定配置正确 |
| 后台配置 | `D:\BuildCache\pingche\private\admin-backend.clixml`，由本机同一用户用 DPAPI 保存 |
| 功能密钥 | `D:\BuildCache\pingche\management-preview\feature-keys.clixml`，包含既有官方授权与代理配置的加密密钥 |
| 代理内核 | `D:\codex-tools\sing-box-1.13.14\sing-box-1.13.14-windows-amd64\sing-box.exe` |
| 端口 | 前端 8000、中央 API 18080 均应空闲；仅绑定 `127.0.0.1` |
| 管理员 | 使用目标数据库中既有管理员账号和密码；没有默认密码，启动器不会创建或重置管理员 |

保留原 DPAPI 配置、`feature-keys.clixml`、数据库和密钥备份。已有密文时不要删除或重新生成密钥，也不要更换预览目录来规避解密错误；缺失时恢复匹配的原文件。DPAPI 文件不能直接换一个 Windows 用户或另一台电脑使用。不要把解密后的配置、订阅 URL、Authorization、Cookie 或节点密码输出到终端、聊天或仓库。

当前源码启动时会由 Flyway 应用 V32/V33。使用已有数据库前应有可恢复备份，不能清库或改写已执行迁移来解决启动失败；升级与降级边界见[订阅与代理链迁移说明](proxy-subscriptions-and-chains.md#api)。空库不会自动拥有原管理员和原账户。

## 启动与登录

在 PowerShell 中执行：

```powershell
Set-Location 'C:\Users\32406\.codex\worktrees\cfe6\pingche'
& '.\一键启动-管理后台.cmd'
```

也可以在这个目录双击 `一键启动-管理后台.cmd`。它调用 `scripts/windows/start-admin-local-test.ps1`，再调用已有预览管理器；通常等待约三分钟以内，成功后自动打开[管理登录页](http://localhost:8000/#/user/login)。使用既有管理员登录即可。网页通过 8000 转发 API 请求到 18080，不要将 18080 当成网页地址。

希望自行打开浏览器，并让启动命令直接返回时：

```powershell
& '.\scripts\windows\start-admin-local-test.ps1' -NoBrowser -NoPause
```

启动结果会显示 `RunDirectory`、`StatusPath`、前后端日志路径与该次运行的完整停止命令。请保留这些信息。按 Enter 或关闭启动窗口后服务仍继续运行；服务由独立隐藏管理进程持有，不依赖这个窗口。端口被占用时会明确失败，不会自动结束其他进程，也不会静默复用旧代码实例。

默认配置路径与本机不同时，可直接调用现有底层入口并保留返回对象；参数中的路径应指向原有配置、原有密钥所在预览目录和已安装内核，不能用空目录代替已有密钥目录：

```powershell
$preview = & '.\scripts\windows\start-admin-preview.ps1' `
    -ConfigPath 'D:\BuildCache\pingche\private\admin-backend.clixml' `
    -PreviewDirectory 'D:\BuildCache\pingche\management-preview' `
    -ProxyKernel 'D:\codex-tools\sing-box-1.13.14\sing-box-1.13.14-windows-amd64\sing-box.exe'
$preview | Format-List
```

本次不需要 `-EnableWebLogin`。已有服务器私有授权预置需显式配置时，可给底层入口添加 `-AuthorizationPresetFile`，值为既有私有文件的绝对路径；它只把文件位置交给后台，不自动保存授权或调用官网。

## 页面测试顺序

1. 打开[代理节点](http://localhost:8000/#/proxy-nodes)。确认手动 HTTP、HTTPS、SOCKS5 节点仍可查看；对获准测试的节点执行单节点出口测试，查看 IP、地区与时间。单节点成功只证明该节点本次可用，不能代替完整链路测试。
2. 点击“管理订阅 → 导入订阅”，填写一个获准使用的订阅名称和 URL，再点“导入并获取节点”。首次导入会下载并显示节点；订阅列表只显示主机和保存状态，不回显 URL。之后必须点击“更新节点”才手动下载，修改名称或 URL 并保存不会立即下载，URL 留空则保留原链接。
3. 订阅下载由当前 Java 后台直接访问公网 HTTPS 443，禁止 URL 用户信息和重定向，最多 1 MiB、128 个节点。它不经过官方账户链；本机 TUN 或本地浏览器能打开订阅，也不能证明国内服务器部署后能下载。当前仅导入支持的 URI、Base64 或 Clash/Mihomo 顶层 `proxies` 参数，错误时保留原节点。
4. 在[官方账户 → 账户管理](http://localhost:8000/#/official-accounts/accounts)点击“授权登录”，在“访问代理节点”选择已启用的后置／出口节点，按需选择不同的“前置代理节点”。链路为“后台 → 前置（可选）→ 后置／出口 → 官网”；例如订阅节点作前置、静态 ISP SOCKS5 作出口。留空前置表示单出口，仍须选择出口。
5. 点击“测试完整链路”，核对最终出口 IP 和地区。该测试使用当前选择，不保存账户配置，也不更新单节点测试结果。失败时不要继续把旧测试结果当作当前链可用的证据。
6. 已有授权点击“保存代理链”即可，无需重填 Authorization 或 Cookie。新连接仍需管理员明确使用既有预置或导入自己的授权后保存。保存后关闭并重开窗口，确认选择保留；再手动刷新官方账户和会话，以验证保存的链真正用于官方请求。保存链会使旧快照失效，测试出口成功也不保证官方接口通过验证。
7. 如需覆盖订阅更新与故障，只对专用测试订阅、节点和账户操作：同名节点更新应保留 ID，消失节点应显示“来源缺失”并停用；故障前置或出口不得被跳过，不得回退直连。节点再次出现后不会自动启用，原本手动停用的节点也不会被更新重新启用。删除订阅会保留节点及账户引用，不会连带删除节点。

只刷新与查看会话即可完成本轮链路验收，无需执行真实官方登出、解绑或密码重置。自动化模拟测试与本地页面可用，均不代表真实订阅、前后置公网节点、官方账号或国内服务器已验收通过。

## 停止与查看状态

优先复制启动窗口显示的完整 `Stop this run: ...` 命令，到 PowerShell 执行。若通过底层入口保留了 `$preview`，在同一个 PowerShell 中直接执行：

```powershell
& '.\scripts\windows\stop-admin-preview.ps1' -RunDirectory $preview.RunDirectory
Get-Content -LiteralPath $preview.StatusPath -Raw
```

停止命令返回 `STOP_REQUESTED` 表示请求已写入；稍后再次查看 `status.json`，`Phase` 为 `EXITED` 才表示管理进程完成清理。`FAILED` 则需查看同次运行的 `ErrorCode` 和日志。停止只关闭该次运行持有的前后端子进程，保留数据库、配置、密钥和日志，不调用官方登出。不要按 `java.exe` 或 `node.exe` 名称批量杀进程。

每次运行的文件位于 `D:\BuildCache\pingche\management-preview\runs\<runId>\`。启动输出中的路径对应本次实例，不应凭“最新目录”猜测另一个实例。可在设置好该次 `$preview` 后查看：

```powershell
Get-Content -LiteralPath $preview.BackendLog -Tail 80
Get-Content -LiteralPath $preview.FrontendLog -Tail 80
Get-NetTCPConnection -State Listen -ErrorAction Stop |
    Where-Object LocalPort -in @(8000, 18080) |
    Select-Object LocalAddress, LocalPort, OwningProcess
```

| 现象 | 处理 |
| --- | --- |
| `PREVIEW_CONFIG_MISSING` | 恢复当前 Windows 用户的既有 DPAPI 配置，不重置数据库账号或密钥 |
| `PREVIEW_PROXY_KERNEL_MISSING` | 核对 sing-box 文件位置；有自定义位置时用底层入口传入 `-ProxyKernel` |
| `PREVIEW_PORT_UNAVAILABLE` | 查清 8000/18080 的已有监听与运行记录，用原实例的停止入口退出后再启动 |
| `PREVIEW_DIRECTORY_NOT_PRIVATE` / `PREVIEW_PATH_REPARSE_POINT` | 核对既有私有目录权限与实际路径，不放宽全盘权限或改用新密钥目录 |
| `PREVIEW_START_FAILED` / `PREVIEW_START_TIMEOUT` | 打开错误中给出的 `status.json`，检查同目录 `api.out.log`、`api.err.log`、`ui.out.log`、`ui.err.log`；离线 Maven 缺依赖时先按项目安装说明准备依赖 |
| 登录被拒绝 | 核对当前数据库、既有账号状态和管理员角色；没有可尝试的默认密码 |
| 订阅下载失败 | 核对后台本身能否访问该公网 HTTPS 443 地址、是否跳转、内容和协议是否受支持；账户链不会代理下载。默认 TUN Fake-IP 会触发有时限的公共 DNS 查询，取得真实公网地址后仍须能连接；不会放行真实私网地址 |
| 保存链后旧数据显示为空 | 旧快照已失效；手动刷新官方账户和会话，查看新链的实际结果 |
| `Failed to fetch` / 中央后台不可用 | 先查本次状态、两个端口及日志；不要把服务退出当作授权失效，也不要自动重发官方操作 |

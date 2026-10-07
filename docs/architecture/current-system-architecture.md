# 当前系统架构

本文描述当前代码，不把规划中的能力写成已上线功能。项目是一个 monorepo：`apps/admin` 是中央管理系统，`apps/client` 是用户桌面端；Java 17 仍是两端的业务后端，Rust 用于 Tauri 壳和独立计费服务。

## 运行组件

```text
官方 Codex CLI / 桌面应用
       │ 本机 .codex/sessions/*.jsonl
       ▼
Rust Windows 服务 ──设备令牌、用量事件、心跳──▶ 中央 Java API ──▶ PostgreSQL
       ▲                                            ▲
       │ 首次配对                                   │ 系统用户、团队与额度策略
React/Tauri 桌面端 ──回环 API──▶ 本地 Java 服务 ────┤
                                                    │
Ant Design Pro 管理页 ─────────管理员 API────────────┘
```

| 组件 | 代码位置 | 职责与生命周期 |
| --- | --- | --- |
| 管理页 | `apps/admin/frontend` | 管理系统用户、团队、个人 Token 上限；查看用量、官方额度摘要和设备心跳。 |
| 中央 Java API | `apps/admin/backend` | Spring Security 鉴权、PostgreSQL/Flyway 持久化、幂等事件入库及额度计算；独立部署。 |
| 桌面界面与 Tauri 壳 | `apps/client/frontend` | 本机登录界面与仪表盘；Tauri 仅向本地 Java 转发固定命令，并管理 Java 子进程。 |
| 本地 Java 服务 | `apps/client/backend` | 只监听回环地址；内存中维护本系统会话、配对计费服务、读取本机官方额度摘要；随桌面程序关闭。 |
| Rust 计费服务 | `apps/client/meter` | Windows Service，独立于桌面窗口运行；增量扫描本机 Codex 会话日志，发送用量与心跳。 |

管理页开发端口默认 `8000`，中央 API 默认 `8080`。桌面本地 Java 使用随机回环端口，不向局域网开放。管理端生产部署应通过 HTTPS/反向代理暴露中央 API；开发阶段允许受信任局域网 HTTP，但会明文传输本系统密码和会话。

## 两套身份与两种额度

1. **Quota Guard 系统账户**：本项目用户、管理员和团队使用。桌面端登录后，本地 Java 向中央 Java 获取本系统会话；管理员的强制下线只使本系统会话失效。
2. **官方 Codex 登录**：用户在官方应用或 CLI 中自行完成。Quota Guard 不托管、分发或覆盖官方凭证，也不替用户登录官方 ChatGPT 桌面应用。
3. **官方额度**：本地 Java 通过 Codex app-server 的 `account/read` 与 `account/rateLimits/read` 读取账户级套餐、窗口百分比和重置时间，只上报安全摘要。官方没有返回的窗口不得推算或伪造。
4. **个人 Token 额度**：管理员直接填写团队默认及用户覆盖的滚动 5 小时、7 天 Token 上限。用户覆盖优先于团队；`null` 是未配置，`0` 是零额度。这是本系统按人统计值，不能由官方账户百分比换算。

## 配对与计费数据流

1. 管理员在每台 Windows 用户电脑上以该 Windows 用户身份安装 `CodexQuotaGuardMeter`。安装脚本固定该用户的 `.codex` 目录，创建设备 ID，并把服务设为自动启动及异常退出后恢复。安装程序目前**不会静默注册服务**。
2. 用户登录桌面端的系统账户。本地 Java 使用系统 access token 调用中央 `/client/meter/enroll`，取得仅可访问 `/meter/**` 的设备令牌，并通过本地配对收件箱交给 Rust 服务。中央数据库只保存设备令牌 SHA-256 摘要；Rust 将令牌放在受限的 ProgramData 目录。一个设备 ID 不能自行改绑另一系统用户，撤销后也不能自行恢复。
3. Rust 首次启用与首次配对只建立旧日志的字节游标，不追溯计费。之后优先读取新增 `token_usage_record.total_tokens`，生成不含原始响应 ID 的稳定事件 ID。每批至多 100 条；上报成功才提交扫描游标，失败则保留游标重试。
4. 中央 Java 校验设备令牌、事件格式和时间，按系统用户与事件 ID 去重，存入 `codex_usage_events`。5 小时/7 天窗口按**服务端接收时间**滚动求和，计算已用、剩余和提醒状态。Rust 每约 30 秒发送心跳；管理员可见最后心跳与在线/离线状态。
5. 桌面窗口关闭后 Rust 服务仍可继续上报。桌面程序打开时的 Java 扫描器可作为回退，并兼容部分旧版 `token_count` 累计记录；中央去重防止同一事件重复计费。

## 日志与排障

新构建的 Rust 服务将安全摘要写到 `%ProgramData%\CodexQuotaGuard\Meter\logs\meter.jsonl`，轮转文件为 `meter.1.jsonl` 至 `meter.5.jsonl`。单文件上限 2 MiB，总占用约 12 MiB；轮转时最旧备份会被删除。日志记录启动/正常停止、基线、配对前状态、成功上报的事件数和 Token 合计、心跳及固定错误码；**不写入官方凭证、设备令牌、原始会话行或响应 ID**。日志目录继承计费服务状态目录的 SYSTEM/Administrators ACL。安装旧版的电脑必须重新构建并升级服务，源码更新不会自动替换运行中的可执行文件。

管理员 PowerShell 可查看最近记录：

```powershell
Get-Content "$env:ProgramData\CodexQuotaGuard\Meter\logs\meter.jsonl" -Tail 50
Get-Service CodexQuotaGuardMeter
```

桌面端的 `meter-status.json` 只是当前状态快照；中央 `codex_usage_events` 才是已接收的逐笔事件。Windows 事件查看器的 Service Control Manager 记录启动和异常退出，不包含逐笔 Token 数据。

## 不能保证的事

- 官方 Codex 请求不经过本系统，所以个人额度用尽只触发本系统提醒，**不能硬性阻断官方 Codex 或让官方会话下线**。
- 管理员/root 可以主动停止服务或修改本机日志；服务恢复策略只应对进程意外退出，不能防止管理员停用。没有跨平台通用内核驱动，也不承诺防篡改计费。
- 只能统计本机可读取且格式受支持的 Codex 会话记录；普通 ChatGPT 桌面聊天、其他设备或自定义 `CODEX_HOME` 不一定包含在此统计中。官方额度是账户级参考，不是个人账单。
- Windows Service 已实现；macOS `launchd`、Linux `systemd` 的常驻安装器尚未实现。尚未完成设备配对和新用量的实际入库验证时，仪表盘的 0 Token 不代表真实使用量为零。

接口细节见 [Web 后端 API](../api/web-backend-api.md)，运行与打包命令见项目根目录 [README](../../README.md)。

# Rust 会话归属验证 Demo

独立、离线的命令行实验，不接入现有桌面客户端、统计服务或后端，不修改登录状态，也不提供任何下线接口。

## 先说结论

**当前实现能查询明确登记的归属，能识别新会话候选和归属冲突；不能自动证明真实会话属于哪个用户。**

官网的登录会话列表没有 Quota Guard 用户 ID。IP、设备名、时间和“只有一条新增”都不是可靠的身份证明。这里不把 ChatGPT 对话 ID、Codex thread ID 或本系统设备 ID 当成官网的 `session_id`。

2026-10-14，经用户授权进行了一次本机只读比对：

- 官网已捕获快照：20 条会话，其中 15 条标记为 Codex。
- 本机 `access_token` / `id_token` 的 JWT 内容能解码，分别包含 `session_id` / `sid` 字段。
- 比对解码内容里的字符串：**没有任何值与快照中的 `session_id` 完全相同**。
- 这不证明永远无法映射：快照时效、浏览器与本机是否同一账号、标识命名空间均未验证。
- 未验证 JWT 签名；未刷新 Token、修改凭证、调用下线或其他变更接口。Token 只在进程内存处理，未上传或存入新文件。官网临时快照在比对后已删除。

## 运行示例

在本目录打开 PowerShell：

```powershell
.\dist\quota-session-binding-demo.exe demo
```

所有示例都是虚构数据。输出 JSON 中可以看到：

| 会话 | 结果 | 含义 |
| --- | --- | --- |
| Alice 已登记会话 | `RECORDED_BINDING` → `alice-demo` | 根据已有人工记录查询，不是程序自动认人 |
| Bob 已登记会话 | `RECORDED_BINDING` → `bob-demo` | 同上 |
| 旧的未知会话 | `UNBOUND` | 没有归属证据 |
| Carol 操作期间新增的会话 | `CANDIDATE`，owner 为 null | 只是候选，不直接认领给 Carol |

另有 `CONFLICT`（重复会话 ID 或多个不同用户/设备/配置目录登记）和 `UNAVAILABLE`（没有有效会话 ID）。冲突时不选任何一个 owner。

```powershell
.\dist\quota-session-binding-demo.exe analyze .\examples\multiple-users.json
```

如已通过独立方式确认某条候选的归属，从结果复制完整 `session_ref`，显式登记：

```powershell
.\dist\quota-session-binding-demo.exe confirm .\examples\multiple-users.json --session-ref "<完整候选哈希>" --confirmed-by "operator-demo" --acknowledge-owner --out .\binding.local.json
.\dist\quota-session-binding-demo.exe analyze .\binding.local.json
```

登记为输入 `context` 中的用户、设备和 profile。`--acknowledge-owner` 仅表示操作者确认，并不验证身份。已有冲突/不同归属不覆盖；输出文件必须不存在。

## 输入与安全边界

- `before` / `after` 必须是同一官方账号登录前后的**完整**会话列表，且 `show_session_manager: true`；缺失、未开启、不合法快照拒绝处理。真实来源、分页完整性、账号一致性和新鲜度仍须操作者独立核实，程序不能认证这些事实。
- `context.official_account_id` 是操作者提供的分组标识，**不是已验证的 OpenAI 账号身份**，也不能默认拿本项目官方账户表的内部 UUID 当成真实 OpenAI 账号 ID。
- 绑定键：账号范围 + 官方 session_id；归属：业务用户 + 已登记设备 + Codex profile。每个人应该使用独立 profile，不应复制同一份登录凭证。
- 同一会话登记到多个用户、设备或 profile 时标记冲突；未知的凭证复制无法仅凭该表发现。
- 报告只展示 `SHA256(account_id + NUL + session_id)` 作为 `session_ref`，不打印原始 session_id。它只是本 Demo 的引用，不是官网接口参数。
- **confirm 输出文件会保留原始 session_id 和业务归属**，不是匿名报告。真实输入/登记文件只放到本人可访问的本地目录，Windows 使用限制访问的目录 ACL；不要提交仓库或共享。`.local.json`、`private/` 和 `dist/` 已忽略。Unix 新建登记文件权限为 0600。
- 从列表消失只表示“本快照未出现”，不等于已下线；重新登录可能需要重新登记。
- 所有报告 `enforcement_allowed: false`。即使登记成功，也不能作为生产环境自动下线授权。

### 可选：本机只读标识比较

只有取得凭证所有者授权后才运行；不要把 Token 粘贴到参数或聊天中：

```powershell
.\dist\quota-session-binding-demo.exe probe --auth-file "<本人授权的 Codex auth.json 路径>" --sessions-file "<受限目录中的官网会话响应 JSON 路径>" --allow-read-auth
```

该命令只打开指定的两个文件，没有网络操作。仅解码 `tokens.access_token` 和 `tokens.id_token` 的 JWT 字符串，不使用 refresh_token 或 API Key。输出计数、固定字段名及匹配行号，不打印 Token、claim 值或原始会话 ID。

`matches` 只表示**字符串恰好相等**。其他字段（如 account_id）碰巧相等也可能出现，归类为 `other_string_claim`，不能证明字段语义、签名或用户归属。`snapshot_row` 是当前输入数组中的零基下标，不能跨快照当成稳定标识。四个字段始终为 false：`signature_verified`、`account_scope_verified`、`owner_established`、`enforcement_allowed`。

输入最多 1 MiB；最多 1000 条会话/登记；错误只返回固定代码，不回显原始输入。

## 构建与测试

需要 Rust 1.85+。此次构建缓存和临时目录设在 D 盘，均为当前 PowerShell 的环境变量，不改变系统配置；其他机器可改成自己的可用路径：

```powershell
$env:CARGO_TARGET_DIR = 'D:\BuildCache\quota-session-binding-demo'
$env:TEMP = 'D:\codex-tools\rust-demo-temp'
$env:TMP = $env:TEMP
$env:CARGO_INCREMENTAL = '0'
New-Item -ItemType Directory -Force $env:CARGO_TARGET_DIR, $env:TEMP | Out-Null
cargo test --locked --jobs 1
cargo clippy --locked --all-targets -- -D warnings
cargo fmt --check
cargo build --locked --release --jobs 1
```

当前验证：43 个测试通过（25 核心归属 + 12 标识比较 + 6 命令行流程），格式检查、clippy 通过。未安装 cargo-llvm-cov / LLVM coverage tools，因此未测量覆盖率，不声明覆盖率达标。真实用户/设备归属尚未端到端验证。

## 下一步验证

若要真正自动绑定，下一步需专门安排一台测试设备和同一官方账号，明确记录一次独立的新登录，再核对本机标识与官网新增会话。需要另行授权，不能自动退出当前设备或复用别人的凭证。只有找到可验证的稳定关联，再考虑接入生产统计服务和精确下线；本 Demo 不实施这些操作。

# 邮箱登录辅助

本功能代收管理员自有邮箱中的指定登录邮件。用户仍在官方页面完成登录；不自动填写官方密码、不导入官方会话凭据，也不把邮箱连接凭据发送给客户端。Rust 统计服务不参与收邮件。

## 使用流程

1. 管理员在「官方账户」中打开对应账户的「邮箱配置」，选择服务商，填写连接信息、收件邮箱和「发件地址 1」。若官方邮件有另一个实际发件地址，再填写可选的「发件地址 2」；两个地址同时生效，任意一个精确匹配即可，无需切换。
2. 保存并点击「测试已保存配置」。此测试只验证 IMAPS 连接、认证、只读文件夹和游标，不读取邮件正文。
3. 将系统用户加入关联该官方账户的启用团队。用户不能自行指定其他邮箱或官方账户。
4. 用户在新版桌面客户端打开「登录辅助」，点击「监听邮件」。**等待出现「正在监听」后，再到官方页面请求验证码**。
5. 后端收到第一封符合规则的新邮件后停止监听，展示本次验证码；用户可主动复制，也可再次监听。取消、超时和离开页面后不会展示迟到结果。

同一官方账户同时只有一个活动监听；其他用户会收到占用提示。监听最长 180 秒，匹配后的结果最多展示 60 秒，到期清除验证码字段。每个系统用户每分钟最多创建 6 次任务。网络异常时客户端也会按期限停止轮询、清码，不会无限显示「监听中」。这些是本系统的任务期限，不代表官方验证码的有效期。

## 服务商与授权

采用 **IMAP over TLS**，默认端口 993，验证服务器证书与主机名，不支持明文 IMAP、跳过证书验证或内网/回环邮件服务器。预设提供默认值，并不代表每个真实邮箱都已经联机验收。

| 类别 | 预设 | 需要准备 |
| --- | --- | --- |
| 国内个人邮箱 | QQ/Foxmail、163、126、yeah | 开启 IMAP，通常填写客户端授权码 |
| 国内企业邮箱 | 腾讯企业邮箱、阿里邮箱 | 管理员允许第三方客户端，使用相应客户端密码 |
| Google | Gmail、Google Workspace | 应用密码，或自有 OAuth 应用的 Client ID、Client Secret、Refresh Token |
| Microsoft | Outlook、Hotmail、Microsoft 365 | OAuth2 Client ID、Refresh Token；机密应用另需 Client Secret；IMAP 必须启用 |
| 其他国际邮箱 | iCloud、Yahoo、Zoho、Fastmail、GMX、Yandex | 按服务商说明准备应用密码、启用 IMAP；地区/套餐限制可能不同 |
| 其他 | 自定义 IMAPS | 服务商公开的 IMAPS 域名、端口及有效授权 |

OAuth2 当前是**管理员配置已经取得的授权**，不包含网站内的一键 OAuth 同意/回调流程。Google/Microsoft 使用固定官方令牌端点刷新，返回的新 Refresh Token 会加密保存；同账户刷新串行化，避免并发覆盖。不要把密码、Refresh Token、Client Secret 或真实验证码发到聊天或提交到 Git。

- Google IMAP 授权范围为 `https://mail.google.com/`。这属于较广的邮箱授权，不是“仅验证码”权限；程序本身以只读方式访问。参见 [Google XOAUTH2](https://developers.google.com/workspace/gmail/imap/xoauth2-protocol)。
- Microsoft 使用 `https://outlook.office.com/IMAP.AccessAsUser.All` 和 `offline_access`。参见 [Microsoft IMAP OAuth](https://learn.microsoft.com/en-us/exchange/client-developer/legacy-protocols/how-to-authenticate-an-imap-pop-smtp-application-by-using-oauth)。
- Fastmail 需支持 IMAP 的套餐及应用专用密码，Basic 套餐不支持。参见 [Fastmail 设置](https://www.fastmail.help/hc/en-us/articles/1500000278342-Server-names-and-ports)。[GMX](https://support.gmx.com/pop-imap/imap/server.html)、[Yandex](https://www.yandex.com/support/yandex-360/customers/mail/en/mail-clients/others) 的地区设置以邮箱后台为准。
- 不支持仅网页访问、仅 POP3 或必须依赖本机 Bridge 的邮箱接入；例如 Proton 的 Bridge 方案不是本轮的公网 IMAPS 接入。参见 [Proton 说明](https://proton.me/support/imap-smtp-and-pop3-setup)。

建议使用专门收取官方登录邮件的邮箱，不要把包含私人邮件的主邮箱作为共享验证码入口。

## 配置服务端加密密钥

中央 Java 服务需要独立的环境变量 `MAIL_ASSISTANCE_ENCRYPTION_KEY`：**32 个安全随机字节的 Base64 编码**。它不是邮箱密码，不与 JWT 密钥复用；必须长期保存并安全备份。丢失或随意更换会导致已保存邮箱授权无法解密，需要重新录入。

连接同一数据库的多个 Java 实例必须使用同一把邮箱密钥。不要在每次启动或每个实例上各自重新生成；Windows DPAPI 文件也不能直接作为跨机器密钥分发方式。

未配置密钥时只禁用邮箱功能，不影响用户、团队、统计和额度接口。

使用本项目 Windows DPAPI 启动配置时，运行一次：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\apps\admin\backend\scripts\initialize-mail-key.ps1
```

如使用其他配置路径，传 `-ConfigPath '实际的受保护配置.clixml'`。脚本只向已有配置添加缺失的邮箱密钥，先留下原文件的加密备份，不修改数据库密码/JWT 密钥，不输出密钥；重复执行保留原密钥。然后重新启动中央 Java 服务。Linux/容器部署使用自己的机密管理或受保护环境注入，不使用 Windows 脚本。`.env.example` 仅是模板，Spring Boot 不会自动读取普通 `.env` 文件。

正式部署应同时为管理后台和客户端到中央服务的连接配置 HTTPS。邮件服务器链路本身始终使用 TLS；既有局域网 HTTP 开发选项并不能保护管理页提交的邮箱授权信息。

## 代理与 Fake-IP 解析

后台保留代理/TUN 时，如果系统把邮箱域名解析为已知的 Fake-IP 范围 `198.18.0.0/15` 或 `fdfe:dcba:9876::/64`（包含两者同时返回），或者系统无法解析该域名，会通过固定的 Cloudflare / Google HTTPS DNS 接口尝试取得真实公网地址。普通公网 DNS 结果不走备用通道。系统结果若混有这些范围之外的回环、私网或链路本地地址则仍直接拒绝，不能借备用解析绕过目标限制；备用答案也绝不允许上述 Fake-IP 或私网地址。

备用查询只发送邮箱服务器域名与 A/AAAA 类型，不发送邮箱账号、授权码或邮件内容；不跟随 HTTP 重定向，验证 HTTPS 证书，限制响应大小为 16 KiB，并设置总时限。查询结果逐一检查，连接固定到校验过的公网 IP；IMAPS 仍按原邮箱域名校验 TLS 证书，不直接信任 Fake-IP，也不修改操作系统或代理软件的 DNS 设置。

适用于当前 TUN/Fake-IP 场景，不等于自动支持所有 HTTP/SOCKS 代理配置。备用 DNS 的 HTTPS 地址也必须可访问；完全断网或代理不可用时会失败，不降级为明文。邮箱主机继续填写 `imap.163.com` 等域名，无需手填或固定服务商 IP。

本次只更新中央 Java 后端，不需要重新安装桌面客户端。可选的 `CQG_MAIL_PROXY_PROBE=1` 测试仅连接公开的 163 IMAPS 服务、校验 TLS 并读取连接欢迎信息，不发送登录指令、不读取业务邮件；正常测试默认不访问公网。

协议依据：[Cloudflare DoH JSON](https://github.com/cloudflare/cloudflare-docs/blob/production/src/content/docs/1.1.1.1/encryption/dns-over-https/make-api-requests/dns-json.mdx)、[Mihomo Fake-IP 配置](https://wiki.metacubex.one/en/config/dns/)。若代理自定义了其他 Fake-IP 范围，不会自动把任意私网地址当作代理地址放行。

代理兼容改动已通过 170 项常规后端测试（1 项公网测试默认跳过）；公网测试另外显式运行，在系统仍返回 IPv4/IPv6 Fake-IP 时，通过实际 Angus IMAPS 连接完成证书验证并读到欢迎响应。此验证不代表邮箱账号/授权码已验证成功，完整登录需管理员在页面执行“测试连接”。

## 配置与连接诊断

- 「邮箱登录用户名」填写邮箱服务商的登录名，163 应使用完整邮箱地址，不是 Quota Guard 系统账号。
- 「邮件文件夹」默认 `INBOX`，表示服务端收件箱，不是本机目录。「发件地址 1 / 2」用于匹配登录邮件的实际 From 地址，通常不是自己的收件地址；请以邮件详情为准。每个输入框只填一个完整邮箱，不支持显示名称、域名或通配符。
- 「测试已保存配置」不会使用表单中尚未保存的修改。更换授权码或其他字段后先点「保存」，再测试；留空授权字段会保留原有授权。
- 保存或重新打开弹窗后，授权输入框会刻意清空、不回显；服务端只返回 `hasPassword` 状态，空框不表示已删除授权。可通过已授权的只读诊断核对密文是否存在及是否与指定授权码一致，诊断不输出原文或指纹。
- 测试失败返回固定安全分类：`MAIL_DNS_FAILED`、`MAIL_HOST_BLOCKED`、`MAIL_AUTH_FAILED`、`MAIL_TLS_FAILED`、`MAIL_CONNECTION_TIMEOUT`、`MAIL_FOLDER_FAILED`、`MAIL_CURSOR_UNAVAILABLE`。认证失败可能来自授权码、IMAP 服务开关或服务商安全策略，不等于能断言密码必错。日志只记录固定提示，不记录原始服务商异常、邮箱地址或凭据。
- 根据[网易官方 IMAP ID 说明](https://help.mail.163.com/faqDetail.do?code=d7a5dc8471cd0c0e8b4b8f4f8e49998b374173cfe9171305fa1ce630d7f67ac2eda07326646e6eb0)，对 `imap.163.com`、`imap.126.com`、`imap.yeah.net` 在认证成功且支持 ID 时、打开收件箱前发送固定产品名。不会把用户邮箱或授权码作为身份描述字段；该声明针对当前连接，后续 STATUS 池连接的服务商行为仍需真实监听验收。

`MailSavedConfigurationProbeTest` 默认禁用。仅在取得对应邮箱所有者的明确许可后，才能临时注入已有受保护配置、目标账户名称，并设置 `CQG_SAVED_MAILBOX_PROBE=APPROVED_READ_ONLY` 执行。它只读本地数据库中的单份指定 163/993/INBOX 配置，尝试一次只读连接和游标验证，不读取邮件正文、不发送邮件、不修改配置；只输出固定结果码。可选 `CQG_MAIL_PROBE_EXPECTED_SHA256` 只校验指定授权码指纹，不一致则在连接前停止；`CQG_MAIL_PROBE_MODE=CURSOR_METADATA` 只检查文件夹的定位元数据。不要在普通构建或 CI 长期设置这些诊断环境变量。

`MailProvidedEmlProbeTest` 用于所有者提供的本地 `.eml`，默认禁用。显式设置 `CQG_LOCAL_EML_PROBE=APPROVED_OFFLINE_FILE`、`CQG_LOCAL_EML_PATH` 和 `CQG_LOCAL_EML_EXPECTED_SENDER` 后，只离线验证解码、发件地址比较与模板匹配，只输出布尔结果，不打印或保存正文、主题、收件地址及验证码，也不连接邮箱或数据库。本地文件缺少可靠的 IMAP INTERNALDATE，因此诊断模拟接收时间，仅能证明内容识别，不能代替真实监听的 UID/时间边界验收。原始邮件不得提交到仓库。

## 新邮件与隔离规则

- 建立连接后优先保存服务端 `UIDVALIDITY`、`UIDNEXT` 与监听就绪时间，只处理新 UID；历史未读邮件也不会被当作新验证码。接收时间的辅助下界使用本次监听的请求时间，而不是稍后发布的就绪时间，避免基线建立后、就绪状态保存前到达的新 UID 邮件被误丢；请求之前的旧接收时间仍被拒绝。
- 网易三个已支持主机可能不提供 `UIDNEXT`。仅在 UID 稳定、UIDVALIDITY 有效且缺失值为 `-1` 时，使用独立高水位模式：显式 NOOP 同步后，只读取最后一封现存邮件的 UID（不读取头或正文、不遍历历史），把最大已见 UID 加一作为后续查询下界。这个下界不是伪造的服务端 UIDNEXT；空箱使用下界 1，已分配但删除的 UID 缺口不会造成旧邮件被读取。
- 高水位路径始终使用同一条已选中连接，固定协议实例，不借用新状态连接或透明重连。每轮通过 NOOP 与尾 UID 检测增长，查询下界不后退；依赖 IMAP 同一 selected session 内 UID 稳定的规范保证，缓存的 UIDVALIDITY 不等于每轮远端复查。断线、协议切换、尾邮件已删除/UID 未知、UID 不稳定、编号耗尽或批量异常均停止监听，不通过遍历历史或 expunge 修改邮箱来恢复。
- UID 不要求连续；游标发生变化、邮件批量异常或无法确认基线时终止任务，不退回搜索历史邮箱。
- 识别采用后端本地规则，不调用语言模型，也不再要求整段英文逐字相同：先将唯一的实际 `From` 与管理员明确配置的最多两个地址逐项精确比较，并核对收件邮箱，再从主题或正文确认 `ChatGPT` 与登录用途，最后从紧邻验证码标签或明确输入提示的位置提取六位数字。支持英文 `login / log-in / sign-in`、简体登录与繁体登入/登錄，以及验证码、代码/代碼、临时/一次性等常见表达。主题已明确为登录验证码且正文只有六位码时也可识别。不会把任意 `openai.com` 子域或未配置地址自动加入允许列表。
- 对大小写、换行空白、常见 Unicode 连字符及全角标点/数字做正常化；MIME 解码、HTML 取文字和多表示择一由正文读取层负责。同一个验证码在主题/正文重复出现可接受，出现多个不同六位候选则拒绝；不从 URL、较长数字或字母数字标识中截取验证码。
- 邮件中的“不是本人操作请重设密码”安全提醒不等于密码重置邮件，不能简单凭这句话拒绝正常登录模板；重置密码/恢复账户的主题或主要用途、无明确登录用途的通知/账单、未知格式、歧义号码和超大邮件不返回验证码。规则覆盖已测试的常见中英文布局，不保证未来所有官方文案都无需更新。回归样例全部使用合成邮箱和号码，不提交真实邮件或验证码。
- 中文短模板的“输入此临时验证码以继续”可在主题已经明确登录用途时识别；“如果你无意登录 ChatGPT，请重置密码”属于条件式安全提醒。单独的“以继续”不构成登录用途，真正密码重置主题仍拒绝。
- 英文临时代码邮件支持 `to continue` 提示与 `If you were not trying to log in` 条件式安全提醒；HTML 预览文字与正文重复同一码不会导致误拒，出现不同码仍拒绝。
- 不下载远程图片、不执行 HTML、不解析附件；对 MIME 深度、大小、批次和网络响应设上限。连接、OAuth 请求和任务都有超时；取消会关闭底层套接字。
- 邮箱授权使用 AES-GCM 加密，关联账户与字段；验证码密文关联任务、用户和账户，过期不再发放并清空字段。任务记录不保存原始邮件正文，历史任务定期清理。数据库备份/WAL 的保留期不等于接口中的结果有效期。
- 查询、取消和取码都校验任务所有者以及当前团队/官方账户配置；更换邮箱授权目标时必须重新填写凭据，不能把旧密码复用到新服务器。
- 同一实际邮箱或收件地址不能重复绑定多个官方账户，防止绕过监听占用或重复发码。

连接测试成功但没有验证码时，先确认已显示「正在监听」后才重新请求发码、实际邮件在配置的文件夹中，以及对应实际发件人已填入「发件地址 1 / 2」，而不是误填自己的收件地址。连接成功仅证明 IMAPS 可用，不代表发件规则或每一种邮件模板均匹配。只给客户端返回验证码及任务状态，不返回邮件正文、主题或收发件人列表。

**边界**：普通邮件通常没有能与某台设备登录请求对应的可靠标识。单账户排他监听只能降低错配，不能区分用户在本系统外触发的同类邮件，也不提供官方请求级绑定。发件地址/模板匹配不等于密码学签名认证。获得验证码仍可能获得官方账户权限，不能承诺“只用 Codex、不能改安全设置”，也不能让本系统配额变为官方强制限额。

## 接口与升级

管理端接口：`GET /api/v1/admin/mail-assistance/providers`；`GET/PUT/DELETE /api/v1/admin/official-accounts/{id}/mailbox`；`POST /api/v1/admin/official-accounts/{id}/mailbox/test`。

客户端接口：`GET /api/v1/client/login-assistance`；`POST /api/v1/client/login-assistance/listeners`；`GET/DELETE /api/v1/client/login-assistance/listeners/{id}`。接口不接受调用者自选邮箱地址或其他用户 ID；邮箱配置响应只给 `hasPassword` 等标志，不返回授权值。

双发件地址沿用管理端的 `senderEmail` 字符串字段与现有数据库列，保存后为小写、去重、英文逗号分隔的最多两个完整邮箱，原单地址配置继续有效。接口也接受逗号、分号或换行分隔；原始与规范化后的总长均不超过 320 字符。此改动无需新增数据库迁移或更改桌面取码接口。

Flyway V21 只新增邮箱配置和监听任务表，不改现有用户/额度数据。回退时停用功能并退回应用代码，保留新增表和加密密钥；若要删除表，另写前向迁移，不修改已应用的 V21。现有正式安装包不会因源码修改自动增加菜单，需要重新构建/升级桌面客户端和中央后端。

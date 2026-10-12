# 邮箱登录辅助

本功能代收管理员自有邮箱中的指定登录邮件。用户仍在官方页面完成登录；不自动填写官方密码、不导入官方会话凭据，也不把邮箱连接凭据发送给客户端。Rust 统计服务不参与收邮件。

## 使用流程

1. 管理员在「官方账户」中打开对应账户的「邮箱配置」，选择服务商，填写连接信息、收件邮箱和真实发件邮箱。
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

## 新邮件与隔离规则

- 建立连接后保存 `UIDVALIDITY`、`UIDNEXT` 与监听就绪时间，只处理新 UID；历史未读邮件也不会被当作新验证码。
- UID 不要求连续；游标发生变化、邮件批量异常或无法确认基线时终止任务，不退回搜索历史邮箱。
- 匹配真实 `From` 地址、收件邮箱、正文中的 `ChatGPT Log-in Code` 标题和已确认的登录提示，提取紧接提示的唯一六位数字。截图中的具体数字不会进入代码。
- 邮件中的“不是本人操作请重设密码”安全提醒不等于密码重置邮件，不能简单凭这句话拒绝正常模板；独立密码重置/恢复邮件、未知模板、多码和超大邮件不返回验证码。
- 不下载远程图片、不执行 HTML、不解析附件；对 MIME 深度、大小、批次和网络响应设上限。连接、OAuth 请求和任务都有超时；取消会关闭底层套接字。
- 邮箱授权使用 AES-GCM 加密，关联账户与字段；验证码密文关联任务、用户和账户，过期不再发放并清空字段。任务记录不保存原始邮件正文，历史任务定期清理。数据库备份/WAL 的保留期不等于接口中的结果有效期。
- 查询、取消和取码都校验任务所有者以及当前团队/官方账户配置；更换邮箱授权目标时必须重新填写凭据，不能把旧密码复用到新服务器。
- 同一实际邮箱或收件地址不能重复绑定多个官方账户，防止绕过监听占用或重复发码。

**边界**：普通邮件通常没有能与某台设备登录请求对应的可靠标识。单账户排他监听只能降低错配，不能区分用户在本系统外触发的同类邮件，也不提供官方请求级绑定。发件地址/模板匹配不等于密码学签名认证。获得验证码仍可能获得官方账户权限，不能承诺“只用 Codex、不能改安全设置”，也不能让本系统配额变为官方强制限额。

## 接口与升级

管理端接口：`GET /api/v1/admin/mail-assistance/providers`；`GET/PUT/DELETE /api/v1/admin/official-accounts/{id}/mailbox`；`POST /api/v1/admin/official-accounts/{id}/mailbox/test`。

客户端接口：`GET /api/v1/client/login-assistance`；`POST /api/v1/client/login-assistance/listeners`；`GET/DELETE /api/v1/client/login-assistance/listeners/{id}`。接口不接受调用者自选邮箱地址或其他用户 ID；邮箱配置响应只给 `hasPassword` 等标志，不返回授权值。

Flyway V21 只新增邮箱配置和监听任务表，不改现有用户/额度数据。回退时停用功能并退回应用代码，保留新增表和加密密钥；若要删除表，另写前向迁移，不修改已应用的 V21。现有正式安装包不会因源码修改自动增加菜单，需要重新构建/升级桌面客户端和中央后端。

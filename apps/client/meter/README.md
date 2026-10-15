# Rust Meter 0.2.3

Meter 是 Windows SCM 管理的独立进程。首次登录仅用于登记设备和配对；随后采集、设备认证、续期和补传均不需要桌面前端、客户端 Java 或软件 refresh token 存活。安装脚本配置自动启动和崩溃恢复；本轮仅只读检查真实服务元数据，未停止、重启、安装服务或发送真实用量。

## 设备授权与归属

- `POST /api/v1/client/meter/enroll` 返回 `deviceId/deviceToken/expiresAt/userId`。本地助手核对当前软件用户后写入配对 inbox。
- Meter 使用独立设备密钥；新配对密钥首次上传前通过 `POST /api/v1/meter/credential/renew` 核实服务端 `deviceId/userId/expiresAt`，再在私有状态目录记录已验证归属。
- 在线 heartbeat 将授权期限滚动延长 30 天。到期后 30 天内可仅用设备密钥恢复；超过此宽限需要用户重新登录配对。明确撤销的设备、停用的用户不能续期。
- 续期保留原设备密钥，避免响应丢失使后台进程失去授权。该密钥不具有普通用户或管理 API 权限。用量接口只返回 `acceptedEvents` 确认，不返回配额详情。

## 持久补传

`usage-outbox.json` 在任何网络请求前原子保存新事件及扫描位置，绑定设备、服务器 origin、本地 Codex 目录及已认证软件用户。源日志随后删除或服务重开不会丢失已捕获的待传事件。服务器按用户和稳定 event ID 去重；确认后的事件才从本地待传列表移除。

未知旧归属、换用户时尚未扫描的过渡事件、服务器拒绝的事件保存在同一 journal 的 `quarantined` 中。它们不归入新用户；之后有效的新事件仍继续上报。过旧或未来时间的记录保留待核对，不修改发生时间。公开状态和活动日志只给安全错误码与 `pendingEvents` 数量，不包含设备密钥或原始记录。

journal 上限为 64 MiB。损坏、写入失败、身份不匹配或达到上限会明确报错并保留已有文件，不自动删除记录。隔离记录需要人工核对；当前没有自动历史补录或跨用户迁移功能。

## 可观测范围与验证

当前采集官方本地 `sessions/**/*.jsonl` 中完整的 `token_usage_record`，保留可用的 input、cached input、cache write、output、reasoning output、model 和 reasoning effort 分项。无法观测官方没有写入这些记录的请求；不能承诺追踪所有实际网络请求。初次配对前的历史活动不自动计费。

测试使用合成文件、本地 HTTP mock 和注入的无操作 guard。独立子进程测试覆盖没有桌面 Java/前端进程参与时的离线采集、源日志删除、重开补传；其他测试覆盖稳定 ID 重试、隔离后继续上报、用户切换及错误持久化。真实桌面完全退出的 live 验证需要另行操作，未在本轮偷偷执行。

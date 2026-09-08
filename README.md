# Codex Quota Guard

用于协作团队的 Codex 额度监控桌面工具。

当前产品实现迁移到以下结构：

- `backend/`：Java 17 + Spring Boot 本地后端；只监听回环地址，并由 Electron 主进程持有启动令牌。
- `desktop/`：Electron + React 桌面界面；渲染进程不直接访问文件、令牌或 Codex。
- `internal/` 与 `go.mod`：早期 Go 原型，暂时保留为行为与测试参考，后续在 Java 版本覆盖功能后再移除。

安全原则：共享空间只保存签名的成员资料、策略与额度快照；不保存或传输 Codex 登录凭据。

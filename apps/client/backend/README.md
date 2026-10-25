# 已退役的桌面 Java 后端

当前桌面源码不再编译、启动或打包本目录。根 Maven 聚合构建只包含中央后端 `apps/admin/backend`。

桌面界面通过 Axios 直接调用中央 API；本机设备身份、安全会话存储、官方 Codex CLI 本地 RPC 等系统操作由 Tauri 提供。计量由独立 Rust Windows 服务负责，安装程序强制安装并配置 Windows 自动启动和故障恢复。

本目录暂留用于核对旧版本的响应格式、累计日志兼容规则与迁移历史，不是一个需要部署或运行的组件。运行方式见根 README 和 [当前架构](../../../docs/architecture/current-system-architecture.md)。

# Tauri + React 重构方案

## 目标

将现有 Electron + 原生 DOM 桌面端替换为与 CC-Switch 一致的跨平台 UI 技术栈，同时保留 Java 17 + Spring Boot 作为唯一业务后端。

## 技术边界

- UI：React 18、TypeScript、Vite、Tailwind CSS、shadcn/ui（Radix UI）
- 前端能力：TanStack Query、react-i18next、react-hook-form + Zod、Framer Motion、Recharts、Sonner、Lucide
- 桌面壳：Tauri 2 + Rust，仅负责窗口、安全边界、Java 进程生命周期和本地 API 代理
- 后端：Java 17 + Spring Boot，负责 Codex app-server、账号、额度、房间、签名和持久化业务
- 平台目标：Windows、macOS、Linux

## 安全与通信

1. Tauri 启动时生成一次性随机 bearer token，并通过 `QUOTA_GUARD_API_TOKEN` 环境变量传给 Java。
2. Java 只监听 `127.0.0.1`，端口由 Tauri 指定或通过安全的本地默认值选择。
3. React 只能调用显式白名单中的 Tauri commands，不能读取 bearer token，也不能直接访问任意 URL。
4. Tauri 只允许代理固定路径：
   - `GET /api/v1/status`
   - `GET /api/v1/room/overview`
   - `GET /api/v1/account/overview`
   - `POST /api/v1/account/login/device`
   - `POST /api/v1/account/logout`
   - `POST /api/v1/system/shutdown`（仅由 Tauri 在退出时调用）
5. 窗口关闭时终止由当前实例启动的 Java 子进程。

## Tauri command 契约

- `status() -> StatusResponse`
- `room_overview() -> RoomOverview`
- `account_overview() -> AccountOverview`
- `start_device_login() -> DeviceLogin`
- `logout() -> void`

命令返回可序列化 JSON；错误转换为不含 token、邮箱或本地敏感路径的用户可读消息。

## 交付阶段

1. 建立 Vite/React/Tailwind/shadcn 脚手架和共享类型。
2. 修复并补齐 Spring Boot 账号服务/API，统一环境变量配置。
3. 建立 Tauri 2 壳、Java sidecar 生命周期和固定 API 代理。
4. 重建额度仪表盘，保留当前房间、成员、配额、警告和刷新能力。
5. 分层测试：Java 单元/控制器测试、React 组件测试、Rust 单元测试、桌面烟雾测试。
6. 通过后删除旧 Electron 运行入口；旧代码保留到新链路验证完成后再移除。

## 验收标准

- `services/desktop-backend` 全部测试通过，Spring Boot 仅绑定回环地址且无 token 时拒绝启动。
- React 构建、类型检查和测试通过；加载、空、错误、成功四种状态完整。
- Tauri Rust 测试和打包检查通过；React 无法获得 Java bearer token。
- Windows 上可启动/退出，Java 进程随应用生命周期关闭。
- Tauri 配置具备 Windows、macOS、Linux 打包目标，平台差异集中在壳层。

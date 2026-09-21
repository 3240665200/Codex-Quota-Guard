# Codex Quota Guard

用于协作团队的 Codex 额度监控桌面工具，支持 Windows、macOS 和 Linux。

## 技术栈

- 桌面壳：Tauri 2 + Rust
- 界面：React 18 + TypeScript + Vite
- UI：Tailwind CSS + shadcn/ui（Radix UI）+ Lucide
- 前端状态：TanStack Query + Zod + react-i18next + Framer Motion + Sonner
- 后端：Java 17 + Spring Boot 4（桌面本地服务 + Web 用户服务）

Rust 只负责桌面窗口、安全边界、桌面本地 Java 进程生命周期和固定 API 代理。
桌面本地后端负责 Codex 官方账号、额度、共享房间和本机 Codex app-server 通信；Web 后端负责 Web 用户注册登录、用户资料、角色和在线服务数据。两类账号相互独立：Web 用户账号不等于 Codex 官方账号，Web 后端不保存 Codex 登录凭据。

## 安全模型

- 桌面本地 Java 服务只监听 `127.0.0.1`；独立 Web 后端部署时必须由 HTTPS 或可信反向代理保护。
- Tauri 每次启动生成临时 bearer token，只通过 `QUOTA_GUARD_API_TOKEN` 环境变量交给 Java。
- React 无法读取 token，也不能代理任意本地 URL。
- 设备登录地址只能由系统浏览器打开 `https://auth.openai.com/**`。
- 关闭桌面主窗口时，由该实例启动的 Java 子进程会同步退出。
- 共享空间只保存签名的成员资料、策略与额度快照，不保存或传输 Codex 登录凭据。

## 项目结构

- `src/`：React/shadcn 桌面界面与组件测试
- `src-tauri/`：Tauri 2 壳、Java 启动器、固定 API 命令与 Rust 测试
- `services/desktop-backend/`：桌面端随附的 Java 17 + Spring Boot 本地后端与测试
- `services/web-backend/`：可独立部署的 Java 17 + Spring Boot Web 用户后端与测试
- `pom.xml`：Maven aggregator，统一管理两个 Java 服务模块（两个子模块仍使用各自的 Spring Boot parent）
- `scripts/prepare-backend.mjs`：构建 Java JAR 并复制到 Tauri 资源目录
- `docs/tauri-react-refactor.md`：重构边界、接口契约和验收标准

## 开发

环境要求：Java 17、Maven 3.9+、Node.js 20+、pnpm 10.12.3、Rust 1.85+，以及 Tauri 对应平台的系统依赖。

```bash
pnpm install
pnpm dev
```

`pnpm dev` 会先运行 Java 测试并准备后端 JAR，再启动 Vite 和 Tauri。

启动 Web 后端开发服务：

```bash
pnpm dev:web-backend
```

启动前需准备 PostgreSQL，并设置数据库与 JWT 环境变量；完整配置见
`services/web-backend/README.md`。

## 验证与构建

```bash
pnpm typecheck
pnpm test:unit
mvn test
pnpm test:java
mvn -f services/desktop-backend/pom.xml verify
mvn -f services/web-backend/pom.xml verify
cargo test --manifest-path src-tauri/Cargo.toml
pnpm build
```

其中 `test:desktop-backend` 和 `test:web-backend` 分别验证两个服务，`test:java` 串行运行两者；根级 `mvn test` 会通过 aggregator 运行两个模块。

只构建当前平台的桌面可执行文件、不生成安装包：

```bash
pnpm tauri build --no-bundle
```

构建产物位于 `src-tauri/target/release/`。macOS 和 Linux 安装包需要分别在对应系统上构建。

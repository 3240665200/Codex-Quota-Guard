# Codex Quota Guard

用于协作团队的 Codex 额度监控桌面工具，支持 Windows、macOS 和 Linux。

## 技术栈

- 桌面壳：Tauri 2 + Rust
- 界面：React 18 + TypeScript + Vite
- UI：Tailwind CSS + shadcn/ui（Radix UI）+ Lucide
- 前端状态：TanStack Query + Zod + react-i18next + Framer Motion + Sonner
- 后端：Java 17 + Spring Boot 4

Rust 只负责桌面窗口、安全边界、Java 进程生命周期和固定 API 代理；账号、额度、共享房间、签名和持久化业务继续由 Java 后端负责。

## 安全模型

- Java 只监听 `127.0.0.1`。
- Tauri 每次启动生成临时 bearer token，只通过 `QUOTA_GUARD_API_TOKEN` 环境变量交给 Java。
- React 无法读取 token，也不能代理任意本地 URL。
- 设备登录地址只能由系统浏览器打开 `https://auth.openai.com/**`。
- 关闭桌面主窗口时，由该实例启动的 Java 子进程会同步退出。
- 共享空间只保存签名的成员资料、策略与额度快照，不保存或传输 Codex 登录凭据。

## 项目结构

- `src/`：React/shadcn 桌面界面与组件测试
- `src-tauri/`：Tauri 2 壳、Java 启动器、固定 API 命令与 Rust 测试
- `backend/`：Java 17 + Spring Boot 后端与测试
- `scripts/prepare-backend.mjs`：构建 Java JAR 并复制到 Tauri 资源目录
- `docs/tauri-react-refactor.md`：重构边界、接口契约和验收标准

## 开发

环境要求：Java 17、Maven 3.9+、Node.js 20+、pnpm 10.12.3、Rust 1.85+，以及 Tauri 对应平台的系统依赖。

```bash
pnpm install
pnpm dev
```

`pnpm dev` 会先运行 Java 测试并准备后端 JAR，再启动 Vite 和 Tauri。

## 验证与构建

```bash
pnpm typecheck
pnpm test:unit
mvn -f backend/pom.xml verify
cargo test --manifest-path src-tauri/Cargo.toml
pnpm build
```

只构建当前平台的桌面可执行文件、不生成安装包：

```bash
pnpm tauri build --no-bundle
```

构建产物位于 `src-tauri/target/release/`。macOS 和 Linux 安装包需要分别在对应系统上构建。

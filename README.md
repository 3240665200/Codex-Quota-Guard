# Codex Quota Guard

Codex Quota Guard 是一个同时包含跨平台桌面客户端与 Web 管理后台的单仓库项目。

## 技术栈

- 客户端界面：React 18、TypeScript、Vite、Tailwind CSS、shadcn/ui
- 客户端桌面壳：Tauri 2 + Rust
- 客户端本地后端：Java 17 + Spring Boot 4
- 管理端界面：Ant Design Pro 6、React 19、Umi Max 4、Ant Design 6、Pro Components 3
- 管理端后端：Java 17 + Spring Boot 4、Spring Security、PostgreSQL、Flyway

客户端本地后端负责额度、共享房间和本机 `codex app-server` 通信。管理端后端负责
应用用户、管理员权限与联网服务数据，并原生管理 Codex 账号。应用用户与 Codex 账号
相互独立；Codex 凭据由 Java 后端使用 AES-GCM 加密后保存到 PostgreSQL，查询接口
不会返回凭据原文。

## 目录结构

```text
apps/
  admin/
    frontend/   Ant Design Pro 管理界面
    backend/    Java Web 用户与管理 API
  client/
    frontend/   React + Tauri 桌面客户端
    backend/    Java 桌面本地服务
docs/
  architecture/ 架构方案与能力边界
  api/          对外 API 契约
  product/      产品流程与用户旅程
  README.md     文档索引
package.json    前端工作区统一命令
pom.xml         Java 后端 Maven 聚合入口
pnpm-workspace.yaml  pnpm 工作区声明
```

每个应用的前端与后端都放在同一个应用目录下，同时保持独立构建和部署边界。
仓库根目录只保留工作区入口；依赖目录和构建产物由工具生成，不属于源码层级。

## 环境要求

- Java 17
- Maven 3.9+
- Node.js 22+
- pnpm 10.12.3
- Rust 1.85+ 与 Tauri 对应平台依赖（只开发管理端时不需要 Rust）
- PostgreSQL（运行管理端后端时需要）

安装前端依赖：

```bash
pnpm install
```

## 运行管理后台

先按 `apps/admin/backend/.env.example` 配置 PostgreSQL、JWT 和管理员初始化变量，再启动 Java API：

```bash
pnpm dev:admin-backend
```

另开一个终端启动 Ant Design Pro：

```bash
pnpm dev:admin
```

默认管理页面为 `http://localhost:8000`，开发环境会把 `/api` 代理到
`http://127.0.0.1:8080`。

## 运行桌面客户端

```bash
pnpm dev:client
```

该命令会构建 `apps/client/backend` 的 Java JAR，把它复制到 Tauri 资源目录，
然后启动 Vite 与 Tauri。Java 服务只监听回环地址，并由 Tauri 管理生命周期。

## 验证与构建

```bash
mvn verify
pnpm test:client
pnpm test:admin
pnpm build:client
pnpm build:admin
cargo test --manifest-path apps/client/frontend/src-tauri/Cargo.toml
```

也可以执行聚合验证：

```bash
pnpm verify
```

桌面安装包需要分别在 Windows、macOS、Linux 上构建。管理端前端的生产构建产物
位于 `apps/admin/frontend/dist`，应与启用 HTTPS 的 Java API 或反向代理一起部署。

## 安全边界

- 管理 API 的管理员权限由 Java 后端强制校验，前端菜单隐藏不是权限控制。
- 密码只保存 BCrypt 哈希；refresh token 在数据库中只保存 SHA-256 摘要。
- 强制下线通过会话版本立即使该用户的 access token 与 refresh token 全部失效。
- Codex 凭据加密密钥只注入 Java 后端，不会发送到浏览器或写入仓库。
- 生产环境必须使用 HTTPS，并从环境变量或密钥管理服务注入数据库和 JWT 密钥。
- 桌面端临时 bearer token、Web access token、refresh token 和数据库密码均不得提交到 Git。

管理端前端基于 [Ant Design Pro](https://github.com/ant-design/ant-design-pro) 官方模板定制。

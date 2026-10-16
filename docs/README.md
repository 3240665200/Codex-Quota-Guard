# 项目文档

文档按用途分层维护，避免架构说明、接口契约和产品需求混在同一目录。

当前服务器发布目标：[服务器 0.3.23 发布说明与升级注意事项](releases/server-v0.3.23.md)。桌面客户端保持 [0.3.22](releases/v0.3.22.md)，统计程序保持 `0.2.4`。

使用与本地验证：[Windows 本地管理网页测试](local-admin-test.md)、[订阅与前置／后置代理链](proxy-subscriptions-and-chains.md)。

## 服务器部署

- [Docker Hub 拉取部署（推荐，无需源码）](../deploy/docker/registry/README.md)
- [官方账户授权、私有预置与会话管理](official-account-management.md)
- [离线镜像部署](../deploy/docker/offline/README.md)
- [Linux Docker 源码构建部署](../deploy/docker/README.md)

## 架构

- [当前系统架构与部署边界](architecture/current-system-architecture.md)
- [当前方案：本机官方登录与个人 Token 统计](architecture/local-codex-token-quota.md)
- [团队与个人额度策略](architecture/team-quota-policy.md)
- [Token 分项统计与 USD 费用估算](architecture/usage-pricing.md)

历史方案仅供追溯，不代表当前接口：

- [Tauri + React 重构方案（历史）](architecture/tauri-react-refactor.md)
- [Web 后端能力契约（历史）](architecture/web-backend-capability.md)

## API

- [Web 后端 REST API 契约](api/web-backend-api.md)

## 产品

- [用户旅程](product/user-journeys.md)

应用自己的运行说明放在对应应用目录内：

- 管理端后端：`apps/admin/backend/README.md`
- 桌面客户端：根目录 `README.md` 的运行章节

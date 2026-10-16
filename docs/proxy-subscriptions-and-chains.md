# 订阅与前置／后置代理链

这是当前源码的新功能；已发布的 `0.3.22` 镜像尚不包含它。更新发布前，不要认为服务器仅刷新页面就能使用。

## 使用方式

1. 在“代理节点”点击“管理订阅 → 导入订阅”，输入名称和订阅链接。也可继续手动添加 HTTP、HTTPS、SOCKS5 节点。
2. 订阅首次添加时下载并导入节点；之后仅在点击“更新节点”时手动下载，不创建定时任务。
3. 在官方账户的授权页面选择必填的“后置／出口节点”，按需选择“前置节点”。两个位置都可以使用订阅节点，也可以使用手动节点。
4. 点击“测试完整链路”，检查返回的最终出口 IP 和地区。这个结果仅属于当次所选组合，不会覆盖节点列表里的单节点测试结果。
5. 已保存授权的账户可以单独“保存代理链”，不用重新填写 Authorization 或 Cookie；然后重新刷新官方账户与会话。新账户仍须明确保存或导入自己的授权。

```text
单节点：服务器 → 出口节点 → 官网
双节点：服务器 → 前置节点 → 后置／出口节点 → 官网
```

例如静态 ISP 节点需要前置代理时，可以选择订阅中的可用节点作为前置，手工添加的静态 ISP SOCKS5 作为出口。前置必须能从服务器连接，后置必须能从前置连接；本机开了 TUN 后可用不代表服务器可直连。

## 规则与更新语义

- 每个官方账户独立保存组合，出口必选；前置留空表示单节点，不是直接访问官网。
- 不能将同一个节点同时选为前置和出口。任一所选节点缺失、停用或连接失败，整条链停止，不省略前置、不改用其他节点、不回退直连。
- 官方账户读取、刷新、会话登出与绑定所需的账户刷新均使用该账户保存的链。
- 单独保存链保留现有加密授权和已验证身份，但使旧会话快照失效，防止把旧出口取得的数据当作新链验证结果。
- 同一订阅内按唯一节点名称匹配，手动更新保留原节点 ID，因此已有账户引用不会因刷新而换成另一个随机节点。
- 新节点加入列表；从订阅中消失的节点标为“来源缺失”并停用，不删除。节点重现后也不会自动启用，需要管理员确认。
- 管理员主动停用的节点不会因订阅刷新而重新启用。
- 修改订阅名称或 URL 不会立即下载；URL 留空保持原链接。下载、解析或版本检查失败，不覆盖已有节点列表。
- 删除订阅只解除来源关系，保留节点、加密配置和账户引用。需要删除节点时另行操作；被官方账户引用的节点受外键保护。
- 订阅维护的节点使用独立启停操作，不通过简单 HTTP/SOCKS 表单改写 VMess/VLESS 等协议配置。

## 支持范围

订阅支持 URI 逐行列表、其 Base64 编码以及 Clash/Mihomo YAML 的顶层 `proxies` 列表。支持 HTTP、HTTPS、SOCKS5、Shadowsocks、VMess、VLESS、Trojan 的已实现协议变体，包括已有 TLS、WebSocket、gRPC、Reality 参数映射。

这是节点数据导入，不是执行完整 Clash/V2Ray 配置：不会应用订阅里的规则、DNS、TUN、监听端口、外部控制器或嵌套 provider；拒绝脚本、YAML 显式类型与别名。不支持的协议或参数会明确报错，整个更新失败，不偷偷删除字段或切换为直连。并非所有 Clash 插件、传输方式和协议均受支持。

当前订阅限制：公网 HTTPS 地址、443 端口、无 URL 用户信息、无跳转；最多 1 MiB、128 个节点、节点名不可重复。订阅下载由服务器直接进行，与官方账户的代理链分开；若订阅地址本身在服务器不可达，本次不会自动借用本机 VPN 或账户链获取。

本机使用 TUN 且域名解析返回默认 Fake-IP（IPv4 `198.18.0.0/15` 或 IPv6 `fdfe:dcba:9876::/64`）时，会复用已有受限公共 DNS 查询取得真实地址，再逐一验证公网地址并固定连接 IP；TLS 仍验证原域名。补查包含在原有五秒 DNS 截止内。直接填写 Fake-IP、混入普通私网地址或公共 DNS 返回非公网结果，仍会拒绝；不会通过开启私网节点例外来解决订阅下载问题。

完整订阅 URL 可能包含订阅令牌：服务端加密保存，列表只显示来源主机和已保存状态，不回显 URL 或节点密码。前端不将它们写入浏览器持久存储。HTTP 管理入口仍可用，但明文传输有风险。

订阅请求使用固定的 `User-Agent: Codex-Quota-Guard` 供订阅服务识别客户端，不冒充 Clash，也不携带官方账户 Authorization 或 Cookie。

## API

### Trojan URI 订阅兼容

对于 URI 列表或 Base64 包装的 Trojan 订阅，支持 `peer` 作为 `sni` 别名；同时出现时必须一致，冲突会明确报错。`allowInsecure=0` 或 `false` 保持证书校验。

如果订阅的 Trojan 链接声明 `allowInsecure=1` 或 `true`，需在该订阅的导入／编辑窗口主动勾选“允许此订阅的 Trojan 节点跳过证书验证”。新建默认关闭，设置随订阅保存，之后手动更新沿用该设置。只有当前订阅中明确声明此参数的 Trojan URI 节点会跳过节点证书校验；其它订阅、其它协议、Clash YAML、订阅下载 HTTPS 和官方站点 HTTPS 校验不受影响。

这个例外会接受节点提供的任意证书，无法据此验证代理节点身份。关闭许可或移除订阅来源时，会立即在保存的配置中恢复这些节点的证书校验、停用节点并清除旧出口测试结果，保留节点 ID 和账户引用；之后手动启用不会恢复旧例外。修改订阅地址或开启许可仍需点击“更新节点”获取新配置。

该适配按格式识别，不针对某个供应商 IP、路径或订阅令牌硬编码。未获许可时返回 `PROXY_TROJAN_TLS_CONFIRMATION_REQUIRED`，`peer`/`sni` 冲突返回 `PROXY_TROJAN_SNI_CONFLICT`，错误不包含原始节点链接、域名或凭据。

### 管理接口

下列路径均在 `/api/v1/admin` 下，仅管理员可用，敏感响应为 `Cache-Control: no-store`。

| 方法 | 路径 | 内容 |
| --- | --- | --- |
| GET | `/proxy-subscriptions` | 订阅摘要数组，不返回 URL |
| POST | `/proxy-subscriptions` | `{label,url,allowInsecureTrojan?}`，首次下载并导入，许可默认 false |
| PUT | `/proxy-subscriptions/{id}` | `{label,url?,version,allowInsecureTrojan?}`，省略许可则保持原值；关闭许可会立即撤销旧节点例外 |
| POST | `/proxy-subscriptions/{id}/refresh` | `{version}`，手动更新 |
| DELETE | `/proxy-subscriptions/{id}?version=n` | 保留节点并解除订阅来源 |
| PUT | `/proxy-nodes/{id}/enabled` | `{enabled,version}`，独立启停节点 |
| POST | `/proxy-chains/test` | `{frontProxyNodeId?,proxyNodeId}`，测试组合 |
| PUT | `/official-accounts/{id}/connection/proxy` | `{frontProxyNodeId?,proxyNodeId,version}`，只修改账户链 |

原账户授权／预置请求新增可空 `frontProxyNodeId`，现有 `proxyNodeId` 仍为出口。旧单节点请求兼容；旧无状态 `/proxy-nodes/import` 仍为退役接口，新订阅使用上表接口。

迁移 V32 增加账户可空前置节点引用，V33 增加加密订阅及节点来源关系，不重新初始化数据库。升级前保留数据库、原 `.env` 和全部密钥。旧应用不理解前置链，不应将切回旧应用视作网络策略等价的回滚；不要在要求前置的账户上继续运行旧请求进程。

V34 仅为订阅增加默认 false 的 `allow_insecure_trojan` 列，旧订阅不会自动获得许可。订阅摘要返回 `allowInsecureTrojan` 布尔值，不返回 URL。降级到不理解该许可的旧代码前，应先在新版关闭例外并确认已保存的节点恢复校验，不能只切换旧应用后继续使用原配置。

V32 历史脚本内的“部署上一版应用即可回滚”注释不能作为运行依据：保留新增列只能维持表结构兼容，旧代码仍可能忽略前置并直接连接出口。已执行迁移不要改写 SQL、执行 Flyway repair、删列或清空数据库；停止旧实例后按新版本继续修复。确需回到旧应用时，应先停止官方请求并逐账户核对单出口策略；恢复升级前备份则必须同时恢复匹配的数据库、应用和密钥。新增订阅表的降级清理也应另写经过审核的向前迁移。

## 实现参考与验证

采用已有 sing-box 内核，不内置新浏览器，不修改系统代理。后置 outbound 的 `detour` 指向前置，应用请求路由指向后置；两节点地址均按现有地址策略校验和固定，TLS 名称独立保留。

参考：[sing-box Dial Fields](https://sing-box.sagernet.org/configuration/shared/dial/)、[Mihomo dialer-proxy](https://wiki.metacubex.one/config/proxies/dialer-proxy/)、[Mihomo providers](https://wiki.metacubex.one/config/proxy-providers/)、[V2Fly 出站转发](https://www.v2fly.org/config/outbounds.html#proxysettingsobject)。

本地内核验证只使用 loopback 模拟前置、后置及最终目标，检查两个节点均收到连接，并在前置停止后确认不会直连仍然可用的后置。Windows sing-box 1.13.14 和无网络 Linux 后端容器都运行此测试；这不等于真实订阅、真实前置节点或官方账号已经通过公网验收。

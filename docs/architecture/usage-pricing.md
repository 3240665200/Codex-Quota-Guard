# Token 分项统计与 USD 费用估算

## 统计与计价口径

Rust 计费服务和本机 Java 回退扫描器从官方 Codex JSONL 日志提取分项，中央 Java 后端接收并按原有事件 ID 去重。个人、团队的滚动额度和团队累计总额度继续使用 `totalTokens`；金额仅用于展示，不改变额度扣减。

参考 [CC Switch 的计费计算器](https://github.com/farion1231/cc-switch/blob/5667b287c6e1a8cc54e527bafc3c72d59f312b99/src-tauri/src/proxy/usage/calculator.rs) 的分项计价方法。本项目使用本机日志采集、Java 中央计价，不要求用户配置代理或切换官方服务地址。

| 上报字段 | 含义 | 计价关系 |
| --- | --- | --- |
| `totalTokens` | 本次事件总 Token | 只用于个人、团队额度扣减及总量展示 |
| `inputTokens` | 输入 Token，已包含缓存 | 扣除缓存读写后按普通输入单价计算 |
| `cachedInputTokens` | 缓存读 Token | 包含在输入中，按缓存读单价计算 |
| `cacheWriteInputTokens` | 缓存写 Token | 包含在输入中，按缓存写单价计算 |
| `outputTokens` | 输出 Token，已包含思考 | 全部按输出单价计算 |
| `reasoningOutputTokens` | 思考 Token | 输出的子集，仅展示，不再次收费 |

单价统一为 **USD / 百万 Token**：

```text
普通输入 = inputTokens - cachedInputTokens - cacheWriteInputTokens
估算费用 (USD) = (
    普通输入 × 输入单价
  + outputTokens × 输出单价
  + cachedInputTokens × 缓存读单价
  + cacheWriteInputTokens × 缓存写单价
) / 1,000,000
```

例如输入 1000、缓存读 300、缓存写 100、输出 200、思考 150，配置普通输入 2、输出 8、缓存读 0.5、缓存写 3 USD / 百万 Token：总量是 1200 Token，费用是 0.00325 USD。150 个思考 Token 不再另加到总量或费用。

Java 使用 `BigDecimal`，每笔费用按 `HALF_UP` 保留 12 位小数，再汇总。界面最多显示 6 位小数，小于 0.000001 USD 的正费用显示 `< 0.000001`，与零费用区分。

## 模型定价与未知数据

OpenAI [官方定价页](https://developers.openai.com/api/docs/pricing)公布价格，但公开的 [List models API](https://developers.openai.com/api/reference/resources/models/methods/list)只返回模型标识等元数据，不包含单价；截至 2026-10-10 未找到有文档支持的模型价格查询 API。因此不从 `/v1/models` 猜测价格，也不使用未经支持的内部接口。

V20 从 [CC Switch 的固定版本价格表](https://github.com/farion1231/cc-switch/blob/eda410e57b4659d6bbb2a10a069d8bac2213099a/src-tauri/src/database/schema.rs)导入 OpenAI/Codex 常用模型的 USD / 百万 Token 快照。来源标为 `CC_SWITCH`，记录来源链接及核查日期；这是第三方快照，不等于 OpenAI 实时官方报价。已存在的管理员定价不被覆盖。管理员在“用量统计 → 模型定价”可编辑四个分项单价，编辑后来源标为 `MANUAL`；未知模型也可手动添加，可填 `0`。模型标识精确匹配上报值。查询按当前单价计算历史费用；修改或删除单价会改变历史费用展示，不修改原始用量记录。**当前不自动同步 CC Switch 或 models.dev**，需定期核对来源并通过新迁移或管理员界面更新价格。

内置快照只代表 CC Switch 所列的标准、短上下文文本 Token 价格；长上下文、Fast/Priority、Batch、区域加价、多模态和工具调用费不在本估算内。模型别名改指向、促销到期或价格调整后，管理员需复核单价。

| `costStatus` | 条件 | 费用 |
| --- | --- | --- |
| `PRICED` | 分项有效且模型已定价 | 估算 USD，可为零 |
| `MISSING_BREAKDOWN` | 输入、输出、缓存读或缓存写任一未知 | `null` |
| `INVALID_BREAKDOWN` | 输入与输出不等于总量、缓存超过输入，或思考超过输出等 | `null` |
| `MISSING_PRICE` | 分项有效，但模型未配置单价 | `null` |

历史记录不会回填分项或推测模型。新日志中输入、输出缺失以及显式 `null` 保留未知；未出现可选缓存、思考字段时按该格式的零值处理。累计日志回退时，仅在前后快照均有对应值时计算分项差值。思考分项未知不影响按完整输出计算费用。

汇总费用只包括可估价事件，并展示“可估价 X / Y 条”。全部未知时费用为 `null`；部分可估价时是已知部分之和。缓存命中率使用分项有效事件的缓存读总量 / 输入总量，输入总量为零时为 `null`。分项汇总只加已知值，不把缺失当作零。

这不是官方订阅账单，也不把官方 5 小时、7 天额度百分比换算成 USD。官方额度窗口仍独立显示。

## 查看入口与时间范围

- 管理端 `/usage`：按日期、模型、思考等级、用户、团队筛选；显示汇总、Token/USD 趋势、Top 20 分布及分页流水。保留原有流水字段，追加分项和费用。
- 桌面端“用量统计”：当前系统用户近 24 小时、7 天、30 天汇总和趋势，每 10 秒刷新，可手动刷新；另保留“消耗流水”和“运行诊断”。
- 中央统计以事件发生时间筛选，范围为 `[from, to)`，趋势按 UTC 小时或日期分桶。界面补齐没有事件的桶为零；有事件但费用未知的桶保持 `null`，不画成零费用。
- 管理接口最大范围 93 天，小时趋势最多 31 天；个人接口只允许 `days=1/7/30`。

个人统计身份来自中央认证用户，不接受请求参数覆盖用户 ID。本机 Java 和 Tauri 使用固定查询路径，仅转发 `days`；桌面查询缓存包含系统用户 ID，切换用户时不展示另一人的结果。读取失败显示失败状态，不能伪装成没有用量。

## 升级与数据库

V19 只新增五个可空分项列和 `usage_model_prices` 表；V20 为该表增加价格来源列，并对不存在的模型导入 CC Switch 快照，不覆盖已有单价。不改旧迁移、不改事件 ID、不删除流水。新后端兼容旧客户端的上报和流水响应；客户端使用 `includeTokenBreakdown=true` 请求新字段。回退旧后端可保留新增列及表，不需要删除数据。

完整功能需更新中央后端、桌面安装包和 Rust 服务。旧服务可以继续上报总量与原有元数据，但不会产生新增分项，相关费用仍为未知。构建源码或运行前端测试不会自动升级已安装的服务。

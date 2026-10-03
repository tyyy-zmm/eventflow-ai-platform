# Redis 与 MySQL 库存一致性及故障恢复

## 目标与边界

Redis 承担高并发入口的快速判断，MySQL 保存最终订单和库存真相。系统不尝试让一次 Redis 命令和一次 MySQL 事务组成“跨库原子事务”，而是把每一步变成可识别、可重试、可对账的状态变化。

最终正确性仍由 MySQL 约束保证：库存使用 `available>0` 条件扣减，订单使用 `(activity_id,user_id)` 唯一约束，一次请求使用 `(user_id,request_key)` 唯一约束。即使 Redis 状态漂移，也只会增加一次数据库拒绝，不会超卖或重复建单。

## 正常链路

1. Redis Lua 在一个原子脚本中检查活动库存和用户标记，写入 `PENDING` 请求并预扣一份库存。
2. 只有预扣成功的请求进入 MySQL，事务内写入 `ux_request` 与 `ux_outbox`。
3. Outbox Relay 将事件可靠发送到 Kafka，失败时保留数据库记录并退避重试。
4. Kafka 消费者在 MySQL 事务内执行条件扣库存、唯一性检查和订单写入。
5. 同一事务写入 `ux_reservation_action`：成功建单记录 `CONFIRM`，失败或过期记录 `RELEASE`。
6. 补偿 Worker 通过幂等 Lua 应用动作：确认预扣，或回补 Redis 库存并清理用户占位。

用户取消或确认超时时，MySQL 事务先恢复最终库存并记录 `RESTORE`；Redis 随后幂等回补，但保留一人一单标记，与“取消后不可重复购买同一活动”的业务规则一致。

## Redis 状态

- `PENDING`：Lua 已预扣，数据库结果尚未确认。
- `CONFIRMED`：MySQL 已存在成功订单，Redis 预扣正式生效。
- `RELEASED`：建单失败、请求过期或孤儿预扣，库存已回补，用户可使用新请求重试。
- `RESTORED`：订单取消或确认超时，库存已回补，但保留用户购买记录。

Lua 对状态迁移做条件判断，因此同一补偿动作重复执行不会重复加库存。MySQL 动作表使用 `(request_id,action)` 唯一约束，数据库侧也不会重复生成同类动作。

## 关键故障窗口

| 故障位置 | 已有持久依据 | 恢复方式 |
|---|---|---|
| Lua 预扣后、MySQL 写请求前崩溃 | Redis `PENDING` 与超时索引 | 对账发现数据库无请求，执行 `RELEASE` |
| MySQL 请求/Outbox 提交后、HTTP 响应前崩溃 | MySQL 幂等请求与 Outbox | 客户端同键重试返回原请求，Relay 继续投递 |
| Kafka 发送后、Outbox 标记前崩溃 | Outbox 仍未发送 | 重发同一 `eventId`，消费事务幂等 |
| MySQL 建单提交后、Kafka ACK 前崩溃 | 订单、请求终态与补偿动作均已提交 | Kafka 重投，数据库识别已完成；补偿动作幂等 |
| Redis 暂时不可用 | MySQL 订单及未应用动作 | 新请求保守拒绝；查询/取消继续；恢复后重放动作 |
| Kafka 暂时不可用 | MySQL 请求与 Outbox | 请求保持 `ACCEPTED`，Kafka 恢复后继续投递；超时请求释放预扣 |

## 对账逻辑

Redis 为每个活动维护按到期时间排序的 `PENDING` 集合。对账任务扫描到期项并查询 MySQL：

- 数据库不存在请求：判定为 Lua 后、落库前的孤儿预扣，回补库存；
- 请求仍是 `ACCEPTED`：使用数据库处理截止时间延长观察期限；
- 请求成功：补做 `CONFIRM`；
- 请求拒绝或过期：补做 `RELEASE`，一人一单冲突则保留用户标记。

这套逻辑解决的是进程崩溃和中间件短时不可用造成的最终一致性，不等同于 Redis 集群级数据灾备。当前本地环境是 Redis 单节点 AOF、Kafka 单 Broker、MySQL 单实例。

## 已验证结果

正式故障证据：`evidence/faults-1790100345790/`。已覆盖预扣后崩溃、HTTP 响应前崩溃、Kafka 发送后崩溃、数据库提交后未 ACK、取消事务回滚、Kafka 停机恢复、Redis 停机恢复、幂等重放和并发突发。

所有场景单独结束时，Redis 待处理预扣、未应用补偿动作、MySQL 库存违规和订单状态违规均为 0。

## 面试回答

“为什么不能只相信 Redis 库存？”

Redis Lua 只能保证脚本内部原子，不能覆盖后续 Kafka 与 MySQL。Redis 预扣之后应用可能崩溃，消息可能延迟，MySQL 也可能失败，因此 MySQL 必须保留条件扣减和唯一约束，Redis 侧则通过待处理状态、幂等补偿动作和超时对账恢复。

“会不会重复回补导致库存变多？”

不会依赖‘消息只来一次’。补偿动作在 MySQL 有唯一键，Redis Lua 只允许 `PENDING/CONFIRMED` 向目标状态迁移；同一动作重复执行时状态条件不满足，不会再次 `INCR`。

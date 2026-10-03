# Redis Lua 防超卖与一人一单｜新版详解

> 历史材料（归档于 2026-10-03）：下文“当前”“最终”“待实现”等说法只指原记录日期，不代表合并版状态。请先读[生活优选当前入口](<../../../../../README.md>)和[当前验收](<../../../../合并验收.md>)。旧压测、预约体系和简历口径不可直接套用到合并版。标有 `_archive/` 的源码/原始实验仅保存在本地归档，不包含在本仓库。

## 1. 旧版思路解决了什么

旧版把“库存是否充足”和“用户是否购买过”放入 Redis，用 Lua 在一个原子执行单元中完成校验、扣减和用户标记。它比“查 MySQL + 分布式锁 + 更新库存”更适合高并发入口，因为多数售罄和重复请求无需占用数据库连接或参与行锁竞争。

这个方向仍然成立，但“Redis Lua 原子”只能保证脚本内部没有并发穿插，不能单独证明跨 Redis、Kafka、MySQL 的业务最终正确。

## 2. Lua 的原子逻辑

```lua
-- 0: accepted, 1: sold_out, 2: duplicate, 3: request_conflict
if requestId already exists then
  if payloadDigest differs then return 3 end
  return savedResult
end
if SISMEMBER(buyersKey, userId) == 1 then return 2 end
if tonumber(GET(stockKey) or '-1') <= 0 then return 1 end
DECR(stockKey)
SADD(buyersKey, userId)
HSET(requestKey, 'state', 'ACCEPTED', 'digest', payloadDigest)
XADD(eventStream, '*', 'requestId', requestId, 'userId', userId, 'activityId', activityId)
return 0
```

实际实现还要校验活动时间、请求参数和 key 的存在性，明确库存未初始化时是失败关闭还是回源初始化。脚本不调用外部服务，避免把不可控网络操作放入 Redis 执行线程。

## 3. 为什么必须有 requestId

一人一单解决的是业务身份唯一，requestId 解决的是同一次请求重试。客户端超时后携带同一个 requestId 重试，系统应返回原受理结果；如果每次重试都生成新 ID，Kafka 和数据库会看到多个不同请求，只能依赖用户活动唯一约束兜底，无法向客户端稳定返回原状态。

同一个 requestId 携带不同 userId/activityId 时必须拒绝，不能返回前一次结果。可以保存规范化 payload 的摘要做一致性检查。

## 4. Redis 数据结构取舍

- **String 库存：** `DECR` 简单高效，适合单活动剩余名额。
- **Set 用户集合：**支持一人一单的 membership 判断，但大活动可能形成大 Key。
- **Hash 用户计数：**适合一人 N 单，但仍有大 Key 和迁移成本。
- **分片集合：**可按 userId hash 拆分大 Key，但增加对账、清理和 Cluster slot 设计复杂度。
- **Bitmap：**用户 ID 连续且只需布尔标记时节省空间，但不适合稀疏长 ID，也不保存订单信息。

简历只需要说明当前选择和业务规模，不必为了展示技术把所有结构都用上。

## 5. Lua 能防住哪些问题

能防住两个请求同时看到最后一份 Redis 库存、同一用户并发通过 Redis 资格校验，以及检查与预扣之间的竞态。

它不能单独防住 Redis 已成功但事件没有可靠传出、Redis 数据丢失、Kafka 重复投递、MySQL 事务失败后未补偿，以及订单取消后的回补竞态。所以正确表述是“Redis Lua 在入口原子完成预扣与一人一单校验，MySQL 约束和补偿对账保证最终正确”。

## 6. Redis 与 MySQL 如何一致

系统不追求两个系统每一毫秒数值相同，而是允许短暂差异并保证最终收敛：Redis 受理后生成稳定请求记录；Kafka 消费者幂等落库；MySQL 失败时生成可重试补偿任务；补偿按 requestId 只回补一次；定时扫描长时间停留在 ACCEPTED/PROCESSING 的请求；最终对账 Redis 预扣、MySQL 有效订单与补偿任务。

MySQL 条件扣减与唯一约束始终保留，避免 Redis 异常时突破最终不变量。

## 7. 实验设计

正确性实验至少包括：1000 用户抢 100 份库存、多线程同一用户重放、Kafka 重复消息、Redis 受理后应用强退、MySQL 建单失败、取消与超时竞争。每轮核对 Redis 剩余量、MySQL 权威库存、有效订单数、重复用户数、悬挂请求和待补偿任务。

性能实验区分入口 QPS 与最终订单 TPS。入口 QPS 可以很高，因为大量请求在 Redis 快速拒绝；最终 TPS 受 Kafka 消费和 MySQL 写入限制。简历写 2000 QPS 时必须明确是秒杀入口吞吐，而不是 2000 笔订单每秒。

## 8. 一分钟逐字稿

> 旧方案直接查询 MySQL 判断一人一单并更新库存，高并发时会消耗连接并造成热点行锁竞争。我把资格判断前置到 Redis，用 Lua 原子完成库存检查、用户集合判断、预扣和请求受理记录，因此售罄与重复请求不会进入数据库。Lua 只能保证 Redis 内部原子，所以后续落库仍使用 MySQL 条件扣减和用户活动唯一约束；Redis 预扣后发生宕机或建单失败，则通过幂等补偿与超时对账恢复。这样 Redis 负责性能，MySQL 负责最终正确性。

## 9. 高频追问

### Lua 会阻塞 Redis 吗？

会。Redis 执行 Lua 期间不能处理其他命令，所以脚本必须短小、固定复杂度，不能扫描大集合或执行不可控循环。

### Redis Cluster 下 Lua 多 Key 怎么办？

所有 key 必须在同一个 hash slot，可使用 `{activityId}` hash tag。只在单节点测试过时不能声称已验证 Cluster。

### Redis 宕机怎么办？

新的秒杀请求可失败关闭或限流降级，不能无界回源压垮 MySQL；已经受理的请求通过持久事件、Kafka 和 MySQL 状态继续恢复。恢复 Redis 后执行对账再开放入口。

### 预扣成功能直接返回下单成功吗？

不能。只能返回“已受理/排队中”，客户端按 requestId 查询最终结果。只有 MySQL 订单事务成功后才是业务成功。

## 10. 证据边界

当前代码已经具备 Redis Lua 准入、MySQL 交易约束和 Kafka 可靠性相关模块，但仓库主线并未完全等同于本文的“Redis 真实库存预扣”版本。完整实现和压测报告补齐前，不能把目标设计说成现有代码已全部验证。

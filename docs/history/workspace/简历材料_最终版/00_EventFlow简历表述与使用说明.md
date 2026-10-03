# EventFlow 简历表述与使用说明

> 历史材料（归档于 2026-10-03）：下文“当前”“最终”“待实现”等说法只指原记录日期，不代表合并版状态。请先读[生活优选当前入口](<../../../../README.md>)和[当前验收](<../../../合并验收.md>)。旧压测、预约体系和简历口径不可直接套用到合并版。标有 `_archive/` 的源码/原始实验仅保存在本地归档，不包含在本仓库。

> 历史材料提示（2026-10-03）：本文针对早期 EventFlow/候补实现或当时缺口。现在已另行实现并验证 backend-upgrade 秒杀与多级缓存链路，请以 [新版收尾讲稿与证据](<../../cache-seckill/收尾面试讲稿与简历证据.md>) 为当前生活优选简历依据，避免混用候补、秒杀和旧压测指标。


## 一、项目定位

**项目名称：EventFlow - 高并发活动预约与智能行程规划平台**

**技术栈：Java、Spring Boot、MySQL、Redis、Kafka、Docker、DeepSeek、Multi-Agent**

**项目简介：** 基于点评类业务底座扩展活动预约与候补场景，围绕限量名额交易、热点目录访问、异步候补补位和自然语言行程规划，构建 MySQL 权威交易、Redis 缓存与准入、Kafka 可靠事件链路及只读 Multi-Agent 规划能力。

来源口径：项目借鉴黑马点评的业务背景和基础工程结构，EventFlow 的预约状态机、正式鉴权、候补链路、Outbox/Inbox、缓存治理、规划任务底座、故障实验与模型评测为后续扩展。面试时不要把课程已有功能说成从零原创，也不要把本地单节点实验说成生产上线。

## 二、推荐简历四条

### 1. MySQL 交易一致性

**预约交易一致性：** 设计 `HELD/CONFIRMED/CANCELLED/EXPIRED` 预约状态机，以 MySQL 场次行短事务统一名额扣减、有效预约身份、请求幂等与 Outbox 写入；在 16 线程、1000 用户、1-7 人组竞争 100 个名额的真实 MySQL 实验中实现分配总量严格为 100，并通过进程 `halt(137)` 验证响应丢失后原键重试不重复扣量。

### 2. Redis 缓存与准入

**Redis 热点治理：** 构建 Cache-Aside、随机 TTL、owner 回填锁与用户/活动级 Lua 准入；同机三轮热目录压测中，可持续档位由 MySQL 直读 1000 QPS 提升至 Redis 3000 QPS，3000 档成功率 100%、P95 不高于 18.2ms、目录回源为 0。

### 3. Kafka 可靠候补

**Kafka 可靠候补链路：** 基于 Transactional Outbox、发送租约、原 `eventId` 重投及 Inbox 去重，将名额释放与候补补位解耦；消费者按 `sessionId` 分区，在同一 MySQL 事务内完成 Inbox 登记与 FIFO 多人补位，经过 Kafka 停机 120 秒、1000 条持久唤醒积压及生产者/消费者崩溃窗口验证，恢复后业务效果保持一次。

### 4. Multi-Agent 规划与评测

**Multi-Agent 行程规划：** 拆分 Discovery/Planner，基于活动标题、描述和区域完成偏好筛选与行程编排，并以 Java 校验预算、时间冲突和余量等硬约束；规划只读，用户确认后才进入预约事务。

## 三、一页简历压缩版

版面不足时使用下面版本，优先保留数字与机制，不再额外堆背景描述。

- **MySQL 一致性：** 以场次行短事务、有效身份约束和请求幂等实现预约状态机；1000 用户/16 线程争抢 100 名额时分配严格为 100，进程退出后原键重试不重复扣量。
- **Redis 治理：** 实现 Cache-Aside、随机 TTL、owner 回填锁与用户/活动级 Lua 准入；三轮热目录压测中，可持续档位由直读 1000 提升至 Redis 3000 QPS，3000 档成功率 100%、P95≤18.2ms。
- **Kafka 候补：** 以 Outbox/Inbox、发送租约、原事件重投和事务内 FIFO 补位治理重复与崩溃窗口；Kafka 停机 120 秒并积压 1000 条唤醒后恢复，业务效果保持一次。
- **Agent 规划：** 构建 Discovery/Planner，依据活动语义完成偏好筛选与行程编排，并以 Java 校验预算、时间冲突和余量等硬约束。

## 四、不能写成什么

- 不把“1000 用户竞争”写成 QPS。QPS 只引用独立热目录 HTTP 饱和实验，并明确是本机目录服务链路，不是生产系统吞吐。
- 不写“Redis 将正式详情接口 P95 降低 xx%”。1 万目录矩阵中正式接口 P95 没改善；3000 QPS/18.2ms 来自排除鉴权和发布查询的 `CatalogService` 热点实验。
- 不写“Kafka exactly-once”。正确说法是 at-least-once 传递下，通过 Inbox 和数据库事务实现业务效果幂等。
- 不写“Multi-Agent 准确率提升 10%”。冻结测试只有 44 条有效案例，观察值是 40/44 到 44/44，不能外推总体准确率。
- 不写“Review 提升正确率/降低成本”。本次消融中 Review 没有正确率收益，反而多 24 次调用和 22,200 Token。
- 不写“AI 自动预约”。规划只读，最终预约必须由用户确认并重新进入鉴权后的 MySQL 事务。

## 五、面试开场版

> EventFlow 是我在点评类业务基础上扩展的活动预约与智能规划项目。我没有把 Redis 当库存真相，而是用 MySQL 短事务维护名额守恒；Redis 负责目录缓存和入口保护；Kafka 通过 Outbox/Inbox 驱动候补补位；AI 只负责只读规划，最终仍由 Java 硬校验和预约事务兜底。项目重点不是堆中间件，而是分别验证数据库竞争、缓存回源、消息崩溃窗口和模型协议遵循，并保留了没有收益的实验结论。

## 六、证据索引

- 最终集中验收：`_archive/2026-10-03/eventflow/experiments/pre-ai-1790044322569/results.json`（7 阶段全部 PASS，外部模型调用 0 次）
- 验收过程与分项证据：`_archive/2026-10-03/eventflow/experiments/pre-ai-1790044322569/` 下各阶段日志、集成报告与 `source-sha256.json`
- MySQL 竞争与恢复：`_archive/2026-10-03/eventflow/docs/MySQL恢复与竞争验收.md`
- Redis 负载矩阵：`_archive/2026-10-03/eventflow/docs/Redis矩阵验收.md`
- Redis QPS 饱和实验：`_archive/2026-10-03/eventflow/docs/Redis_QPS饱和实验.md`
- Kafka 停机恢复：`_archive/2026-10-03/eventflow/docs/Kafka停机恢复实验.md`
- Kafka 异常闭环：`_archive/2026-10-03/eventflow/docs/Kafka异常闭环验收.md`
- Agent 冻结评测：`_archive/2026-10-03/eventflow/eval/freeze-v2-2026-09-21/RESULTS.md`
- Agent 语义评测：`_archive/2026-10-03/eventflow/eval/planning-semantic-v1.json` 与 `_archive/2026-10-03/eventflow/eval/runs/semantic-{dev,test}-2026-09-21*/events.jsonl`
- 本轮准入与跨 TTL 实验：`_archive/2026-10-03/eventflow/experiments/process-1790007850902`、`_archive/2026-10-03/eventflow/experiments/process-1790008010858`
- 面试逐字稿与模拟题：`简历材料_最终版/06_EventFlow面试逐字稿与模拟题.md`

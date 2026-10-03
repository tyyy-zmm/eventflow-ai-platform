# Kafka 异常闭环验收

本轮交付代码与独立测试，不代表已通过真实中间件验收。未运行 Maven、数据库迁移、Kafka 停机或模型调用；真实实验由主 agent runner 串行执行。未修改主 scripts、MiddlewareIT 或 operations/Maintenance。

## 运行入口

工作目录为 `hmdp_code - agent/eventflow`。使用现有启动好的真实 MySQL/Kafka，不启动或停止容器。MySQL 连接必须是 loopback 上的 `eventflow_it`；拒绝其他数据库名称。不 reset、clean、TRUNCATE 或删除已有实验数据。需设置 `EVENTFLOW_INTEGRATION=true`、`EVENTFLOW_IT_DB_URL`、`EVENTFLOW_DB_USER`、`EVENTFLOW_DB_PASSWORD` 和实际 Kafka 地址 `EVENTFLOW_KAFKA_SERVERS`。凭据由 runner 的现有环境提供，不写入实验文档。

```sh
mvn -Dtest=DeliveryContractTest test
mvn -Dtest=DeliveryRecoveryIT test
```

两条命令必须串行。IT 不依赖 failsafe 或主 scripts；未开启环境门控时跳过，不能把 skipped 计作通过。IT 显式 `background=false`、`eventflow.kafka.publisher.enabled=false`、禁用模型/崩溃注入、关闭自动 listener 启动，手动启动所属消费者。发布开关默认 true，完全独立于 background 容量扫描开关；主 runner/MiddlewareIT 即使关闭扫描仍正常发布 Kafka。专属 IT 关闭 publisher 时不领取全局 Outbox/Replay。每个 case 创建随机三分区 topic 和随机 group，仅自己产生的消息会被消费。关闭自己创建的容器和 AdminClient，保留新 fixture/topic/审计以便复核。每个异步条件最多等待 60 秒，发送/管理操作另有有界超时，不能无限等到“成功”。

## V8 升级

`src/main/java/db/migration/V8__delivery_recovery.java` 是 Flyway JavaMigration。V1 不变。JDBC metadata 精确识别仅由 event_type/source_id 两列构成的唯一索引，数量不是一条则在 DDL 前失败。MySQL 使用 INFORMATION_SCHEMA.STATISTICS 复核列顺序和唯一性；H2 使用 TABLE_CONSTRAINTS 将索引映射到真实生成的约束名。

先建立 `(event_type,source_id,source_revision)` 新唯一约束，再新增位置、摘要、长度和重放错误码列，最后移除旧约束，并复核唯一索引后置条件。无 DML、无数据删除。MySQL DDL 不是整段原子事务；中途失败会留下部分 DDL，但旧或新身份约束仍在，不承诺自动回滚。runner 应在维护窗口升级，失败时先检查 Flyway 历史和 INFORMATION_SCHEMA，再按实际 DDL 状态处理，不能盲目重跑/清库。H2 用例包含 V7 带源事件升级到最新版本的保留检查；真实 MySQL 升级现存库仍须 runner 实测。

## 用例与证据

| 用例 | 必须成立的断言 |
| --- | --- |
| 非法 schema | 异常先落库，原 topic/partition/offset、UTF-8 长度和 SHA-256 可定位；未知敏感字段不进入 payload；不可直接重放，管理员忽略审计仅一条；随后原 offset 提交 |
| 身份冲突 | 相同 eventId 改 revision 不能当正常重复；CONFLICT 持久化，offset 不越过；已有原事件 Inbox 不得误关闭冲突重放；管理员 ignore 取消 QUEUED 并推进原 offset |
| 管理员重放 | actor/requestKey 幂等，改原因冲突；发送保留原 eventId，SENT 时异常仍 REPLAY_REQUESTED；真实消费的同摘要 Inbox 才变 RESOLVED，系统 EVENT_RESOLVED 审计一次 |
| 两消费者再均衡 | 两独立容器共享组，各持有分区；停止第二个容器后第一个取得三分区；在原属第二消费者的分区继续处理并提交 offset |
| 重复/乱序 | 先 revision=2 后 revision=1，各重复同 eventId；Inbox 只两条，候补固定同一预约，预约仅一条，available=8、局部守恒=10 |
| 保留清理 | 旧未发布源、OPEN 异常、终态 RESOLVED/IGNORED 但 QUEUED 重放、SENT 且 REPLAY_REQUESTED 均保护关联 Inbox/Outbox；无待办对照组可删 |
| MATCHED 请求键 | 关联 HELD/CONFIRMED 预约保留；关联 CANCELLED/EXPIRED 预约且请求期限到期才清理 |

真实保留测试调用 Maintenance 的原始清理谓词，测试 JdbcTemplate 仅给 Inbox/Outbox/waitlist_request 三条 DELETE 加本次场次条件，其余 DELETE 不执行。过期扫描也只选择本次 fixture 的预约。这个适配器仅防止污染共享 IT 数据，不替代生产清理算法；原 SQL 不再符合约定形状时测试应失败以要求 review。重放 IT worker 的选择同样限定本次 replay id，不领取其他实验的 QUEUED 工作。管理员重放 case 的失败记录是独立 SQL fixture，原因明确为 IT_SYNTHETIC_REPLAY_FIXTURE，没有声称发生过真实 Kafka 故障。

## 恢复与审计边界

Outbox/Replay 使用 1/2/4/8/16/30 秒基础退避，额外 0–25% 抖动；30 秒发送租约保留，实际重领取同时受 lease_until 和 next_attempt_at 限制。旧 owner 不能覆盖新 owner 的状态。错误只存异常类型，不存连接详情或任意异常消息。发送成功只是发送工作完成，不等于业务完成。

TaskTracking 的 OUTBOX/REPLAY 仅对实际领取工作计数，空 poll 不记成功；捕获的发送异常和领取数据库异常记 failed，owner 条件更新未成功也不记成功。CONSUME 的 success 表示这条记录处理与 ack 路径完成，包含已持久化隔离或显式忽略，不是“补位成功率”。不能以这些计数代替 Inbox/预约证据。

消费者采用无限重试、容器 pause/resume 退避，禁用自动 recovered commit 和 ack-after-handle。恢复调度器独立于应用 Scheduled 任务。API 参考 [Spring Kafka DefaultErrorHandler](https://docs.spring.io/spring-kafka/api/org/springframework/kafka/listener/DefaultErrorHandler.html) 和 [容器暂停服务](https://docs.spring.io/spring-kafka/api/org/springframework/kafka/listener/ListenerContainerPauseService.html)；锁定依赖版本下的心跳/再均衡行为必须由真实 IT 验证。

身份摘要不匹配的冲突需要审计忽略或另建权威新事件，不能删 Inbox 强制执行。忽略不能撤回已经进入 Kafka 的在途消息，也不能回滚此前业务事务。旧 Inbox 没有 payload_hash 时不自动断言完整摘要一致，因此不能据此关闭失败工单。管理员权限校验仍由 API/auth 层负责，本 IT 直接调用服务，不证明 HTTP 角色隔离。

## 留给主 runner 的实测

- 串行编译与 DeliveryContractTest、DeliveryRecoveryIT；保存 surefire 结果，注明 skipped。
- V7→V8 真实 MySQL 带已有数据升级，确认行数/源身份不丢及两列旧唯一索引移除；V9 由主 agent 管理。
- Kafka 停 30/120 秒、发送确认后标记失败、消费者提交后 ack 前进程崩溃；固定恢复窗口，分别核对发送/Inbox/业务。
- 真实数据库故障期间 pause/resume 与再均衡组合，以及两进程非优雅退出；目前 IT 的再均衡是消费者正常离组，不代表崩溃/网络分区。
- 单独开启扫描兜底实验，区分 MESSAGE 与 RECONCILE；本 IT 关闭扫描，只证明消息触发效果。

兼容性提醒：既有 RemainingStagesTest 的 replayFailure 用例只回拨 lease_until；新增重放退避后也必须回拨 next_attempt_at，主 agent 可更新该既有测试。本轮遵守范围，没有修改它。

现存失败/重放/审计 record_id/object_id 长度合同为 100 字符；本 IT 短随机 topic 满足该合同。超过该长度的 Kafka 主题位置需要后续统一扩容（含外键/审计/API），当前不能声称支持所有 249 字符主题。schema1 无 occurredAt 时会检查已有源事件/Inbox 的年龄，防止新失败记录延长旧去重窗口；源/Inbox 都已缺失的外部旧 schema1 消息仍无法恢复真实事件年龄，这个边界不能声称已解决。

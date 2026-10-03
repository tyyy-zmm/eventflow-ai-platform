# Kafka 停机恢复实验

## 执行条件

```bash
bash scripts/local.sh kafka-experiment
```

需要现有私有 `.env`，`eventflow-local` 的 MySQL、Redis、Kafka 健康，18191/18192 空闲。先 clean package 并执行常规测试，再运行专用实验组；只使用 loopback `eventflow_it`，不清库，使用新建场次及独立 topic/group。与其他进程实验共用运行锁。

**本入口会短暂停止共享的本机 Kafka 容器。运行期间不要让其他应用依赖该容器，不适合与其他压测同时执行。** MySQL/Redis 不停止，Kafka 数据卷保留，不执行 compose down、删除容器或清除消息。

## 验收链路

1. 创建容量 10 的场次，占满名额，另一个用户申请候补 2 个；启动真实 Java Kafka 发布/消费实例，等初始 Outbox 发布完成及 consumer group 消费收敛。
2. compose stop Kafka，使用容器状态确认实际停止；取消原预约，生成容量释放事件。
3. 等待真实发送失败，查 MySQL 验证原 eventId 未发布、发送次数至少 1、错误已记录、Inbox 为 0；候补仍 WAITING，余量 10。
4. Kafka 停机期间向启用 Kafka 的同一 Java 实例提交另一个场次的预约，必须成功，验证业务提交不依赖同步 Kafka 确认。
5. compose start 同一 Kafka，等待健康；原 Java 进程不重启，等待租约/重试发送、消费及补位。原事件至少两次发送尝试、已发布、Inbox 1、匹配 1、目标用户预约 1、余量 8。
6. consumer group 三分区消费收敛，七项业务不变量全 0，停止本轮自己的应用进程，撤销本轮会话。

真实模型和容量补偿扫描关闭，补位不能由扫描器代替 Kafka 完成。业务取消和候补状态查询由独立协调实例完成；停机期间新增预约则直接请求启用 Kafka 的工作实例。

## 清理与证据

Kafka 停止前设置恢复标记。断言失败或正常 SIGINT/SIGTERM 中断时，finally 会尝试启动 Kafka，恢复健康的等待不受实验取消信号打断；最长 240 秒。Docker 不可用或 Kafka 恢复失败仍可能导致清理失败，结果必须检查，不能承诺任意系统故障都可自动恢复。强杀脚本、宿主机断电无法保证 finally 执行；此时先人工 `bash scripts/local.sh up` 恢复环境，再检查遗留锁与进程，不自动删除未知锁。

输出 `experiments/process-<timestamp>/`：`broker-outage.json` 记录停机期间事件/容器状态/初始 offset；`results.json` 记录最终 SQL、offset、容器健康变化、应用 PID、结果；工作实例日志包含真实发送超时。失败同样保留记录。

故障后的重试受 30 秒 Outbox 租约及退避影响；不修改数据库时间、不清除租约来加速通过。恢复耗时包含启动/健康检查、租约重试、消费与验收轮询，不应解读为 Kafka 自身启动耗时或服务 SLA。

## 结论边界

这验证单机单副本 Kafka 的正常停止与持久卷重启，以及有限样本的 Outbox 留存、恢复投递和业务去重。不是 broker 强杀、磁盘损坏、多副本 leader 切换、长时间大规模积压或跨系统分布式 exactly-once 实验。Kafka 停机期间业务写入仍可成功，但候补补位延迟，不能说系统所有功能都完全不受影响。

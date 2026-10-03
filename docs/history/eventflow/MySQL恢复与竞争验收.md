# MySQL 恢复与竞争验收

## 集成更新

独立交付后的入口已纳入 `bash scripts/local.sh pre-ai`，本页下方的“本轮未执行”描述仅限交付时状态。最新通过范围以集中验收结果为准。新增真实连接池耗尽用例：池上限2、250ms等待，两个连接同时占用时新意图无法获得连接且不写预约/请求，释放后原键成功重试。

清理用例已改为真实持久表上的本轮新场次 fixture。JdbcTemplate 适配器仅允许 Maintenance 原有请求清理 SQL 增加 session_id 限定，其余删除返回0；SQL结构变化直接失败。验证过期终态键可清理，NULL保留期、有效预约和未到期保留期不可删，并检查本次事件未被删除。Inbox/Outbox 和活跃 MATCHED 键的清理边界由 DeliveryRecoveryIT 中独立限定场次的保留测试覆盖。已放弃旧临时表方案，避免 MySQL 重复引用临时表的限制；[MySQL ALTER TABLE 规则](https://dev.mysql.com/doc/refman/8.4/en/alter-table.html)也说明重命名会调整自动 CHECK 名称，下方临时表说明仅为历史。

external 运行时已配置 SESSION innodb_lock_wait_timeout=2、UTC、驱动连接超时3秒/socket超时10秒，Hikari等待1秒/上限24。锁超时或资源不足仍由API返回retryable503，客户端复用原键重试；不宣称已经实现业务内部自动重试或所有HTTP请求硬性5秒截止。

交付日期：2026-09-18。依据 booking 状态机、planning 任务设计以及《后端完整设计》第 5、10、12 节。只新增 BackendLifecycleIT.java、mysql-recovery.mjs 和本文档；没有修改业务、主 runner 或容器配置。planning/stub 独立进程验收由 Gibbs 的 PlanningLifecycleIT 负责，此交付不调用任何模型。

## 执行状态与职责

本轮没有执行 Maven、数据库竞争测试、备份恢复或容器停机。已完成 Node 语法检查、help 接口检查、非 IT 源库拒绝检查，以及使用已有 target/classes 和本地缓存依赖的 Java 17 javac 编译；编译产物仅在 /private/tmp/eventflow-lifecycle-compile。拒绝检查在 Docker 调用前结束，独立编译不代表最新业务源码或真实 MySQL 已验收。

主 agent 的 mysql-service runner 负责实际容器停机恢复。本脚本只负责逻辑备份、新库恢复、数据对照和执行计划归档，不运行 docker stop/start/restart/down，不运行 Maven，不删除任何数据库。两类实验分别报告，不能把逻辑恢复当成崩溃恢复或高可用证明。

## 独立真实竞争测试

位置：src/test/java/com/eventflow/integration/BackendLifecycleIT.java。没有 SpringBootTest，不启动应用、后台扫描、Redis、Kafka、Flyway 或模型；直接使用真实 MySQL、JdbcTemplate 和现有 BookingService。

前置条件：主 runner 已在 eventflow_it 完成迁移；运行期没有其他应用、worker、迁移或实验写入这个 IT 库。EVENTFLOW_LIFECYCLE_IT=true 和 EVENTFLOW_IT_EXCLUSIVE=true 必须显式设置。URL 只接受 jdbc:mysql://127.0.0.1:PORT/eventflow_it，允许唯一固定参数 ?serverTimezone=UTC，不接受其他库、非 loopback 地址或凭据参数。凭据沿用 EVENTFLOW_DB_USER/EVENTFLOW_DB_PASSWORD。连接固定 UTC，连接超时 3 秒，socket 超时 15 秒。

类名以 IT 结尾，需主 agent 显式选择 BackendLifecycleIT；不假设默认 Surefire 会自动发现。本轮禁止运行 Maven，所以只交付入口信息，不执行测试。运行方法：加载 local.sh 环境后，由现有 Maven 测试入口设置 -Dtest=BackendLifecycleIT 和以上两个开关；本交付不修改 local.sh 的分发逻辑。

| 用例 | 真实边界与断言 |
| --- | --- |
| 确认/过期竞争 | 把本测试 HELD 的 deadline 改为 DB 当前时刻前 1 秒；两个对象并发 confirm/expireDue。确认必须 CONFLICT，最终 EXPIRED、revision=1、仅一个释放事件、无有效预约身份；再次扫描不重复释放。这个用例验证已到期拒绝确认，不宣称覆盖截止前确认胜出的所有时序。 |
| 取消/补位 | 两个对象同时取消同一预约；再同时处理相同唤醒事件。取消目标幂等，只释放一次；Inbox 仅一条，MATCHED 固定预约，原预约键/候补键重放返回历史对象。取消与补位分为两个竞争阶段，不能描述为强制触发了所有三方交错。 |
| 候补退出/匹配竞争 | 同时退出 WAITING 与处理补位，接受两种合法顺序。退出胜出则 CANCELLED 且没有预约；匹配胜出则退出 CONFLICT、MATCHED 对应 HELD，有效候补身份消失。 |
| 锁等待超时 | 独立 blocker 连接 FOR UPDATE 锁住场次；另一个固定连接设置 SESSION innodb_lock_wait_timeout=1，再调用 reserve。必须看到 MySQL 1205；预约、请求、HELD 事件均未产生；释放 blocker 后原键成功重试且重放身份相同。仅验证测试连接的 1 秒配置和显式调用重试，不证明 HTTP deadline 或业务自动重试。 |
| 1000 用户竞争 | 同 JVM 两个 BookingService 对象，16 个执行线程，连接池最多 20；1000 个不同 userId/请求键，组人数循环 1..7，共争抢 100 名额。成功人数之和=100，available=0；其余只允许 SOLD_OUT；成功预约数=不同有效身份数=请求数=ReservationHeld 事件数，守恒/身份检查全通过。打印 session、成功/拒绝计数供报告使用。不是双进程，也不经过 auth/HTTP/Redis 准入，不能用于端到端吞吐声明。 |
| 清理边界 | 单独固定连接 CREATE TEMPORARY TABLE ... LIKE 遮蔽 Maintenance.cleanup 涉及的 11 张表，运行实际 cleanup 方法。已终结且保留期已过的请求删除；NULL 保留期、HELD 关联、未来保留期保留；未发布或有 OPEN 异常的 Inbox/Outbox 保留，已发布且 RESOLVED 的旧记录可删。连接关闭仅自动移除临时表，不删除原表记录。 |

所有场次使用随机 ID，数据以 Lifecycle IT 标题标识，保留全部真实夹具，不执行清库或测试后删记录。生产 expireDue 是全库扫描；本测试通过 scopedJdbcTemplate 精确识别当前扫描 SQL，并增加本次 held.id/session_id 条件，扫描仍在真实 MySQL 上执行，后续重读、场次锁、事务、状态/名额/身份/事件写入完全沿用 BookingService。SQL 结构变化则断言失败，不回退全局扫描。两次调用分别只过期本次预约一次和返回零，并断言限定扫描确实执行两次；不要求全局到期数为零，不过期或清除已有夹具。此用例不验证生产扫描器跨场次公平性或全库批次选择。

清理用例依赖 MySQL 会话级临时表遮蔽同名持久表，关闭连接后临时表自动消失；LIKE 保留列/索引/CHECK，但不复制 FK，所以这是清理选择条件的独立验证，不是持久表 FK 行为验收。依据：[MySQL 临时表说明](https://dev.mysql.com/doc/refman/8.4/en/create-temporary-table.html)、[CREATE TABLE LIKE](https://dev.mysql.com/doc/refman/8.4/en/create-table-like.html)。

## 备份恢复脚本接口

位置：scripts/mysql-recovery.mjs，独立 Node ESM，仅使用 Node 标准库及 docker compose exec -T mysql；宿主机无需 mysql/mysqldump。主 agent 在 local.sh 加载 .env 后调用 node scripts/mysql-recovery.mjs，或使用已经加载相同环境的 mysql-service runner 调用它。当前 local.sh 若没有对应分支，不能直接把不存在的命令写成已支持接口；分发修改由主 agent 负责。

输入：

- EVENTFLOW_IT_DB_URL：上述 loopback eventflow_it URL，兼容现有 ?serverTimezone=UTC。
- EVENTFLOW_IT_EXCLUSIVE=true：调用方承诺源 IT 库没有应用、worker、迁移或其他实验写入。
- EVENTFLOW_REPORT_DIR：可选报告父目录，默认为 experiments；实际输出放在其 mysql-recovery-<timestamp>_<random> 子目录，避免覆盖现有报告。
- Compose 环境由 local.sh 提供。数据库操作使用容器内 MYSQL_ROOT_PASSWORD，以 MYSQL_PWD 环境传给容器内客户端，不传到宿主机、不放命令行参数、不打印。脚本不读取 .env，也不自行加载凭据。

通过 stdout 返回一行 PASS: <报告绝对路径>; retained verification database: <库名>。失败 exit code=1，stderr 仅给固定脱敏提示，不转发 Docker/MySQL stderr，不回显 URL 或密码。--help 不连接数据库。

执行步骤：

1. 确认 SELECT DATABASE()=eventflow_it、MySQL 8、所有表为 InnoDB base table；拒绝 view、trigger、routine、event。源库需要已有场次和预约，避免空库恢复被误报为关键行通过。
2. 核对 Flyway 所有记录 success=1，version/script 顺序与当前 db/migration 的 V*.sql 对应。源 Flyway 历史整表也参与逐行 SHA-256 验证，因此 checksum/installed_on/installed_rank 等字段必须原样恢复；不运行迁移，不宣称用 Flyway 引擎重新计算了本地 migration checksum。
3. 取得源表结构摘要、按固定列序/主键排序的 JSON 行流 SHA-256、逐表行数和业务不变量。DDL 的 SHOW CREATE TABLE 摘要包含索引、FK、CHECK 和表选项；JSON 字段覆盖所有列，因此包括预约状态、revision、请求键、MATCHED 关联、Inbox/Outbox、身份与配额等关键行。
4. mysqldump 使用 --single-transaction --skip-lock-tables --skip-add-drop-table --no-tablespaces --set-gtid-purged=OFF --hex-blob --skip-comments。不使用 --databases，dump 不包含 CREATE DATABASE/USE 源库或 DROP TABLE。此合同拒绝触发器等对象，防止备份范围遗漏。
5. 再读源摘要确认备份期间未改变；随后 CREATE DATABASE eventflow_restore_<timestamp>，沿用源 charset/collation。不使用 IF NOT EXISTS，名称碰撞直接失败；从不 DROP 任何库。仅恢复进刚创建的新库，源查询连接设 SESSION TRANSACTION READ ONLY，唯一源连接 DDL 是创建新验证库。
6. 恢复后逐表结构/行数/行 SHA-256 必须与源一致，并检查全部不变量；再次确认源摘要没有改变。源必须实际静止，前后摘要检查不是并发备份正确性的替代证明。
7. 只在恢复库归档 EXPLAIN FORMAT=JSON：HELD 到期扫描、真实场次候补队首、当前目录检索与 Outbox 租约领取。SQL 使用现有业务字段、条件和排序，不添加 FORCE INDEX，不运行 EXPLAIN ANALYZE，不写死“必须命中某索引”。小样本表扫描可能是合理计划，结果由主 agent 分析。

恢复库无论通过或失败都自动保留，脚本没有清理/删库接口。170 秒总执行预算涵盖 Docker 客户端调用和流传输；查询按批次运行以减少进程启动成本。这是代码预算，不是已测量的耗时承诺，数据规模或本机负载可能导致超时失败。源连接只读；导入会写新库，杀掉客户端不等同于停止共享 MySQL 服务。

报告文件：

- eventflow_it.sql：逻辑备份，权限 0600；含完整业务数据与已编码鉴权信息，按本地实验文件处理。
- source-snapshot.json：源 server/version、迁移文件名、每表结构与行 SHA-256/行数、源不变量。
- destination.json：新库名及保留说明，创建后立即写入，方便失败后查证。
- acceptance.json：只有全量恢复校验通过才写 PASS，含备份 SHA-256、源/恢复逐表摘要及两端不变量。
- explain.json：每条 SQL 及解析后的 EXPLAIN JSON 对象，使用 mysql --raw 后 JSON.parse，方便主 runner 直接读取计划字段。

业务不变量包括：名额守恒、有效预约/候补交集为空、有效身份与状态/所属对象一致、缺失身份检测、MATCHED 预约的用户/场次/人数一致，以及 queued_count 等于 QUEUED 数量。配额只读检查属于数据库恢复合同，不重复 planning 行为测试。

## 需主 Agent 跟进

目前业务 BookingService 的事务超时为 10 秒，已有 datasource 初始化只配置 UTC，没有看到统一 SESSION innodb_lock_wait_timeout=2 或事务回滚后的内部自动重试实现。ApiErrors 将 DataAccessException 转成可重试 503，但这不证明设计中的锁等待/请求总预算已经落实。本测试显式设定短锁超时、确认回滚，再由测试调用原键重试；若要满足业务自动重试与生产等待预算，需要主 agent 评估配置/业务修改，本轮没有越界修改。

planning 的 lease 锁内复查风险已口头报告，交由 Gibbs 与主 agent 处理；不要把此文档中的恢复配额检查当成该风险已解决的证据。

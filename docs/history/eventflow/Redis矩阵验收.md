# Redis 生命周期与负载矩阵验收

## 执行接口

由主 runner 先启动两个应用：`http://127.0.0.1:18191` baseline（Redis 关闭）及 `http://127.0.0.1:18192` redis（Redis 开启）。两者必须使用同一个 loopback `eventflow_it` 数据库，关闭 Kafka、background、planning worker 和缓存失效 worker。测试过程中不要有其他目录请求、重启、元数据编辑或故障注入，以便解释进程累计计数。

```sh
node scripts/cache-load-matrix.mjs
```

必需环境变量：

- `EVENTFLOW_IT_DB_URL`：`jdbc:mysql://127.0.0.1:13306/eventflow_it` 或 localhost 等价形式，可有连接选项，禁止 URL 内 user/password。
- `EVENTFLOW_TEST_TOKEN`：用户 bearer token，只有环境变量入口。
- `EVENTFLOW_ADMIN_TOKEN`：管理员 bearer token，只有环境变量入口。
- `EVENTFLOW_REPORT_DIR`：主 runner 设置为 `<run>/cache-matrix`；最终报告固定为 `results.json`，原始窗口为 `round-N.json`。

可选 `EVENTFLOW_MATRIX_URLS` 为按 baseline,redis 顺序的两个 HTTP literal loopback 地址，默认上面的端口；禁止用户信息、路径、query、fragment、重定向。可选 `EVENTFLOW_MATRIX_SECONDS` 为 1–8 的整数，默认 8，只影响阶梯，长测始终每应用 180 秒。Docker 必须使用本地 Unix socket daemon。脚本在仓库根目录通过 `docker compose exec -T mysql` 访问容器内部 loopback MySQL，只连接 `eventflow_it`；密码在容器内从现有环境读取，不作为命令行参数或输出。

请求接口：`GET /api/v1/sessions/{id}`（user token）及 `GET /api/v1/admin/catalog-statistics`（admin token）。不登录、不启动应用、不调用模型，不更改现有 read benchmark、local.sh 或故障 runner。

## 数据与矩阵

每次运行 INSERT 10,000 条缓存合成场次，250 条一批。ID 使用本次运行独立范围，普通 INSERT 遇到冲突立即失败，不覆盖他人数据。固定 seed `20260918` 决定每组请求序列和合成属性；日期取运行时次日 UTC 的 10:00–11:00，不是 AI 生成业务结果。所有场次均发布、容量 100，报告保留 ID 起点、标识和日期。行保留用于重放与审计，不自动删除，也不操作 Redis keys、FLUSHDB 或 TTL。重复执行会新增自己的数据集，负载每次只访问本次的 10,000 条。

阶梯为 hot80/uniform × 10/100/300 RPS × 3 次重复 × 两应用，共 36 个 8 秒窗口。hot80 表示 80% 概率选择固定前 100 场次，另外 20% 在全部 10,000 条中 uniform；因此总热点概率约 80.2%。每一对使用相同 seed 和请求序列，baseline/redis 顺序随重复轮次和分布交错。没有隐式预热。

另加同 hot80、100 RPS、每应用 180 秒的长测对，顺序 baseline → redis，总计 38 个窗口。长测在约 60/120 秒及末尾记录累计计数样本。纯负载目标 `36×8+2×180=648` 秒，通常总体约 11–13 分钟；全局硬截止 900 秒，主 runner 的 1,200,000 ms timeout 足够。单 HTTP 最长 5 秒，单 SQL 最长 15 秒，最多 128 个在途请求。调度落后超过 250 ms 或在途满额时记录 client_dropped，不补发积压流量。

不清 TTL，不强制热缓存。生产正缓存 TTL 为 55–65 秒，连续轮次和 180 秒长测会跨越这个时间尺度；窗口读取计数可能同时包含冷回填、TTL 到期及冷门访问，不把所有读取都归因于 TTL，也不声称长测 100% 热命中。

## 报告与判据

每个实际发送请求记录 ID、HTTP 状态、200 内容校验结果、503 retryable/Retry-After、响应延迟和计划发送偏移。客户端丢弃也逐条记录原因和调度延迟。200 必须逐字段匹配 id/title/startsAt/endsAt/city/area/priceCents/description/revision，日期按项目现有 `.SSS Z` UTC JSON 格式精确比对。不会输出 token、请求头或 Docker/MySQL stderr。

每窗口保存 all/success P95/P99、调度延迟 P95/P99、状态计数、丢弃数、原始 records；failures 与 lock503 独立列出。lock503 是所有 HTTP 503 的原始分类名称，可能包括锁竞争、目录忙、数据库或 Redis 不可用，不能仅凭状态推断都是锁竞争。成功样本与全部样本分开，不能靠丢弃或拒绝请求宣称性能提升。

每轮至少一个正确 200；所有 200 内容正确；503 必须符合 retryable=true、Retry-After=1 的契约。合法 503 或客户端丢弃本身不导致 FAIL，但必须展示数量。网络/JSON 失败、其他状态、全部拒绝、错误 200 或计数不满足边界会 FAIL。读取和命中差值非负，总和不超过 sent 且不少于 HTTP 200 数；baseline 必须 hits=0、reads=HTTP 200 数。计数回退或并行流量导致的异常会暴露出来，不伪造数据。

`databaseReads` 是已完成目录 SQL/load 的进程累计计数，`cacheHits` 也是进程累计。差值只对应目录层，不包含认证、发布状态检查或所有数据库 SQL，不能据此声称整个接口不读数据库。P95/P99 只报告真实测量结果，没有预设提升门槛。未完成的窗口也会保存已有 records，并标记 measurementComplete=false；results.json 保留已完成轮次和 FAIL。

## RedisLifecycleIT

真实测试入口为 `com.eventflow.integration.RedisLifecycleIT`；需 `EVENTFLOW_INTEGRATION=true`、IT DB URL 和现有 external profile DB/Redis 环境。数据库 URL/Redis host 必须 loopback，连接后再次验证 SELECT DATABASE()=eventflow_it。Kafka、listeners、background、planning 及失效 worker 显式关闭。预计应用上下文启动后测试本身约 8–15 秒，每项 timeout 90 秒。

- 三轮各 100 并发单 key 冷回填，统计所有正确返回及合理 503；每轮独立 CatalogService 计数必须读取 1 次，总计 3 次。暂停只发生在已完成真实 load 后，由 2 秒屏障限制，正常等待 150 ms，低于服务 3 秒 deadline。不是人为数据库 sleep。
- 24 个本次自有 key 先记录原生 55–65 秒 PTTL，再只对这些 key 设置 300 ms 批次 TTL，真实等待到期后发 100 并发，读取差值不超过唯一场次数；串行补齐后总读取差值必须等于 24。
- 不存在 ID 的 null 负缓存原生 5 秒到期；先插入数据库行验证负缓存仍独立存在，等待自然到期后读到新行，同时正缓存不失效。
- 旧 owner 被替换、租约真实到期后 successor 回填、剩余租约低于 7 秒三种边界，通过实际 CatalogService Lua 校验不能覆盖新 payload、不能删除 successor lock、低租约不能 STORE。旧请求可以返回已经读到的旧 snapshot；这里保护的是缓存写入和锁所有权，未声称对在途请求提供强一致响应。

测试证据按方法及随机 run 标识保存到 `EVENTFLOW_REPORT_DIR`（默认 `experiments/redis-lifecycle`）的独立 JSON，断言前写 requests/PTTL/owner/payload/统计。结束只删除自身分配 ID 对应的目录和锁 keys，以及 description 精确匹配本次 marker 的数据库行；不扫描、不 FLUSHDB、不删除其他实验数据。请主 agent 在 package 后串行运行真实 IT 和矩阵，避免计数污染。

## 实现建议

未修改 CatalogService。计数不是所有 SQL 的指标，HTTP 发布状态检查仍然访问数据库。旧 owner 返回旧 snapshot 的行为目前符合现有实现；若需要在途读的强一致语义，应由主 agent 单独决定，不能从本 IT 的缓存 fencing 结果推导出强一致。无真实运行时不填写或猜测提升数字。

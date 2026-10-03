# 缓存数据复核与延迟关单验收

## 原始 17,690 QPS 的复核结论

重新读取 `backend/evidence/multilevel-benchmark-1791020937633/` 的九份逐请求样本：请求数与结果记录相同，成功请求数除以实际耗时得到的 QPS 与汇总相同，重新排序计算 P95 也一致。未发现重复累加双实例请求数或毫秒/秒换算错误。

旧实验多级缓存中位数轮的平均延迟约 0.452ms，17,689.65 次/秒乘以 0.000452 秒约等于 8，与总并发 8 相符。P95 1.093ms 是尾延迟，不能用它代替平均延迟来反推吞吐。

旧实验与两组正式复跑共 27 份原始样本均重新计算通过，结果见 [raw-recalculation.json](raw-recalculation.json)。

原结果的限制：固定一个已预热商家、本机 HTTP、两个 384MiB JVM；测试令牌校验不查询登录会话数据库。目标为 `/v2/shops/{id}` 基础信息接口，不是页面使用的完整 `/v2/catalog/shops/{id}`。旧脚本只校验 HTTP 200，虽然解析了 JSON，却没有逐请求校验内容。

复核版脚本逐请求校验 HTTP 200、商家 ID、名称和描述；增加平均延迟、错误响应体计数、JAR SHA256、商家数量及登录方式记录。所有请求真实经过 HTTP 和鉴权，三种模式返回同样的业务内容。

## 正式复跑方法

1. `bash backend/scripts/verification.sh package`，构建并完成真实中间件测试。
2. 停止构建和测试后，运行 `CACHE_BENCH_SECONDS=60 bash backend/scripts/verification.sh multilevel-benchmark`：单热点、签名令牌、并发 8、三轮，每种模式每轮 60 秒。
3. 第二组使用 `CACHE_BENCH_AUTH=cookie CACHE_BENCH_SHOPS=20 CACHE_BENCH_SECONDS=30 bash backend/scripts/verification.sh multilevel-benchmark`：128 个正常登录会话、20 个商家轮询、并发 8、三轮；包括会话查库成本。

两组分开解释，不能把一组的 P95 与另一组的吞吐拼成一项结果。它们均为闭环负载测试，不代表最大容量。客户端和中间件都在本机，未做独立压测机或生产流量建模。

诊断运行 `multilevel-benchmark-1791032819072` 期间并行运行过构建和测试，不作为正式简历数字；首次沙箱启动失败也不计入性能统计。

## 单热点正式结果

原始目录：`backend/evidence/multilevel-benchmark-1791033569825/`。三轮汇总见 [hot-summary.json](hot-summary.json)，逐轮见 [hot-results.json](hot-results.json)，环境与构建摘要见 [hot-environment.json](hot-environment.json)。

| 模式 | QPS 中位数 | P95 中位数 | QPS 最低至最高 |
| --- | ---: | ---: | ---: |
| MySQL 直读 | 7,396.10 | 2.112ms | 6,717.45–7,502.57 |
| Redis TTL | 6,653.09 | 2.326ms | 5,647.00–9,750.23 |
| 多级缓存 | 15,002.78 | 1.194ms | 14,745.30–21,331.47 |

全部响应状态与内容校验通过。多级缓存相对 Redis TTL 的吞吐中位数为 2.26 倍，P95 中位数降低 48.7%。三轮波动明显，不能只选最高轮，也不能把本机结果描述为稳定生产容量。MySQL 直读对该小表热点比 Redis TTL 中位数快，不意味着数据库普遍比 Redis 快：两者客户端协议、序列化和本机负载不同。

## 正常登录、多商家对照结果

原始目录：`backend/evidence/multilevel-benchmark-1791034324777/`。见 [cookie-summary.json](cookie-summary.json)、[cookie-results.json](cookie-results.json)、[cookie-environment.json](cookie-environment.json)。准备 128 个 Cookie 会话时保留正常注册限流，遇到 429 等待后重试；这部分耗时不计入读压测。

| 模式 | QPS 中位数 | P95 中位数 | 三轮请求数 | HTTP 非 200 |
| --- | ---: | ---: | ---: | ---: |
| MySQL 直读 | 5,250.62 | 2.227ms | 479,472 | 0 |
| Redis TTL | 6,204.09 | 1.882ms | 467,122 | 5 |
| 多级缓存 | 8,396.39 | 1.707ms | 754,746 | 0 |

多级缓存相对 Redis TTL 的吞吐中位数为 1.35 倍，P95 中位数降低 9.3%。所有 HTTP 200 都通过商家内容校验。Redis TTL 第三轮有 5 次 503，三轮错误率约 0.0011%；成功 QPS 和成功 P95 均未把这些失败算入成功样本。失败未删去，不能宣称该基线零错误。旧采样未保存这 5 次错误的业务码，服务日志没有对应异常堆栈，不能据此断言具体根因。

Redis TTL 三轮 QPS 为 6,515、6,204、2,851，多级缓存为 9,031、8,396、7,730，说明同机测试仍存在明显波动。缓存指标中的 `databaseReads` 只计商家回源，不包含每次 Cookie 会话认证的 SQL；端到端响应时间包含认证。这个结果仍不是完整商家详情页，更不是生产最大容量。

简历如果使用绝对值，优先标明正常登录、多商家、三轮中位数，并能解释上述误差与失败；不要混用单热点组的较高吞吐和本组的鉴权描述。

## 支付与关单验收结果

最终完整回归：138 项测试，失败 0、错误 0、跳过 0，包含真实 MySQL、Redis、Kafka、Flyway V11 和 HTTP 测试。

最终构建还修正了多笔迟到付款的汇总状态：只有所有退款完成，支付单才显示已退款；新的迟到付款再次进入待退款。该修正后重跑完整回归和 API 演示。性能实验所测构建早于这项支付修正，但缓存和鉴权源码 SHA256 一致，见 [final-build.json](final-build.json)。

- 建单与关单任务一起提交，回滚时都不存在。
- 支付与取消并发，最终只有一个订单终态；到期付款与关单并发，只回补一次。
- 同一渠道流水重复通知不重复建单，不同重复扣款流水产生退款任务。
- 关单后付款创建退款任务；退款失败保留并退避重试。
- 模拟渠道退款已成功、本地未确认时重新执行，只有一次渠道退款记录。
- ZSet 实际投递、到期读取、处理后移除已通过真实 Redis/MySQL 验证；删除队列后数据库扫描仍可关闭订单。
- HTTP 测试覆盖用户归属、CSRF、金额不一致、绕过支付确认以及后台退款完成。

支付渠道为模拟账本，未连接第三方支付，不涉及真实资金。本轮没有改 PDF，也没有把模拟付款通知说成已接入真实签名回调。

独立演示 `bash backend/scripts/verification.sh payment-demo` 的四项检查全部通过，见 [payment-demo.json](payment-demo.json)：正常付款及重复通知、取消后付款及退款重放、后台超时关闭后退款，以及 Redis 库存收敛。10 份库存、一笔完成、两笔关闭后，Redis 剩余 9 份；库存与订单不变量均为 0 违规。

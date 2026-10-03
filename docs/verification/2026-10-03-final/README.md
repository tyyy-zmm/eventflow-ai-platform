# 最终收尾验证

日期：2026-10-03。对应本轮缓存、订单查询分片、前端入口和可观测性收尾。

## 后端

命令：`bash backend/scripts/verification.sh package`

- 125 项测试，失败 0、错误 0、跳过 0；
- 使用隔离 MySQL、Redis DB13 和验证 Kafka topic/group；
- Flyway V1-V10 在 MySQL 8.4 实际迁移；
- 覆盖订单投影回滚、重试、防倒退、自动核对修复和 Bloom 多实例并发边界。

## 缓存 A/B

命令：`bash backend/scripts/verification.sh multilevel-benchmark`

- 双 JVM，每个 384MiB，总并发 8，每模式 30 秒 × 3 轮；
- 三种模式交错执行，成功率均为 100%；
- 多级缓存 QPS 中位数 17,689.65，相对数据库直读 2.84 倍、相对 Redis TTL 2.34 倍；
- 多级缓存 P95 中位数 1.093ms，相对数据库直读降低 54.3%、相对 Redis TTL 降低 48.3%。

可复核文件：[环境](cache-environment.json)、[逐轮结果](cache-results.json)、[汇总](cache-summary.json)。原始逐请求样本体积约 171MiB，仅保存在本地忽略目录 `backend/evidence/multilevel-benchmark-1791020937633/`。

## 口径

缓存实验只测受认证的商家基础信息接口，不代表详情页、整站或秒杀下单 TPS。浏览器流程和监控结论见下节；简历只使用本页记录且能由归档文件复核的数字。

## 浏览器与监控

- Chrome 无头浏览器 9 组客户流程全部通过，错误 0；“真实异步下单与本地确认”组新增验证历史查询读模型和确认状态同步，见[结果](browser-results.json)与[页面截图](desktop-history-query.png)。
- 未携带指标令牌访问 `/actuator/prometheus` 返回 401，正确令牌返回 200；
- Prometheus 实测目标 `up{job="life-choice-backend"}=1`，可读取 `life_order_projection_pending`；
- Grafana 12.1.1 成功加载固定 UID `life-choice-reliability` 的 10 面板看板。

结构化结果见[后端测试](backend-tests.json)、[浏览器结果](browser-results.json)和[监控结果](monitoring-results.json)；本轮关键源码摘要见 [source-manifest.sha256](source-manifest.sha256)。

容器全量重新构建两次均在拉取 Maven、Node 或 Nginx 基础镜像元数据时遇到 Docker Hub EOF/TLS handshake timeout；本轮改用已经通过 Maven 构建的本地 JAR 验证 Prometheus/Grafana。`docker compose --profile observability config --quiet` 已通过，失败点位于外部镜像授权连接，并非 Dockerfile 编译步骤。

# EventFlow AI Platform

生活优选是一个面向本地活动的高并发预约与智能行程规划平台。项目将 Redis、Kafka、MySQL 和双 Agent 规划放在同一条可运行链路中，重点展示高并发准入、异步交易可靠性、跨系统最终一致性和确定性 Agent Guardrail。

## 核心能力

- Redis Lua 原子完成库存预扣、一人一单、请求幂等和入口限流；
- MySQL 条件扣减、唯一约束和本地事务提供最终交易正确性；
- Transactional Outbox + Kafka 至少一次投递，消费端以请求状态和唯一约束实现业务幂等；
- 补偿任务、条件状态机和超时对账修复预扣后宕机、建单失败、取消与过期；
- Cache-Aside、随机 TTL、owner 锁与 revision fence 治理热点商家缓存；
- Discovery/Planner 双 Agent 负责活动筛选和行程组合，Java 校验预算、时间冲突和实时余量；
- 用户注册登录、HttpOnly Session、CSRF、同源校验、订单恢复和响应丢失重放；
- Docker Compose 一键启动，Flyway 管理数据库迁移，Actuator 提供健康与 Prometheus 指标。

## 架构

```text
Browser
  -> Nginx (same-origin static + /v2 proxy)
  -> Spring Boot API
       -> Redis Lua admission / cache
       -> MySQL request + outbox transaction
       -> Kafka asynchronous order creation
       -> MySQL order transaction + compensation action
       -> Discovery Agent -> Planner Agent -> Java Validator
```

Redis 准入成功只表示请求已受理。业务成功以 MySQL 订单事务提交为准；Kafka 采用至少一次语义，不宣称跨 Redis、Kafka、MySQL 的 exactly-once。

## 快速开始

要求 Docker Desktop 或兼容的 Docker Compose。

```bash
cp .env.example .env
# 将四个 replace-with... 值替换为随机长密码
docker compose up --build
```

打开 <http://127.0.0.1:8080>。首次启动会创建演示商家和活动，可自行注册账号。Compose 默认以确定性的 `stub` 模式演示规划全链路；配置 `DEEPSEEK_API_KEY` 并将 `PLANNING_MODE=deepseek` 后使用真实模型。直接启动后端时规划默认关闭，预约功能不受影响。

停止服务使用 `docker compose down`；需要清空本地数据时使用 `docker compose down -v`。

## 本地开发

后端需要 Java 17、Maven 3.9；前端需要 Node.js 22。

```bash
docker compose up -d mysql redis kafka
cd backend && mvn spring-boot:run -Dspring-boot.run.arguments="--upgrade.demo-data=true"
cd frontend && npm ci && npm run dev
```

开发前端默认将 `/v2` 代理到 `127.0.0.1:8093`。

## 验证

```bash
cd backend && mvn test
cd frontend && npm ci && npm run build
docker compose config
```

真实 MySQL/Redis/Kafka 集成、故障和容量实验位于 `backend/scripts/`，实验口径与报告位于 `docs/`。同机压测数据用于架构对照，不能外推为生产容量。

## 生产边界

该仓库接近可部署的单机/单区域版本，但不等于已经完成商业生产上线：

- 演示环境为单 MySQL、单 Redis、单 Kafka Broker；
- 没有真实支付、退款、核销、短信和密码找回；
- 没有多可用区容灾、Redis Cluster 和 Kafka 多副本故障验证；
- 上线必须使用 HTTPS，将 `COOKIE_SECURE=true`，并接入外部密钥管理、备份、告警和网关；
- Agent 输出是只读规划建议，预约时始终重新进入标准交易链路。

更详细的上线检查见 [docs/上线准备清单.md](docs/上线准备清单.md)。

秒杀描述与当前代码的逐步映射见 [docs/秒杀链路与代码对照.md](docs/秒杀链路与代码对照.md)。

客户侧搜索、门店详情、抢购、订单确认/取消与 FIFO 候补补位的完整说明见 [docs/客户闭环与候补验收.md](docs/客户闭环与候补验收.md)。

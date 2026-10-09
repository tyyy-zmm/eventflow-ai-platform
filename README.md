# 趣票｜校园文体活动抢票平台

面向校园音乐节、话剧、赛事、讲座和社团活动，提供活动浏览、限时抢票、候补补位、模拟支付和超时关单。项目重点是热门活动缓存、限量票额秒杀及订单可靠性；活动浏览、候补和活动规划共用一个前端、一套账户和一个 Spring Boot 后端。

**这是后续唯一维护的项目。** `hm-dianping/backend-upgrade` 的已验收交易与缓存实现已合入这里。旧 EventFlow 的规划能力由本项目的 `planning` 模块承接；独立预约账户、`/api/v1` 接口和 `ef_*` 表不再作为另一套应用运行。旧目录仅供追溯，见 [合并说明](docs/项目合并说明.md)。

查阅代码说明和简历材料，请从[资料导航](docs/资料导航.md)进入。旧资料已经标注历史归属。

## 项目内容

- 活动：按分类和校区浏览活动、场次及票档；活动基础信息使用 Caffeine → Redis → MySQL，多级缓存有过期、失效通知和有界回源。
- 抢票：Redis Lua 预扣票额，MySQL 请求与 Outbox 同事务，Kafka 异步建单，幂等消费；取消与到期释放票额，补偿可重试，恢复 epoch 阻止旧请求穿过库存重建。
- 用户：Cookie 会话、CSRF、订单查询、确认、取消、丢失响应后的原请求重试。
- 延迟关单与模拟支付：Redis ZSet 到期触发、MySQL 持久化任务兜底，CAS 状态更新和事务库存回补；模拟付款重复通知及关单后付款退款。通过 `SANDBOX_PAYMENTS=true` 开启后端模拟接口，详见[流程与 API](docs/learning/06-延迟关单与模拟支付.md)。不涉及真实资金。
- 历史订单：交易库保留库存与订单的本地事务，后台将订单投影到按用户路由的 32 张查询表；版本号抵御重复与乱序，定时核对负责缺失修复。
- 候补：售罄后排队，释放票额后走同一套预占与建单规则。候补不承诺严格 FIFO，也不绕过一人一票档。
- 规划：Discovery、Planner、Review 生成校园活动安排，Java 校验时间、预算和余票。默认禁用，可用 stub 演示；建议不占票额，不代表抢票成功。
- 运维：健康检查、Prometheus、受管理员保护的库存不变量与流水线观测接口。

模拟支付不涉及真实资金或现场核销。项目按活动票档售票，每人每票档限购一次，不涉及选座；规划结果只提供建议，不提前锁票。

## 本地运行

需要 Java 17、Maven、Node.js 22+、Docker Compose。配置由脚本生成，不提交 `.env`。

```bash
bash run.sh init
bash run.sh up
bash run.sh build
bash run.sh dev
```

打开 `http://127.0.0.1:4176`。前端代理 `/v2` 到后端 `8093`；MySQL、Redis、Kafka 端口分别为 `23307`、`26380`、`29093`。本机已有中间件和依赖时，也可分别运行 `bash run.sh backend`、`bash run.sh frontend`。

`bash run.sh dev` 默认启用模拟支付，以便完整演示“抢票—支付—出票—超时关单—退款”流程，不涉及真实资金。需要关闭时可执行：

```bash
SANDBOX_PAYMENTS=false VITE_SANDBOX_PAYMENTS=false bash run.sh dev
```

前后端开关需要保持一致。开启后，待支付订单可进入模拟支付窗口，查看付款及退款状态，已关闭订单可通过“付款记录”重新查询。前端付款响应丢失时会查询同一笔付款，不创建第二笔付款通知。Vite 的开关属于构建配置，生产静态构建也需要显式设置 `VITE_SANDBOX_PAYMENTS=true`。

启用不调用外部模型的规划演示：`PLANNING_MODE=stub bash run.sh dev`。真实模型需要自行配置 `PLANNING_MODE=deepseek` 和 `DEEPSEEK_API_KEY`，本次合并验收没有调用付费模型。

容器部署：`bash run.sh init` 后运行 `docker compose up -d --build`，入口 `http://127.0.0.1:8080`。不要同时用本地后端和容器后端占用 8093。

本地观测平台使用可选 profile：

```bash
docker compose --profile observability up -d --build
```

Prometheus 默认位于 `http://127.0.0.1:29090`，Grafana 位于 `http://127.0.0.1:23000`。请在 `.env` 配置独立的 `METRICS_TOKEN` 和 `GRAFANA_ADMIN_PASSWORD`；Prometheus 使用只允许读取 `/actuator/prometheus` 的指标令牌，不能访问管理接口。

## 测试与迁移

V11 新增持久化关单任务、模拟支付流水和退款任务；现有待支付订单会补入关单任务。最新 138 项测试及缓存数据复核见[本轮验收](docs/verification/2026-10-03-cache-payment-audit/README.md)。

```bash
bash run.sh test    # 单元测试；外部中间件测试跳过
bash run.sh verify  # 完整后端测试，使用独立验收数据库和 Redis DB13
```

`verify` 需要先启动本项目中间件；数据库为 `life_choice_verification`，Kafka topic/group 为 `life-choice-verification-v1`。不清空业务库。浏览器验收见 [合并验收](docs/合并验收.md)。

数据库沿用原项目 V1–V6；V7 增加库存 epoch，V8–V10 增加订单读模型、Bloom 重建互斥和投影展示字段。不能把旧 `backend-upgrade` 的数据库直接接入本项目：它的 V5 与本项目 V5 内容不同。已有活动若包含订单而没有 epoch，需要管理员显式调用 `POST /v2/admin/activities/{id}/recover`；启动时不会自动重置该活动票额。

## 目录

```text
backend/         后端、数据库迁移、测试、实验脚本
frontend/        统一前端，根路径 /，只有一套登录
infra/           部署配置
compose.yaml     本项目中间件与前后端
run.sh           统一运行入口
docs/            当前说明；history/ 保存历史设计记录
evidence/        本次验收；history/ 保存合并前实验结果
```

本次代码验收与旧性能数据分开记录。历史缓存 2.20 倍、40 请求/秒持续 15 分钟等数据仍属于原测量版本，不能直接称作合并版的性能结果。

## 对照代码学习

缓存、查询分片、ID 选型、Kafka 可靠性及验证边界见 [五章学习说明](docs/learning/README.md)。

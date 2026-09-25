# Contributing

1. 从新分支提交变更，不提交 `.env`、构建产物或实验原始数据。
2. 后端运行 `mvn test`，前端运行 `npm ci && npm run build`。
3. 涉及库存、幂等或补偿时，必须补充失败重试和重复执行测试。
4. 数据库变更只新增 Flyway migration，不修改已经发布的 migration。
5. PR 说明应包含行为变化、验证命令和兼容性影响。

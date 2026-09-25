# Security Policy

请勿在公开 Issue 中提交密钥、密码、Cookie、个人数据或生产日志。安全问题请通过仓库维护者提供的私密联系方式报告。

部署时必须替换 `.env.example` 中的所有占位值，启用 HTTPS 和 `COOKIE_SECURE=true`，限制 Actuator/Prometheus 的网络访问，并将数据库、Redis、Kafka 放在私有网络。该演示项目不处理真实支付信息。

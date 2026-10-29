# ADR-0000：技术版本基线

- 状态：已锁定
- 日期：2026-10-29
- 复核点：W16（2027-02，MVP 前）、W19（AI 开工前）

## 决策

| 层 | 组件 | 版本 | 管理方式 | 引入时点 |
|---|---|---|---|---|
| 运行时 | JDK | 21.0.8 | 本机 | 已就绪 |
| 构建 | Maven | 3.9.12 | Wrapper | 已就绪 |
| 框架 | Spring Boot | 4.1.2 | 显式 parent | SC-0103 |
| 框架 | Spring Framework | 7.0.10 | Boot BOM | SC-0103 |
| 安全 | Spring Security | 7.1.2 | Boot BOM | SC-0401 |
| JSON | Jackson | 3.1.7 | Boot BOM | SC-0103 |
| 测试 | JUnit Jupiter | 6.0.3 | Boot BOM | SC-0103 |
| 持久化 | MyBatis Spring Boot Starter | 4.1.0 | 显式 | SC-0204 |
| 迁移 | Flyway core + flyway-mysql | 12.4.0 | Boot BOM | SC-0203 |
| 驱动 | MySQL Connector/J | 9.7.0 | Boot BOM | SC-0203 |
| 数据库 | MySQL Server | 8.4 LTS | Docker 镜像 | SC-0105 |
| 测试 | Testcontainers | 2.0.5 | Boot BOM | SC-0206 |
| AI | Spring AI BOM | 2.0.2 | 显式 | 已锁定，W19 引入 starter |
| AI | Qdrant starter | 2.0.2 | Spring AI BOM | W21 |
| 消息 | spring-boot-starter-amqp | Boot 托管 | Boot BOM | W14 |
| 缓存 | spring-boot-starter-data-redis | Boot 托管 | Boot BOM | W13 |
| 观测 | spring-boot-starter-actuator | Boot 托管 | Boot BOM | W18 |

## 选择理由

- Spring Boot 4.1.2 是当前稳定 GA；4.2.0-RC1 为预览版，不用于本项目。
- Spring AI 2.0.x 官方支持 Spring Boot 4.0.x / 4.1.x，2.0.2 为最新 GA。
- MyBatis Spring Boot Starter 4.1.0 已适配 Spring Boot 4.1，无需退回 Boot 3.x。
- Flyway 使用 Boot 托管版本 12.4.0，不手动追 13.x 最新版。

## 验证记录

- 2026-10-29：对 Maven Central 上所有上述坐标执行 HEAD 校验，全部返回 200。
- 2026-10-29：`./mvnw test` 通过（Tests run: 1, Failures: 0, Errors: 0）。

## 使用约束

- W16 之前不升级 Spring Boot 4.2 / Flyway 13，避免 MVP 前引入升级风险。
- 引入 MyBatis、Flyway、Redis、RabbitMQ、Spring AI 的工单中，需再次运行完整测试。
- Boot 4 关键差异：Web starter 为 `spring-boot-starter-webmvc`；Jackson 3 使用 `tools.jackson.*`；JUnit 6 使用 `@MockitoBean`；Testcontainers 2 坐标带 `testcontainers-` 前缀。

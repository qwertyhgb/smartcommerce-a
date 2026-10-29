# SmartCommerce AI

[![CI](https://github.com/qwertyhgb/smartcommerce-a/actions/workflows/ci.yml/badge.svg)](https://github.com/qwertyhgb/smartcommerce-a/actions/workflows/ci.yml)

Java 21 + Spring Boot 4.1.2 模块化单体电商后端，AI 售后能力分阶段接入。

开发协作规则见 [AGENTS.md](AGENTS.md)。

当前进度见 [STATUS.md](STATUS.md)，每日记录与工单账本见 [docs/progress/](docs/progress/)，计划快照见 [docs/plan/](docs/plan/)。

## 技术基线

- JDK 21 / Maven 3.9.12（Maven Wrapper 锁定）
- Spring Boot 4.1.2 / Spring Framework 7.0.10
- Spring AI BOM 2.0.2（W19 引入 starter）
- MyBatis Spring Boot Starter 4.1.0 / MySQL 8.4 LTS / Flyway 12.4.0（W2 引入）

完整版本矩阵与复核点见 [docs/adr/0000-technology-baseline.md](docs/adr/0000-technology-baseline.md)。

## 快速开始

```bash
./mvnw test
./mvnw spring-boot:run
```

## 本地基础设施

首次使用先准备本地环境变量（`.env` 已被 Git 忽略）：

```bash
cp .env.example .env
```

按工单阶段启动，默认只启动 MySQL：

```bash
# W1：MySQL
docker compose --env-file .env -f infra/compose.yaml up -d --wait

# W13：+ Redis
docker compose --env-file .env -f infra/compose.yaml --profile cache up -d --wait

# W14：+ RabbitMQ（管理台 http://localhost:15672）
docker compose --env-file .env -f infra/compose.yaml --profile cache --profile mq up -d --wait

# W21：+ Qdrant
docker compose --env-file .env -f infra/compose.yaml --profile cache --profile mq --profile vector up -d --wait
```

MySQL 监听 `127.0.0.1:3306`，数据卷 `smartcommerce_mysql-data`；Redis 不持久化，RabbitMQ 与 Qdrant 使用独立命名卷。

## 当前阶段

W1 工程化：可复现启动与基础测试流程。业务模块按开发手册工单逐步加入。

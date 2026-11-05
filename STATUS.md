# 项目状态

最后更新：2026-11-05

## 当前

- 阶段：W1 进行中（2026-11-02 ~ 11-08）
- 当前工单：无（SC-0102 已于 11-05 完成）
- 下一工单：SC-0103（计划 2026-11-04，Must，1.5h）Java 21 模块化单体仓库与版本矩阵
- 最近完成：SC-0102 C4 上下文图与个人信息流向图（交付 `569802b`，账本随本次提交）；SC-0101 PRD 定稿 + 复核勘误闭环（`fa61ce7`，勘误 `64c78a8`、`54a4fc9`、`1fc072c`）
- 远程仓库：https://github.com/qwertyhgb/smartcommerce-a （main）
- 账本：`docs/progress/tickets.csv`（238 行，18 列，2 DONE / 236 TODO）
- 今日记录：`docs/progress/daily/2026-11-05.md`（含 11-04 补录与 SC-0102 关单）
- 阻塞：无

## 环境

- JDK 21.0.8；Maven Wrapper 3.9.12
- CI：`.github/workflows/ci.yml`（push/PR 自动跑 + 手动触发 `./mvnw test`）
- MySQL：`smartcommerce-mysql`，127.0.0.1:3306，healthy（8.4.11）
- Redis / RabbitMQ / Qdrant：compose 已定义，未启动
- 最近验证：2026-11-03 `./mvnw -B -ntp test` 通过（1 个测试，0 失败）

## 最近决策

- 版本基线：`docs/adr/0000-technology-baseline.md`
- MVP 范围：`docs/adr/0001-mvp-scope.md`（Accepted；2026-11-03 增补变更记录：订单列表接口、售后回退边）
- 产品需求：`docs/prd/README.md` + `docs/prd/non-goals.md`（SC-0101 产出，已定稿）
- 系统上下文：`docs/architecture/context.md`（SC-0102 产出；W16/W19+ 目标态信任边界，非已实现清单）
- 排期规则：`docs/progress/schedule-policy.md`（`planned_date` 不是截止日期，支持 DEFERRED）
- 进度账本：`docs/progress/README.md`
- 计划快照：`docs/plan/`

## 下一步

1. SC-0103：Java 21 模块化单体仓库与版本矩阵（骨架与版本矩阵已于 10-29 落地，先按验收标准复核账本与代码是否一致）
2. 每张工单按 AGENTS.md 更新 STATUS/daily/CSV 并提交；周复盘时把 CSV 同步到 Excel 看板

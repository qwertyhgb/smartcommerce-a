# 进度账本

目标：任何 AI 工具或协作者打开仓库，都能知道哪些工单完成、每天做了什么、下一步是什么。

## 文件

- `docs/progress/tickets.csv`：238 张工单账本，来自计划手册第 7 章；AI 主要读写这里。
- `docs/progress/daily/YYYY-MM-DD.md`：每日记录（只在有工作时写）。
- `docs/progress/schedule-policy.md`：提前、延后、`DEFERRED` 的排期规则。
- `docs/progress/handoff-prompt.md`：换 AI 工具时复制的提示词。
- `STATUS.md`：当前状态快照，每次会话结束更新。
- `docs/plan/`：计划手册和 Excel 的只读快照。

## 列说明

- `planned_date`：建议日期，不是截止日期。
- `status`：`TODO` / `DOING` / `BLOCKED` / `DEFERRED` / `DONE`。
- `actual_start` / `actual_done`：实际开始和完成日期。
- `evidence`：测试结果、命令或文档路径；`DONE` 必填。
- `commit`：完成提交的哈希；`DONE` 必填。
- `notes`：延期原因、阻塞原因、决策或遗留问题。

## 状态定义

- `TODO`：未开始
- `DOING`：进行中
- `BLOCKED`：阻塞，`notes` 写原因和下一步
- `DEFERRED`：主动延期，`notes` 写原因和计划周
- `DONE`：已完成，必须填 `evidence`、`commit`、`actual_done`

## 每张工单

- 开始：`status=DOING`，填 `actual_start`。
- 完成：`status=DONE`，填 `actual_done`、`evidence`、`commit`。
- 延期：`status=DEFERRED`，`notes` 写原因和计划周。
- 只改状态没有证据，视为未完成。

## 排期

- 计划日期不是截止日期；允许提前和延后，具体见 `schedule-policy.md`。
- 按周结算 10h 项目预算，不按天考核。
- 提前的前提是前置依赖已完成且测试通过；不要跳过依赖。
- 延后 Must 时用 `DEFERRED` 记录，不伪造 `actual_done`。
- 每周复盘一次；连续两周落后就按 ADR-0001 砍 Should/Could，不砍 Must、测试、鉴权、库存正确性。

## 每天

- 开始：读 `STATUS.md`、CSV 中未完成项、最近 3 天 daily、`git log -10 --oneline`。
- 结束：更新 `STATUS.md`、追加当天 daily、更新 CSV、提交。
- 没有工作的一天不需要补 daily 记录；不要为了“连续”伪造记录。
- 每日记录模板：

````markdown
# YYYY-MM-DD（周X）

## 工单
- SC-xxxx（优先级，工时）：...

## 完成
- ...

## 验证
- 命令 → 结果

## 决策/风险
- ...

## 下一步
- ...
````

## 每周

把 CSV 同步到 `docs/plan/SmartCommerce_AI_v2_238_Day_Project_Tracker.xlsx` 的对应行；Release Gate 通过后打 tag，例如 `w01-gate`。每周复盘完成、延期、阻塞，再排下周。

## 生成说明

`tickets.csv` 于 2026-10-29 从 `docs/plan/SmartCommerce_AI_v2_Development_Manual_238_Days.md` 第 7 章自动解析生成，共 238 行、18 列，初始状态全部为 `TODO`；`actual_start` / `actual_done` 为空。如果计划手册更新，重新生成该文件，不要手工改动 id/planned_date/title 等结构字段。

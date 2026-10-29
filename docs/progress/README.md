# 进度账本

目标：任何 AI 工具或协作者打开仓库，都能知道哪些工单完成、每天做了什么、下一步是什么。

## 文件

- `docs/progress/tickets.csv`：238 张工单账本，来自计划手册第 7 章；AI 主要读写这里。
- `docs/progress/daily/YYYY-MM-DD.md`：每日记录。
- `docs/progress/handoff-prompt.md`：换 AI 工具时复制的提示词。
- `STATUS.md`：当前状态快照，每次会话结束更新。
- `docs/plan/`：计划手册和 Excel 的只读快照。

## 状态定义

- `TODO`：未开始
- `DOING`：进行中
- `BLOCKED`：阻塞，`notes` 写原因
- `DONE`：已完成，必须填 `evidence` 和 `commit`

## 每张工单

- 开始前：把 `status` 改为 `DOING`。
- 完成后：`status=DONE`，`evidence` 填测试结果、命令或文档路径，`commit` 填提交哈希，`notes` 填决策或遗留问题。
- 只改状态没有证据，视为未完成。

## 每天

- 开始：读 `STATUS.md`、CSV 中未完成项、最近 3 天 daily、`git log -10 --oneline`。
- 结束：更新 `STATUS.md`、追加当天 daily、更新 CSV、提交。
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

把 CSV 同步到 `docs/plan/SmartCommerce_AI_v2_238_Day_Project_Tracker.xlsx` 的对应行；Release Gate 通过后打 tag，例如 `w01-gate`。

## 生成说明

`tickets.csv` 于 2026-10-29 从 `docs/plan/SmartCommerce_AI_v2_Development_Manual_238_Days.md` 第 7 章自动解析生成，共 238 行，初始状态全部为 `TODO`。如果计划手册更新，重新生成该文件，不要手工改动 id/date/title 等结构字段。

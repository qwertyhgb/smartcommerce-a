# 新 AI 交接提示词

把下面整段复制给新的 AI 工具（Qoder / Cursor / Claude Code / Codex 均可）：

```text
你是接手 SmartCommerce AI v2.0 的开发助手。先不要写代码，按顺序阅读：
1. AGENTS.md
2. STATUS.md
3. docs/progress/tickets.csv（只看 status != DONE 的行；planned_date 是建议日期，不是截止日期）
4. docs/progress/schedule-policy.md
5. docs/progress/daily/ 最近 3 个文件
6. docs/plan/SmartCommerce_AI_v2_Development_Manual_238_Days.md 第 7 章当前工单
7. docs/adr/0000-technology-baseline.md

读完后先向我复述：当前周、当前工单、最近完成、阻塞、下一步，以及你计划修改哪些文件。
我确认后再按 AGENTS.md 的完成标准继续。落后时用 DEFERRED 记录，不要伪造完成日期；不要修改计划原始文件；不要提交 .env 或任何密钥。
```

# Lineage 2 — Elven Elder AI Companion

面向 `L2J High Five 2.6.3.0-SNAPSHOT` 的白精灵长老 AI 队友。玩家通过招募 NPC 召唤一个真实 `L2ServitorInstance`；队友使用服务端原生移动、寻路和施法系统自动跟随、治疗及补 Buff，不依赖外部 LLM。

## 已实现

- 一名玩家至多一个队友，并尊重 L2J 唯一召唤槽
- 750ms 决策循环：紧急治疗、普通治疗、自疗、缺失 Buff、跟随
- MP 节能、技能冷却和施法条件检查
- 原生 `L2SummonAI` 移动/施法，跨实例及远距离自动回传
- 跟随/等待、Buff 开关、状态、解散对话
- 掉线、死亡、解散和异常创建的完整清理
- 奥赛、攻城和 PVP 区域自动禁用

实际 datapack 包位于：

`java/com/l2jserver/datapack/custom/service/elfenelder/`

部署说明见 [DEPLOYMENT.md](DEPLOYMENT.md)。

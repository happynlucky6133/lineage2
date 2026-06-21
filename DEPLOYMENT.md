# AiNPC — Elven Elder Companion

L2J High Five 服务端修改，为游戏添加可招募的白精灵长老（Elven Elder）AI 队友。
队友自动跟随玩家，在 PvE 中判断战况、治疗主人、补充辅助魔法。

## 文件结构

```
java/com/lineage2/elfenelder/
├── config/ElvenElderConfig.java      — 配置常量（治疗阈值、跟随距离、技能 ID 占位等）
├── service/ElvenElderService.java    — 招募/解散生命周期、玩家登出清理、全局开关
├── model/ElvenElderCompanion.java    — 队友实体（位置追踪、跟随逻辑、传送恢复）
└── brain/ElvenElderBrain.java        — AI 状态机（治疗、buff、禁用区域检测、卡住恢复）

html/npc/
├── recruit_elven_elder.html          — NPC 对话主页（招募/状态/解散）
├── recruit_elven_elder_status.html   — 队友状态面板
└── recruit_elven_elder_dismiss.html  — 解散确认页
```

## 部署步骤

### 1. 复制源码

将 `java/com/lineage2/elfenelder/` 整个目录复制到 L2J High Five 服务端源码的 `gameserver/src/main/java/com/lineage2/` 下。

### 2. 环境变量

在启动脚本中设置：

```bash
export AI_COMPANION_ENABLED=true
```

设为 `false` 可关闭整个 AI 队友系统。

### 3. 填写 TODO ID（必须）

以下 ID 在 `ElvenElderConfig.java` 中全部设为 `0`，部署前必须核对游戏数据文件并填入正确值：

| 常量 | 含义 | 查找位置 |
|------|------|---------|
| `COMPANION_DISPLAY_ID` | NPC 外观显示 ID | `npcdata/npcname-cn.dat` 或 SQL `npc` 表 |
| `COMPANION_APPEARANCE_TEMPLATE_ID` | NPC 模型模板 ID | SQL `npc` 表 `templateId` 字段 |
| `HEAL_SKILL_ID_EMERGENCY` | 紧急治疗技能 ID | SQL `skills` 表 |
| `HEAL_SKILL_ID_NORMAL` | 普通治疗技能 ID | SQL `skills` 表 |
| `SELF_HEAL_SKILL_ID` | 自疗技能 ID | SQL `skills` 表 |
| `BUFF_WHITELIST` | 辅助魔法技能 ID 列表 | SQL `skills` 表（白精灵长老可用 buff） |

> **重要：** 不要用占位值上线，否则技能无法生效，NPC 无法正常显示。

### 4. NPC 对话 HTML

将 `html/npc/` 目录复制到 L2J High Five 服务端的 `gameserver/data/html/npc/` 下。

招募 NPC 需要在 NPC 的 handler 中对接 bypass 命令 `npc_recruit_elven_elder`。参考 `recruit_elven_elder.html` 中的 TODO 注释修改。

### 5. 编译

使用 L2J High Five 的标准编译流程（Maven 或 Ant）。确保 `elfenelder/` 包在 classpath 中。

## 启用/禁用

| 方式 | 效果 |
|------|------|
| 环境变量 `AI_COMPANION_ENABLED=false` | 完全关闭，拒绝所有招募请求 |
| 玩家对话「关闭辅助」 | 停止 buff 和自动治疗（仍需手动判断） |
| 玩家对话「解除同行」 | 解散当前队友 |

## TODO 占位符清单

以下功能因依赖 L2J 核心 API，目前标记为 TODO：

- `ElvenElderCompanion.java`
  - `dismiss()` 中的 NPC 删除 / 取消调度
  - `isValidPosition()` 的碰撞检测 / 可行走区域
  - `findLegalSpawnPosition()` 的螺旋搜索
  - `detectCrossInstanceTeleport()` 中的实例 ID 获取
  - `moveTowardsOwner()` 中的 L2J 移动 API 调用
  - `safeTeleportBack()` 中的 L2J 传送 API 调用

- `ElvenElderService.java`
  - `isInDisabledScenario()` 的区域检测
  - `recruit()` 中获取玩家实体和位置的代码

- `ElvenElderBrain.java`
  - `getOwnerHpPercent()` / `getCompanionHpPercent()` / `getCompanionMpPercent()`
  - `hasEnoughMp()` / `getSkillLevel()`
  - `tryBuff()` / `tryFollow()` 中的技能施放和移动
  - `isOwnerInDisabledScenario()` 的 ZoneManager 检测

## Phase 计划

| Phase | 内容 | 状态 |
|-------|------|------|
| 1 | Config + Service 骨架 | ✅ |
| 2 | Companion 实体 + 跟随 | ✅ |
| 3 | Brain 状态机 + 治疗决策 | ✅ |
| 4 | Buff 管理 + 禁用区域 + 传送恢复 | ✅ |
| 5 | NPC 招募 HTML + 部署文档 | ✅ |

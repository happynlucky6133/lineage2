# lineage2 — AiNPC

L2J High Five 服务端 AI 白精灵长老队友系统。

玩家可与招募 NPC 对话，邀请白精灵长老同行。队友自动跟随、在 PvE 中判断主人血量进行治疗、补充辅助魔法。AI 逻辑在服务端以确定性状态机实现，不依赖外部 LLM。

## 文件一览

- `java/com/lineage2/elfenelder/` — 服务端 Java 代码（Config / Service / Companion / Brain）
- `html/npc/` — 游戏内 NPC 对话 HTML 脚本
- `DEPLOYMENT.md` — 部署配置文档

## 快速开始

```bash
# 1. 复制 Java 源码到 L2J High Five 项目
cp -r java/com/lineage2/elfenelder/ /path/to/gameserver/src/main/java/com/lineage2/

# 2. 复制对话 HTML 到服务端
cp -r html/npc/ /path/to/gameserver/data/html/npc/

# 3. 启动时开启
export AI_COMPANION_ENABLED=true

# 4. 编译并启动游戏服务器
```

> 部署前必须先填写 `ElvenElderConfig.java` 中的技能 ID 和 NPC 外观 ID（当前为 TODO 占位符）。详见 `DEPLOYMENT.md`。

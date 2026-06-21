# 部署说明

目标版本：L2J High Five `l2j-server-game 2.6.3.0-SNAPSHOT`，JDK 25。

## 文件映射

- `*.java` → `server/game/script/com/l2jserver/datapack/custom/service/elfenelder/`
- `custom_elfenelder.xml` → `server/game/data/stats/npcs/custom/`
- `elfenelder_spawn.xml` → `server/game/data/spawnlist/elfenelder.xml`
- 在 `server/game/data/scripts.cfg` 注册 `com/l2jserver/datapack/custom/service/elfenelder/ElvenElderRecruitAI.java`

招募 NPC ID 为 `60005`，队友模板 ID 为 `60006`。当前在 Giran 和 Aden 各生成一名招募 NPC。

## 启动要求

功能默认关闭，部署环境需显式设置：

```text
ELVEN_ELDER_ENABLED=true
```

datapack 以 loose classes 动态加载，GameServer 必须把 `script` 放进运行时 classpath：

```sh
java -Xms512m -Xmx2g \
  -cp "l2jserver.jar:script:libs/*" \
  com.l2jserver.gameserver.GameServer
```

使用 `java -jar l2jserver.jar` 会让 `AILoader` 在运行时找不到 `AbstractNpcAI` 等 datapack 基类。

## 验证基线

2026-06-21 已在 `192.168.9.10` 的 Docker GameServer 实机验证：

- 四个 Java 文件以 JDK 25 和实际服务器 classpath 编译通过
- 597 个 AI 脚本加载成功
- `ElvenElderRecruitAI` 与 `ElvenElderCompanionManager` 已由 JVM 实例化
- 13 个 custom NPC 和 XML spawn 无解析错误
- GameServer 注册到 LoginServer，并监听 `7777`

部署前应先备份启动脚本、`scripts.cfg`、NPC/spawn XML 和已有脚本目录。

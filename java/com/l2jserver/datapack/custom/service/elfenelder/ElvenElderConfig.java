/*
 * Copyright © 2004-2026 L2J Server
 *
 * This file is part of L2J Server.
 *
 * L2J Server is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License.
 */
package com.l2jserver.datapack.custom.service.elfenelder;

import java.util.Set;

/**
 * Central configuration for the Elven Elder companion system.
 */
public final class ElvenElderConfig
{
	// Feature toggle
	public static final boolean COMPANION_ENABLED = Boolean.parseBoolean(System.getenv().getOrDefault("ELVEN_ELDER_ENABLED", "false"));

	// NPC IDs
	public static final int RECRUITER_NPC_ID = 60005; // 招募 NPC
	public static final int COMPANION_NPC_ID = 60006; // 队友实体外观

	// Healing thresholds
	public static final double HEAL_EMERGENCY_THRESHOLD = 0.35;   // 紧急治疗: owner HP < 35%
	public static final double HEAL_NORMAL_THRESHOLD = 0.70;      // 普通治疗: owner HP < 70%
	public static final double SELF_HEAL_THRESHOLD = 0.55;         // 自疗: companion HP < 55%

	// MP conservation
	public static final double MP_CONSERVATION_THRESHOLD = 0.20;   // MP < 20% 进入节能模式

	// Movement
	public static final int FOLLOW_DISTANCE_MIN = 80;
	public static final int FOLLOW_DISTANCE_MAX = 250;
	public static final int RETURN_DISTANCE_THRESHOLD = 1200;
	public static final int TELEPORT_RETURN_RADIUS = 150;

	// Tick interval
	public static final int TICK_INTERVAL_MS = 750;

	// Per-player limit
	public static final int MAX_COMPANIONS_PER_PLAYER = 1;

	// Buff settings
	public static final long BUFF_COOLDOWN_MS = 5000;

	// Path failure limit
	public static final int MAX_PATH_FAILURES = 3;

	// Skill IDs — Elven Elder class (verified against data/skillTrees/classSkillTree.xml)
	// Heals
	public static final int HEAL_SKILL_ID = 1011;           // Heal (基础单奶)
	public static final int BATTLE_HEAL_SKILL_ID = 1015;    // Battle Heal (主力单奶)
	public static final int GREATER_HEAL_SKILL_ID = 1217;   // Greater Heal (强力单奶)
	public static final int GROUP_HEAL_SKILL_ID = 1027;     // Group Heal (群奶)
	public static final int REGENERATION_SKILL_ID = 1044;   // Regeneration (持续回血)
	public static final int RECHARGE_SKILL_ID = 1013;       // Recharge (回蓝)

	// Buffs — whitelist for auto-buff
	public static final int[] BUFF_WHITELIST = {
		1068,  // Might
		1040,  // Shield
		1035,  // Mental Shield
		1044,  // Regeneration
		1078,  // Concentration
		1204,  // Wind Walk
		1087,  // Agility
		1033,  // Resist Poison
		1043,  // Blessed Body
	};

	// Disabled scenarios
	public static final Set<String> DISABLED_SCENARIOS = Set.of(
		"olympiad",
		"arena_pvp",
		"castle_siege",
		"contest_instance"
	);

	// Heal skill selection: emergency = strongest, normal = economical
	public static final int getEmergencyHealSkillId()
	{
		return GREATER_HEAL_SKILL_ID;
	}

	public static final int getNormalHealSkillId()
	{
		return BATTLE_HEAL_SKILL_ID;
	}

	public static final int getSelfHealSkillId()
	{
		return HEAL_SKILL_ID;
	}

	private ElvenElderConfig()
	{
		// utility class
	}
}

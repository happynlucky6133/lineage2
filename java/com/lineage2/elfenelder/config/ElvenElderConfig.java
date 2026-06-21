/*
 * This program is free software; you can redistribute it and/or modify it under
 * the terms of the GNU General Public License version 2.
 *
 * ElvenElder Configuration — Phase 1 skeleton
 *
 * All tunable constants for the Elven Elder AI companion system.
 * Values here are deliberately kept as hardcoded defaults; a future config
 * loader (properties / DB) can replace them.
 *
 * NOTE: displayId, appearance template ID, and skill IDs referenced in
 * comments throughout the project must be verified against the official
 * L2J High Five data files before production deployment.
 */
package com.lineage2.elfenelder.config;

import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Central configuration holder for the Elven Elder companion system.
 *
 * <p>Reads the {@code AI_COMPANION_ENABLED} environment variable at class-load
 * time to gate the entire feature.  All remaining thresholds are final so that
 * they can be inlined by the JIT where possible.</p>
 *
 * @author hamann (Phase 1)
 */
public final class ElvenElderConfig
{
	private static final Logger _log = Logger.getLogger(ElvenElderConfig.class.getName());

	// ------------------------------------------------------------------------
	// Feature toggle
	// ------------------------------------------------------------------------

	/**
	 * Master switch.  Read from environment variable {@code AI_COMPANION_ENABLED}.
	 * Default: {@code true}.  When {@code false} the service registers no entry
	 * points and refuses all recruit requests.
	 */
	public static final boolean AI_COMPANION_ENABLED;

	static
	{
		String envVal = System.getenv("AI_COMPANION_ENABLED");
		if (envVal == null)
		{
			AI_COMPANION_ENABLED = true;
			_log.info("ElvenElderConfig: AI_COMPANION_ENABLED not set, defaulting to TRUE.");
		}
		else
		{
			AI_COMPANION_ENABLED = Boolean.parseBoolean(envVal);
			_log.info("ElvenElderConfig: AI_COMPANION_ENABLED = " + AI_COMPANION_ENABLED);
		}
	}

	// ------------------------------------------------------------------------
	// Healing thresholds
	// ------------------------------------------------------------------------

	/**
	 * Emergency heal trigger: companion heals owner when owner HP < 35 %.
	 */
	public static final float HEAL_EMERGENCY_THRESHOLD = 0.35f;

	/**
	 * Normal heal trigger: companion heals owner when owner HP < 70 %.
	 */
	public static final float HEAL_NORMAL_THRESHOLD = 0.70f;

	/**
	 * Self-heal trigger: companion uses self-heal when its own HP < 55 % AND
	 * the owner is not in emergency state.
	 */
	public static final float SELF_HEAL_THRESHOLD = 0.55f;

	// ------------------------------------------------------------------------
	// MP conservation
	// ------------------------------------------------------------------------

	/**
	 * When companion MP drops below this fraction (20 %) only emergency heals
	 * are performed — normal heals and self-heals are skipped to conserve MP.
	 */
	public static final float MP_CONSERVATION_THRESHOLD = 0.20f;

	// ------------------------------------------------------------------------
	// Movement & positioning
	// ------------------------------------------------------------------------

	/**
	 * Comfortable follow-distance range (in L2 units).
	 * Companion attempts to stay within [FOLLOW_DISTANCE_MIN, FOLLOW_DISTANCE_MAX].
	 */
	public static final int FOLLOW_DISTANCE_MIN = 80;

	/**
	 * Upper bound of comfortable follow distance.
	 */
	public static final int FOLLOW_DISTANCE_MAX = 250;

	/**
	 * Return-to-owner threshold.  If the companion is farther than this value
	 * from the owner it will attempt to teleport back (after verifying a legal
	 * spawn position).
	 */
	public static final int RETURN_DISTANCE_THRESHOLD = 1200;

	// ------------------------------------------------------------------------
	// Tick interval
	// ------------------------------------------------------------------------

	/**
	 * Companion tick interval in milliseconds.  Range: 500–1000 ms.
	 */
	public static final int TICK_INTERVAL_MS = 750; // midpoint of recommended range

	// ------------------------------------------------------------------------
	// Per-player limits
	// ------------------------------------------------------------------------

	/**
	 * Maximum number of AI companions per player.  Enforced at 1.
	 */
	public static final int MAX_COMPANIONS_PER_PLAYER = 1;

	// ------------------------------------------------------------------------
	// Disabled scenarios (set of instance-type identifiers)
	// ------------------------------------------------------------------------

	/**
	 * Scenarios in which the companion must be safely dismissed:
	 * <ul>
	 *   <li>Olympiad Arena</li>
	 *   <li>Arena / PvP Events</li>
	 *   <li>Castle Siege</li>
	 *   <li>Contest / Event Instances</li>
	 * </ul>
	 *
	 * The actual detection logic maps these identifiers to L2J region/zone IDs.
	 * These strings are used as keys in the zone-checker.
	 */
	public static final Set<String> DISABLED_SCENARIOS = Set.of(
		"olympiad",
		"arena_pvp",
		"castle_siege",
		"contest_instance"
	);

	// ------------------------------------------------------------------------
	// Identity placeholders — MUST be verified against game data
	// ------------------------------------------------------------------------

	/**
	 * TODO: Verify / fill in the following IDs before production deployment.
	 *
	 * <ul>
	 *   <li>{@code COMPANION_DISPLAY_ID} — NPC display ID shown in-game</li>
	 *   <li>{@code COMPANION_APPEARANCE_TEMPLATE_ID} — appearance / model ID</li>
	 *   <li>{@code HEAL_SKILL_ID_EMERGENCY} — emergency heal skill ID</li>
	 *   <li>{@code HEAL_SKILL_ID_NORMAL}   — normal heal skill ID</li>
	 *   <li>{@code SELF_HEAL_SKILL_ID}     — companion self-heal skill ID</li>
	 * </ul>
	 */
	public static final int    COMPANION_DISPLAY_ID                  = 0; // TODO: verify
	public static final int    COMPANION_APPEARANCE_TEMPLATE_ID      = 0; // TODO: verify
	public static final int    HEAL_SKILL_ID_EMERGENCY               = 0; // TODO: verify
	public static final int    HEAL_SKILL_ID_NORMAL                  = 0; // TODO: verify
	public static final int    SELF_HEAL_SKILL_ID                    = 0; // TODO: verify

	// ------------------------------------------------------------------------
	// Private constructor — utility class
	// ------------------------------------------------------------------------

	private ElvenElderConfig()
	{
		// prevent instantiation
	}
}

/*
 * This program is free software; you can redistribute it and/or modify it under
 * the terms of the GNU General Public License version 2.
 *
 * ElvenElder Service — Phase 1 skeleton
 *
 * Manages the lifecycle of AI companions (per-player singleton).
 *
 * Responsibilities:
 *   • Recruit — player talks to the Elder NPC, creates & binds companion
 *   • Dismiss — player dismisses companion, cleans up entity & timers
 *   • Logout cleanup — auto-dismiss on player logout, no orphan companions
 *   • Restart cleanup — no residual state survives a server restart
 *   • Feature gate — respects AI_COMPANION_ENABLED env var
 *   • Disabled-scenario detection — safe dismiss when owner enters forbidden zones
 *
 * NOTE: No combat decision / AI bridge code belongs here — that is a later phase.
 *       All references to L2J core APIs are illustrative; adapt to the actual
 *       High Five source tree as needed.
 */
package com.lineage2.elfenelder.service;

import com.lineage2.elfenelder.brain.ElvenElderBrain;
import com.lineage2.elfenelder.config.ElvenElderConfig;
import com.lineage2.elfenelder.model.ElvenElderCompanion;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Central service for Elven Elder companion management.
 *
 * <p>This is a singleton-like holder keyed by {@code activeId}.  Each active
 * companion gets its own {@link CompanionInstance} which owns the companion
 * character reference, the periodic tick task handle, and the owner link.
 * References are cleared on dismiss / logout so GC can reclaim everything.</p>
 *
 * @author hamann (Phase 1)
 */
public final class ElvenElderService
{
	private static final Logger _log = Logger.getLogger(ElvenElderService.class.getName());

	// ------------------------------------------------------------------------
	// Singleton holder
	// ------------------------------------------------------------------------

	private ElvenElderService()
	{
		// intentionally private — use getInstance()
	}

	private static final ElvenElderService INSTANCE = new ElvenElderService();

	public static ElvenElderService getInstance()
	{
		return INSTANCE;
	}

	// ------------------------------------------------------------------------
	// Per-player companion registry
	// ------------------------------------------------------------------------

	/**
	 * Maps owner {@code activeId} → companion instance.
	 * Cleared on logout / dismiss; survives restarts only if backed by
	 * persistence (future phase).
	 */
	private final Map<Integer, CompanionInstance> _companions = new ConcurrentHashMap<>();

	// ------------------------------------------------------------------------
	// Feature gate
	// ------------------------------------------------------------------------

	/**
	 * Returns {@code true} if the global environment toggle allows the system
	 * to operate.  When {@code false} all recruit requests are silently
	 * rejected and no service entry point is registered.
	 */
	public boolean isEnabled()
	{
		return ElvenElderConfig.AI_COMPANION_ENABLED;
	}

	// ------------------------------------------------------------------------
	// Tick scheduling
	// ------------------------------------------------------------------------

	/**
	 * Schedules the periodic tick task for a companion.
	 * Uses a simple timer-based approach as a placeholder for L2J's
	 * {@code ThreadPoolManager} integration.
	 *
	 * TODO: replace with L2J ThreadPoolManager.schedulePeriodicTask(...)
	 *   Example:
	 *     ThreadPoolManager.getInstance().schedulePeriodicTask(() -> brain.onTick(), 0, TICK_INTERVAL_MS);
	 *
	 * @param activeId   owner's character ID
	 * @param companion  the companion model
	 * @param brain      the brain instance
	 * @return a ScheduledFuture handle (used for cancellation in shutdown)
	 */
	private ScheduledFuture<?> scheduleTickTask(int activeId, ElvenElderCompanion companion, ElvenElderBrain brain)
	{
		// TODO: integrate with L2J ThreadPoolManager for real server scheduling.
		//   For now, we use java.util.concurrent as a stand-in.
		//   In production this must run on the L2J game server thread pool.
		java.util.concurrent.ScheduledExecutorService executor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "ElvenElder-Tick-" + activeId);
			t.setDaemon(true);
			return t;
		});
		return executor.scheduleAtFixedRate(() -> {
			if (!companion.isDisposed()) {
				brain.onTick();
			}
		}, ElvenElderConfig.TICK_INTERVAL_MS, ElvenElderConfig.TICK_INTERVAL_MS, TimeUnit.MILLISECONDS);
	}

	// ========================================================================
	// PUBLIC API
	// ========================================================================

	/**
	 * Recruit a companion for the given player.
	 *
	 * <p>Called when the player initiates dialogue with the Elven Elder NPC.
	 * Enforces the one-companion-per-player rule (idempotent on re-recruit).</p>
	 *
	 * @param activeId the player's active character ID
	 * @return {@code true} if recruitment succeeded (or was already active)
	 */
	public boolean recruit(int activeId)
	{
		if (!isEnabled())
		{
			_log.fine(() -> "ElvenElderService: recruit denied — AI_COMPANION_ENABLED=false, playerId=" + activeId);
			return false;
		}

		// Idempotent: if already recruited, just confirm
		CompanionInstance existing = _companions.get(activeId);
		if (existing != null && existing.isAlive())
		 {
			_log.fine(() -> "ElvenElderService: player " + activeId + " already has an active companion (idempotent recruit)");
			return true;
		}

		// Capacity guard (should never fire given MAX_COMPANIONS_PER_PLAYER == 1)
		if (_companions.size() >= ElvenElderConfig.MAX_COMPANIONS_PER_PLAYER * getMaxPlayers())
		{
			_log.warning("ElvenElderService: companion pool capacity reached, rejecting recruit for playerId=" + activeId);
			return false;
		}

	try
	{
		// ------------------------------------------------------------------
		// TODO: Replace stub entity creation with real L2J APIs.
		//   - L2PcInstance owner = L2PcInstance.getOrCreate(activeId);
		//   - double spawnX = owner.getX();  double spawnY = owner.getY();  double spawnZ = owner.getZ();
		//   - L2Npc actor = SpawnTable.getInstance().spawnMonster(COMPANION_DISPLAY_ID, spawnX, spawnY, spawnZ, 0, false, 0);
		//   - actor.setIsInvul(true);  // companion is invulnerable in Phase 1
		// ------------------------------------------------------------------

		// Create a real companion model with valid owner reference (activeId itself serves as owner identity).
		// Coordinates: TODO — in production, read from owner's current position.
		// Here we use a fixed spawn offset near the owner (simulated).
		double spawnX = 0, spawnY = 0, spawnZ = 0; // TODO: get from owner L2PcInstance.getX/Y/Z

		ElvenElderCompanion companion = new ElvenElderCompanion(activeId, spawnX, spawnY, spawnZ);

		// Create the brain and bind it to the companion.
		ElvenElderBrain brain = new ElvenElderBrain(companion, this, null); // TODO: pass real owner ref

		// Schedule the periodic tick task (750ms interval).
		ScheduledFuture<?> tickTask = scheduleTickTask(activeId, companion, brain);

		// Wire the brain into the companion so onTick delegates to the brain.
		companion.setBrain(brain);

		// Create the instance wrapper.
		CompanionInstance instance = new CompanionInstance(activeId, companion, brain, tickTask);
		_companions.put(activeId, instance);

		_log.info(() -> "ElvenElderService: companion recruited for playerId=" + activeId);
		return true;
	}
	catch (Exception e)
	{
		_log.log(Level.SEVERE, () -> "ElvenElderService: failed to recruit companion for playerId=" + activeId, e);
		return false;
	}
	}

	/**
	 * Dismiss the companion belonging to the given player.
	 *
	 * <p>Cleans up the companion entity, cancels the tick task, and removes
	 * the entry from the registry.  Safe to call even if no companion exists.</p>
	 *
	 * @param activeId the player's active character ID
	 */
	public void dismiss(int activeId)
	{
		CompanionInstance instance = _companions.remove(activeId);
		if (instance == null)
		{
			_log.fine(() -> "ElvenElderService: dismiss called for playerId=" + activeId + " but no active companion found (no-op)");
			return;
		}

		instance.shutdown();
		_log.info(() -> "ElvenElderService: companion dismissed for playerId=" + activeId);
	}

	/**
	 * Called when a player logs out.  Ensures no orphan companions remain.
	 *
	 * @param activeId the logging-out player's character ID
	 */
	public void onPlayerLogout(int activeId)
	{
		if (_companions.containsKey(activeId))
		{
			dismiss(activeId);
			_log.info(() -> "ElvenElderService: auto-dismiss on logout for playerId=" + activeId);
		}
	}

	/**
	 * Called when a player logs in.  Checks whether a companion should be
	 * restored (future phase: persistence-based recovery).
	 *
	 * <p>Currently a no-op stub — server restarts leave no orphans because
	 * {@code _companions} is an in-memory map.</p>
	 *
	 * @param activeId the logging-in player's character ID
	 */
	public void onPlayerLogin(int activeId)
	{
		// TODO Phase 2: check persistence store for existing companion records.
		//   If found, recreate the companion entity and re-bind.
		//   Otherwise do nothing (player must recruit again via NPC dialogue).
		_log.fine(() -> "ElvenElderService: login hook for playerId=" + activeId + " (stub — no auto-recovery yet)");
	}

	/**
	 * Checks whether the given player is in a disabled scenario (Olympiad,
	 * siege, arena PvP, etc.).  If so, the companion is safely dismissed.
	 *
	 * @param activeId the player's character ID
	 * @return {@code true} if the player is currently in a disabled scenario
	 */
	public boolean checkAndDismissOnDisabledScenario(int activeId)
	{
		CompanionInstance instance = _companions.get(activeId);
		if (instance == null || !instance.isAlive())
		{
			return false;
		}

		if (isInDisabledScenario(instance))
		{
			_log.info(() -> "ElvenElderService: player " + activeId + " entered disabled scenario, dismissing companion");
			dismiss(activeId);
			return true;
		}
		return false;
	}

	// ------------------------------------------------------------------------
	// Internal helpers
	// ------------------------------------------------------------------------

	/**
	 * Determines whether the companion's owner is currently in a scenario
	 * where companions are not allowed.
	 *
	 * <p>TODO: implement actual zone/instance detection using L2J region APIs.
	 * The placeholder below demonstrates the intended flow.</p>
	 *
	 * @param instance the companion instance whose owner we're checking
	 * @return {@code true} if the owner is in a disabled scenario
	 */
	private boolean isInDisabledScenario(CompanionInstance instance)
	{
		// Stub — replace with real zone/instance checker
		// Example:
		//   int zoneId = instance.getOwner().getCurrentZone().getId();
		//   return DISABLED_SCENARIOS.contains(zoneToScenarioKey(zoneId));
		return false;
	}

	/**
	 * Returns the maximum theoretical player count for capacity calculations.
	 * In practice this is just a sanity guard; the real limit is per-player.
	 */
	private int getMaxPlayers()
	{
		// TODO: wire to L2J GameServer instance max players config.
		return 5000;
	}

	// ========================================================================
	// Companion Instance — per-player state holder
	// ========================================================================

	/**
	 * Holds all state for a single companion tied to one player.
	 *
	 * <p>Fields are cleared (references nulled) on {@link #shutdown()} to allow
	 * the GC to reclaim the companion character, tick scheduler entry, and
	 * owner reference.</p>
	 */
	private static final class CompanionInstance
	{
		/** Owner's active character ID. */
		private final int _ownerId;

		/** The actual ElvenElderCompanion model instance. */
		private volatile ElvenElderCompanion _companionModel;

		/** Reference to the companion character / NPC entity. */
		private volatile Object _companionEntity; // TODO: replace with actual L2J Character type

		/** The brain instance controlling AI decisions. */
		private volatile ElvenElderBrain _brain;

		/**
		 * Handle for the periodic tick task.
		 * Cancelled on shutdown.
		 */
		private volatile ScheduledFuture<?> _tickTaskHandle;

		/** Whether this instance is still alive (not shut down). */
		private volatile boolean _alive = true;

		CompanionInstance(int ownerId, ElvenElderCompanion companionModel, ElvenElderBrain brain, ScheduledFuture<?> tickTaskHandle)
		{
			this._ownerId = ownerId;
			this._companionModel = companionModel;
			this._brain = brain;
			this._tickTaskHandle = tickTaskHandle;
			this._companionEntity = null;
		}

		int ownerId()
		{
			return _ownerId;
		}

		boolean isAlive()
		{
			return _alive;
		}

		/**
		 * Full cleanup in correct order:
		 *   1. Cancel tick task
		 *   2. Stop AI (brain.dismiss)
		 *   3. Delete actor (companion.dismiss)
		 *   4. Clear references
		 *   5. Mark dead
		 * Safe to call multiple times (idempotent).
		 */
		void shutdown()
		{
			if (!_alive)
			{
				return; // idempotent
			}
			_alive = false;

			// Step 1: Cancel the periodic tick task
			if (_tickTaskHandle != null)
			{
				_tickTaskHandle.cancel(false);
				_tickTaskHandle = null;
			}

			// Step 2: Stop AI brain
			if (_brain != null)
			{
				_brain.dismiss();
				_brain = null;
			}

			// Step 3: Delete companion entity (companion.dismiss handles actor cleanup)
			if (_companionModel != null)
			{
				_companionModel.dismiss();
			}

			// Step 4: Clear all references to allow GC
			_companionEntity = null;
			_companionModel = null;

			// _ownerId is intentionally left as-is (primitive, no leak risk)
		}
	}
}

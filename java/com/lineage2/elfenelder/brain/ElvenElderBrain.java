/*
 * This program is free software; you can redistribute it and/or modify it under
 * the terms of the GNU General Public License version 2.
 *
 * ElvenElder Brain — Phase 3
 *
 * Core AI decision engine for the Elven Elder companion.
 * Implements a finite-state machine and a priority-based healing/buffing
 * decision loop that runs every {@code TICK_INTERVAL_MS} (750 ms).
 *
 * State machine:
 *   IDLE → FOLLOW → COMBAT_SUPPORT → RETURN → DISMISSED
 *
 * Decision priority per tick:
 *   1. Survival / legality checks (owner offline/death/disabled-zone → dismiss)
 *   2. Emergency heal  (owner HP < 35%)
 *   3. Normal heal     (owner HP < 70%)
 *   4. Self-heal       (companion HP < 55% AND owner not in emergency)
 *   5. Buff            (buff-enabled, MP sufficient, missing buffs)
 *   6. Follow          (distance exceeds comfort range)
 *   7. Idle            (nothing to do)
 *
 * All skill casts go through the normal skill-use system — no direct HP
 * manipulation.  MP < 20% triggers energy-saving mode (only emergency heal
 * and self-heal are allowed).
 *
 * @author hamann (Phase 3)
 */
package com.lineage2.elfenelder.brain;

import com.lineage2.elfenelder.config.ElvenElderConfig;
import com.lineage2.elfenelder.model.ElvenElderCompanion;
import com.lineage2.elfenelder.service.ElvenElderService;

import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * AI brain for the Elven Elder companion.
 *
 * <p>This class owns the state machine and the per-tick decision logic.
 * It is instantiated once per companion and invoked by the service-layer
 * scheduler at {@link ElvenElderConfig#TICK_INTERVAL_MS} intervals.</p>
 *
 * <p><b>Thread-safety:</b> All public entry points are guarded by a
 * {@link ReentrantLock} so that concurrent ticks on the same companion
 * cannot re-enter execution.</p>
 *
 * @see ElvenElderCompanion
 * @see ElvenElderService
 */
public class ElvenElderBrain
{
    // =====================================================================
    // Logging
    // =====================================================================

    private static final Logger _log = Logger.getLogger(ElvenElderBrain.class.getName());

    // =====================================================================
    // State machine
    // =====================================================================

    /**
     * Represents the current high-level state of the companion's behaviour.
     */
    public enum BrainState
    {
        /** Companion is idle — no owner bound yet. */
        IDLE,
        /** Companion is following the owner. */
        FOLLOW,
        /** Companion is in combat support mode (healing / buffing). */
        COMBAT_SUPPORT,
        /** Companion is returning to the owner (after being too far away). */
        RETURN,
        /** Companion has been dismissed and is no longer active. */
        DISMISSED
    }

    // =====================================================================
    // Fields
    // =====================================================================

    /** The companion entity this brain controls. */
    private final ElvenElderCompanion _companion;

    /** The service layer — used to dispatch skill casts. */
    private final ElvenElderService _service;

    /**
     * Reference to the owner player.
     * TODO: replace with concrete L2J type (e.g., L2PcInstance).
     */
    private volatile Object _owner;

    /** Current state of the brain's finite-state machine. */
    private volatile BrainState _currentState;

    /** Lock to prevent re-entrant tick execution on the same companion. */
    private final ReentrantLock _tickLock = new ReentrantLock();

    /**
     * Whether a heal skill is currently being cast on the given target.
     * Key = owner hash code (since we only heal one target), value = timestamp
     * when the heal was initiated.  Used to prevent duplicate heal starts.
     */
    private volatile long _healCastTimestamp = 0;

    /**
     * Whether a self-heal is currently being cast.
     */
    private volatile boolean _selfHealing = false;

    // =====================================================================
    // Construction
    // =====================================================================

    /**
     * Creates a new brain bound to the given companion and service.
     *
     * @param companion the companion entity this brain controls
     * @param service   the service layer for skill casting and lifecycle mgmt
     * @param owner     the owner player reference
     * @throws NullPointerException if companion or service is null
     */
    public ElvenElderBrain(
        final ElvenElderCompanion companion,
        final ElvenElderService service,
        final Object owner
    )
    {
        this._companion = Objects.requireNonNull(companion, "companion must not be null");
        this._service = Objects.requireNonNull(service, "service must not be null");
        this._owner = Objects.requireNonNull(owner, "owner must not be null");
        this._currentState = BrainState.IDLE;
    }

    // =====================================================================
    // Public API
    // =====================================================================

    /**
     * Executes one tick of the brain's decision loop.
     *
     * <p>This method is idempotent and thread-safe — it acquires a lock to
     * prevent concurrent re-entry on the same companion.</p>
     *
     * <p>Decision flow (in priority order):</p>
     * <ol>
     *   <li>Survival / legality checks → dismiss if needed</li>
     *   <li>Emergency heal (owner HP &lt; 35%)</li>
     *   <li>Normal heal (owner HP &lt; 70%)</li>
     *   <li>Self-heal (companion HP &lt; 55%, owner not emergency)</li>
     *   <li>Buff (if enabled, MP sufficient, missing buffs)</li>
     *   <li>Follow (if too far from owner)</li>
     *   <li>Idle (do nothing)</li>
     * </ol>
     */
    public void onTick()
    {
        // Prevent re-entrant execution on the same companion
        if (!_tickLock.tryLock())
        {
            _log.fine(() -> "ElvenElderBrain: tick skipped — lock held for playerId="
                + _companion.getActiveCharId());
            return;
        }

        try
        {
            // Quick exit if disposed
            if (_companion.isDisposed() || _companion.getState()
                == ElvenElderCompanion.FollowerState.DISMISSED)
            {
                setState(BrainState.DISMISSED);
                return;
            }

            // Phase 4: cross-instance detection
            if (detectCrossInstanceChange())
            {
                handleCrossInstanceTeleport();
                return;
            }

            // 1. Survival / legality checks
            if (!checkSurvival())
            {
                return; // dismissed inside checkSurvival
            }

            // 2. Emergency heal
            if (tryEmergencyHeal())
            {
                setState(BrainState.COMBAT_SUPPORT);
                return;
            }

            // 3. Normal heal (only if not in energy-saving mode)
            if (!isEnergySavingMode() && tryNormalHeal())
            {
                setState(BrainState.COMBAT_SUPPORT);
                return;
            }

            // 4. Self-heal (only if owner is not in emergency)
            if (trySelfHeal())
            {
                setState(BrainState.COMBAT_SUPPORT);
                return;
            }

            // 5. Buff (if enabled and MP sufficient)
            if (_companion.isBuffEnabled() && !isEnergySavingMode())
            {
                if (tryBuff())
                {
                    setState(BrainState.COMBAT_SUPPORT);
                    return;
                }
            }

            // 6. Follow (with Phase 4 stuck recovery)
            if (tryFollowWithRecovery())
            {
                setState(BrainState.FOLLOW);
                return;
            }

            // 7. Idle — nothing to do this tick
            // (no log to avoid spamming every 750ms)
        }
        finally
        {
            _tickLock.unlock();
        }
    }

    /**
     * Returns the current brain state.
     */
    public BrainState getCurrentState()
    {
        return _currentState;
    }

    /**
     * Forces the brain into the DISMISSED state and triggers companion cleanup.
     */
    public void dismiss()
    {
        setState(BrainState.DISMISSED);
        _companion.dismiss();
        _log.info(() -> "ElvenElderBrain: dismissed for playerId="
            + _companion.getActiveCharId());
    }

    // =====================================================================
    // State management
    // =====================================================================

    /**
     * Transitions the brain to a new state, logging state changes
     * (but not idle transitions).
     *
     * @param newState the target state
     */
    private void setState(final BrainState newState)
    {
        if (_currentState != newState)
        {
            _log.fine(() -> "ElvenElderBrain: state " + _currentState + " → "
                + newState + " for playerId=" + _companion.getActiveCharId());
            _currentState = newState;
        }
    }

    // =====================================================================
    // Decision: Survival / Legality checks
    // =====================================================================

    /**
     * Checks whether the companion should remain active.
     * Returns {@code true} if the companion is still valid; {@code false}
     * means the companion has been dismissed.
     *
     * <p>Checks performed:</p>
     * <ul>
     *   <li>Owner reference is not null</li>
     *   <li>Owner is online and alive</li>
     *   <li>Owner is not in a disabled scenario</li>
     *   <li>Companion is not in a disabled scenario</li>
     * </ul>
     *
     * @return {@code true} if companion should continue operating
     */
    private boolean checkSurvival()
    {
        // Owner null check
        if (_owner == null)
        {
            _log.warning(() -> "ElvenElderBrain: owner reference lost for playerId="
                + _companion.getActiveCharId() + ", dismissing");
            dismiss();
            return false;
        }

        // TODO: check if owner is online and alive
        //   L2PcInstance pc = (L2PcInstance) _owner;
        //   if (!pc.isOnline() || pc.isDead()) { dismiss(); return false; }

        // TODO: check if owner is in a disabled scenario
        //   if (_service.checkAndDismissOnDisabledScenario(_companion.getActiveCharId())) {
        //       setState(BrainState.DISMISSED);
        //       return false;
        //   }

        // Phase 4: check if owner entered a disabled scenario zone
        if (isOwnerInDisabledScenario())
        {
            handleDisabledScenarioEntry();
            setState(BrainState.DISMISSED);
            return false;
        }

        // Check if companion is in a disabled scenario
        if (_companion.isInDisabledScenario())
        {
            _log.info(() -> "ElvenElderBrain: companion in disabled scenario for playerId="
                + _companion.getActiveCharId() + ", dismissing");
            dismiss();
            return false;
        }

        return true;
    }

    // =====================================================================
    // Decision: Emergency Heal (owner HP < 35%)
    // =====================================================================

    /**
     * Attempts to cast the strongest single-target heal on the owner
     * when their HP drops below the emergency threshold (35%).
     *
     * <p>Constraints:</p>
     * <ul>
     *   <li>Only heals the owner — never strangers or hostile targets</li>
     *   <li>Skips if owner is dead, invisible, or out of range</li>
     *   <li>Skips if a heal is already being cast (anti-spam)</li>
     *   <li>Always allowed, even in energy-saving mode</li>
     * </ul>
     *
     * @return {@code true} if a heal was successfully initiated
     */
    private boolean tryEmergencyHeal()
    {
        // TODO: retrieve owner HP percentage
        //   double ownerHpPercent = getOwnerHpPercent();
        //   if (ownerHpPercent >= ElvenElderConfig.HEAL_EMERGENCY_THRESHOLD) {
        //       return false;
        //   }

        // TODO: validate owner is alive, visible, and in same world
        //   if (!isOwnerValid()) { return false; }

        // Skip if a heal is already being cast on owner
        if (_healCastTimestamp > 0)
        {
            // Allow override if the previous heal was cast a very long time ago
            // (skill cast time exceeded), to handle stuck casts
            // TODO: use actual skill cast time for comparison
            return false;
        }

        // TODO: check if companion has enough MP for emergency heal
        //   if (!hasEnoughMp(ElvenElderConfig.HEAL_SKILL_ID_EMERGENCY)) { return false; }

        _log.fine(() -> "ElvenElderBrain: emergency heal triggered for playerId="
            + _companion.getActiveCharId() + " (owner HP < "
            + (ElvenElderConfig.HEAL_EMERGENCY_THRESHOLD * 100) + "%)");

        // TODO: cast the emergency heal skill via the service layer
        //   _service.castSkill(
        //       _companion,
        //       _owner,
        //       ElvenElderConfig.HEAL_SKILL_ID_EMERGENCY
        //   );
        _healCastTimestamp = System.currentTimeMillis();

        return true; // Signal that we attempted an action
    }

    // =====================================================================
    // Decision: Normal Heal (owner HP < 70%)
    // =====================================================================

    /**
     * Attempts to cast an economical single-target heal on the owner
     * when their HP drops below the normal threshold (70%).
     *
     * <p>Skipped in energy-saving mode (MP &lt; 20%).</p>
     *
     * @return {@code true} if a heal was successfully initiated
     */
    private boolean tryNormalHeal()
    {
        // TODO: retrieve owner HP percentage
        //   double ownerHpPercent = getOwnerHpPercent();
        //   if (ownerHpPercent >= ElvenElderConfig.HEAL_NORMAL_THRESHOLD) {
        //       return false;
        //   }

        // TODO: validate owner is alive, visible, and in same world
        //   if (!isOwnerValid()) { return false; }

        // Skip if a heal is already being cast on owner
        if (_healCastTimestamp > 0)
        {
            return false;
        }

        // TODO: check MP sufficiency
        //   if (!hasEnoughMp(ElvenElderConfig.HEAL_SKILL_ID_NORMAL)) { return false; }

        _log.fine(() -> "ElvenElderBrain: normal heal triggered for playerId="
            + _companion.getActiveCharId() + " (owner HP < "
            + (ElvenElderConfig.HEAL_NORMAL_THRESHOLD * 100) + "%)");

        // TODO: cast the normal heal skill via the service layer
        //   _service.castSkill(
        //       _companion,
        //       _owner,
        //       ElvenElderConfig.HEAL_SKILL_ID_NORMAL
        //   );
        _healCastTimestamp = System.currentTimeMillis();

        return true;
    }

    // =====================================================================
    // Decision: Self-Heal (companion HP < 55%)
    // =====================================================================

    /**
     * Attempts to heal the companion itself when its HP drops below 55%
     * AND the owner is not in an emergency state.
     *
     * <p>Allowed in energy-saving mode (self-preservation is critical).</p>
     *
     * @return {@code true} if a self-heal was successfully initiated
     */
    private boolean trySelfHeal()
    {
        // TODO: retrieve companion HP percentage
        //   double selfHpPercent = getCompanionHpPercent();
        //   if (selfHpPercent >= ElvenElderConfig.SELF_HEAL_THRESHOLD) {
        //       return false;
        //   }

        // Owner must not be in emergency state (priority goes to owner)
        // TODO: check owner HP against emergency threshold
        //   if (getOwnerHpPercent() < ElvenElderConfig.HEAL_EMERGENCY_THRESHOLD) {
        //       return false;
        //   }

        // Skip if already self-healing
        if (_selfHealing)
        {
            return false;
        }

        // TODO: check MP sufficiency
        //   if (!hasEnoughMp(ElvenElderConfig.SELF_HEAL_SKILL_ID)) { return false; }

        _log.fine(() -> "ElvenElderBrain: self-heal triggered for playerId="
            + _companion.getActiveCharId() + " (self HP < "
            + (ElvenElderConfig.SELF_HEAL_THRESHOLD * 100) + "%)");

        // TODO: cast the self-heal skill via the service layer
        //   _service.castSkill(
        //       _companion,
        //       _companion, // self-target
        //       ElvenElderConfig.SELF_HEAL_SKILL_ID
        //   );
        _selfHealing = true;

        return true;
    }

    // =====================================================================
    // Decision: Buff
    // =====================================================================

    /**
     * Attempts to apply missing buffs to the owner when buff support is
     * enabled and MP is sufficient.
     *
     * <p>Skipped in energy-saving mode.  Only applies buffs that are
     * missing — does not overwrite existing buffs.</p>
     *
     * @return {@code true} if a buff was successfully applied
     */
    private boolean tryBuff()
    {
        // TODO: retrieve owner's current active buffs
        //   Set<Integer> ownerBuffs = getOwnerActiveBuffs();

        // TODO: check which buffs are missing
        //   List<Integer> missingBuffs = getMissingBuffs(ownerBuffs);

        // TODO: if no buffs missing, return false

        // TODO: check MP sufficiency for buff skills
        //   for (int skillId : missingBuffs) {
        //       if (!hasEnoughMp(skillId)) { return false; }
        //   }

        _log.fine(() -> "ElvenElderBrain: buff cycle triggered for playerId="
            + _companion.getActiveCharId());

        // TODO: cast missing buff skills via the service layer
        //   for (int skillId : missingBuffs) {
        //       _service.castSkill(_companion, _owner, skillId);
        //   }

        return true;
    }

    // =====================================================================
    // Decision: Follow
    // =====================================================================

    /**
     * Attempts to move the companion closer to the owner if they are
     * outside the comfortable follow distance range.
     *
     * @return {@code true} if a follow action was initiated
     */
    private boolean tryFollow()
    {
        // TODO: retrieve owner position
        //   double ownerX = ((L2PcInstance)_owner).getX();
        //   double ownerY = ((L2PcInstance)_owner).getY();
        //   double ownerZ = ((L2PcInstance)_owner).getZ();

        // TODO: check distance and call companion.moveTowardsOwner()
        //   if (companion.distance(ownerX, ownerY, ownerZ) > ElvenElderConfig.FOLLOW_DISTANCE_MAX) {
        //       _companion.moveTowardsOwner(ownerX, ownerY, ownerZ);
        //       return true;
        //   }

        // TODO: handle return-if-too-far case
        //   if (_companion.shouldReturnToOwner()) {
        //       setState(BrainState.RETURN);
        //       _companion.safeTeleportBack(ownerX, ownerY, ownerZ, ...);
        //       return true;
        //   }

        return false;
    }

    // =====================================================================
    // Energy-saving mode
    // =====================================================================

    /**
     * Returns {@code true} if the companion's MP is below the conservation
     * threshold ({@value ElvenElderConfig#MP_CONSERVATION_THRESHOLD * 100}%).
     *
     * <p>In energy-saving mode, only emergency heals and self-heals are
     * permitted — normal heals and buffs are skipped to conserve MP.</p>
     *
     * @return {@code true} if energy-saving mode is active
     */
    private boolean isEnergySavingMode()
    {
        // TODO: retrieve companion MP percentage
        //   double mpPercent = getCompanionMpPercent();
        //   return mpPercent < ElvenElderConfig.MP_CONSERVATION_THRESHOLD;

        return false; // Placeholder — replace with actual MP check
    }


    // =====================================================================
    // Buff management (enhanced for Phase 4)
    // =====================================================================

    /**
     * Buff application cooldown — prevents rapid buff cycling.
     * Minimum 5 seconds between buff attempts.
     */
    private volatile long _lastBuffTimestamp = 0;

    /**
     * Attempt to apply missing buffs to the owner.
     * Enhanced for Phase 4: checks buff duration, MP sufficiency,
     * and avoids redundant buff applications.
     *
     * @return {@code true} if a buff was applied
     */
    private boolean tryBuffEnhanced()
    {
        // Cooldown check — prevent rapid buff cycling
        long now = System.currentTimeMillis();
        if (now - _lastBuffTimestamp < ElvenElderConfig.BUFF_COOLDOWN_MS)
        {
            return false;
        }

        // MP check — cannot afford buffs
        if (getCompanionMpPercent() < ElvenElderConfig.MP_CONSERVATION_THRESHOLD)
        {
            return false;
        }

        // Get owner's current active buffs
        java.util.Set<Integer> ownerBuffs = getOwnerActiveBuffs();

        // Get missing buffs from whitelist
        java.util.List<Integer> missingBuffs = getMissingBuffs(ownerBuffs);

        if (missingBuffs.isEmpty())
        {
            return false;
        }

        // Apply the first missing buff (one per tick to avoid spam)
        int skillId = missingBuffs.get(0);
        int skillLevel = getSkillLevelForBuff(skillId);

        _log.fine(() -> "ElvenElderBrain: applying buff skillId=" + skillId
            + " level=" + skillLevel + " for playerId=" + _companion.getActiveCharId());

        // TODO: cast buff skill via service layer
        //   _service.castSkill(_companion, _owner, skillId, skillLevel);

        _lastBuffTimestamp = now;
        return true;
    }

    /**
     * Retrieves the owner's currently active buff skill IDs.
     *
     * @return set of active buff skill IDs
     * @throws IllegalStateException if owner is null or invalid
     */
    private java.util.Set<Integer> getOwnerActiveBuffs()
    {
        // TODO: implement using L2J API
        //   L2PcInstance pc = (L2PcInstance) _owner;
        //   return pc.getActiveBuffs().stream()
        //       .map(b -> b.getId())
        //       .collect(java.util.stream.Collectors.toSet());
        throw new IllegalStateException("getOwnerActiveBuffs() not yet wired to L2J API");
    }

    /**
     * Determines which buffs from the whitelist are missing from the owner.
     *
     * @param currentBuffs set of owner's currently active buff IDs
     * @return list of missing buff skill IDs (ordered by priority)
     */
    private java.util.List<Integer> getMissingBuffs(java.util.Set<Integer> currentBuffs)
    {
        java.util.List<Integer> missing = new java.util.ArrayList<>();
        for (int buffId : ElvenElderConfig.BUFF_WHITELIST)
        {
            if (!currentBuffs.contains(buffId))
            {
                missing.add(buffId);
            }
        }
        return missing;
    }

    /**
     * Returns the effective level for a buff skill on this companion.
     *
     * @param skillId the buff skill ID
     * @return the skill level (or 0 if not available)
     */
    private int getSkillLevelForBuff(int skillId)
    {
        // TODO: look up from companion's skill table
        //   return _companion.getSkillLevel(skillId);
        return 1; // Placeholder
    }

    // =====================================================================
    // Disabled scenario detection (Phase 4)
    // =====================================================================

    /**
     * Checks whether the owner has entered a disabled scenario zone.
     * Returns true if the owner is in a forbidden area.
     *
     * @return {@code true} if owner is in a disabled scenario
     */
    private boolean isOwnerInDisabledScenario()
    {
        // TODO: implement using L2J ZoneManager / RegionManager API
        //   L2PcInstance pc = (L2PcInstance) _owner;
        //   L2ZoneType zone = pc.getZone();
        //   if (zone instanceof L2OlympiadZone || zone instanceof L2ArenaZone
        //       || zone instanceof L2SiegeZone || zone instanceof L2ContestZone) {
        //       return true;
        //   }
        //   return false;
        return false; // Placeholder — always false until wired
    }

    /**
     * Triggers safe dismissal when the owner enters a disabled scenario.
     * Logs the event and calls dismiss().
     */
    private void handleDisabledScenarioEntry()
    {
        _log.info(() -> "ElvenElderBrain: owner entered disabled scenario for playerId="
            + _companion.getActiveCharId() + ", triggering safe dismiss");
        dismiss();
    }

    // =====================================================================
    // Teleport / Cross-instance recovery (Phase 4)
    // =====================================================================

    /**
     * Tracks the owner's last known instance ID for cross-instance detection.
     */
    private volatile int _lastOwnerInstanceId = -1;

    /**
     * Detects whether the owner has changed instance (teleported to a different zone/world).
     *
     * @return {@code true} if a cross-instance change was detected
     */
    private boolean detectCrossInstanceChange()
    {
        // TODO: get owner's current instance ID
        //   L2PcInstance pc = (L2PcInstance) _owner;
        //   int currentInstanceId = pc.getInstanceId();

        int currentInstanceId = 0; // Placeholder

        if (_lastOwnerInstanceId == -1)
        {
            // First tick — initialize
            _lastOwnerInstanceId = currentInstanceId;
            return false;
        }

        if (currentInstanceId != _lastOwnerInstanceId)
        {
            _log.info(() -> "ElvenElderBrain: cross-instance detected for playerId="
                + _companion.getActiveCharId()
                + " (instance " + _lastOwnerInstanceId + " → " + currentInstanceId + ")");
            _lastOwnerInstanceId = currentInstanceId;
            return true;
        }

        return false;
    }

    /**
     * Handles a cross-instance teleport event.
     * Attempts to safely teleport the companion to the owner's location.
     */
    private void handleCrossInstanceTeleport()
    {
        // TODO: get owner's current position
        //   L2PcInstance pc = (L2PcInstance) _owner;
        //   double x = pc.getX();
        //   double y = pc.getY();
        //   double z = pc.getZ();

        double x = 0, y = 0, z = 0; // Placeholder

        // Validate the target position is legal (not inside walls, not in forbidden zone)
        if (!isValidPosition(x, y, z))
        {
            _log.warning(() -> "ElvenElderBrain: teleport target position invalid for playerId="
                + _companion.getActiveCharId() + ", falling back to safe return");
            // Fall back to safe return to owner
            safeReturnToOwner();
            return;
        }

        // TODO: teleport companion to owner's position
        //   _companion.teleportTo(x, y, z);

        setState(BrainState.FOLLOW);
    }

    /**
     * Validates whether a given position is legal for the companion to occupy.
     * Checks terrain collision, walkability, and zone restrictions.
     *
     * @param x X coordinate
     * @param y Y coordinate
     * @param z Z coordinate
     * @return {@code true} if the position is legal
     */
    private boolean isValidPosition(double x, double y, double z)
    {
        // TODO: implement using L2J terrain/collision API
        //   return L2World.getInstance().checkCollision(x, y, z) == 0;
        return true; // Placeholder — assume valid until wired
    }

    /**
     * Performs a safe return to the owner when teleportation is not possible.
     * Uses the companion's built-in pathfinding fallback.
     */
    private void safeReturnToOwner()
    {
        _log.info(() -> "ElvenElderBrain: safe return initiated for playerId="
            + _companion.getActiveCharId());
        setState(BrainState.RETURN);
        // TODO: trigger companion's safe return logic
        //   _companion.safeTeleportBack(ownerX, ownerY, ownerZ);
    }

    // =====================================================================
    // Stuck detection and recovery (Phase 4)
    // =====================================================================

    /**
     * Handles consecutive pathfinding failures.
     * If failures exceed the threshold, triggers safe return.
     */
    private void handlePathFailure()
    {
        _companion.incrementPathFailureCount();

        if (_companion.getPathFailureCount() >= ElvenElderConfig.MAX_PATH_FAILURES)
        {
            _log.warning(() -> "ElvenElderBrain: path failure threshold reached ("
                + _companion.getPathFailureCount() + ") for playerId="
                + _companion.getActiveCharId() + ", triggering safe return");
            safeReturnToOwner();
        }
    }

    /**
     * Resets the path failure counter on successful movement.
     */
    private void resetPathFailureCount()
    {
        _companion.resetPathFailureCount();
    }

    // =====================================================================
    // Enhanced follow with stuck recovery (Phase 4)
    // =====================================================================

    /**
     * Enhanced follow method that integrates stuck recovery.
     * Calls the base tryFollow() and adds path failure handling.
     *
     * @return {@code true} if a follow action was initiated
     */
    private boolean tryFollowWithRecovery()
    {
        boolean followed = tryFollow();
        if (followed)
        {
            resetPathFailureCount();
        }
        else
        {
            // If follow failed and we're supposed to be following,
            // check if we're stuck
            if (_currentState == BrainState.FOLLOW || _currentState == BrainState.RETURN)
            {
                handlePathFailure();
            }
        }
        return followed;
    }


    // =====================================================================
    // Helper stubs (to be wired to L2J APIs in Phase 4+)
    // =====================================================================

    /**
     * Returns the owner's current HP as a fraction of max HP (0.0 – 1.0).
     *
     * @return owner HP fraction
     * @throws IllegalStateException if owner is null or disposed
     */
    private double getOwnerHpPercent()
    {
        // TODO: implement using L2J API
        //   L2PcInstance pc = (L2PcInstance) _owner;
        //   return pc.getCurrentHp() / (double) pc.getMaxHp();
        throw new IllegalStateException("getOwnerHpPercent() not yet wired to L2J API");
    }

    /**
     * Returns the companion's current HP as a fraction of max HP (0.0 – 1.0).
     *
     * @return companion HP fraction
     */
    private double getCompanionHpPercent()
    {
        // TODO: implement using L2J API
        //   L2Character npc = (L2Character) _companion.getActorInstance();
        //   return npc.getCurrentHp() / (double) npc.getMaxHp();
        throw new IllegalStateException("getCompanionHpPercent() not yet wired to L2J API");
    }

    /**
     * Returns the companion's current MP as a fraction of max MP (0.0 – 1.0).
     *
     * @return companion MP fraction
     */
    private double getCompanionMpPercent()
    {
        // TODO: implement using L2J API
        //   L2Character npc = (L2Character) _companion.getActorInstance();
        //   return npc.getCurrentMp() / (double) npc.getMaxMp();
        throw new IllegalStateException("getCompanionMpPercent() not yet wired to L2J API");
    }

    /**
     * Checks whether the companion has enough MP to cast a given skill.
     *
     * @param skillId the skill ID to check
     * @return {@code true} if MP is sufficient
     */
    private boolean hasEnoughMp(int skillId)
    {
        // TODO: implement using L2J API
        //   L2Character npc = (L2Character) _companion.getActorInstance();
        //   L2Skill skill = L2SkillTable.getInstance().getInfo(skillId, getSkillLevel(skillId));
        //   return skill != null && npc.getCurrentMp() >= skill.getMpConsume();
        throw new IllegalStateException("hasEnoughMp() not yet wired to L2J API");
    }

    /**
     * Returns the companion's effective level for a given skill.
     *
     * @param skillId the skill ID
     * @return the skill level (or 0 if unknown)
     */
    private int getSkillLevel(int skillId)
    {
        // TODO: look up from companion's skill table
        return 0;
    }

    /**
     * Checks whether the owner is a valid heal target:
     * alive, visible, in the same world, and not hostile.
     *
     * @return {@code true} if the owner is a valid target
     */
    private boolean isOwnerValid()
    {
        // TODO: implement using L2J API
        //   L2PcInstance pc = (L2PcInstance) _owner;
        //   return pc != null && pc.isOnline() && !pc.isDead()
        //       && pc.isVisible() && !pc.isDead();
        return _owner != null;
    }

    /**
     * Clears the heal-cast tracking flag after the skill cast completes
     * (or times out).
     */
    void onHealCastComplete()
    {
        _healCastTimestamp = 0;
        _selfHealing = false;
    }

    /**
     * Updates the owner reference.  Called when the owner changes
     * (e.g., character switch, re-login).
     *
     * @param newOwner the new owner player reference
     */
    void setOwner(final Object newOwner)
    {
        this._owner = Objects.requireNonNull(newOwner);
    }

    /**
     * Returns the owner reference (for debugging / testing).
     */
    Object getOwner()
    {
        return _owner;
    }

    /**
     * Returns the companion entity.
     */
    ElvenElderCompanion getCompanion()
    {
        return _companion;
    }

    /**
     * Returns the service layer reference.
     */
    ElvenElderService getService()
    {
        return _service;
    }

    // =====================================================================
    // toString
    // =====================================================================

    @Override
    public String toString()
    {
        return "ElvenElderBrain{"
            + "companionId=" + _companion.getActiveCharId()
            + ", state=" + _currentState
            + ", disposed=" + _companion.isDisposed()
            + "}";
    }
}

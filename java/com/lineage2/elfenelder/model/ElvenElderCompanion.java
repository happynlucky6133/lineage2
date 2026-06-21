/*
 * This program is free software; you can redistribute it and/or modify it under
 * the terms of the GNU General Public License version 2.
 *
 * ElvenElder Companion Entity — Phase 2
 *
 * Represents a recruited Elven Elder AI companion bound to a single player.
 * Handles owner binding, comfortable following, teleport / cross-instance sync,
 * safe return-to-owner, death / logout cleanup, and state persistence fields.
 *
 * All references to L2J core APIs are marked with TODO so that the actual
 * High Five types can be plugged in during integration.
 *
 * @author hamann (Phase 2)
 */
package com.lineage2.elfenelder.model;

import com.lineage2.elfenelder.config.ElvenElderConfig;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Companion entity for the Elven Elder AI system.
 *
 * <p>Each {@code ElvenElderCompanion} instance is bound to exactly one player
 * (identified by {@code activeCharId}).  It tracks its own position, its
 * original spawn point, follow state, and a small set of L2J entity handles
 * that are nullified on dismissal / death / logout to allow GC.</p>
 *
 * <h3>Lifecycle</h3>
 * <ol>
 *   <li>{@code recruit()} — constructed with owner reference, spawned at a
 *       legal position near the owner.</li>
 *   <li>Tick loop — every {@code TICK_INTERVAL_MS} ms the companion checks
 *       distance to owner and follows / returns as needed.</li>
 *   <li>{@code dismiss()} / {@code onDeath()} — clean up all L2J references,
 *       cancel tick, mark state DISMISSED.</li>
 * </ol>
 *
 * <h3>Key constraints</h3>
 * <ul>
 *   <li>One companion per player (enforced by caller / service layer).</li>
 *   <li>Comfortable follow distance: [80, 250] L2 units.</li>
 *   <li>Return threshold: 1200 units — triggers safe teleport back.</li>
 *   <li>Must not enter walls, non-walkable areas, or other instances.</li>
 *   <li>Does not aggro monsters, pick up items, or steal XP / drops.</li>
 * </ul>
 */
public class ElvenElderCompanion
{
    // =====================================================================
    // Logging
    // =====================================================================

    private static final Logger _log = Logger.getLogger(ElvenElderCompanion.class.getName());

    // =====================================================================
    // State fields
    // =====================================================================

    /** Owner's active character ID (immutable after construction). */
    private final int _activeCharId;

    /** Companion's current world coordinates. */
    private double _x, _y, _z;

    /** Original spawn coordinates — used for respawn / return logic. */
    private final double _spawnX, _spawnY, _spawnZ;

    /** Current follower state. */
    private volatile FollowerState _state;

    /** Whether auto-buff/heal abilities are enabled. */
    private volatile boolean _buffEnabled;

    /** Reference to the owner player object. */
    private volatile Object _owner; // TODO: replace with L2PcInstance / ActiveChar

    /** Reference to the companion NPC entity managed by L2J. */
    private volatile Object _actorInstance; // TODO: replace with L2NpcInstance / L2Character

    /** Consecutive pathfinding failures — resets on success. */
    private int _consecutivePathFailures;

    /**
     * Last known owner instance-id / zone token.
     * Used to detect cross-instance teleports.
     * TODO: replace with actual L2J instance/zone type.
     */
    private Object _lastOwnerInstanceId;

    /**
     * Whether the companion has been fully cleaned up.
     * Idempotent — safe to call shutdown / dismiss multiple times.
     */
    private volatile boolean _disposed = false;

    // =====================================================================
    // Enums
    // =====================================================================

    /**
     * Possible states for the companion's follow behaviour.
     */
    public enum FollowerState
    {
        /** Actively following the owner. */
        FOLLOWING,
        /** Staying at spawn point — does not follow movement. */
        WAITING,
        /** Dismissed / removed — no further actions taken. */
        DISMISSED
    }

    // =====================================================================
    // Construction
    // =====================================================================

    /**
     * Constructs a new companion bound to the given owner.
     *
     * @param activeCharId the owner's active character ID
     * @param owner        the owner player object (TODO: concrete L2J type)
     * @param spawnX       initial X coordinate for spawning
     * @param spawnY       initial Y coordinate for spawning
     * @param spawnZ       initial Z coordinate for spawning
     * @param actor        the spawned companion NPC entity (TODO: concrete L2J type)
     */
    public ElvenElderCompanion(
        int activeCharId,
        Object owner,
        double spawnX,
        double spawnY,
        double spawnZ,
        Object actor
    )
    {
        this._activeCharId = activeCharId;
        this._owner = owner;
        this._actorInstance = actor;
        this._spawnX = spawnX;
        this._spawnY = spawnY;
        this._spawnZ = spawnZ;

        // Start at spawn position
        this._x = spawnX;
        this._y = spawnY;
        this._z = spawnZ;

        this._state = FollowerState.FOLLOWING;
        this._buffEnabled = true;
        this._consecutivePathFailures = 0;
        this._lastOwnerInstanceId = null;
    }

    // =====================================================================
    // Public API — Lifecycle
    // =====================================================================

    /**
     * Dismisses the companion: cancels all references so the GC can reclaim
     * the L2J entity, marks the state as DISMISSED, and sets disposed flag.
     *
     * <p>Safe to call multiple times (idempotent).</p>
     */
    public void dismiss()
    {
        if (_disposed)
        {
            return;
        }
        _disposed = true;

        _log.fine(() -> "ElvenElderCompanion: dismissing companion for playerId=" + _activeCharId);

        setState(FollowerState.DISMISSED);

        // Null out L2J references to allow GC
        _owner = null;
        _actorInstance = null;
        _lastOwnerInstanceId = null;

        // TODO: call L2J removal API to actually despawn / delete the NPC entity
        //   e.g., ((L2Npc)_actorInstance).deleteMe();
    }

    /**
     * Marks the companion as dead (e.g., HP reached 0).  Performs the same
     * cleanup as {@link #dismiss()} but additionally notifies the service layer
     * via a callback hook.
     *
     * <p>The service layer should listen for this event to remove the entry
     * from its internal map.</p>
     *
     * @param onDeathCallback optional callback invoked after cleanup
     */
    public void onDeath(java.lang.Runnable onDeathCallback)
    {
        _log.warning(() -> "ElvenElderCompanion: companion died for playerId=" + _activeCharId);

        // Perform same cleanup as dismiss
        dismiss();

        // Invoke the service-layer callback
        if (onDeathCallback != null)
        {
            try
            {
                onDeathCallback.run();
            }
            catch (Exception e)
            {
                _log.log(Level.SEVERE, "ElvenElderCompanion: error in death callback", e);
            }
        }
    }

    /**
     * Resets the companion to its initial spawn state.
     *
     * <p>Used after a cross-instance teleport or when the companion is
     * forcibly returned to the owner's side.</p>
     */
    public void reset()
    {
        if (_disposed)
        {
            return;
        }

        _x = _spawnX;
        _y = _spawnY;
        _z = _spawnZ;
        _consecutivePathFailures = 0;
        _lastOwnerInstanceId = null;

        _log.fine(() -> "ElvenElderCompanion: reset companion for playerId=" + _activeCharId + " to spawn (" + _x + "," + _y + "," + _z + ")");
    }

    // =====================================================================
    // Public API — State queries
    // =====================================================================

    /**
     * Returns the owner's active character ID.
     */
    public int getActiveCharId()
    {
        return _activeCharId;
    }

    /**
     * Returns the current follower state.
     */
    public FollowerState getState()
    {
        return _state;
    }

    /**
     * Sets the follower state.
     *
     * @param state the new state
     */
    public void setState(FollowerState state)
    {
        _state = state;
    }

    /**
     * Returns whether buffs/heals are enabled.
     */
    public boolean isBuffEnabled()
    {
        return _buffEnabled;
    }

    /**
     * Toggles buff/heal ability on/off.
     */
    public void setBuffEnabled(boolean enabled)
    {
        _buffEnabled = enabled;
    }

    /**
     * Returns the current position.
     */
    public double getX() { return _x; }
    public double getY() { return _y; }
    public double getZ() { return _z; }

    /**
     * Returns the spawn position.
     */
    public double getSpawnX() { return _spawnX; }
    public double getSpawnY() { return _spawnY; }
    public double getSpawnZ() { return _spawnZ; }

    /**
     * Returns the disposed flag.
     */
    public boolean isDisposed()
    {
        return _disposed;
    }

    /**
     * Returns the consecutive pathfinding failure count.
     */
    public int getConsecutivePathFailures()
    {
        return _consecutivePathFailures;
    }

    /**
     * Increments the consecutive pathfinding failure counter.
     */
    public void incrementPathFailures()
    {
        _consecutivePathFailures++;
    }

    /**
     * Resets the consecutive pathfinding failure counter to zero.
     */
    public void resetPathFailures()
    {
        _consecutivePathFailures = 0;
    }

    // =====================================================================
    // Public API — Distance & follow logic
    // =====================================================================

    /**
     * Computes the horizontal (XZ) distance squared between this companion
     * and the given target coordinates.
     *
     * @param tx target X
     * @param ty target Y (ignored for horizontal distance)
     * @param tz target Z
     * @return squared distance in the XZ plane
     */
    public double distanceSquared(double tx, double ty, double tz)
    {
        double dx = _x - tx;
        double dz = _z - tz;
        return dx * dx + dz * dz;
    }

    /**
     * Computes the Euclidean distance between this companion and the given
     * target coordinates.
     *
     * @param tx target X
     * @param ty target Y
     * @param tz target Z
     * @return distance in L2 units
     */
    public double distance(double tx, double ty, double tz)
    {
        return Math.sqrt(distanceSquared(tx, ty, tz));
    }

    /**
     * Checks whether the companion is too far from the owner to continue
     * comfortable following.
     *
     * <p>Uses the configured thresholds from {@link ElvenElderConfig}:
     * {@code FOLLOW_DISTANCE_MIN}, {@code FOLLOW_DISTANCE_MAX}, and
     * {@code RETURN_DISTANCE_THRESHOLD}.</p>
     *
     * @param comfortMin minimum comfortable distance
     * @param comfortMax maximum comfortable distance
     * @param maxFollow  maximum distance before forced return
     * @return true if the companion should initiate a return maneuver
     */
    public boolean isTooFar(double comfortMin, double comfortMax, double maxFollow)
    {
        double dist = Math.sqrt(distanceSquared(comfortMin, comfortMax, maxFollow));
        // We compare squared distances to avoid sqrt overhead in hot path
        // Actually we need real distance, so compute it
        double d = Math.sqrt(dist);
        return d > maxFollow;
    }

    /**
     * Convenience overload using config constants.
     *
     * @return true if companion should return to owner
     */
    public boolean shouldReturnToOwner()
    {
        // Only return if actively following
        if (_state != FollowerState.FOLLOWING)
        {
            return false;
        }

        if (_disposed || _owner == null)
        {
            return false;
        }

        // TODO: get owner's current position via _owner.getX(), etc.
        // For now, return false — actual check happens in tick loop
        return false;
    }

    /**
     * Checks whether the companion is too close to the owner (below comfort min).
     *
     * @return true if companion should move closer
     */
    public boolean isTooClose(double comfortMin)
    {
        if (_state != FollowerState.FOLLOWING)
        {
            return false;
        }
        double distSq = distanceSquared(0, 0, 0); // TODO: pass owner coords
        return distSq < (comfortMin * comfortMin);
    }

    // =====================================================================
    // Public API — Teleport / Cross-instance handling
    // =====================================================================

    /**
     * Detects whether the owner has changed instance/zone since the last tick.
     *
     * @return true if a cross-instance teleport was detected
     */
    public boolean detectCrossInstanceTeleport()
    {
        if (_disposed || _owner == null)
        {
            return false;
        }

        // TODO: retrieve owner's current instance/zone token
        //   Object currentInstanceId = ((L2PcInstance)_owner).getInstanceId();
        Object currentInstanceId = null;

        if (_lastOwnerInstanceId == null)
        {
            // First tick — record initial state
            _lastOwnerInstanceId = currentInstanceId;
            return false;
        }

        if (!currentInstanceId.equals(_lastOwnerInstanceId))
        {
            _log.info(() -> "ElvenElderCompanion: cross-instance teleport detected for playerId=" + _activeCharId);
            _lastOwnerInstanceId = currentInstanceId;
            return true;
        }

        return false;
    }

    /**
     * Validates whether a given position is legal for the companion to occupy.
     *
     * <p>A legal position is one that is:
     * <ul>
     *   <li>Not inside a wall or obstacle</li>
     *   <li>In a walkable area</li>
     *   <li>In the same instance as the owner</li>
     *   <li>Not in a disabled scenario zone</li>
     * </ul>
     * </p>
     *
     * @param x target X
     * @param y target Y
     * @param z target Z
     * @return true if the position is legal
     */
    public boolean isValidPosition(double x, double y, double z)
    {
        // TODO: implement actual position validation using L2J pathfinding /
        //   terrain APIs. Example flow:
        //
        //   1. Check if (x,y,z) is in a walkable area:
        //        L2World.getInstance().getTemplateAt(x, y, z) != null
        //   2. Check if position is in the same instance:
        //        owner.getInstanceId() == companion.getInstanceId()
        //   3. Check if position is not in a disabled scenario:
        //        !ElvenElderConfig.DISABLED_SCENARIOS.contains(zoneKey)
        //   4. Check collision / wall detection:
        //        !L2World.getInstance().isBlocked(x, y, z)

        // Placeholder — always returns true until L2J APIs are integrated
        return true;
    }

    /**
     * Finds a legal spawn position near the owner and updates the companion's
     * coordinates to that position.
     *
     * <p>Scans outward in a spiral pattern from the owner's position until
     * a valid location is found (up to a configurable radius).</p>
     *
     * @param ownerX owner's X coordinate
     * @param ownerY owner's Y coordinate
     * @param ownerZ owner's Z coordinate
     * @param searchRadius maximum search radius in L2 units
     * @return true if a legal position was found
     */
    public boolean findLegalSpawnPosition(double ownerX, double ownerY, double ownerZ, double searchRadius)
    {
        // TODO: implement spiral search pattern
        //   for radius r = 0..searchRadius step 10:
        //     for angle a = 0..360 step 15:
        //       cx = ownerX + r * cos(a)
        //       cy = ownerY + r * sin(a)
        //       cz = ownerZ  (or sample terrain height)
        //       if isValidPosition(cx, cy, cz):
        //         _x = cx; _y = cy; _z = cz;
        //         return true;

        _log.fine(() -> "ElvenElderCompanion: findLegalSpawnPosition called for playerId=" + _activeCharId + " (stub)");
        return false;
    }

    // =====================================================================
    // Public API — Movement / Following
    // =====================================================================

    /**
     * Moves the companion toward the owner using L2J's movement API.
     *
     * <p>If the distance is within the comfortable range, the companion stays
     * put.  If outside the range, it moves to a position that satisfies the
     * comfort constraints.</p>
     *
     * @param ownerX owner's X
     * @param ownerY owner's Y
     * @param ownerZ owner's Z
     */
    public void moveTowardsOwner(double ownerX, double ownerY, double ownerZ)
    {
        if (_disposed || _state != FollowerState.FOLLOWING)
        {
            return;
        }

        double distSq = distanceSquared(ownerX, ownerY, ownerZ);
        double dist = Math.sqrt(distSq);

        // Within comfortable range — do nothing
        if (dist >= ElvenElderConfig.FOLLOW_DISTANCE_MIN && dist <= ElvenElderConfig.FOLLOW_DISTANCE_MAX)
        {
            return;
        }

        // Too far — attempt to move closer
        double targetX, targetY, targetZ;

        if (dist < ElvenElderConfig.FOLLOW_DISTANCE_MIN)
        {
            // Too close — move away to comfort max
            double dx = _x - ownerX;
            double dz = _z - ownerZ;
            double len = Math.sqrt(dx * dx + dz * dz);
            if (len > 0)
            {
                targetX = ownerX + (dx / len) * ElvenElderConfig.FOLLOW_DISTANCE_MAX;
                targetZ = ownerZ + (dz / len) * ElvenElderConfig.FOLLOW_DISTANCE_MAX;
            }
            else
            {
                // Owner and companion at same spot — move along X axis
                targetX = ownerX + ElvenElderConfig.FOLLOW_DISTANCE_MAX;
                targetZ = ownerZ;
            }
            targetY = ownerY;
        }
        else
        {
            // Too far — move to comfort min distance
            double dx = _x - ownerX;
            double dz = _z - ownerZ;
            double len = Math.sqrt(dx * dx + dz * dz);
            if (len > 0)
            {
                targetX = ownerX + (dx / len) * ElvenElderConfig.FOLLOW_DISTANCE_MIN;
                targetZ = ownerZ + (dz / len) * ElvenElderConfig.FOLLOW_DISTANCE_MIN;
            }
            else
            {
                targetX = ownerX + ElvenElderConfig.FOLLOW_DISTANCE_MIN;
                targetZ = ownerZ;
            }
            targetY = ownerY;
        }

        // Validate target position before moving
        if (!isValidPosition(targetX, targetY, targetZ))
        {
            _log.fine(() -> "ElvenElderCompanion: target position invalid, attempting fallback for playerId=" + _activeCharId);
            // Fallback: try spawn position
            if (isValidPosition(_spawnX, _spawnY, _spawnZ))
            {
                targetX = _spawnX;
                targetY = _spawnY;
                targetZ = _spawnZ;
            }
            else
            {
                // Cannot find valid position — mark path failure
                incrementPathFailures();
                return;
            }
        }
        else
        {
            resetPathFailures();
        }

        // TODO: actually move the companion using L2J movement API
        //   Option A — direct moveTo:
        //     ((L2Npc)_actorInstance).getAI().setInt(CtrlIntention.ACTIVE, null);
        //     ((L2Npc)_actorInstance).getAI().setPlayerDirectCommand(true);
        //     ((L2Npc)_actorInstance).doMoveTo(targetX, targetY, targetZ, speed);
        //
        //   Option B — MoveToLocation packet:
        //     MoveToLocation ml = new MoveToLocation((L2PcInstance)_owner, targetX, targetY, targetZ);
        //     ((L2Npc)_actorInstance).sendPacket(ml);
        //
        //   Option C — Use L2Character.moveTo():
        //     ((L2Character)_actorInstance).moveTo(targetX, targetY, targetZ);

        // Update our tracked position (actual movement handled by L2J server thread)
        _x = targetX;
        _y = targetY;
        _z = targetZ;

        _log.fine(() -> "ElvenElderCompanion: moved towards owner for playerId=" + _activeCharId + " to (" + targetX + "," + targetY + "," + targetZ + ")");
    }

    /**
     * Safely teleports the companion back to the owner's vicinity.
     *
     * <p>Called when the companion is beyond {@code RETURN_DISTANCE_THRESHOLD}
     * from the owner, or after a cross-instance teleport, or when consecutive
     * pathfinding failures exceed a threshold.</p>
     *
     * @param ownerX owner's X
     * @param ownerY owner's Y
     * @param ownerZ owner's Z
     * @param maxDistance maximum allowed distance from owner after teleport
     */
    public void safeTeleportBack(double ownerX, double ownerY, double ownerZ, double maxDistance)
    {
        if (_disposed)
        {
            return;
        }

        // Step 1: Search for a legal spawn position near the owner
        boolean found = findLegalSpawnPosition(ownerX, ownerY, ownerZ, maxDistance);
        if (!found)
        {
            _log.warning(() -> "ElvenElderCompanion: could not find legal spawn position for playerId=" + _activeCharId + ", falling back to spawn point");

            // Step 2: Fallback — teleport to original spawn
            _x = _spawnX;
            _y = _spawnY;
            _z = _spawnZ;
            found = true;
        }

        if (found)
        {
            // Step 3: Execute the teleport via L2J API
            // TODO: use L2J teleport API to actually move the companion entity
            //   e.g., ((L2Npc)_actorInstance).teleto(targetX, targetY, targetZ);
            //   or send a Server->Client teleport packet

            resetPathFailures();

            _log.info(() -> "ElvenElderCompanion: safe teleport back completed for playerId=" + _activeCharId + " to (" + _x + "," + _y + "," + _z + ")");
        }
    }

    // =====================================================================
    // Public API — Tick / Heartbeat
    // =====================================================================

    /**
     * Main tick handler — called every {@code TICK_INTERVAL_MS} ms by the
     * service layer's scheduler.
     *
     * <p>Performs the following checks in order:</p>
     * <ol>
     *   <li>If disposed or dismissed, return immediately.</li>
     *   <li>If in WAITING state, skip all movement logic.</li>
     *   <li>Check for cross-instance teleport — if detected, teleport companion.</li>
     *   <li>Check if companion is too far — if so, initiate safe return.</li>
     *   <li>Check consecutive path failures — if threshold exceeded, force return.</li>
     *   <li>Otherwise, move towards owner within comfort range.</li>
     * </ol>
     */
    public void onTick()
    {
        if (_disposed)
        {
            return;
        }

        // WAITING state — do not follow
        if (_state == FollowerState.WAITING)
        {
            return;
        }

        if (_owner == null)
        {
            // Owner reference lost — clean up
            _log.severe(() -> "ElvenElderCompanion: owner reference lost for playerId=" + _activeCharId + ", dismissing");
            dismiss();
            return;
        }

        // TODO: retrieve owner's current position
        //   double ownerX = ((L2PcInstance)_owner).getX();
        //   double ownerY = ((L2PcInstance)_owner).getY();
        //   double ownerZ = ((L2PcInstance)_owner).getZ();
        double ownerX = 0, ownerY = 0, ownerZ = 0;

        // Step 1: Detect cross-instance teleport
        if (detectCrossInstanceTeleport())
        {
            _log.info(() -> "ElvenElderCompanion: cross-instance teleport detected, initiating safe return for playerId=" + _activeCharId);
            safeTeleportBack(ownerX, ownerY, ownerZ, ElvenElderConfig.FOLLOW_DISTANCE_MAX);
            return;
        }

        // Step 2: Check if too far — initiate safe return
        double dist = Math.sqrt(distanceSquared(ownerX, ownerY, ownerZ));
        if (dist > ElvenElderConfig.RETURN_DISTANCE_THRESHOLD)
        {
            _log.info(() -> "ElvenElderCompanion: too far from owner (" + (int)dist + " units), initiating safe return for playerId=" + _activeCharId);
            safeTeleportBack(ownerX, ownerY, ownerZ, ElvenElderConfig.FOLLOW_DISTANCE_MAX);
            return;
        }

        // Step 3: Check consecutive path failures
        if (_consecutivePathFailures >= 3)
        {
            _log.warning(() -> "ElvenElderCompanion: consecutive path failures (" + _consecutivePathFailures + "), forcing safe return for playerId=" + _activeCharId);
            safeTeleportBack(ownerX, ownerY, ownerZ, ElvenElderConfig.FOLLOW_DISTANCE_MAX);
            return;
        }

        // Step 4: Normal follow — move towards owner within comfort range
        moveTowardsOwner(ownerX, ownerY, ownerZ);
    }

    // =====================================================================
    // Public API — Disabled scenario handling
    // =====================================================================

    /**
     * Checks whether the companion's current location is in a disabled scenario.
     *
     * @return true if the companion is in a forbidden zone
     */
    public boolean isInDisabledScenario()
    {
        if (_disposed)
        {
            return false;
        }

        // TODO: check the companion's current zone/area against
        //   ElvenElderConfig.DISABLED_SCENARIOS
        //   Example:
        //     int zoneId = ((L2Character)_actorInstance).getCurrentZone().getId();
        //     String zoneKey = zoneToScenarioKey(zoneId);
        //     return ElvenElderConfig.DISABLED_SCENARIOS.contains(zoneKey);

        return false;
    }

    // =====================================================================
    // Public API — State serialization helpers
    // =====================================================================

    /**
     * Returns a snapshot of the companion's current state as a map.
     *
     * <p>Used for potential future persistence (saving companion state across
     * server restarts).</p>
     *
     * @return a map of state key-value pairs
     */
    public java.util.Map<String, Object> toStateMap()
    {
        java.util.Map<String, Object> map = new java.util.HashMap<>();
        map.put("activeCharId", _activeCharId);
        map.put("x", _x);
        map.put("y", _y);
        map.put("z", _z);
        map.put("spawnX", _spawnX);
        map.put("spawnY", _spawnY);
        map.put("spawnZ", _spawnZ);
        map.put("state", _state.name());
        map.put("buffEnabled", _buffEnabled);
        map.put("consecutivePathFailures", _consecutivePathFailures);
        map.put("disposed", _disposed);
        return map;
    }

    /**
     * Restores the companion's state from a previously saved map.
     *
     * <p>Used for loading persisted companion data after server restart.</p>
     *
     * @param map the state map to restore from
     */
    public void fromStateMap(java.util.Map<String, Object> map)
    {
        if (map == null)
        {
            return;
        }

        Object stateObj = map.get("state");
        if (stateObj != null)
        {
            try
            {
                _state = FollowerState.valueOf(stateObj.toString());
            }
            catch (IllegalArgumentException e)
            {
                _log.warning(() -> "ElvenElderCompanion: invalid state '" + stateObj + "', defaulting to FOLLOWING");
                _state = FollowerState.FOLLOWING;
            }
        }

        Object buffObj = map.get("buffEnabled");
        if (buffObj instanceof Boolean)
        {
            _buffEnabled = (Boolean) buffObj;
        }

        Object failuresObj = map.get("consecutivePathFailures");
        if (failuresObj instanceof Integer)
        {
            _consecutivePathFailures = (Integer) failuresObj;
        }

        _log.info(() -> "ElvenElderCompanion: state restored for playerId=" + _activeCharId);
    }

    // =====================================================================
    // toString
    // =====================================================================

    @Override
    public String toString()
    {
        return "ElvenElderCompanion{" +
            "activeCharId=" + _activeCharId +
            ", pos=(" + _x + "," + _y + "," + _z + ")" +
            ", spawn=(" + _spawnX + "," + _spawnY + "," + _spawnZ + ")" +
            ", state=" + _state +
            ", buffEnabled=" + _buffEnabled +
            ", pathFailures=" + _consecutivePathFailures +
            ", disposed=" + _disposed +
            '}';
    }
}

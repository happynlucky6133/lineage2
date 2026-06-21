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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.l2jserver.gameserver.ThreadPoolManager;
import com.l2jserver.gameserver.data.xml.impl.NpcData;
import com.l2jserver.gameserver.idfactory.IdFactory;
import com.l2jserver.gameserver.model.actor.instance.L2PcInstance;
import com.l2jserver.gameserver.model.actor.instance.L2ServitorInstance;
import com.l2jserver.gameserver.model.actor.templates.L2NpcTemplate;
import com.l2jserver.gameserver.model.zone.ZoneId;

/**
 * Manages Elven Elder companion lifecycle.
 * Singleton — one companion per player, tracked by owner's objectId.
 */
public final class ElvenElderCompanionManager
{
	private static final Logger LOG = LoggerFactory.getLogger(ElvenElderCompanionManager.class);

	private static final ElvenElderCompanionManager INSTANCE = new ElvenElderCompanionManager();

	private final Map<Integer, CompanionSlot> _companions = new ConcurrentHashMap<>();

	private ElvenElderCompanionManager()
	{
	}

	public static ElvenElderCompanionManager getInstance()
	{
		return INSTANCE;
	}

	// ---- Public API ----

	/**
	 * Recruit a companion for the player.
	 * @return true if recruitment succeeded or already active
	 */
	public synchronized boolean recruit(L2PcInstance owner)
	{
		if (!ElvenElderConfig.COMPANION_ENABLED)
		{
			LOG.info("ElvenElderManager: recruit denied — feature disabled");
			return false;
		}

		if (owner == null)
		{
			return false;
		}

		if (!owner.isOnline() || owner.isDead() || !isAllowed(owner))
		{
			return false;
		}

		int ownerId = owner.getObjectId();

		// Idempotent check
		CompanionSlot existing = _companions.get(ownerId);
		if (existing != null && !existing._companion.isDead())
		{
			LOG.info("ElvenElderManager: player {} already has a companion", owner.getName());
			return true;
		}

		if (existing != null)
		{
			// Clean up dead companion
			cleanupSlot(ownerId, existing);
		}

		// L2J supports a single pet/servitor slot per player. Never replace a
		// legitimate summon silently.
		if (owner.hasSummon())
		{
			LOG.info("ElvenElderManager: recruit denied for {} — summon slot is occupied", owner.getName());
			return false;
		}

		try
		{
			L2NpcTemplate template = NpcData.getInstance().getTemplate(ElvenElderConfig.COMPANION_NPC_ID);
			if (template == null)
			{
				LOG.warn("ElvenElderManager: companion NPC template {} not found!", ElvenElderConfig.COMPANION_NPC_ID);
				return false;
			}

			int objectId = IdFactory.getInstance().getNextId();
			L2ServitorInstance companion = new L2ServitorInstance(objectId, template, owner);
			companion.setName("Elven Elder");
			companion.setTitle(owner.getName() + "'s Companion");
			companion.setCurrentHp(companion.getMaxHp());
			companion.setCurrentMp(companion.getMaxMp());
			companion.setHeading(owner.getHeading());
			owner.setPet(companion);
			companion.setRunning();
			companion.spawnMe(owner.getX() + 50, owner.getY() + 50, owner.getZ());
			companion.setFollowStatus(true);

			// Create brain and schedule ticks
			ElvenElderBrain brain = new ElvenElderBrain(companion, this);
			Future<?> tickTask = ThreadPoolManager.getInstance().scheduleGeneralAtFixedRate(brain, 0, ElvenElderConfig.TICK_INTERVAL_MS);

			CompanionSlot slot = new CompanionSlot(ownerId, companion, brain, tickTask);
			_companions.put(ownerId, slot);

			LOG.info("ElvenElderManager: companion recruited for {}", owner.getName());
			return true;
		}
		catch (Exception e)
		{
			if (owner.getSummon() != null && owner.getSummon().getId() == ElvenElderConfig.COMPANION_NPC_ID)
			{
				owner.getSummon().deleteMe(owner);
			}
			LOG.warn("ElvenElderManager: recruit failed for {}", owner.getName(), e);
			return false;
		}
	}

	/**
	 * Dismiss the companion for the given owner.
	 */
	public synchronized void dismiss(L2PcInstance owner, boolean sendMessage)
	{
		if (owner == null)
		{
			return;
		}

		int ownerId = owner.getObjectId();
		CompanionSlot slot = _companions.remove(ownerId);
		if (slot != null)
		{
			cleanupSlot(ownerId, slot);
			LOG.info("ElvenElderManager: companion dismissed for {}", owner.getName());
		}
	}

	/**
	 * Remove a slot even when the owner reference has already disappeared.
	 */
	public synchronized void dismiss(int ownerId)
	{
		CompanionSlot slot = _companions.remove(ownerId);
		if (slot != null)
		{
			cleanupSlot(ownerId, slot);
		}
	}

	/**
	 * Called when the companion dies.
	 */
	public synchronized void onCompanionDeath(L2PcInstance owner)
	{
		if (owner == null)
		{
			return;
		}

		int ownerId = owner.getObjectId();
		CompanionSlot slot = _companions.remove(ownerId);
		if (slot != null)
		{
			cleanupSlot(ownerId, slot);
			LOG.info("ElvenElderManager: companion died for {}", owner.getName());
		}
	}

	/**
	 * Called on player logout.
	 */
	public void onPlayerLogout(L2PcInstance owner)
	{
		if (owner == null)
		{
			return;
		}

		dismiss(owner, false);
	}

	/**
	 * Toggle buff mode for a player's companion.
	 */
	public void setBuffEnabled(L2PcInstance owner, boolean enabled)
	{
		if (owner == null)
		{
			return;
		}
		CompanionSlot slot = _companions.get(owner.getObjectId());
		if (slot != null)
		{
			slot._buffEnabled.set(enabled);
		}
	}

	/**
	 * Check if buffs are enabled for this player's companion.
	 */
	public boolean isBuffEnabled(L2PcInstance owner)
	{
		if (owner == null)
		{
			return false;
		}
		CompanionSlot slot = _companions.get(owner.getObjectId());
		return slot != null && slot._buffEnabled.get();
	}

	public void setFollowing(L2PcInstance owner, boolean following)
	{
		if (owner == null)
		{
			return;
		}
		CompanionSlot slot = _companions.get(owner.getObjectId());
		if (slot != null && !slot._companion.isDead())
		{
			slot._companion.setFollowStatus(following);
		}
	}

	public boolean isFollowing(L2PcInstance owner)
	{
		L2ServitorInstance companion = getCompanion(owner);
		return companion != null && companion.getFollowStatus();
	}

	/**
	 * Check if player has an active companion.
	 */
	public boolean hasCompanion(L2PcInstance owner)
	{
		if (owner == null)
		{
			return false;
		}
		CompanionSlot slot = _companions.get(owner.getObjectId());
		return slot != null && !slot._companion.isDead();
	}

	/**
	 * Get the active companion for a player.
	 */
	public L2ServitorInstance getCompanion(L2PcInstance owner)
	{
		if (owner == null)
		{
			return null;
		}
		CompanionSlot slot = _companions.get(owner.getObjectId());
		return slot != null ? slot._companion : null;
	}

	// ---- Internal ----

	private void cleanupSlot(int ownerId, CompanionSlot slot)
	{
		// Cancel tick task
		if (slot._tickTask != null)
		{
			slot._tickTask.cancel(false);
			slot._tickTask = null;
		}

		// Despawn companion
		if (slot._companion != null)
		{
			L2PcInstance owner = slot._companion.getOwner();
			if (slot._companion.isVisible() && !slot._companion.isDead())
			{
				slot._companion.unSummon(owner);
			}
			else
			{
				slot._companion.deleteMe(owner);
			}
		}
	}

	public boolean isAllowed(L2PcInstance owner)
	{
		return owner != null && !owner.isInOlympiadMode() && !owner.isInsideZone(ZoneId.SIEGE) && !owner.isInsideZone(ZoneId.PVP);
	}

	/**
	 * Internal holder for per-player companion state.
	 */
	private static final class CompanionSlot
	{
		final int _ownerId;
		final L2ServitorInstance _companion;
		final ElvenElderBrain _brain;
		final AtomicBoolean _buffEnabled = new AtomicBoolean(true);
		Future<?> _tickTask;

		CompanionSlot(int ownerId, L2ServitorInstance companion, ElvenElderBrain brain, Future<?> tickTask)
		{
			_ownerId = ownerId;
			_companion = companion;
			_brain = brain;
			_tickTask = tickTask;
		}
	}
}

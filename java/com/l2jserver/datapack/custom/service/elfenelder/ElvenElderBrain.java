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

import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.l2jserver.gameserver.ai.CtrlIntention;
import com.l2jserver.gameserver.datatables.SkillData;
import com.l2jserver.gameserver.model.actor.L2Character;
import com.l2jserver.gameserver.model.actor.instance.L2PcInstance;
import com.l2jserver.gameserver.model.actor.instance.L2ServitorInstance;
import com.l2jserver.gameserver.model.skills.Skill;

/**
 * AI brain for the Elven Elder companion.
 * Priority-based decision loop running every TICK_INTERVAL_MS.
 *
 * Decision priority:
 * 1. Survival checks (owner offline/dead/disabled-zone → dismiss)
 * 2. Emergency heal (owner HP < 35%)
 * 3. Normal heal (owner HP < 70%, not in energy-save mode)
 * 4. Self-heal (companion HP < 55%, owner not emergency)
 * 5. Buff (if enabled, MP sufficient, missing buffs)
 * 6. Follow (if too far)
 * 7. Idle
 */
public class ElvenElderBrain implements Runnable
{
	private static final Logger LOG = LoggerFactory.getLogger(ElvenElderBrain.class);

	private final L2ServitorInstance _companion;
	private final ElvenElderCompanionManager _manager;
	private final int _ownerId;
	private final ReentrantLock _lock = new ReentrantLock();

	private long _lastHealCastTime = 0;
	private long _lastBuffCastTime = 0;

	public ElvenElderBrain(L2ServitorInstance companion, ElvenElderCompanionManager manager)
	{
		_companion = Objects.requireNonNull(companion);
		_manager = Objects.requireNonNull(manager);
		_ownerId = companion.getOwner().getObjectId();
	}

	@Override
	public void run()
	{
		if (!_lock.tryLock())
		{
			return; // Skip if already running
		}

		try
		{
			if (_companion.isDead() || _companion.isAlikeDead())
			{
				_manager.onCompanionDeath(_companion.getOwner());
				return;
			}

			L2PcInstance owner = _companion.getOwner();
			if (owner == null || !owner.isOnline())
			{
				_manager.dismiss(_ownerId);
				return;
			}

			// 1. Survival / legality
			if (owner.isDead() || owner.isAlikeDead())
			{
				// Owner died — companion stays, but won't heal a dead target
				return;
			}

			if (!_manager.isAllowed(owner))
			{
				_manager.dismiss(owner, false);
				return;
			}

			// Keep instance/position coherent after teleports. Native summon AI
			// handles ordinary pathing; this is the stuck/long-distance fallback.
			if ((_companion.getInstanceId() != owner.getInstanceId()) || !_companion.isInsideRadius(owner, ElvenElderConfig.RETURN_DISTANCE_THRESHOLD, true, false))
			{
				_companion.teleToLocation(owner.getX(), owner.getY(), owner.getZ(), owner.getHeading(), owner.getInstanceId(), ElvenElderConfig.TELEPORT_RETURN_RADIUS);
				return;
			}

			if (_companion.isCastingNow() || _companion.isAllSkillsDisabled())
			{
				return;
			}

			// 2. Emergency heal (owner HP < 35%)
			if (tryHeal(owner, ElvenElderConfig.HEAL_EMERGENCY_THRESHOLD, true))
			{
				return;
			}

			// 3. Normal heal (owner HP < 70%, not energy saving)
			if (!isEnergySavingMode() && tryHeal(owner, ElvenElderConfig.HEAL_NORMAL_THRESHOLD, false))
			{
				return;
			}

			// 4. Self-heal (companion HP < 55%, owner not emergency)
			if (trySelfHeal(owner))
			{
				return;
			}

			// 5. Buff
			if (_manager.isBuffEnabled(owner))
			{
				tryBuff(owner);
			}

			// 6. Follow — L2SummonAI handles this natively, nothing extra needed
		}
		catch (Exception e)
		{
			LOG.warn("ElvenElderBrain tick error for owner {}", _companion.getOwner() != null ? _companion.getOwner().getName() : "null", e);
		}
		finally
		{
			_lock.unlock();
		}
	}

	/**
	 * Attempt to heal the owner.
	 */
	private boolean tryHeal(L2PcInstance owner, double threshold, boolean isEmergency)
	{
		double ownerHpPercent = owner.getCurrentHp() / Math.max(owner.getMaxHp(), 1);
		if (ownerHpPercent >= threshold)
		{
			return false;
		}

		// Anti-spam: don't recast if a heal is in progress
		if (System.currentTimeMillis() - _lastHealCastTime < 1000)
		{
			return false;
		}

		int skillId = isEmergency ? ElvenElderConfig.getEmergencyHealSkillId() : ElvenElderConfig.getNormalHealSkillId();
		int skillLevel = getBestSkillLevel(skillId);
		if (skillLevel <= 0)
		{
			return false;
		}

		Skill skill = SkillData.getInstance().getSkill(skillId, skillLevel);
		if (skill == null)
		{
			return false;
		}

		double mp = _companion.getCurrentMp();
		if (mp < (skill.getMpConsume1() + skill.getMpConsume2()))
		{
			if (!isEmergency)
			{
				return false; // Skip normal heal, try emergency
			}
			// For emergency, try lower level
			skill = findAffordableSkill(skillId, mp);
			if (skill == null)
			{
				return false;
			}
		}

		LOG.debug("ElvenElderBrain: {} heal on {} (HP {}%)", isEmergency ? "EMERGENCY" : "NORMAL", owner.getName(), String.format("%.1f", ownerHpPercent * 100));
		if (!cast(skill, owner))
		{
			return false;
		}
		_lastHealCastTime = System.currentTimeMillis();
		return true;
	}

	/**
	 * Attempt to self-heal.
	 */
	private boolean trySelfHeal(L2PcInstance owner)
	{
		double selfHp = _companion.getCurrentHp() / Math.max(_companion.getMaxHp(), 1);
		if (selfHp >= ElvenElderConfig.SELF_HEAL_THRESHOLD)
		{
			return false;
		}

		// Don't self-heal if owner is in emergency
		double ownerHp = owner.getCurrentHp() / Math.max(owner.getMaxHp(), 1);
		if (ownerHp < ElvenElderConfig.HEAL_EMERGENCY_THRESHOLD)
		{
			return false;
		}

		int skillId = ElvenElderConfig.getSelfHealSkillId();
		int skillLevel = getBestSkillLevel(skillId);
		if (skillLevel <= 0)
		{
			return false;
		}

		Skill skill = SkillData.getInstance().getSkill(skillId, skillLevel);
		if (skill == null)
		{
			return false;
		}

		if (_companion.getCurrentMp() < (skill.getMpConsume1() + skill.getMpConsume2()))
		{
			return false;
		}

		LOG.debug("ElvenElderBrain: SELF-HEAL (HP {}%)", String.format("%.1f", selfHp * 100));
		if (!cast(skill, _companion))
		{
			return false;
		}
		_lastHealCastTime = System.currentTimeMillis();
		return true;
	}

	/**
	 * Attempt to apply a missing buff.
	 */
	private void tryBuff(L2PcInstance owner)
	{
		if (ElvenElderConfig.BUFF_WHITELIST.length == 0)
		{
			return;
		}
		long now = System.currentTimeMillis();
		if (now - _lastBuffCastTime < ElvenElderConfig.BUFF_COOLDOWN_MS)
		{
			return;
		}

		if (isEnergySavingMode())
		{
			return;
		}

		for (int buffId : ElvenElderConfig.BUFF_WHITELIST)
		{
			if (owner.getEffectList().getBuffInfoBySkillId(buffId) != null)
			{
				continue; // Already active
			}

			int level = getBestSkillLevel(buffId);
			if (level <= 0)
			{
				continue;
			}

			Skill skill = SkillData.getInstance().getSkill(buffId, level);
			if (skill == null)
			{
				continue;
			}

			if (_companion.getCurrentMp() < (skill.getMpConsume1() + skill.getMpConsume2()))
			{
				continue;
			}

			LOG.debug("ElvenElderBrain: buff {} on {}", skill.getName(), owner.getName());
			if (cast(skill, owner))
			{
				_lastBuffCastTime = now;
				return; // One buff per tick
			}
		}
	}

	/**
	 * Returns the highest level of a skill that this companion knows.
	 */
	private int getBestSkillLevel(int skillId)
	{
		Skill skill = _companion.getSkills().get(skillId);
		if (skill != null)
		{
			return skill.getLevel();
		}
		return 0;
	}

	/**
	 * Find the highest affordable level of a skill.
	 */
	private Skill findAffordableSkill(int skillId, double availableMp)
	{
		Skill best = null;
		for (int level = getBestSkillLevel(skillId); level > 0; level--)
		{
			Skill s = SkillData.getInstance().getSkill(skillId, level);
			if (s != null && (s.getMpConsume1() + s.getMpConsume2()) <= availableMp)
			{
				best = s;
				break;
			}
		}
		return best;
	}

	private boolean isEnergySavingMode()
	{
		double mpPercent = _companion.getCurrentMp() / Math.max(_companion.getMaxMp(), 1);
		return mpPercent < ElvenElderConfig.MP_CONSERVATION_THRESHOLD;
	}

	private boolean cast(Skill skill, L2Character target)
	{
		if (!_companion.checkDoCastConditions(skill) || target == null || target.isDead())
		{
			return false;
		}
		_companion.setTarget(target);
		_companion.getAI().setIntention(CtrlIntention.AI_INTENTION_CAST, skill, target);
		return true;
	}
}

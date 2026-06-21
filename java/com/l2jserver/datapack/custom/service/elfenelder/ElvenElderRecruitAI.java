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

import com.l2jserver.gameserver.model.actor.L2Npc;
import com.l2jserver.gameserver.model.actor.instance.L2PcInstance;
import com.l2jserver.gameserver.model.actor.instance.L2ServitorInstance;
import com.l2jserver.gameserver.model.quest.Quest;

/**
 * NPC AI for the Elven Elder recruiter.
 * Handles recruitment, status display, dismiss, and toggle commands via HTML dialogs.
 */
public final class ElvenElderRecruitAI extends Quest
{
	private static final ElvenElderCompanionManager _manager = ElvenElderCompanionManager.getInstance();

	public ElvenElderRecruitAI()
	{
		super(-1, ElvenElderRecruitAI.class.getSimpleName());

		if (ElvenElderConfig.COMPANION_ENABLED)
		{
			bindStartNpc(ElvenElderConfig.RECRUITER_NPC_ID);
			bindTalk(ElvenElderConfig.RECRUITER_NPC_ID);
			bindFirstTalk(ElvenElderConfig.RECRUITER_NPC_ID);
		}
	}

	@Override
	public String onEvent(String event, L2Npc npc, L2PcInstance player)
	{
		if (!ElvenElderConfig.COMPANION_ENABLED)
		{
			return npc == null ? null : getNoQuestMsg(player);
		}

		switch (event)
		{
			case "recruit":
			{
				if (_manager.recruit(player))
				{
					return buildStatusHtml(npc, player, "Companion has been summoned!");
				}
				return buildStatusHtml(npc, player, "Failed to recruit companion. You may already have one.");
			}
			case "dismiss":
			{
				_manager.dismiss(player, true);
				return buildMainMenu(npc, player);
			}
			case "toggle_buff":
			{
				boolean current = _manager.isBuffEnabled(player);
				_manager.setBuffEnabled(player, !current);
				return buildMainMenu(npc, player);
			}
			case "toggle_follow":
			{
				_manager.setFollowing(player, !_manager.isFollowing(player));
				return buildMainMenu(npc, player);
			}
			case "status":
			{
				return buildStatusHtml(npc, player, null);
			}
			case "menu":
			{
				return buildMainMenu(npc, player);
			}
			default:
			{
				return buildMainMenu(npc, player);
			}
		}
	}

	@Override
	public String onFirstTalk(L2Npc npc, L2PcInstance player)
	{
		if (!ElvenElderConfig.COMPANION_ENABLED)
		{
			return getNoQuestMsg(player);
		}
		return buildMainMenu(npc, player);
	}

	@Override
	public String onTalk(L2Npc npc, L2PcInstance player)
	{
		return buildMainMenu(npc, player);
	}

	private String buildMainMenu(L2Npc npc, L2PcInstance player)
	{
		boolean hasCompanion = _manager.hasCompanion(player);
		boolean buffEnabled = _manager.isBuffEnabled(player);
		boolean following = _manager.isFollowing(player);
		String buffStatus = buffEnabled ? "Enabled" : "Disabled";
		String buffAction = buffEnabled ? "disable" : "enable";

		StringBuilder html = new StringBuilder();
		html.append("<html><body><center>");
		html.append("<font color=\"LEVEL\">Elven Elder Recruiter</font><br>");
		html.append("I can summon an Elven Elder to accompany you.<br><br>");

		if (!hasCompanion)
		{
			html.append("<button value=\"Summon Companion\" action=\"bypass -h Quest ").append(getName()).append(" recruit\" width=200 height=27 back=L2UI_CT1.Button_DF_Down fore=L2UI_CT1.Button_DF><br>");
		}
		else
		{
			html.append("<font color=\"00FF00\">You have an active companion.</font><br><br>");
			html.append("<button value=\"Check Status\" action=\"bypass -h Quest ").append(getName()).append(" status\" width=200 height=27 back=L2UI_CT1.Button_DF_Down fore=L2UI_CT1.Button_DF><br>");
			html.append("<button value=\"Dismiss Companion\" action=\"bypass -h Quest ").append(getName()).append(" dismiss\" width=200 height=27 back=L2UI_CT1.Button_DF_Down fore=L2UI_CT1.Button_DF><br>");
			html.append("<button value=\"Buff: ").append(buffStatus).append(" (click to ").append(buffAction).append(")\" action=\"bypass -h Quest ").append(getName()).append(" toggle_buff\" width=200 height=27 back=L2UI_CT1.Button_DF_Down fore=L2UI_CT1.Button_DF><br>");
			html.append("<button value=\"").append(following ? "Wait Here" : "Follow Me").append("\" action=\"bypass -h Quest ").append(getName()).append(" toggle_follow\" width=200 height=27 back=L2UI_CT1.Button_DF_Down fore=L2UI_CT1.Button_DF><br>");
		}

		html.append("</center></body></html>");
		return html.toString();
	}

	private String buildStatusHtml(L2Npc npc, L2PcInstance player, String message)
	{
		L2ServitorInstance companion = _manager.getCompanion(player);
		StringBuilder html = new StringBuilder();
		html.append("<html><body><center>");
		html.append("<font color=\"LEVEL\">Elven Elder Companion Status</font><br>");

		if (companion != null && !companion.isDead())
		{
			int hpPercent = (int) ((companion.getCurrentHp() / Math.max(companion.getMaxHp(), 1)) * 100);
			int mpPercent = (int) ((companion.getCurrentMp() / Math.max(companion.getMaxMp(), 1)) * 100);
			int ownerHpPercent = (int) ((player.getCurrentHp() / Math.max(player.getMaxHp(), 1)) * 100);

			html.append("Companion HP: ").append(hpPercent).append("%<br>");
			html.append("Companion MP: ").append(mpPercent).append("%<br>");
			html.append("Your HP: ").append(ownerHpPercent).append("%<br>");
			html.append("Buff: ").append(_manager.isBuffEnabled(player) ? "Enabled" : "Disabled").append("<br>");
			html.append("Movement: ").append(_manager.isFollowing(player) ? "Following" : "Waiting").append("<br>");
			html.append("Level: ").append(companion.getLevel()).append("<br>");
		}
		else
		{
			html.append("No active companion.<br>");
		}

		if (message != null && !message.isEmpty())
		{
			html.append("<br><font color=\"LEVEL\">").append(message).append("</font><br>");
		}

		html.append("<br><button value=\"Back\" action=\"bypass -h Quest ").append(getName()).append(" menu\" width=100 height=27 back=L2UI_CT1.Button_DF_Down fore=L2UI_CT1.Button_DF>");
		html.append("</center></body></html>");
		return html.toString();
	}

	public static void main(String[] args)
	{
		new ElvenElderRecruitAI();
	}
}

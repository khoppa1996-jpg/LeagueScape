package com.leaguescape.task;

import com.leaguescape.LeagueScapeSounds;
import com.leaguescape.area.AreaGraphService;
import com.leaguescape.data.Area;
import com.leaguescape.overlay.TaskCompletionPopupOverlay;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.StatChanged;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.InventoryID;
import net.runelite.client.audio.AudioPlayer;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.api.Skill;

/**
 * Listens to game events and auto-completes revealed tasks in the player's current area when conditions are met.
 * Triggers the completion popup and task-complete sound. One completion per event.
 * <p>
 * taskType values align with the task schema (e.g. tasks_json_object_properties.json): Mining, Cooking, Fishing,
 * Quest, Combat, Equipment, Woodcutting, Firemaking, Prayer, Crafting, Fletching, etc.
 */
@Slf4j
@Singleton
public class TaskCompletionListener
{
	private static final Pattern NUMBER_PREFIX = Pattern.compile("^(\\d+)\\s+(.+)$");
	private static final Pattern CHAT_YOU_GET = Pattern.compile("You get (?:some |a )?(.+)", Pattern.CASE_INSENSITIVE);
	private static final Pattern CHAT_YOU_COOK = Pattern.compile("You successfully cook (.+)", Pattern.CASE_INSENSITIVE);
	private static final Pattern CHAT_YOU_CATCH = Pattern.compile("You catch (?:a |some )?(.+)", Pattern.CASE_INSENSITIVE);
	private static final Pattern CHAT_QUEST_COMPLETE = Pattern.compile("You have completed (.+)", Pattern.CASE_INSENSITIVE);
	private static final Pattern CHAT_FIREMAKING = Pattern.compile("The fire catches and the logs begin to burn\\.", Pattern.CASE_INSENSITIVE);
	private static final Pattern CHAT_YOU_BURN = Pattern.compile("You burn (?:the |some )?(.+)", Pattern.CASE_INSENSITIVE);
	private static final Pattern CHAT_YOU_LIGHT = Pattern.compile("You light (?:a |the )?(.+)", Pattern.CASE_INSENSITIVE);
	private static final Pattern CHAT_YOU_BURY = Pattern.compile("You bury (?:the |some |a )?(.+)", Pattern.CASE_INSENSITIVE);
	private static final Pattern CHAT_YOU_MAKE = Pattern.compile("You make (?:a |an |some )?(.+)", Pattern.CASE_INSENSITIVE);

	private static final Pattern EQUIP_AN = Pattern.compile("^Equip an (.+)$", Pattern.CASE_INSENSITIVE);
	private static final Pattern EQUIP_A = Pattern.compile("^Equip a (.+)$", Pattern.CASE_INSENSITIVE);

	private final Client client;
	private final AreaGraphService areaGraphService;
	private final TaskGridService taskGridService;
	private final TaskCompletionPopupOverlay popup;
	private final AudioPlayer audioPlayer;
	private final ItemManager itemManager;

	/** Progress for count-based tasks: key = areaId + "|" + taskId, value = current count. */
	private final Map<String, Integer> progress = new ConcurrentHashMap<>();

	@Inject
	public TaskCompletionListener(Client client, AreaGraphService areaGraphService, TaskGridService taskGridService,
		TaskCompletionPopupOverlay popup, AudioPlayer audioPlayer, ItemManager itemManager)
	{
		this.client = client;
		this.areaGraphService = areaGraphService;
		this.taskGridService = taskGridService;
		this.popup = popup;
		this.audioPlayer = audioPlayer;
		this.itemManager = itemManager;
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (event.getType() != ChatMessageType.GAMEMESSAGE && event.getType() != ChatMessageType.SPAM)
			return;
		String msg = event.getMessage();
		if (msg == null || msg.isEmpty()) return;

		Area area = currentUnlockedArea();
		if (area == null) return;
		List<TaskTile> revealed = taskGridService.getRevealedTiles(area.getId());
		if (revealed.isEmpty()) return;

		// Mining / Woodcutting: "You get some tin ore" / "You get some logs" (taskType from schema: Mining, Woodcutting)
		Matcher getMatcher = CHAT_YOU_GET.matcher(msg);
		if (getMatcher.matches())
		{
			String item = normalize(getMatcher.group(1));
			for (TaskTile tile : revealed)
			{
				String type = tile.getTaskType();
				if (!"Mining".equals(type) && !"Woodcutting".equals(type)) continue;
				if (!displayNameContains(tile.getDisplayName(), item)) continue;
				if (incrementAndCheck(area.getId(), tile, 1))
				{
					completeAndNotify(area.getId(), tile);
					return;
				}
			}
			return;
		}

		// Cooking: "You successfully cook (item)"
		Matcher cookMatcher = CHAT_YOU_COOK.matcher(msg);
		if (cookMatcher.matches())
		{
			String item = normalize(cookMatcher.group(1));
			for (TaskTile tile : revealed)
			{
				if (!"Cooking".equals(tile.getTaskType())) continue;
				if (!displayNameContains(tile.getDisplayName(), item)) continue;
				completeAndNotify(area.getId(), tile);
				return;
			}
			return;
		}

		// Fishing: "You catch a shrimp"
		Matcher catchMatcher = CHAT_YOU_CATCH.matcher(msg);
		if (catchMatcher.matches())
		{
			String item = normalize(catchMatcher.group(1));
			for (TaskTile tile : revealed)
			{
				if (!"Fishing".equals(tile.getTaskType())) continue;
				if (!displayNameContains(tile.getDisplayName(), item)) continue;
				completeAndNotify(area.getId(), tile);
				return;
			}
			return;
		}

		// Quest: "You have completed ..." (taskType: Quest)
		Matcher questMatcher = CHAT_QUEST_COMPLETE.matcher(msg);
		if (questMatcher.matches())
		{
			for (TaskTile tile : revealed)
			{
				if (!"Quest".equals(tile.getTaskType())) continue;
				if (tile.getDisplayName() != null && tile.getDisplayName().toLowerCase().contains("complete") && tile.getDisplayName().toLowerCase().contains("quest"))
				{
					completeAndNotify(area.getId(), tile);
					return;
				}
			}
			return;
		}

		// Firemaking: "The fire catches and the logs begin to burn." (taskType: Firemaking; message doesn't specify log type, complete first revealed)
		if (CHAT_FIREMAKING.matcher(msg).matches())
		{
			for (TaskTile tile : revealed)
			{
				if (!"Firemaking".equals(tile.getTaskType())) continue;
				completeAndNotify(area.getId(), tile);
				return;
			}
			return;
		}
		// Fallback: "You burn the X" / "You light a X" (match by item name)
		Matcher burnMatcher = CHAT_YOU_BURN.matcher(msg);
		if (burnMatcher.matches())
		{
			String what = normalize(burnMatcher.group(1));
			for (TaskTile tile : revealed)
			{
				if (!"Firemaking".equals(tile.getTaskType())) continue;
				if (!displayNameContains(tile.getDisplayName(), what)) continue;
				completeAndNotify(area.getId(), tile);
				return;
			}
			return;
		}
		Matcher lightMatcher = CHAT_YOU_LIGHT.matcher(msg);
		if (lightMatcher.matches())
		{
			String what = normalize(lightMatcher.group(1));
			for (TaskTile tile : revealed)
			{
				if (!"Firemaking".equals(tile.getTaskType())) continue;
				if (!displayNameContains(tile.getDisplayName(), what)) continue;
				completeAndNotify(area.getId(), tile);
				return;
			}
			return;
		}

		// Prayer: "You bury the bones" (taskType: Prayer)
		Matcher buryMatcher = CHAT_YOU_BURY.matcher(msg);
		if (buryMatcher.matches())
		{
			String what = normalize(buryMatcher.group(1));
			for (TaskTile tile : revealed)
			{
				if (!"Prayer".equals(tile.getTaskType())) continue;
				if (!displayNameContains(tile.getDisplayName(), what)) continue;
				if (incrementAndCheck(area.getId(), tile, 1))
				{
					completeAndNotify(area.getId(), tile);
					return;
				}
			}
			return;
		}

		// Crafting / Fletching: "You make a leather body" / "You make a shortbow" (taskType: Crafting, Fletching)
		Matcher makeMatcher = CHAT_YOU_MAKE.matcher(msg);
		if (makeMatcher.matches())
		{
			String what = normalize(makeMatcher.group(1));
			for (TaskTile tile : revealed)
			{
				String type = tile.getTaskType();
				if (!"Crafting".equals(type) && !"Fletching".equals(type)) continue;
				if (!displayNameContains(tile.getDisplayName(), what)) continue;
				completeAndNotify(area.getId(), tile);
				return;
			}
		}
	}

	@Subscribe
	public void onActorDeath(ActorDeath event)
	{
		if (event.getActor() == null || !(event.getActor() instanceof NPC)) return;
		NPC npc = (NPC) event.getActor();
		Player local = client.getLocalPlayer();
		if (local == null) return;
		// Only count if we're in combat with it (simplification: any NPC death while we're in area counts)
		Area area = currentUnlockedArea();
		if (area == null) return;
		List<TaskTile> revealed = taskGridService.getRevealedTiles(area.getId());
		if (revealed.isEmpty()) return;

		String npcName = npc.getName();
		if (npcName == null) npcName = "";

		for (TaskTile tile : revealed)
		{
			if (!"Combat".equals(tile.getTaskType())) continue;
			String name = tile.getDisplayName();
			if (name == null) continue;
			// "Defeat a Goblin", "Defeat a Moss Giant", "1 Sarachnis Kill" -> extract target name
			String target = extractCombatTarget(name);
			if (target != null && npcName.toLowerCase().contains(target.toLowerCase()))
			{
				completeAndNotify(area.getId(), tile);
				return;
			}
		}
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (event.getContainerId() != InventoryID.EQUIPMENT.getId())
			return;
		Area area = currentUnlockedArea();
		if (area == null) return;
		List<TaskTile> revealed = taskGridService.getRevealedTiles(area.getId());
		if (revealed.isEmpty()) return;
		// Build set of currently equipped item names (from item composition)
		Set<String> equippedNames = new java.util.HashSet<>();
		if (event.getItemContainer() != null && itemManager != null)
		{
			for (net.runelite.api.Item item : event.getItemContainer().getItems())
			{
				if (item.getId() <= 0) continue;
				net.runelite.api.ItemComposition comp = itemManager.getItemComposition(item.getId());
				if (comp != null && comp.getName() != null && !comp.getName().isEmpty())
					equippedNames.add(comp.getName().toLowerCase());
			}
		}
		for (TaskTile tile : revealed)
		{
			if (!"Equipment".equals(tile.getTaskType())) continue;
			String taskItem = extractEquipItemName(tile.getDisplayName());
			if (taskItem == null) continue;
			if (equippedNames.contains(taskItem.toLowerCase()))
			{
				completeAndNotify(area.getId(), tile);
				return;
			}
		}
	}

	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		// Combat level: any combat skill change may push us over a "Reach Combat Level X" threshold
		Skill skill = event.getSkill();
		if (skill != Skill.ATTACK && skill != Skill.STRENGTH && skill != Skill.DEFENCE
			&& skill != Skill.RANGED && skill != Skill.MAGIC && skill != Skill.HITPOINTS
			&& skill != Skill.PRAYER) return;

		Area area = currentUnlockedArea();
		if (area == null) return;
		List<TaskTile> revealed = taskGridService.getRevealedTiles(area.getId());
		if (revealed.isEmpty()) return;

		int combatLevel = client.getLocalPlayer() != null ? client.getLocalPlayer().getCombatLevel() : 0;
		for (TaskTile tile : revealed)
		{
			if (!"Combat".equals(tile.getTaskType())) continue;
			String name = tile.getDisplayName();
			if (name == null) continue;
			Integer required = parseCombatLevelRequirement(name);
			if (required != null && combatLevel >= required)
			{
				completeAndNotify(area.getId(), tile);
				return;
			}
		}
	}

	private Area currentUnlockedArea()
	{
		Player player = client.getLocalPlayer();
		if (player == null) return null;
		WorldPoint wp = player.getWorldLocation();
		if (wp == null) return null;
		Area area = areaGraphService.getAreaAt(wp);
		if (area == null) return null;
		Set<String> unlocked = areaGraphService.getUnlockedAreaIds();
		if (!unlocked.contains(area.getId())) return null;
		return area;
	}

	private static String normalize(String s)
	{
		if (s == null) return "";
		s = s.trim().toLowerCase();
		if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
		return s;
	}

	private static boolean displayNameContains(String displayName, String itemPhrase)
	{
		if (displayName == null || itemPhrase == null) return false;
		return displayName.toLowerCase().contains(itemPhrase) || itemPhrase.contains(displayName.toLowerCase());
	}

	/** Extract required quantity from "Mine 5 Tin Ore" -> 5; default 1 if no number. */
	private static int requiredQuantity(String displayName)
	{
		if (displayName == null) return 1;
		Matcher m = NUMBER_PREFIX.matcher(displayName.trim());
		return m.matches() ? Integer.parseInt(m.group(1)) : 1;
	}

	/** Extract combat target: "Defeat a Goblin" -> "goblin", "1 Sarachnis Kill" -> "sarachnis", "Defeat a Moss Giant" -> "moss giant". */
	private static String extractCombatTarget(String displayName)
	{
		if (displayName == null) return null;
		String d = displayName.trim();
		// "Defeat a X" or "Defeat the X"
		if (d.toLowerCase().startsWith("defeat "))
		{
			String rest = d.substring(7).trim();
			if (rest.toLowerCase().startsWith("a ")) return rest.substring(2).trim();
			if (rest.toLowerCase().startsWith("the ")) return rest.substring(4).trim();
			return rest;
		}
		// "1 Sarachnis Kill" -> number + name + " Kill"
		Matcher m = Pattern.compile("^\\d+\\s+(.+?)\\s+kill$", Pattern.CASE_INSENSITIVE).matcher(d);
		if (m.matches()) return m.group(1).trim();
		// "Kill a Chicken with your fists"
		if (d.toLowerCase().startsWith("kill "))
		{
			String rest = d.substring(5).trim();
			if (rest.toLowerCase().startsWith("a ")) return rest.replaceAll("\\s+with.*$", "").trim();
			return rest;
		}
		return null;
	}

	private static Integer parseCombatLevelRequirement(String displayName)
	{
		if (displayName == null) return null;
		// "Reach Combat Level 10"
		Matcher m = Pattern.compile("(?i)combat level\\s+(\\d+)").matcher(displayName);
		return m.find() ? Integer.parseInt(m.group(1)) : null;
	}

	/** Extract item name from "Equip a Spiny Helmet" or "Equip an Attack potion". */
	private static String extractEquipItemName(String displayName)
	{
		if (displayName == null) return null;
		String d = displayName.trim();
		Matcher an = EQUIP_AN.matcher(d);
		if (an.matches()) return an.group(1).trim();
		Matcher a = EQUIP_A.matcher(d);
		if (a.matches()) return a.group(1).trim();
		return null;
	}

	/** Increment progress for (areaId, taskId); return true if progress >= required (and task should be completed). Clears progress on completion. */
	private boolean incrementAndCheck(String areaId, TaskTile tile, int delta)
	{
		int required = requiredQuantity(tile.getDisplayName());
		String key = areaId + "|" + tile.getId();
		int current = progress.merge(key, delta, Integer::sum);
		if (current >= required)
		{
			progress.remove(key);
			return true;
		}
		return false;
	}

	/**
	 * Marks the task as completed in TaskGridService, shows the completion popup overlay, and
	 * plays the task-complete sound. Call from game thread after a condition is met (e.g. chat message).
	 */
	private void completeAndNotify(String areaId, TaskTile tile)
	{
		taskGridService.setCompleted(areaId, tile.getId());
		popup.showCompleted(areaId, tile, tile.getPoints());
		LeagueScapeSounds.play(audioPlayer, LeagueScapeSounds.TASK_COMPLETE);
		log.debug("Task completed: {} in area {}", tile.getDisplayName(), areaId);
	}
}

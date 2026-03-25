package com.leaguescape.worldunlock;

import com.leaguescape.LeagueScapeConfig;
import com.leaguescape.constants.TaskTypes;
import com.leaguescape.constants.WorldUnlockTileType;
import com.leaguescape.grid.GridPos;
import com.leaguescape.grid.Spiral;
import com.leaguescape.grid.RevealLogic;
import com.leaguescape.points.PointsService;
import com.leaguescape.task.TaskDefinition;
import com.leaguescape.task.TaskGridService;
import com.leaguescape.task.TaskState;
import com.leaguescape.task.TaskTile;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.config.ConfigManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds the global task list from all unlocked world-unlock tiles plus tasks with no area,
 * persists completed/claimed state in a "global" namespace, and awards points on claim.
 * Also builds the spiral grid layout (matching the Area Task Panel's tier distribution)
 * and provides per-tile state for the Global Task Panel.
 */
@Singleton
public class GlobalTaskListService
{
	private static final Logger log = LoggerFactory.getLogger(GlobalTaskListService.class);
	private static final String STATE_GROUP = com.leaguescape.util.LeagueScapeConfigConstants.STATE_GROUP;
	private static final String KEY_GLOBAL_CLAIMED = "globalTaskProgress_claimed";
	private static final String KEY_GLOBAL_COMPLETED = "globalTaskProgress_completed";
	private static final String KEY_GLOBAL_CENTER_CLAIMED = "globalTaskProgress_centerClaimed";
	private static final String KEY_GLOBAL_TASK_POSITIONS = "globalTaskProgress_positions";
	private static final String KEY_GLOBAL_PSEUDO_CENTER = "globalTaskProgress_pseudoCenter";
	private static final String KEY_GLOBAL_LAST_VIEWED = "globalTaskProgress_lastViewed";
	/** Stores which grid positions have been claimed (for reveal); format "row,col||row,col". */
	private static final String KEY_GLOBAL_CLAIMED_POSITIONS = "globalTaskProgress_claimedPositions";
	/** Ring numbers (Chebyshev) that already awarded a global grid ring-completion bonus. */
	private static final String KEY_GLOBAL_RING_BONUS = "globalTaskProgress_ringBonus";
	/** Persisted seed so {@link #buildGlobalGrid(int)} matches the Global Task panel layout. */
	private static final String KEY_GLOBAL_LAYOUT_SEED = "globalTaskProgress_layoutSeed";
	/** Max bonus points for completing one full ring on the global task grid. */
	private static final int RING_BONUS_CAP = 250;
	private static final String ID_SEP = ",";
	private static final String POS_ENTRY_SEP = "||";
	/** Separator for list of claimed positions (must not be comma, since position is "row,col"). */
	private static final String CLAIMED_POS_SEP = ";;";
	private static final String POS_KV_SEP = "::";
	/** Separator for grid state entries: pos + GRID_STATE_SEP + taskKey (single source of truth: position -> task). */
	private static final String GRID_STATE_SEP = "##";
	/** Legacy separator (taskKey|||pos); still parsed on load for backward compatibility. */
	private static final String COMPOSITE_SEP = "|||";
	private static final int MAX_TIER = 5;
	/** Minimum rings (matches Area Task grid); ensures tier 4/5 visibility. */
	private static final int MIN_RINGS = 9;
	/** Maximum rings for "infinite" expansion. */
	private static final int MAX_RINGS = 100;
	/** Matches "[level] [skill]" requirement (e.g. "50 Agility", "70 Strength"). Group 1 = level, group 2 = skill name. */
	private static final Pattern LEVEL_SKILL_REQUIREMENT = Pattern.compile("^(\\d+)\\s+(.+)$");
	/** Matches bracket-only requirement (e.g. "41-50", "1-10", "31 - 40"). Used with taskType as skill name. Group 1 = min, group 2 = max. */
	private static final Pattern SKILL_BRACKET_ONLY = Pattern.compile("^(\\d+)\\s*-\\s*(\\d+)$");

	private final ConfigManager configManager;
	private final LeagueScapeConfig config;
	private final PointsService pointsService;
	private final WorldUnlockService worldUnlockService;
	private final TaskGridService taskGridService;

	@Inject
	public GlobalTaskListService(ConfigManager configManager, LeagueScapeConfig config,
		PointsService pointsService, WorldUnlockService worldUnlockService,
		TaskGridService taskGridService)
	{
		this.configManager = configManager;
		this.config = config;
		this.pointsService = pointsService;
		this.worldUnlockService = worldUnlockService;
		this.taskGridService = taskGridService;
	}

	/**
	 * Stable layout seed for the global task grid (persisted). Call from the Global Task panel so
	 * {@link #buildGlobalGrid(int)} and ring bonuses use the same shuffle as the UI.
	 */
	public int getOrCreateLayoutSeed()
	{
		String raw = configManager.getConfiguration(STATE_GROUP, KEY_GLOBAL_LAYOUT_SEED);
		if (raw != null && !raw.trim().isEmpty())
		{
			try
			{
				return Integer.parseInt(raw.trim());
			}
			catch (NumberFormatException ignored)
			{
			}
		}
		int seed = (int) System.nanoTime();
		configManager.setConfiguration(STATE_GROUP, KEY_GLOBAL_LAYOUT_SEED, String.valueOf(seed));
		return seed;
	}

	/** Normalized key for a task (same as TaskGridService: displayName lowercase). */
	public static String taskKey(TaskDefinition t)
	{
		String name = t.getDisplayName();
		return name != null ? name.trim().toLowerCase() : "";
	}

	/** Normalized key from a raw display name string. */
	public static String taskKeyFromName(String displayName)
	{
		return displayName != null ? displayName.trim().toLowerCase() : "";
	}

	/** True if the task is area-specific and all (or any, if any) of its required areas are in the unlocked set. */
	private static boolean isUnlockedAreaTask(TaskDefinition t, Set<String> unlockedAreaIds)
	{
		List<String> req = t.getRequiredAreaIds();
		if (req == null || req.isEmpty()) return false;
		if (t.isAreaRequirementAny())
			return req.stream().anyMatch(unlockedAreaIds::contains);
		return req.stream().allMatch(unlockedAreaIds::contains);
	}

	/**
	 * True if the task has at least one skill-bracket token in requirements and every such bracket's skill tile is unlocked
	 * (for prioritization over no-area tasks).
	 */
	private boolean isUnlockedSkillBracketTask(TaskDefinition t)
	{
		String taskType = t.getTaskType();
		String req = t.getRequirements();
		if (taskType == null || taskType.trim().isEmpty() || req == null || req.trim().isEmpty())
			return false;
		Set<String> unlockedIds = worldUnlockService.getUnlockedIds();
		boolean foundBracket = false;
		for (String part : req.split(","))
		{
			String token = part.trim();
			if (token.isEmpty()) continue;
			Matcher bracket = SKILL_BRACKET_ONLY.matcher(token);
			if (bracket.matches())
			{
				foundBracket = true;
				int minLevel = Integer.parseInt(bracket.group(1));
				String skillTileId = worldUnlockService.getSkillTileIdForLevel(taskType.trim(), minLevel);
				if (skillTileId == null || !unlockedIds.contains(skillTileId))
					return false;
			}
		}
		return foundBracket;
	}

	/** True if the task has a non-empty required-area list. */
	private static boolean isAreaTask(TaskDefinition t)
	{
		List<String> req = t.getRequiredAreaIds();
		return req != null && !req.isEmpty();
	}

	/**
	 * Collection Log with no {@code bossId}: never gate on boss tiles parsed from requirement text (area-only and global).
	 */
	private static boolean isCollectionLogWithoutBossId(TaskDefinition task)
	{
		return task != null && TaskTypes.isCollectionLogType(task.getTaskType())
			&& (task.getBossId() == null || task.getBossId().trim().isEmpty());
	}

	/**
	 * Collection Log with no boss id and no real area (empty or only "undefined"): skip generic quest-unlock requirement
	 * gating so filler-style tasks behave like other global tasks without those prerequisites.
	 */
	private static boolean isCollectionLogWithoutBossOrArea(TaskDefinition task)
	{
		if (!isCollectionLogWithoutBossId(task))
			return false;
		List<String> req = task.getRequiredAreaIds();
		if (req != null && !req.isEmpty())
		{
			boolean onlyPlaceholder = req.stream().allMatch(a ->
				a == null || a.trim().isEmpty() || "undefined".equalsIgnoreCase(a.trim()));
			if (!onlyPlaceholder)
				return false;
		}
		return true;
	}

	/**
	 * Global task grid ordering among Collection Log tasks: boss-gated ({@code bossId}) first, then area-bound entries,
	 * then non-area-specific filler ({@link #isCollectionLogWithoutBossOrArea}). Non-collection tasks return 0.
	 */
	private static int collectionLogGridRank(TaskDefinition t)
	{
		if (t == null || !TaskTypes.isCollectionLogType(t.getTaskType()))
			return 0;
		if (t.getBossId() != null && !t.getBossId().trim().isEmpty())
			return 3;
		if (isCollectionLogWithoutBossOrArea(t))
			return 1;
		return 2;
	}

	/**
	 * Returns the rollable task list for the Global Task panel.
	 * Tasks are filtered by World Unlock panel state:
	 * - Area tasks: require all areas (or any if areaRequirement=any) to be unlocked
	 * - Skill tasks: require the matching skill tile to be unlocked (e.g. Woodcutting 1-10)
	 * - Quest tasks: require a quest unlock that satisfies the task's requirements
	 * - Diary tasks: require at least one achievement diary unlock
	 * - Boss tasks: require at least one boss unlock
	 * - Collection Log: quest gate waived only for no-area entries without bossId; boss tile from requirements only when bossId is set
	 * - No-area, no-type tasks: always allowed
	 */
	public List<TaskDefinition> getGlobalTasks()
	{
		UnlockedContent unlocked = buildUnlockedContent();
		LinkedHashMap<String, TaskDefinition> byKey = new LinkedHashMap<>();
		Set<String> globalClaimedTaskKeys = loadSet(KEY_GLOBAL_CLAIMED);

		// 1. Add tasks from unlocked taskDisplayNames tiles (explicit task lists)
		// In World Unlock mode, Quest and Achievement Diary tasks do not populate.
		for (String unlockId : worldUnlockService.getUnlockedIds())
		{
			for (TaskDefinition t : worldUnlockService.getTasksForUnlock(unlockId))
			{
				if (isQuestOrDiaryTask(t)) continue;
				WorldUnlockTile tile = worldUnlockService.getTileById(unlockId);
				if (tile == null || tile.getTaskLink() == null) continue;
				String linkType = tile.getTaskLink().getType();
				if (!"taskDisplayNames".equals(linkType)) continue;
				String key = taskKey(t);
				if (!key.isEmpty() && !byKey.containsKey(key))
					byKey.put(key, t);
			}
		}

		// 2. Filter all tasks by unlock state (area, skill, quest, diary, Collection Log, killCount chain)
		// In World Unlock mode, Quest tasks do not populate. Achievement Diary tasks populate only when the diary tier for that area is unlocked.
		List<TaskDefinition> allTasks = taskGridService.getEffectiveDefaultTasks();
		// 2a. Add non-killCount tasks first (including Achievement Diary when area + diary tier unlocked)
		for (TaskDefinition t : allTasks)
		{
			if (isKillCountTask(t) || com.leaguescape.constants.TaskTypes.QUEST.equalsIgnoreCase(t != null ? t.getTaskType() : null)) continue;
			String key = taskKey(t);
			if (key.isEmpty() || byKey.containsKey(key)) continue;
			if (canTaskAppearWithUnlocks(t, unlocked, byKey, globalClaimedTaskKeys))
				byKey.put(key, t);
		}
		// 2b. Add killCount tasks in order: difficulty 1, then 2–5 (chain: previous step must be claimed on global grid)
		for (int difficulty = 1; difficulty <= 5; difficulty++)
		{
			for (TaskDefinition t : allTasks)
			{
				if (!isKillCountTask(t) || t.getDifficulty() != difficulty) continue;
				String key = taskKey(t);
				if (key.isEmpty() || byKey.containsKey(key)) continue;
				if (canTaskAppearWithUnlocks(t, unlocked, byKey, globalClaimedTaskKeys))
					byKey.put(key, t);
			}
		}

		log.debug("[GlobalTask] getGlobalTasks: unlocked={}, returning {} tasks", unlocked.summary(), byKey.size());
		return new ArrayList<>(byKey.values());
	}

	/**
	 * Returns the list of tasks available for assignment on the Global Task grid.
	 * Based only on: no-area tasks + tasks allowed by current World Unlock state.
	 * Used as the single pool for lazy assignment (strict one-use per task).
	 */
	public List<TaskDefinition> getAvailableTasksForGlobalGrid()
	{
		List<TaskDefinition> tasks = getGlobalTasks();
		if (tasks.isEmpty())
		{
			List<TaskDefinition> noArea = taskGridService.getEffectiveDefaultTasks().stream()
				.filter(t -> {
					List<String> ids = t.getRequiredAreaIds();
					return ids == null || ids.isEmpty()
						|| (ids.stream().anyMatch(a -> "undefined".equalsIgnoreCase(a)));
				})
				.limit(500)
				.collect(Collectors.toList());
			if (!noArea.isEmpty()) tasks = noArea;
			else
			{
				List<TaskDefinition> anyTasks = taskGridService.getEffectiveDefaultTasks().stream()
					.limit(500)
					.collect(Collectors.toList());
				if (!anyTasks.isEmpty()) tasks = anyTasks;
			}
		}
		return tasks;
	}

	/** Returns the four cardinal neighbor position strings "row,col" for the given position. */
	private static List<String> getNeighborPositions(String pos)
	{
		int[] rc = parsePos(pos);
		if (rc == null) return new ArrayList<>();
		int r = rc[0], c = rc[1];
		List<String> out = new ArrayList<>(4);
		out.add((r + 1) + "," + c);
		out.add((r - 1) + "," + c);
		out.add(r + "," + (c + 1));
		out.add(r + "," + (c - 1));
		return out;
	}

	private static boolean isKillCountTask(TaskDefinition t)
	{
		return t != null && "killCount".equalsIgnoreCase(t.getTaskType());
	}

	/** Combat achievements that gate on {@link TaskDefinition#getBossId()} use boss + unlock-tile tokens in §5b/§7, not generic quest-unlock text matching. */
	private static boolean isCombatWithBossId(TaskDefinition task)
	{
		return task != null && "Combat".equalsIgnoreCase(task.getTaskType())
			&& task.getBossId() != null && !task.getBossId().trim().isEmpty();
	}

	/**
	 * Collection Log tasks with {@code bossId} whose requirements are empty or only world-unlock tile ids (e.g. {@code zulrah})
	 * should use §1/§5/§7 only, not §3 quest text — same idea as legacy {@code "Defeat …"} prerequisites and {@link #isCombatWithBossId}.
	 * If requirements include non-tile text (e.g. a quest name), §3 still applies.
	 */
	private boolean isCollectionLogBossGatedOnlyByUnlockTiles(TaskDefinition task)
	{
		if (task == null || !TaskTypes.isCollectionLogType(task.getTaskType())
			|| task.getBossId() == null || task.getBossId().trim().isEmpty())
			return false;
		String req = task.getRequirements();
		boolean hasReq = req != null && !req.trim().isEmpty();
		return !hasReq || requirementsAllTokensResolveToWorldUnlockTiles(task);
	}

	/**
	 * True when every comma-separated requirements token resolves to a world-unlock tile id (boss/area/quest/skill tile).
	 * Used so tasks that only reference unlock tiles can pass §3 when no quest tiles are unlocked yet.
	 */
	private boolean requirementsAllTokensResolveToWorldUnlockTiles(TaskDefinition task)
	{
		String req = task.getRequirements();
		if (req == null || req.trim().isEmpty())
			return false;
		for (String part : req.split(","))
		{
			String token = part.trim();
			if (token.isEmpty()) continue;
			if (worldUnlockService.resolvePrerequisiteToTileId(token) == null)
				return false;
		}
		return true;
	}

	/** True if this task is a Quest or Achievement Diary task; such tasks do not populate in World Unlock mode. */
	private static boolean isQuestOrDiaryTask(TaskDefinition t)
	{
		if (t == null || t.getTaskType() == null) return false;
		String type = t.getTaskType();
		return com.leaguescape.constants.TaskTypes.QUEST.equalsIgnoreCase(type) || com.leaguescape.constants.TaskTypes.isAchievementDiaryType(type);
	}

	/**
	 * True if this task can appear in the Global Task panel given current World Unlock state.
	 * Mirrors the unlock-type gating from World Unlock tiles.
	 * @param alreadyInList task catalog built so far (used to resolve killCount prerequisite display names)
	 * @param globalClaimedTaskKeys keys claimed on the global task grid; killCount chains require the previous step to be claimed
	 */
	private boolean canTaskAppearWithUnlocks(TaskDefinition task, UnlockedContent u, Map<String, TaskDefinition> alreadyInList,
		Set<String> globalClaimedTaskKeys)
	{
		// 1. Area: required areas must be unlocked
		List<String> requiredAreas = task.getRequiredAreaIds();
		if (requiredAreas != null && !requiredAreas.isEmpty())
		{
			boolean hasUndefined = requiredAreas.stream().anyMatch(a -> "undefined".equalsIgnoreCase(a));
			if (hasUndefined) return true;  // undefined area = no gate
			if (task.isAreaRequirementAny())
			{
				if (!requiredAreas.stream().anyMatch(a -> u.areas.contains(a)))
					return false;
			}
			else
			{
				for (String areaId : requiredAreas)
				{
					if (!u.areas.contains(areaId))
						return false;
				}
			}
		}

		// 2. Skill: if taskType matches a skill unlock tile (case-insensitive), that skill must be unlocked
		String taskType = task.getTaskType();
		boolean hasRequirements = task.getRequirements() != null && !task.getRequirements().trim().isEmpty();
		if (taskType != null && containsSkillNameIgnoreCase(u.allSkillNames, taskType) && !containsSkillNameIgnoreCase(u.skills, taskType))
			return false;

		// 2b. Skill bracket: every "min-max" token must match an unlocked skill tile for this skill (comma-separated = AND).
		if (taskType != null && containsSkillNameIgnoreCase(u.allSkillNames, taskType) && hasRequirements)
		{
			String req = task.getRequirements();
			if (req != null && !req.trim().isEmpty())
			{
				Set<String> unlockedIds = worldUnlockService.getUnlockedIds();
				for (String part : req.split(","))
				{
					String token = part.trim();
					if (token.isEmpty()) continue;
					Matcher bracket = SKILL_BRACKET_ONLY.matcher(token);
					if (bracket.matches())
					{
						int minLevel = Integer.parseInt(bracket.group(1));
						String skillTileId = worldUnlockService.getSkillTileIdForLevel(taskType.trim(), minLevel);
						if (skillTileId != null && !unlockedIds.contains(skillTileId))
							return false;
					}
				}
			}
		}

		// 3. Quest: if task has quest requirements or is Quest type, need quest unlock
		// (except for "Defeat ..." style prerequisite-task requirements)
		boolean isTaskPrereqReq = isTaskPrerequisiteRequirement(task.getRequirements());
		boolean isSkillTask = taskType != null && containsSkillNameIgnoreCase(u.allSkillNames, taskType);
		boolean hasSkillBracketReq = isSkillTask && hasRequirements && task.getRequirements().matches(".*\\d+\\s*-\\s*\\d+.*");
		boolean questTypeOrReqNeedsQuestUnlock = com.leaguescape.constants.TaskTypes.QUEST.equalsIgnoreCase(taskType)
			|| (hasRequirements && !isKillCountTask(task) && !isTaskPrereqReq && !hasSkillBracketReq && !isCollectionLogWithoutBossOrArea(task));
		// Combat+bossId and Collection Log+bossId (tile-id-only requirements): gated by boss unlock + §7, not §3 quest text.
		boolean useQuestUnlockSection = questTypeOrReqNeedsQuestUnlock && !isCombatWithBossId(task)
			&& !isCollectionLogBossGatedOnlyByUnlockTiles(task);
		if (useQuestUnlockSection)
		{
			boolean hasReqText = task.getRequirements() != null && !task.getRequirements().trim().isEmpty();
			if (u.questRequirements.isEmpty())
			{
				// Allow when every requirement token is a resolvable unlock tile (checked in §7); otherwise need at least one quest tile unlocked.
				if (!hasReqText || !requirementsAllTokensResolveToWorldUnlockTiles(task))
					return false;
			}
			else if (hasReqText)
			{
				for (String part : task.getRequirements().split(","))
				{
					String raw = part.trim();
					if (raw.isEmpty()) continue;
					// Unlock-tile names/ids: satisfied by §6–7, not by quest-unlock text pool
					if (worldUnlockService.resolvePrerequisiteToTileId(raw) != null)
						continue;
					String q = raw.toLowerCase();
					boolean satisfied = u.questRequirements.stream()
						.anyMatch(unlocked -> unlocked.contains(q) || q.contains(unlocked));
					if (!satisfied)
						return false;
				}
			}
		}

		// 4. Achievement Diary: the appropriate area(s) for that diary must be unlocked AND the diary tier must be unlocked.
		// Uses area_mapping.json: diary key -> area ids. At least one of those areas must be unlocked, and the tier key (e.g. varrock_1) must be unlocked.
		if (com.leaguescape.constants.TaskTypes.isAchievementDiaryType(taskType))
		{
			List<String> diaryAreas = task.getRequiredAreaIds();
			if (diaryAreas == null || diaryAreas.isEmpty())
				return false;
			String areaId = diaryAreas.get(0);
			String diaryKey = worldUnlockService.getDiaryKeyForAreaId(areaId);
			if (diaryKey == null)
				return false;
			int difficulty = Math.max(1, Math.min(4, task.getDifficulty()));
			String tierKey = diaryKey + "_" + difficulty;
			if (!u.unlockedDiaryTierKeys.contains(tierKey))
				return false;
			// Require at least one area that belongs to this diary to be unlocked (area id -> diary key from area_mapping + tiles).
			boolean anyAreaUnlocked = u.areas.stream()
				.anyMatch(unlockedAreaId -> diaryKey.equals(worldUnlockService.getDiaryKeyForAreaId(unlockedAreaId)));
			if (!anyAreaUnlocked)
				return false;
		}

		// 5. Collection Log: require that boss tile only when bossId is set (section 1 already gated real areas)
		if (taskType != null && TaskTypes.isCollectionLogType(taskType))
		{
			if (task.getBossId() != null && !task.getBossId().trim().isEmpty())
			{
				String bossTileId = worldUnlockService.resolvePrerequisiteToTileId(task.getBossId().trim());
				if (bossTileId != null && !worldUnlockService.getUnlockedIds().contains(bossTileId))
					return false;
			}
		}

		// 5b. Combat (achievement) tasks whose prerequisite is a boss require that boss to be unlocked
		if ("Combat".equalsIgnoreCase(taskType) && task.getBossId() != null && !task.getBossId().trim().isEmpty())
		{
			String bossTileId = worldUnlockService.resolvePrerequisiteToTileId(task.getBossId().trim());
			if (bossTileId != null && !worldUnlockService.getUnlockedIds().contains(bossTileId))
				return false;
		}

		// 6. killCount: comma reqs = AND of unlocked tiles (boss + area, etc.); single req = prior chain step claimed OR unlock tile (quest/boss/area)
		if ("killCount".equalsIgnoreCase(taskType))
		{
			String req = task.getRequirements() != null ? task.getRequirements().trim() : "";
			if (req.isEmpty()) return false;
			if (req.contains(","))
			{
				// AND: all resolved tiles must be unlocked (boss + area for first killCount task)
				Set<String> unlockedIds = worldUnlockService.getUnlockedIds();
				for (String part : req.split(","))
				{
					String token = part.trim();
					if (token.isEmpty()) continue;
					String tileId = worldUnlockService.resolvePrerequisiteToTileId(token);
					if (tileId != null && !unlockedIds.contains(tileId))
						return false;
				}
			}
			else
			{
				String prevKey = taskKeyFromName(req);
				boolean prevInCatalog = alreadyInList != null && !prevKey.isEmpty() && alreadyInList.containsKey(prevKey);
				TaskDefinition prevTask = prevInCatalog ? alreadyInList.get(prevKey) : null;
				if (prevInCatalog && prevTask != null && isKillCountTask(prevTask))
				{
					// e.g. "Defeat Brutus 10 times" requires "Defeat Brutus 5 times" to be claimed before it appears in the pool
					if (globalClaimedTaskKeys == null || !globalClaimedTaskKeys.contains(prevKey))
						return false;
				}
				else if (!prevInCatalog)
				{
					String tileId = worldUnlockService.resolvePrerequisiteToTileId(req);
					if (tileId != null)
					{
						if (!worldUnlockService.getUnlockedIds().contains(tileId))
							return false;
					}
					else if (!prevKey.isEmpty())
						return false;
				}
			}
		}
		else if (hasRequirements)
		{
			// 7a. "Defeat ..." prerequisite-task requirement: prerequisite task must already be present (revealed/available) in the global task list
			if (isTaskPrereqReq)
			{
				String req = task.getRequirements().trim();
				// Single prerequisite only (matches boss chain style)
				if (!req.contains(","))
				{
					String prevKey = taskKeyFromName(req);
					if (!prevKey.isEmpty() && (alreadyInList == null || !alreadyInList.containsKey(prevKey)))
						return false;
				}
			}

			// 7. Requirements: each comma-separated token is checked; "[level] [skill]" and bracket tokens gate on unlock;
			// resolved world-unlock tile ids (quest, area, boss, diary, …) each require that tile unlocked (AND across tokens).
			Set<String> unlockedIds = worldUnlockService.getUnlockedIds();
			for (String part : task.getRequirements().split(","))
			{
				String token = part.trim();
				if (token.isEmpty()) continue;
				Matcher levelSkill = LEVEL_SKILL_REQUIREMENT.matcher(token);
				if (levelSkill.matches())
				{
					int level = Integer.parseInt(levelSkill.group(1));
					String skillName = levelSkill.group(2).trim();
					String skillTileId = worldUnlockService.getSkillTileIdForLevel(skillName, level);
					if (skillTileId != null && !unlockedIds.contains(skillTileId))
						return false;
					continue;
				}
				// Bracket on its own (e.g. "41-50"): use taskType as skill name; same behaviour as "Skill [bracket]"
				Matcher bracketOnly = SKILL_BRACKET_ONLY.matcher(token);
				if (bracketOnly.matches() && taskType != null && containsSkillNameIgnoreCase(u.allSkillNames, taskType))
				{
					int minLevel = Integer.parseInt(bracketOnly.group(1));
					String skillTileId = worldUnlockService.getSkillTileIdForLevel(taskType, minLevel);
					if (skillTileId != null && !unlockedIds.contains(skillTileId))
						return false;
					continue;
				}
				String tileId = worldUnlockService.resolvePrerequisiteToTileId(token);
				if (tileId == null)
					continue;
				WorldUnlockTile tile = worldUnlockService.getTileById(tileId);
				if (tile != null && WorldUnlockTileType.SKILL.equals(tile.getType()))
				{
					if (!unlockedIds.contains(tileId))
						return false;
					continue;
				}
				if (tile != null && WorldUnlockTileType.BOSS.equals(tile.getType()) && "Combat".equalsIgnoreCase(taskType))
				{
					if (!unlockedIds.contains(tileId))
						return false;
					continue;
				}
				if (tile != null && WorldUnlockTileType.BOSS.equals(tile.getType()) && isCollectionLogWithoutBossId(task))
					continue;
				if (!unlockedIds.contains(tileId))
					return false;
			}
		}

		return true;
	}

	/** True if the set contains a skill name that equals (ignore case) the given name. */
	private static boolean containsSkillNameIgnoreCase(Set<String> skillNames, String name)
	{
		if (name == null || skillNames == null) return false;
		String n = name.trim();
		if (n.isEmpty()) return false;
		return skillNames.stream().anyMatch(s -> s != null && s.trim().equalsIgnoreCase(n));
	}

	/** True if the requirements string looks like a prerequisite-task reference (e.g. "Defeat Barrows"). */
	private static boolean isTaskPrerequisiteRequirement(String requirements)
	{
		if (requirements == null) return false;
		for (String part : requirements.split(","))
		{
			String t = part != null ? part.trim().toLowerCase() : "";
			if (t.startsWith("defeat "))
				return true;
		}
		return false;
	}

	/** Unlocked content derived from World Unlock panel tiles. */
	private static final class UnlockedContent
	{
		final Set<String> areas;
		final Set<String> skills;
		final Set<String> questRequirements;
		final Set<String> diaryRequirements;
		final Set<String> bossRequirements;
		final Set<String> allSkillNames;
		/** Unlocked or revealed tile ids; used to gate tasks with [skill] [bracket] requirements. */
		final Set<String> unlockedOrRevealedTileIds;
		/** Unlocked achievement diary tier keys: "diaryKey_difficulty" (e.g. varrock_1, desert_2). Easy=1, medium=2, hard=3, elite=4. */
		final Set<String> unlockedDiaryTierKeys;

		UnlockedContent(Set<String> areas, Set<String> skills, Set<String> questRequirements,
			Set<String> diaryRequirements, Set<String> bossRequirements, Set<String> allSkillNames,
			Set<String> unlockedOrRevealedTileIds, Set<String> unlockedDiaryTierKeys)
		{
			this.areas = areas;
			this.skills = skills;
			this.questRequirements = questRequirements;
			this.diaryRequirements = diaryRequirements;
			this.bossRequirements = bossRequirements;
			this.allSkillNames = allSkillNames;
			this.unlockedOrRevealedTileIds = unlockedOrRevealedTileIds;
			this.unlockedDiaryTierKeys = unlockedDiaryTierKeys != null ? unlockedDiaryTierKeys : new HashSet<>();
		}

		String summary()
		{
			return "areas=" + areas.size() + ",skills=" + skills.size()
				+ ",quests=" + questRequirements.size() + ",diaries=" + diaryRequirements.size()
				+ ",bosses=" + bossRequirements.size() + ",diaryTiers=" + unlockedDiaryTierKeys.size();
		}
	}

	private UnlockedContent buildUnlockedContent()
	{
		Set<String> areas = new HashSet<>();
		Set<String> skills = new HashSet<>();
		Set<String> questReqs = new HashSet<>();
		Set<String> diaryReqs = new HashSet<>();
		Set<String> bossReqs = new HashSet<>();
		Set<String> allSkillNames = new HashSet<>();
		Set<String> unlockedOrRevealedTileIds = worldUnlockService.getUnlockedOrRevealedTileIds();

		for (WorldUnlockTile tile : worldUnlockService.getTiles())
		{
			TaskLink link = tile.getTaskLink();
			String type = tile.getType();
			boolean unlocked = worldUnlockService.getUnlockedIds().contains(tile.getId());

			if ("area".equals(type))
			{
				if (unlocked)
					areas.add(tile.getId());
			}
			else if ("skill".equals(type) && link != null && link.getSkillName() != null)
			{
				allSkillNames.add(link.getSkillName());
				if (unlocked)
					skills.add(link.getSkillName());
			}
			else if ("quest".equals(type) && link != null && link.getRequirementsContains() != null)
			{
				if (unlocked)
					questReqs.add(link.getRequirementsContains().trim().toLowerCase());
			}
			else if ("achievement_diary".equals(type) && link != null && link.getRequirementsContains() != null)
			{
				if (unlocked)
					diaryReqs.add(link.getRequirementsContains().trim().toLowerCase());
			}
			else if ("boss".equals(type) && link != null && link.getRequirementsContains() != null)
			{
				if (unlocked)
					bossReqs.add(link.getRequirementsContains().trim().toLowerCase());
			}
		}

		Set<String> unlockedDiaryTierKeys = worldUnlockService.getUnlockedDiaryTierKeys();
		return new UnlockedContent(areas, skills, questReqs, diaryReqs, bossReqs, allSkillNames, unlockedOrRevealedTileIds, unlockedDiaryTierKeys);
	}

	/**
	 * Builds the grid using lazy assignment: only assign a task to a cell when it is first revealed
	 * (adjacent to a claimed cell). Center (0,0) is the anchor. Each task is used at most once (strict one-use).
	 * Available tasks = no-area + unlocked World Unlock state only.
	 */
	public List<TaskTile> buildGlobalGrid(int reshuffleSeed)
	{
		List<TaskTile> out = new ArrayList<>();
		out.add(new TaskTile(TaskTile.idFor(0, 0), 0, "Free", 0, 0, 0, null, null, true, null, null));
		try
		{
			worldUnlockService.load();
		}
		catch (Exception e)
		{
			log.warn("[GlobalTask] load failed, returning center only", e);
			return out;
		}

		// 1. Available task pool: only no-area + unlocked World Unlock state
		List<TaskDefinition> availableTasks = getAvailableTasksForGlobalGrid();
		Map<String, TaskDefinition> taskByKey = new LinkedHashMap<>();
		Set<String> seen = new HashSet<>();
		for (TaskDefinition t : availableTasks)
		{
			String key = taskKey(t);
			if (!key.isEmpty() && seen.add(key))
				taskByKey.put(key, t);
		}
		log.debug("[GlobalTask] buildGlobalGrid: available pool size {}", taskByKey.size());

		if (taskByKey.isEmpty())
		{
			log.warn("[GlobalTask] No tasks available; returning center only");
			return out;
		}

		try
		{
		// 2. Single grid state: position -> task key. Only add when a position is first revealed; never overwrite.
		Map<String, String> gridState = new HashMap<>(loadGridState());
		Set<String> claimedPositions = getClaimedPositions();
		Map<String, TaskDefinition> allTasksByKeyFallback = new HashMap<>();
		for (TaskDefinition t : taskGridService.getEffectiveDefaultTasks())
		{
			String k = taskKey(t);
			if (!k.isEmpty() && !allTasksByKeyFallback.containsKey(k))
				allTasksByKeyFallback.put(k, t);
		}

		// 3. Revealed = center + claimed + neighbors of claimed
		Set<String> revealedPositions = new HashSet<>();
		revealedPositions.add("0,0");
		for (String claimed : claimedPositions)
		{
			revealedPositions.add(normalizePos(claimed));
			revealedPositions.addAll(getNeighborPositions(claimed));
		}

		// 4. For each revealed position: if in gridState use it (resolve to def or placeholder). Else add to toAssign (first time revealed).
		Map<String, TaskDefinition> atPosition = new HashMap<>();
		List<String> toAssign = new ArrayList<>();
		for (String pos : revealedPositions)
		{
			if ("0,0".equals(pos)) continue;
			String normPos = normalizePos(pos);
			String taskKeyAtPos = gridState.get(normPos);
			if (taskKeyAtPos != null)
			{
				TaskDefinition def = allTasksByKeyFallback.get(taskKeyAtPos);
				if (def == null) def = taskByKey.get(taskKeyAtPos);
				atPosition.put(pos, def != null ? def : placeholderTile());
			}
			else
				toAssign.add(pos);
		}

		// 5. Available for new assignment = pool minus (task keys already in grid state) minus (claimed). One-use: when we add to grid we remove from pool.
		Set<String> usedTaskKeys = new HashSet<>(gridState.values());
		usedTaskKeys.addAll(loadSet(KEY_GLOBAL_CLAIMED));
		List<TaskDefinition> availableForNew = new ArrayList<>();
		for (TaskDefinition t : taskByKey.values())
		{
			if (!usedTaskKeys.contains(taskKey(t)))
				availableForNew.add(t);
		}
		// Unlocked area ids = area tiles that are unlocked in the World Unlock grid
		Set<String> unlockedAreaIds = worldUnlockService.getTiles().stream()
			.filter(t -> "area".equals(t.getType()) && worldUnlockService.getUnlockedIds().contains(t.getId()))
			.map(t -> t.getId())
			.collect(Collectors.toSet());
		// Prioritize: skill-bracket tasks first when their bracket is unlocked/revealed, then area tasks, then global tasks.
		// Among Collection Log tasks: boss-gated first, then area-bound, then non-area-specific filler (rare on the grid).
		availableForNew.sort((a, b) -> {
			boolean aSkill = isUnlockedSkillBracketTask(a);
			boolean bSkill = isUnlockedSkillBracketTask(b);
			if (aSkill != bSkill) return aSkill ? -1 : 1;
			boolean aUnlockedArea = isUnlockedAreaTask(a, unlockedAreaIds);
			boolean bUnlockedArea = isUnlockedAreaTask(b, unlockedAreaIds);
			if (aUnlockedArea != bUnlockedArea) return aUnlockedArea ? -1 : 1;
			boolean aArea = isAreaTask(a);
			boolean bArea = isAreaTask(b);
			if (aArea != bArea) return aArea ? -1 : 1;
			boolean aCl = TaskTypes.isCollectionLogType(a.getTaskType());
			boolean bCl = TaskTypes.isCollectionLogType(b.getTaskType());
			if (aCl && bCl)
			{
				int ra = collectionLogGridRank(a);
				int rb = collectionLogGridRank(b);
				if (ra != rb) return Integer.compare(rb, ra);
			}
			int da = Math.max(1, Math.min(MAX_TIER, a.getDifficulty()));
			int db = Math.max(1, Math.min(MAX_TIER, b.getDifficulty()));
			if (da != db) return Integer.compare(da, db);
			boolean aNa = isCollectionLogWithoutBossOrArea(a);
			boolean bNa = isCollectionLogWithoutBossOrArea(b);
			if (aNa != bNa) return aNa ? 1 : -1;
			return taskKey(a).compareTo(taskKey(b));
		});
		Random rnd = new Random(reshuffleSeed);
		// Shuffle within same (unlockedArea, skillBracket, area, collectionLogRank, difficulty) to randomize order while keeping prioritization
		int idx = 0;
		while (idx < availableForNew.size())
		{
			boolean s0 = isUnlockedSkillBracketTask(availableForNew.get(idx));
			boolean u0 = isUnlockedAreaTask(availableForNew.get(idx), unlockedAreaIds);
			boolean a0 = isAreaTask(availableForNew.get(idx));
			int cl0 = collectionLogGridRank(availableForNew.get(idx));
			int d0 = Math.max(1, Math.min(MAX_TIER, availableForNew.get(idx).getDifficulty()));
			int j = idx + 1;
			while (j < availableForNew.size())
			{
				boolean s = isUnlockedSkillBracketTask(availableForNew.get(j));
				boolean u = isUnlockedAreaTask(availableForNew.get(j), unlockedAreaIds);
				boolean a = isAreaTask(availableForNew.get(j));
				int cl = collectionLogGridRank(availableForNew.get(j));
				int d = Math.max(1, Math.min(MAX_TIER, availableForNew.get(j).getDifficulty()));
				if (s != s0 || u != u0 || a != a0 || cl != cl0 || d != d0) break;
				j++;
			}
			if (j > idx + 1)
				java.util.Collections.shuffle(availableForNew.subList(idx, j), rnd);
			idx = j;
		}

		// 6. Assign only to toAssign (newly revealed, not in grid state). Add to grid state and remove from available (one-use).
		Comparator<int[]> byDistFromCenter = (a, b) -> {
			int da = chebyshevDist(a[0], a[1], 0, 0);
			int db = chebyshevDist(b[0], b[1], 0, 0);
			if (da != db) return Integer.compare(da, db);
			if (a[0] != b[0]) return Integer.compare(a[0], b[0]);
			return Integer.compare(a[1], b[1]);
		};
		List<int[]> toAssignRc = new ArrayList<>();
		for (String pos : toAssign)
		{
			int[] rc = parsePos(pos);
			if (rc != null) toAssignRc.add(rc);
		}
		toAssignRc.sort(byDistFromCenter);

		for (int i = 0; i < toAssignRc.size() && i < availableForNew.size(); i++)
		{
			int[] rc = toAssignRc.get(i);
			String pos = rc[0] + "," + rc[1];
			TaskDefinition def = availableForNew.get(i);
			if (def == null) def = placeholderTile();
			String tk = taskKey(def);
			if (!"unknown".equals(tk))
			{
				gridState.put(pos, tk);
				usedTaskKeys.add(tk);
			}
			atPosition.put(pos, def);
		}

		// 7. Persist single grid state
		saveGridState(gridState);

		// 8. Output: center + all revealed positions with task (from atPosition)
		List<String> positionsToOutput = new ArrayList<>(atPosition.keySet());
		positionsToOutput.sort((a, b) -> {
			int[] ar = parsePos(a), br = parsePos(b);
			if (ar == null || br == null) return 0;
			return byDistFromCenter.compare(ar, br);
		});

		for (String posStr : positionsToOutput)
		{
			if ("0,0".equals(posStr)) continue;
			int[] rc = parsePos(posStr);
			if (rc == null) continue;
			TaskDefinition def = atPosition.get(posStr);
			if (def == null) def = placeholderTile();
			int r = rc[0], c = rc[1];
			String id = TaskTile.idFor(r, c);
			int difficulty = Math.max(1, Math.min(MAX_TIER, def.getDifficulty()));
			int points = pointsForTier(difficulty);
			String displayName = def.getDisplayName() != null ? def.getDisplayName() : id;
			out.add(new TaskTile(id, difficulty, displayName, points, r, c,
				def.getTaskType(),
				def.getRequiredAreaIds().isEmpty() ? null : new ArrayList<>(def.getRequiredAreaIds()),
				!def.isAreaRequirementAny(),
				def.getRequirements(),
				def.getBossId()));
		}

		log.debug("[GlobalTask] buildGlobalGrid output: {} tiles (revealed+assigned)", out.size());
		}
		catch (Exception e)
		{
			log.warn("[GlobalTask] buildGlobalGrid failed, returning center + placeholder", e);
			TaskDefinition ph = placeholderTile();
			out.add(new TaskTile(TaskTile.idFor(1, 0), 1, ph.getDisplayName(), pointsForTier(1), 1, 0,
				ph.getTaskType(), null, true, null, null));
		}
		return out;
	}

	private static boolean isAdjacentToAny(String pos, Set<String> positions)
	{
		String[] p = pos.split(",");
		if (p.length != 2) return false;
		int r, c;
		try
		{
			r = Integer.parseInt(p[0].trim());
			c = Integer.parseInt(p[1].trim());
		}
		catch (NumberFormatException e) { return false; }

		int[][] deltas = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
		for (int[] d : deltas)
		{
			if (positions.contains((r + d[0]) + "," + (c + d[1])))
				return true;
		}
		return false;
	}

	private static int[] parsePos(String pos) { return GridPos.parse(pos); }

	private static int chebyshevDist(int r1, int c1, int r2, int c2) { return GridPos.chebyshevDist(r1, c1, r2, c2); }

	/** Keys must be composite "taskKey|||pos"; reject position-as-key entries (e.g. "1,0") that would show coords as task name. */
	private static boolean isPositionLike(String keyOrPos)
	{
		if (keyOrPos == null) return true;
		return keyOrPos.trim().matches("-?\\d+\\s*,\\s*-?\\d+");
	}

	/** Tier 1 task with no area; used when a stored assignment cannot be resolved or on error. Never reassign. */
	private static TaskDefinition placeholderTile()
	{
		TaskDefinition p = new TaskDefinition();
		p.setDisplayName("Unknown");
		p.setTaskType(TaskTypes.OTHER);
		p.setDifficulty(1);
		return p;
	}

	/** Canonical position string "r,c" (no spaces) so load/store and comparisons match. */
	private static String normalizePos(String pos)
	{
		return GridPos.normalize(pos);
	}

	/**
	 * Loads the single grid state: normalized position -> task key.
	 * Add to this map only when a position is first revealed (adjacent claimed); never overwrite.
	 * Supports new format "pos##taskKey" and legacy "taskKey|||pos::pos".
	 */
	private Map<String, String> loadGridState()
	{
		String raw = configManager.getConfiguration(STATE_GROUP, KEY_GLOBAL_TASK_POSITIONS);
		Map<String, String> gridState = new HashMap<>();
		if (raw == null || raw.isEmpty()) return gridState;
		Pattern entrySplit = Pattern.compile("\\|\\|(?!\\|)");
		for (String entry : entrySplit.split(raw))
		{
			entry = entry.trim();
			if (entry.isEmpty()) continue;
			// New format: pos##taskKey (position is key in map)
			if (entry.contains(GRID_STATE_SEP))
			{
				int i = entry.indexOf(GRID_STATE_SEP);
				String pos = entry.substring(0, i).trim();
				String taskKey = entry.substring(i + GRID_STATE_SEP.length()).trim().toLowerCase();
				if (!pos.isEmpty() && !taskKey.isEmpty() && !isPositionLike(taskKey))
					gridState.put(normalizePos(pos), taskKey);
				continue;
			}
			// Legacy: taskKey|||pos::pos
			int lastSep = entry.lastIndexOf(POS_KV_SEP);
			if (lastSep < 0) continue;
			String key = entry.substring(0, lastSep).trim();
			String posVal = entry.substring(lastSep + POS_KV_SEP.length()).trim();
			if (!key.contains(COMPOSITE_SEP) || isPositionLike(key) || posVal.isEmpty()) continue;
			String tk = key.substring(0, key.indexOf(COMPOSITE_SEP)).trim().toLowerCase();
			if (!tk.isEmpty() && !isPositionLike(tk))
				gridState.put(normalizePos(posVal), tk);
		}
		return gridState;
	}

	/** Saves grid state: one entry per position as "pos##taskKey". */
	private void saveGridState(Map<String, String> gridState)
	{
		List<String> parts = new ArrayList<>();
		for (Map.Entry<String, String> e : gridState.entrySet())
		{
			String pos = e.getKey();
			String taskKey = e.getValue();
			if (pos != null && !pos.isEmpty() && taskKey != null && !taskKey.isEmpty() && !isPositionLike(taskKey))
				parts.add(pos + GRID_STATE_SEP + taskKey);
		}
		configManager.setConfiguration(STATE_GROUP, KEY_GLOBAL_TASK_POSITIONS, String.join(POS_ENTRY_SEP, parts));
	}

	/** Returns all positions in grid state that have the given task key. */
	private static List<String> getPositionsForTaskKey(Map<String, String> gridState, String taskKey)
	{
		List<String> out = new ArrayList<>();
		if (taskKey == null || gridState == null) return out;
		String normKey = taskKey.trim().toLowerCase();
		for (Map.Entry<String, String> e : gridState.entrySet())
		{
			if (normKey.equals(e.getValue()))
				out.add(e.getKey());
		}
		return out;
	}

	/** Returns the pseudo-center position (e.g. "0,0" or last claimed). Defaults to "0,0" if null. */
	private String loadPseudoCenter()
	{
		String raw = configManager.getConfiguration(STATE_GROUP, KEY_GLOBAL_PSEUDO_CENTER);
		return (raw != null && !raw.isEmpty()) ? raw : "0,0";
	}

	private void savePseudoCenter(String pos)
	{
		if (pos != null && !pos.isEmpty())
			configManager.setConfiguration(STATE_GROUP, KEY_GLOBAL_PSEUDO_CENTER, pos);
	}

	/** Saves the last viewed tile position (row,col) for focus-on-open. */
	public void saveLastViewedPosition(int row, int col)
	{
		configManager.setConfiguration(STATE_GROUP, KEY_GLOBAL_LAST_VIEWED, row + "," + col);
	}

	/** Returns the last viewed tile position [row, col] or null if never viewed (use center). */
	public int[] loadLastViewedPosition()
	{
		String raw = configManager.getConfiguration(STATE_GROUP, KEY_GLOBAL_LAST_VIEWED);
		if (raw == null || raw.isEmpty()) return null;
		return parsePos(raw);
	}

	/**
	 * Returns the state of a tile in the global task grid.
	 * Center tile is always revealed; other tiles are revealed when a cardinal neighbor is claimed.
	 */
	public TaskState getGlobalState(String tileId, List<TaskTile> grid)
	{
		// Center (0,0) is always shown: CLAIMED if claimed, else COMPLETED_UNCLAIMED (click to claim)
		boolean isCenter = "0,0".equals(tileId);
		if (isCenter)
		{
			if (isCenterClaimed()) return TaskState.CLAIMED;
			return TaskState.COMPLETED_UNCLAIMED;
		}

		// Find tile in grid
		TaskTile tile = null;
		for (TaskTile t : grid)
		{
			if (t.getId().equals(tileId))
			{
				tile = t;
				break;
			}
		}
		if (tile == null) return TaskState.LOCKED;

		Set<String> claimedPositions = getClaimedPositions();
		// CLAIMED only at the specific position the user claimed (not every tile with the same task)
		if (claimedPositions.contains(tileId)) return TaskState.CLAIMED;
		// Completed-but-unclaimed only when this task is done and not yet claimed (anywhere)
		String key = taskKeyFromName(tile.getDisplayName());
		if (isCompleted(key) && !isClaimed(key)) return TaskState.COMPLETED_UNCLAIMED;

		// Revealed if any cardinal neighbor position is claimed (same logic as Area Task grid)
		if (isRevealedGlobal(tile, claimedPositions)) return TaskState.REVEALED;
		return TaskState.LOCKED;
	}

	/**
	 * Same reveal logic as Area Task grid (TaskGridService.isRevealed):
	 * tile is revealed if any cardinal neighbor position is in the claimed set.
	 * Uses position-based claiming (not task-key) so it works for infinite rings.
	 */
	private boolean isRevealedGlobal(TaskTile tile, Set<String> claimedPositions)
	{
		return RevealLogic.revealedByClaimedTaskIds(tile.getRow(), tile.getCol(), claimedPositions);
	}

	/** Returns claimed grid positions: center (if claimed) + explicitly stored claimed positions. */
	private Set<String> getClaimedPositions()
	{
		Set<String> claimed = new HashSet<>();
		if (isCenterClaimed())
			claimed.add("0,0");
		claimed.addAll(loadClaimedPositions());
		return claimed;
	}

	/** Loads the set of grid positions (row,col) that have been claimed (for reveal logic). */
	private Set<String> loadClaimedPositions()
	{
		String raw = configManager.getConfiguration(STATE_GROUP, KEY_GLOBAL_CLAIMED_POSITIONS);
		Set<String> set = new HashSet<>();
		if (raw != null && !raw.isEmpty())
		{
			for (String pos : raw.split(Pattern.quote(CLAIMED_POS_SEP)))
			{
				String p = pos.trim();
				if (!p.isEmpty()) set.add(p);
			}
		}
		return set;
	}

	private void saveClaimedPositions(Set<String> positions)
	{
		configManager.setConfiguration(STATE_GROUP, KEY_GLOBAL_CLAIMED_POSITIONS,
			String.join(CLAIMED_POS_SEP, positions));
	}

	public boolean isCenterClaimed()
	{
		String raw = configManager.getConfiguration(STATE_GROUP, KEY_GLOBAL_CENTER_CLAIMED);
		return "true".equals(raw);
	}

	/** True if the starter area tile is unlocked on the World Unlock grid; must be true before the player can claim any task. */
	public boolean isStarterAreaUnlockedOnGrid()
	{
		String start = config.startingArea();
		return start != null && !start.isEmpty() && worldUnlockService.getUnlockedIds().contains(start);
	}

	public void claimCenter()
	{
		// Persist center as claimed
		configManager.setConfiguration(STATE_GROUP, KEY_GLOBAL_CENTER_CLAIMED, "true");
		Set<String> claimedPos = loadClaimedPositions();
		claimedPos.add("0,0");
		saveClaimedPositions(claimedPos);
		savePseudoCenter("0,0");
		// Auto-unlock the starter world tile (e.g. Lumbridge) so getGlobalTasks returns area tasks for adjacent slots
		List<WorldUnlockTilePlacement> grid = worldUnlockService.getGrid();
		if (!grid.isEmpty())
		{
			WorldUnlockTile starter = grid.get(0).getTile();
			if (starter != null && worldUnlockService.getTileCost(starter) == 0
				&& (starter.getPrerequisites() == null || starter.getPrerequisites().isEmpty()))
			{
				String starterId = starter.getId();
				boolean wasNewUnlock = starterId != null && !worldUnlockService.getUnlockedIds().contains(starterId);
				boolean unlocked = worldUnlockService.unlock(starterId, worldUnlockService.getTileCost(starter));
				log.debug("[GlobalTask] claimCenter: auto-unlocked starter {} = {}", starterId, unlocked);
				if (unlocked && wasNewUnlock)
				{
					resetRepeatableSkillTaskProgressAfterAreaUnlock();
				}
			}
		}
	}

	/**
	 * World Unlock: when a new area is unlocked, returns fully claimed whitelisted no-area skill tasks
	 * (not {@code onceOnly}) to the rollable pool: clears completion/claim state for the task key and
	 * replaces its grid cell(s) with the same placeholder task used for unknown assignments (display name
	 * Unknown, task key {@code unknown}) so claimed positions
	 * stay claimed (reveal map unchanged) while the real task can roll onto newly revealed cells.
	 * Skips area-bound tasks, {@code onceOnly} tasks, completed-but-unclaimed tasks, and tasks on the grid
	 * that were never claimed (still in progress).
	 */
	public void resetRepeatableSkillTaskProgressAfterAreaUnlock()
	{
		List<TaskDefinition> defs = taskGridService.getEffectiveDefaultTasks();
		if (defs == null || defs.isEmpty())
		{
			return;
		}

		String placeholderKey = taskKey(placeholderTile());

		Set<String> completed = loadSet(KEY_GLOBAL_COMPLETED);
		Set<String> claimed = loadSet(KEY_GLOBAL_CLAIMED);
		Set<String> claimedPos = loadClaimedPositions();
		Map<String, String> gridState = loadGridState();
		Set<String> seenKeys = new HashSet<>();
		boolean changed = false;

		for (TaskDefinition t : defs)
		{
			if (t == null || !TaskTypes.isRepeatableWorldUnlockSkillType(t.getTaskType())) continue;
			if (Boolean.TRUE.equals(t.getOnceOnly())) continue;
			List<String> reqAreas = t.getRequiredAreaIds();
			if (reqAreas != null && !reqAreas.isEmpty()) continue;
			String key = taskKey(t);
			if (key.isEmpty() || !seenKeys.add(key)) continue;
			if (completed.contains(key) && !claimed.contains(key)) continue;
			if (!claimed.contains(key)) continue;

			if (completed.remove(key))
				changed = true;
			if (claimed.remove(key))
				changed = true;
			for (String pos : new ArrayList<>(getPositionsForTaskKey(gridState, key)))
			{
				String norm = normalizePos(pos);
				String prev = gridState.put(norm, placeholderKey);
				if (prev == null || !placeholderKey.equals(prev))
					changed = true;
			}
		}

		if (changed)
		{
			saveSet(KEY_GLOBAL_COMPLETED, completed);
			saveSet(KEY_GLOBAL_CLAIMED, claimed);
			saveClaimedPositions(claimedPos);
			saveGridState(gridState);
		}
	}

	public boolean isCompleted(String taskKey)
	{
		return loadSet(KEY_GLOBAL_COMPLETED).contains(taskKey);
	}

	public boolean isClaimed(String taskKey)
	{
		return loadSet(KEY_GLOBAL_CLAIMED).contains(taskKey);
	}

	/** Marks a task as completed (e.g. by auto-completion). Does not award points. */
	public void setCompleted(String taskKey)
	{
		Set<String> completed = loadSet(KEY_GLOBAL_COMPLETED);
		completed.add(taskKey);
		saveSet(KEY_GLOBAL_COMPLETED, completed);
	}

	/** Returns the points awarded when a task of the given difficulty is claimed. */
	public int getPointsForDifficulty(int difficulty)
	{
		return pointsForTier(difficulty);
	}

	/** Sentinel for unknown position (don't persist to claimed positions). */
	private static final int UNKNOWN_POS = -999;

	/**
	 * Marks a task as claimed: persists and awards points by difficulty. Idempotent if already claimed.
	 * Use {@link #claimTask(String, int, int)} when the tile position is known so adjacent tiles reveal.
	 * @return ring-completion bonus points awarded this call (0 if none)
	 */
	public int claimTask(String taskKey)
	{
		return claimTask(taskKey, UNKNOWN_POS, UNKNOWN_POS);
	}

	/**
	 * Marks a task as claimed at the given grid position. Persists the position so adjacent tiles reveal;
	 * no tile/task repositioning occurs. Idempotent if already claimed.
	 * @return ring-completion bonus points awarded this call (0 if none)
	 */
	public int claimTask(String taskKey, int row, int col)
	{
		Set<String> claimed = loadSet(KEY_GLOBAL_CLAIMED);
		if (claimed.contains(taskKey))
			return 0;

		TaskDefinition task = getGlobalTasks().stream()
			.filter(t -> taskKey(t).equals(taskKey))
			.findFirst()
			.orElse(null);
		int points = task != null ? pointsForTier(task.getDifficulty()) : 0;

		claimed.add(taskKey);
		saveSet(KEY_GLOBAL_CLAIMED, claimed);

		// Persist claimed position only when known so getClaimedPositions() reveals adjacent tiles
		boolean positionKnown = (row != UNKNOWN_POS || col != UNKNOWN_POS);
		if (positionKnown)
		{
			String pos = row + "," + col;
			Set<String> claimedPos = loadClaimedPositions();
			claimedPos.add(pos);
			saveClaimedPositions(claimedPos);
			savePseudoCenter(pos);
		}
		else
		{
			List<String> positions = getPositionsForTaskKey(loadGridState(), taskKey);
			if (!positions.isEmpty())
				savePseudoCenter(positions.get(0));
		}
		if (points > 0)
		{
			pointsService.addEarned(points);
			log.debug("Global task {} claimed at ({},{}), +{} points", taskKey, row, col, points);
		}
		if (positionKnown)
			return maybeAwardGlobalRingBonus(row, col);
		return 0;
	}

	/**
	 * When every tile in a Chebyshev ring is claimed on the global grid, awards
	 * {@code min(ring × pointsForTier(mode difficulty), RING_BONUS_CAP)}.
	 * @return bonus points awarded, or 0
	 */
	private int maybeAwardGlobalRingBonus(int row, int col)
	{
		int ring = GridPos.ringNumber(row, col);
		if (ring <= 0) return 0;

		Set<String> ringBonusDone = loadGlobalRingBonusSet();
		if (ringBonusDone.contains(Integer.toString(ring))) return 0;

		List<TaskTile> grid = buildGlobalGrid(getOrCreateLayoutSeed());
		List<TaskTile> inRing = grid.stream()
			.filter(t -> GridPos.ringNumber(t.getRow(), t.getCol()) == ring)
			.collect(Collectors.toList());
		if (inRing.isEmpty()) return 0;

		Set<String> claimed = loadSet(KEY_GLOBAL_CLAIMED);
		for (TaskTile t : inRing)
		{
			String k = taskKeyFromName(t.getDisplayName());
			if (k.isEmpty() || !claimed.contains(k)) return 0;
		}

		int modeTier = modeDifficultyTierFromTiles(inRing);
		int tierPoints = pointsForTier(modeTier);
		int bonus = Math.min(ring * tierPoints, RING_BONUS_CAP);
		if (bonus <= 0) return 0;

		pointsService.addEarned(bonus);
		ringBonusDone.add(Integer.toString(ring));
		saveGlobalRingBonusSet(ringBonusDone);
		log.debug("Global grid ring {} completion bonus: +{} (mode tier {})", ring, bonus, modeTier);
		return bonus;
	}

	private int modeDifficultyTierFromTiles(List<TaskTile> tiles)
	{
		int[] counts = new int[MAX_TIER + 1];
		for (TaskTile t : tiles)
		{
			int d = difficultyTierFromPointsForGlobal(t.getPoints());
			counts[d]++;
		}
		int best = 1;
		int maxCount = -1;
		for (int d = 1; d <= MAX_TIER; d++)
		{
			if (counts[d] > maxCount)
			{
				maxCount = counts[d];
				best = d;
			}
		}
		return best;
	}

	private int difficultyTierFromPointsForGlobal(int points)
	{
		for (int t = 1; t <= MAX_TIER; t++)
		{
			if (points == pointsForTier(t))
				return t;
		}
		for (int t = MAX_TIER; t >= 1; t--)
		{
			if (points >= pointsForTier(t))
				return t;
		}
		return 1;
	}

	private Set<String> loadGlobalRingBonusSet()
	{
		String raw = configManager.getConfiguration(STATE_GROUP, KEY_GLOBAL_RING_BONUS);
		Set<String> set = new HashSet<>();
		if (raw != null && !raw.isEmpty())
		{
			for (String id : raw.split("\\" + ID_SEP))
			{
				String tid = id.trim();
				if (!tid.isEmpty()) set.add(tid);
			}
		}
		return set;
	}

	private void saveGlobalRingBonusSet(Set<String> set)
	{
		configManager.setConfiguration(STATE_GROUP, KEY_GLOBAL_RING_BONUS, String.join(ID_SEP, set));
	}

	private int pointsForTier(int tier)
	{
		switch (tier)
		{
			case 0: return 0;
			case 1: return config.taskTier1Points();
			case 2: return config.taskTier2Points();
			case 3: return config.taskTier3Points();
			case 4: return config.taskTier4Points();
			case 5: return config.taskTier5Points();
			default: return config.taskTier5Points();
		}
	}

	private Set<String> loadSet(String key)
	{
		String raw = configManager.getConfiguration(STATE_GROUP, key);
		Set<String> set = new HashSet<>();
		if (raw != null && !raw.isEmpty())
		{
			for (String id : raw.split("\\" + ID_SEP))
			{
				String tid = id.trim();
				if (!tid.isEmpty()) set.add(tid);
			}
		}
		return set;
	}

	private void saveSet(String key, Set<String> set)
	{
		String value = String.join(ID_SEP, set);
		configManager.setConfiguration(STATE_GROUP, key, value);
	}

	/** Clears global task completed and claimed state (e.g. on reset). */
	public void clearGlobalTaskProgress()
	{
		configManager.unsetConfiguration(STATE_GROUP, KEY_GLOBAL_CLAIMED);
		configManager.unsetConfiguration(STATE_GROUP, KEY_GLOBAL_COMPLETED);
		configManager.unsetConfiguration(STATE_GROUP, KEY_GLOBAL_CENTER_CLAIMED);
		configManager.unsetConfiguration(STATE_GROUP, KEY_GLOBAL_CLAIMED_POSITIONS);
		configManager.unsetConfiguration(STATE_GROUP, KEY_GLOBAL_TASK_POSITIONS);
		configManager.unsetConfiguration(STATE_GROUP, KEY_GLOBAL_PSEUDO_CENTER);
		configManager.unsetConfiguration(STATE_GROUP, KEY_GLOBAL_LAST_VIEWED);
		configManager.unsetConfiguration(STATE_GROUP, KEY_GLOBAL_RING_BONUS);
		configManager.unsetConfiguration(STATE_GROUP, KEY_GLOBAL_LAYOUT_SEED);
	}

	private static List<int[]> spiralOrderForRing(int tier)
	{
		return Spiral.ring(tier);
	}
}

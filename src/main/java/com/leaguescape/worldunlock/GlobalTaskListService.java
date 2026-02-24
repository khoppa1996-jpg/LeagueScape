package com.leaguescape.worldunlock;

import com.leaguescape.LeagueScapeConfig;
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
	private static final String STATE_GROUP = "leaguescapeState";
	private static final String KEY_GLOBAL_CLAIMED = "globalTaskProgress_claimed";
	private static final String KEY_GLOBAL_COMPLETED = "globalTaskProgress_completed";
	private static final String KEY_GLOBAL_CENTER_CLAIMED = "globalTaskProgress_centerClaimed";
	private static final String KEY_GLOBAL_TASK_POSITIONS = "globalTaskProgress_positions";
	private static final String KEY_GLOBAL_PSEUDO_CENTER = "globalTaskProgress_pseudoCenter";
	private static final String KEY_GLOBAL_LAST_VIEWED = "globalTaskProgress_lastViewed";
	/** Stores which grid positions have been claimed (for reveal); format "row,col||row,col". */
	private static final String KEY_GLOBAL_CLAIMED_POSITIONS = "globalTaskProgress_claimedPositions";
	private static final String ID_SEP = ",";
	private static final String POS_ENTRY_SEP = "||";
	/** Separator for list of claimed positions (must not be comma, since position is "row,col"). */
	private static final String CLAIMED_POS_SEP = ";;";
	private static final String POS_KV_SEP = "::";
	/** Separator for multi-position keys: taskKey + COMPOSITE_SEP + pos (allows same task at multiple positions). */
	private static final String COMPOSITE_SEP = "|||";
	private static final int MAX_TIER = 5;
	/** Minimum rings (matches Area Task grid); ensures tier 4/5 visibility. */
	private static final int MIN_RINGS = 9;
	/** Maximum rings for "infinite" expansion. */
	private static final int MAX_RINGS = 100;

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

	/**
	 * Returns the rollable task list for the Global Task panel.
	 * Tasks are filtered by World Unlock panel state:
	 * - Area tasks: require all areas (or any if areaRequirement=any) to be unlocked
	 * - Skill tasks: require the matching skill tile to be unlocked (e.g. Woodcutting 1-10)
	 * - Quest tasks: require a quest unlock that satisfies the task's requirements
	 * - Diary tasks: require at least one achievement diary unlock
	 * - Boss tasks: require at least one boss unlock
	 * - No-area, no-type tasks: always allowed
	 */
	public List<TaskDefinition> getGlobalTasks()
	{
		UnlockedContent unlocked = buildUnlockedContent();
		LinkedHashMap<String, TaskDefinition> byKey = new LinkedHashMap<>();

		// 1. Add tasks from unlocked taskDisplayNames tiles (explicit task lists)
		for (String unlockId : worldUnlockService.getUnlockedIds())
		{
			for (TaskDefinition t : worldUnlockService.getTasksForUnlock(unlockId))
			{
				WorldUnlockTile tile = worldUnlockService.getTileById(unlockId);
				if (tile == null || tile.getTaskLink() == null) continue;
				String linkType = tile.getTaskLink().getType();
				if (!"taskDisplayNames".equals(linkType)) continue;
				String key = taskKey(t);
				if (!key.isEmpty() && !byKey.containsKey(key))
					byKey.put(key, t);
			}
		}

		// 2. Filter all tasks by unlock state (area, skill, quest, diary, boss)
		for (TaskDefinition t : taskGridService.getEffectiveDefaultTasks())
		{
			String key = taskKey(t);
			if (key.isEmpty() || byKey.containsKey(key)) continue;

			if (canTaskAppearWithUnlocks(t, unlocked))
				byKey.put(key, t);
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

	/**
	 * True if this task can appear in the Global Task panel given current World Unlock state.
	 * Mirrors the unlock-type gating from World Unlock tiles.
	 */
	private boolean canTaskAppearWithUnlocks(TaskDefinition task, UnlockedContent u)
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

		// 2. Skill: if taskType matches a skill unlock tile, that skill must be unlocked
		String taskType = task.getTaskType();
		if (taskType != null && u.allSkillNames.contains(taskType) && !u.skills.contains(taskType))
			return false;

		// 3. Quest: if task has quest requirements or is Quest type, need quest unlock
		if ("Quest".equalsIgnoreCase(taskType) || (task.getRequirements() != null && !task.getRequirements().trim().isEmpty()))
		{
			if (u.questRequirements.isEmpty())
				return false;
			if (task.getRequirements() != null && !task.getRequirements().trim().isEmpty())
			{
				for (String part : task.getRequirements().split(","))
				{
					String q = part.trim().toLowerCase();
					if (q.isEmpty()) continue;
					boolean satisfied = u.questRequirements.stream()
						.anyMatch(unlocked -> unlocked.contains(q) || q.contains(unlocked));
					if (!satisfied)
						return false;
				}
			}
		}

		// 4. Achievement Diary: diary tasks need at least one diary unlock
		if ("Achievement Diary".equalsIgnoreCase(taskType) || "Diary".equalsIgnoreCase(taskType))
		{
			if (u.diaryRequirements.isEmpty())
				return false;
		}

		// 5. Boss: boss tasks need at least one boss unlock
		if ("Boss".equalsIgnoreCase(taskType))
		{
			if (u.bossRequirements.isEmpty())
				return false;
		}

		return true;
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

		UnlockedContent(Set<String> areas, Set<String> skills, Set<String> questRequirements,
			Set<String> diaryRequirements, Set<String> bossRequirements, Set<String> allSkillNames)
		{
			this.areas = areas;
			this.skills = skills;
			this.questRequirements = questRequirements;
			this.diaryRequirements = diaryRequirements;
			this.bossRequirements = bossRequirements;
			this.allSkillNames = allSkillNames;
		}

		String summary()
		{
			return "areas=" + areas.size() + ",skills=" + skills.size()
				+ ",quests=" + questRequirements.size() + ",diaries=" + diaryRequirements.size()
				+ ",bosses=" + bossRequirements.size();
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

		return new UnlockedContent(areas, skills, questReqs, diaryReqs, bossReqs, allSkillNames);
	}

	/**
	 * Builds the grid using lazy assignment: only assign a task to a cell when it is first revealed
	 * (adjacent to a claimed cell). Center (0,0) is the anchor. Each task is used at most once (strict one-use).
	 * Available tasks = no-area + unlocked World Unlock state only.
	 */
	public List<TaskTile> buildGlobalGrid(int reshuffleSeed)
	{
		worldUnlockService.load();
		List<TaskTile> out = new ArrayList<>();
		out.add(new TaskTile(TaskTile.idFor(0, 0), 0, "Free", 0, 0, 0, null, null, true, null));

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

		// 2. Load persisted state: position -> task assignments, claimed positions
		Map<String, String> posMap = loadTaskPositions();
		Set<String> claimedPositions = getClaimedPositions();
		Map<String, TaskDefinition> allTasksByKeyFallback = new HashMap<>();
		for (TaskDefinition t : taskGridService.getEffectiveDefaultTasks())
		{
			String k = taskKey(t);
			if (!k.isEmpty() && !allTasksByKeyFallback.containsKey(k))
				allTasksByKeyFallback.put(k, t);
		}

		// 3. Revealed positions = center + all positions adjacent to any claimed position
		Set<String> revealedPositions = new HashSet<>();
		revealedPositions.add("0,0");
		for (String claimed : claimedPositions)
		{
			revealedPositions.add(claimed);
			revealedPositions.addAll(getNeighborPositions(claimed));
		}

		// 4. atPosition: already-assigned revealed cells (from posMap). toAssign: only revealed cells that have NO entry in posMap.
		// If a position exists in posMap at all, never put it in toAssign — we never reassign.
		Map<String, TaskDefinition> atPosition = new HashMap<>();
		List<String> toAssign = new ArrayList<>();
		for (String pos : revealedPositions)
		{
			if ("0,0".equals(pos)) continue;  // center has no task assignment
			String normPos = normalizePos(pos);
			boolean inPosMap = false;
			for (Map.Entry<String, String> e : posMap.entrySet())
			{
				// Match by normalized value, or by position embedded in composite key (robust to any value format)
				String entryPos = e.getValue();
				boolean valueMatches = normPos.equals(normalizePos(entryPos));
				if (!valueMatches && e.getKey() != null && e.getKey().contains(COMPOSITE_SEP))
				{
					String keyPos = e.getKey().substring(e.getKey().indexOf(COMPOSITE_SEP) + COMPOSITE_SEP.length()).trim();
					valueMatches = normPos.equals(normalizePos(keyPos));
				}
				if (!valueMatches) continue;
				inPosMap = true;  // position has an assignment in storage — never reassign
				String tk = parseTaskKeyFromPositionKey(e.getKey());
				if (!tk.isEmpty() && !isPositionLike(tk))
				{
					TaskDefinition def = allTasksByKeyFallback.get(tk);
					if (def == null) def = taskByKey.get(tk);
					if (def == null)
					{
						// Keep tile visible even when task key can't be resolved (e.g. key format change)
						def = new TaskDefinition();
						def.setDisplayName(tk);
						def.setDifficulty(1);
					}
					atPosition.put(pos, def);
				}
				break;
			}
			if (!inPosMap)
				toAssign.add(pos);
		}

		// 5. Task keys that must not be assigned again: already in posMap (placed) + claimed by user
		Set<String> alreadyPlacedTaskKeys = new HashSet<>();
		for (String k : posMap.keySet())
		{
			String tk = parseTaskKeyFromPositionKey(k);
			if (!tk.isEmpty() && !isPositionLike(tk)) alreadyPlacedTaskKeys.add(tk);
		}
		Set<String> claimedTaskKeys = loadSet(KEY_GLOBAL_CLAIMED);
		alreadyPlacedTaskKeys.addAll(claimedTaskKeys);

		// 6. Available for new assignment = pool minus already placed minus claimed. Sort by difficulty (tier 1 first, then 2, etc.).
		List<TaskDefinition> availableForNew = new ArrayList<>();
		for (TaskDefinition t : taskByKey.values())
		{
			if (!alreadyPlacedTaskKeys.contains(taskKey(t)))
				availableForNew.add(t);
		}
		// Match Area Task Grid: tier 1 closer to center, then tier 2, tier 3, etc. (spiral order + difficulty ordering)
		availableForNew.sort((a, b) -> {
			int da = Math.max(1, Math.min(MAX_TIER, a.getDifficulty()));
			int db = Math.max(1, Math.min(MAX_TIER, b.getDifficulty()));
			if (da != db) return Integer.compare(da, db);
			return taskKey(a).compareTo(taskKey(b));
		});
		// Optional: shuffle within same difficulty for variety (use seed so reproducible)
		Random rnd = new Random(reshuffleSeed);
		int idx = 0;
		while (idx < availableForNew.size())
		{
			int d = Math.max(1, Math.min(MAX_TIER, availableForNew.get(idx).getDifficulty()));
			int j = idx + 1;
			while (j < availableForNew.size() && Math.max(1, Math.min(MAX_TIER, availableForNew.get(j).getDifficulty())) == d)
				j++;
			if (j > idx + 1)
				java.util.Collections.shuffle(availableForNew.subList(idx, j), rnd);
			idx = j;
		}

		// 7. Sort toAssign by spiral order (center-first, then by ring), assign one task per position (strict one-use)
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

		Map<String, String> toPersist = new HashMap<>(posMap);
		for (int i = 0; i < toAssignRc.size() && i < availableForNew.size(); i++)
		{
			int[] rc = toAssignRc.get(i);
			String pos = rc[0] + "," + rc[1];
			TaskDefinition def = availableForNew.get(i);
			atPosition.put(pos, def);
			String key = taskKey(def);
			toPersist.put(key + COMPOSITE_SEP + pos, pos);
		}

		// 8. Output: center + all assigned revealed positions (sorted)
		List<String> positionsToOutput = new ArrayList<>(atPosition.keySet());
		positionsToOutput.sort((a, b) -> {
			int[] ar = parsePos(a), br = parsePos(b);
			if (ar == null || br == null) return 0;
			return byDistFromCenter.compare(ar, br);
		});

		for (String posStr : positionsToOutput)
		{
			if ("0,0".equals(posStr)) continue;  // center already in out
			int[] rc = parsePos(posStr);
			if (rc == null) continue;
			TaskDefinition def = atPosition.get(posStr);
			if (def == null) continue;
			int r = rc[0], c = rc[1];
			String id = TaskTile.idFor(r, c);
			int difficulty = Math.max(1, Math.min(MAX_TIER, def.getDifficulty()));
			int points = pointsForTier(difficulty);
			String displayName = def.getDisplayName() != null ? def.getDisplayName() : id;
			out.add(new TaskTile(id, difficulty, displayName, points, r, c,
				def.getTaskType(),
				def.getRequiredAreaIds().isEmpty() ? null : new ArrayList<>(def.getRequiredAreaIds()),
				!def.isAreaRequirementAny(),
				def.getRequirements()));
		}

		// 9. Persist: only add new assignments (never remove existing)
		saveTaskPositions(toPersist);
		log.debug("[GlobalTask] buildGlobalGrid output: {} tiles (revealed+assigned)", out.size());
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

	private static int[] parsePos(String pos)
	{
		String[] p = pos.split(",");
		if (p.length != 2) return null;
		try
		{
			return new int[]{ Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()) };
		}
		catch (NumberFormatException e) { return null; }
	}

	private static int chebyshevDist(int r1, int c1, int r2, int c2)
	{
		return Math.max(Math.abs(r1 - r2), Math.abs(c1 - c2));
	}

	/** Keys must be composite "taskKey|||pos"; reject position-as-key entries (e.g. "1,0") that would show coords as task name. */
	private static boolean isPositionLike(String keyOrPos)
	{
		if (keyOrPos == null) return true;
		return keyOrPos.trim().matches("-?\\d+\\s*,\\s*-?\\d+");
	}

	/** Canonical position string "r,c" (no spaces) so load/store and comparisons match. */
	private static String normalizePos(String pos)
	{
		int[] rc = parsePos(pos);
		return rc != null ? (rc[0] + "," + rc[1]) : (pos != null ? pos.trim() : "");
	}

	private Map<String, String> loadTaskPositions()
	{
		String raw = configManager.getConfiguration(STATE_GROUP, KEY_GLOBAL_TASK_POSITIONS);
		Map<String, String> map = new HashMap<>();
		if (raw == null || raw.isEmpty()) return map;
		// Split by "||" but not when part of "|||" (composite key), so entries like "taskKey|||pos::pos" stay intact
		Pattern entrySplit = Pattern.compile("\\|\\|(?!\\|)");
		for (String entry : entrySplit.split(raw))
		{
			int lastSep = entry.lastIndexOf(POS_KV_SEP);
			if (lastSep < 0) continue;
			String key = entry.substring(0, lastSep).trim();
			String pos = entry.substring(lastSep + POS_KV_SEP.length()).trim();
			// Ignore position-as-key entries (legacy/corrupt) so we don't show coordinates as task names
			if (key.isEmpty() || pos.isEmpty() || !key.contains(COMPOSITE_SEP) || isPositionLike(key))
				continue;
			map.put(key, normalizePos(pos));
		}
		return map;
	}

	private void saveTaskPositions(Map<String, String> map)
	{
		List<String> parts = new ArrayList<>();
		for (Map.Entry<String, String> e : map.entrySet())
		{
			String k = e.getKey();
			// Only persist composite keys "taskKey|||pos"; skip position-as-key entries so they are dropped
			if (k != null && k.contains(COMPOSITE_SEP) && !isPositionLike(k))
				parts.add(k + POS_KV_SEP + e.getValue());
		}
		configManager.setConfiguration(STATE_GROUP, KEY_GLOBAL_TASK_POSITIONS, String.join(POS_ENTRY_SEP, parts));
	}

	/** Extracts taskKey from a position map key (handles "taskKey" or "taskKey|||pos"). Normalized for lookup (trim + lowercase). */
	private static String parseTaskKeyFromPositionKey(String positionKey)
	{
		if (positionKey == null) return "";
		int i = positionKey.indexOf(COMPOSITE_SEP);
		String raw = i >= 0 ? positionKey.substring(0, i) : positionKey;
		return raw != null ? raw.trim().toLowerCase() : "";
	}

	/** Returns all positions stored for the given task key (supports multi-position and legacy single key). */
	private static List<String> getPositionsForTaskKey(Map<String, String> posMap, String taskKey)
	{
		List<String> out = new ArrayList<>();
		if (taskKey == null || posMap == null) return out;
		String single = posMap.get(taskKey);
		if (single != null) out.add(single);
		String prefix = taskKey + COMPOSITE_SEP;
		for (Map.Entry<String, String> e : posMap.entrySet())
		{
			if (e.getKey() != null && e.getKey().startsWith(prefix))
				out.add(e.getValue());
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
		int row = tile.getRow(), col = tile.getCol();
		int[][] deltas = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
		for (int[] d : deltas)
		{
			int nr = row + d[0], nc = col + d[1];
			String neighborId = TaskTile.idFor(nr, nc);
			if (claimedPositions.contains(neighborId))
				return true;
		}
		return false;
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
			if (starter != null && starter.getCost() == 0
				&& (starter.getPrerequisites() == null || starter.getPrerequisites().isEmpty()))
			{
				boolean unlocked = worldUnlockService.unlock(starter.getId(), starter.getCost());
				log.debug("[GlobalTask] claimCenter: auto-unlocked starter {} = {}", starter.getId(), unlocked);
			}
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
	 */
	public void claimTask(String taskKey)
	{
		claimTask(taskKey, UNKNOWN_POS, UNKNOWN_POS);
	}

	/**
	 * Marks a task as claimed at the given grid position. Persists the position so adjacent tiles reveal;
	 * no tile/task repositioning occurs. Idempotent if already claimed.
	 */
	public void claimTask(String taskKey, int row, int col)
	{
		Set<String> claimed = loadSet(KEY_GLOBAL_CLAIMED);
		if (claimed.contains(taskKey))
			return;

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
			List<String> positions = getPositionsForTaskKey(loadTaskPositions(), taskKey);
			if (!positions.isEmpty())
				savePseudoCenter(positions.get(0));
		}
		if (points > 0)
		{
			pointsService.addEarned(points);
			log.debug("Global task {} claimed at ({},{}), +{} points", taskKey, row, col, points);
		}
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
	}

	private static List<int[]> spiralOrderForRing(int tier)
	{
		List<int[]> out = new ArrayList<>(8 * tier);
		for (int c = 1 - tier; c <= tier; c++) out.add(new int[]{ tier, c });
		for (int r = tier - 1; r >= -tier; r--) out.add(new int[]{ r, tier });
		for (int c = tier - 1; c >= -tier; c--) out.add(new int[]{ -tier, c });
		for (int r = -tier + 1; r <= tier; r++) out.add(new int[]{ r, -tier });
		return out;
	}
}

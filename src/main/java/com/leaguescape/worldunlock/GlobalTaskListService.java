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
import java.util.regex.Pattern;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
	private static final String ID_SEP = ",";
	private static final String POS_ENTRY_SEP = "||";
	private static final String POS_KV_SEP = "::";
	/** Separator for multi-position keys: taskKey + COMPOSITE_SEP + pos (allows same task at multiple positions). */
	private static final String COMPOSITE_SEP = "|||";
	private static final int MAX_TIER = 5;

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
	 * Returns the flat list of global tasks: tasks from unlocked world-unlock tiles,
	 * plus tasks with no area or "undefined" area (always available). Only includes tasks
	 * that can be completed with currently unlocked skills, areas, quests, achievement diaries,
	 * and bosses. Deduplicated by task key.
	 */
	public List<TaskDefinition> getGlobalTasks()
	{
		UnlockedContent unlocked = buildUnlockedContent();

		LinkedHashMap<String, TaskDefinition> byKey = new LinkedHashMap<>();

		// 1. Tasks from unlocked world-unlock tiles
		Set<String> unlockedIds = worldUnlockService.getUnlockedIds();
		for (String unlockId : unlockedIds)
		{
			for (TaskDefinition t : worldUnlockService.getTasksForUnlock(unlockId))
			{
				if (!canTaskBeCompletedWithUnlocks(t, unlocked))
					continue;
				String key = taskKey(t);
				if (!key.isEmpty() && !byKey.containsKey(key))
					byKey.put(key, t);
			}
		}

		// 2. Tasks with no area or "undefined" area — include only if requirements are unlocked
		for (TaskDefinition t : taskGridService.getEffectiveDefaultTasks())
		{
			List<String> areaIds = t.getRequiredAreaIds();
			boolean noArea = areaIds == null || areaIds.isEmpty();
			boolean undefinedArea = areaIds != null && areaIds.stream()
				.anyMatch(a -> "undefined".equalsIgnoreCase(a));
			if (noArea || undefinedArea)
			{
				if (!canTaskBeCompletedWithUnlocks(t, unlocked))
					continue;
				String key = taskKey(t);
				if (!key.isEmpty() && !byKey.containsKey(key))
					byKey.put(key, t);
			}
		}

		return new ArrayList<>(byKey.values());
	}

	/** Unlocked content derived from world-unlock tiles. */
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

	/** True if the task can be completed with the currently unlocked content. */
	private boolean canTaskBeCompletedWithUnlocks(TaskDefinition task, UnlockedContent u)
	{
		// Area: required areas must be unlocked (or task has no area requirement)
		List<String> requiredAreas = task.getRequiredAreaIds();
		if (requiredAreas != null && !requiredAreas.isEmpty())
		{
			for (String areaId : requiredAreas)
			{
				if ("undefined".equalsIgnoreCase(areaId))
					continue;
				if (!u.areas.contains(areaId))
					return false;
			}
		}

		// Skill: if task requires a skill (taskType matches a skill unlock), that skill must be unlocked
		String taskType = task.getTaskType();
		if (taskType != null && u.allSkillNames.contains(taskType) && !u.skills.contains(taskType))
			return false;

		// Quest: if task has requirements (quest names), each must be unlocked
		String requirements = task.getRequirements();
		if (requirements != null && !requirements.trim().isEmpty())
		{
			for (String part : requirements.split(","))
			{
				String q = part.trim().toLowerCase();
				if (q.isEmpty()) continue;
				boolean satisfied = u.questRequirements.stream()
					.anyMatch(unlocked -> unlocked.contains(q) || q.contains(unlocked));
				if (!satisfied)
					return false;
			}
		}
		else if ("Quest".equalsIgnoreCase(taskType) && u.questRequirements.isEmpty())
		{
			return false;
		}

		// Achievement Diary: if task is diary type, need at least one diary unlock
		if ("Achievement Diary".equalsIgnoreCase(taskType) || "Diary".equalsIgnoreCase(taskType))
		{
			if (u.diaryRequirements.isEmpty())
				return false;
		}

		// Boss: if task is boss type, need at least one boss unlock
		if ("Boss".equalsIgnoreCase(taskType))
		{
			if (u.bossRequirements.isEmpty())
				return false;
		}

		return true;
	}

	/**
	 * Builds the spiral grid of TaskTile objects from the current global tasks.
	 * Claimed and revealed tasks keep their persisted positions and do not move.
	 * New tasks are placed deterministically by tier: lower tiers near the pseudo-center
	 * (last claimed position or 0,0), higher tiers farther out. No reshuffling.
	 * Center (0,0) is a special "Free" tile that must be claimed to reveal ring 1.
	 */
	public List<TaskTile> buildGlobalGrid(int reshuffleSeed)
	{
		List<TaskTile> out = new ArrayList<>();
		out.add(new TaskTile(TaskTile.idFor(0, 0), 0, "Free", 0, 0, 0, null, null, true, null));

		List<TaskDefinition> tasks = getGlobalTasks();
		if (tasks.isEmpty()) return out;

		Map<String, TaskDefinition> taskByKey = new LinkedHashMap<>();
		Set<String> seen = new HashSet<>();
		for (TaskDefinition t : tasks)
		{
			String key = taskKey(t);
			if (!key.isEmpty() && seen.add(key))
				taskByKey.put(key, t);
		}

		Map<String, String> posMap = loadTaskPositions();

		// Parse composite keys (taskKey|||pos) for multi-position support
		Set<String> fixedKeys = new HashSet<>();
		Set<String> occupiedPos = new HashSet<>(posMap.values());
		for (Map.Entry<String, String> e : posMap.entrySet())
		{
			String taskKey = parseTaskKeyFromPositionKey(e.getKey());
			if (taskByKey.containsKey(taskKey))
				fixedKeys.add(taskKey);
		}

		// Claimed positions (for computing adjacent slots for newly revealed)
		Set<String> claimedPositions = new HashSet<>();
		Set<String> claimedKeys = loadSet(KEY_GLOBAL_CLAIMED);
		for (String key : claimedKeys)
		{
			for (String pos : getPositionsForTaskKey(posMap, key))
				claimedPositions.add(pos);
		}
		if (isCenterClaimed())
			claimedPositions.add("0,0");

		// Positions adjacent to claimed (for newly revealed tasks)
		Set<String> adjacentToClaimed = new HashSet<>();
		for (String claimed : claimedPositions)
		{
			String[] parts = claimed.split(",");
			if (parts.length != 2) continue;
			try
			{
				int r = Integer.parseInt(parts[0].trim());
				int c = Integer.parseInt(parts[1].trim());
				int[][] d = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
				for (int[] delta : d)
					adjacentToClaimed.add((r + delta[0]) + "," + (c + delta[1]));
			}
			catch (NumberFormatException ignored) { }
		}

		List<int[]> forNewlyRevealed = new ArrayList<>();
		List<int[]> forLocked = new ArrayList<>();
		String pseudoCenter = loadPseudoCenter();
		int[] parsedCenter = parsePos(pseudoCenter);
		final int[] center = (parsedCenter != null) ? parsedCenter : new int[]{ 0, 0 };

		for (String p : adjacentToClaimed)
		{
			if (!occupiedPos.contains(p))
			{
				int[] rc = parsePos(p);
				if (rc != null)
					forNewlyRevealed.add(rc);
			}
		}
		Comparator<int[]> byDistFromCenter = (a, b) -> {
			int da = chebyshevDist(a[0], a[1], center[0], center[1]);
			int db = chebyshevDist(b[0], b[1], center[0], center[1]);
			if (da != db) return Integer.compare(da, db);
			if (a[0] != b[0]) return Integer.compare(a[0], b[0]);
			return Integer.compare(a[1], b[1]);
		};
		forNewlyRevealed.sort(byDistFromCenter);

		// All spiral positions (unlimited size — keep adding rings until we have enough)
		List<int[]> allSpiral = new ArrayList<>();
		int ring = 1;
		while (allSpiral.size() < taskByKey.size())
		{
			allSpiral.addAll(spiralOrderForRing(ring));
			ring++;
		}
		for (int[] rc : allSpiral)
		{
			String p = rc[0] + "," + rc[1];
			if (!occupiedPos.contains(p) && !adjacentToClaimed.contains(p))
				forLocked.add(rc);
		}
		// Ensure enough locked slots for unfixed tasks (each gets one locked position; adjacent filled by cycling)
		int unfixedCount = taskByKey.size() - fixedKeys.size();
		int neededLocked = unfixedCount;
		while (forLocked.size() < neededLocked)
		{
			allSpiral.addAll(spiralOrderForRing(ring));
			ring++;
			forLocked.clear();
			for (int[] rc : allSpiral)
			{
				String p = rc[0] + "," + rc[1];
				if (!occupiedPos.contains(p) && !adjacentToClaimed.contains(p))
					forLocked.add(rc);
			}
			forLocked.sort(byDistFromCenter);
		}

		// Unfixed tasks: sort by tier (ascending) for deterministic placement
		List<TaskDefinition> unfixed = new ArrayList<>();
		for (Map.Entry<String, TaskDefinition> e : taskByKey.entrySet())
		{
			if (!fixedKeys.contains(e.getKey()))
				unfixed.add(e.getValue());
		}
		unfixed.sort((a, b) -> {
			int ta = Math.max(1, Math.min(MAX_TIER, a.getDifficulty()));
			int tb = Math.max(1, Math.min(MAX_TIER, b.getDifficulty()));
			if (ta != tb) return Integer.compare(ta, tb);
			return taskKey(a).compareTo(taskKey(b));
		});

		int newlyRevealedCount = Math.min(unfixed.size(), forNewlyRevealed.size());
		List<TaskDefinition> newlyRevealed = unfixed.subList(0, newlyRevealedCount);
		List<TaskDefinition> locked = unfixed.subList(newlyRevealedCount, unfixed.size());

		// Build task -> position mapping (start with fixed from posMap)
		Map<String, String> taskToPos = new HashMap<>();
		for (Map.Entry<String, String> e : posMap.entrySet())
		{
			String taskKey = parseTaskKeyFromPositionKey(e.getKey());
			if (fixedKeys.contains(taskKey) && taskByKey.containsKey(taskKey))
				taskToPos.put(e.getKey(), e.getValue()); // keep composite key for multi-position
		}

		// Fill EVERY adjacent-to-claimed slot by cycling unfixed tasks (so no empty adjacent cells)
		for (int i = 0; i < forNewlyRevealed.size() && !unfixed.isEmpty(); i++)
		{
			int[] rc = forNewlyRevealed.get(i);
			TaskDefinition def = unfixed.get(i % unfixed.size());
			taskToPos.put(taskKey(def) + COMPOSITE_SEP + rc[0] + "," + rc[1], rc[0] + "," + rc[1]);
		}
		int idx = 0;
		for (TaskDefinition def : locked)
		{
			if (idx >= forLocked.size()) break;
			int[] rc = forLocked.get(idx++);
			taskToPos.put(taskKey(def) + COMPOSITE_SEP + rc[0] + "," + rc[1], rc[0] + "," + rc[1]);
		}

		// Build output: position -> task from taskToPos (supports multi-position via composite keys)
		Map<String, TaskDefinition> atPosition = new HashMap<>();
		for (Map.Entry<String, String> e : taskToPos.entrySet())
		{
			String pos = e.getValue();
			String taskKey = parseTaskKeyFromPositionKey(e.getKey());
			TaskDefinition def = taskByKey.get(taskKey);
			if (def != null && pos != null)
				atPosition.put(pos, def);
		}

		List<int[]> positionsToOutput = new ArrayList<>();
		for (String posStr : atPosition.keySet())
		{
			int[] rc = parsePos(posStr);
			if (rc != null) positionsToOutput.add(rc);
		}
		positionsToOutput.sort(byDistFromCenter);

		for (int[] rc : positionsToOutput)
		{
			String pos = rc[0] + "," + rc[1];
			TaskDefinition def = atPosition.get(pos);
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

		// Persist positions: use composite key (taskKey|||pos) so same task can appear at multiple positions.
		// Remove stale positions for tasks in the current output, then write current positions.
		Map<String, String> toPersist = new HashMap<>(posMap);
		Set<String> outputTaskKeys = new HashSet<>();
		for (TaskTile t : out)
		{
			if ("0,0".equals(t.getId())) continue;
			outputTaskKeys.add(taskKeyFromName(t.getDisplayName()));
		}
		for (String taskKey : outputTaskKeys)
		{
			List<String> toRemove = new ArrayList<>();
			for (String k : toPersist.keySet())
			{
				if (taskKey.equals(k) || k.startsWith(taskKey + COMPOSITE_SEP))
					toRemove.add(k);
			}
			for (String k : toRemove) toPersist.remove(k);
		}
		for (TaskTile t : out)
		{
			if ("0,0".equals(t.getId())) continue;
			String key = taskKeyFromName(t.getDisplayName());
			String pos = t.getRow() + "," + t.getCol();
			toPersist.put(key + COMPOSITE_SEP + pos, pos);
		}
		saveTaskPositions(toPersist);

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

	private Map<String, String> loadTaskPositions()
	{
		String raw = configManager.getConfiguration(STATE_GROUP, KEY_GLOBAL_TASK_POSITIONS);
		Map<String, String> map = new HashMap<>();
		if (raw == null || raw.isEmpty()) return map;
		for (String entry : raw.split(Pattern.quote(POS_ENTRY_SEP)))
		{
			int lastSep = entry.lastIndexOf(POS_KV_SEP);
			if (lastSep < 0) continue;
			String key = entry.substring(0, lastSep).trim();
			String pos = entry.substring(lastSep + POS_KV_SEP.length()).trim();
			if (!key.isEmpty() && !pos.isEmpty())
				map.put(key, pos);
		}
		return map;
	}

	private void saveTaskPositions(Map<String, String> map)
	{
		List<String> parts = new ArrayList<>();
		for (Map.Entry<String, String> e : map.entrySet())
		{
			parts.add(e.getKey() + POS_KV_SEP + e.getValue());
		}
		configManager.setConfiguration(STATE_GROUP, KEY_GLOBAL_TASK_POSITIONS, String.join(POS_ENTRY_SEP, parts));
	}

	/** Extracts taskKey from a position map key (handles "taskKey" or "taskKey|||pos"). */
	private static String parseTaskKeyFromPositionKey(String positionKey)
	{
		if (positionKey == null) return "";
		int i = positionKey.indexOf(COMPOSITE_SEP);
		return i >= 0 ? positionKey.substring(0, i) : positionKey;
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
		boolean isCenter = "0,0".equals(tileId);
		if (isCenter)
		{
			if (isCenterClaimed()) return TaskState.CLAIMED;
			return TaskState.COMPLETED_UNCLAIMED;
		}

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

		String key = taskKeyFromName(tile.getDisplayName());
		if (isClaimed(key)) return TaskState.CLAIMED;
		if (isCompleted(key)) return TaskState.COMPLETED_UNCLAIMED;

		if (isRevealedGlobal(tile, grid)) return TaskState.REVEALED;
		return TaskState.LOCKED;
	}

	private boolean isRevealedGlobal(TaskTile tile, List<TaskTile> grid)
	{
		int row = tile.getRow(), col = tile.getCol();
		int[][] deltas = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
		Map<String, TaskTile> posToTile = new HashMap<>();
		for (TaskTile t : grid)
			posToTile.put(t.getRow() + "," + t.getCol(), t);

		for (int[] d : deltas)
		{
			int nr = row + d[0], nc = col + d[1];
			TaskTile neighbor = posToTile.get(nr + "," + nc);
			if (neighbor == null) continue;

			if ("0,0".equals(neighbor.getId()))
			{
				if (isCenterClaimed()) return true;
			}
			else
			{
				String nKey = taskKeyFromName(neighbor.getDisplayName());
				if (isClaimed(nKey)) return true;
			}
		}
		return false;
	}

	public boolean isCenterClaimed()
	{
		String raw = configManager.getConfiguration(STATE_GROUP, KEY_GLOBAL_CENTER_CLAIMED);
		return "true".equals(raw);
	}

	public void claimCenter()
	{
		configManager.setConfiguration(STATE_GROUP, KEY_GLOBAL_CENTER_CLAIMED, "true");
		savePseudoCenter("0,0");
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

	/**
	 * Marks a task as claimed: persists and awards points by difficulty. Idempotent if already claimed.
	 */
	public void claimTask(String taskKey)
	{
		Set<String> claimed = loadSet(KEY_GLOBAL_CLAIMED);
		if (claimed.contains(taskKey)) return;

		TaskDefinition task = getGlobalTasks().stream()
			.filter(t -> taskKey(t).equals(taskKey))
			.findFirst()
			.orElse(null);
		int points = task != null ? pointsForTier(task.getDifficulty()) : 0;

		claimed.add(taskKey);
		saveSet(KEY_GLOBAL_CLAIMED, claimed);
		// Update pseudo-center for tier-based placement of newly revealed tasks
		List<String> positions = getPositionsForTaskKey(loadTaskPositions(), taskKey);
		if (!positions.isEmpty())
			savePseudoCenter(positions.get(0));
		if (points > 0)
		{
			pointsService.addEarned(points);
			log.debug("Global task {} claimed, +{} points", taskKey, points);
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

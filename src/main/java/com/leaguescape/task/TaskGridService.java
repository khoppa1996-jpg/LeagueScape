package com.leaguescape.task;

import com.google.gson.Gson;
import com.leaguescape.LeagueScapeConfig;
import com.leaguescape.LeagueScapePlugin;
import com.leaguescape.points.AreaCompletionService;
import com.leaguescape.points.PointsService;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;

/**
 * Generates task grid per area from tasks.json, persists completed/claimed state, and awards points on claim.
 * Tasks are randomized per area (seeded by areaId) with easy tasks near the center and harder tasks toward the outer edge.
 */
@Slf4j
@Singleton
public class TaskGridService
{
	private static final String STATE_GROUP = "leaguescapeState";
	private static final String KEY_PREFIX = "taskProgress_";
	private static final String SUFFIX_CLAIMED = "_claimed";
	private static final String SUFFIX_COMPLETED = "_completed";
	private static final String ID_SEP = "|";

	private static final int MAX_TIER = 5;
	private static final String TASKS_RESOURCE = "tasks.json";

	private final ConfigManager configManager;
	private final LeagueScapeConfig config;
	private final PointsService pointsService;
	private final AreaCompletionService areaCompletionService;

	private volatile TasksData tasksData;

	@Inject
	public TaskGridService(ConfigManager configManager, LeagueScapeConfig config,
		PointsService pointsService, AreaCompletionService areaCompletionService)
	{
		this.configManager = configManager;
		this.config = config;
		this.pointsService = pointsService;
		this.areaCompletionService = areaCompletionService;
	}

	private TasksData loadTasksData()
	{
		if (tasksData != null) return tasksData;
		synchronized (this)
		{
			if (tasksData != null) return tasksData;
			String pathStr = config.tasksFilePath();
			if (pathStr != null && !pathStr.trim().isEmpty())
			{
				try
				{
					Path path = Paths.get(pathStr.trim());
					if (Files.isRegularFile(path))
					{
						try (InputStream in = Files.newInputStream(path))
						{
							tasksData = new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), TasksData.class);
							if (tasksData != null && tasksData.getDefaultTasks() != null)
							{
								log.info("LeagueScape tasks loaded from {}", path);
								return tasksData;
							}
						}
					}
				}
				catch (Exception e)
				{
					log.warn("LeagueScape failed to load tasks from config path: {}", e.getMessage());
				}
			}
			try (InputStream in = LeagueScapePlugin.class.getResourceAsStream(TASKS_RESOURCE))
			{
				if (in != null)
				{
					tasksData = new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), TasksData.class);
					if (tasksData != null && tasksData.getDefaultTasks() != null)
					{
						log.debug("LeagueScape tasks loaded from built-in resource");
						return tasksData;
					}
				}
			}
			catch (Exception e)
			{
				log.warn("LeagueScape failed to load built-in tasks: {}", e.getMessage());
			}
			tasksData = new TasksData();
			tasksData.setDefaultTasks(new ArrayList<>());
			return tasksData;
		}
	}

	/** Get task list for an area (area override or default filtered by task.area). */
	private List<TaskDefinition> getTasksForArea(String areaId)
	{
		TasksData data = loadTasksData();
		if (data.getAreas() != null && data.getAreas().containsKey(areaId))
		{
			TasksData.AreaTasks at = data.getAreas().get(areaId);
			if (at != null && at.getTasks() != null && !at.getTasks().isEmpty())
				return filterTasksByArea(at.getTasks(), areaId);
		}
		List<TaskDefinition> defaultList = data.getDefaultTasks() != null ? data.getDefaultTasks() : new ArrayList<>();
		return filterTasksByArea(defaultList, areaId);
	}

	/** Keep only tasks that apply to this area (task.area is null/empty or equals areaId). */
	private List<TaskDefinition> filterTasksByArea(List<TaskDefinition> tasks, String areaId)
	{
		if (tasks == null) return new ArrayList<>();
		return tasks.stream()
			.filter(t -> t.getArea() == null || t.getArea().trim().isEmpty() || areaId.equals(t.getArea()))
			.collect(java.util.stream.Collectors.toList());
	}

	/**
	 * Generate the full task grid for an area (all tiers up to MAX_TIER).
	 * Center (0,0) is tier 0 "Free". Tasks are randomized per area (seeded by areaId):
	 * difficulty 1 near center, difficulty 5 at the outer edge.
	 */
	public List<TaskTile> getGridForArea(String areaId)
	{
		List<TaskDefinition> taskDefs = getTasksForArea(areaId);
		Random rng = new Random(areaId.hashCode());

		// Build (row, col) positions grouped by tier (1..5), in consistent order
		List<List<int[]>> positionsByTier = new ArrayList<>();
		for (int t = 0; t <= MAX_TIER; t++)
			positionsByTier.add(new ArrayList<>());
		for (int r = -MAX_TIER; r <= MAX_TIER; r++)
		{
			for (int c = -MAX_TIER; c <= MAX_TIER; c++)
			{
				int tier = Math.max(Math.abs(r), Math.abs(c));
				if (tier > MAX_TIER) continue;
				if (r == 0 && c == 0) continue;
				positionsByTier.get(tier).add(new int[]{r, c});
			}
		}

		// Partition tasks by difficulty (1-5); clamp invalid to 1
		List<List<TaskDefinition>> byDifficulty = new ArrayList<>();
		for (int d = 0; d <= MAX_TIER; d++)
			byDifficulty.add(new ArrayList<>());
		for (TaskDefinition def : taskDefs)
		{
			int d = def.getDifficulty();
			if (d < 1) d = 1;
			if (d > MAX_TIER) d = MAX_TIER;
			byDifficulty.get(d).add(def);
		}

		// Assign tasks to positions by tier: tier t uses difficulty t, random order (with replacement if needed)
		List<TaskDefinition> assigned = new ArrayList<>(120);
		for (int tier = 1; tier <= MAX_TIER; tier++)
		{
			List<int[]> positions = positionsByTier.get(tier);
			List<TaskDefinition> pool = byDifficulty.get(tier);
			if (pool.isEmpty())
				pool = taskDefs.isEmpty() ? new ArrayList<>() : byDifficulty.get(1);
			if (pool.isEmpty())
			{
				TaskDefinition fallback = new TaskDefinition();
				fallback.setDisplayName("Task " + tier);
				fallback.setTaskType(null);
				fallback.setDifficulty(tier);
				pool = Collections.singletonList(fallback);
			}
			List<TaskDefinition> shuffled = new ArrayList<>(pool);
			Collections.shuffle(shuffled, rng);
			for (int i = 0; i < positions.size(); i++)
				assigned.add(shuffled.get(i % shuffled.size()));
		}

		// Map (r,c) -> index into assigned (same order: tier 1 cells, then tier 2, ...)
		java.util.Map<String, Integer> positionToIndex = new java.util.HashMap<>();
		int idx = 0;
		for (int tier = 1; tier <= MAX_TIER; tier++)
			for (int[] rc : positionsByTier.get(tier))
				positionToIndex.put(rc[0] + "," + rc[1], idx++);

		// Build TaskTile list in (r,c) iteration order
		List<TaskTile> out = new ArrayList<>();
		for (int r = -MAX_TIER; r <= MAX_TIER; r++)
		{
			for (int c = -MAX_TIER; c <= MAX_TIER; c++)
			{
				int tier = Math.max(Math.abs(r), Math.abs(c));
				if (tier > MAX_TIER) continue;
				String id = TaskTile.idFor(r, c);
				int points = pointsForTier(tier);
				if (r == 0 && c == 0)
				{
					out.add(new TaskTile(id, 0, "Free", 0, r, c, null));
					continue;
				}
				Integer ai = positionToIndex.get(r + "," + c);
				TaskDefinition def = (ai != null && ai < assigned.size()) ? assigned.get(ai) : null;
				String displayName = def != null && def.getDisplayName() != null ? def.getDisplayName() : ("Task " + id);
				String taskType = def != null ? def.getTaskType() : null;
				out.add(new TaskTile(id, tier, displayName, points, r, c, taskType));
			}
		}
		return out;
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
			default: return tier;
		}
	}

	public TaskState getState(String areaId, String taskId, List<TaskTile> grid)
	{
		Set<String> claimed = loadSet(areaId, SUFFIX_CLAIMED);
		Set<String> completed = loadSet(areaId, SUFFIX_COMPLETED);

		boolean isCenter = "0,0".equals(taskId);
		if (isCenter)
		{
			if (claimed.contains(taskId)) return TaskState.CLAIMED;
			return TaskState.COMPLETED_UNCLAIMED; // "Free" tile: ready to claim
		}

		if (claimed.contains(taskId)) return TaskState.CLAIMED;
		if (completed.contains(taskId)) return TaskState.COMPLETED_UNCLAIMED;

		// Revealed if any neighbor is claimed (or center counts as "claimed" for revealing tier 1)
		boolean revealed = isRevealed(taskId, claimed, grid);
		return revealed ? TaskState.REVEALED : TaskState.LOCKED;
	}

	private boolean isRevealed(String taskId, Set<String> claimed, List<TaskTile> grid)
	{
		TaskTile tile = grid.stream().filter(t -> t.getId().equals(taskId)).findFirst().orElse(null);
		if (tile == null) return false;
		// Neighbors: cardinal only
		int[][] deltas = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
		for (int[] d : deltas)
		{
			int nr = tile.getRow() + d[0];
			int nc = tile.getCol() + d[1];
			String neighborId = TaskTile.idFor(nr, nc);
			if (claimed.contains(neighborId)) return true;
		}
		return false;
	}

	public void setCompleted(String areaId, String taskId)
	{
		Set<String> completed = loadSet(areaId, SUFFIX_COMPLETED);
		completed.add(taskId);
		saveSet(areaId, SUFFIX_COMPLETED, completed);
	}

	public void setClaimed(String areaId, String taskId)
	{
		Set<String> claimed = loadSet(areaId, SUFFIX_CLAIMED);
		if (claimed.contains(taskId)) return;
		claimed.add(taskId);
		saveSet(areaId, SUFFIX_CLAIMED, claimed);

		// Award points (tier 0 = 0)
		List<TaskTile> grid = getGridForArea(areaId);
		int points = grid.stream()
			.filter(t -> t.getId().equals(taskId))
			.mapToInt(TaskTile::getPoints)
			.findFirst()
			.orElse(0);
		if (points > 0)
		{
			pointsService.addEarned(points);
			areaCompletionService.addEarnedInArea(areaId, points);
			log.debug("Task {} claimed in area {}, +{} points", taskId, areaId, points);
		}
	}

	private Set<String> loadSet(String areaId, String suffix)
	{
		String key = KEY_PREFIX + areaId + suffix;
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

	private void saveSet(String areaId, String suffix, Set<String> set)
	{
		String key = KEY_PREFIX + areaId + suffix;
		String value = String.join(ID_SEP, set);
		configManager.setConfiguration(STATE_GROUP, key, value);
	}
}

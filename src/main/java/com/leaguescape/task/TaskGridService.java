package com.leaguescape.task;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
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
import java.lang.reflect.Type;
import java.util.List;
import java.util.Random;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.config.ConfigManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Generates task grid per area from tasks.json, persists completed/claimed state, and awards points on claim.
 * Tasks are randomized per area (seeded by areaId) with easy tasks near the center and harder tasks toward the outer edge.
 */
@Singleton
public class TaskGridService
{
	private static final Logger log = LoggerFactory.getLogger(TaskGridService.class);
	private static final String STATE_GROUP = "leaguescapeState";
	private static final String KEY_PREFIX = "taskProgress_";
	private static final String SUFFIX_CLAIMED = "_claimed";
	private static final String SUFFIX_COMPLETED = "_completed";
	private static final String ID_SEP = "|";

	private static final int MAX_TIER = 5;
	private static final String TASKS_RESOURCE = "/tasks.json";
	private static final String KEY_TASKS_OVERRIDE = "tasksJsonOverride";
	private static final String KEY_CUSTOM_TASKS = "customTasksJson";

	private static final JsonDeserializer<TaskDefinition> TASK_DESERIALIZER = new JsonDeserializer<TaskDefinition>()
	{
		@Override
		public TaskDefinition deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException
		{
			JsonObject obj = json.getAsJsonObject();
			TaskDefinition def = new TaskDefinition();
			if (obj.has("displayName")) def.setDisplayName(obj.get("displayName").getAsString());
			if (obj.has("taskType")) def.setTaskType(obj.get("taskType").getAsString());
			if (obj.has("difficulty")) def.setDifficulty(obj.get("difficulty").getAsInt());
			if (obj.has("area"))
			{
				JsonElement areaEl = obj.get("area");
				if (areaEl.isJsonArray())
				{
					List<String> list = new ArrayList<>();
					for (JsonElement e : areaEl.getAsJsonArray())
						list.add(e.getAsString());
					def.setAreas(list);
				}
				else if (areaEl.isJsonPrimitive())
					def.setArea(areaEl.getAsString());
			}
			if (obj.has("f2p")) def.setF2p(obj.get("f2p").getAsBoolean());
			return def;
		}
	};

	private static final JsonSerializer<TaskDefinition> TASK_SERIALIZER = (src, typeOfSrc, context) ->
	{
		JsonObject obj = new JsonObject();
		if (src.getDisplayName() != null) obj.addProperty("displayName", src.getDisplayName());
		if (src.getTaskType() != null) obj.addProperty("taskType", src.getTaskType());
		obj.addProperty("difficulty", src.getDifficulty());
		List<String> areaIds = src.getRequiredAreaIds();
		if (!areaIds.isEmpty())
		{
			if (areaIds.size() == 1)
				obj.addProperty("area", areaIds.get(0));
			else
			{
				JsonArray arr = new JsonArray();
				for (String id : areaIds) arr.add(id);
				obj.add("area", arr);
			}
		}
		if (src.getF2p() != null) obj.addProperty("f2p", src.getF2p());
		return obj;
	};

	private static final Gson GSON = new GsonBuilder()
		.registerTypeAdapter(TaskDefinition.class, TASK_DESERIALIZER)
		.create();

	private static final Gson GSON_SERIALIZE = new GsonBuilder()
		.registerTypeAdapter(TaskDefinition.class, TASK_SERIALIZER)
		.create();

	private final ConfigManager configManager;
	private final LeagueScapeConfig config;
	private final PointsService pointsService;
	private final AreaCompletionService areaCompletionService;

	private volatile TasksData tasksData;

	/** Call after changing tasks override or custom tasks in config so next get uses updated data. */
	public void invalidateTasksCache()
	{
		tasksData = null;
	}

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
							tasksData = GSON.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), TasksData.class);
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
					tasksData = GSON.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), TasksData.class);
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

	/** Base tasks: from config override if set, else from file or built-in resource. */
	private TasksData loadBaseTasksData()
	{
		String override = configManager.getConfiguration(STATE_GROUP, KEY_TASKS_OVERRIDE);
		if (override != null && !override.trim().isEmpty())
		{
			try
			{
				TasksData parsed = GSON.fromJson(override.trim(), TasksData.class);
				if (parsed != null && parsed.getDefaultTasks() != null)
					return parsed;
			}
			catch (Exception e)
			{
				log.warn("LeagueScape tasks override invalid: {}", e.getMessage());
			}
		}
		return loadTasksData();
	}

	private List<TaskDefinition> loadCustomTasksFromConfig()
	{
		String raw = configManager.getConfiguration(STATE_GROUP, KEY_CUSTOM_TASKS);
		if (raw == null || raw.trim().isEmpty()) return new ArrayList<>();
		try
		{
			com.google.gson.reflect.TypeToken<List<TaskDefinition>> typeToken = new com.google.gson.reflect.TypeToken<List<TaskDefinition>>(){};
			List<TaskDefinition> list = GSON.fromJson(raw.trim(), typeToken.getType());
			return list != null ? list : new ArrayList<>();
		}
		catch (Exception e)
		{
			log.warn("LeagueScape custom tasks invalid: {}", e.getMessage());
			return new ArrayList<>();
		}
	}

	private void saveCustomTasksToConfig(List<TaskDefinition> list)
	{
		String json = GSON_SERIALIZE.toJson(list != null ? list : new ArrayList<>());
		configManager.setConfiguration(STATE_GROUP, KEY_CUSTOM_TASKS, json);
		invalidateTasksCache();
	}

	/** Effective task set: base defaultTasks + custom tasks, and base areas. Used for grid and export. */
	private TasksData getEffectiveTasksData()
	{
		TasksData base = loadBaseTasksData();
		List<TaskDefinition> custom = loadCustomTasksFromConfig();
		TasksData result = new TasksData();
		List<TaskDefinition> combined = new ArrayList<>(base.getDefaultTasks() != null ? base.getDefaultTasks() : new ArrayList<>());
		combined.addAll(custom);
		result.setDefaultTasks(combined);
		result.setAreas(base.getAreas() != null ? new java.util.HashMap<>(base.getAreas()) : new java.util.HashMap<>());
		return result;
	}

	// --- Task config API (import, export, custom tasks) ---

	/** Whether the effective task set is currently overridden by imported JSON. */
	public boolean hasTasksOverride()
	{
		String override = configManager.getConfiguration(STATE_GROUP, KEY_TASKS_OVERRIDE);
		return override != null && !override.trim().isEmpty();
	}

	/** Set tasks from JSON (replaces file/resource until cleared). Expects TasksData format with defaultTasks (and optional areas). */
	public void setTasksOverride(String tasksJson) throws IllegalArgumentException
	{
		if (tasksJson == null || tasksJson.trim().isEmpty())
		{
			clearTasksOverride();
			return;
		}
		TasksData parsed = GSON.fromJson(tasksJson.trim(), TasksData.class);
		if (parsed == null || parsed.getDefaultTasks() == null)
			throw new IllegalArgumentException("Invalid tasks JSON: need defaultTasks array");
		configManager.setConfiguration(STATE_GROUP, KEY_TASKS_OVERRIDE, tasksJson.trim());
		invalidateTasksCache();
	}

	/** Clear imported override so tasks load from file or built-in again. */
	public void clearTasksOverride()
	{
		configManager.unsetConfiguration(STATE_GROUP, KEY_TASKS_OVERRIDE);
		invalidateTasksCache();
	}

	/** Custom (in-plugin) tasks only. */
	public List<TaskDefinition> getCustomTasks()
	{
		return new ArrayList<>(loadCustomTasksFromConfig());
	}

	public void addCustomTask(TaskDefinition task)
	{
		List<TaskDefinition> list = loadCustomTasksFromConfig();
		list.add(task != null ? task : new TaskDefinition());
		saveCustomTasksToConfig(list);
	}

	public void updateCustomTask(int index, TaskDefinition task)
	{
		List<TaskDefinition> list = loadCustomTasksFromConfig();
		if (index >= 0 && index < list.size() && task != null)
		{
			list.set(index, task);
			saveCustomTasksToConfig(list);
		}
	}

	public void removeCustomTask(int index)
	{
		List<TaskDefinition> list = loadCustomTasksFromConfig();
		if (index >= 0 && index < list.size())
		{
			list.remove(index);
			saveCustomTasksToConfig(list);
		}
	}

	/** Export effective task set (base + custom) as JSON string. */
	public String exportTasksToJson()
	{
		TasksData data = getEffectiveTasksData();
		JsonObject root = new JsonObject();
		root.addProperty("_comment", "Task properties: displayName, taskType, difficulty (1-5). Optional 'area' is a single area id or an array of area ids.");
		JsonArray defaultArr = new JsonArray();
		for (TaskDefinition t : data.getDefaultTasks())
			defaultArr.add(GSON_SERIALIZE.toJsonTree(t));
		root.add("defaultTasks", defaultArr);
		root.add("areas", new JsonObject());
		return GSON_SERIALIZE.toJson(root);
	}

	/** Get task list for an area (area override or default filtered by task.area and task mode). */
	private List<TaskDefinition> getTasksForArea(String areaId)
	{
		TasksData data = getEffectiveTasksData();
		List<TaskDefinition> list;
		if (data.getAreas() != null && data.getAreas().containsKey(areaId))
		{
			TasksData.AreaTasks at = data.getAreas().get(areaId);
			if (at != null && at.getTasks() != null && !at.getTasks().isEmpty())
				list = filterTasksByArea(at.getTasks(), areaId);
			else
				list = filterTasksByArea(data.getDefaultTasks() != null ? data.getDefaultTasks() : new ArrayList<>(), areaId);
		}
		else
			list = filterTasksByArea(data.getDefaultTasks() != null ? data.getDefaultTasks() : new ArrayList<>(), areaId);
		// Free to Play mode: only tasks with f2p == true
		if (config.taskMode() == LeagueScapeConfig.TaskMode.FREE_TO_PLAY)
			list = list.stream()
				.filter(t -> Boolean.TRUE.equals(t.getF2p()))
				.collect(java.util.stream.Collectors.toList());
		return list;
	}

	/** Keep only tasks that apply to this area (task has no area restriction, or areaId is in task's required area list). */
	private List<TaskDefinition> filterTasksByArea(List<TaskDefinition> tasks, String areaId)
	{
		if (tasks == null) return new ArrayList<>();
		return tasks.stream()
			.filter(t -> {
				List<String> required = t.getRequiredAreaIds();
				return required.isEmpty() || required.contains(areaId);
			})
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
					out.add(new TaskTile(id, 0, "Free", 0, r, c, null, null));
					continue;
				}
				Integer ai = positionToIndex.get(r + "," + c);
				TaskDefinition def = (ai != null && ai < assigned.size()) ? assigned.get(ai) : null;
				String displayName = def != null && def.getDisplayName() != null ? def.getDisplayName() : ("Task " + id);
				String taskType = def != null ? def.getTaskType() : null;
				List<String> requiredAreaIds = (def != null && !def.getRequiredAreaIds().isEmpty())
					? new ArrayList<>(def.getRequiredAreaIds()) : null;
				out.add(new TaskTile(id, tier, displayName, points, r, c, taskType, requiredAreaIds));
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

	/**
	 * Returns all task tiles in the given area that are currently revealed (neighbor of a claimed tile, not yet completed or claimed).
	 * Used by task completion listeners to only consider revealed tasks for auto-completion.
	 */
	public List<TaskTile> getRevealedTiles(String areaId)
	{
		List<TaskTile> grid = getGridForArea(areaId);
		List<TaskTile> out = new ArrayList<>();
		for (TaskTile tile : grid)
		{
			if (tile.getTier() == 0) continue; // center "Free" tile
			if (getState(areaId, tile.getId(), grid) == TaskState.REVEALED)
				out.add(tile);
		}
		return out;
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

	/**
	 * True if every task in the area's grid (excluding the center "Free" tile) has been completed.
	 * Used in point-buy mode to show an area as complete only when all its tasks are done.
	 */
	public boolean isAreaFullyCompleted(String areaId)
	{
		List<TaskTile> grid = getGridForArea(areaId);
		Set<String> completed = loadSet(areaId, SUFFIX_COMPLETED);
		for (TaskTile tile : grid)
		{
			if (tile.getTier() == 0) continue; // center "Free" tile
			if (!completed.contains(tile.getId())) return false;
		}
		return true;
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

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
import com.leaguescape.area.AreaGraphService;
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
import java.util.HashMap;
import java.util.HashSet;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;
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
	/** Minimum number of tasks in the pool for each area's task panel (pad by repeating if needed). */
	private static final int MIN_TASKS_PER_AREA = 100;
	/** Maximum number of tasks in the pool for each area's task panel (cap after prioritizing area-specific then filler). */
	private static final int MAX_TASKS_PER_AREA = 400;
	private static final String TASKS_RESOURCE = "/tasks.json";
	private static final String KEY_TASKS_OVERRIDE = "tasksJsonOverride";
	private static final String KEY_CUSTOM_TASKS = "customTasksJson";
	private static final String KEY_GRID_RESET_COUNTER = "taskGridResetCounter";

	/** Custom Gson deserializer for TaskDefinition: reads displayName, taskType, difficulty, area (string or array), f2p. */
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
			// area: comma-separated string (e.g. "lumbridge, draynor, varrock") or legacy JSON array
			if (obj.has("area"))
			{
				JsonElement areaEl = obj.get("area");
				List<String> list = new ArrayList<>();
				if (areaEl.isJsonArray())
				{
					for (JsonElement e : areaEl.getAsJsonArray())
						list.add(e.getAsString().trim());
				}
				else if (areaEl.isJsonPrimitive())
				{
					for (String part : areaEl.getAsString().split(","))
					{
						String id = part.trim();
						if (!id.isEmpty()) list.add(id);
					}
				}
				if (list.isEmpty())
					{ /* leave area/areas null */ }
				else if (list.size() == 1)
					def.setArea(list.get(0));
				else
					def.setAreas(list);
			}
			if (obj.has("f2p")) def.setF2p(obj.get("f2p").getAsBoolean());
			if (obj.has("requirements")) def.setRequirements(obj.get("requirements").getAsString());
			if (obj.has("areaRequirement")) def.setAreaRequirement(obj.get("areaRequirement").getAsString());
			if (obj.has("onceOnly")) def.setOnceOnly(obj.get("onceOnly").getAsBoolean());
			return def;
		}
	};

	/** Custom Gson serializer for TaskDefinition: writes displayName, taskType, difficulty, area (single or array), f2p, requirements. */
	private static final JsonSerializer<TaskDefinition> TASK_SERIALIZER = (src, typeOfSrc, context) ->
	{
		JsonObject obj = new JsonObject();
		if (src.getDisplayName() != null) obj.addProperty("displayName", src.getDisplayName());
		if (src.getTaskType() != null) obj.addProperty("taskType", src.getTaskType());
		obj.addProperty("difficulty", src.getDifficulty());
		List<String> areaIds = src.getRequiredAreaIds();
		if (!areaIds.isEmpty())
			obj.addProperty("area", String.join(", ", areaIds));
		if (src.getF2p() != null) obj.addProperty("f2p", src.getF2p());
		if (src.getRequirements() != null) obj.addProperty("requirements", src.getRequirements());
		if (src.getAreaRequirement() != null && !src.getAreaRequirement().isEmpty()) obj.addProperty("areaRequirement", src.getAreaRequirement());
		if (src.getOnceOnly() != null && src.getOnceOnly()) obj.addProperty("onceOnly", true);
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
	private final AreaGraphService areaGraphService;

	private volatile TasksData tasksData;

	/** Cache: task key -> area id for onceOnly tasks. Cleared when tasks cache is invalidated. */
	private volatile Map<String, String> onceOnlyAssignmentCache;

	/**
	 * Clears the cached tasks data. Call after changing the tasks file path, override, or custom
	 * tasks in config so the next {@link #getGridForArea(String)} or related call uses updated data.
	 */
	public void invalidateTasksCache()
	{
		tasksData = null;
		onceOnlyAssignmentCache = null;
	}

	@Inject
	public TaskGridService(ConfigManager configManager, LeagueScapeConfig config,
		PointsService pointsService, AreaCompletionService areaCompletionService,
		AreaGraphService areaGraphService)
	{
		this.configManager = configManager;
		this.config = config;
		this.pointsService = pointsService;
		this.areaCompletionService = areaCompletionService;
		this.areaGraphService = areaGraphService;
	}

	/**
	 * Loads tasks from config file path (if set and valid), else from built-in /tasks.json.
	 * Result is cached in {@link #tasksData}. Double-checked locking used for thread-safe lazy init.
	 *
	 * @return never null; defaultTasks may be empty if loading failed
	 */
	private TasksData loadTasksData()
	{
		if (tasksData != null) return tasksData;
		synchronized (this)
		{
			if (tasksData != null) return tasksData;
			// 1) Try config file path first
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
			// 2) Fall back to built-in resource
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
			// 3) Empty fallback so callers never get null
			tasksData = new TasksData();
			tasksData.setDefaultTasks(new ArrayList<>());
			return tasksData;
		}
	}

	/**
	 * Base task set: from imported JSON override (KEY_TASKS_OVERRIDE) if present, otherwise
	 * from {@link #loadTasksData()} (file path or built-in). Used by getEffectiveTasksData.
	 */
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

	/** Loads the user's custom (in-plugin added) tasks from config. Returns empty list if unset or invalid. */
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

	/** Saves custom tasks to config as JSON and invalidates the tasks cache. */
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

	/** Append multiple tasks to the custom task list and persist once. */
	public void addCustomTasks(List<TaskDefinition> tasks)
	{
		if (tasks == null || tasks.isEmpty()) return;
		List<TaskDefinition> list = loadCustomTasksFromConfig();
		for (TaskDefinition t : tasks)
			if (t != null) list.add(t);
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

	/** Export a list of tasks as JSON (defaultTasks array + areas empty). For Task Creator Helper export. */
	public String exportTaskListAsJson(List<TaskDefinition> tasks)
	{
		JsonObject root = new JsonObject();
		root.addProperty("_comment", "Task properties: displayName, taskType, difficulty (1-5). Optional area, requirements.");
		JsonArray defaultArr = new JsonArray();
		for (TaskDefinition t : (tasks != null ? tasks : Collections.<TaskDefinition>emptyList()))
			defaultArr.add(GSON_SERIALIZE.toJsonTree(t));
		root.add("defaultTasks", defaultArr);
		root.add("areas", new JsonObject());
		return GSON_SERIALIZE.toJson(root);
	}

	/**
	 * Get task list for an area: area override or default, filtered by area and task mode, then
	 * prioritized (area-specific first, filler tasks second), capped at MAX_TASKS_PER_AREA, and
	 * padded to MIN_TASKS_PER_AREA by repeating if needed.
	 */
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
		// onceOnly: include only if this area is the one assigned to this task
		Map<String, String> onceOnlyMap = getOnceOnlyAssignments();
		list = list.stream()
			.filter(t -> {
				if (!Boolean.TRUE.equals(t.getOnceOnly())) return true;
				String assigned = onceOnlyMap.get(taskKey(t));
				return assigned != null && assigned.equals(areaId);
			})
			.collect(Collectors.toList());
		// Free to Play mode: only tasks with f2p == true
		if (config.taskMode() == LeagueScapeConfig.TaskMode.FREE_TO_PLAY)
			list = list.stream()
				.filter(t -> Boolean.TRUE.equals(t.getF2p()))
				.collect(Collectors.toList());
		return prioritizeAndCapTasksForArea(list, areaId);
	}

	/**
	 * Prioritizes tasks for an area: tasks with "area" (or "areas") containing this areaId come first,
	 * then filler tasks (no area restriction). No task may appear more than once in the same area
	 * (deduplicated by display name). Caps at MAX_TASKS_PER_AREA; if fewer than MIN_TASKS_PER_AREA
	 * unique tasks exist, the pool is left smaller (no repeating to pad).
	 */
	private List<TaskDefinition> prioritizeAndCapTasksForArea(List<TaskDefinition> tasks, String areaId)
	{
		if (tasks == null || tasks.isEmpty())
			return new ArrayList<>();

		// Area-specific: task's requiredAreaIds is non-empty and contains this area
		List<TaskDefinition> areaSpecific = new ArrayList<>();
		List<TaskDefinition> filler = new ArrayList<>();
		for (TaskDefinition t : tasks)
		{
			List<String> required = t.getRequiredAreaIds();
			if (!required.isEmpty() && required.contains(areaId))
				areaSpecific.add(t);
			else if (required.isEmpty())
				filler.add(t);
		}

		// Build combined with no duplicates within this area (same displayName = same task)
		Set<String> seenInArea = new HashSet<>();
		List<TaskDefinition> combined = new ArrayList<>();
		for (TaskDefinition t : areaSpecific)
		{
			String key = taskKey(t);
			if (seenInArea.add(key))
				combined.add(t);
		}
		for (TaskDefinition t : filler)
		{
			String key = taskKey(t);
			if (seenInArea.add(key))
				combined.add(t);
		}

		if (combined.size() > MAX_TASKS_PER_AREA)
			combined = new ArrayList<>(combined.subList(0, MAX_TASKS_PER_AREA));

		return combined;
	}

	/** Normalized key for deduplication: same task (e.g. "Defeat a Guard") has the same key within an area. */
	private static String taskKey(TaskDefinition t)
	{
		String name = t.getDisplayName();
		return name != null ? name.trim().toLowerCase() : "";
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
			.collect(Collectors.toList());
	}

	/** Builds map: task key -> area id for each onceOnly task (deterministic: first eligible area in sorted order). */
	private Map<String, String> getOnceOnlyAssignments()
	{
		Map<String, String> cache = onceOnlyAssignmentCache;
		if (cache != null) return cache;
		TasksData data = getEffectiveTasksData();
		List<String> sortedAreaIds = areaGraphService.getAreas().stream()
			.map(a -> a.getId())
			.sorted()
			.collect(Collectors.toList());
		List<TaskDefinition> allTasks = new ArrayList<>(data.getDefaultTasks() != null ? data.getDefaultTasks() : Collections.emptyList());
		Map<String, String> map = new HashMap<>();
		for (TaskDefinition t : allTasks)
		{
			if (!Boolean.TRUE.equals(t.getOnceOnly())) continue;
			List<String> required = t.getRequiredAreaIds();
			String assign = null;
			for (String aid : sortedAreaIds)
			{
				if (required.isEmpty() || required.contains(aid))
				{
					assign = aid;
					break;
				}
			}
			if (assign != null)
				map.put(taskKey(t), assign);
		}
		onceOnlyAssignmentCache = map;
		return map;
	}

	/**
	 * Generate the full task grid for an area. Uses {@link #computeEffectiveMaxTier(String)} so the grid
	 * has enough tiers (up to {@value #MAX_GRID_TIERS}) to meet the area's point target and avoid soft lock.
	 * Center (0,0) is tier 0 "Free". Tasks are randomized per area (seeded by areaId):
	 * difficulty 1 near center, difficulty 5 at the outer edge.
	 */
	public List<TaskTile> getGridForArea(String areaId)
	{
		List<TaskDefinition> taskDefs = getTasksForArea(areaId);
		long seed = (long) areaId.hashCode() + getGridResetCounter();
		Random rng = new Random(seed);

		int effectiveMaxTier = computeEffectiveMaxTier(areaId);
		// Partition tasks by difficulty (1-5); clamp invalid to 1 (needed before building positions for overfill)
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

		// Area-specific task count per tier (tier t uses difficulty min(t,5)) for overfill sizing
		int[] areaCountByTier = new int[effectiveMaxTier + 1];
		for (int t = 1; t <= effectiveMaxTier; t++)
		{
			int d = Math.min(t, MAX_TIER);
			areaCountByTier[t] = (int) byDifficulty.get(d).stream()
				.filter(def -> def.getRequiredAreaIds() != null && def.getRequiredAreaIds().contains(areaId))
				.count();
		}

		// Build (row, col) positions grouped by tier; allow overfill so all area tasks fit (grid may extend on sides)
		List<List<int[]>> positionsByTier = new ArrayList<>();
		for (int t = 0; t <= effectiveMaxTier; t++)
			positionsByTier.add(new ArrayList<>());
		for (int r = -effectiveMaxTier; r <= effectiveMaxTier; r++)
		{
			for (int c = -effectiveMaxTier; c <= effectiveMaxTier; c++)
			{
				int tier = Math.max(Math.abs(r), Math.abs(c));
				if (tier > effectiveMaxTier) continue;
				if (r == 0 && c == 0) continue;
				positionsByTier.get(tier).add(new int[]{r, c});
			}
		}
		// Overfill: ensure each tier has at least enough slots for its area-specific tasks
		int overfillIndex = 0;
		for (int t = 1; t <= effectiveMaxTier; t++)
		{
			int needSlots = Math.max(8 * t, areaCountByTier[t]);
			while (positionsByTier.get(t).size() < needSlots)
				positionsByTier.get(t).add(nextOverfillPosition(effectiveMaxTier, overfillIndex++));
		}

		// Assign tasks to positions by tier: tier t uses difficulty min(t,5). Area-specific first, then filler.
		// Each task appears at most once (no duplicates); extra slots get a placeholder.
		List<TaskDefinition> assigned = new ArrayList<>(4 * effectiveMaxTier * (effectiveMaxTier + 1));
		for (int tier = 1; tier <= effectiveMaxTier; tier++)
		{
			List<int[]> positions = positionsByTier.get(tier);
			int difficultyIndex = Math.min(tier, MAX_TIER);
			List<TaskDefinition> pool = byDifficulty.get(difficultyIndex);
			if (pool.isEmpty())
				pool = taskDefs.isEmpty() ? new ArrayList<>() : byDifficulty.get(1);
			// Deduplicate pool by displayName so same task never appears twice
			Set<String> seenKey = new HashSet<>();
			List<TaskDefinition> poolDeduped = new ArrayList<>();
			for (TaskDefinition t : pool)
			{
				if (seenKey.add(taskKey(t)))
					poolDeduped.add(t);
			}
			pool = poolDeduped;
			if (pool.isEmpty())
			{
				TaskDefinition fallback = new TaskDefinition();
				fallback.setDisplayName("Task " + tier);
				fallback.setTaskType(null);
				fallback.setDifficulty(tier);
				pool = Collections.singletonList(fallback);
			}
			List<TaskDefinition> onceOnly = pool.stream().filter(t -> Boolean.TRUE.equals(t.getOnceOnly())).collect(Collectors.toList());
			List<TaskDefinition> rest = pool.stream().filter(t -> !Boolean.TRUE.equals(t.getOnceOnly())).collect(Collectors.toList());
			if (rest.isEmpty() && !onceOnly.isEmpty()) rest = new ArrayList<>(onceOnly);
			// Preserve area prioritization: partition rest into area-specific (for this area) and filler, shuffle each, then area-specific first
			List<TaskDefinition> areaSpecificRest = rest.stream()
				.filter(t -> {
					List<String> req = t.getRequiredAreaIds();
					return !req.isEmpty() && req.contains(areaId);
				})
				.collect(Collectors.toList());
			List<TaskDefinition> fillerRest = rest.stream()
				.filter(t -> t.getRequiredAreaIds().isEmpty())
				.collect(Collectors.toList());
			Collections.shuffle(onceOnly, rng);
			Collections.shuffle(areaSpecificRest, rng);
			Collections.shuffle(fillerRest, rng);
			List<TaskDefinition> restOrdered = new ArrayList<>(areaSpecificRest.size() + fillerRest.size());
			restOrdered.addAll(areaSpecificRest);
			restOrdered.addAll(fillerRest);
			if (restOrdered.isEmpty() && !rest.isEmpty()) restOrdered.addAll(rest);
			int n = positions.size();
			TaskDefinition tierPlaceholder = null;
			for (int i = 0; i < n; i++)
			{
				if (i < onceOnly.size())
				{
					assigned.add(onceOnly.get(i));
				}
				else
				{
					int restIndex = i - onceOnly.size();
					if (restIndex < restOrdered.size())
						assigned.add(restOrdered.get(restIndex));
					else
					{
						if (tierPlaceholder == null)
						{
							tierPlaceholder = new TaskDefinition();
							tierPlaceholder.setDisplayName("—");
							tierPlaceholder.setTaskType(null);
							tierPlaceholder.setDifficulty(tier);
						}
						assigned.add(tierPlaceholder);
					}
				}
			}
		}

		// Map (r,c) -> index into assigned (same order: tier 1 cells, then tier 2, ...)
		java.util.Map<String, Integer> positionToIndex = new java.util.HashMap<>();
		int idx = 0;
		for (int tier = 1; tier <= effectiveMaxTier; tier++)
			for (int[] rc : positionsByTier.get(tier))
				positionToIndex.put(rc[0] + "," + rc[1], idx++);

		// Build TaskTile list: center then all positions by tier (includes overfill cells)
		List<TaskTile> out = new ArrayList<>();
		out.add(new TaskTile(TaskTile.idFor(0, 0), 0, "Free", 0, 0, 0, null, null, true));
		for (int tier = 1; tier <= effectiveMaxTier; tier++)
		{
			for (int[] rc : positionsByTier.get(tier))
			{
				int r = rc[0], c = rc[1];
				String id = TaskTile.idFor(r, c);
				int points = pointsForTier(tier);
				Integer ai = positionToIndex.get(r + "," + c);
				TaskDefinition def = (ai != null && ai < assigned.size()) ? assigned.get(ai) : null;
				String displayName = def != null && def.getDisplayName() != null ? def.getDisplayName() : ("Task " + id);
				String taskType = def != null ? def.getTaskType() : null;
				List<String> requiredAreaIds = (def != null && !def.getRequiredAreaIds().isEmpty())
					? new ArrayList<>(def.getRequiredAreaIds()) : null;
				boolean requireAllAreas = def == null || !def.isAreaRequirementAny();
				out.add(new TaskTile(id, tier, displayName, points, r, c, taskType, requiredAreaIds, requireAllAreas));
			}
		}
		return out;
	}

	/** Returns points awarded when a task in the given tier is claimed (from config tier 1–5 points). Tier 6+ uses tier 5 value. */
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

	/** Total points on grid if we have tiers 1..maxTier (each tier t has 8*t slots). */
	private int totalPointsForTiers(int maxTier)
	{
		int total = 0;
		for (int t = 1; t <= maxTier; t++)
			total += 8 * t * pointsForTier(t);
		return total;
	}

	/** Buffer multiplier so the board has enough points to avoid soft lock (e.g. 1.2 = 20% extra). */
	private static final double TARGET_POINTS_BUFFER = 1.2;
	/** Maximum grid size (tiers) to avoid huge boards; 5 is default. */
	private static final int MAX_GRID_TIERS = 12;

	/**
	 * Computes the minimum number of tiers so the grid offers enough points to avoid soft lock:
	 * - Point buy: total points >= (most expensive unlockable neighbor cost) * buffer.
	 * - Points to complete: total points >= (area's completion threshold) * buffer.
	 */
	private int computeEffectiveMaxTier(String areaId)
	{
		int target;
		if (config.unlockMode() == LeagueScapeConfig.UnlockMode.POINT_BUY)
		{
			Set<String> unlocked = areaGraphService.getUnlockedAreaIds();
			List<com.leaguescape.data.Area> neighbors = areaGraphService.getUnlockableNeighbors(unlocked);
			int maxCost = 0;
			for (com.leaguescape.data.Area a : neighbors)
			{
				if (a == null) continue;
				int cost = areaGraphService.getCost(a.getId());
				if (cost > maxCost) maxCost = cost;
			}
			target = (int) Math.ceil(maxCost * TARGET_POINTS_BUFFER);
		}
		else
		{
			int toComplete = areaGraphService.getPointsToComplete(areaId);
			target = (int) Math.ceil(toComplete * TARGET_POINTS_BUFFER);
		}
		if (target <= 0)
			return MAX_TIER;
		for (int T = MAX_TIER; T <= MAX_GRID_TIERS; T++)
		{
			if (totalPointsForTiers(T) >= target)
				return T;
		}
		return MAX_GRID_TIERS;
	}

	/** Columns per overfill row so overfill positions are deterministic and compact. */
	private static final int OVERFILL_COLS = 50;

	/**
	 * Returns the (r,c) for the {@code index}-th overfill position, placed just beyond the base grid (row baseMaxTier+1 and beyond).
	 * Used so tiers can have more slots than 8*t when there are many area-specific tasks.
	 */
	private int[] nextOverfillPosition(int baseMaxTier, int index)
	{
		int row = baseMaxTier + 1 + (index / OVERFILL_COLS);
		int col = (index % OVERFILL_COLS) - (OVERFILL_COLS / 2);
		return new int[]{ row, col };
	}

	/**
	 * Returns the current state of a task tile in an area (LOCKED, REVEALED, COMPLETED_UNCLAIMED, CLAIMED).
	 * Center tile "0,0" is treated as always revealed and completes to COMPLETED_UNCLAIMED until claimed.
	 *
	 * @param areaId  area ID
	 * @param taskId  tile ID (e.g. "0,0", "1,0")
	 * @param grid    full grid for this area (used to resolve neighbors for reveal check)
	 */
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

	/**
	 * True if this task tile is revealed (at least one cardinal neighbor is claimed, or center counts for tier 1).
	 * Used by getState to distinguish LOCKED vs REVEALED.
	 */
	private boolean isRevealed(String taskId, Set<String> claimed, List<TaskTile> grid)
	{
		TaskTile tile = grid.stream().filter(t -> t.getId().equals(taskId)).findFirst().orElse(null);
		if (tile == null) return false;
		// Cardinal neighbors only: (r±1,c) and (r,c±1)
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

	/**
	 * Marks a task as completed (e.g. by auto-completion logic). Does not award points; that happens
	 * when the player clicks Claim. Persists to config immediately.
	 */
	public void setCompleted(String areaId, String taskId)
	{
		Set<String> completed = loadSet(areaId, SUFFIX_COMPLETED);
		completed.add(taskId);
		saveSet(areaId, SUFFIX_COMPLETED, completed);
	}

	/**
	 * Marks a task as claimed: adds to claimed set, persists, and awards tier points to
	 * PointsService and AreaCompletionService. Idempotent if already claimed.
	 */
	public void setClaimed(String areaId, String taskId)
	{
		Set<String> claimed = loadSet(areaId, SUFFIX_CLAIMED);
		if (claimed.contains(taskId)) return;
		claimed.add(taskId);
		saveSet(areaId, SUFFIX_CLAIMED, claimed);

		// Award points from the tile (user-configured tier points). addEarnedInArea updates both per-area and global total.
		List<TaskTile> grid = getGridForArea(areaId);
		int points = grid.stream()
			.filter(t -> t.getId().equals(taskId))
			.mapToInt(TaskTile::getPoints)
			.findFirst()
			.orElse(0);
		if (points > 0)
		{
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

	/** Loads a set of task IDs from config (key = taskProgress_&lt;areaId&gt;&lt;suffix&gt;, value = ID_SEP-separated). */
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

	/** Persists a set of task IDs to config (same key format as loadSet). */
	private void saveSet(String areaId, String suffix, Set<String> set)
	{
		String key = KEY_PREFIX + areaId + suffix;
		String value = String.join(ID_SEP, set);
		configManager.setConfiguration(STATE_GROUP, key, value);
	}

	/**
	 * Returns the current grid reset counter (used in random seed so reset progress gives fresh shuffle).
	 */
	public int getGridResetCounter()
	{
		String raw = configManager.getConfiguration(STATE_GROUP, KEY_GRID_RESET_COUNTER);
		if (raw == null || raw.isEmpty()) return 0;
		try
		{
			return Integer.parseInt(raw);
		}
		catch (NumberFormatException e)
		{
			return 0;
		}
	}

	/**
	 * Increments the grid reset counter so next getGridForArea produces a new random assignment per area.
	 * Does not clear task claimed/completed state; use clearAllTaskProgress for that.
	 */
	public void incrementGridResetCounter()
	{
		int next = getGridResetCounter() + 1;
		configManager.setConfiguration(STATE_GROUP, KEY_GRID_RESET_COUNTER, next);
		invalidateTasksCache();
	}

	/**
	 * Clears claimed and completed task state for the given area IDs. Used on reset progress; does not
	 * remove custom tasks or task override. Custom areas are not modified by this plugin.
	 */
	public void clearAllTaskProgress(java.util.Collection<String> areaIds)
	{
		if (areaIds == null) return;
		for (String areaId : areaIds)
		{
			configManager.unsetConfiguration(STATE_GROUP, KEY_PREFIX + areaId + SUFFIX_CLAIMED);
			configManager.unsetConfiguration(STATE_GROUP, KEY_PREFIX + areaId + SUFFIX_COMPLETED);
		}
		invalidateTasksCache();
	}
}

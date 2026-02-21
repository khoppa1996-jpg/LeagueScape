package com.leaguescape.task;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import com.google.gson.reflect.TypeToken;
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
import net.runelite.api.Client;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
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

	private static final java.lang.reflect.Type LIST_TASK_DEFINITION = new TypeToken<List<TaskDefinition>>(){}.getType();

	/** Parses JSON from the stream into TasksData. Accepts both root array {@code [ ... ]} and object {@code {"defaultTasks": [...]}. */
	private static TasksData parseTasksDataFromStream(InputStream in) throws Exception
	{
		JsonElement root = new JsonParser().parse(new InputStreamReader(in, StandardCharsets.UTF_8));
		if (root == null) return null;
		if (root.isJsonArray())
		{
			List<TaskDefinition> list = GSON.fromJson(root, LIST_TASK_DEFINITION);
			TasksData data = new TasksData();
			data.setDefaultTasks(list != null ? list : new ArrayList<>());
			return data;
		}
		return GSON.fromJson(root, TasksData.class);
	}

	private static final Gson GSON_SERIALIZE = new GsonBuilder()
		.registerTypeAdapter(TaskDefinition.class, TASK_SERIALIZER)
		.create();

	private final ConfigManager configManager;
	private final LeagueScapeConfig config;
	private final PointsService pointsService;
	private final AreaCompletionService areaCompletionService;
	private final AreaGraphService areaGraphService;
	private final Client client;

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
		AreaGraphService areaGraphService, Client client)
	{
		this.configManager = configManager;
		this.config = config;
		this.pointsService = pointsService;
		this.areaCompletionService = areaCompletionService;
		this.areaGraphService = areaGraphService;
		this.client = client;
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
							tasksData = parseTasksDataFromStream(in);
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
					tasksData = parseTasksDataFromStream(in);
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

		// Build ordered pool per difficulty (1–5): dedupe by taskKey, area-specific first then filler, shuffled. Only tasks from tasks.json (no synthetic tasks).
		List<List<TaskDefinition>> orderedPoolsByDifficulty = new ArrayList<>();
		for (int d = 0; d <= MAX_TIER; d++)
			orderedPoolsByDifficulty.add(new ArrayList<>());
		for (int d = 1; d <= MAX_TIER; d++)
		{
			List<TaskDefinition> pool = new ArrayList<>(byDifficulty.get(d));
			Set<String> seenKey = new HashSet<>();
			pool = pool.stream().filter(t -> seenKey.add(taskKey(t))).collect(Collectors.toList());
			if (pool.isEmpty() && d > 1)
				pool = new ArrayList<>(orderedPoolsByDifficulty.get(1));
			// No synthetic "Task " + d; leave pool empty if no tasks from tasks.json for this difficulty
			List<TaskDefinition> onceOnly = pool.stream().filter(t -> Boolean.TRUE.equals(t.getOnceOnly())).collect(Collectors.toList());
			List<TaskDefinition> rest = pool.stream().filter(t -> !Boolean.TRUE.equals(t.getOnceOnly())).collect(Collectors.toList());
			if (rest.isEmpty() && !onceOnly.isEmpty()) rest = new ArrayList<>(onceOnly);
			List<TaskDefinition> areaSpecific = rest.stream()
				.filter(t -> t.getRequiredAreaIds() != null && t.getRequiredAreaIds().contains(areaId))
				.collect(Collectors.toList());
			List<TaskDefinition> filler = rest.stream()
				.filter(t -> t.getRequiredAreaIds() == null || t.getRequiredAreaIds().isEmpty())
				.collect(Collectors.toList());
			Collections.shuffle(onceOnly, rng);
			Collections.shuffle(areaSpecific, rng);
			Collections.shuffle(filler, rng);
			List<TaskDefinition> ordered = new ArrayList<>(onceOnly.size() + areaSpecific.size() + filler.size());
			ordered.addAll(onceOnly);
			ordered.addAll(areaSpecific);
			ordered.addAll(filler);
			if (ordered.isEmpty() && !rest.isEmpty()) ordered.addAll(rest);
			orderedPoolsByDifficulty.set(d, ordered);
		}

		// Fallback pool: area-specific first, then no-area tasks (from tasks.json only). Used so no tile is left blank.
		List<TaskDefinition> areaFirst = taskDefs.stream()
			.filter(t -> t.getRequiredAreaIds() != null && !t.getRequiredAreaIds().isEmpty() && t.getRequiredAreaIds().contains(areaId))
			.collect(Collectors.toList());
		List<TaskDefinition> noArea = taskDefs.stream()
			.filter(t -> t.getRequiredAreaIds() == null || t.getRequiredAreaIds().isEmpty())
			.collect(Collectors.toList());
		List<TaskDefinition> fallbackPool = new ArrayList<>(areaFirst.size() + noArea.size());
		Set<String> fallbackSeen = new HashSet<>();
		for (TaskDefinition t : areaFirst)
			if (fallbackSeen.add(taskKey(t))) fallbackPool.add(t);
		for (TaskDefinition t : noArea)
			if (fallbackSeen.add(taskKey(t))) fallbackPool.add(t);
		Collections.shuffle(fallbackPool, rng);
		if (fallbackPool.isEmpty())
			fallbackPool = new ArrayList<>(taskDefs); // edge case: use any task
		final int[] fallbackIndex = { 0 };

		// Assign tasks per position: concentric layout. Only tasks from tasks.json; prioritize area then filler; never leave a tile blank.
		Set<String> usedTaskKeys = new HashSet<>();
		List<TaskDefinition> assigned = new ArrayList<>(4 * effectiveMaxTier * (effectiveMaxTier + 1));
		for (int tier = 1; tier <= effectiveMaxTier; tier++)
		{
			List<int[]> positions = positionsByTier.get(tier);
			for (int[] rc : positions)
			{
				int r = rc[0], c = rc[1];
				int difficulty = difficultyForCell(areaId, tier, r, c, effectiveMaxTier, rng);
				TaskDefinition chosen = null;
				for (int d = difficulty; d >= 1 && chosen == null; d--)
				{
					for (TaskDefinition t : orderedPoolsByDifficulty.get(d))
					{
						if (usedTaskKeys.add(taskKey(t)))
						{
							chosen = t;
							break;
						}
					}
				}
				// Fallback 1: tasks that match cell difficulty (never assign e.g. difficulty-4 to a tier-1 ring)
				if (chosen == null && !fallbackPool.isEmpty())
				{
					for (int i = 0; i < fallbackPool.size(); i++)
					{
						TaskDefinition t = fallbackPool.get((fallbackIndex[0] + i) % fallbackPool.size());
						if (t.getDifficulty() <= difficulty && usedTaskKeys.add(taskKey(t)))
						{
							chosen = t;
							fallbackIndex[0] += i + 1;
							break;
						}
					}
				}
				// Fallback 2: any remaining task (no-area tasks can be completed in almost any area)
				if (chosen == null && !fallbackPool.isEmpty())
				{
					for (int i = 0; i < fallbackPool.size(); i++)
					{
						TaskDefinition t = fallbackPool.get((fallbackIndex[0] + i) % fallbackPool.size());
						if (usedTaskKeys.add(taskKey(t)))
						{
							chosen = t;
							fallbackIndex[0] += i + 1;
							break;
						}
					}
				}
				// Placeholder only if there are no tasks at all
				if (chosen == null)
				{
					TaskDefinition placeholder = new TaskDefinition();
					placeholder.setDisplayName("—");
					placeholder.setTaskType(null);
					placeholder.setDifficulty(tier);
					chosen = placeholder;
				}
				assigned.add(chosen);
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
		out.add(new TaskTile(TaskTile.idFor(0, 0), 0, "Free", 0, 0, 0, null, null, true, null));
		for (int tier = 1; tier <= effectiveMaxTier; tier++)
		{
			for (int[] rc : positionsByTier.get(tier))
			{
				int r = rc[0], c = rc[1];
				String id = TaskTile.idFor(r, c);
				Integer ai = positionToIndex.get(r + "," + c);
				TaskDefinition def = (ai != null && ai < assigned.size()) ? assigned.get(ai) : null;
				// Points follow the task's difficulty, not the cell tier, so filler tasks keep their original point value
				int points = pointsForTier(def != null ? def.getDifficulty() : tier);
				String displayName = def != null && def.getDisplayName() != null ? def.getDisplayName() : ("Task " + id);
				String taskType = def != null ? def.getTaskType() : null;
				List<String> requiredAreaIds = (def != null && !def.getRequiredAreaIds().isEmpty())
					? new ArrayList<>(def.getRequiredAreaIds()) : null;
				boolean requireAllAreas = def == null || !def.isAreaRequirementAny();
				String requirements = (def != null && def.getRequirements() != null && !def.getRequirements().isEmpty()) ? def.getRequirements().trim() : null;
				int displayTier = displayTierForCell(areaId, tier);
				out.add(new TaskTile(id, displayTier, displayName, points, r, c, taskType, requiredAreaIds, requireAllAreas, requirements));
			}
		}
		// Enforce: no two mystery tiles may share a side or corner (at least one tile space between mystery tiles)
		separateAdjacentMysteryTiles(out, areaId);
		return out;
	}

	/** True if two grid positions (r,c) are adjacent (share a side or corner). */
	private static boolean isAdjacent(int r1, int c1, int r2, int c2)
	{
		if (r1 == r2 && c1 == c2) return false;
		return Math.abs(r1 - r2) <= 1 && Math.abs(c1 - c2) <= 1;
	}

	/**
	 * Ensures no two mystery tiles are adjacent (share side or corner). When two mystery tiles are
	 * adjacent, swaps one with a non-mystery tile from a position that has no mystery neighbors.
	 */
	private void separateAdjacentMysteryTiles(List<TaskTile> grid, String areaId)
	{
		if (grid.size() <= 1) return;
		Set<String> unlocked = areaGraphService.getUnlockedAreaIds();
		// Index 0 is center; indices 1..size-1 are task tiles with positions
		java.util.Map<String, Integer> posToIndex = new HashMap<>();
		for (int i = 1; i < grid.size(); i++)
		{
			TaskTile t = grid.get(i);
			posToIndex.put(t.getRow() + "," + t.getCol(), i);
		}
		int maxIterations = grid.size() * 2; // avoid infinite loop
		for (int iter = 0; iter < maxIterations; iter++)
		{
			Set<String> mysteryPositions = new HashSet<>();
			for (int i = 1; i < grid.size(); i++)
			{
				TaskTile t = grid.get(i);
				if (t.isMystery(unlocked, areaId))
					mysteryPositions.add(t.getRow() + "," + t.getCol());
			}
			// Find two adjacent mystery positions
			String mysteryA = null;
			String mysteryB = null;
			for (String posA : mysteryPositions)
			{
				String[] pa = posA.split(",", 2);
				int r1 = Integer.parseInt(pa[0]);
				int c1 = Integer.parseInt(pa[1]);
				for (String posB : mysteryPositions)
				{
					if (posA.compareTo(posB) >= 0) continue;
					String[] pb = posB.split(",", 2);
					int r2 = Integer.parseInt(pb[0]);
					int c2 = Integer.parseInt(pb[1]);
					if (isAdjacent(r1, c1, r2, c2))
					{
						mysteryA = posA;
						mysteryB = posB;
						break;
					}
				}
				if (mysteryA != null) break;
			}
			if (mysteryA == null) break; // no adjacent mystery pair
			// Find a non-mystery position that has no mystery neighbor (so after swap it stays valid)
			Integer idxSwap = null;
			for (int i = 1; i < grid.size(); i++)
			{
				TaskTile t = grid.get(i);
				String pos = t.getRow() + "," + t.getCol();
				if (mysteryPositions.contains(pos)) continue; // must be non-mystery
				boolean hasMysteryNeighbor = false;
				for (int dr = -1; dr <= 1 && !hasMysteryNeighbor; dr++)
					for (int dc = -1; dc <= 1 && !hasMysteryNeighbor; dc++)
					{
						if (dr == 0 && dc == 0) continue;
						String neighbor = (t.getRow() + dr) + "," + (t.getCol() + dc);
						if (mysteryPositions.contains(neighbor))
							hasMysteryNeighbor = true;
					}
				if (!hasMysteryNeighbor)
				{
					idxSwap = i;
					break;
				}
			}
			if (idxSwap == null) break; // cannot fix (e.g. too many mystery tasks)
			int idxA = posToIndex.get(mysteryA);
			int idxB = idxSwap;
			// Swap task content between tile at idxA and tile at idxB (keep positions)
			TaskTile tileA = grid.get(idxA);
			TaskTile tileB = grid.get(idxB);
			TaskTile newA = new TaskTile(tileA.getId(), tileA.getTier(), tileB.getDisplayName(), tileB.getPoints(),
				tileA.getRow(), tileA.getCol(), tileB.getTaskType(), tileB.getRequiredAreaIds(), tileB.isRequireAllAreas(), tileB.getRequirements());
			TaskTile newB = new TaskTile(tileB.getId(), tileB.getTier(), tileA.getDisplayName(), tileA.getPoints(),
				tileB.getRow(), tileB.getCol(), tileA.getTaskType(), tileA.getRequiredAreaIds(), tileA.isRequireAllAreas(), tileA.getRequirements());
			grid.set(idxA, newA);
			grid.set(idxB, newB);
		}
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
		int computed = MAX_GRID_TIERS;
		for (int T = MAX_TIER; T <= MAX_GRID_TIERS; T++)
		{
			if (totalPointsForTiers(T) >= target)
			{
				computed = T;
				break;
			}
		}
		// Starting area: ensure at least 9 rings so tier 4/5 tasks are visible (rings 9+)
		String start = config.startingArea();
		if (start != null && start.equals(areaId) && computed < STARTING_AREA_TIER4_FIRST_RING)
			return STARTING_AREA_TIER4_FIRST_RING;
		return computed;
	}

	/** Columns per overfill row so overfill positions are deterministic and compact. */
	private static final int OVERFILL_COLS = 50;

	/** Width of transition zones (mix of two difficulties) on the 1–5 scale; centered transitions at 1.5, 2.5, 3.5, 4.5. */
	private static final double TRANSITION_HALF_WIDTH = 0.35;

	/** In starting area, rings 4–8 use only difficulties 1–3; tier 4+ first appears at ring 9. */
	private static final int STARTING_AREA_TIER4_FIRST_RING = 9;

	/**
	 * Returns the difficulty (1–5) used to choose the task pool for a cell at ring {@code ring} with coords (r,c).
	 * Layout: inner rings = 1; transition rings = random mix of two difficulties; tier-3 zone has tier-4 in corners; outer = mix 4&5.
	 * In the starting area: rings 1–3 are all difficulty 1; rings 4–8 are difficulties 1–3 only; rings 9+ are tier 4 and 5.
	 * Scales with total rings T (grid can be larger or smaller than 17x17).
	 */
	private int difficultyForCell(String areaId, int ring, int r, int c, int totalRings, Random rng)
	{
		if (totalRings <= 1)
			return 1;
		String start = config.startingArea();
		boolean isStartingArea = start != null && start.equals(areaId);
		// Starting area: first 3 rings all difficulty 1
		if (isStartingArea && ring <= 3)
			return 1;
		double dCont;
		if (isStartingArea && ring >= 4)
		{
			// Fixed schedule: rings 4–8 = difficulties 1–3 only; rings 9+ = 4 and 5 (tier 4 never before ring 9)
			if (ring < STARTING_AREA_TIER4_FIRST_RING)
			{
				// Rings 4,5,6,7,8 → dCont 1.0, 1.5, 2.0, 2.5, 3.0
				dCont = 1.0 + 2.0 * (ring - 4) / 4.0;
			}
			else
			{
				// Rings 9+ → scale from 3.0 to 5.0 over remaining rings
				int ringsFrom9 = Math.max(1, totalRings - (STARTING_AREA_TIER4_FIRST_RING - 1));
				dCont = 3.0 + 2.0 * (ring - STARTING_AREA_TIER4_FIRST_RING) / ringsFrom9;
			}
		}
		else
		{
			dCont = 1.0 + 4.0 * (ring - 1) / (totalRings - 1);
		}
		// Tier-3 ring: put tier 4 in corners (cells where both |r| and |c| equal the ring)
		boolean isCorner = (Math.abs(r) == ring && Math.abs(c) == ring);
		if (isCorner && dCont >= 2.5 && dCont <= 3.5)
			return 4;
		// Transition zones: randomly choose between the two adjacent difficulties
		if (dCont >= 1.5 - TRANSITION_HALF_WIDTH && dCont <= 1.5 + TRANSITION_HALF_WIDTH)
			return rng.nextBoolean() ? 1 : 2;
		if (dCont >= 2.5 - TRANSITION_HALF_WIDTH && dCont <= 2.5 + TRANSITION_HALF_WIDTH)
			return rng.nextBoolean() ? 2 : 3;
		if (dCont >= 3.5 - TRANSITION_HALF_WIDTH && dCont <= 3.5 + TRANSITION_HALF_WIDTH)
			return rng.nextBoolean() ? 3 : 4;
		if (dCont >= 4.5 - TRANSITION_HALF_WIDTH && dCont <= 4.5 + TRANSITION_HALF_WIDTH)
			return rng.nextBoolean() ? 4 : 5;
		int d = (int) Math.round(dCont);
		return Math.max(1, Math.min(MAX_TIER, d));
	}

	/**
	 * Returns the display tier (1-based) shown for a cell at the given ring. In the starting area, rings 1–3
	 * all show as tier 1; ring 4 → 1, rings 5–6 → 2, rings 7–8 → 3, rings 9+ → 4 and 5.
	 */
	private int displayTierForCell(String areaId, int ring)
	{
		String start = config.startingArea();
		if (start == null || !start.equals(areaId))
			return ring;
		if (ring <= 3)
			return 1;
		// Ring 4→1, 5–6→2, 7–8→3, 9–10→4, 11–12→5, ...
		if (ring < STARTING_AREA_TIER4_FIRST_RING)
			return (int) Math.ceil((ring - 2) / 2.0);  // 4→1, 5,6→2, 7,8→3
		int logicalRing = ring - (STARTING_AREA_TIER4_FIRST_RING - 1);  // ring 9→1, 10→2, ...
		return 3 + (int) Math.ceil(logicalRing / 2.0);   // 9,10→4, 11,12→5
	}

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
	 * Returns true if all quests listed in the requirements string are complete.
	 * Requirements can be comma-separated quest names (e.g. "Waterfall Quest, Dragon Slayer II")
	 * or "100% Quest Completion" to require every quest finished.
	 */
	public boolean areQuestRequirementsMet(String requirements)
	{
		if (requirements == null || requirements.isEmpty()) return true;
		String req = requirements.trim();
		if (req.equalsIgnoreCase("100% Quest Completion"))
		{
			for (Quest q : Quest.values())
			{
				if (q.getState(client) != QuestState.FINISHED) return false;
			}
			return true;
		}
		for (String name : parseQuestNamesFromRequirements(req))
		{
			Quest quest = findQuestByName(name);
			if (quest == null || quest.getState(client) != QuestState.FINISHED) return false;
		}
		return true;
	}

	/**
	 * Returns the list of required quest names that are not yet finished (for UI message).
	 * Empty if all requirements are met or there are no quest requirements.
	 */
	public List<String> getUnmetQuestRequirements(String requirements)
	{
		List<String> unmet = new ArrayList<>();
		if (requirements == null || requirements.isEmpty()) return unmet;
		String req = requirements.trim();
		if (req.equalsIgnoreCase("100% Quest Completion"))
		{
			for (Quest q : Quest.values())
			{
				if (q.getState(client) != QuestState.FINISHED) unmet.add(q.getName());
			}
			return unmet;
		}
		for (String name : parseQuestNamesFromRequirements(req))
		{
			Quest quest = findQuestByName(name);
			if (quest == null) unmet.add(name);
			else if (quest.getState(client) != QuestState.FINISHED) unmet.add(quest.getName());
		}
		return unmet;
	}

	private static List<String> parseQuestNamesFromRequirements(String requirements)
	{
		List<String> out = new ArrayList<>();
		for (String part : requirements.split(","))
		{
			String name = part.trim();
			if (!name.isEmpty()) out.add(name);
		}
		return out;
	}

	private static Quest findQuestByName(String name)
	{
		if (name == null || name.isEmpty()) return null;
		String n = name.trim();
		for (Quest q : Quest.values())
		{
			if (q.getName().equalsIgnoreCase(n)) return q;
		}
		return null;
	}

	/**
	 * Marks a task as claimed: adds to claimed set, persists, and awards tier points to
	 * PointsService and AreaCompletionService. Idempotent if already claimed.
	 * If the task has a "requirements" (quest) string, all listed quests must be finished or claim is blocked.
	 */
	public void setClaimed(String areaId, String taskId)
	{
		Set<String> claimed = loadSet(areaId, SUFFIX_CLAIMED);
		if (claimed.contains(taskId)) return;

		List<TaskTile> grid = getGridForArea(areaId);
		TaskTile tile = grid.stream().filter(t -> t.getId().equals(taskId)).findFirst().orElse(null);
		if (tile != null && tile.getRequirements() != null && !tile.getRequirements().isEmpty()
			&& !areQuestRequirementsMet(tile.getRequirements()))
			return;

		claimed.add(taskId);
		saveSet(areaId, SUFFIX_CLAIMED, claimed);

		int points = tile != null ? tile.getPoints() : grid.stream()
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

package com.leaguescape.worldunlock;

import com.leaguescape.LeagueScapeConfig;
import com.leaguescape.points.PointsService;
import com.leaguescape.task.TaskDefinition;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.config.ConfigManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds the global task list from all unlocked world-unlock tiles, persists completed/claimed
 * state in a "global" namespace, and awards points on claim. Used only when unlock mode is WORLD_UNLOCK.
 */
@Singleton
public class GlobalTaskListService
{
	private static final Logger log = LoggerFactory.getLogger(GlobalTaskListService.class);
	private static final String STATE_GROUP = "leaguescapeState";
	private static final String KEY_GLOBAL_CLAIMED = "globalTaskProgress_claimed";
	private static final String KEY_GLOBAL_COMPLETED = "globalTaskProgress_completed";
	private static final String ID_SEP = ",";

	private final ConfigManager configManager;
	private final LeagueScapeConfig config;
	private final PointsService pointsService;
	private final WorldUnlockService worldUnlockService;

	@Inject
	public GlobalTaskListService(ConfigManager configManager, LeagueScapeConfig config,
		PointsService pointsService, WorldUnlockService worldUnlockService)
	{
		this.configManager = configManager;
		this.config = config;
		this.pointsService = pointsService;
		this.worldUnlockService = worldUnlockService;
	}

	/** Normalized key for a task (same as TaskGridService: displayName lowercase). */
	public static String taskKey(TaskDefinition t)
	{
		String name = t.getDisplayName();
		return name != null ? name.trim().toLowerCase() : "";
	}

	/** Returns the flat list of tasks from all unlocked world-unlock tiles, deduplicated by task key. */
	public List<TaskDefinition> getGlobalTasks()
	{
		Set<String> unlockedIds = worldUnlockService.getUnlockedIds();
		LinkedHashMap<String, TaskDefinition> byKey = new LinkedHashMap<>();
		for (String unlockId : unlockedIds)
		{
			for (TaskDefinition t : worldUnlockService.getTasksForUnlock(unlockId))
			{
				String key = taskKey(t);
				if (!key.isEmpty() && !byKey.containsKey(key))
				{
					byKey.put(key, t);
				}
			}
		}
		return new ArrayList<>(byKey.values());
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
	}
}

package com.leaguescape.points;

import com.leaguescape.LeagueScapeConfig;
import com.leaguescape.area.AreaGraphService;
import com.leaguescape.data.AreaStatus;
import com.leaguescape.task.TaskGridService;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;

/**
 * Tracks points earned per area and which areas are "complete" (earned enough points in that area).
 * Used when unlock mode is POINTS_TO_COMPLETE: complete an area by earning points there, then you can unlock the next.
 */
@Slf4j
@Singleton
public class AreaCompletionService
{
	private static final String CONFIG_GROUP = "leaguescapeState";
	private static final String KEY_POINTS_PER_AREA = "pointsEarnedPerArea";
	private static final String KEY_COMPLETED_AREAS = "completedAreas";

	private final ConfigManager configManager;
	private final AreaGraphService areaGraphService;
	private final PointsService pointsService;
	private final LeagueScapeConfig config;

	private final Map<String, Integer> pointsEarnedInArea = new ConcurrentHashMap<>();
	private final Set<String> completedAreaIds = new HashSet<>();
	private final Provider<TaskGridService> taskGridServiceProvider;

	@Inject
	public AreaCompletionService(ConfigManager configManager, AreaGraphService areaGraphService, PointsService pointsService, LeagueScapeConfig config,
		Provider<TaskGridService> taskGridServiceProvider)
	{
		this.configManager = configManager;
		this.areaGraphService = areaGraphService;
		this.pointsService = pointsService;
		this.config = config;
		this.taskGridServiceProvider = taskGridServiceProvider;
	}

	/**
	 * Loads points-per-area and completed-areas from config. Clears in-memory maps first, then
	 * parses KEY_POINTS_PER_AREA (format "areaId:points,areaId:points,...") and KEY_COMPLETED_AREAS
	 * (comma-separated area IDs). Recomputes completed set from points in case the area's
	 * points-to-complete threshold changed, then persists the completed list.
	 */
	public void loadFromConfig()
	{
		pointsEarnedInArea.clear();
		String raw = configManager.getConfiguration(CONFIG_GROUP, KEY_POINTS_PER_AREA);
		if (raw != null && !raw.isEmpty())
		{
			for (String part : raw.split(","))
			{
				String[] kv = part.split(":", 2);
				if (kv.length == 2)
				{
					try
					{
						String areaId = kv[0].trim();
						int points = Integer.parseInt(kv[1].trim());
						if (!areaId.isEmpty() && points > 0)
							pointsEarnedInArea.put(areaId, points);
					}
					catch (NumberFormatException ignored) { }
				}
			}
		}

		completedAreaIds.clear();
		String completedRaw = configManager.getConfiguration(CONFIG_GROUP, KEY_COMPLETED_AREAS);
		if (completedRaw != null && !completedRaw.isEmpty())
		{
			for (String id : completedRaw.split(","))
			{
				String tid = id.trim();
				if (!tid.isEmpty()) completedAreaIds.add(tid);
			}
		}
		// Recompute completed from points (in case area pointsToComplete threshold changed in config)
		for (String areaId : pointsEarnedInArea.keySet())
		{
			if (getPointsEarnedInArea(areaId) >= getPointsToComplete(areaId))
				completedAreaIds.add(areaId);
		}
		persistCompleted();
	}

	/** Points earned in this area (from completing tasks there). */
	public int getPointsEarnedInArea(String areaId)
	{
		return pointsEarnedInArea.getOrDefault(areaId, 0);
	}

	/** Points required to "complete" this area (uses area's pointsToComplete, or unlockCost if not set). */
	public int getPointsToComplete(String areaId)
	{
		return areaGraphService.getPointsToComplete(areaId);
	}

	/** True if this area has been completed (earned enough points in it). */
	public boolean isComplete(String areaId)
	{
		return completedAreaIds.contains(areaId);
	}

	/** Set of area IDs that are complete. Unmodifiable. */
	public Set<String> getCompletedAreaIds()
	{
		return Collections.unmodifiableSet(completedAreaIds);
	}

	/**
	 * Set of area IDs that count as "complete" for gating the next unlock.
	 * Points-to-complete mode: areas that have earned at least their points-to-complete threshold.
	 * Point-buy mode: unlocked areas that have every task completed.
	 */
	public Set<String> getEffectiveCompletedAreaIds()
	{
		if (config.unlockMode() == LeagueScapeConfig.UnlockMode.POINT_BUY)
		{
			Set<String> out = new HashSet<>();
			TaskGridService taskGrid = taskGridServiceProvider.get();
			for (String areaId : areaGraphService.getUnlockedAreaIds())
			{
				if (taskGrid.isAreaFullyCompleted(areaId)) out.add(areaId);
			}
			return out;
		}
		return Collections.unmodifiableSet(completedAreaIds);
	}

	/**
	 * Current status of an area: Locked (no interaction), Unlocked (interaction + tasks, not complete), or Complete (fully done).
	 * In point-buy mode: complete only when every task in the area is completed (not just when area is unlocked).
	 * In points-to-complete mode: complete when the area has earned enough points to complete it.
	 */
	public AreaStatus getAreaStatus(String areaId)
	{
		if (!areaGraphService.getUnlockedAreaIds().contains(areaId))
		{
			return AreaStatus.LOCKED;
		}
		if (config.unlockMode() == LeagueScapeConfig.UnlockMode.POINT_BUY)
		{
			// Unlocked area is complete only when all tasks in that area are completed
			return taskGridServiceProvider.get().isAreaFullyCompleted(areaId) ? AreaStatus.COMPLETE : AreaStatus.UNLOCKED;
		}
		// Points-to-complete: complete when earned enough points in this area
		return completedAreaIds.contains(areaId) ? AreaStatus.COMPLETE : AreaStatus.UNLOCKED;
	}

	/**
	 * Add points earned in a specific area (e.g. from completing a task there).
	 * Also adds to global earned total so they can be spent. When points in area reach threshold, area is marked complete.
	 */
	public void addEarnedInArea(String areaId, int amount)
	{
		if (amount <= 0) return;
		pointsService.addEarned(amount);
		int prev = pointsEarnedInArea.getOrDefault(areaId, 0);
		int next = prev + amount;
		pointsEarnedInArea.put(areaId, next);
		persistPointsPerArea();
		int threshold = getPointsToComplete(areaId);
		if (threshold > 0 && next >= threshold && completedAreaIds.add(areaId))
		{
			persistCompleted();
			log.debug("Area {} completed ({} / {} points)", areaId, next, threshold);
		}
	}

	/** Persists points-per-area as "areaId:points,areaId:points,...". Skips areas with <= 0 points. */
	private void persistPointsPerArea()
	{
		StringBuilder sb = new StringBuilder();
		for (Map.Entry<String, Integer> e : pointsEarnedInArea.entrySet())
		{
			if (e.getValue() <= 0) continue;
			if (sb.length() > 0) sb.append(",");
			sb.append(e.getKey()).append(":").append(e.getValue());
		}
		configManager.setConfiguration(CONFIG_GROUP, KEY_POINTS_PER_AREA, sb.toString());
	}

	/** Persists completed area IDs as comma-separated string. */
	private void persistCompleted()
	{
		String joined = String.join(",", completedAreaIds);
		configManager.setConfiguration(CONFIG_GROUP, KEY_COMPLETED_AREAS, joined);
	}
}

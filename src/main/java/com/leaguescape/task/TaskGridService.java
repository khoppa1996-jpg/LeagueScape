package com.leaguescape.task;

import com.leaguescape.LeagueScapeConfig;
import com.leaguescape.points.AreaCompletionService;
import com.leaguescape.points.PointsService;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;

/**
 * Generates task grid per area, persists completed/claimed state, and awards points on claim.
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

	private final ConfigManager configManager;
	private final LeagueScapeConfig config;
	private final PointsService pointsService;
	private final AreaCompletionService areaCompletionService;

	@Inject
	public TaskGridService(ConfigManager configManager, LeagueScapeConfig config,
		PointsService pointsService, AreaCompletionService areaCompletionService)
	{
		this.configManager = configManager;
		this.config = config;
		this.pointsService = pointsService;
		this.areaCompletionService = areaCompletionService;
	}

	/**
	 * Generate the full task grid for an area (all tiers up to MAX_TIER).
	 * Center (0,0) is tier 0 "Free"; tier t has cells with max(|r|,|c|)=t.
	 */
	public List<TaskTile> getGridForArea(String areaId)
	{
		List<TaskTile> out = new ArrayList<>();
		for (int r = -MAX_TIER; r <= MAX_TIER; r++)
		{
			for (int c = -MAX_TIER; c <= MAX_TIER; c++)
			{
				int tier = Math.max(Math.abs(r), Math.abs(c));
				if (tier > MAX_TIER) continue;
				String id = TaskTile.idFor(r, c);
				int points = pointsForTier(tier);
				String displayName = (r == 0 && c == 0) ? "Free" : ("Task " + id);
				out.add(new TaskTile(id, tier, displayName, points, r, c));
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

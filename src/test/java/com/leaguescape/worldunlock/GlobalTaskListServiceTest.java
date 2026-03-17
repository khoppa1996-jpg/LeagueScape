package com.leaguescape.worldunlock;

import com.leaguescape.LeagueScapeConfig;
import com.leaguescape.points.PointsService;
import com.leaguescape.task.TaskDefinition;
import com.leaguescape.task.TaskGridService;
import com.leaguescape.task.TaskState;
import com.leaguescape.task.TaskTile;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.runelite.client.config.ConfigManager;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Unit tests for GlobalTaskListService.
 * Verifies: task list filling, task assignment to grid positions, tile reveal logic,
 * and that adjacent tiles appear when center is claimed.
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class GlobalTaskListServiceTest
{
	@Mock
	private ConfigManager configManager;

	@Mock
	private LeagueScapeConfig config;

	@Mock
	private PointsService pointsService;

	@Mock
	private WorldUnlockService worldUnlockService;

	@Mock
	private TaskGridService taskGridService;

	private GlobalTaskListService service;

	private static final String STATE_GROUP = "leaguescapeState";
	private static final String KEY_CENTER_CLAIMED = "globalTaskProgress_centerClaimed";
	private static final String KEY_CLAIMED = "globalTaskProgress_claimed";
	private static final String KEY_POSITIONS = "globalTaskProgress_positions";
	private static final String KEY_PSEUDO_CENTER = "globalTaskProgress_pseudoCenter";

	@Before
	public void setUp()
	{
		// Default config: center not claimed, no positions
		lenient().when(configManager.getConfiguration(eq(STATE_GROUP), eq(KEY_CENTER_CLAIMED))).thenReturn(null);
		lenient().when(configManager.getConfiguration(eq(STATE_GROUP), eq(KEY_CLAIMED))).thenReturn(null);
		lenient().when(configManager.getConfiguration(eq(STATE_GROUP), eq(KEY_POSITIONS))).thenReturn(null);
		lenient().when(configManager.getConfiguration(eq(STATE_GROUP), eq(KEY_PSEUDO_CENTER))).thenReturn(null);
		lenient().when(configManager.getConfiguration(eq(STATE_GROUP), eq("globalTaskProgress_completed"))).thenReturn(null);
		lenient().when(configManager.getConfiguration(eq(STATE_GROUP), eq("globalTaskProgress_lastViewed"))).thenReturn(null);
		lenient().when(config.taskTier1Points()).thenReturn(10);
		lenient().when(config.taskTier2Points()).thenReturn(25);
		lenient().when(config.taskTier3Points()).thenReturn(50);
		lenient().when(config.taskTier4Points()).thenReturn(75);
		lenient().when(config.taskTier5Points()).thenReturn(100);

		service = new GlobalTaskListService(configManager, config, pointsService,
			worldUnlockService, taskGridService);
	}

	/** Create a simple task for testing. */
	private TaskDefinition task(String displayName, int difficulty)
	{
		TaskDefinition t = new TaskDefinition();
		t.setDisplayName(displayName);
		t.setDifficulty(difficulty);
		// No area = no-area task (getRequiredAreaIds returns empty)
		return t;
	}

	@Test
	public void testGetGlobalTasksReturnsNoAreaTasksWhenUnlockedEmpty()
	{
		// When no world tiles are unlocked, getGlobalTasks should still return no-area tasks
		when(worldUnlockService.getUnlockedIds()).thenReturn(Collections.emptySet());
		when(worldUnlockService.getTiles()).thenReturn(Collections.emptyList());

		List<TaskDefinition> noAreaTasks = Arrays.asList(
			task("Chop some Logs", 1),
			task("Burn some Logs", 1)
		);
		when(taskGridService.getEffectiveDefaultTasks()).thenReturn(noAreaTasks);

		List<TaskDefinition> result = service.getGlobalTasks();

		assertNotNull(result);
		assertEquals(2, result.size());
	}

	@Test
	public void testBuildGlobalGridReturnsCenterOnlyWhenCenterNotClaimed()
	{
		// Center not claimed -> only center tile in grid
		when(configManager.getConfiguration(eq(STATE_GROUP), eq(KEY_CENTER_CLAIMED))).thenReturn(null);
		when(worldUnlockService.getUnlockedIds()).thenReturn(Collections.emptySet());
		when(worldUnlockService.getTiles()).thenReturn(Collections.emptyList());
		when(taskGridService.getEffectiveDefaultTasks()).thenReturn(Collections.emptyList());

		List<TaskTile> grid = service.buildGlobalGrid(12345);

		assertNotNull(grid);
		assertEquals(1, grid.size());
		assertEquals("0,0", grid.get(0).getId());
		assertEquals("Free", grid.get(0).getDisplayName());
	}

	@Test
	public void testBuildGlobalGridRevealsAdjacentTilesWhenCenterClaimed()
	{
		// Center claimed -> adjacent tiles (1,0), (-1,0), (0,1), (0,-1) should be revealed
		when(configManager.getConfiguration(eq(STATE_GROUP), eq(KEY_CENTER_CLAIMED))).thenReturn("true");
		when(configManager.getConfiguration(eq(STATE_GROUP), eq(KEY_POSITIONS))).thenReturn(null);
		when(configManager.getConfiguration(eq(STATE_GROUP), eq(KEY_PSEUDO_CENTER))).thenReturn("0,0");

		// Provide tasks for the grid
		List<TaskDefinition> tasks = Arrays.asList(
			task("Chop some Logs", 1),
			task("Burn some Logs", 1),
			task("Fletch Arrowshafts", 1),
			task("Mine Copper", 1),
			task("Fish Shrimp", 1)
		);
		when(worldUnlockService.getUnlockedIds()).thenReturn(Collections.emptySet());
		when(worldUnlockService.getTiles()).thenReturn(Collections.emptyList());
		when(taskGridService.getEffectiveDefaultTasks()).thenReturn(tasks);

		List<TaskTile> grid = service.buildGlobalGrid(12345);

		assertNotNull(grid);
		// Should have center + 4 adjacent = 5 tiles minimum
		assertTrue("Grid should have center + adjacent tiles when center claimed, got " + grid.size(),
			grid.size() >= 5);

		// Verify center is present
		boolean hasCenter = grid.stream().anyMatch(t -> "0,0".equals(t.getId()));
		assertTrue("Grid should contain center tile", hasCenter);

		// Verify adjacent positions exist
		Set<String> positions = new HashSet<>();
		for (TaskTile t : grid)
			positions.add(t.getRow() + "," + t.getCol());

		assertTrue("Should have (1,0)", positions.contains("1,0"));
		assertTrue("Should have (-1,0)", positions.contains("-1,0"));
		assertTrue("Should have (0,1)", positions.contains("0,1"));
		assertTrue("Should have (0,-1)", positions.contains("0,-1"));
	}

	@Test
	public void testGetGlobalStateReturnsRevealedForAdjacentWhenCenterClaimed()
	{
		// Setup: center claimed, grid has center + adjacent
		when(configManager.getConfiguration(eq(STATE_GROUP), eq(KEY_CENTER_CLAIMED))).thenReturn("true");
		when(configManager.getConfiguration(eq(STATE_GROUP), eq(KEY_CLAIMED))).thenReturn(null);
		when(configManager.getConfiguration(eq(STATE_GROUP), eq("globalTaskProgress_completed"))).thenReturn(null);

		List<TaskTile> grid = new ArrayList<>();
		grid.add(new TaskTile("0,0", 0, "Free", 0, 0, 0, null, null, true, null, null));
		grid.add(new TaskTile("1,0", 1, "Chop Logs", 10, 1, 0, "Woodcutting", null, true, null, null));

		TaskState centerState = service.getGlobalState("0,0", grid);
		assertEquals(TaskState.CLAIMED, centerState);

		TaskState adjacentState = service.getGlobalState("1,0", grid);
		assertEquals("Adjacent tile should be REVEALED when center is claimed", TaskState.REVEALED, adjacentState);
	}

	@Test
	public void testGetGlobalStateReturnsLockedForTileWithoutClaimedNeighbor()
	{
		// Tile at (2,0) has no claimed neighbor when only center is claimed
		when(configManager.getConfiguration(eq(STATE_GROUP), eq(KEY_CENTER_CLAIMED))).thenReturn("true");
		when(configManager.getConfiguration(eq(STATE_GROUP), eq(KEY_CLAIMED))).thenReturn(null);

		List<TaskTile> grid = new ArrayList<>();
		grid.add(new TaskTile("0,0", 0, "Free", 0, 0, 0, null, null, true, null, null));
		grid.add(new TaskTile("1,0", 1, "Chop Logs", 10, 1, 0, "Woodcutting", null, true, null, null));
		grid.add(new TaskTile("2,0", 1, "Burn Logs", 10, 2, 0, "Firemaking", null, true, null, null));

		// (2,0) neighbors (1,0) and (3,0). (1,0) is in grid but not claimed. So (2,0) is LOCKED
		TaskState state = service.getGlobalState("2,0", grid);
		assertEquals(TaskState.LOCKED, state);
	}

	@Test
	public void testFallbackUsesAnyTasksWhenNoAreaTasksEmpty()
	{
		// When getGlobalTasks returns empty and no-area filter yields empty, use any tasks
		when(configManager.getConfiguration(eq(STATE_GROUP), eq(KEY_CENTER_CLAIMED))).thenReturn("true");
		when(configManager.getConfiguration(eq(STATE_GROUP), eq(KEY_POSITIONS))).thenReturn(null);
		when(configManager.getConfiguration(eq(STATE_GROUP), eq(KEY_PSEUDO_CENTER))).thenReturn("0,0");

		// All tasks have area - so no-area filter would exclude them
		TaskDefinition areaTask = new TaskDefinition();
		areaTask.setDisplayName("Lumbridge Task");
		areaTask.setDifficulty(1);
		areaTask.setArea("lumbridge");
		List<TaskDefinition> allTasks = Arrays.asList(areaTask);

		when(worldUnlockService.getUnlockedIds()).thenReturn(Collections.emptySet());
		when(worldUnlockService.getTiles()).thenReturn(Collections.emptyList());
		when(taskGridService.getEffectiveDefaultTasks()).thenReturn(allTasks);

		List<TaskTile> grid = service.buildGlobalGrid(12345);

		// With lazy assignment and strict one-use, we get center + as many adjacent as we have tasks in the pool (here 1)
		assertTrue("Fallback should use any tasks when no-area is empty, got " + grid.size(),
			grid.size() >= 2);
	}

	@Test
	public void testIsStarterAreaUnlockedOnGridReturnsFalseWhenStarterNotUnlocked()
	{
		when(config.startingArea()).thenReturn("lumbridge");
		when(worldUnlockService.getUnlockedIds()).thenReturn(Collections.emptySet());

		assertFalse(service.isStarterAreaUnlockedOnGrid());
	}

	@Test
	public void testIsStarterAreaUnlockedOnGridReturnsFalseWhenStarterAreaConfigEmpty()
	{
		when(config.startingArea()).thenReturn("");
		when(worldUnlockService.getUnlockedIds()).thenReturn(Collections.singleton("lumbridge"));

		assertFalse(service.isStarterAreaUnlockedOnGrid());
	}

	@Test
	public void testIsStarterAreaUnlockedOnGridReturnsTrueWhenStarterUnlocked()
	{
		when(config.startingArea()).thenReturn("lumbridge");
		when(worldUnlockService.getUnlockedIds()).thenReturn(new HashSet<>(Arrays.asList("lumbridge", "varrock")));

		assertTrue(service.isStarterAreaUnlockedOnGrid());
	}
}

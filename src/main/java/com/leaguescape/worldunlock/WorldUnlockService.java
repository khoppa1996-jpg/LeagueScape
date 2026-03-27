package com.leaguescape.worldunlock;

import com.google.gson.Gson;
import com.leaguescape.LeagueScapeConfig;
import com.leaguescape.area.AreaGraphService;
import com.leaguescape.constants.WorldUnlockTileType;
import com.leaguescape.grid.GridPos;
import com.leaguescape.grid.RevealLogic;
import com.leaguescape.util.ConfigParsing;
import com.leaguescape.util.LeagueScapeConfigConstants;
import com.leaguescape.util.ResourceJsonLoader;
import com.leaguescape.util.ResourcePaths;
import com.leaguescape.data.Area;
import com.leaguescape.data.AreaMappingData;
import com.leaguescape.points.PointsService;
import com.leaguescape.task.TaskDefinition;
import com.leaguescape.task.TaskGridService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
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
 * Loads world_unlocks.json, persists unlocked tile ids, and provides unlock(tileId, cost).
 * Used only when unlock mode is WORLD_UNLOCK.
 */
@Singleton
public class WorldUnlockService
{
	private static final Logger log = LoggerFactory.getLogger(WorldUnlockService.class);
	private static final String STATE_GROUP = LeagueScapeConfigConstants.STATE_GROUP;
	private static final String KEY_WORLD_UNLOCK_UNLOCKED_IDS = "worldUnlockUnlockedIds";
	private static final String KEY_WORLD_UNLOCK_CLAIMED_IDS = "worldUnlockClaimedIds";
	private static final String KEY_WORLD_UNLOCK_GRID_SEED = "worldUnlockGridSeed";
	private static final String KEY_WORLD_UNLOCK_GRID_STATE = "worldUnlockGridState";
	/** Grid state entry format: pos##tileId (same idea as Global Task grid). */
	private static final String GRID_STATE_SEP = "##";
	private static final String POS_ENTRY_SEP = "||";
	private static final String PLUGIN_CONFIG_GROUP = LeagueScapeConfigConstants.CONFIG_GROUP;
	/** Matches {@code LeagueScapeConfig} per-tier key middle part: worldUnlockTier{N}<suffix>Multiplier */
	private static final String[] WORLD_UNLOCK_TYPE_SUFFIX = {
		"Skill", "Area", "Boss", "Quest", "AchievementDiary"
	};
	private static final String[] WORLD_UNLOCK_LEGACY_KEYS = {
		"worldUnlockSkillMultiplier",
		"worldUnlockAreaMultiplier",
		"worldUnlockBossMultiplier",
		"worldUnlockQuestMultiplier",
		"worldUnlockAchievementDiaryMultiplier"
	};

	private final ConfigManager configManager;
	private final LeagueScapeConfig config;
	private final PointsService pointsService;
	private final TaskGridService taskGridService;
	private final AreaGraphService areaGraphService;

	private List<WorldUnlockTile> tiles = new ArrayList<>();
	private final Set<String> unlockedIds = new HashSet<>();
	private final Set<String> claimedIds = new HashSet<>();
	private boolean loaded = false;
	/** Lazy-built: area id -> achievement diary key (e.g. varrock -> varrock, al_kharid -> desert). Uses area_mapping.json when available. */
	private Map<String, String> areaIdToDiaryKey = null;
	/** Lazy-built from area_mapping.json: diary key (normalized) -> list of area ids in that diary. Empty if mapping not loaded. */
	private Map<String, List<String>> diaryKeyToAreaIds = null;

	@Inject
	public WorldUnlockService(ConfigManager configManager, LeagueScapeConfig config, PointsService pointsService,
		TaskGridService taskGridService, AreaGraphService areaGraphService)
	{
		this.configManager = configManager;
		this.config = config;
		this.pointsService = pointsService;
		this.taskGridService = taskGridService;
		this.areaGraphService = areaGraphService;
	}

	/** Load tiles from resource and persisted unlocked ids. Call once at startup when mode is WORLD_UNLOCK. */
	public void load()
	{
		if (loaded)
		{
			return;
		}
		tiles.clear();
		unlockedIds.clear();
		areaIdToDiaryKey = null;
		diaryKeyToAreaIds = null;
		Gson gson = new Gson();
		WorldUnlocksData data = ResourceJsonLoader.load(getClass(), ResourcePaths.WORLD_UNLOCKS_JSON, WorldUnlocksData.class, gson, log);
		if (data != null && data.getUnlocks() != null)
		{
			tiles = data.getUnlocks();
		}
		unlockedIds.addAll(ConfigParsing.parseCommaSeparatedSet(configManager.getConfiguration(STATE_GROUP, KEY_WORLD_UNLOCK_UNLOCKED_IDS)));
		claimedIds.clear();
		claimedIds.addAll(ConfigParsing.parseCommaSeparatedSet(configManager.getConfiguration(STATE_GROUP, KEY_WORLD_UNLOCK_CLAIMED_IDS)));
		// Boss tiles match area tiles: unlocked implies claimed for fog/reveal (repair legacy saves missing claimed id).
		boolean bossClaimRepair = false;
		for (WorldUnlockTile t : tiles)
		{
			if (t == null || t.getId() == null || !WorldUnlockTileType.BOSS.equals(t.getType())) continue;
			if (unlockedIds.contains(t.getId()) && !claimedIds.contains(t.getId()))
			{
				claimedIds.add(t.getId());
				bossClaimRepair = true;
			}
		}
		if (bossClaimRepair)
			persistClaimed();
		loaded = true;
	}

	private void persistUnlocked()
	{
		configManager.setConfiguration(STATE_GROUP, KEY_WORLD_UNLOCK_UNLOCKED_IDS, ConfigParsing.joinComma(unlockedIds));
	}

	private void persistClaimed()
	{
		configManager.setConfiguration(STATE_GROUP, KEY_WORLD_UNLOCK_CLAIMED_IDS, ConfigParsing.joinComma(claimedIds));
	}

	private static int[] parsePos(String pos) { return GridPos.parse(pos); }
	private static String normalizePos(String pos) { return GridPos.normalize(pos); }
	private static List<String> getNeighborPositions(String pos) { return GridPos.neighbors4(pos); }

	/** Loads grid state: normalized position -> tile id. Only add when a position is first revealed; never overwrite. */
	private Map<String, String> loadGridState()
	{
		String raw = configManager.getConfiguration(STATE_GROUP, KEY_WORLD_UNLOCK_GRID_STATE);
		Map<String, String> gridState = new HashMap<>();
		if (raw == null || raw.isEmpty()) return gridState;
		Pattern entrySplit = Pattern.compile("\\|\\|(?!\\|)");
		for (String entry : entrySplit.split(raw))
		{
			entry = entry.trim();
			if (entry.isEmpty() || !entry.contains(GRID_STATE_SEP)) continue;
			int i = entry.indexOf(GRID_STATE_SEP);
			String pos = entry.substring(0, i).trim();
			String tileId = entry.substring(i + GRID_STATE_SEP.length()).trim();
			if (!pos.isEmpty() && !tileId.isEmpty())
				gridState.put(normalizePos(pos), tileId);
		}
		return gridState;
	}

	private void saveGridState(Map<String, String> gridState)
	{
		List<String> parts = new ArrayList<>();
		for (Map.Entry<String, String> e : gridState.entrySet())
		{
			String pos = e.getKey();
			String tileId = e.getValue();
			if (pos != null && !pos.isEmpty() && tileId != null && !tileId.isEmpty())
				parts.add(pos + GRID_STATE_SEP + tileId);
		}
		configManager.setConfiguration(STATE_GROUP, KEY_WORLD_UNLOCK_GRID_STATE, String.join(POS_ENTRY_SEP, parts));
	}

	/** Returns the grid seed used for shuffle order when assigning new tiles. */
	public int getGridSeed()
	{
		String raw = configManager.getConfiguration(STATE_GROUP, KEY_WORLD_UNLOCK_GRID_SEED);
		if (raw == null || raw.isEmpty()) return 0;
		try { return Integer.parseInt(raw); } catch (NumberFormatException e) { return 0; }
	}

	/** Increments the grid seed so the next getGrid() produces a new layout (e.g. on reset). */
	public void incrementGridSeed()
	{
		configManager.setConfiguration(STATE_GROUP, KEY_WORLD_UNLOCK_GRID_SEED, String.valueOf(getGridSeed() + 1));
	}

	/**
	 * Returns the world unlock grid using rolling assignment (same model as Global Task grid).
	 * Center (0,0) = start tile. Tiles are assigned only when first revealed (adjacent to a claimed tile).
	 * Never reassign; a tile may only be assigned to a revealed slot when every prerequisite is satisfied.
	 * For <b>quest</b> and <b>boss</b> tiles, every prerequisite that resolves to another unlock tile must be <b>claimed</b>
	 * (not merely unlocked). For <b>skill</b> tiles, each resolved prerequisite must be <b>claimed</b>, <b>revealed</b> (on the grid), or <b>unlocked</b>.
	 * Other types use <b>unlocked or claimed</b> as before (area and boss tiles are auto-claimed on unlock).
	 * Fog-of-war reveal still spreads only from <b>claimed</b> tiles (and the center), so adjacent cells stay hidden until
	 * the player claims the neighboring tile.
	 * Rings 1–2: only tier-1 skill tiles (levels 1–10). Ring 3+ (weighted roll): 65% skill, 12% quest (requires an
	 * unlocked area prerequisite), 10% neighbor area (from areas.json adjacency to an unlocked area only), 10% boss
	 * (bosses whose prerequisites are satisfied for assignment), 3% achievement diary (unlocked area prerequisite).
	 * Fallback order matches that priority.
	 */
	public List<WorldUnlockTilePlacement> getGrid()
	{
		if (!loaded) load();
		List<WorldUnlockTile> all = new ArrayList<>(tiles);
		if (all.isEmpty()) return Collections.emptyList();

		// Center = configured starter area tile if present, else first tile with no prereqs and cost 0, else first in list
		WorldUnlockTile centerTile = null;
		String startArea = config.startingArea();
		if (startArea != null && !startArea.isEmpty())
		{
			centerTile = getTileById(startArea);
		}
		if (centerTile == null)
		{
			for (WorldUnlockTile t : all)
			{
				if ((t.getPrerequisites() == null || t.getPrerequisites().isEmpty()) && getTileCost(t) == 0)
				{
					centerTile = t;
					break;
				}
			}
		}
		if (centerTile == null)
			centerTile = all.get(0);

		// 1. Single grid state: position -> tile id. Only add when first revealed; never overwrite.
		Map<String, String> gridState = new HashMap<>(loadGridState());

		// 2. Claimed positions = positions whose tile has been claimed (unlock + action done). Only these reveal neighbors.
		Set<String> claimedPositions = new HashSet<>();
		claimedPositions.add("0,0"); // center counts as claimed for reveal so its neighbors are always revealed
		for (Map.Entry<String, String> e : gridState.entrySet())
		{
			if (claimedIds.contains(e.getValue()))
				claimedPositions.add(e.getKey());
		}

		// 3. Revealed = center + claimed positions + neighbors of claimed positions (all sides of each claimed tile)
		Set<String> revealedPositions = new HashSet<>();
		revealedPositions.add("0,0");
		for (String cp : claimedPositions)
		{
			revealedPositions.add(normalizePos(cp));
			for (String neighbor : getNeighborPositions(cp))
				revealedPositions.add(normalizePos(neighbor));
		}

		// 4. toAssign = revealed positions that have no assignment yet (first time revealed), deduplicated
		Set<String> placedIds = new HashSet<>(gridState.values());
		Set<String> toAssignSet = new HashSet<>();
		for (String pos : revealedPositions)
		{
			String norm = normalizePos(pos);
			if ("0,0".equals(norm)) continue;
			if (!gridState.containsKey(norm))
				toAssignSet.add(norm);
		}
		List<String> toAssign = new ArrayList<>(toAssignSet);

		// 5. Available = tiles (not center) not yet placed, with prerequisites satisfied (quest/boss: prereqs claimed; else unlocked or claimed).
		// Skill tiles: each resolved prerequisite must be claimed, revealed on the grid, or unlocked (see {@link #prerequisitesSatisfied}).
		Set<String> satisfiedIds = new HashSet<>(claimedIds);
		satisfiedIds.addAll(unlockedIds);
		Set<String> revealedTileIds = new HashSet<>(gridState.values());
		List<WorldUnlockTile> available = new ArrayList<>();
		for (WorldUnlockTile t : all)
		{
			if (t == centerTile) continue;
			if (placedIds.contains(t.getId())) continue;
			if (!prerequisitesSatisfied(t, satisfiedIds, claimedIds, revealedTileIds)) continue;
			available.add(t);
		}

		// 6. Neighbor areas (from areas.json): only allow area tiles that neighbor an unlocked area (or the starter center).
		// Revealed-but-not-unlocked area tiles on the grid do not expand the neighbor frontier.
		Set<String> neighborAreaIds = getNeighborAreaIdsOfUnlockedAreas(centerTile);

		// 7. Partition available into skill, quest, boss, and area (and other). Progression: quests first, then bosses, then areas when filling non-skill slots.
		List<WorldUnlockTile> skillTiles = new ArrayList<>();
		List<WorldUnlockTile> questTiles = new ArrayList<>();
		List<WorldUnlockTile> bossTiles = new ArrayList<>();
		List<WorldUnlockTile> areaTiles = new ArrayList<>();
		List<WorldUnlockTile> otherNonSkillTiles = new ArrayList<>();
		for (WorldUnlockTile t : available)
		{
			if (WorldUnlockTileType.SKILL.equals(t.getType()))
				skillTiles.add(t);
			else if (WorldUnlockTileType.QUEST.equals(t.getType()))
				questTiles.add(t);
			else if (WorldUnlockTileType.BOSS.equals(t.getType()))
				bossTiles.add(t);
			else if (WorldUnlockTileType.AREA.equals(t.getType()))
				areaTiles.add(t);
			else
				otherNonSkillTiles.add(t);
		}
		int seed = getGridSeed();
		Random rng = new Random(seed);
		// Order areas: neighbor areas first, then others
		areaTiles = orderNeighborAreasFirst(areaTiles, neighborAreaIds, rng);
		Collections.shuffle(skillTiles, rng);
		Collections.shuffle(questTiles, rng);
		Collections.shuffle(bossTiles, rng);

		// 8. Sort toAssign by spiral order (ring 1 first, then ring 2, then 3+)
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

		// 9. Assign: rings 1–2 = only skill unlocks level 1–10; ring 3+ = weighted: skill, quest/boss (unlocked area prereq) ≥ neighbor area.
		for (int[] rc : toAssignRc)
		{
			String pos = rc[0] + "," + rc[1];
			int ring = chebyshevDist(rc[0], rc[1], 0, 0);
			WorldUnlockTile chosen = null;

			if (ring <= 2)
			{
				// First two rings: only skill unlocks level 1–10
				List<WorldUnlockTile> skill1To10 = skillTiles.stream().filter(WorldUnlockService::isSkillLevel1To10).collect(Collectors.toList());
				if (!skill1To10.isEmpty())
				{
					chosen = skill1To10.get(rng.nextInt(skill1To10.size()));
					skillTiles.remove(chosen);
				}
			}
			else
			{
				// Ring 3+: weighted roll with eligibility
				double roll = rng.nextDouble();
				List<WorldUnlockTile> questEligible = questTiles.stream().filter(this::hasUnlockedAreaPrerequisite).collect(Collectors.toList());
				List<WorldUnlockTile> questLowestTier = lowestTierTiles(questEligible);
				List<WorldUnlockTile> areaEligible = areaTiles.stream().filter(t -> neighborAreaIds.contains(t.getId())).collect(Collectors.toList());
				List<WorldUnlockTile> areaLowestTier = lowestTierTiles(areaEligible);
				// Boss tiles here already satisfy prerequisites via claimed ids; do not require a direct area prereq on the
				// boss (e.g. Brutus only lists quest "Ides of Milk", which would wrongly exclude it from the boss roll).
				List<WorldUnlockTile> bossEligible = new ArrayList<>(bossTiles);
				List<WorldUnlockTile> bossLowestTier = lowestTierTiles(bossEligible);
				List<WorldUnlockTile> diaryEligible = otherNonSkillTiles.stream()
					.filter(t -> WorldUnlockTileType.ACHIEVEMENT_DIARY.equals(t.getType()) && hasUnlockedAreaPrerequisite(t))
					.collect(Collectors.toList());
				List<WorldUnlockTile> diaryLowestTier = lowestTierTiles(diaryEligible);

				// Cumulative thresholds: 65% skill, 12% quest, 10% neighbor area, 10% boss, 3% diary (quest/boss ≥ area).
				if (roll < 0.65 && !skillTiles.isEmpty())
					chosen = skillTiles.remove(rng.nextInt(skillTiles.size()));
				else if (roll < 0.77 && !questEligible.isEmpty())
					chosen = chooseFromLowestTier(questTiles, questEligible, questLowestTier, rng);
				else if (roll < 0.87 && !areaEligible.isEmpty())
					chosen = chooseFromLowestTier(areaTiles, areaEligible, areaLowestTier, rng);
				else if (roll < 0.97 && !bossEligible.isEmpty())
					chosen = chooseFromLowestTier(bossTiles, bossEligible, bossLowestTier, rng);
				else if (!diaryEligible.isEmpty())
					chosen = chooseFromLowestTier(otherNonSkillTiles, diaryEligible, diaryLowestTier, rng);

				// Fallback if rolled category was empty: skill → quest → area → boss → diary
				if (chosen == null && !skillTiles.isEmpty())
					chosen = skillTiles.remove(rng.nextInt(skillTiles.size()));
				if (chosen == null && !questEligible.isEmpty())
					chosen = chooseFromLowestTier(questTiles, questEligible, questLowestTier, rng);
				if (chosen == null && !areaEligible.isEmpty())
					chosen = chooseFromLowestTier(areaTiles, areaEligible, areaLowestTier, rng);
				if (chosen == null && !bossEligible.isEmpty())
					chosen = chooseFromLowestTier(bossTiles, bossEligible, bossLowestTier, rng);
				if (chosen == null && !diaryEligible.isEmpty())
					chosen = chooseFromLowestTier(otherNonSkillTiles, diaryEligible, diaryLowestTier, rng);
			}

			// Final fallback: always assign a tile to every revealed position so no empty gaps appear
			if (chosen == null)
			{
				List<WorldUnlockTile> stillAvailable = new ArrayList<>();
				for (WorldUnlockTile t : all)
				{
					if (t == centerTile) continue;
					if (placedIds.contains(t.getId())) continue;
					if (!prerequisitesSatisfied(t, satisfiedIds, claimedIds, revealedTileIds)) continue;
					// Never allow non-neighbor areas to populate, even as a fallback.
					// This ensures the first revealed areas near the starter come from areas.json neighbor relationships.
					if (WorldUnlockTileType.AREA.equals(t.getType()) && !neighborAreaIds.contains(t.getId())) continue;
					stillAvailable.add(t);
				}
				if (!stillAvailable.isEmpty())
				{
					chosen = stillAvailable.get(rng.nextInt(stillAvailable.size()));
					skillTiles.remove(chosen);
					questTiles.remove(chosen);
					areaTiles.remove(chosen);
					bossTiles.remove(chosen);
					otherNonSkillTiles.remove(chosen);
				}
			}

			if (chosen != null)
			{
				gridState.put(pos, chosen.getId());
				placedIds.add(chosen.getId());
			}
		}

		saveGridState(gridState);

		// 10. Output: center + all placements from grid state (sorted by position)
		List<WorldUnlockTilePlacement> grid = new ArrayList<>();
		grid.add(new WorldUnlockTilePlacement(centerTile, 0, 0));
		List<String> positionsSorted = new ArrayList<>(gridState.keySet());
		positionsSorted.sort((a, b) -> {
			int[] ar = parsePos(a), br = parsePos(b);
			if (ar == null || br == null) return 0;
			return byDistFromCenter.compare(ar, br);
		});
		for (String posStr : positionsSorted)
		{
			int[] rc = parsePos(posStr);
			if (rc == null) continue;
			String tileId = gridState.get(posStr);
			WorldUnlockTile tile = getTileById(tileId);
			if (tile != null)
				grid.add(new WorldUnlockTilePlacement(tile, rc[0], rc[1]));
		}
		return grid;
	}

	/**
	 * Area ids that are neighbors (in areas.json) of any <b>unlocked</b> area world-unlock tile, plus neighbors of the
	 * starter center when it is an area. Does not use merely revealed (assigned but not unlocked) area tiles on the grid.
	 * Neighbor ids that are already unlocked are excluded.
	 */
	private Set<String> getNeighborAreaIdsOfUnlockedAreas(WorldUnlockTile centerTile)
	{
		Set<String> sourceAreaIds = new HashSet<>();
		for (String tileId : unlockedIds)
		{
			WorldUnlockTile tile = getTileById(tileId);
			if (tile != null && WorldUnlockTileType.AREA.equals(tile.getType()))
				sourceAreaIds.add(tileId);
		}
		if (centerTile != null && WorldUnlockTileType.AREA.equals(centerTile.getType()) && centerTile.getId() != null)
			sourceAreaIds.add(centerTile.getId());

		Set<String> neighborIds = new HashSet<>();
		for (String areaId : sourceAreaIds)
		{
			Area area = areaGraphService.getArea(areaId);
			if (area == null || area.getNeighbors() == null)
				continue;
			for (String n : area.getNeighbors())
			{
				if (n != null && !n.isEmpty() && !unlockedIds.contains(n))
					neighborIds.add(n);
			}
		}
		return neighborIds;
	}

	/** Puts tiles that are area type and whose id is in neighborAreaIds at the front, then shuffles each part. */
	private static List<WorldUnlockTile> orderNeighborAreasFirst(List<WorldUnlockTile> list, Set<String> neighborAreaIds, Random rng)
	{
		List<WorldUnlockTile> neighbor = new ArrayList<>();
		List<WorldUnlockTile> other = new ArrayList<>();
		for (WorldUnlockTile t : list)
		{
			if (WorldUnlockTileType.AREA.equals(t.getType()) && neighborAreaIds.contains(t.getId()))
				neighbor.add(t);
			else
				other.add(t);
		}
		Collections.shuffle(neighbor, rng);
		Collections.shuffle(other, rng);
		List<WorldUnlockTile> out = new ArrayList<>(neighbor);
		out.addAll(other);
		return out;
	}

	/** Removes the given tile from the list and returns it. */
	private static WorldUnlockTile removeRandom(List<WorldUnlockTile> list, WorldUnlockTile tile, Random rng)
	{
		int i = list.indexOf(tile);
		if (i < 0) return null;
		return list.remove(i);
	}

	/** Puts tiles that are area type and whose id is in neighborAreaIds at the end, then shuffles each part. Used for ring 1–2 so skills 1–10 fill first. */
	private static List<WorldUnlockTile> orderNeighborAreasLast(List<WorldUnlockTile> list, Set<String> neighborAreaIds, Random rng)
	{
		List<WorldUnlockTile> other = new ArrayList<>();
		List<WorldUnlockTile> neighbor = new ArrayList<>();
		for (WorldUnlockTile t : list)
		{
			if (WorldUnlockTileType.AREA.equals(t.getType()) && neighborAreaIds.contains(t.getId()))
				neighbor.add(t);
			else
				other.add(t);
		}
		Collections.shuffle(other, rng);
		Collections.shuffle(neighbor, rng);
		List<WorldUnlockTile> out = new ArrayList<>(other);
		out.addAll(neighbor);
		return out;
	}

	/** Returns all tiles from eligible with the lowest tier present. */
	private static List<WorldUnlockTile> lowestTierTiles(List<WorldUnlockTile> eligible)
	{
		if (eligible == null || eligible.isEmpty())
			return Collections.emptyList();
		int minTier = eligible.stream().mapToInt(t -> t.getTier() > 0 ? t.getTier() : Integer.MAX_VALUE).min().orElse(Integer.MAX_VALUE);
		if (minTier == Integer.MAX_VALUE)
			return Collections.emptyList();
		return eligible.stream().filter(t -> t.getTier() == minTier).collect(Collectors.toList());
	}

	/**
	 * Chooses from the lowest-tier eligible set when possible, otherwise from any eligible tile.
	 * Used for quests, neighbor areas, bosses, and diaries so tier 1 appears before higher tiers when multiple are eligible.
	 */
	private static WorldUnlockTile chooseFromLowestTier(List<WorldUnlockTile> source, List<WorldUnlockTile> eligible,
		List<WorldUnlockTile> lowestTierEligible, Random rng)
	{
		List<WorldUnlockTile> pickFrom = (lowestTierEligible != null && !lowestTierEligible.isEmpty()) ? lowestTierEligible : eligible;
		if (pickFrom == null || pickFrom.isEmpty())
			return null;
		WorldUnlockTile chosen = pickFrom.get(rng.nextInt(pickFrom.size()));
		return removeRandom(source, chosen, rng);
	}

	/**
	 * Returns the skill tile id whose level band contains the given level (e.g. 50 and "Agility" -> "agility_41_50").
	 * Used so task requirements like "50 Agility" only populate when that skill bracket is unlocked.
	 */
	public String getSkillTileIdForLevel(String skillName, int level)
	{
		if (skillName == null || (skillName = skillName.trim()).isEmpty()) return null;
		if (!loaded) load();
		for (WorldUnlockTile t : tiles)
		{
			if (!WorldUnlockTileType.SKILL.equals(t.getType())) continue;
			TaskLink link = t.getTaskLink();
			if (link == null || !skillName.equalsIgnoreCase(link.getSkillName())) continue;
			Integer min = link.getLevelMin();
			Integer max = link.getLevelMax();
			if (min != null && max != null && level >= min && level <= max)
				return t.getId();
		}
		return null;
	}

	/**
	 * Resolves a prerequisite or requirement string to a World Unlock tile id.
	 * If the string matches (case-insensitive) any tile's id or displayName, returns that tile's id.
	 * Used so prerequisites/requirements can reference areas, quests, or bosses by id or display name.
	 */
	public String resolvePrerequisiteToTileId(String prereq)
	{
		if (prereq == null || (prereq = prereq.trim()).isEmpty()) return null;
		if (!loaded) load();
		for (WorldUnlockTile t : tiles)
		{
			if (prereq.equalsIgnoreCase(t.getId())) return t.getId();
			if (t.getDisplayName() != null && prereq.equalsIgnoreCase(t.getDisplayName().trim())) return t.getId();
		}
		return null;
	}

	/**
	 * True only when all prerequisites are satisfied (AND logic).
	 * For {@link WorldUnlockTileType#QUEST} and {@link WorldUnlockTileType#BOSS} tiles, each prerequisite that resolves
	 * to an unlock tile id must be in {@code claimedIds}.
	 * For {@link WorldUnlockTileType#SKILL} tiles, each resolved prerequisite must be {@linkplain #claim claimed},
	 * assigned on the grid ({@code revealedTileIds}), or {@linkplain #unlock unlocked}.
	 * Other tile types: each prereq must be in {@code satisfiedIds} (typically unlocked ∪ claimed).
	 */
	private boolean prerequisitesSatisfied(WorldUnlockTile tile, Set<String> satisfiedIds, Set<String> claimedIds,
		Set<String> revealedTileIds)
	{
		if (tile.getPrerequisites() == null || tile.getPrerequisites().isEmpty())
			return true;
		boolean requireClaimedPrereqs = WorldUnlockTileType.QUEST.equals(tile.getType())
			|| WorldUnlockTileType.BOSS.equals(tile.getType());
		boolean skillTile = WorldUnlockTileType.SKILL.equals(tile.getType());
		return tile.getPrerequisites().stream().allMatch(prereq -> {
			String tileId = resolvePrerequisiteToTileId(prereq);
			String toCheck = (tileId != null) ? tileId : prereq.trim();
			if (requireClaimedPrereqs && tileId != null)
				return claimedIds.contains(tileId);
			if (skillTile && tileId != null)
				return claimedIds.contains(tileId) || revealedTileIds.contains(tileId) || unlockedIds.contains(tileId);
			return satisfiedIds.contains(toCheck);
		});
	}

	/** Same id resolution as {@link #prerequisitesSatisfied}; prerequisites may use display name or id. */
	private boolean isPrerequisiteUnlocked(String prereq)
	{
		if (prereq == null) return false;
		String tileId = resolvePrerequisiteToTileId(prereq);
		String id = tileId != null ? tileId : prereq.trim();
		return unlockedIds.contains(id);
	}

	private static int chebyshevDist(int r1, int c1, int r2, int c2)
	{
		return Math.max(Math.abs(r1 - r2), Math.abs(c1 - c2));
	}

	/** Level band index for skill tiles: 0 = 1-10, 1 = 11-20, ..., 9 = 91-99. Returns -1 if not a skill tile. */
	private static int getSkillLevelBand(WorldUnlockTile t)
	{
		if (!WorldUnlockTileType.SKILL.equals(t.getType())) return -1;
		TaskLink link = t.getTaskLink();
		if (link == null) return -1;
		Integer levelMax = link.getLevelMax();
		if (levelMax == null) return -1;
		return (levelMax - 1) / 10;
	}

	/** True if tile is a skill unlock, tier 1, with level band 1-10 (levelMax <= 10). */
	private static boolean isSkillLevel1To10(WorldUnlockTile t)
	{
		return getSkillLevelBand(t) == 0 && t.getTier() == 1;
	}

	/** True if at least one prerequisite resolves to an area tile that is currently unlocked on the world unlock grid. */
	private boolean hasUnlockedAreaPrerequisite(WorldUnlockTile t)
	{
		if (t.getPrerequisites() == null || t.getPrerequisites().isEmpty()) return false;
		for (String prereq : t.getPrerequisites())
		{
			String tileId = resolvePrerequisiteToTileId(prereq);
			String id = tileId != null ? tileId : prereq.trim();
			if (!unlockedIds.contains(id)) continue;
			WorldUnlockTile wt = getTileById(id);
			if (wt != null && WorldUnlockTileType.AREA.equals(wt.getType()))
				return true;
		}
		return false;
	}

	/** Spiral order for one ring (same as TaskGridService). Ring 1 = 8 cells, ring 2 = 16, etc. */
	private static List<int[]> spiralOrderForRing(int tier)
	{
		List<int[]> out = new ArrayList<>(8 * tier);
		for (int c = 1 - tier; c <= tier; c++) out.add(new int[]{ tier, c });
		for (int r = tier - 1; r >= -tier; r--) out.add(new int[]{ r, tier });
		for (int c = tier - 1; c >= -tier; c--) out.add(new int[]{ -tier, c });
		for (int r = -tier + 1; r <= tier; r++) out.add(new int[]{ r, -tier });
		return out;
	}

	/**
	 * True if this tile is revealed. Center (0,0) is always revealed; any other tile is revealed
	 * only when at least one cardinal neighbor is claimed (unlocked + action done + claimed).
	 * Quest and boss tiles stay hidden until every prerequisite unlock tile is {@linkplain #claim claimed}, not merely unlocked.
	 * Skill tiles additionally require each prerequisite to be claimed, on the grid, or unlocked (same as population rules).
	 */
	public boolean isRevealed(WorldUnlockTilePlacement placement, Set<String> claimed, List<WorldUnlockTilePlacement> grid)
	{
		int row = placement.getRow(), col = placement.getCol();
		if (row == 0 && col == 0)
			return true; // center (starter) is always revealed

		java.util.Map<String, String> posToId = new java.util.HashMap<>();
		for (WorldUnlockTilePlacement p : grid)
			posToId.put(p.getRow() + "," + p.getCol(), p.getTile().getId());

		java.util.Set<String> claimedNeighborPositions = new java.util.HashSet<>();
		for (String nPos : GridPos.neighbors4(row, col))
		{
			String neighborId = posToId.get(nPos);
			if (neighborId != null && claimed.contains(neighborId))
				claimedNeighborPositions.add(nPos);
		}
		if (!RevealLogic.revealedByClaimedPositions(row, col, claimedNeighborPositions))
			return false;
		WorldUnlockTile tile = placement.getTile();
		if (tile != null && (WorldUnlockTileType.QUEST.equals(tile.getType()) || WorldUnlockTileType.BOSS.equals(tile.getType())))
			return resolvedPrerequisitesAllClaimed(tile, claimed);
		if (tile != null && WorldUnlockTileType.SKILL.equals(tile.getType()))
			return skillPrerequisitesRevealedOrClaimed(tile, claimed, grid);
		return true;
	}

	/**
	 * Skill unlocks: prerequisite satisfied if claimed, placed on the grid, or unlocked (matches {@link #prerequisitesSatisfied}).
	 */
	private boolean skillPrerequisitesRevealedOrClaimed(WorldUnlockTile tile, Set<String> claimedIds,
		List<WorldUnlockTilePlacement> grid)
	{
		if (tile.getPrerequisites() == null || tile.getPrerequisites().isEmpty())
			return true;
		Set<String> revealedTileIds = new HashSet<>();
		for (WorldUnlockTilePlacement p : grid)
		{
			if (p != null && p.getTile() != null && p.getTile().getId() != null)
				revealedTileIds.add(p.getTile().getId());
		}
		Set<String> satisfiedIds = new HashSet<>(claimedIds);
		satisfiedIds.addAll(unlockedIds);
		return tile.getPrerequisites().stream().allMatch(prereq -> {
			String tileId = resolvePrerequisiteToTileId(prereq);
			String toCheck = (tileId != null) ? tileId : prereq.trim();
			if (tileId != null)
				return claimedIds.contains(tileId) || revealedTileIds.contains(tileId) || unlockedIds.contains(tileId);
			return satisfiedIds.contains(toCheck);
		});
	}

	/** For quest/boss tiles: every prerequisite that maps to an unlock tile id must be in {@code claimedIds}. */
	private boolean resolvedPrerequisitesAllClaimed(WorldUnlockTile tile, Set<String> claimedIds)
	{
		if (tile.getPrerequisites() == null || tile.getPrerequisites().isEmpty())
			return true;
		return tile.getPrerequisites().stream().allMatch(prereq -> {
			String tileId = resolvePrerequisiteToTileId(prereq);
			if (tileId != null)
				return claimedIds.contains(tileId);
			return true;
		});
	}

	/** Returns the set of claimed tile ids (unlocked and action completed). Only claimed tiles reveal adjacent positions. */
	public Set<String> getClaimedIds()
	{
		if (!loaded) load();
		return Collections.unmodifiableSet(new HashSet<>(claimedIds));
	}

	/**
	 * Marks the tile as claimed (action completed). Call after the player has unlocked and completed the tile's action.
	 * Only unlocked tiles can be claimed. Claiming reveals adjacent tiles. Returns true on success.
	 */
	public boolean claim(String tileId)
	{
		if (!loaded) load();
		if (tileId == null || !unlockedIds.contains(tileId))
			return false;
		if (claimedIds.contains(tileId))
			return true; // already claimed
		claimedIds.add(tileId);
		persistClaimed();
		return true;
	}

	/** Returns all unlock tiles from world_unlocks.json (after load()). */
	public List<WorldUnlockTile> getTiles()
	{
		if (!loaded)
		{
			load();
		}
		return Collections.unmodifiableList(tiles);
	}

	/** Returns the set of unlocked tile ids. */
	public Set<String> getUnlockedIds()
	{
		if (!loaded)
		{
			load();
		}
		return Collections.unmodifiableSet(new HashSet<>(unlockedIds));
	}

	/**
	 * Ensures the configured starter area tile is unlocked (and claimed, if it is an area tile) when it is free
	 * (cost 0, no prerequisites). Returns the starter tile id if it was newly unlocked, else null.
	 *
	 * This is used to keep the world unlock grid, area overlay, and point totals aligned with the configured starting
	 * area and starting points without requiring an extra manual "Unlock (Free)" click after reset.
	 */
	public String ensureStarterAreaUnlockedIfFree()
	{
		if (!loaded) load();
		String startId = config.startingArea();
		if (startId == null || startId.trim().isEmpty()) return null;
		WorldUnlockTile starter = getTileById(startId.trim());
		if (starter == null) return null;
		if (unlockedIds.contains(starter.getId())) return null;
		if (getTileCost(starter) != 0) return null;
		if (starter.getPrerequisites() != null && !starter.getPrerequisites().isEmpty()) return null;

		unlockedIds.add(starter.getId());
		persistUnlocked();
		// Keep behaviour consistent with unlock(): area tiles are claimed immediately on unlock.
		if (WorldUnlockTileType.AREA.equals(starter.getType()))
		{
			claimedIds.add(starter.getId());
			persistClaimed();
		}
		return starter.getId();
	}

	/**
	 * Returns tile ids that are either unlocked or revealed on the World Unlock grid (same visibility as
	 * {@link #isRevealed}, including quest/boss and skill prerequisite rules).
	 * Used so task requirements like "[skill] [bracket]" (e.g. "Agility 41-50") allow the task
	 * to populate once that skill unlock is visible (revealed), not only when it is unlocked.
	 */
	public Set<String> getUnlockedOrRevealedTileIds()
	{
		Set<String> out = new HashSet<>(getUnlockedIds());
		Set<String> claimed = getClaimedIds();
		List<WorldUnlockTilePlacement> grid = getGrid();
		for (WorldUnlockTilePlacement p : grid)
		{
			if (p == null || p.getTile() == null || p.getTile().getId() == null) continue;
			if (!isRevealed(p, claimed, grid)) continue;
			out.add(p.getTile().getId());
		}
		return out;
	}

	/** Returns the tile with the given id, or null. */
	public WorldUnlockTile getTileById(String id)
	{
		if (!loaded)
		{
			load();
		}
		return tiles.stream().filter(t -> id.equals(t.getId())).findFirst().orElse(null);
	}

	/** Display name for the configured starter area (from areas.json when available), for use in the World Unlock panel. */
	public String getStarterAreaDisplayName()
	{
		String startId = config.startingArea();
		if (startId == null || startId.isEmpty()) return "";
		Area area = areaGraphService.getArea(startId);
		if (area != null && area.getDisplayName() != null && !area.getDisplayName().isEmpty())
			return area.getDisplayName();
		WorldUnlockTile tile = getTileById(startId);
		if (tile != null && tile.getDisplayName() != null && !tile.getDisplayName().isEmpty())
			return tile.getDisplayName();
		return startId;
	}

	/**
	 * Returns the point cost to unlock this tile when in World Unlock mode.
	 * Cost = tile tier × tier points (config) × per-type multiplier for this tile's tier band (config).
	 * Example: tile tier 2, 25 pts, multiplier 4 → 2 × 25 × 4 = 200. The starter area tile returns 0.
	 * Tile tier &gt; 5 uses the tier-5 multiplier row.
	 */
	public int getTileCost(WorldUnlockTile tile)
	{
		if (tile == null) return 0;
		String startArea = config.startingArea();
		if (startArea != null && startArea.equals(tile.getId()))
			return 0;
		int tier = Math.max(1, tile.getTier());
		int tierPoints = getTierPoints(tile.getTier());
		int multiplierBand = Math.min(5, tier);
		int multiplier = getMultiplier(tile.getType(), multiplierBand);
		return tier * tierPoints * multiplier;
	}

	private int getTierPoints(int tier)
	{
		switch (tier)
		{
			case 1: return config.taskTier1Points();
			case 2: return config.taskTier2Points();
			case 3: return config.taskTier3Points();
			case 4: return config.taskTier4Points();
			case 5: return config.taskTier5Points();
			default: return Math.max(1, tier);
		}
	}

	/** @param tierBand tile tier clamped to 1–5 (callers pass {@code Math.min(5, tileTier)}) */
	private int getMultiplier(String type, int tierBand)
	{
		int t = Math.min(5, Math.max(1, tierBand));
		if (type == null) return 1;
		switch (type)
		{
			case WorldUnlockTileType.SKILL:
				return skillMultiplierForTier(t);
			case WorldUnlockTileType.AREA:
				return areaMultiplierForTier(t);
			case WorldUnlockTileType.BOSS:
				return bossMultiplierForTier(t);
			case WorldUnlockTileType.QUEST:
				return questMultiplierForTier(t);
			case WorldUnlockTileType.ACHIEVEMENT_DIARY:
				return achievementDiaryMultiplierForTier(t);
			case WorldUnlockTileType.TASK_FILTER:
			default:
				return skillMultiplierForTier(t);
		}
	}

	/**
	 * Per-tier multiplier from config storage, then tier-1 legacy globals, then interface default (for migration from
	 * pre–per-tier {@code worldUnlockSkillMultiplier}, etc.).
	 */
	private int readWorldUnlockMultiplier(int tier, int typeIndex, int interfaceDefault)
	{
		String key = "worldUnlockTier" + tier + WORLD_UNLOCK_TYPE_SUFFIX[typeIndex] + "Multiplier";
		String raw = configManager.getConfiguration(PLUGIN_CONFIG_GROUP, key);
		if (raw != null && !raw.trim().isEmpty())
		{
			try
			{
				return Math.max(1, Math.min(99, Integer.parseInt(raw.trim())));
			}
			catch (NumberFormatException ignored)
			{
			}
		}
		if (tier == 1 && typeIndex >= 0 && typeIndex < WORLD_UNLOCK_LEGACY_KEYS.length)
		{
			raw = configManager.getConfiguration(PLUGIN_CONFIG_GROUP, WORLD_UNLOCK_LEGACY_KEYS[typeIndex]);
			if (raw != null && !raw.trim().isEmpty())
			{
				try
				{
					return Math.max(1, Math.min(99, Integer.parseInt(raw.trim())));
				}
				catch (NumberFormatException ignored)
				{
				}
			}
		}
		return Math.max(1, Math.min(99, interfaceDefault));
	}

	private int skillMultiplierForTier(int t)
	{
		switch (t)
		{
			case 1: return readWorldUnlockMultiplier(1, 0, config.worldUnlockTier1SkillMultiplier());
			case 2: return readWorldUnlockMultiplier(2, 0, config.worldUnlockTier2SkillMultiplier());
			case 3: return readWorldUnlockMultiplier(3, 0, config.worldUnlockTier3SkillMultiplier());
			case 4: return readWorldUnlockMultiplier(4, 0, config.worldUnlockTier4SkillMultiplier());
			default: return readWorldUnlockMultiplier(5, 0, config.worldUnlockTier5SkillMultiplier());
		}
	}

	private int areaMultiplierForTier(int t)
	{
		switch (t)
		{
			case 1: return readWorldUnlockMultiplier(1, 1, config.worldUnlockTier1AreaMultiplier());
			case 2: return readWorldUnlockMultiplier(2, 1, config.worldUnlockTier2AreaMultiplier());
			case 3: return readWorldUnlockMultiplier(3, 1, config.worldUnlockTier3AreaMultiplier());
			case 4: return readWorldUnlockMultiplier(4, 1, config.worldUnlockTier4AreaMultiplier());
			default: return readWorldUnlockMultiplier(5, 1, config.worldUnlockTier5AreaMultiplier());
		}
	}

	private int bossMultiplierForTier(int t)
	{
		switch (t)
		{
			case 1: return readWorldUnlockMultiplier(1, 2, config.worldUnlockTier1BossMultiplier());
			case 2: return readWorldUnlockMultiplier(2, 2, config.worldUnlockTier2BossMultiplier());
			case 3: return readWorldUnlockMultiplier(3, 2, config.worldUnlockTier3BossMultiplier());
			case 4: return readWorldUnlockMultiplier(4, 2, config.worldUnlockTier4BossMultiplier());
			default: return readWorldUnlockMultiplier(5, 2, config.worldUnlockTier5BossMultiplier());
		}
	}

	private int questMultiplierForTier(int t)
	{
		switch (t)
		{
			case 1: return readWorldUnlockMultiplier(1, 3, config.worldUnlockTier1QuestMultiplier());
			case 2: return readWorldUnlockMultiplier(2, 3, config.worldUnlockTier2QuestMultiplier());
			case 3: return readWorldUnlockMultiplier(3, 3, config.worldUnlockTier3QuestMultiplier());
			case 4: return readWorldUnlockMultiplier(4, 3, config.worldUnlockTier4QuestMultiplier());
			default: return readWorldUnlockMultiplier(5, 3, config.worldUnlockTier5QuestMultiplier());
		}
	}

	private int achievementDiaryMultiplierForTier(int t)
	{
		switch (t)
		{
			case 1: return readWorldUnlockMultiplier(1, 4, config.worldUnlockTier1AchievementDiaryMultiplier());
			case 2: return readWorldUnlockMultiplier(2, 4, config.worldUnlockTier2AchievementDiaryMultiplier());
			case 3: return readWorldUnlockMultiplier(3, 4, config.worldUnlockTier3AchievementDiaryMultiplier());
			case 4: return readWorldUnlockMultiplier(4, 4, config.worldUnlockTier4AchievementDiaryMultiplier());
			default: return readWorldUnlockMultiplier(5, 4, config.worldUnlockTier5AchievementDiaryMultiplier());
		}
	}

	/**
	 * Unlocks the tile with the given id if prerequisites are met and cost can be spent.
	 * Returns true on success.
	 */
	public boolean unlock(String tileId, int cost)
	{
		if (!loaded)
		{
			load();
		}
		WorldUnlockTile tile = getTileById(tileId);
		if (tile == null)
		{
			return false;
		}
		if (unlockedIds.contains(tileId))
		{
			return true; // already unlocked
		}
		if (tile.getPrerequisites() != null)
		{
			for (String prereq : tile.getPrerequisites())
			{
				if (!isPrerequisiteUnlocked(prereq))
				{
					return false;
				}
			}
		}
		if (cost > 0 && !pointsService.spend(cost))
		{
			return false;
		}
		unlockedIds.add(tileId);
		persistUnlocked();
		incrementGridSeed();
		// Area and boss tiles: unlock and claim in one action (no separate "complete action then claim" step)
		if (WorldUnlockTileType.AREA.equals(tile.getType()) || WorldUnlockTileType.BOSS.equals(tile.getType()))
		{
			claimedIds.add(tileId);
			persistClaimed();
		}
		return true;
	}

	/** Returns true if all prerequisites of the tile are unlocked. */
	public boolean isUnlockable(WorldUnlockTile tile)
	{
		if (tile.getPrerequisites() == null || tile.getPrerequisites().isEmpty())
		{
			return true;
		}
		return tile.getPrerequisites().stream().allMatch(this::isPrerequisiteUnlocked);
	}

	/** Returns tiles that are not yet unlocked and whose prerequisites are satisfied. */
	public List<WorldUnlockTile> getUnlockableTiles()
	{
		if (!loaded)
		{
			load();
		}
		return tiles.stream()
			.filter(t -> !unlockedIds.contains(t.getId()))
			.filter(this::isUnlockable)
			.collect(Collectors.toList());
	}

	/** Clears all unlocked and claimed world-unlock ids and grid state (e.g. on reset). Next getGrid() will re-roll from center. */
	public void clearUnlocked()
	{
		unlockedIds.clear();
		claimedIds.clear();
		persistUnlocked();
		persistClaimed();
		configManager.unsetConfiguration(STATE_GROUP, KEY_WORLD_UNLOCK_GRID_STATE);
	}

	private static final java.util.regex.Pattern DIARY_SUFFIX = java.util.regex.Pattern.compile("_(easy|medium|hard|elite)$");

	/** Diary key from achievement_diary tile id (e.g. varrock_easy -> varrock, lumbridge_draynor_elite -> lumbridge_draynor). */
	public static String getDiaryKeyFromTileId(String tileId)
	{
		if (tileId == null) return null;
		return DIARY_SUFFIX.matcher(tileId).replaceFirst("");
	}

	/** Fallback: area ids that belong to a diary region but are not prerequisites of the diary tile. */
	private static final Map<String, String> AREA_TO_DIARY_FALLBACK;
	static
	{
		Map<String, String> m = new HashMap<>();
		// Kandarin
		m.put("hemenster", "kandarin");
		m.put("catherby", "kandarin");
		m.put("camelot_seers", "kandarin");
		m.put("barbarian_waterfall", "kandarin");
		m.put("piscatoris", "kandarin");
		m.put("yanille", "kandarin");
		m.put("taverley", "kandarin");
		// Karamja (incl. Musa Point, Brimhaven)
		m.put("musa_point", "karamja");
		// Desert
		m.put("nardah_desert", "desert");
		m.put("polnivneach_desert", "desert");
		m.put("sophanem", "desert");
		m.put("uzer_desert", "desert");
		m.put("lassar", "desert");
		m.put("jaldraocht", "desert");
		m.put("necropolis", "desert");
		// Wilderness
		m.put("edgeville", "wilderness");
		m.put("south_central_wilderness", "wilderness");
		m.put("southeast_wilderness", "wilderness");
		m.put("deep_wilderness", "wilderness");
		m.put("northeast_wilderness", "wilderness");
		m.put("north_central_wilderness", "wilderness");
		m.put("northwestern_wilderness", "wilderness");
		m.put("southwestern_wilderness", "wilderness");
		// Morytania
		m.put("port_phasmatys", "morytania");
		m.put("mort_myre_swamp", "morytania");
		m.put("slepe", "morytania");
		m.put("myreditch", "morytania");
		m.put("darkmeyer", "morytania");
		m.put("southern_morytania", "morytania");
		// Western Provinces
		m.put("gnome_stronghold", "western_provinces");
		m.put("ape_atoll", "western_provinces");
		m.put("isafdar", "western_provinces");
		// Fremennik
		m.put("trollheim", "fremennik");
		// Kourend & Kebos
		m.put("hosidius", "kourend_kebos");
		m.put("arceuus", "kourend_kebos");
		m.put("port_piscarilius", "kourend_kebos");
		m.put("lovakengj", "kourend_kebos");
		m.put("shayzien", "kourend_kebos");
		m.put("northern_kourend", "kourend_kebos");
		m.put("kebos_lowlands", "kourend_kebos");
		m.put("kebos_swamp", "kourend_kebos");
		// Falador
		m.put("port_sarim_mudskipper", "falador");
		// Varrock
		m.put("grand_exchange", "varrock");
		AREA_TO_DIARY_FALLBACK = Collections.unmodifiableMap(m);
	}

	/** Single source of truth: achievementDiaryAreaMapping JSON key (normalized lowercase, & -> _) -> tile diary key. Used when default rule does not match tile id. */
	private static final Map<String, String> DIARY_MAPPING_KEY_OVERRIDES;
	static
	{
		Map<String, String> m = new HashMap<>();
		m.put("fremennikprovince", "fremennik");
		m.put("westernprovinces", "western_provinces");
		DIARY_MAPPING_KEY_OVERRIDES = Collections.unmodifiableMap(m);
	}

	/** Normalizes achievementDiaryAreaMapping JSON key to diary key used in tile ids (e.g. Lumbridge&Draynor -> lumbridge_draynor). */
	private static String normalizeDiaryMappingKey(String jsonKey)
	{
		if (jsonKey == null || jsonKey.isEmpty()) return "";
		String s = jsonKey.trim().toLowerCase().replace('&', '_');
		String override = DIARY_MAPPING_KEY_OVERRIDES.get(s);
		return override != null ? override : s;
	}

	/** Loads area_mapping.json and builds diaryKey -> area ids and areaId -> diaryKey. No-op if resource missing. */
	private void ensureAreaMappingLoaded()
	{
		if (diaryKeyToAreaIds != null) return;
		AreaMappingData data = ResourceJsonLoader.load(getClass(), ResourcePaths.AREA_MAPPING_JSON, AreaMappingData.class, new Gson(), log);
		Map<String, List<String>> diaryToAreas = new HashMap<>();
		Map<String, String> areaToDiary = new HashMap<>();
		if (data != null && data.getAchievementDiaryAreaMapping() != null)
		{
			for (Map.Entry<String, List<String>> e : data.getAchievementDiaryAreaMapping().entrySet())
			{
				String normalized = normalizeDiaryMappingKey(e.getKey());
				if (normalized.isEmpty()) continue;
				List<String> areaIds = e.getValue() != null ? e.getValue() : Collections.emptyList();
				diaryToAreas.put(normalized, new ArrayList<>(areaIds));
				for (String areaId : areaIds)
					if (areaId != null && !areaId.trim().isEmpty())
						areaToDiary.put(areaId.trim(), normalized);
			}
		}
		diaryKeyToAreaIds = Collections.unmodifiableMap(diaryToAreas);
		// Merge with tile-based mapping so areas not in JSON still resolve (e.g. from world_unlocks prerequisites)
		Map<String, String> merged = new HashMap<>(buildAreaIdToDiaryKeyFromTiles());
		merged.putAll(areaToDiary);
		areaIdToDiaryKey = Collections.unmodifiableMap(merged);
	}

	/** Builds area id -> diary key from achievement_diary tiles (prerequisites + diary key as area) plus fallback. */
	private Map<String, String> buildAreaIdToDiaryKeyFromTiles()
	{
		Map<String, String> map = new HashMap<>(AREA_TO_DIARY_FALLBACK);
		Set<String> areaTileIds = tiles.stream()
			.filter(t -> WorldUnlockTileType.AREA.equals(t.getType()))
			.map(WorldUnlockTile::getId)
			.collect(Collectors.toSet());
		for (WorldUnlockTile tile : tiles)
		{
			if (!WorldUnlockTileType.ACHIEVEMENT_DIARY.equals(tile.getType())) continue;
			String diaryKey = getDiaryKeyFromTileId(tile.getId());
			if (diaryKey == null) continue;
			if (areaTileIds.contains(diaryKey))
				map.put(diaryKey, diaryKey);
			if (tile.getPrerequisites() != null)
			{
				for (String prereq : tile.getPrerequisites())
					map.put(prereq, diaryKey);
			}
		}
		return map;
	}

	/** Builds area id -> diary key: area_mapping.json first, then tile-based + fallback. */
	private Map<String, String> buildAreaIdToDiaryKey()
	{
		ensureAreaMappingLoaded();
		return areaIdToDiaryKey;
	}

	/** Returns the achievement diary key for an area id (e.g. varrock -> varrock, al_kharid -> desert), or null. Uses area_mapping.json when available. */
	public String getDiaryKeyForAreaId(String areaId)
	{
		if (!loaded) load();
		if (areaId == null || areaId.isEmpty()) return null;
		if (areaIdToDiaryKey == null)
			buildAreaIdToDiaryKey();
		return areaIdToDiaryKey.get(areaId);
	}

	/**
	 * Returns the list of area ids that belong to this achievement diary (from area_mapping.json).
	 * Used to require that at least one of these areas is unlocked before diary tasks can populate.
	 */
	public List<String> getAreaIdsForDiaryKey(String diaryKey)
	{
		if (!loaded) load();
		ensureAreaMappingLoaded();
		List<String> list = diaryKeyToAreaIds.get(diaryKey != null ? diaryKey.trim() : "");
		return list != null ? new ArrayList<>(list) : Collections.emptyList();
	}

	/**
	 * Returns unlocked achievement diary tier keys for Global task gating.
	 * Format: "diaryKey_difficulty" (e.g. varrock_1, desert_2). easy=1, medium=2, hard=3, elite=4.
	 */
	public Set<String> getUnlockedDiaryTierKeys()
	{
		if (!loaded) load();
		Set<String> out = new HashSet<>();
		for (WorldUnlockTile tile : tiles)
		{
			if (!WorldUnlockTileType.ACHIEVEMENT_DIARY.equals(tile.getType()) || !unlockedIds.contains(tile.getId()))
				continue;
			TaskLink link = tile.getTaskLink();
			if (link == null) continue;
			String diaryKey = getDiaryKeyFromTileId(tile.getId());
			if (diaryKey == null) continue;
			int difficulty = link.getDifficulty() != null ? link.getDifficulty() : 1;
			out.add(diaryKey + "_" + difficulty);
		}
		return out;
	}

	/**
	 * Returns the list of task definitions associated with this unlock tile (resolved from taskLink against tasks.json).
	 */
	public List<TaskDefinition> getTasksForUnlock(String tileId)
	{
		WorldUnlockTile tile = getTileById(tileId);
		if (tile == null || tile.getTaskLink() == null)
		{
			return Collections.emptyList();
		}
		List<TaskDefinition> all = taskGridService.getEffectiveDefaultTasks();
		TaskLink link = tile.getTaskLink();
		String linkType = link.getType() != null ? link.getType() : "";

		switch (linkType)
		{
			case WorldUnlockTileType.AREA:
				// Tasks where task.area/areas contains this tile's id (area tile id = area id)
				String areaId = tile.getId();
				return all.stream()
					.filter(t -> t.getRequiredAreaIds().contains(areaId))
					.collect(Collectors.toList());
			case WorldUnlockTileType.SKILL:
				// taskType matches skillName, difficulty matches tier from level band (1-39->1, 40-59->2, 60-79->3, 80-89->4, 90-99->5)
				String skillName = link.getSkillName();
				int tier = levelBandToTier(link.getLevelMin() != null ? link.getLevelMin() : 1);
				return all.stream()
					.filter(t -> (skillName == null || skillName.equalsIgnoreCase(t.getTaskType())) && t.getDifficulty() == tier)
					.collect(Collectors.toList());
			case WorldUnlockTileType.TASK_FILTER:
				return all.stream()
					.filter(t -> matchTaskFilter(tile, t, link))
					.collect(Collectors.toList());
			case "taskDisplayNames":
				if (link.getTaskDisplayNames() == null || link.getTaskDisplayNames().isEmpty())
					return Collections.emptyList();
				java.util.Set<String> names = link.getTaskDisplayNames().stream()
					.map(s -> s != null ? s.trim().toLowerCase() : "")
					.collect(Collectors.toSet());
				return all.stream()
					.filter(t -> t.getDisplayName() != null && names.contains(t.getDisplayName().trim().toLowerCase()))
					.collect(Collectors.toList());
			default:
				return Collections.emptyList();
		}
	}

	private static int levelBandToTier(int level)
	{
		if (level <= 39) return 1;
		if (level <= 59) return 2;
		if (level <= 79) return 3;
		if (level <= 89) return 4;
		return 5;
	}

	private boolean matchTaskFilter(WorldUnlockTile tile, TaskDefinition t, TaskLink link)
	{
		if (link.getTaskType() != null && !link.getTaskType().equalsIgnoreCase(t.getTaskType()))
			return false;
		if (link.getDifficulty() != null && link.getDifficulty() != t.getDifficulty())
			return false;
		// Achievement diary: match by task area -> diary key (task's required area must belong to this diary)
		if (WorldUnlockTileType.ACHIEVEMENT_DIARY.equals(tile.getType()) && com.leaguescape.constants.TaskTypes.ACHIEVEMENT_DIARY.equalsIgnoreCase(link.getTaskType()))
		{
			List<String> areaIds = t.getRequiredAreaIds();
			if (areaIds == null || areaIds.isEmpty()) return false;
			String diaryKey = getDiaryKeyFromTileId(tile.getId());
			if (diaryKey == null) return false;
			boolean anyAreaMatches = areaIds.stream()
				.anyMatch(areaId -> diaryKey.equals(getDiaryKeyForAreaId(areaId)));
			if (!anyAreaMatches) return false;
		}
		else if (link.getRequirementsContains() != null && !link.getRequirementsContains().isEmpty())
		{
			String req = t.getRequirements();
			if (req == null || !req.toLowerCase().contains(link.getRequirementsContains().toLowerCase()))
				return false;
		}
		return true;
	}
}

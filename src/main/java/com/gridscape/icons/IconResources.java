package com.gridscape.icons;

import com.gridscape.constants.TaskTypes;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Centralized icon resource prefixes and lookup maps.
 * Pure constants; no loading or scaling logic.
 */
public final class IconResources
{
	private IconResources() {}

	public static final String TASK_ICONS_RESOURCE_PREFIX = "/com/taskIcons/";
	public static final String BOSS_ICONS_RESOURCE_PREFIX = "/com/bossicons/";
	public static final String AREA_ICONS_RESOURCE_PREFIX = "/com/area_icons/";
	public static final String GENERIC_TASK_ICON = "/com/gridscape/task_icon.png";

	/** Boss unlock tile id -> boss icon filename (e.g. game_icon_barrowschests.png) where id does not match filename. */
	public static final Map<String, String> BOSS_ICON_OVERRIDES;

	/** Local task icon filenames under com/taskIcons/ (no wiki lookup). */
	public static final Map<String, String> TASK_TYPE_LOCAL_ICON;

	/** Area unlock tile id (from areas.json) -> area icon filename in com/area_icons/. */
	public static final Map<String, String> AREA_ICON_FILENAME;

	static
	{
		Map<String, String> boss = new HashMap<>();
		boss.put("abyssal_sire", "game_icon_abyssalsire.png");
		boss.put("alchemical_hydra", "game_icon_alchemicalhydra.png");
		boss.put("amoxliatl", "game_icon_amoxliatl.png");
		boss.put("araaxor", "game_icon_araaxor.png");
		boss.put("artio_callisto", "game_icon_artio.png");
		boss.put("barrows", "game_icon_barrowschests.png");
		boss.put("brutus", "game_icon_brutus.png");
		boss.put("bryophyta", "game_icon_bryophyta.png");
		boss.put("calvarion_vetion", "game_icon_calvarion.png");
		boss.put("cerberus", "game_icon_cerberus.png");
		boss.put("chambers_of_xeric", "game_icon_chambersofxeric.png");
		boss.put("chaos_elemental", "game_icon_chaoselemental.png");
		boss.put("chaos_fanatic", "game_icon_chaosfanatic.png");
		boss.put("commander_zilyana", "game_icon_commanderzilyana.png");
		boss.put("corporeal_beast", "game_icon_corporealbeast.png");
		boss.put("crazy_archaeologist", "game_icon_crazyarchaeologist.png");
		boss.put("dagannoth_kings", "game_icon_dagannothrex.png");
		boss.put("deranged_archaeologist", "game_icon_derangedarchaeologist.png");
		boss.put("doom_of_mokhaiotl", "game_icon_doomofmokhaiotl.png");
		boss.put("duke_sucellus", "game_icon_dukesucellus.png");
		boss.put("gemstone_crab", "game_icon_gemstonecrab.png");
		boss.put("general_graardor", "game_icon_generalgraardor.png");
		boss.put("giant_mole", "game_icon_giantmole.png");
		boss.put("grotesque_guardians", "game_icon_grotesqueguardians.png");
		boss.put("hespori", "game_icon_hespori.png");
		boss.put("kalphite_queen", "game_icon_kalphitequeen.png");
		boss.put("king_black_dragon", "game_icon_kingblackdragon.png");
		boss.put("kraken", "game_icon_kraken.png");
		boss.put("kree'arra", "game_icon_kreearra.png");
		boss.put("kril_tsutsaroth", "game_icon_kriltsutsaroth.png");
		boss.put("moons_of_peril", "game_icon_lunarchests.png");
		boss.put("the_mimic", "game_icon_mimic.png");
		boss.put("nex", "game_icon_nex.png");
		boss.put("the_nightmare", "game_icon_nightmare.png");
		boss.put("obor", "game_icon_obor.png");
		boss.put("phantom_muspah", "game_icon_phantommuspah.png");
		boss.put("sarachnis", "game_icon_sarachnis.png");
		boss.put("scorpia", "game_icon_scorpia.png");
		boss.put("scurrius", "game_icon_scurrius.png");
		boss.put("shellbane_gryphon", "game_icon_shellbanegryphon.png");
		boss.put("skotizo", "game_icon_skotizo.png");
		boss.put("sol_heredit", "game_icon_solheredit.png");
		boss.put("spindel_venenatis", "game_icon_spindel.png");
		boss.put("tempoross", "game_icon_tempoross.png");
		boss.put("theatre_of_blood", "game_icon_theatreofblood.png");
		boss.put("corrupted_hunllef", "game_icon_thecorruptedgauntlet.png");
		boss.put("crystalline_hunllef", "game_icon_thegauntlet.png");
		boss.put("the_hueycoatl", "game_icon_thehueycoatl.png");
		boss.put("the_leviathan", "game_icon_theleviathan.png");
		boss.put("thermonuclear_smoke_devil", "game_icon_thermonuclearsmokedevil.png");
		boss.put("royal_titans", "game_icon_theroyaltitans.png");
		boss.put("the_whisperer", "game_icon_thewhisperer.png");
		boss.put("tombs_of_amascut", "game_icon_tombsofamascutexpertmode.png");
		boss.put("tzkal_zuk", "game_icon_tzkalzuk.png");
		boss.put("tztok_jad", "game_icon_tztokjad.png");
		boss.put("vardorvis", "game_icon_vardorvis.png");
		boss.put("vorkath", "game_icon_vorkath.png");
		boss.put("wintertodt", "game_icon_wintertodt.png");
		boss.put("yama", "game_icon_yama.png");
		boss.put("zalcano", "game_icon_zalcano.png");
		boss.put("zulrah", "game_icon_zulrah.png");
		BOSS_ICON_OVERRIDES = Collections.unmodifiableMap(boss);

		Map<String, String> taskType = new HashMap<>();
		taskType.put(TaskTypes.COMBAT, "Combat_icon_(detail).png");
		taskType.put(TaskTypes.CLUE_SCROLL, "Clue_scroll_v1.png");
		taskType.put(TaskTypes.COLLECTION_LOG, "Collection_log_detail.png");
		taskType.put("Agility", "Agility_icon_(detail).png");
		taskType.put("Construction", "Construction_icon_(detail).png");
		taskType.put("Cooking", "Cooking_icon_(detail).png");
		taskType.put("Crafting", "Crafting_icon_(detail).png");
		taskType.put("Farming", "Farming_icon_(detail).png");
		taskType.put("Firemaking", "Firemaking_icon_(detail).png");
		taskType.put("Fishing", "Fishing_icon_(detail).png");
		taskType.put("Fletching", "Fletching_icon_(detail).png");
		taskType.put("Herblore", "Herblore_icon_(detail).png");
		taskType.put("Hunter", "Hunter_icon_(detail).png");
		taskType.put("Magic", "Magic_icon.png");
		taskType.put("Mining", "Mining_icon_(detail).png");
		taskType.put("Prayer", "Prayer_icon_(detail).png");
		taskType.put("Runecraft", "Runecraft_icon_(detail).png");
		taskType.put("Sailing", "Sailing_icon_(detail).png");
		taskType.put("Slayer", "Slayer_icon_(detail).png");
		taskType.put("Smithing", "Smithing_icon_(detail).png");
		taskType.put("Thieving", "Thieving_icon_(detail).png");
		taskType.put("Woodcutting", "Woodcutting_icon_(detail).png");
		taskType.put(TaskTypes.EQUIPMENT, "Equipment.png");
		taskType.put(TaskTypes.ACTIVITY, "Activity.png");
		taskType.put(TaskTypes.QUEST, "Quest.png");
		taskType.put(TaskTypes.ACHIEVEMENT_DIARY, "Achievement_Diaries.png");
		taskType.put(TaskTypes.DIARY, "Achievement_Diaries.png");
		taskType.put(TaskTypes.OTHER, "Other_icon.png");
		taskType.put(TaskTypes.LEVEL, "Stats_icon.png");
		TASK_TYPE_LOCAL_ICON = Collections.unmodifiableMap(taskType);

		Map<String, String> areas = new HashMap<>();
		// Area icons (keep in sync with bundled images under src/main/resources/com/area_icons/)
		areas.put("southeast_wilderness", "Wilderness_SE_icon.png");
		areas.put("fossil_island", "Fossil_Island_icon.png");
		areas.put("isle_of_souls", "Isle_Of_Souls_icon.png");
		areas.put("silvarea", "Silvarea_icon.png");
		areas.put("slepe", "Slepe_icon.png");
		areas.put("grand_exchange", "Grand_Exchange_icon.png");
		areas.put("edgeville", "Edgeville_icon.png");
		areas.put("necropolis", "Necropolis_icon.png");
		areas.put("lassar", "Lassar_icon.png");
		areas.put("troll_country", "Troll_Country_icon.png");
		areas.put("arceuus", "Arceuus_icon.png");
		areas.put("northern_kourend", "Northern_Kourend_icon.png");
		areas.put("tlati_rainforest", "Tlati_Rainforest_icon.png");
		areas.put("lumbridge", "Lumbridge_icon.png");
		areas.put("ape_atoll", "Ape_Atoll_icon.png");
		areas.put("fremennik_isles", "Fremennik_Isles_icon.png");
		areas.put("deep_wilderness", "Wilderness_Deep_icon.png");
		areas.put("northeast_wilderness", "Wilderness_NE_icon.png");
		areas.put("north_central_wilderness", "Wilderness_NC_icon.png");
		areas.put("northwestern_wilderness", "Wilderness_NW_icon.png");
		areas.put("southwestern_wilderness", "Wilderness_SW_icon.png");
		areas.put("south_central_wilderness", "Wilderness_SC_icon.png");
		areas.put("canifis", "Canifis_icon.png");
		areas.put("haunted_wood", "Haunted_wood_icon.png");
		areas.put("port_phasmatys", "Port_Phasmatys_icon.png");
		areas.put("myreditch", "Myreditch_icon.png");
		areas.put("darkmeyer", "Darkmeyer_icon.png");
		areas.put("southern_morytania", "Southern_Morytania_icon.png");
		areas.put("mort_myre_swamp", "Mort_Myre_swamp_icon.png");
		areas.put("draynor", "Draynor_icon.png");
		areas.put("al_kharid", "Al_Kharid_icon.png");
		areas.put("desert", "Desert_icon.png");
		areas.put("uzer_desert", "Uzer_Desert_icon.png");
		areas.put("nardah_desert", "Nardah_Desert_icon.png");
		areas.put("sophanem", "Sophanem_icon.png");
		areas.put("polnivneach_desert", "Pollnivneach_icon.png");
		areas.put("jaldraocht", "Jaldraocht_icon.png");
		areas.put("ruins_of_unkah", "Ruins_of_Unkah_icon.png");
		areas.put("falador", "Falador_icon.png");
		areas.put("port_sarim_mudskipper", "Port_Sarim_icon.png");
		areas.put("entrana", "Entrana_icon.png");
		areas.put("pest_control", "Pest_Control_icon.png");
		areas.put("musa_point", "Musa_Point_icon.png");
		areas.put("karamja", "Karamja_icon.png");
		areas.put("taverley", "Taverley_icon.png");
		areas.put("burthorpe", "Burthorpe_icon.png");
		areas.put("trollheim", "Trollheim_icon.png");
		areas.put("weiss", "Weiss_icon.png");
		areas.put("rellekka", "Rellekka_icon.png");
		areas.put("camelot_seers", "Camelot_Seers_icon.png");
		areas.put("hemenster", "Hemenster_icon.png");
		areas.put("barbarian_waterfall", "Barbarian_waterfall.png");
		areas.put("gnome_stronghold", "Gnome_stronghold_icon.png");
		areas.put("piscatoris", "Piscatoris_icon.png");
		areas.put("ardougne", "Ardougne_icon.png");
		areas.put("varrock", "Varrock_icon.png");
		areas.put("khazard_battlegrounds", "Khazard_icon.png");
		areas.put("yanille", "Yanille_icon.png");
		areas.put("feldip_hills", "Feldip_hills_icon.png");
		areas.put("corsair_cove", "Corsair_Cove_icon.png");
		areas.put("rimmington", "Rimmington_icon.png");
		areas.put("isafdar", "Isafdar_icon.png");
		areas.put("prifddinas", "Prifddinas_icon.png");
		areas.put("port_piscarilius", "Port_Piscarilius_icon.png");
		areas.put("lovakengj", "Lovakengj_icon.png");
		areas.put("kingstown", "Kingstown_icon.png");
		areas.put("hosidius", "Hosidius_icon.png");
		areas.put("kourend_woodland", "Kourend_woodland_icon.png");
		areas.put("shayzien", "Shayzien_icon.png");
		areas.put("kebos_lowlands", "Kebos_LowLands_icon.png");
		areas.put("kebos_swamp", "Kebos_Swamp_icon.png");
		areas.put("custodia_mountains", "Custodia_Mountains_icon.png");
		areas.put("auburnvale", "Auburnvale_icon.png");
		areas.put("proudspire", "Proudspire_icon.png");
		areas.put("western_varlamore", "Varlamore_W_icon.png");
		areas.put("eastern_varlamore", "Varlamore_E_icon.png");
		areas.put("aldarin", "Aldarin_icon.png");
		areas.put("ardent_ne", "Ardent_NE_icon.png");
		areas.put("ardent_nw", "Ardent_NW_icon.png");
		areas.put("ardent_se", "Ardent_SE_icon.png");
		areas.put("ardent_sw", "Ardent_SW_icon.png");
		areas.put("unquiet", "Unquiet_icon.png");
		areas.put("shrouded_e", "Shrouded_E_icon.png");
		areas.put("shrouded_w", "Shrouded_W_icon.png");
		areas.put("sunset", "Sunset_icon.png");
		areas.put("western_s", "Western_S_icon.png");
		areas.put("western_n", "Western_N_icon.png");
		areas.put("northern_w", "Northern_W_icon.png");
		areas.put("northern_e", "Northern_E_icon.png");
		areas.put("mos_le_harmless", "Mos_Le_harmless_icon.png");
		areas.put("catherby", "Catherby_icon.png");
		AREA_ICON_FILENAME = Collections.unmodifiableMap(areas);
	}
}


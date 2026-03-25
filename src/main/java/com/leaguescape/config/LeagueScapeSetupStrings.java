package com.leaguescape.config;

/**
 * All user-visible copy for the Rules &amp; Setup dialog (tabs, instructions, dialogs).
 * Edit strings here only; UI classes reference these constants.
 */
public final class LeagueScapeSetupStrings
{
	private LeagueScapeSetupStrings() {}

	/** Window title (also shown in the title row). */
	public static final String FRAME_WINDOW_TITLE = "LeagueScape – Rules and Setup";

	public static final String TAB_RULES = "Rules";
	public static final String TAB_GAME_MODE = "Game Mode";
	public static final String TAB_AREA_CONFIGURATION = "Area Configuration";
	public static final String TAB_CONTROLS = "Controls";

	// --- Rules tab ---
	public static final String RULES_MAIN = "Welcome to LeagueScape. This window opens once per RuneScape account the first time you run the plugin "
		+ "(after you log in). You can reopen it anytime from the LeagueScape sidebar panel or the task icon menu.\n\n"
		+ "LeagueScape is an area-based progression plugin for Old School RuneScape. You pick a "
		+ "starting area and starting points in Game Mode. "
		+ "Complete tasks to earn points, then spend them to unlock more content depending on how you configure the game.\n\n"
		+ "— Game modes (set in Game Mode tab) —\n\n"
		+ "• Point buy: Areas are linked by neighbors. Spend points to unlock any neighboring area that you have not unlocked yet. "
		+ "Each area has its own task grid; open tasks from the task icon (minimap) or the LeagueScape panel.\n\n"
		+ "• Points to complete: You earn points in your current area. When you spend enough to meet the \"points to complete\" "
		+ "target for that area, you may unlock one of its connected neighbors (as configured). Task grids work per area like Point buy.\n\n"
		+ "• World Unlock: You spend points on a spiral grid of unlock tiles (skills, quests, bosses, areas, diaries). "
		+ "Unlocking tiles reveals tasks and can gate areas. Use the World Unlocks grid from the task icon menu or the LeagueScape panel; "
		+ "the global task grid lists tasks from your unlocked tiles.\n\n"
		+ "— Ring completion bonus (task grids) —\n\n"
		+ "Task grids are built in rings around the center. When you claim the last task in a full ring "
		+ "(every tile at the same ring distance from the center), you earn bonus points: "
		+ "ring number × your tier points for that ring's most common task difficulty (from Game Mode), "
		+ "capped at 250 points per ring. Each ring pays at most once. "
		+ "A small popup confirms when you receive the bonus. "
		+ "This applies to each area's task grid (Point buy and Points to complete) and to the global task grid in World Unlock mode.\n\n"
		+ "— This popup —\n"
		+ "• Rules (this tab): Overview, game modes, ring completion bonus, and task icon controls.\n"
		+ "• Game Mode: Unlock mode, task tier points, starter area, starting points, task list, reset progress.\n"
		+ "• Area Configuration: Import/export areas, edit polygons and neighbors, restore removed areas.\n"
		+ "• Controls: Keybinds for area editing on the map and in the world map view.";

	public static final String RULES_TASK_ICON = "Task icon (minimap)\n"
		+ "• Where: Small square icon to the left of the world map orb (below the minimap). Same icon as shown here.\n"
		+ "• Left-click: Opens your task panel — in World Unlock mode this is the global task grid; "
		+ "in Point buy and Points to complete modes it opens tasks for your current area.\n"
		+ "• Right-click: Opens a menu — Tasks, World Unlocks (World Unlock mode only), and Rules & Setup.";

	// --- Controls tab ---
	public static final String CONTROLS_BODY = "Area editing – Game viewport (when editing an area):\n"
		+ "• Shift + Right-click on a tile: Add polygon corner at that tile.\n"
		+ "• Shift + Right-click on an existing corner: Choose \"Move\" to enter move mode; then click another tile and choose \"Set new corner\" to move the corner there, or \"Cancel move\" to cancel.\n\n"
		+ "Area editing – World map (when editing an area):\n"
		+ "• Right-click: Move corner, Remove corner, Fill using others' corners, Begin new polygon, Add neighbors, Done editing, Cancel editing.\n\n"
		+ "Other: Open the world map and right click an area to see its details and unlock/tasks. Use the LeagueScape sidebar panel for Tasks and World Unlock.";

	// --- Game Mode tab ---
	public static final String GAME_MODE_UNLOCK_MODE = "Unlock mode:";
	public static final String GAME_MODE_WORLD_UNLOCK_COST_HTML = "<html>Unlock cost = tier × tier points × multiplier</html>";
	public static final String GAME_MODE_MULTIPLIER_TIER = "Multiplier tier (tile tier 1–5):";
	public static final String[] GAME_MODE_WORLD_UNLOCK_TYPE_LABELS = {
		"Skill:", "Area:", "Boss:", "Quest:", "Achievement diary:"
	};
	public static final String GAME_MODE_RESET_TIER_DEFAULTS = "Reset tier to defaults";
	public static final String GAME_MODE_TASK_MODE = "Task mode:";
	public static final String GAME_MODE_STARTER_AREA = "Starter area:";
	public static final String GAME_MODE_STARTING_POINTS = "Starting points:";
	public static final String GAME_MODE_UPDATE_STARTING_RULES = "Update starting rules";
	public static final String GAME_MODE_RESET_PROGRESS = "Reset Progress";

	public static String gameModeTierPointsLabel(int tier)
	{
		return "Tier " + tier + " points:";
	}

	public static final String GAME_MODE_UPDATE_RULES_CONFIRM = "Apply the selected rules (starting points, tier points, unlock mode)? World Unlock per-tier multipliers save when you change each spinner. Your progress will not be reset.";
	public static final String GAME_MODE_UPDATE_RULES_TITLE = "Update starting rules";
	public static final String GAME_MODE_UPDATE_RULES_DONE = "Starting rules updated.";
	public static final String GAME_MODE_UPDATE_RULES_DONE_TITLE = "Update complete";

	public static final String GAME_MODE_RESET_CONFIRM = "Reset all LeagueScape progress (points, area unlocks, and task completions)? This cannot be undone.";
	public static final String GAME_MODE_RESET_TITLE = "Reset progress";
	public static final String GAME_MODE_RESET_INPUT_NAME = "Enter your in-game character name to confirm:";
	public static final String GAME_MODE_RESET_INPUT_NAME_TITLE = "Confirm reset";
	public static final String GAME_MODE_RESET_NAME_MISMATCH = "Name did not match. Reset cancelled.";
	public static final String GAME_MODE_RESET_INPUT_RESET = "Type RESET (all caps) to confirm:";
	public static final String GAME_MODE_RESET_CANCELLED = "Reset cancelled.";
	public static final String GAME_MODE_RESET_CANCELLED_TITLE = "Cancelled";
	public static final String GAME_MODE_RESET_DONE = "Progress has been reset.";
	public static final String GAME_MODE_RESET_DONE_TITLE = "Reset complete";

	// --- Area Configuration tab ---
	public static final String AREA_ADD_NEW = "Add new area";
	public static final String AREA_IMPORT_JSON = "Import Area JSON";
	public static final String AREA_EXPORT_JSON = "Export Area JSON";
	public static final String AREA_SECTION_LIST = "Areas";
	public static final String AREA_SECTION_REMOVED = "Removed areas (Restore to add back)";
	public static final String AREA_SECTION_EDIT = "Edit area";
	public static final String AREA_MAKE_HOLE_REMOVE_POLYGON = "Remove polygon";
	public static final String AREA_MAKE_HOLE_FROM_POLYGON = "from polygon";
	public static final String AREA_MAKE_HOLE_BUTTON = "Make hole";
	public static final String AREA_SECTION_MAKE_HOLE = "Make hole";
	public static final String AREA_ROW_EDIT = "Edit";
	public static final String AREA_ROW_REMOVE = "Remove";
	public static final String AREA_ROW_RESTORE = "Restore";

	public static final String AREA_ID_LABEL_NEW = "Area ID (slug, e.g. lumbridge):";
	public static final String AREA_ID_LABEL_EXISTING = "Area ID (slug):";
	public static final String AREA_DISPLAY_NAME = "Display name:";
	public static final String AREA_DESCRIPTION = "Description (shown in Area Details on world map):";
	public static final String AREA_CORNERS_HINT_HTML = "<html>Corners: Shift+RMB to add; Shift+RMB corner to Move, then Set.</html>";
	public static final String AREA_CORNERS_HINT_TOOLTIP = "Shift+Right-click to add corner; Shift+Right-click a corner to Move it, then click another tile to Set.";
	public static final String AREA_NEIGHBORS = "Neighbors:";
	public static final String AREA_UNLOCK_COST = "Unlock cost (points to spend to unlock this area):";
	public static final String AREA_POINTS_TO_COMPLETE = "Points to complete (points to earn in this area to complete it; used in Points-to-complete mode):";
	public static final String AREA_SAVE = "Save";
	public static final String AREA_CANCEL = "Cancel";

	public static String areaHolesCountLabel(int count)
	{
		return "Holes (cut out): " + count;
	}

	public static String areaHoleRowLabel(int holeIndex, int vertexCount)
	{
		return "  Hole " + (holeIndex + 1) + ": " + vertexCount + " vertices";
	}

	public static String areaPolygonComboLabel(int polygonIndex, int pointCount)
	{
		return "Polygon " + (polygonIndex + 1) + " (" + pointCount + " pts)";
	}

	public static String areaCornerRowLabel(int index, int worldX, int worldY, int plane)
	{
		return "  " + (index + 1) + ": " + worldX + ", " + worldY + ", " + plane;
	}

	public static final String AREA_EDIT_HEADER_EXPANDED = "▼ Edit area";
	public static final String AREA_MAKE_HOLE_HEADER_EXPANDED = "▼ Make hole";

	public static final String AREA_MAKE_HOLE_DIFFERENT_POLYGONS = "Choose different polygons: outer and inner must differ.";
	public static final String AREA_MAKE_HOLE_TITLE = "Make hole";
	public static final String AREA_MAKE_HOLE_MIN_VERTICES = "Both polygons need at least 3 vertices.";
	public static final String AREA_MAKE_HOLE_INNER_INSIDE = "The inner polygon must be entirely inside the outer polygon (e.g. island inside ocean).";

	public static final String AREA_IMPORT_DIALOG_TITLE = "Import Area JSON";
	public static final String AREA_IMPORT_FILE_FILTER = "JSON files";
	public static String areaImportSuccess(int count, String fileName)
	{
		return "Imported " + count + " areas from " + fileName + ".\nImported areas replace your custom areas; built-in areas are unchanged.";
	}
	public static final String AREA_IMPORT_COMPLETE_TITLE = "Import Complete";
	public static String areaImportInvalidJson(String message)
	{
		return "Invalid area JSON:\n\n" + message;
	}
	public static final String AREA_IMPORT_ERROR_TITLE = "Import Error";
	public static String areaImportFailed(String message)
	{
		return "Import failed: " + message;
	}

	public static String areaExportSuccess(int areaCount, String fileName)
	{
		return "Exported " + areaCount + " areas to " + fileName;
	}
	public static final String AREA_EXPORT_COMPLETE_TITLE = "Export Complete";
	public static String areaExportFailed(String message)
	{
		return "Export failed: " + message;
	}
	public static final String AREA_EXPORT_ERROR_TITLE = "Export Error";
}

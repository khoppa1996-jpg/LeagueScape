package com.leaguescape.util;

/**
 * Central constants for LeagueScape classpath resource paths (JSON and other assets).
 * Use these when loading resources and as default filenames in file choosers.
 */
public final class ResourcePaths
{
	private ResourcePaths() {}

	/** Built-in areas definition (classpath root). */
	public static final String AREAS_JSON = "/areas.json";
	/** Built-in tasks definition (classpath root). */
	public static final String TASKS_JSON = "/tasks.json";
	/** Built-in quest tasks (classpath root). */
	public static final String QUEST_TASKS_JSON = "/quest_tasks.json";
	/** World unlock tiles definition (classpath root). */
	public static final String WORLD_UNLOCKS_JSON = "/world_unlocks.json";
	/** Goals definition (classpath root). */
	public static final String GOALS_JSON = "/goals.json";
	/** Achievement diary / kingdom area groupings (classpath root). */
	public static final String AREA_MAPPING_JSON = "/area_mapping.json";

	/** Default filename for areas export (no leading slash). */
	public static final String DEFAULT_AREAS_FILENAME = "areas.json";
	/** Default filename for tasks export (no leading slash). */
	public static final String DEFAULT_TASKS_FILENAME = "tasks.json";
}

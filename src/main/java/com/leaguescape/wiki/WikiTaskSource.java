package com.leaguescape.wiki;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Wiki-backed task source types for the Task Creator Helper. Each source maps to a category
 * (for category-based generation: one page = one task) and optionally to list-page titles
 * (for table parsing: one page = many tasks).
 */
public enum WikiTaskSource
{
	QUESTS("Quests", "Quests", "Quest", "Complete ", null),
	MINIQUESTS("Miniquests", "Miniquests", "Quest", "Complete ", null),
	BOSSES("Bosses", "Bosses", "Combat", "Defeat ", null),
	NPCS("NPCs", "NPCs", "Combat", "Defeat ", null),
	MINIGAMES("Minigames", "Minigames", "Activity", "Complete ", null),
	CLUE_SCROLLS("Clue scrolls", "Clue scrolls", "Clue Scroll", "Complete ", null),
	COMBAT_ACHIEVEMENTS("Combat achievements", "Combat Achievements", "Combat", "Complete ", null),
	ACHIEVEMENT_DIARY("Achievement diary", "Achievement Diary", "Achievement Diary", null, Arrays.asList("Achievement Diary/Lumbridge & Draynor", "Achievement Diary/Varrock", "Achievement Diary/Desert")),
	LEAGUE_TASKS("League tasks", "Trailblazer Reloaded League tasks", "Activity", null, Collections.singletonList("Leagues 4 task list"));

	private final String displayName;
	private final String categoryName;
	private final String defaultTaskType;
	/** Prefix for display name when building from page title (e.g. "Complete " + title). Null = use list-page parsing. */
	private final String displayNamePrefix;
	/** Wiki page titles for list-page parsing (table of tasks). Null/empty = category-based only. */
	private final List<String> listPageTitles;

	WikiTaskSource(String displayName, String categoryName, String defaultTaskType, String displayNamePrefix, List<String> listPageTitles)
	{
		this.displayName = displayName;
		this.categoryName = categoryName;
		this.defaultTaskType = defaultTaskType;
		this.displayNamePrefix = displayNamePrefix;
		this.listPageTitles = listPageTitles != null ? listPageTitles : Collections.emptyList();
	}

	public String getDisplayName() { return displayName; }
	public String getCategoryName() { return categoryName; }
	public String getDefaultTaskType() { return defaultTaskType; }
	public String getDisplayNamePrefix() { return displayNamePrefix; }
	/** True if this source uses list-page table parsing (e.g. league tasks, achievement diary). */
	public boolean hasListPages() { return !listPageTitles.isEmpty(); }
	public List<String> getListPageTitles() { return listPageTitles; }
}

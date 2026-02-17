package com.leaguescape.wiki;

import com.leaguescape.task.TaskDefinition;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/**
 * Generates task definitions from the OSRS Wiki: category-based (one page = one task) and
 * list-page table parsing (one page = many tasks). Runs on caller's thread; run from background
 * executor when used from UI.
 */
@Slf4j
@Singleton
public class WikiTaskGenerator
{
	private static final int CATEGORY_PAGE_LIMIT = 500;
	private static final Pattern WIKI_LINK = Pattern.compile("\\[\\[([^]|]*(?:\\|[^]]*)?)\\]\\]");
	private static final Pattern PIPES = Pattern.compile("\\|");

	private final OsrsWikiApiService wikiApi;

	@Inject
	public WikiTaskGenerator(OsrsWikiApiService wikiApi)
	{
		this.wikiApi = wikiApi;
	}

	/** Defaults applied when generating tasks. */
	public static class GeneratorDefaults
	{
		private int defaultDifficulty = 3;
		private boolean defaultF2p = true;
		private int difficultyRangeMin = 1;
		private int difficultyRangeMax = 5;

		public int getDefaultDifficulty() { return defaultDifficulty; }
		public void setDefaultDifficulty(int v) { this.defaultDifficulty = Math.max(1, Math.min(5, v)); }
		public boolean isDefaultF2p() { return defaultF2p; }
		public void setDefaultF2p(boolean v) { this.defaultF2p = v; }
		public int getDifficultyRangeMin() { return difficultyRangeMin; }
		public void setDifficultyRangeMin(int v) { this.difficultyRangeMin = Math.max(1, Math.min(5, v)); }
		public int getDifficultyRangeMax() { return difficultyRangeMax; }
		public void setDifficultyRangeMax(int v) { this.difficultyRangeMax = Math.max(1, Math.min(5, v)); }
	}

	/**
	 * Generate tasks from the given sources. Progress messages can be sent to the callback
	 * (call from background thread; UI should use SwingUtilities.invokeLater to update).
	 */
	public List<TaskDefinition> generate(Set<WikiTaskSource> sources, GeneratorDefaults defaults, Consumer<String> progressCallback)
	{
		if (sources == null || sources.isEmpty()) return new ArrayList<>();
		GeneratorDefaults d = defaults != null ? defaults : new GeneratorDefaults();
		List<TaskDefinition> out = new ArrayList<>();
		for (WikiTaskSource source : sources)
		{
			if (source.hasListPages())
			{
				report(progressCallback, "Parsing " + source.getDisplayName() + "...");
				List<TaskDefinition> fromList = generateFromListPages(source, d);
				out.addAll(fromList);
				report(progressCallback, source.getDisplayName() + ": " + fromList.size() + " tasks");
			}
			else
			{
				report(progressCallback, "Fetching " + source.getDisplayName() + "...");
				List<TaskDefinition> fromCat = generateFromCategory(source, d);
				out.addAll(fromCat);
				report(progressCallback, source.getDisplayName() + ": " + fromCat.size() + " tasks");
			}
		}
		return out;
	}

	private static void report(Consumer<String> cb, String msg)
	{
		if (cb != null) cb.accept(msg);
	}

	private List<TaskDefinition> generateFromCategory(WikiTaskSource source, GeneratorDefaults d)
	{
		List<TaskDefinition> out = new ArrayList<>();
		String prefix = source.getDisplayNamePrefix();
		if (prefix == null) prefix = "";
		String continueToken = null;
		do
		{
			OsrsWikiApiService.CategoryMembersResult result = wikiApi.listCategoryMembers(source.getCategoryName(), CATEGORY_PAGE_LIMIT, continueToken);
			for (String title : result.getTitles())
			{
				if (title == null || title.trim().isEmpty()) continue;
				String displayName = prefix + title.trim();
				TaskDefinition def = new TaskDefinition();
				def.setDisplayName(displayName);
				def.setTaskType(source.getDefaultTaskType());
				def.setDifficulty(clampDifficulty(d.getDefaultDifficulty(), d));
				def.setF2p(d.isDefaultF2p());
				out.add(def);
			}
			continueToken = result.getNextContinue();
		}
		while (continueToken != null && !continueToken.isEmpty());
		return out;
	}

	private List<TaskDefinition> generateFromListPages(WikiTaskSource source, GeneratorDefaults d)
	{
		List<TaskDefinition> out = new ArrayList<>();
		for (String pageTitle : source.getListPageTitles())
		{
			String content = wikiApi.getPageContent(pageTitle);
			if (content == null || content.isEmpty()) continue;
			List<TaskDefinition> fromTable = parseWikiTable(content, source.getDefaultTaskType(), d);
			out.addAll(fromTable);
		}
		return out;
	}

	/**
	 * Parse wikitext table: find rows (|- or |), split cells by |, strip [[link]] to text.
	 * Looks for header row to infer column indices for Task/Name, Tier, Requirements, Area.
	 */
	private List<TaskDefinition> parseWikiTable(String wikitext, String defaultTaskType, GeneratorDefaults d)
	{
		List<TaskDefinition> out = new ArrayList<>();
		String[] lines = wikitext.split("\\r?\\n");
		List<String[]> rows = new ArrayList<>();
		int taskCol = -1, tierCol = -1, reqCol = -1, areaCol = -1;
		boolean headerDone = false;
		for (String line : lines)
		{
			String trimmed = line.trim();
			if (trimmed.startsWith("|-") || trimmed.startsWith("|") || trimmed.startsWith("!"))
			{
				String rowContent = trimmed.replace('!', '|');
				rowContent = (rowContent.startsWith("|-") ? rowContent.substring(2) : rowContent.substring(1)).trim();
				String[] cells = PIPES.split(rowContent, -1);
				for (int i = 0; i < cells.length; i++)
					cells[i] = stripWikiMarkup(cells[i].trim());
				if (!headerDone && rows.isEmpty())
				{
					for (int i = 0; i < cells.length; i++)
					{
						String c = cells[i].toLowerCase();
						if (c.contains("task") || c.contains("name") || c.contains("description")) taskCol = i;
						else if (c.contains("tier") || c.contains("difficulty") || c.contains("level")) tierCol = i;
						else if (c.contains("requirement")) reqCol = i;
						else if (c.contains("area") || c.contains("region")) areaCol = i;
					}
					headerDone = true;
					if (taskCol >= 0 || tierCol >= 0 || reqCol >= 0 || areaCol >= 0)
						continue;
				}
				if (cells.length > 0 && taskCol < 0) taskCol = 0;
				rows.add(cells);
			}
		}
		int tCol = taskCol >= 0 ? taskCol : 0;
		for (String[] cells : rows)
		{
			String displayName = tCol < cells.length ? cells[tCol] : null;
			if (displayName == null || displayName.isEmpty()) continue;
			int difficulty = d.getDefaultDifficulty();
			if (tierCol >= 0 && tierCol < cells.length)
			{
				int parsed = tierFromName(cells[tierCol]);
				if (parsed >= 1 && parsed <= 5) difficulty = parsed;
			}
			if (difficulty < d.getDifficultyRangeMin() || difficulty > d.getDifficultyRangeMax()) continue;
			String requirements = (reqCol >= 0 && reqCol < cells.length) ? cells[reqCol] : null;
			String area = (areaCol >= 0 && areaCol < cells.length) ? cells[areaCol] : null;
			TaskDefinition def = new TaskDefinition();
			def.setDisplayName(displayName);
			def.setTaskType(defaultTaskType);
			def.setDifficulty(difficulty);
			def.setF2p(d.isDefaultF2p());
			if (requirements != null && !requirements.isEmpty()) def.setRequirements(requirements);
			if (area != null && !area.isEmpty()) def.setArea(normalizeAreaId(area));
			out.add(def);
		}
		return out;
	}

	private static String stripWikiMarkup(String s)
	{
		if (s == null) return "";
		String t = s;
		java.util.regex.Matcher m = WIKI_LINK.matcher(t);
		while (m.find())
		{
			String link = m.group(1);
			String text = link.contains("|") ? link.substring(link.indexOf("|") + 1) : link;
			t = t.substring(0, m.start()) + text + t.substring(m.end());
			m.reset(t);
		}
		return t.replace("'''", "").replace("''", "").trim();
	}

	private static int tierFromName(String tierName)
	{
		if (tierName == null) return 3;
		String t = tierName.toLowerCase();
		if (t.contains("easy")) return 1;
		if (t.contains("medium")) return 2;
		if (t.contains("hard")) return 3;
		if (t.contains("elite")) return 4;
		if (t.contains("master")) return 5;
		return 3;
	}

	private static String normalizeAreaId(String areaName)
	{
		if (areaName == null) return null;
		return areaName.toLowerCase().replaceAll("[^a-z0-9_]", "_").replaceAll("_+", "_").replaceAll("^_|_$", "");
	}

	private static int clampDifficulty(int difficulty, GeneratorDefaults d)
	{
		int v = Math.max(1, Math.min(5, difficulty));
		if (v < d.getDifficultyRangeMin()) return d.getDifficultyRangeMin();
		if (v > d.getDifficultyRangeMax()) return d.getDifficultyRangeMax();
		return v;
	}
}

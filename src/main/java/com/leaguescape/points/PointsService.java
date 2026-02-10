package com.leaguescape.points;

import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Getter;
import net.runelite.client.config.ConfigManager;

/**
 * Tracks points earned and spent. Persists earned total and spent total via ConfigManager.
 */
@Singleton
public class PointsService
{
	private static final String CONFIG_GROUP = "leaguescapeState";
	private static final String KEY_EARNED = "pointsEarnedTotal";
	private static final String KEY_SPENT = "pointsSpentTotal";

	@Getter
	private int earnedTotal;
	@Getter
	private int spentTotal;

	private final ConfigManager configManager;

	@Inject
	public PointsService(ConfigManager configManager)
	{
		this.configManager = configManager;
	}

	public int getSpendable()
	{
		return Math.max(0, earnedTotal - spentTotal);
	}

	public void addEarned(int amount)
	{
		if (amount <= 0) return;
		earnedTotal += amount;
		persist();
	}

	/**
	 * Spend points (e.g. to unlock an area). Returns true if enough spendable points.
	 */
	public boolean spend(int amount)
	{
		if (amount <= 0 || amount > getSpendable()) return false;
		spentTotal += amount;
		persist();
		return true;
	}

	public void loadFromConfig()
	{
		String e = configManager.getConfiguration(CONFIG_GROUP, KEY_EARNED);
		String s = configManager.getConfiguration(CONFIG_GROUP, KEY_SPENT);
		earnedTotal = parseInt(e, 0);
		spentTotal = parseInt(s, 0);
	}

	public void setStartingPoints(int points)
	{
		// When user sets starting points, we treat it as initial "earned" so spendable = startingPoints
		earnedTotal = points;
		spentTotal = 0;
		persist();
	}

	private void persist()
	{
		configManager.setConfiguration(CONFIG_GROUP, KEY_EARNED, earnedTotal);
		configManager.setConfiguration(CONFIG_GROUP, KEY_SPENT, spentTotal);
	}

	private static int parseInt(String s, int def)
	{
		if (s == null || s.isEmpty()) return def;
		try
		{
			return Integer.parseInt(s);
		}
		catch (NumberFormatException e)
		{
			return def;
		}
	}
}

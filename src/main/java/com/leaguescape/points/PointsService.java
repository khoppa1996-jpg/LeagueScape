package com.leaguescape.points;

import lombok.Getter;
import net.runelite.client.config.ConfigManager;

/**
 * Tracks points earned and spent for LeagueScape. Persists earned total and spent total via
 * ConfigManager so progress survives restarts. Instantiated only via LeagueScapePlugin's
 * {@code @Provides} so a single instance is used. Spendable = earnedTotal - spentTotal (used for
 * unlocking areas in point-buy mode).
 */
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

	public PointsService(ConfigManager configManager)
	{
		this.configManager = configManager;
	}

	/**
	 * Returns the number of points the player can currently spend (e.g. on unlocking areas).
	 * Never negative.
	 */
	public int getSpendable()
	{
		return Math.max(0, earnedTotal - spentTotal);
	}

	/**
	 * Adds points to the earned total (e.g. from claiming a task). Does nothing if amount <= 0.
	 * Persists to config after update.
	 */
	public void addEarned(int amount)
	{
		if (amount <= 0) return;
		earnedTotal += amount;
		persist();
	}

	/**
	 * Spends points (e.g. to unlock an area). Only succeeds if amount is positive and not greater
	 * than spendable points. Persists to config on success.
	 *
	 * @param amount points to spend
	 * @return true if the spend was applied, false if amount invalid or insufficient spendable
	 */
	public boolean spend(int amount)
	{
		if (amount <= 0 || amount > getSpendable()) return false;
		spentTotal += amount;
		persist();
		return true;
	}

	/**
	 * Loads earned and spent totals from config. Call on plugin start or when loading saved state.
	 */
	public void loadFromConfig()
	{
		String e = configManager.getConfiguration(CONFIG_GROUP, KEY_EARNED);
		String s = configManager.getConfiguration(CONFIG_GROUP, KEY_SPENT);
		earnedTotal = parseInt(e, 0);
		spentTotal = parseInt(s, 0);
	}

	/**
	 * Sets the starting points (e.g. from config). Resets spent to 0 so spendable = starting points.
	 * Used when the user changes "Starting points" in config.
	 */
	public void setStartingPoints(int points)
	{
		earnedTotal = points;
		spentTotal = 0;
		persist();
	}

	/** Writes current earned and spent totals to config. */
	private void persist()
	{
		configManager.setConfiguration(CONFIG_GROUP, KEY_EARNED, earnedTotal);
		configManager.setConfiguration(CONFIG_GROUP, KEY_SPENT, spentTotal);
	}

	/** Parses an integer from config string; returns default if null, empty, or invalid. */
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

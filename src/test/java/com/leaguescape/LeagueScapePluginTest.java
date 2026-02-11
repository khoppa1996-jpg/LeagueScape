package com.leaguescape;

import com.leaguescape.config.LeagueScapeConfigPlugin;
import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class LeagueScapePluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(LeagueScapePlugin.class, LeagueScapeConfigPlugin.class);
		RuneLite.main(args);
	}
}

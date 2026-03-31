package com.gridscape.icons;

import com.gridscape.constants.TaskTypes;
import org.junit.Assert;
import org.junit.Test;

public class IconResolverTest
{
	@Test
	public void killCountWithoutBossUsesCombatIcon()
	{
		String path = IconResolver.resolveTaskTileLocalIconPath(TaskTypes.KILL_COUNT, "Kill 5 goblins", null);
		Assert.assertNotNull(path);
		Assert.assertTrue(path.contains("Combat_icon"));
	}

	@Test
	public void killCountWithBossUsesBossIcon()
	{
		String path = IconResolver.resolveTaskTileLocalIconPath(TaskTypes.KILL_COUNT, "Kill Zulrah", "zulrah");
		Assert.assertNotNull(path);
		Assert.assertTrue(path.contains("bossicons"));
		Assert.assertTrue(path.contains("zulrah"));
	}

	@Test
	public void combatWithBossIdUsesBossIconNotCombatIcon()
	{
		String path = IconResolver.resolveTaskTileLocalIconPath(TaskTypes.COMBAT, "Defeat Zulrah", "zulrah");
		Assert.assertNotNull(path);
		Assert.assertTrue(path.contains("bossicons"));
		Assert.assertFalse(path.contains("Combat_icon"));
	}

	@Test
	public void collectionLogWithBossIdUsesCollectionLogIconNotBossIcon()
	{
		String path = IconResolver.resolveTaskTileLocalIconPath(TaskTypes.COLLECTION_LOG, "Zulrah (Collection Log)", "zulrah");
		Assert.assertNotNull(path);
		Assert.assertTrue(path.contains("Collection_log_detail"));
		Assert.assertFalse(path.contains("bossicons"));
	}
}

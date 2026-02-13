package com.leaguescape;

import java.io.InputStream;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.audio.AudioPlayer;

/**
 * Plays LeagueScape UI sound effects from resources/soundeffects/.
 * Gain is in dB; 0f = normal volume.
 */
@Slf4j
public final class LeagueScapeSounds
{
	private static final String PREFIX = "/soundeffects/";

	public static final String LOCKED = PREFIX + "Locked.wav";
	public static final String WRONG = PREFIX + "Wrong_sound_effect.wav.ogg";
	public static final String EQUIP_FUN = PREFIX + "Equip_fun.wav";
	public static final String COINS_JINGLE = PREFIX + "Coins_jingle_(4).wav.ogg";

	private static final float GAIN_DB = 0f;

	/** Play a sound from a classpath resource path (e.g. LeagueScapeSounds.LOCKED). */
	public static void play(AudioPlayer player, String resourcePath)
	{
		if (player == null || resourcePath == null) return;
		try (InputStream in = LeagueScapeSounds.class.getResourceAsStream(resourcePath))
		{
			if (in != null)
				player.play(in, GAIN_DB);
		}
		catch (Exception e)
		{
			log.debug("LeagueScape sound failed {}: {}", resourcePath, e.getMessage());
		}
	}
}

package com.leaguescape;

import java.io.InputStream;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.audio.AudioPlayer;

/**
 * Plays LeagueScape UI sound effects from classpath resources under /soundeffects/.
 * Gain is in dB; 0f = normal volume. Use {@link #play(AudioPlayer, String)} with one of the
 * public path constants, or a custom path under the same prefix.
 */
@Slf4j
public final class LeagueScapeSounds
{
	private static final String PREFIX = "/soundeffects/";

	/** Played when the player tries to interact with a locked area or action. */
	public static final String LOCKED = PREFIX + "Locked.wav";
	/** Played when an action is invalid (e.g. not enough points to unlock). */
	public static final String WRONG = PREFIX + "Wrong_sound_effect.wav.ogg";
	/** Played on general button/clicks (dialogs, menus, etc.). */
	public static final String BUTTON_PRESS = PREFIX + "button_press.wav";
	public static final String EQUIP_FUN = PREFIX + "Equip_fun.wav";
	public static final String COINS_JINGLE = PREFIX + "Coins_jingle_(4).wav.ogg";
	/** Played when a task is auto-completed (e.g. collection log style). */
	public static final String TASK_COMPLETE = PREFIX + "Task_complete.wav";

	private static final float GAIN_DB = 0f;

	/**
	 * Plays a sound from a classpath resource path. Safe to call with null player or path (no-op).
	 * Logs at debug if the resource is missing or playback fails.
	 *
	 * @param player       RuneLite audio player (may be null)
	 * @param resourcePath classpath path (e.g. {@link #LOCKED}, or any path under /soundeffects/)
	 */
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

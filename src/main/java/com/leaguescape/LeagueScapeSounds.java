package com.leaguescape;

import java.io.InputStream;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.audio.AudioPlayer;

/**
 * Plays LeagueScape UI sound effects from classpath resources under /soundeffects/.
 * Gain is in dB; 0f = normal volume. Use {@link #play(AudioPlayer, String)} or
 * {@link #play(AudioPlayer, String, Client)} with one of the public path constants.
 * When a {@link Client} is provided, volume is scaled by the game's Audio Settings:
 * the RuneLite API exposes {@link Client#getMusicVolume()} (0-255) but not the Sound Effects
 * slider, so LeagueScape sounds currently follow the <b>Music</b> volume slider. If the API
 * adds getSoundEffectVolume() in the future, callers can switch to that for the Sound Effects slider.
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

	private static final float GAIN_DB_FULL = 0f;
	/** Gain when volume is 0 (mute). */
	private static final float GAIN_DB_MUTED = -80f;

	/**
	 * Converts the game volume (0-255, e.g. from Music or Sound Effects slider) to dB gain.
	 * 0 = muted, 255 = full (0 dB).
	 */
	public static float volumeToGainDb(int volume0to255)
	{
		if (volume0to255 <= 0) return GAIN_DB_MUTED;
		if (volume0to255 >= 255) return GAIN_DB_FULL;
		// Logarithmic scale: 20 * log10((v+1)/256)
		double linear = (volume0to255 + 1) / 256.0;
		return (float) (20.0 * Math.log10(linear));
	}

	/**
	 * Plays a sound from a classpath resource path. Safe to call with null player or path (no-op).
	 * Logs at debug if the resource is missing or playback fails.
	 *
	 * @param player       RuneLite audio player (may be null)
	 * @param resourcePath classpath path (e.g. {@link #LOCKED}, or any path under /soundeffects/)
	 */
	public static void play(AudioPlayer player, String resourcePath)
	{
		play(player, resourcePath, null);
	}

	/**
	 * Plays a sound scaled by the game's audio volume. Uses the game's Sound Effects volume
	 * when the RuneLite API exposes it; otherwise uses Music volume (0-255) so LeagueScape
	 * sounds follow the Audio Settings panel. Safe to call with null player, path, or client.
	 *
	 * @param player       RuneLite audio player (may be null)
	 * @param resourcePath classpath path (e.g. {@link #LOCKED})
	 * @param client       game client for volume (may be null; then full volume is used)
	 */
	public static void play(AudioPlayer player, String resourcePath, Client client)
	{
		if (player == null || resourcePath == null) return;
		float gain = GAIN_DB_FULL;
		if (client != null)
		{
			// API only exposes getMusicVolume(); use it so our sounds follow the Music slider.
			// When getSoundEffectVolume() is added, use it here for the Sound Effects slider.
			int vol = client.getMusicVolume();
			gain = volumeToGainDb(vol);
			if (gain <= GAIN_DB_MUTED + 1f)
			{
				return; // Muted
			}
		}
		try (InputStream in = LeagueScapeSounds.class.getResourceAsStream(resourcePath))
		{
			if (in != null)
				player.play(in, gain);
		}
		catch (Exception e)
		{
			log.debug("LeagueScape sound failed {}: {}", resourcePath, e.getMessage());
		}
	}
}

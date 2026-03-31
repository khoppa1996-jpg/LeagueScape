package com.gridscape;

import java.io.InputStream;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.VarPlayer;
import net.runelite.client.audio.AudioPlayer;

/**
 * Plays GridScape UI sound effects from classpath resources under /soundeffects/.
 * Gain is in dB; 0f = normal volume. Use {@link #play(AudioPlayer, String)} or
 * {@link #play(AudioPlayer, String, Client)} with one of the public path constants.
 * When a {@link Client} is provided, volume follows the game's <b>Sound effects</b> slider
 * via {@link VarPlayer#SOUND_EFFECT_VOLUME} (same scale as {@link net.runelite.api.SoundEffectVolume}).
 */
@Slf4j
public final class GridScapeSounds
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
	 * Converts the game volume (0 = mute; typical sound-effect steps up to 127; music may use up to 255) to dB gain.
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
	 * Plays a sound scaled by the game's <b>Sound effects</b> volume (Audio Settings in the client).
	 * Safe to call with null player, path, or client.
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
			int vol = client.getVarpValue(VarPlayer.SOUND_EFFECT_VOLUME);
			gain = volumeToGainDb(vol);
			if (gain <= GAIN_DB_MUTED + 1f)
			{
				return; // Muted
			}
		}
		try (InputStream in = GridScapeSounds.class.getResourceAsStream(resourcePath))
		{
			if (in != null)
				player.play(in, gain);
		}
		catch (Exception e)
		{
			log.debug("GridScape sound failed {}: {}", resourcePath, e.getMessage());
		}
	}
}

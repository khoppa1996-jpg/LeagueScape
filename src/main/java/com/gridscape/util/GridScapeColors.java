package com.gridscape.util;

import java.awt.Color;

/**
 * Shared UI colors for GridScape popups and panels (theme consistency).
 */
public final class GridScapeColors
{
	private GridScapeColors() {}

	/** Popup/panel background (brown). */
	public static final Color POPUP_BG = new Color(0x54, 0x4D, 0x41);
	/** Popup/panel text (cream). */
	public static final Color POPUP_TEXT = new Color(0xC4, 0xB8, 0x96);
	/** Same as POPUP_TEXT with alpha (e.g. for overlays). */
	public static final Color POPUP_TEXT_ALPHA = new Color(0xC4, 0xB8, 0x96, 220);
}

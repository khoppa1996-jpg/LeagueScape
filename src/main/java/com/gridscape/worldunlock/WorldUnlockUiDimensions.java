package com.gridscape.worldunlock;

import java.awt.Dimension;

/**
 * Shared layout dimensions so {@link WorldUnlockGridPanel} and {@link GlobalTaskListPanel} pack to the same size.
 */
public final class WorldUnlockUiDimensions
{
	private WorldUnlockUiDimensions() {}

	/** Scroll viewport for both world-unlock and global-task spiral grids. */
	public static final Dimension GRID_SCROLL_PREFERRED = new Dimension(400, 320);

	/** Same minimum scroll size on both panels so packed dialog width matches. */
	public static final Dimension GRID_SCROLL_MINIMUM = new Dimension(400, 200);

	/**
	 * Preferred size for the root panel (header + grid scroll + south row + compound border).
	 * Applied to both panels so their dialogs match after {@code pack()}.
	 */
	public static final Dimension PANEL_PREFERRED = new Dimension(520, 460);
}

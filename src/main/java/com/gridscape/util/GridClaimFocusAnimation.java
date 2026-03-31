package com.leaguescape.util;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Point;
import java.awt.Rectangle;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JViewport;
import javax.swing.Timer;
import java.util.function.Consumer;

/**
 * Smooth zoom + scroll focus after claiming a grid tile (default 100ms).
 */
public final class GridClaimFocusAnimation
{
	public static final int CLAIM_FOCUS_DURATION_MS = 100;
	private static final int STEPS = 10;

	private GridClaimFocusAnimation() {}

	/** Client properties on each grid cell so we can read real {@link Component#getBounds()} after layout. */
	public static final String GRID_CELL_ROW_KEY = "leaguescape.gridRow";
	public static final String GRID_CELL_COL_KEY = "leaguescape.gridCol";

	public static void putGridCellKeys(JComponent c, int row, int col)
	{
		c.putClientProperty(GRID_CELL_ROW_KEY, row);
		c.putClientProperty(GRID_CELL_COL_KEY, col);
	}

	private static boolean matchesGridCell(JComponent c, int row, int col)
	{
		Object r = c.getClientProperty(GRID_CELL_ROW_KEY);
		Object co = c.getClientProperty(GRID_CELL_COL_KEY);
		if (!(r instanceof Integer) || !(co instanceof Integer))
			return false;
		return ((Integer) r).intValue() == row && ((Integer) co).intValue() == col;
	}

	private static Component findGridCellChild(JPanel gridPanel, int row, int col)
	{
		for (Component c : gridPanel.getComponents())
		{
			if (c instanceof JComponent && matchesGridCell((JComponent) c, row, col))
				return c;
		}
		return null;
	}

	private static int[] viewExtentsForScrolling(JViewport viewport, JPanel gridPanel, int ringExtent, int tileSize,
		int cellPaddingTotal)
	{
		Dimension vs = viewport.getViewSize();
		Dimension pref = gridPanel.getPreferredSize();
		int span = 2 * ringExtent + 1;
		int cellW = tileSize + cellPaddingTotal;
		int logicalW = span * cellW;
		int logicalH = span * cellW;
		int viewW = Math.max(Math.max(vs.width, pref.width), logicalW);
		int viewH = Math.max(Math.max(vs.height, pref.height), logicalH);
		return new int[] { viewW, viewH };
	}

	private static Point centerViewportOnPoint(int cx, int cy, int vw, int vh, int viewW, int viewH)
	{
		int maxX = Math.max(0, viewW - vw);
		int maxY = Math.max(0, viewH - vh);
		int vpx = Math.max(0, Math.min(cx - vw / 2, maxX));
		int vpy = Math.max(0, Math.min(cy - vh / 2, maxY));
		return new Point(vpx, vpy);
	}

	/**
	 * Centers the viewport on the grid cell at {@code (row,col)}. Prefer the laid-out bounds of the cell
	 * component (see {@link #putGridCellKeys}) — {@link java.awt.GridBagLayout} collapses empty rows, so
	 * {@code gy * cellH} does not match real Y positions and vertical panning would fail without this.
	 * Falls back to ring geometry when the cell is missing or not yet laid out.
	 *
	 * @param ringExtent half the grid width in cells minus center (same as maxRing / center index)
	 */
	public static Point computeViewPositionForTile(JViewport viewport, JPanel gridPanel, int row, int col,
		int ringExtent, int tileSize, int cellPaddingTotal)
	{
		if (viewport == null || gridPanel == null)
			return new Point(0, 0);

		Container scrollParent = viewport.getParent();
		if (scrollParent != null)
			scrollParent.validate();
		viewport.validate();
		gridPanel.validate();

		int vw = viewport.getExtentSize().width;
		int vh = viewport.getExtentSize().height;
		if (vw <= 0 || vh <= 0)
			return new Point(0, 0);

		int[] extents = viewExtentsForScrolling(viewport, gridPanel, ringExtent, tileSize, cellPaddingTotal);
		int viewW = extents[0];
		int viewH = extents[1];

		Component target = findGridCellChild(gridPanel, row, col);
		if (target != null)
		{
			Rectangle b = target.getBounds();
			if (b.width > 0 && b.height > 0)
			{
				int cx = b.x + b.width / 2;
				int cy = b.y + b.height / 2;
				return centerViewportOnPoint(cx, cy, vw, vh, viewW, viewH);
			}
		}

		int gx = col + ringExtent;
		int gy = ringExtent - row;
		int cellW = tileSize + cellPaddingTotal;
		int cellH = cellW;
		int px = gx * cellW;
		int py = gy * cellH;
		return centerViewportOnPoint(px + cellW / 2, py + cellH / 2, vw, vh, viewW, viewH);
	}

	/**
	 * Animates zoom from start to end over {@link #CLAIM_FOCUS_DURATION_MS} with smoothstep, calling
	 * {@code refresh} after each step. If zoom span is negligible, runs one refresh and completes.
	 */
	public static void animateZoomToClaim(float zoomStart, float zoomEnd, float zoomMin, float zoomMax,
		Consumer<Float> setZoom, Runnable refresh, Runnable onComplete)
	{
		float z0 = Math.max(zoomMin, Math.min(zoomMax, zoomStart));
		float z1 = Math.max(zoomMin, Math.min(zoomMax, zoomEnd));
		if (Math.abs(z1 - z0) < 0.0005f)
		{
			if (refresh != null)
			{
				refresh.run();
			}
			if (onComplete != null)
			{
				onComplete.run();
			}
			return;
		}
		final int[] step = { 0 };
		int delay = Math.max(1, CLAIM_FOCUS_DURATION_MS / STEPS);
		Timer timer = new Timer(delay, null);
		timer.addActionListener(e -> {
			step[0]++;
			float p = Math.min(1f, step[0] / (float) STEPS);
			float t = p * p * (3f - 2f * p);
			float z = z0 + (z1 - z0) * t;
			z = Math.max(zoomMin, Math.min(zoomMax, z));
			setZoom.accept(z);
			if (refresh != null)
			{
				refresh.run();
			}
			if (step[0] >= STEPS)
			{
				timer.stop();
				setZoom.accept(z1);
				if (refresh != null)
				{
					refresh.run();
				}
				if (onComplete != null)
				{
					onComplete.run();
				}
			}
		});
		timer.setRepeats(true);
		timer.start();
	}
}

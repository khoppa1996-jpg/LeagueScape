package com.gridscape.util;

import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * Paints frontier fog using four quadrant images (top/bottom × left/right corners).
 * Parameters {@code north,east,south,west} are geographic (row - 1 = north, row + 1 = south, etc.), matching
 * {@link com.gridscape.util.FrontierFogHelpers}. Task and world-unlock grids lay out with
 * {@code gridy = maxRing - row}, so on screen geographic south (row + 1) sits above the cell and geographic north
 * (row - 1) below. Corner quadrants follow Java screen coords (y-down): the top edge of the cell uses geographic
 * <em>south</em> (neighbor above on screen); the bottom edge uses geographic <em>north</em> (neighbor below on screen).
 * <p>
 * When corner assets are full-tile images (all four decorative corners in one bitmap), each file is cropped to the
 * quadrant matching its name before scaling. Images smaller than 64×64 px (either dimension) are scaled without
 * cropping (dedicated corner sprites).
 */
public final class FogTileCompositor
{
	/** Source images at least this size on both axes are treated as full-tile textures and cropped per quadrant. */
	private static final int MIN_CROP_DIMENSION = 64;

	private FogTileCompositor() {}

	/**
	 * @param north east south west geographic cardinals from {@link com.gridscape.util.FrontierFogHelpers}
	 *        (neighbor at row - 1 / row + 1 / col +/- 1).
	 */
	public static void paintFogQuadrants(Graphics g, int w, int h,
		boolean north, boolean east, boolean south, boolean west,
		BufferedImage topLeft, BufferedImage topRight, BufferedImage bottomLeft, BufferedImage bottomRight)
	{
		if (w <= 0 || h <= 0) return;
		int hw = w / 2;
		int hh = h / 2;
		// Map geographic neighbors to screen edges (see class javadoc): top ← south, bottom ← north, left ← west, right ← east.
		boolean drawTL = south || west;
		boolean drawTR = south || east;
		boolean drawBL = north || west;
		boolean drawBR = north || east;
		if (!drawTL && !drawTR && !drawBL && !drawBR) return;

		Graphics2D g2 = (Graphics2D) g.create();
		try
		{
			g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			if (drawTL) drawCornerQuadrant(g2, topLeft, Quadrant.TOP_LEFT, 0, 0, hw, hh);
			if (drawTR) drawCornerQuadrant(g2, topRight, Quadrant.TOP_RIGHT, hw, 0, hw, hh);
			if (drawBL) drawCornerQuadrant(g2, bottomLeft, Quadrant.BOTTOM_LEFT, 0, hh, hw, hh);
			if (drawBR) drawCornerQuadrant(g2, bottomRight, Quadrant.BOTTOM_RIGHT, hw, hh, hw, hh);
		}
		finally
		{
			g2.dispose();
		}
	}

	private enum Quadrant
	{
		TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT
	}

	private static void drawCornerQuadrant(Graphics2D g2, BufferedImage src, Quadrant q, int dx, int dy, int dw, int dh)
	{
		if (src == null || dw <= 0 || dh <= 0) return;
		int iw = src.getWidth();
		int ih = src.getHeight();
		if (iw <= 0 || ih <= 0) return;

		int sx = 0, sy = 0, sw = iw, sh = ih;
		if (iw >= MIN_CROP_DIMENSION && ih >= MIN_CROP_DIMENSION)
		{
			sw = iw / 2;
			sh = ih / 2;
			switch (q)
			{
				case TOP_LEFT:
					sx = 0;
					sy = 0;
					break;
				case TOP_RIGHT:
					sx = sw;
					sy = 0;
					break;
				case BOTTOM_LEFT:
					sx = 0;
					sy = sh;
					break;
				case BOTTOM_RIGHT:
					sx = sw;
					sy = sh;
					break;
			}
		}
		g2.drawImage(src, dx, dy, dx + dw, dy + dh, sx, sy, sx + sw, sy + sh, null);
	}
}

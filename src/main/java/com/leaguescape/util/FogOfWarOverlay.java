package com.leaguescape.util;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Rectangle2D;

/**
 * Draws a fog-of-war layer over unrevealed grid cells: a semi-opaque veil of the panel background,
 * with softer transparent strips along edges that border revealed/visible tiles.
 */
public final class FogOfWarOverlay
{
	private FogOfWarOverlay() {}

	/**
	 * @param clearTop    Neighbor toward decreasing {@code row} (screen-up in our grids) is visible.
	 * @param clearBottom Neighbor toward increasing {@code row} is visible.
	 * @param clearLeft   Neighbor toward decreasing {@code col} is visible.
	 * @param clearRight  Neighbor toward increasing {@code col} is visible.
	 */
	public static void paint(Graphics2D g, int w, int h, boolean clearTop, boolean clearBottom,
		boolean clearLeft, boolean clearRight, Color backgroundBase)
	{
		if (w <= 0 || h <= 0)
		{
			return;
		}
		int strip = Math.max(6, Math.min(w, h) / 4);
		strip = Math.min(strip, Math.min(w, h) / 2);

		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

		int a = 205;
		Color fog = new Color(backgroundBase.getRed(), backgroundBase.getGreen(), backgroundBase.getBlue(), a);
		g.setComposite(AlphaComposite.SrcOver);
		g.setColor(fog);
		g.fillRect(0, 0, w, h);

		Graphics2D gOut = (Graphics2D) g.create();
		gOut.setComposite(AlphaComposite.DstOut);
		if (clearTop)
		{
			gOut.setPaint(new GradientPaint(0, 0, new Color(0, 0, 0, 255), 0, strip, new Color(0, 0, 0, 0)));
			gOut.fill(new Rectangle2D.Float(0, 0, w, strip));
		}
		if (clearBottom)
		{
			gOut.setPaint(new GradientPaint(0, h, new Color(0, 0, 0, 255), 0, h - strip, new Color(0, 0, 0, 0)));
			gOut.fill(new Rectangle2D.Float(0, h - strip, w, strip));
		}
		if (clearLeft)
		{
			gOut.setPaint(new GradientPaint(0, 0, new Color(0, 0, 0, 255), strip, 0, new Color(0, 0, 0, 0)));
			gOut.fill(new Rectangle2D.Float(0, 0, strip, h));
		}
		if (clearRight)
		{
			gOut.setPaint(new GradientPaint(w, 0, new Color(0, 0, 0, 255), w - strip, 0, new Color(0, 0, 0, 0)));
			gOut.fill(new Rectangle2D.Float(w - strip, 0, strip, h));
		}
		gOut.dispose();
	}
}

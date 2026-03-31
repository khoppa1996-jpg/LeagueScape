package com.leaguescape.icons;

import com.leaguescape.LeagueScapePlugin;
import java.awt.image.BufferedImage;
import net.runelite.client.util.ImageUtil;

/** Load and scale icons from plugin classpath resources. */
public final class IconCache
{
	private IconCache() {}

	public static BufferedImage loadWithFallback(String primaryPath, String fallbackPath)
	{
		BufferedImage img = load(primaryPath);
		if (img != null) return img;
		return load(fallbackPath);
	}

	/**
	 * RuneLite {@link ImageUtil#loadImageResource(Class, String)} treats paths without a leading {@code '/'}
	 * as relative to the class's package ({@code com/leaguescape/}), which breaks resources under
	 * {@code /com/taskIcons/}, {@code /com/bossicons/}, etc. Classpath-root resources must keep the leading slash.
	 */
	private static BufferedImage load(String path)
	{
		if (path == null || path.isEmpty()) return null;
		String p = path;
		if (!p.startsWith("/") && p.startsWith("com/"))
			p = "/" + p;
		try
		{
			return ImageUtil.loadImageResource(LeagueScapePlugin.class, p);
		}
		catch (Exception e)
		{
			return null;
		}
	}

	public static BufferedImage scaleToFitAllowUpscale(BufferedImage src, int maxW, int maxH)
	{
		if (src == null || maxW <= 0 || maxH <= 0) return null;
		int w = src.getWidth();
		int h = src.getHeight();
		if (w <= 0 || h <= 0) return null;
		double scale = Math.min((double) maxW / w, (double) maxH / h);
		int nw = Math.max(1, (int) Math.round(w * scale));
		int nh = Math.max(1, (int) Math.round(h * scale));
		return (nw == w && nh == h) ? src : ImageUtil.resizeImage(src, nw, nh);
	}

	public static BufferedImage scaleToLargestDimension(BufferedImage src, int targetMaxDimension)
	{
		if (src == null || targetMaxDimension <= 0) return null;
		int w = src.getWidth();
		int h = src.getHeight();
		if (w <= 0 || h <= 0) return null;
		int maxDim = Math.max(w, h);
		if (maxDim <= 0) return null;
		double scale = (double) targetMaxDimension / maxDim;
		int nw = Math.max(1, (int) Math.round(w * scale));
		int nh = Math.max(1, (int) Math.round(h * scale));
		return (nw == w && nh == h) ? src : ImageUtil.resizeImage(src, nw, nh);
	}
}

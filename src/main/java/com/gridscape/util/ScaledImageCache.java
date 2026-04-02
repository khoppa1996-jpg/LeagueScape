package com.gridscape.util;

import java.awt.Graphics;
import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.Map;
import net.runelite.client.util.ImageUtil;

/**
 * Caches {@link BufferedImage} scales produced with {@link ImageUtil#resizeImage} to avoid
 * {@link Image#getScaledInstance}, which can defer work to the image consumer and repeat work every paint.
 */
public final class ScaledImageCache
{
	private static final int MAX_ENTRIES = 512;

	private static final Map<CacheKey, BufferedImage> CACHE = new LinkedHashMap<CacheKey, BufferedImage>(256, 0.75f, true)
	{
		@Override
		protected boolean removeEldestEntry(Map.Entry<CacheKey, BufferedImage> eldest)
		{
			return size() > MAX_ENTRIES;
		}
	};

	private static final class CacheKey
	{
		final BufferedImage src;
		final int w;
		final int h;

		CacheKey(BufferedImage src, int w, int h)
		{
			this.src = src;
			this.w = w;
			this.h = h;
		}

		@Override
		public boolean equals(Object o)
		{
			if (this == o) return true;
			if (!(o instanceof CacheKey)) return false;
			CacheKey k = (CacheKey) o;
			return src == k.src && w == k.w && h == k.h;
		}

		@Override
		public int hashCode()
		{
			return System.identityHashCode(src) ^ (w * 65537) ^ (h * 131071);
		}
	}

	private ScaledImageCache()
	{
	}

	/**
	 * Returns a scaled copy of {@code src} with dimensions {@code w} x {@code h}, or {@code src}
	 * if dimensions already match. May return a cached instance.
	 */
	public static BufferedImage getScaled(BufferedImage src, int w, int h)
	{
		if (src == null || w <= 0 || h <= 0)
		{
			return null;
		}
		int sw = src.getWidth();
		int sh = src.getHeight();
		if (sw <= 0 || sh <= 0)
		{
			return null;
		}
		if (w == sw && h == sh)
		{
			return src;
		}
		synchronized (CACHE)
		{
			CacheKey key = new CacheKey(src, w, h);
			BufferedImage cached = CACHE.get(key);
			if (cached != null)
			{
				return cached;
			}
			BufferedImage out = ImageUtil.resizeImage(src, w, h);
			if (out != null)
			{
				CACHE.put(key, out);
			}
			return out;
		}
	}

	/**
	 * Draws {@code src} scaled to {@code w} x {@code h} at ({@code x}, {@code y}).
	 */
	public static void drawScaled(Graphics g, BufferedImage src, int x, int y, int w, int h)
	{
		BufferedImage scaled = getScaled(src, w, h);
		if (scaled != null)
		{
			g.drawImage(scaled, x, y, null);
		}
	}
}

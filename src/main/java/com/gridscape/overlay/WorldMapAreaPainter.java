package com.gridscape.overlay;

import com.gridscape.GridScapePlugin;
import com.gridscape.data.Area;
import com.gridscape.util.ScaledImageCache;
import java.awt.Color;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Set;
import net.runelite.api.Point;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.worldmap.WorldMap;
import net.runelite.client.util.ImageUtil;

/**
 * World-map coordinate conversion and polygon drawing for {@link GridScapeMapOverlay}.
 */
public final class WorldMapAreaPainter
{
	public static final int REGION_SIZE = 1 << 6;
	public static final int REGION_TRUNCATE = ~0x3F;
	private static final int LABEL_PADDING = 4;

	private WorldMapAreaPainter()
	{
	}

	public static Polygon worldPolygonToScreen(List<int[]> polygon, WorldMap worldMap, Rectangle worldMapRect, float pixelsPerTile)
	{
		int widthInTiles = (int) Math.ceil(worldMapRect.getWidth() / pixelsPerTile);
		int heightInTiles = (int) Math.ceil(worldMapRect.getHeight() / pixelsPerTile);
		Point worldMapPosition = worldMap.getWorldMapPosition();

		int[] xPoints = new int[polygon.size()];
		int[] yPoints = new int[polygon.size()];
		int n = 0;

		for (int[] v : polygon)
		{
			int wx = v[0];
			int wy = v[1];
			int plane = v.length > 2 ? v[2] : 0;
			if (plane != 0)
			{
				continue;
			}

			if (!worldMap.getWorldMapData().surfaceContainsPosition(wx, wy))
			{
				continue;
			}

			int yTileMax = worldMapPosition.getY() - heightInTiles / 2;
			int yTileOffset = (yTileMax - wy - 1) * -1;
			int xTileOffset = wx + widthInTiles / 2 - worldMapPosition.getX();

			int xGraphDiff = (int) (xTileOffset * pixelsPerTile);
			int yGraphDiff = (int) (yTileOffset * pixelsPerTile);
			yGraphDiff -= pixelsPerTile - Math.ceil(pixelsPerTile / 2);
			xGraphDiff += pixelsPerTile - Math.ceil(pixelsPerTile / 2);
			yGraphDiff = worldMapRect.height - yGraphDiff;
			yGraphDiff += (int) worldMapRect.getY();
			xGraphDiff += (int) worldMapRect.getX();

			xPoints[n] = xGraphDiff;
			yPoints[n] = yGraphDiff;
			n++;
		}

		if (n < 3)
		{
			return null;
		}

		int[] xTrim = new int[n];
		int[] yTrim = new int[n];
		System.arraycopy(xPoints, 0, xTrim, 0, n);
		System.arraycopy(yPoints, 0, yTrim, 0, n);
		return new Polygon(xTrim, yTrim, n);
	}

	public static Path2D.Double worldPolygonToPath2D(List<int[]> polygon, WorldMap worldMap, Rectangle worldMapRect, float pixelsPerTile)
	{
		Polygon p = worldPolygonToScreen(polygon, worldMap, worldMapRect, pixelsPerTile);
		if (p == null || p.npoints < 3) return null;
		Path2D.Double path = new Path2D.Double();
		path.moveTo(p.xpoints[0], p.ypoints[0]);
		for (int i = 1; i < p.npoints; i++)
			path.lineTo(p.xpoints[i], p.ypoints[i]);
		path.closePath();
		return path;
	}

	public static void drawAreaShapeWithHoles(Graphics2D graphics, Area area, WorldMap worldMap, Rectangle worldMapRect, float pixelsPerTile, Color fillColor, boolean outlineOnly)
	{
		List<List<int[]>> polygons = area.getPolygons();
		List<List<int[]>> holes = area.getHoles();
		boolean hasHoles = holes != null && !holes.isEmpty();
		if (hasHoles)
		{
			java.awt.geom.Area combined = new java.awt.geom.Area();
			for (List<int[]> poly : polygons)
			{
				if (poly == null || poly.size() < 3) continue;
				Path2D.Double path = worldPolygonToPath2D(poly, worldMap, worldMapRect, pixelsPerTile);
				if (path != null) combined.add(new java.awt.geom.Area(path));
			}
			for (List<int[]> hole : holes)
			{
				if (hole == null || hole.size() < 3) continue;
				Path2D.Double path = worldPolygonToPath2D(hole, worldMap, worldMapRect, pixelsPerTile);
				if (path != null) combined.subtract(new java.awt.geom.Area(path));
			}
			if (!combined.isEmpty())
			{
				if (fillColor != null && !outlineOnly)
				{
					graphics.setColor(fillColor);
					graphics.fill(combined);
				}
				if (outlineOnly)
					graphics.draw(combined);
			}
		}
		else
		{
			for (List<int[]> polygon : polygons)
			{
				if (polygon == null || polygon.size() < 3) continue;
				Polygon poly = worldPolygonToScreen(polygon, worldMap, worldMapRect, pixelsPerTile);
				if (poly != null && poly.npoints >= 3)
				{
					if (fillColor != null && !outlineOnly)
					{
						graphics.setColor(fillColor);
						graphics.fillPolygon(poly);
					}
					if (outlineOnly)
						graphics.drawPolygon(poly);
				}
			}
		}
	}

	public static void drawChunkGrid(Graphics2D graphics, WorldMap worldMap, Rectangle worldMapRect, float pixelsPerTile)
	{
		int widthInTiles = (int) Math.ceil(worldMapRect.getWidth() / pixelsPerTile);
		int heightInTiles = (int) Math.ceil(worldMapRect.getHeight() / pixelsPerTile);
		Point worldMapPosition = worldMap.getWorldMapPosition();

		int yTileMin = worldMapPosition.getY() - heightInTiles / 2;
		int xRegionMin = (worldMapPosition.getX() - widthInTiles / 2) & REGION_TRUNCATE;
		int xRegionMax = ((worldMapPosition.getX() + widthInTiles / 2) & REGION_TRUNCATE) + REGION_SIZE;
		int yRegionMin = yTileMin & REGION_TRUNCATE;
		int yRegionMax = ((worldMapPosition.getY() + heightInTiles / 2) & REGION_TRUNCATE) + REGION_SIZE;
		int regionPixelSize = (int) Math.ceil(REGION_SIZE * pixelsPerTile);

		graphics.setColor(new Color(0, 19, 36, 127));
		for (int x = xRegionMin; x < xRegionMax; x += REGION_SIZE)
		{
			for (int y = yRegionMin; y < yRegionMax; y += REGION_SIZE)
			{
				int yTileOffset = -(yTileMin - y);
				int xTileOffset = x + widthInTiles / 2 - worldMapPosition.getX();

				int xPos = (int) (xTileOffset * pixelsPerTile) + (int) worldMapRect.getX();
				int yPos = (worldMapRect.height - (int) (yTileOffset * pixelsPerTile)) + (int) worldMapRect.getY();
				yPos -= regionPixelSize;

				graphics.drawRect(xPos, yPos, regionPixelSize, regionPixelSize);
			}
		}
	}

	public static void drawAreaLabels(Graphics2D graphics, Iterable<Area> areas, WorldMap worldMap, Rectangle worldMapRect, float pixelsPerTile)
	{
		for (Area area : areas)
		{
			List<int[]> firstPoly = area.getPolygon();
			if (firstPoly == null || firstPoly.size() < 3) continue;

			double cx = 0;
			double cy = 0;
			int count = 0;
			for (int[] v : firstPoly)
			{
				if (v.length > 2 && v[2] != 0)
				{
					continue;
				}
				cx += v[0];
				cy += v[1];
				count++;
			}
			if (count == 0)
			{
				continue;
			}
			cx /= count;
			cy /= count;

			Point screen = mapWorldPointToGraphicsPoint(worldMap, worldMapRect, pixelsPerTile, (int) cx, (int) cy);
			if (screen == null)
			{
				continue;
			}

			String label = area.getDisplayName() != null ? area.getDisplayName() : area.getId();
			FontMetrics fm = graphics.getFontMetrics();
			int textWidth = fm.stringWidth(label);
			int textHeight = fm.getHeight();

			int x = screen.getX() - textWidth / 2;
			int y = screen.getY() + textHeight / 2;
			if (x < worldMapRect.x)
			{
				x = worldMapRect.x + LABEL_PADDING;
			}
			if (x + textWidth > worldMapRect.x + worldMapRect.width)
			{
				x = worldMapRect.x + worldMapRect.width - textWidth - LABEL_PADDING;
			}
			if (y < worldMapRect.y)
			{
				y = worldMapRect.y + textHeight + LABEL_PADDING;
			}
			if (y > worldMapRect.y + worldMapRect.height)
			{
				y = worldMapRect.y + worldMapRect.height - LABEL_PADDING;
			}

			graphics.setColor(Color.WHITE);
			graphics.drawString(label, x, y);
		}
	}

	public static void drawLockedAreaPadlocks(Graphics2D graphics, WorldMap worldMap, Rectangle worldMapRect, float pixelsPerTile,
		Set<String> unlocked, Iterable<Area> areas, BufferedImage worldMapPadlockIcon)
	{
		if (worldMapPadlockIcon == null) return;

		int iconSize = Math.max(12, Math.min(32, (int) (pixelsPerTile * 1.5)));
		int half = iconSize / 2;

		for (Area area : areas)
		{
			if (unlocked.contains(area.getId())) continue;
			List<List<int[]>> polygons = area.getPolygons();
			if (polygons == null) continue;

			for (List<int[]> poly : polygons)
			{
				if (poly == null || poly.size() < 3) continue;
				double cx = 0;
				double cy = 0;
				int count = 0;
				for (int[] v : poly)
				{
					if (v.length > 2 && v[2] != 0) continue;
					cx += v[0];
					cy += v[1];
					count++;
				}
				if (count == 0) continue;
				cx /= count;
				cy /= count;

				Point screen = mapWorldPointToGraphicsPoint(worldMap, worldMapRect, pixelsPerTile, (int) cx, (int) cy);
				if (screen == null) continue;
				int sx = screen.getX();
				int sy = screen.getY();
				if (sx - half < worldMapRect.x || sx + half > worldMapRect.x + worldMapRect.width
					|| sy - half < worldMapRect.y || sy + half > worldMapRect.y + worldMapRect.height)
				{
					continue;
				}
				ScaledImageCache.drawScaled(graphics, worldMapPadlockIcon, sx - half, sy - half, iconSize, iconSize);
			}
		}
	}

	public static Point mapWorldPointToGraphicsPoint(WorldMap worldMap, Rectangle worldMapRect, float pixelsPerTile, int wx, int wy)
	{
		if (!worldMap.getWorldMapData().surfaceContainsPosition(wx, wy))
		{
			return null;
		}

		int widthInTiles = (int) Math.ceil(worldMapRect.getWidth() / pixelsPerTile);
		int heightInTiles = (int) Math.ceil(worldMapRect.getHeight() / pixelsPerTile);
		Point worldMapPosition = worldMap.getWorldMapPosition();

		int yTileMax = worldMapPosition.getY() - heightInTiles / 2;
		int yTileOffset = (yTileMax - wy - 1) * -1;
		int xTileOffset = wx + widthInTiles / 2 - worldMapPosition.getX();

		int xGraphDiff = (int) (xTileOffset * pixelsPerTile);
		int yGraphDiff = (int) (yTileOffset * pixelsPerTile);
		yGraphDiff -= pixelsPerTile - Math.ceil(pixelsPerTile / 2);
		xGraphDiff += pixelsPerTile - Math.ceil(pixelsPerTile / 2);
		yGraphDiff = worldMapRect.height - yGraphDiff;
		yGraphDiff += (int) worldMapRect.getY();
		xGraphDiff += (int) worldMapRect.getX();

		return new Point(xGraphDiff, yGraphDiff);
	}

	public static WorldPoint screenToWorldPoint(WorldMap worldMap, Rectangle worldMapRect, float pixelsPerTile, int sx, int sy)
	{
		if (!worldMapRect.contains(sx, sy))
		{
			return null;
		}
		int widthInTiles = (int) Math.ceil(worldMapRect.getWidth() / pixelsPerTile);
		int heightInTiles = (int) Math.ceil(worldMapRect.getHeight() / pixelsPerTile);
		Point worldMapPosition = worldMap.getWorldMapPosition();
		double halfTile = pixelsPerTile - Math.ceil(pixelsPerTile / 2);

		double xTileOffset = (sx - worldMapRect.getX() - halfTile) / pixelsPerTile;
		int wx = worldMapPosition.getX() - widthInTiles / 2 + (int) Math.round(xTileOffset);

		double yTileOffset = (worldMapRect.getY() + worldMapRect.getHeight() - sy + halfTile) / pixelsPerTile;
		int yTileMax = worldMapPosition.getY() - heightInTiles / 2;
		int wy = yTileMax + (int) Math.round(yTileOffset) - 1;

		if (!worldMap.getWorldMapData().surfaceContainsPosition(wx, wy))
		{
			return null;
		}
		return new WorldPoint(wx, wy, 0);
	}

	/** Lazy padlock for {@link #drawLockedAreaPadlocks}; overlay may cache the result. */
	public static BufferedImage loadWorldMapPadlockIcon()
	{
		return ImageUtil.loadImageResource(GridScapePlugin.class, "padlock_icon.png");
	}
}

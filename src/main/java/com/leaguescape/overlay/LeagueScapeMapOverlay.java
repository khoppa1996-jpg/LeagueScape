package com.leaguescape.overlay;

import com.leaguescape.LeagueScapeConfig;
import com.leaguescape.LeagueScapePlugin;
import com.leaguescape.area.AreaGraphService;
import com.leaguescape.data.Area;
import com.leaguescape.points.PointsService;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.Set;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import net.runelite.api.Client;
import net.runelite.api.Point;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.worldmap.WorldMap;
import net.runelite.client.input.MouseListener;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Draws LeagueScape areas (locked, unlocked, unlockable) on the world map when it's open.
 * Hover: highlights area with white border. Right-click: shows details and Unlock button.
 */
public class LeagueScapeMapOverlay extends Overlay implements MouseListener
{
	private static final int REGION_SIZE = 1 << 6;
	private static final int REGION_TRUNCATE = ~0x3F;
	private static final int LABEL_PADDING = 4;
	private static final float HOVER_BORDER_WIDTH = 2.5f;
	private static final Color HOVER_BORDER_COLOR = Color.WHITE;

	private final Client client;
	private final AreaGraphService areaGraphService;
	private final LeagueScapeConfig config;
	private final PointsService pointsService;
	private final LeagueScapePlugin plugin;

	private volatile Area hoveredArea = null;

	public LeagueScapeMapOverlay(Client client, AreaGraphService areaGraphService, LeagueScapeConfig config,
		PointsService pointsService, LeagueScapePlugin plugin)
	{
		this.client = client;
		this.areaGraphService = areaGraphService;
		this.config = config;
		this.pointsService = pointsService;
		this.plugin = plugin;
		setPosition(OverlayPosition.DYNAMIC);
		setPriority(Overlay.PRIORITY_HIGH);
		setLayer(OverlayLayer.MANUAL);
		drawAfterInterface(InterfaceID.WORLDMAP);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.drawMapOverlay())
		{
			return null;
		}

		Widget map = client.getWidget(InterfaceID.Worldmap.MAP_CONTAINER);
		if (map == null)
		{
			return null;
		}

		WorldMap worldMap = client.getWorldMap();
		float pixelsPerTile = worldMap.getWorldMapZoom();
		Rectangle worldMapRect = map.getBounds();
		graphics.setClip(worldMapRect);

		Set<String> unlocked = areaGraphService.getUnlockedAreaIds();
		List<Area> unlockable = areaGraphService.getUnlockableNeighbors();

		// Draw area polygons
		for (Area area : areaGraphService.getAreas())
		{
			if (area.getPolygon() == null || area.getPolygon().size() < 3)
			{
				continue;
			}

			Color color;
			if (unlocked.contains(area.getId()))
			{
				color = config.mapUnlockedColor();
			}
			else if (unlockable.contains(area))
			{
				color = config.mapUnlockableColor();
			}
			else
			{
				color = config.mapLockedColor();
			}

			Polygon poly = worldPolygonToScreen(area.getPolygon(), worldMap, worldMapRect, pixelsPerTile);
			if (poly != null && poly.npoints >= 3)
			{
				graphics.setColor(color);
				graphics.fillPolygon(poly);
			}
		}

		// Hover: white border on hovered area
		Area hovered = hoveredArea;
		if (hovered != null && hovered.getPolygon() != null && hovered.getPolygon().size() >= 3)
		{
			Polygon hoverPoly = worldPolygonToScreen(hovered.getPolygon(), worldMap, worldMapRect, pixelsPerTile);
			if (hoverPoly != null && hoverPoly.npoints >= 3)
			{
				graphics.setColor(HOVER_BORDER_COLOR);
				graphics.setStroke(new BasicStroke(HOVER_BORDER_WIDTH));
				graphics.drawPolygon(hoverPoly);
			}
		}

		// Draw chunk grid (like region-locker)
		if (config.drawMapGrid())
		{
			drawChunkGrid(graphics, worldMap, worldMapRect, pixelsPerTile);
		}

		// Draw area labels
		if (config.drawAreaLabels())
		{
			drawAreaLabels(graphics, worldMap, worldMapRect, pixelsPerTile);
		}

		return null;
	}

	private Polygon worldPolygonToScreen(List<int[]> polygon, WorldMap worldMap, Rectangle worldMapRect, float pixelsPerTile)
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
				continue; // Skip non-surface for map
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

		// Trim to actual size
		int[] xTrim = new int[n];
		int[] yTrim = new int[n];
		System.arraycopy(xPoints, 0, xTrim, 0, n);
		System.arraycopy(yPoints, 0, yTrim, 0, n);
		return new Polygon(xTrim, yTrim, n);
	}

	private void drawChunkGrid(Graphics2D graphics, WorldMap worldMap, Rectangle worldMapRect, float pixelsPerTile)
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

	private void drawAreaLabels(Graphics2D graphics, WorldMap worldMap, Rectangle worldMapRect, float pixelsPerTile)
	{
		for (Area area : areaGraphService.getAreas())
		{
			if (area.getPolygon() == null || area.getPolygon().size() < 3)
			{
				continue;
			}

			// Compute centroid
			double cx = 0;
			double cy = 0;
			int count = 0;
			for (int[] v : area.getPolygon())
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

			// Ensure label is within map bounds
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

	private Point mapWorldPointToGraphicsPoint(WorldMap worldMap, Rectangle worldMapRect, float pixelsPerTile, int wx, int wy)
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

	/** Convert screen position (canvas coords) inside map rect to world tile (plane 0), or null if outside/invalid. */
	private WorldPoint screenToWorldPoint(WorldMap worldMap, Rectangle worldMapRect, float pixelsPerTile, int sx, int sy)
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

		double yTileOffset = (worldMapRect.getY() + worldMapRect.getHeight() - sy - halfTile) / pixelsPerTile;
		int yTileMax = worldMapPosition.getY() - heightInTiles / 2;
		int wy = yTileMax + (int) Math.round(yTileOffset) - 1;

		if (!worldMap.getWorldMapData().surfaceContainsPosition(wx, wy))
		{
			return null;
		}
		return new WorldPoint(wx, wy, 0);
	}

	private void updateHoveredArea(int screenX, int screenY)
	{
		if (!config.drawMapOverlay())
		{
			hoveredArea = null;
			return;
		}
		Widget map = client.getWidget(InterfaceID.Worldmap.MAP_CONTAINER);
		if (map == null)
		{
			hoveredArea = null;
			return;
		}
		Rectangle worldMapRect = map.getBounds();
		if (!worldMapRect.contains(screenX, screenY))
		{
			hoveredArea = null;
			return;
		}
		WorldMap worldMap = client.getWorldMap();
		float pixelsPerTile = worldMap.getWorldMapZoom();
		WorldPoint wp = screenToWorldPoint(worldMap, worldMapRect, pixelsPerTile, screenX, screenY);
		hoveredArea = (wp != null) ? areaGraphService.getAreaAt(wp) : null;
	}

	private void showAreaDetailsPopup(Area area)
	{
		if (area == null) return;
		String displayName = area.getDisplayName() != null ? area.getDisplayName() : area.getId();
		int cost = area.getUnlockCost();
		Set<String> unlocked = areaGraphService.getUnlockedAreaIds();
		List<Area> unlockable = areaGraphService.getUnlockableNeighbors();
		boolean canUnlock = unlockable.contains(area) && pointsService.getSpendable() >= cost;

		JPanel panel = new JPanel();
		panel.setLayout(new java.awt.BorderLayout(5, 5));
		panel.add(new JLabel("<html><b>" + displayName + "</b></html>"), java.awt.BorderLayout.NORTH);
		panel.add(new JLabel("Unlock cost: " + cost + " point" + (cost != 1 ? "s" : "")), java.awt.BorderLayout.CENTER);
		JButton unlockBtn = new JButton("Unlock");
		unlockBtn.setEnabled(canUnlock);
		if (canUnlock)
		{
			unlockBtn.addActionListener(e -> {
				if (plugin.unlockArea(area.getId(), cost))
				{
					java.awt.Window w = SwingUtilities.getWindowAncestor((java.awt.Component) e.getSource());
					if (w != null) w.dispose();
				}
			});
		}
		panel.add(unlockBtn, java.awt.BorderLayout.SOUTH);

		SwingUtilities.invokeLater(() -> {
			JOptionPane.showMessageDialog(client.getCanvas(), panel, "Area: " + displayName, JOptionPane.PLAIN_MESSAGE);
		});
	}

	@Override
	public MouseEvent mouseMoved(MouseEvent event)
	{
		updateHoveredArea(event.getX(), event.getY());
		return event;
	}

	@Override
	public MouseEvent mousePressed(MouseEvent event)
	{
		if (event.getButton() != MouseEvent.BUTTON3) return event;
		updateHoveredArea(event.getX(), event.getY());
		if (hoveredArea != null)
		{
			showAreaDetailsPopup(hoveredArea);
		}
		return event;
	}

	@Override
	public MouseEvent mouseReleased(MouseEvent event) { return event; }
	@Override
	public MouseEvent mouseClicked(MouseEvent event) { return event; }
	@Override
	public MouseEvent mouseEntered(MouseEvent event) { return event; }
	@Override
	public MouseEvent mouseExited(MouseEvent event) { hoveredArea = null; return event; }
	@Override
	public MouseEvent mouseDragged(MouseEvent event) { return event; }
}

package com.leaguescape.overlay;

import com.leaguescape.LeagueScapeConfig;
import com.leaguescape.LeagueScapePlugin;
import com.leaguescape.area.AreaGraphService;
import com.leaguescape.data.Area;
import com.leaguescape.data.AreaStatus;
import com.leaguescape.points.AreaCompletionService;
import com.leaguescape.points.PointsService;
import com.leaguescape.task.TaskState;
import com.leaguescape.task.TaskTile;
import com.leaguescape.task.TaskGridService;
import com.leaguescape.wiki.OsrsWikiApiService;
import com.leaguescape.LeagueScapeSounds;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Frame;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JCheckBox;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import net.runelite.api.Client;
import net.runelite.client.util.ImageUtil;
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
 * World-map overlay for LeagueScape. Draws area polygons (locked/unlocked/unlockable) when the
 * world map is open; hover highlights an area with a white border; right-click opens an area
 * details popup (description, status, Unlock button, Tasks button). Tasks button opens the task
 * grid popup for that area (tiles, claim/complete, icons from task type or Wiki). Also handles
 * polygon editing on the map (corner markers, move corner, add corner) when the plugin is in edit
 * mode. Renders on the map layer; does not draw on the game scene (use LockedRegionOverlay for
 * that). All popups and dialogs are created on the EDT; game-thread code uses SwingUtilities.invokeLater
 * where needed.
 */
public class LeagueScapeMapOverlay extends Overlay implements MouseListener
{
	private static final int REGION_SIZE = 1 << 6;
	private static final int REGION_TRUNCATE = ~0x3F;
	private static final int LABEL_PADDING = 4;
	private static final float HOVER_BORDER_WIDTH = 2.5f;
	private static final Color HOVER_BORDER_COLOR = Color.WHITE;
	private static final int CORNER_MARKER_RADIUS = 4;
	private static final int CORNER_HIT_RADIUS = 10;
	private static final Color CORNER_MARKER_COLOR = new Color(255, 255, 255, 200);
	private static final Color CORNER_MARKER_EDIT_COLOR = new Color(255, 220, 100, 220);
	private static final Color CORNER_MARKER_MOVE_COLOR = new Color(255, 180, 80, 255);

	private final Client client;
	private final AreaGraphService areaGraphService;
	private final LeagueScapeConfig config;
	private final PointsService pointsService;
	private final AreaCompletionService areaCompletionService;
	private final LeagueScapePlugin plugin;
	private final TaskGridService taskGridService;
	private final OsrsWikiApiService wikiApi;
	private final net.runelite.client.audio.AudioPlayer audioPlayer;
	private volatile Area hoveredArea = null;
	/** When non-null, we are editing this area's polygon on the map. editingCorners is the current polygon (first only). */
	private volatile String editingAreaId = null;
	private volatile List<int[]> editingCorners = null;
	/** Index of corner being moved; next left-click sets its position. -1 = none. */
	private volatile int moveCornerIndex = -1;

	public LeagueScapeMapOverlay(Client client, AreaGraphService areaGraphService, LeagueScapeConfig config,
		PointsService pointsService, AreaCompletionService areaCompletionService, LeagueScapePlugin plugin,
		TaskGridService taskGridService, OsrsWikiApiService wikiApi,
		net.runelite.client.audio.AudioPlayer audioPlayer)
	{
		this.client = client;
		this.areaGraphService = areaGraphService;
		this.config = config;
		this.pointsService = pointsService;
		this.areaCompletionService = areaCompletionService;
		this.plugin = plugin;
		this.taskGridService = taskGridService;
		this.wikiApi = wikiApi;
		this.audioPlayer = audioPlayer;
		setPosition(OverlayPosition.DYNAMIC);
		setPriority(Overlay.PRIORITY_HIGH);
		setLayer(OverlayLayer.MANUAL);
		drawAfterInterface(InterfaceID.WORLDMAP);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		// When in Edit mode, always draw so all polygon corners appear on the map
		boolean inEditMode = plugin.isEditingArea() || (editingAreaId != null && editingCorners != null);
		if (!config.drawMapOverlay() && !inEditMode)
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
		java.util.Set<String> completedIds = (config.unlockMode() == LeagueScapeConfig.UnlockMode.POINTS_TO_COMPLETE)
			? areaCompletionService.getEffectiveCompletedAreaIds()
			: null;
		List<Area> unlockable = areaGraphService.getUnlockableNeighbors(completedIds);

		// Draw area polygons (all polygons per area for locked/unlocked/unlockable); apply holes so they appear cut out
		for (Area area : areaGraphService.getAreas())
		{
			if (area.getPolygons() == null) continue;

			Color color;
			if (unlocked.contains(area.getId()))
				color = config.mapUnlockedColor();
			else if (unlockable.contains(area))
				color = config.mapUnlockableColor();
			else
				color = config.mapLockedColor();

			drawAreaShapeWithHoles((Graphics2D) graphics, area, worldMap, worldMapRect, pixelsPerTile, color, false);
		}

		// Hover: white border on hovered area (with holes so outline is correct)
		Area hovered = hoveredArea;
		if (hovered != null && hovered.getPolygons() != null)
		{
			graphics.setColor(HOVER_BORDER_COLOR);
			graphics.setStroke(new BasicStroke(HOVER_BORDER_WIDTH));
			drawAreaShapeWithHoles((Graphics2D) graphics, hovered, worldMap, worldMapRect, pixelsPerTile, null, true);
		}

		// Corner markers: overlay map-edit state, plugin Area Edit mode, or Add New Area mode
		boolean isEditMode = (editingAreaId != null && editingCorners != null);
		boolean pluginEditMode = plugin.isEditingArea() && !plugin.isAddNewAreaMode();
		boolean addNewAreaMode = plugin.isAddNewAreaMode();
		if (pluginEditMode)
		{
			// Draw all polygons (completed + current); if there are holes, draw shape with holes cut out
			List<List<int[]>> allPolygons = plugin.getAllEditingPolygons();
			List<List<int[]>> editingHoles = plugin.getEditingHoles() != null ? plugin.getEditingHoles() : Collections.<List<int[]>>emptyList();
			boolean hasHoles = editingHoles != null && !editingHoles.isEmpty();
			if (hasHoles)
			{
				java.awt.geom.Area combined = new java.awt.geom.Area();
				for (List<int[]> poly : allPolygons)
				{
					if (poly == null || poly.size() < 3) continue;
					Path2D.Double path = worldPolygonToPath2D(poly, worldMap, worldMapRect, pixelsPerTile);
					if (path != null) combined.add(new java.awt.geom.Area(path));
				}
				for (List<int[]> hole : editingHoles)
				{
					if (hole == null || hole.size() < 3) continue;
					Path2D.Double path = worldPolygonToPath2D(hole, worldMap, worldMapRect, pixelsPerTile);
					if (path != null) combined.subtract(new java.awt.geom.Area(path));
				}
				if (!combined.isEmpty())
				{
					graphics.setColor(new Color(config.mapUnlockedColor().getRed(), config.mapUnlockedColor().getGreen(), config.mapUnlockedColor().getBlue(), 80));
					((Graphics2D) graphics).fill(combined);
					graphics.setColor(CORNER_MARKER_COLOR);
					graphics.setStroke(new BasicStroke(1.5f));
					((Graphics2D) graphics).draw(combined);
				}
			}
			else
			{
				for (List<int[]> poly : allPolygons)
				{
					if (poly == null || poly.isEmpty()) continue;
					Polygon screenPoly = worldPolygonToScreen(poly, worldMap, worldMapRect, pixelsPerTile);
					if (screenPoly != null && screenPoly.npoints >= 3)
					{
						graphics.setColor(new Color(config.mapUnlockedColor().getRed(), config.mapUnlockedColor().getGreen(), config.mapUnlockedColor().getBlue(), 80));
						graphics.fillPolygon(screenPoly);
						graphics.setColor(CORNER_MARKER_COLOR);
						graphics.setStroke(new BasicStroke(1.5f));
						graphics.drawPolygon(screenPoly);
					}
				}
			}
			// Corner markers for all polygons
			for (List<int[]> poly : allPolygons)
			{
				if (poly == null || poly.isEmpty()) continue;
				for (int[] v : poly)
				{
					Point screen = mapWorldPointToGraphicsPoint(worldMap, worldMapRect, pixelsPerTile, v[0], v[1]);
					if (screen == null) continue;
					if (!worldMapRect.contains(screen.getX(), screen.getY())) continue;
					graphics.setColor(CORNER_MARKER_COLOR);
					graphics.fillOval(screen.getX() - CORNER_MARKER_RADIUS, screen.getY() - CORNER_MARKER_RADIUS,
						CORNER_MARKER_RADIUS * 2, CORNER_MARKER_RADIUS * 2);
				}
			}
			List<int[]> currentCorners = plugin.getEditingCorners();
			int movingIdx = plugin.getMoveCornerIndex();
			for (int i = 0; i < currentCorners.size(); i++)
			{
				int[] v = currentCorners.get(i);
				Point screen = mapWorldPointToGraphicsPoint(worldMap, worldMapRect, pixelsPerTile, v[0], v[1]);
				if (screen == null) continue;
				if (!worldMapRect.contains(screen.getX(), screen.getY())) continue;
				if (i == movingIdx)
					graphics.setColor(CORNER_MARKER_MOVE_COLOR);
				else
					graphics.setColor(CORNER_MARKER_EDIT_COLOR);
				graphics.fillOval(screen.getX() - CORNER_MARKER_RADIUS, screen.getY() - CORNER_MARKER_RADIUS,
					CORNER_MARKER_RADIUS * 2, CORNER_MARKER_RADIUS * 2);
			}
			if (currentCorners.size() >= 3)
			{
				Polygon editPoly = worldPolygonToScreen(currentCorners, worldMap, worldMapRect, pixelsPerTile);
				if (editPoly != null && editPoly.npoints >= 3)
				{
					graphics.setColor(new Color(config.mapUnlockedColor().getRed(), config.mapUnlockedColor().getGreen(), config.mapUnlockedColor().getBlue(), 120));
					graphics.fillPolygon(editPoly);
					graphics.setColor(CORNER_MARKER_EDIT_COLOR);
					graphics.setStroke(new BasicStroke(2f));
					graphics.drawPolygon(editPoly);
				}
			}
		}
		else if (isEditMode)
		{
			// Draw other polygons of this area (read-only), then current polygon being edited
			Area area = areaGraphService.getArea(editingAreaId);
			if (area != null && area.getPolygons() != null)
			{
				for (int p = 0; p < area.getPolygons().size(); p++)
				{
					List<int[]> poly = (p == 0) ? editingCorners : area.getPolygons().get(p);
					if (poly == null || poly.isEmpty()) continue;
					for (int[] v : poly)
					{
						Point screen = mapWorldPointToGraphicsPoint(worldMap, worldMapRect, pixelsPerTile, v[0], v[1]);
						if (screen == null) continue;
						if (!worldMapRect.contains(screen.getX(), screen.getY())) continue;
						graphics.setColor(CORNER_MARKER_COLOR);
						graphics.fillOval(screen.getX() - CORNER_MARKER_RADIUS, screen.getY() - CORNER_MARKER_RADIUS,
							CORNER_MARKER_RADIUS * 2, CORNER_MARKER_RADIUS * 2);
					}
					Polygon screenPoly = worldPolygonToScreen(poly, worldMap, worldMapRect, pixelsPerTile);
					if (screenPoly != null && screenPoly.npoints >= 3)
					{
						graphics.setColor(new Color(config.mapUnlockedColor().getRed(), config.mapUnlockedColor().getGreen(), config.mapUnlockedColor().getBlue(), 80));
						graphics.fillPolygon(screenPoly);
						graphics.setColor(CORNER_MARKER_COLOR);
						graphics.setStroke(new BasicStroke(1.5f));
						graphics.drawPolygon(screenPoly);
					}
				}
			}
			// Current polygon corners with move highlight
			List<int[]> cornersToDraw = editingCorners;
			int movingIdx = moveCornerIndex;
			for (int i = 0; i < cornersToDraw.size(); i++)
			{
				int[] v = cornersToDraw.get(i);
				Point screen = mapWorldPointToGraphicsPoint(worldMap, worldMapRect, pixelsPerTile, v[0], v[1]);
				if (screen == null) continue;
				if (!worldMapRect.contains(screen.getX(), screen.getY())) continue;
				if (i == movingIdx)
					graphics.setColor(CORNER_MARKER_MOVE_COLOR);
				else
					graphics.setColor(CORNER_MARKER_EDIT_COLOR);
				graphics.fillOval(screen.getX() - CORNER_MARKER_RADIUS, screen.getY() - CORNER_MARKER_RADIUS,
					CORNER_MARKER_RADIUS * 2, CORNER_MARKER_RADIUS * 2);
			}
		}
		else if (addNewAreaMode)
		{
			// Show corners of all polygons of all existing areas (read-only) when adding a new area
			for (Area area : areaGraphService.getAreas())
			{
				if (area.getPolygons() == null) continue;
				for (List<int[]> polygon : area.getPolygons())
				{
					if (polygon == null) continue;
					for (int[] v : polygon)
					{
						Point screen = mapWorldPointToGraphicsPoint(worldMap, worldMapRect, pixelsPerTile, v[0], v[1]);
						if (screen == null) continue;
						if (!worldMapRect.contains(screen.getX(), screen.getY())) continue;
						graphics.setColor(CORNER_MARKER_COLOR);
						graphics.fillOval(screen.getX() - CORNER_MARKER_RADIUS, screen.getY() - CORNER_MARKER_RADIUS,
							CORNER_MARKER_RADIUS * 2, CORNER_MARKER_RADIUS * 2);
					}
				}
			}
			// Draw completed polygons of the new area (same as edit mode: "Begin new polygon" keeps them)
			for (List<int[]> poly : plugin.getEditingPolygons())
			{
				if (poly == null || poly.isEmpty()) continue;
				for (int[] v : poly)
				{
					Point screen = mapWorldPointToGraphicsPoint(worldMap, worldMapRect, pixelsPerTile, v[0], v[1]);
					if (screen == null) continue;
					if (!worldMapRect.contains(screen.getX(), screen.getY())) continue;
					graphics.setColor(CORNER_MARKER_COLOR);
					graphics.fillOval(screen.getX() - CORNER_MARKER_RADIUS, screen.getY() - CORNER_MARKER_RADIUS,
						CORNER_MARKER_RADIUS * 2, CORNER_MARKER_RADIUS * 2);
				}
				Polygon screenPoly = worldPolygonToScreen(poly, worldMap, worldMapRect, pixelsPerTile);
				if (screenPoly != null && screenPoly.npoints >= 3)
				{
					graphics.setColor(new Color(config.mapUnlockedColor().getRed(), config.mapUnlockedColor().getGreen(), config.mapUnlockedColor().getBlue(), 80));
					graphics.fillPolygon(screenPoly);
					graphics.setColor(CORNER_MARKER_COLOR);
					graphics.setStroke(new BasicStroke(1.5f));
					graphics.drawPolygon(screenPoly);
				}
			}
			// Draw the new area's current polygon corners (the one being built)
			{
				List<int[]> newCorners = plugin.getEditingCorners();
				for (int[] v : newCorners)
				{
					Point screen = mapWorldPointToGraphicsPoint(worldMap, worldMapRect, pixelsPerTile, v[0], v[1]);
					if (screen == null) continue;
					if (!worldMapRect.contains(screen.getX(), screen.getY())) continue;
					graphics.setColor(CORNER_MARKER_EDIT_COLOR);
					graphics.fillOval(screen.getX() - CORNER_MARKER_RADIUS, screen.getY() - CORNER_MARKER_RADIUS,
						CORNER_MARKER_RADIUS * 2, CORNER_MARKER_RADIUS * 2);
				}
				if (newCorners.size() >= 3)
				{
					Polygon newPoly = worldPolygonToScreen(newCorners, worldMap, worldMapRect, pixelsPerTile);
					if (newPoly != null && newPoly.npoints >= 3)
					{
						graphics.setColor(new Color(config.mapUnlockedColor().getRed(), config.mapUnlockedColor().getGreen(), config.mapUnlockedColor().getBlue(), 120));
						graphics.fillPolygon(newPoly);
						graphics.setColor(CORNER_MARKER_EDIT_COLOR);
						graphics.setStroke(new BasicStroke(2f));
						graphics.drawPolygon(newPoly);
					}
				}
			}
		}
		// Corners are only shown in Edit Area mode or Add New Area mode; not when just hovering

		// In edit mode, draw the editing polygon outline (and fill if >= 3 points)
		if (isEditMode && editingCorners.size() >= 3)
		{
			Polygon editPoly = worldPolygonToScreen(editingCorners, worldMap, worldMapRect, pixelsPerTile);
			if (editPoly != null && editPoly.npoints >= 3)
			{
				graphics.setColor(new Color(config.mapUnlockedColor().getRed(), config.mapUnlockedColor().getGreen(), config.mapUnlockedColor().getBlue(), 120));
				graphics.fillPolygon(editPoly);
				graphics.setColor(CORNER_MARKER_EDIT_COLOR);
				graphics.setStroke(new BasicStroke(2f));
				graphics.drawPolygon(editPoly);
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

	/** Converts world polygon to screen Path2D for use with Area (fill with holes). */
	private Path2D.Double worldPolygonToPath2D(List<int[]> polygon, WorldMap worldMap, Rectangle worldMapRect, float pixelsPerTile)
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

	/** Draw an area's shape (polygons minus holes). fillColor non-null = fill; outlineOnly true = draw outline only (e.g. hover). */
	private void drawAreaShapeWithHoles(Graphics2D graphics, Area area, WorldMap worldMap, Rectangle worldMapRect, float pixelsPerTile, Color fillColor, boolean outlineOnly)
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
			// Use first polygon for label placement
			List<int[]> firstPoly = area.getPolygon();
			if (firstPoly == null || firstPoly.size() < 3) continue;

			// Compute centroid of first polygon
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

		// Inverse of mapWorldPointToGraphicsPoint: same halfTile and tile-offset logic so click lands on correct tile
		double xTileOffset = (sx - worldMapRect.getX() - halfTile) / pixelsPerTile;
		int wx = worldMapPosition.getX() - widthInTiles / 2 + (int) Math.round(xTileOffset);

		// Forward has screenY = worldMapRect.getY() + height - (yTileOffset*pixelsPerTile) + halfTile, so inverse:
		double yTileOffset = (worldMapRect.getY() + worldMapRect.getHeight() - sy + halfTile) / pixelsPerTile;
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

	/** Start editing the given area's polygon on the map. Copies first polygon into editingCorners. */
	private void startMapEditMode(Area area)
	{
		if (area == null || area.getPolygon() == null) return;
		List<int[]> copy = new ArrayList<>();
		for (int[] v : area.getPolygon())
		{
			copy.add(new int[]{ v[0], v[1], v.length > 2 ? v[2] : 0 });
		}
		editingAreaId = area.getId();
		editingCorners = copy;
		moveCornerIndex = -1;
	}

	private void exitMapEditMode(boolean save)
	{
		if (save && plugin.isEditingArea())
		{
			List<List<int[]>> all = plugin.getAllEditingPolygons();
			if (!all.isEmpty() && all.stream().noneMatch(p -> p == null || p.size() < 3))
			{
				Area current = areaGraphService.getArea(plugin.getEditingAreaId());
				if (current != null)
				{
					List<List<int[]>> holesToSave = plugin.getEditingHoles() != null ? plugin.getEditingHoles() : (current.getHoles() != null ? current.getHoles() : Collections.emptyList());
					Area updated = Area.builder()
						.id(current.getId())
						.displayName(current.getDisplayName())
						.description(current.getDescription())
						.polygons(all)
						.holes(holesToSave)
						.includes(current.getIncludes())
						.neighbors(current.getNeighbors())
						.unlockCost(current.getUnlockCost())
						.pointsToComplete(current.getPointsToComplete())
						.build();
					areaGraphService.saveCustomArea(updated);
				}
			}
			plugin.stopEditing();
		}
		else if (save && editingAreaId != null && editingCorners != null && editingCorners.size() >= 3)
		{
			Area current = areaGraphService.getArea(editingAreaId);
			if (current != null)
			{
				List<List<int[]>> holesToSave = plugin.getEditingHoles() != null ? plugin.getEditingHoles() : (current.getHoles() != null ? current.getHoles() : Collections.emptyList());
				Area updated = Area.builder()
					.id(current.getId())
					.displayName(current.getDisplayName())
					.description(current.getDescription())
					.polygons(Collections.singletonList(new ArrayList<>(editingCorners)))
					.holes(holesToSave)
					.includes(current.getIncludes())
					.neighbors(current.getNeighbors())
					.unlockCost(current.getUnlockCost())
					.pointsToComplete(current.getPointsToComplete())
					.build();
				areaGraphService.saveCustomArea(updated);
			}
		}
		else if (!save && plugin.isEditingArea())
		{
			plugin.stopEditing();
		}
		editingAreaId = null;
		editingCorners = null;
		moveCornerIndex = -1;
	}

	/** Returns corner index if (screenX, screenY) is within CORNER_HIT_RADIUS of a corner; -1 otherwise. */
	private int getCornerIndexAtScreen(int screenX, int screenY)
	{
		List<int[]> corners = plugin.isEditingArea() ? plugin.getEditingCorners() : editingCorners;
		if (corners == null) return -1;
		Widget map = client.getWidget(InterfaceID.Worldmap.MAP_CONTAINER);
		if (map == null) return -1;
		Rectangle worldMapRect = map.getBounds();
		WorldMap worldMap = client.getWorldMap();
		float pixelsPerTile = worldMap.getWorldMapZoom();
		int best = -1;
		int bestDistSq = CORNER_HIT_RADIUS * CORNER_HIT_RADIUS + 1;
		for (int i = 0; i < corners.size(); i++)
		{
			int[] v = corners.get(i);
			Point screen = mapWorldPointToGraphicsPoint(worldMap, worldMapRect, pixelsPerTile, v[0], v[1]);
			if (screen == null) continue;
			int dx = screen.getX() - screenX;
			int dy = screen.getY() - screenY;
			int dSq = dx * dx + dy * dy;
			if (dSq <= CORNER_HIT_RADIUS * CORNER_HIT_RADIUS && dSq < bestDistSq)
			{
				bestDistSq = dSq;
				best = i;
			}
		}
		return best;
	}

	/** Signed area of a polygon (positive = counterclockwise, negative = clockwise). */
	private static double polygonSignedArea(List<int[]> poly)
	{
		if (poly == null || poly.size() < 3) return 0;
		double a = 0;
		int n = poly.size();
		for (int i = 0; i < n; i++)
		{
			int[] p = poly.get(i);
			int[] q = poly.get((i + 1) % n);
			a += (double) p[0] * q[1] - (double) q[0] * p[1];
		}
		return 0.5 * a;
	}

	/** Extract all closed subpaths from a Java Area as lists of [x, y, plane] corners. */
	private static List<List<int[]>> extractPolygonsFromArea(java.awt.geom.Area area)
	{
		if (area == null || area.isEmpty()) return new ArrayList<>();
		List<List<int[]>> result = new ArrayList<>();
		float[] coords = new float[6];
		List<int[]> current = null;
		for (PathIterator it = area.getPathIterator(null); !it.isDone(); it.next())
		{
			switch (it.currentSegment(coords))
			{
				case PathIterator.SEG_MOVETO:
					current = new ArrayList<>();
					current.add(new int[]{ (int) Math.round(coords[0]), (int) Math.round(coords[1]), 0 });
					break;
				case PathIterator.SEG_LINETO:
					if (current != null)
						current.add(new int[]{ (int) Math.round(coords[0]), (int) Math.round(coords[1]), 0 });
					break;
				case PathIterator.SEG_CLOSE:
					if (current != null && current.size() >= 3)
						result.add(current);
					current = null;
					break;
				default:
					break;
			}
		}
		return result;
	}

	/**
	 * Paint-bucket fill: start from the user's bounding polygon and "fill" the space,
	 * using the edges of surrounding area polygons as the boundary. Result = (bounding polygon minus all other areas).
	 * The boundary of that filled region follows the user's polygon and the "shoreline" of other areas.
	 * Returns the main (exterior) boundary polygon and a list of holes (islands inside the fill).
	 */
	private void fillUsingOthersCorners()
	{
		List<int[]> bounding = plugin.isEditingArea() ? plugin.getEditingCorners() : (editingCorners != null ? editingCorners : Collections.<int[]>emptyList());
		if (bounding == null || bounding.size() < 3)
		{
			client.addChatMessage(net.runelite.api.ChatMessageType.GAMEMESSAGE, "", "Need at least 3 corners for the bounding polygon.", null);
			return;
		}
		String excludeId = plugin.isEditingArea() ? plugin.getEditingAreaId() : editingAreaId;

		// 1. Our bounding polygon as Area
		Path2D.Double ourPath = new Path2D.Double();
		ourPath.moveTo(bounding.get(0)[0], bounding.get(0)[1]);
		for (int i = 1; i < bounding.size(); i++)
			ourPath.lineTo(bounding.get(i)[0], bounding.get(i)[1]);
		ourPath.closePath();
		java.awt.geom.Area filledArea = new java.awt.geom.Area(ourPath);
		if (filledArea.isEmpty())
		{
			client.addChatMessage(net.runelite.api.ChatMessageType.GAMEMESSAGE, "", "Fill: bounding polygon is invalid.", null);
			return;
		}

		// 2. Subtract every other area's polygon from the fill; for the current area, also subtract any other
		//    polygon that lies inside the bounding one (so it becomes a hole, not filled space)
		for (Area area : areaGraphService.getAreas())
		{
			if (area.getId() == null || area.getPolygons() == null) continue;
			for (List<int[]> poly : area.getPolygons())
			{
				if (poly == null || poly.size() < 3) continue;
				Path2D.Double otherPath = new Path2D.Double();
				otherPath.moveTo(poly.get(0)[0], poly.get(0)[1]);
				for (int i = 1; i < poly.size(); i++)
					otherPath.lineTo(poly.get(i)[0], poly.get(i)[1]);
				otherPath.closePath();
				java.awt.geom.Area otherArea = new java.awt.geom.Area(otherPath);
				if (area.getId().equals(excludeId))
				{
					// Same area: subtract only if it leaves non-empty fill (don't subtract the bounding polygon)
					java.awt.geom.Area backup = (java.awt.geom.Area) filledArea.clone();
					filledArea.subtract(otherArea);
					if (filledArea.isEmpty())
						filledArea = backup;
				}
				else
					filledArea.subtract(otherArea);
			}
		}

		if (filledArea.isEmpty())
		{
			client.addChatMessage(net.runelite.api.ChatMessageType.GAMEMESSAGE, "", "Fill: no space left (fully covered by other areas).", null);
			return;
		}

		// 3. Extract polygons from the filled shape (exterior boundary + holes)
		List<List<int[]>> allRings = extractPolygonsFromArea(filledArea);
		if (allRings.isEmpty())
		{
			client.addChatMessage(net.runelite.api.ChatMessageType.GAMEMESSAGE, "", "Fill: could not extract boundary.", null);
			return;
		}

		// 4. Largest by absolute area = exterior boundary (the "shoreline"); rest = holes (islands)
		allRings.sort((a, b) -> Double.compare(Math.abs(polygonSignedArea(b)), Math.abs(polygonSignedArea(a))));
		List<int[]> mainPolygon = allRings.get(0);
		List<List<int[]>> holes = allRings.size() > 1 ? new ArrayList<>(allRings.subList(1, allRings.size())) : new ArrayList<>();

		// 5. Set as current polygon and holes
		plugin.setEditingCorners(mainPolygon);
		plugin.setEditingHoles(holes);
		if (!plugin.isEditingArea() && editingCorners != null)
		{
			editingCorners.clear();
			editingCorners.addAll(mainPolygon);
			moveCornerIndex = -1;
		}
		client.addChatMessage(net.runelite.api.ChatMessageType.GAMEMESSAGE, "",
			"Fill using others' corners: boundary updated (" + mainPolygon.size() + " corners, " + holes.size() + " hole(s)). Save (Done editing) to apply.", null);
	}

	private static final Color POPUP_BG = new Color(0x54, 0x4D, 0x41);
	private static final Color POPUP_TEXT = new Color(0xC4, 0xB8, 0x96);
	private static final Color POPUP_BORDER = new Color(0x2a, 0x28, 0x24);
	private static final Color PRESSED_INSET_SHADOW = new Color(0, 0, 0, 70);
	private static final int PRESSED_INSET = 2;
	private static final Dimension RECTANGLE_BUTTON_SIZE = new Dimension(160, 28);
	private static final int TASK_ICON_SIZE = 28;
	/** Margin on all sides of the task tile; icon is scaled to fill the rest (same size for all). */
	private static final int TASK_TILE_ICON_MARGIN = 4;
	/** Cell is 72x72; inner area after margin is 64x64. All icons scale to fit this. */
	private static final int TASK_ICON_MAX_FIT = 72 - 2 * TASK_TILE_ICON_MARGIN;
	/** Local task icon filenames under com/taskIcons/ (no wiki lookup). */
	private static final Map<String, String> TASK_TYPE_LOCAL_ICON = new HashMap<>();
	static
	{
		TASK_TYPE_LOCAL_ICON.put("Combat", "Combat_icon_(detail).png");
		TASK_TYPE_LOCAL_ICON.put("Mining", "Mining_icon_(detail).png");
		TASK_TYPE_LOCAL_ICON.put("Fishing", "Fishing_icon_(detail).png");
		TASK_TYPE_LOCAL_ICON.put("Cooking", "Cooking_icon_(detail).png");
		TASK_TYPE_LOCAL_ICON.put("Woodcutting", "Woodcutting_icon_(detail).png");
		TASK_TYPE_LOCAL_ICON.put("Prayer", "Prayer_icon_(detail).png");
		TASK_TYPE_LOCAL_ICON.put("Crafting", "Crafting_icon_(detail).png");
		TASK_TYPE_LOCAL_ICON.put("Smithing", "Smithing_icon_(detail).png");
		TASK_TYPE_LOCAL_ICON.put("Fletching", "Fletching_icon_(detail).png");
		TASK_TYPE_LOCAL_ICON.put("Herblore", "Herblore_icon_(detail).png");
		TASK_TYPE_LOCAL_ICON.put("Thieving", "Thieving_icon_(detail).png");
		TASK_TYPE_LOCAL_ICON.put("Agility", "Agility_icon_(detail).png");
		TASK_TYPE_LOCAL_ICON.put("Firemaking", "Firemaking_icon_(detail).png");
		TASK_TYPE_LOCAL_ICON.put("Farming", "Farming_icon_(detail).png");
		TASK_TYPE_LOCAL_ICON.put("Runecraft", "Runecraft_icon_(detail).png");
		TASK_TYPE_LOCAL_ICON.put("Magic", "Magic_icon_(detail).png");
		TASK_TYPE_LOCAL_ICON.put("Hunter", "Hunter_icon_(detail).png");
		TASK_TYPE_LOCAL_ICON.put("Construction", "Construction_icon_(detail).png");
		TASK_TYPE_LOCAL_ICON.put("Slayer", "Slayer_icon_(detail).png");
		TASK_TYPE_LOCAL_ICON.put("Sailing", "Sailing_icon_(detail).png");
		TASK_TYPE_LOCAL_ICON.put("Quest", "Quest.png");
		TASK_TYPE_LOCAL_ICON.put("Achievement diary", "Achievement_Diaries.png");
		TASK_TYPE_LOCAL_ICON.put("Diary", "Achievement_Diaries.png");
	}

	/** Classpath-absolute path so resources under src/main/resources/com/taskIcons/ are found. */
	private static final String TASK_ICONS_RESOURCE_PREFIX = "/com/taskIcons/";

	/** Scale image to fit inside maxW x maxH preserving aspect ratio (never scale up). */
	private static BufferedImage scaleToFit(BufferedImage src, int maxW, int maxH)
	{
		if (src == null || maxW <= 0 || maxH <= 0) return null;
		int w = src.getWidth();
		int h = src.getHeight();
		if (w <= 0 || h <= 0) return null;
		double scale = Math.min((double) maxW / w, (double) maxH / h);
		scale = Math.min(scale, 1.0); // never scale up
		int nw = Math.max(1, (int) Math.round(w * scale));
		int nh = Math.max(1, (int) Math.round(h * scale));
		return (nw == w && nh == h) ? src : ImageUtil.resizeImage(src, nw, nh);
	}

	/** True if task is "collection log" type (use Collection_log_detail.png). */
	private static boolean isCollectionLogTask(String taskType, String displayName)
	{
		if (taskType != null && taskType.toLowerCase().contains("collection"))
			return true;
		return displayName != null && displayName.toLowerCase().contains("collection log");
	}

	/** True if task is clue scroll type (use Clue_scroll_v1.png). */
	private static boolean isClueTask(String taskType, String displayName)
	{
		if (taskType != null && ("Clue".equalsIgnoreCase(taskType) || taskType.toLowerCase().contains("clue")))
			return true;
		return displayName != null && displayName.toLowerCase().contains("clue scroll");
	}

	/** True if task is "Equip a(n) [item]" (icon from Wiki API). */
	private static boolean isEquipTask(String displayName)
	{
		return displayName != null && (displayName.toLowerCase().startsWith("equip a ") || displayName.toLowerCase().startsWith("equip an "));
	}

	/** Extract item name from "Equip a Spiny Helmet" or "Equip an Attack potion". */
	private static String extractEquipItemName(String displayName)
	{
		if (displayName == null) return null;
		String d = displayName.trim();
		if (d.toLowerCase().startsWith("equip an "))
			return d.substring(9).trim();
		if (d.toLowerCase().startsWith("equip a "))
			return d.substring(8).trim();
		return null;
	}

	/** Local icon resource path for task (collection log, clue, or taskType map). Returns null for equip tasks (use Wiki). */
	private static String getLocalTaskIconPath(String taskType, String displayName)
	{
		if (isCollectionLogTask(taskType, displayName))
			return TASK_ICONS_RESOURCE_PREFIX + "Collection_log_detail.png";
		if (isClueTask(taskType, displayName))
			return TASK_ICONS_RESOURCE_PREFIX + "Clue_scroll_v1.png";
		if (taskType != null && TASK_TYPE_LOCAL_ICON.containsKey(taskType))
			return TASK_ICONS_RESOURCE_PREFIX + TASK_TYPE_LOCAL_ICON.get(taskType);
		return null;
	}

	/** Load task icon from local taskIcons resources; scale to fit inside tile. Returns null if not found. */
	private static BufferedImage loadLocalTaskIcon(String taskType, String displayName)
	{
		String path = getLocalTaskIconPath(taskType, displayName);
		if (path == null) return null;
		BufferedImage img = ImageUtil.loadImageResource(LeagueScapePlugin.class, path);
		return img != null ? scaleToFit(img, TASK_ICON_MAX_FIT, TASK_ICON_MAX_FIT) : null;
	}

	/** Default/fallback icon for task tiles. */
	private static BufferedImage loadTaskIcon()
	{
		BufferedImage img = ImageUtil.loadImageResource(LeagueScapePlugin.class, "complete_checkmark.png");
		if (img != null)
			return scaleToFit(img, TASK_ICON_SIZE, TASK_ICON_SIZE);
		return null;
	}

	/** Icon for mystery tasks (question mark) until all required areas are unlocked. */
	private static BufferedImage createMysteryIcon()
	{
		int size = TASK_ICON_SIZE;
		BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(new Color(180, 180, 180, 220));
		g.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, Math.max(14, size - 4)));
		java.awt.FontMetrics fm = g.getFontMetrics();
		String q = "?";
		int x = (size - fm.stringWidth(q)) / 2;
		int y = (size + fm.getAscent()) / 2 - 2;
		g.drawString(q, x, y);
		g.dispose();
		return img;
	}

	/** Button with empty_button_rectangle background and pressed shadow. Use for Tasks, Back to area, Complete, Claim. */
	private static JButton newRectangleButton(String text, BufferedImage buttonRect, Color textColor)
	{
		BufferedImage img = buttonRect;
		JButton b = new JButton(text)
		{
			@Override
			protected void paintComponent(Graphics g)
			{
				if (img != null)
				{
					g.drawImage(img.getScaledInstance(getWidth(), getHeight(), Image.SCALE_SMOOTH), 0, 0, null);
					g.setColor(getForeground());
					g.setFont(getFont());
					java.awt.FontMetrics fm = g.getFontMetrics();
					String t = getText();
					int x = (getWidth() - fm.stringWidth(t)) / 2;
					int y = (getHeight() + fm.getAscent() - fm.getDescent()) / 2;
					g.drawString(t, x, y);
				}
				else
				{
					super.paintComponent(g);
				}
				if (getModel().isPressed())
				{
					g.setColor(PRESSED_INSET_SHADOW);
					g.fillRect(PRESSED_INSET, PRESSED_INSET, getWidth() - 2 * PRESSED_INSET, getHeight() - 2 * PRESSED_INSET);
				}
			}
		};
		b.setForeground(textColor);
		b.setFocusPainted(false);
		b.setBorderPainted(false);
		b.setContentAreaFilled(img == null);
		b.setOpaque(img == null);
		b.setPreferredSize(RECTANGLE_BUTTON_SIZE);
		return b;
	}

	/** Simple text button with pressed shadow (when rectangle image not needed). */
	private static JButton newPopupButton(String text)
	{
		JButton b = new JButton(text)
		{
			@Override
			protected void paintComponent(Graphics g)
			{
				super.paintComponent(g);
				if (getModel().isPressed())
				{
					g.setColor(PRESSED_INSET_SHADOW);
					g.fillRect(PRESSED_INSET, PRESSED_INSET, getWidth() - 2 * PRESSED_INSET, getHeight() - 2 * PRESSED_INSET);
				}
			}
		};
		b.setFocusPainted(false);
		return b;
	}

	/** Icon-only button (e.g. close) with pressed inset shadow. */
	private static JButton newPopupButtonWithIcon(BufferedImage iconImg, Color fallbackTextColor)
	{
		JButton b = new JButton()
		{
			@Override
			protected void paintComponent(Graphics g)
			{
				super.paintComponent(g);
				if (getModel().isPressed())
				{
					g.setColor(PRESSED_INSET_SHADOW);
					g.fillRect(PRESSED_INSET, PRESSED_INSET, getWidth() - 2 * PRESSED_INSET, getHeight() - 2 * PRESSED_INSET);
				}
			}
		};
		b.setFocusPainted(false);
		b.setBorderPainted(false);
		b.setContentAreaFilled(false);
		b.setMargin(new Insets(0, 0, 0, 0));
		if (iconImg != null)
			b.setIcon(new javax.swing.ImageIcon(ImageUtil.resizeImage(iconImg, 24, 24)));
		else
		{
			b.setText("X");
			b.setForeground(fallbackTextColor);
		}
		return b;
	}

	/** Points display string: spendable total in point-buy mode, or "Points in [area]: X / Y" in points-to-complete mode. */
	private String getPointsDisplayText(Area area)
	{
		if (config.unlockMode() == LeagueScapeConfig.UnlockMode.POINTS_TO_COMPLETE)
		{
			String name = area.getDisplayName() != null ? area.getDisplayName() : area.getId();
			int earned = areaCompletionService.getPointsEarnedInArea(area.getId());
			int needed = areaCompletionService.getPointsToComplete(area.getId());
			return "Points in " + name + ": " + earned + " / " + needed;
		}
		return "Points: " + pointsService.getSpendable();
	}

	/** Shows Area Details popup. When screenX/screenY are non-null, positions the popup at that screen location (like right-click menu). */
	private void showAreaDetailsPopup(Area area, Integer screenX, Integer screenY)
	{
		if (area == null) return;
		String displayName = area.getDisplayName() != null ? area.getDisplayName() : area.getId();
		int cost = area.getUnlockCost();
		Set<String> completedIds = (config.unlockMode() == LeagueScapeConfig.UnlockMode.POINTS_TO_COMPLETE)
			? areaCompletionService.getEffectiveCompletedAreaIds()
			: null;
		List<Area> unlockable = areaGraphService.getUnlockableNeighbors(completedIds);
		boolean canUnlock = unlockable.contains(area) && pointsService.getSpendable() >= cost;
		AreaStatus status = areaCompletionService.getAreaStatus(area.getId());

		BufferedImage interfaceBg = ImageUtil.loadImageResource(LeagueScapePlugin.class, "interface_template.png");
		BufferedImage buttonRect = ImageUtil.loadImageResource(LeagueScapePlugin.class, "empty_button_rectangle.png");
		BufferedImage xBtnImg = ImageUtil.loadImageResource(LeagueScapePlugin.class, "x_button.png");
		BufferedImage checkmarkImg = ImageUtil.loadImageResource(LeagueScapePlugin.class, "complete_checkmark.png");

		SwingUtilities.invokeLater(() -> {
			Frame owner = null;
			java.awt.Window w = SwingUtilities.windowForComponent(client.getCanvas());
			if (w instanceof Frame) owner = (Frame) w;

			JDialog dialog = new JDialog(owner, "Area: " + displayName, false);
			dialog.setUndecorated(true);

			JPanel content = new JPanel()
			{
				@Override
				protected void paintComponent(Graphics g)
				{
					super.paintComponent(g);
					if (interfaceBg != null)
					{
						g.drawImage(interfaceBg.getScaledInstance(getWidth(), getHeight(), Image.SCALE_SMOOTH), 0, 0, null);
					}
					else
					{
						g.setColor(POPUP_BG);
						g.fillRect(0, 0, getWidth(), getHeight());
					}
				}
			};
			content.setLayout(new java.awt.BorderLayout(8, 8));
			content.setBackground(POPUP_BG);
			content.setBorder(new javax.swing.border.CompoundBorder(
				new javax.swing.border.LineBorder(POPUP_BORDER, 2),
				new javax.swing.border.EmptyBorder(10, 12, 10, 12)));
			content.setOpaque(true);

			// Header: title + close button
			JPanel header = new JPanel(new java.awt.BorderLayout(4, 0));
			header.setOpaque(false);
			JLabel titleLabel = new JLabel(displayName);
			titleLabel.setForeground(POPUP_TEXT);
			titleLabel.setFont(titleLabel.getFont().deriveFont(java.awt.Font.BOLD, 14f));
			header.add(titleLabel, java.awt.BorderLayout.CENTER);
			JButton closeBtn = newPopupButtonWithIcon(xBtnImg, POPUP_TEXT);
			closeBtn.addActionListener(e -> {
				LeagueScapeSounds.play(audioPlayer, LeagueScapeSounds.BUTTON_PRESS);
				dialog.dispose();
			});
			header.add(closeBtn, java.awt.BorderLayout.EAST);
			content.add(header, java.awt.BorderLayout.NORTH);

			// Center: description (if set) + status (with checkmark when complete) + cost
			JPanel center = new JPanel();
			center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));
			center.setOpaque(false);
			String description = area.getDescription();
			if (description != null && !description.trim().isEmpty())
			{
				String escaped = description.trim()
					.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
					.replace("\n", "<br>");
				JLabel descLabel = new JLabel("<html><div style='width:220px'>" + escaped + "</div></html>");
				descLabel.setForeground(POPUP_TEXT);
				center.add(descLabel);
				center.add(new JLabel(" "));
			}
			JPanel statusRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0));
			statusRow.setOpaque(false);
			JLabel statusLabel = new JLabel("Status: " + status);
			statusLabel.setForeground(POPUP_TEXT);
			statusRow.add(statusLabel);
			if (status == AreaStatus.COMPLETE && checkmarkImg != null)
			{
				statusRow.add(new JLabel(new javax.swing.ImageIcon(ImageUtil.resizeImage(checkmarkImg, 16, 16))));
			}
			center.add(statusRow);
			String costLabel = (config.unlockMode() == LeagueScapeConfig.UnlockMode.POINTS_TO_COMPLETE)
				? "Points to unlock: " + cost
				: "Unlock cost: " + cost + " point" + (cost != 1 ? "s" : "");
			JLabel costLbl = new JLabel(costLabel);
			costLbl.setForeground(POPUP_TEXT);
			center.add(costLbl);
			JLabel pointsLbl = new JLabel(getPointsDisplayText(area));
			pointsLbl.setForeground(POPUP_TEXT);
			center.add(pointsLbl);
			content.add(center, java.awt.BorderLayout.CENTER);

			// Tasks button: only when area is unlocked
			boolean areaUnlocked = areaGraphService.getUnlockedAreaIds().contains(area.getId());
			JPanel southPanel = new JPanel();
			southPanel.setLayout(new BoxLayout(southPanel, BoxLayout.Y_AXIS));
			southPanel.setOpaque(false);
			if (areaUnlocked)
			{
				JButton tasksBtn = newRectangleButton("Tasks", buttonRect, POPUP_TEXT);
				tasksBtn.addActionListener(e -> {
					LeagueScapeSounds.play(audioPlayer, LeagueScapeSounds.BUTTON_PRESS);
					dialog.dispose();
					showTaskGridPopup(area);
				});
				southPanel.add(tasksBtn);
			}

			// Unlock button (styled with empty_button_rectangle + pressed shadow)
			JButton unlockBtn = new JButton("Unlock")
			{
				@Override
				protected void paintComponent(Graphics g)
				{
					if (buttonRect != null)
					{
						g.drawImage(buttonRect.getScaledInstance(getWidth(), getHeight(), Image.SCALE_SMOOTH), 0, 0, null);
						g.setColor(getForeground());
						g.setFont(getFont());
						java.awt.FontMetrics fm = g.getFontMetrics();
						String text = getText();
						int x = (getWidth() - fm.stringWidth(text)) / 2;
						int y = (getHeight() + fm.getAscent() - fm.getDescent()) / 2;
						g.drawString(text, x, y);
					}
					else
					{
						super.paintComponent(g);
					}
					if (getModel().isPressed())
					{
						g.setColor(PRESSED_INSET_SHADOW);
						g.fillRect(PRESSED_INSET, PRESSED_INSET, getWidth() - 2 * PRESSED_INSET, getHeight() - 2 * PRESSED_INSET);
					}
				}
			};
			unlockBtn.setForeground(POPUP_TEXT);
			unlockBtn.setFocusPainted(false);
			unlockBtn.setBorderPainted(false);
			unlockBtn.setContentAreaFilled(buttonRect == null);
			unlockBtn.setOpaque(buttonRect == null);
			unlockBtn.setPreferredSize(new Dimension(160, 28));
			unlockBtn.addActionListener(e -> {
				if (canUnlock && plugin.unlockArea(area.getId(), cost))
				{
					LeagueScapeSounds.play(audioPlayer, LeagueScapeSounds.LOCKED);
					dialog.dispose();
				}
				else
				{
					LeagueScapeSounds.play(audioPlayer, LeagueScapeSounds.WRONG);
				}
			});
			southPanel.add(unlockBtn);
			content.add(southPanel, java.awt.BorderLayout.SOUTH);

			dialog.setContentPane(content);
			dialog.getRootPane().setBorder(new javax.swing.border.LineBorder(POPUP_BORDER, 2));
			dialog.pack();
			if (screenX != null && screenY != null)
			{
				int x = screenX;
				int y = screenY;
				java.awt.GraphicsConfiguration gc = (owner != null ? owner : dialog).getGraphicsConfiguration();
				java.awt.Rectangle screenBounds = gc.getBounds();
				int maxX = screenBounds.x + screenBounds.width - dialog.getWidth();
				int maxY = screenBounds.y + screenBounds.height - dialog.getHeight();
				x = Math.min(Math.max(x, screenBounds.x), maxX);
				y = Math.min(Math.max(y, screenBounds.y), maxY);
				dialog.setLocation(x, y);
			}
			else
			{
				dialog.setLocationRelativeTo(client.getCanvas());
			}
			dialog.setVisible(true);
		});
	}

	private void showAreaDetailsPopup(Area area)
	{
		showAreaDetailsPopup(area, null, null);
	}

	/** Opens the task grid popup for the given area (e.g. from world map menu). Call from EDT or client thread. */
	public void openTaskGridForArea(Area area)
	{
		showTaskGridPopup(area);
	}

	private void showTaskGridPopup(Area area)
	{
		if (area == null) return;
		String displayName = area.getDisplayName() != null ? area.getDisplayName() : area.getId();
		String areaId = area.getId();

		BufferedImage interfaceBg = ImageUtil.loadImageResource(LeagueScapePlugin.class, "interface_template.png");
		BufferedImage buttonRect = ImageUtil.loadImageResource(LeagueScapePlugin.class, "empty_button_rectangle.png");
		BufferedImage tileSquare = ImageUtil.loadImageResource(LeagueScapePlugin.class, "empty_button_square.png");
		BufferedImage xBtnImg = ImageUtil.loadImageResource(LeagueScapePlugin.class, "x_button.png");
		BufferedImage checkmarkImg = ImageUtil.loadImageResource(LeagueScapePlugin.class, "complete_checkmark.png");
		BufferedImage padlockImg = ImageUtil.loadImageResource(LeagueScapePlugin.class, "padlock_icon.png");
		BufferedImage defaultTaskIcon = loadTaskIcon();
		Map<String, BufferedImage> taskIconCache = new ConcurrentHashMap<>();

		SwingUtilities.invokeLater(() -> {
			Frame owner = null;
			java.awt.Window w = SwingUtilities.windowForComponent(client.getCanvas());
			if (w instanceof Frame) owner = (Frame) w;

			JDialog dialog = new JDialog(owner, displayName + " tasks", false);
			dialog.setUndecorated(true);

			JPanel content = new JPanel()
			{
				@Override
				protected void paintComponent(Graphics g)
				{
					super.paintComponent(g);
					if (interfaceBg != null)
					{
						g.drawImage(interfaceBg.getScaledInstance(getWidth(), getHeight(), Image.SCALE_SMOOTH), 0, 0, null);
					}
					else
					{
						g.setColor(POPUP_BG);
						g.fillRect(0, 0, getWidth(), getHeight());
					}
				}
			};
			content.setLayout(new java.awt.BorderLayout(8, 8));
			content.setBackground(POPUP_BG);
			content.setBorder(new javax.swing.border.CompoundBorder(
				new javax.swing.border.LineBorder(POPUP_BORDER, 2),
				new javax.swing.border.EmptyBorder(10, 12, 10, 12)));
			content.setOpaque(true);

			// Header: title "[area name] tasks" + points + close button
			JPanel header = new JPanel(new java.awt.BorderLayout(4, 0));
			header.setOpaque(false);
			header.setBorder(new javax.swing.border.EmptyBorder(0, 0, 8, 0));
			JPanel titleRow = new JPanel(new java.awt.BorderLayout(4, 0));
			titleRow.setOpaque(false);
			JLabel titleLabel = new JLabel(displayName + " tasks");
			titleLabel.setForeground(POPUP_TEXT);
			titleLabel.setFont(titleLabel.getFont().deriveFont(java.awt.Font.BOLD, 16f));
			titleRow.add(titleLabel, java.awt.BorderLayout.CENTER);
			JButton closeBtn = newPopupButtonWithIcon(xBtnImg, POPUP_TEXT);
			closeBtn.addActionListener(e -> {
				LeagueScapeSounds.play(audioPlayer, LeagueScapeSounds.BUTTON_PRESS);
				dialog.dispose();
			});
			titleRow.add(closeBtn, java.awt.BorderLayout.EAST);
			header.add(titleRow, java.awt.BorderLayout.NORTH);
			final JLabel[] pointsLabelHolder = new JLabel[1];
			pointsLabelHolder[0] = new JLabel(getPointsDisplayText(area));
			pointsLabelHolder[0].setForeground(POPUP_TEXT);
			header.add(pointsLabelHolder[0], java.awt.BorderLayout.SOUTH);
			content.add(header, java.awt.BorderLayout.NORTH);

			// Grid panel: only non-locked tiles, inside scroll pane with vertical + horizontal scroll bars
			JPanel gridPanel = new JPanel();
			gridPanel.setOpaque(false);
			Runnable[] refreshHolder = new Runnable[1];
			refreshHolder[0] = () -> {
				gridPanel.removeAll();
				gridPanel.setLayout(new GridBagLayout());
				List<TaskTile> grid = taskGridService.getGridForArea(areaId);
				Set<String> unlocked = areaGraphService.getUnlockedAreaIds();
				int center = 5;
				for (TaskTile tile : grid)
				{
					TaskState state = taskGridService.getState(areaId, tile.getId(), grid);
					if (state == TaskState.LOCKED)
					{
						continue; // hide locked tiles
					}
					boolean isMystery = tile.isMystery(unlocked);
					BufferedImage taskIcon;
					if (isMystery)
					{
						taskIcon = taskIconCache.get("mystery");
						if (taskIcon == null)
						{
							taskIcon = createMysteryIcon();
							taskIconCache.put("mystery", taskIcon);
						}
					}
					else
					{
						String cacheKey = tile.getTaskType() != null ? ("type:" + tile.getTaskType()) : tile.getDisplayName();
						taskIcon = taskIconCache.get(cacheKey);
						if (taskIcon == null)
						{
							if (isEquipTask(tile.getDisplayName()))
							{
								// Equip tasks: icon from Wiki API by item name
								taskIcon = defaultTaskIcon;
								taskIconCache.put(cacheKey, taskIcon);
								String itemName = extractEquipItemName(tile.getDisplayName());
								if (wikiApi != null && itemName != null && !itemName.isEmpty())
								{
									String keyForCallback = cacheKey;
									wikiApi.fetchItemIconAsync(itemName, img -> {
										if (img != null)
										{
											taskIconCache.put(keyForCallback, scaleToFit(img, TASK_ICON_MAX_FIT, TASK_ICON_MAX_FIT));
											SwingUtilities.invokeLater(refreshHolder[0]);
										}
									});
								}
							}
							else
							{
								// Local taskIcons; scale to fit inside tile, aspect ratio preserved
								taskIcon = loadLocalTaskIcon(tile.getTaskType(), tile.getDisplayName());
								if (taskIcon == null)
									taskIcon = defaultTaskIcon;
								taskIconCache.put(cacheKey, taskIcon);
							}
						}
					}
					int gx = tile.getCol() + center;
					int gy = center - tile.getRow();
					GridBagConstraints gbc = new GridBagConstraints();
					gbc.gridx = gx;
					gbc.gridy = gy;
					gbc.insets = new Insets(2, 2, 2, 2);
					JPanel cell = buildTaskCell(areaId, tile, state, checkmarkImg, padlockImg, tileSquare, buttonRect, taskIcon, POPUP_TEXT, refreshHolder[0], dialog, area, isMystery);
					gridPanel.add(cell, gbc);
				}
				gridPanel.revalidate();
				gridPanel.repaint();
				if (pointsLabelHolder[0] != null)
					pointsLabelHolder[0].setText(getPointsDisplayText(area));
			};
			refreshHolder[0].run();

			JScrollPane scrollPane = new JScrollPane(gridPanel);
			scrollPane.setOpaque(false);
			scrollPane.getViewport().setOpaque(false);
			scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
			scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
			scrollPane.setPreferredSize(new Dimension(400, 320));
			scrollPane.setBorder(null);
			// Click-and-drag to scroll
			final java.awt.Point[] dragStart = new java.awt.Point[1];
			scrollPane.getViewport().addMouseListener(new java.awt.event.MouseAdapter()
			{
				@Override
				public void mousePressed(java.awt.event.MouseEvent e)
				{
					dragStart[0] = e.getPoint();
				}
			});
			scrollPane.getViewport().addMouseMotionListener(new java.awt.event.MouseMotionAdapter()
			{
				@Override
				public void mouseDragged(java.awt.event.MouseEvent e)
				{
					if (dragStart[0] == null) return;
					java.awt.Point vp = scrollPane.getViewport().getViewPosition();
					int dx = dragStart[0].x - e.getX();
					int dy = dragStart[0].y - e.getY();
					int nx = Math.max(0, Math.min(vp.x + dx, scrollPane.getViewport().getViewSize().width - scrollPane.getViewport().getExtentSize().width));
					int ny = Math.max(0, Math.min(vp.y + dy, scrollPane.getViewport().getViewSize().height - scrollPane.getViewport().getExtentSize().height));
					scrollPane.getViewport().setViewPosition(new java.awt.Point(nx, ny));
					dragStart[0] = e.getPoint();
				}
			});
			content.add(scrollPane, java.awt.BorderLayout.CENTER);

			// Back to area button: keep aspect ratio of empty_button_rectangle (no stretch)
			JButton backBtn = newRectangleButton("Back to area", buttonRect, POPUP_TEXT);
			backBtn.setMaximumSize(RECTANGLE_BUTTON_SIZE);
			backBtn.addActionListener(e -> {
				LeagueScapeSounds.play(audioPlayer, LeagueScapeSounds.BUTTON_PRESS);
				dialog.dispose();
				showAreaDetailsPopup(area);
			});
			JPanel southPanel = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.CENTER, 0, 8));
			southPanel.setOpaque(false);
			southPanel.add(backBtn);
			content.add(southPanel, java.awt.BorderLayout.SOUTH);

			dialog.setContentPane(content);
			dialog.getRootPane().setBorder(new javax.swing.border.LineBorder(POPUP_BORDER, 2));
			dialog.pack();
			dialog.setLocationRelativeTo(client.getCanvas());
			dialog.setVisible(true);
		});
	}

	private static final int CLAIMED_CHECKMARK_SIZE = 18;
	private static final int CLAIMED_CHECKMARK_INSET = 4;

	private JPanel buildTaskCell(String areaId, TaskTile tile, TaskState state,
		BufferedImage checkmarkImg, BufferedImage padlockImg, BufferedImage tileBg, BufferedImage buttonRect,
		BufferedImage taskIcon, Color textColor, Runnable onRefresh, JDialog parentDialog, Area area, boolean isMystery)
	{
		if (state == TaskState.CLAIMED)
		{
			return buildClaimedTaskCell(tileBg, checkmarkImg);
		}
		final BufferedImage tileBgFinal = tileBg;
		JPanel cell = new JPanel()
		{
			@Override
			protected void paintComponent(Graphics g)
			{
				if (tileBgFinal != null)
				{
					g.drawImage(tileBgFinal.getScaledInstance(getWidth(), getHeight(), Image.SCALE_SMOOTH), 0, 0, null);
				}
				else
				{
					g.setColor(new Color(60, 55, 50));
					g.fillRect(0, 0, getWidth(), getHeight());
				}
				super.paintComponent(g);
			}
		};
		cell.setLayout(new java.awt.BorderLayout());
		cell.setOpaque(false);
		cell.setPreferredSize(new Dimension(72, 72));

		if (taskIcon != null)
		{
			JLabel iconLabel = new JLabel(new javax.swing.ImageIcon(taskIcon), javax.swing.SwingConstants.CENTER);
			iconLabel.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
			iconLabel.setVerticalAlignment(javax.swing.SwingConstants.CENTER);
			iconLabel.setBorder(new javax.swing.border.EmptyBorder(TASK_TILE_ICON_MARGIN, TASK_TILE_ICON_MARGIN, TASK_TILE_ICON_MARGIN, TASK_TILE_ICON_MARGIN));
			cell.add(iconLabel, java.awt.BorderLayout.CENTER);
		}
		// Single click to claim when completed; otherwise open detail popup
		final boolean mystery = isMystery;
		cell.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				if (e.getButton() != MouseEvent.BUTTON1) return;
				if (state == TaskState.COMPLETED_UNCLAIMED)
				{
					LeagueScapeSounds.play(audioPlayer, LeagueScapeSounds.TASK_COMPLETE);
					taskGridService.setClaimed(areaId, tile.getId());
					SwingUtilities.invokeLater(onRefresh);
					return;
				}
				LeagueScapeSounds.play(audioPlayer, LeagueScapeSounds.BUTTON_PRESS);
				showTaskDetailPopup(parentDialog, areaId, tile, state, buttonRect, checkmarkImg, textColor, onRefresh, mystery);
			}
		});
		cell.setCursor(new java.awt.Cursor(java.awt.Cursor.HAND_CURSOR));

		return cell;
	}

	/** Claimed task: desaturated tile, single small checkmark in corner, not clickable. */
	private JPanel buildClaimedTaskCell(BufferedImage tileBg, BufferedImage checkmarkImg)
	{
		final BufferedImage bg = tileBg;
		final BufferedImage checkmark = checkmarkImg != null ? ImageUtil.resizeImage(checkmarkImg, CLAIMED_CHECKMARK_SIZE, CLAIMED_CHECKMARK_SIZE) : null;
		JPanel cell = new JPanel()
		{
			@Override
			protected void paintComponent(Graphics g)
			{
				if (bg != null)
				{
					Image scaled = bg.getScaledInstance(getWidth(), getHeight(), Image.SCALE_SMOOTH);
					g.drawImage(scaled, 0, 0, null);
				}
				else
				{
					g.setColor(new Color(60, 55, 50));
					g.fillRect(0, 0, getWidth(), getHeight());
				}
				// Desaturate with semi-transparent gray overlay
				g.setColor(new Color(120, 120, 120, 140));
				g.fillRect(0, 0, getWidth(), getHeight());
				if (checkmark != null)
				{
					int x = getWidth() - CLAIMED_CHECKMARK_SIZE - CLAIMED_CHECKMARK_INSET;
					int y = CLAIMED_CHECKMARK_INSET;
					g.drawImage(checkmark, x, y, null);
				}
			}
		};
		cell.setOpaque(false);
		cell.setPreferredSize(new Dimension(72, 72));
		cell.setCursor(new java.awt.Cursor(java.awt.Cursor.DEFAULT_CURSOR));
		return cell;
	}

	private void showTaskDetailPopup(JDialog parentDialog, String areaId, TaskTile tile, TaskState state,
		BufferedImage buttonRect, BufferedImage checkmarkImg, Color textColor, Runnable onRefresh, boolean isMystery)
	{
		Frame frameOwner = null;
		if (parentDialog != null)
		{
			java.awt.Window w = parentDialog.getOwner();
			if (w instanceof Frame) frameOwner = (Frame) w;
		}
		if (frameOwner == null)
		{
			java.awt.Window w = SwingUtilities.windowForComponent(client.getCanvas());
			if (w instanceof Frame) frameOwner = (Frame) w;
		}
		String windowTitle = isMystery ? "Mystery tile" : tile.getDisplayName();
		JDialog detail = new JDialog(frameOwner, windowTitle, false);
		detail.setUndecorated(true);

		JPanel content = new JPanel(new java.awt.BorderLayout(8, 8));
		content.setBackground(POPUP_BG);
		content.setBorder(new javax.swing.border.CompoundBorder(
			new javax.swing.border.LineBorder(POPUP_BORDER, 2),
			new javax.swing.border.EmptyBorder(12, 14, 12, 14)));

		// Header: title + X close button
		JPanel header = new JPanel(new java.awt.BorderLayout(4, 0));
		header.setOpaque(false);
		JLabel titleLabel = new JLabel(windowTitle);
		titleLabel.setForeground(textColor);
		titleLabel.setFont(titleLabel.getFont().deriveFont(java.awt.Font.BOLD, 13f));
		header.add(titleLabel, java.awt.BorderLayout.CENTER);
		BufferedImage xBtnImg = ImageUtil.loadImageResource(LeagueScapePlugin.class, "x_button.png");
		JButton closeBtn = newPopupButtonWithIcon(xBtnImg, POPUP_TEXT);
		closeBtn.addActionListener(e -> {
			LeagueScapeSounds.play(audioPlayer, LeagueScapeSounds.BUTTON_PRESS);
			detail.dispose();
		});
		header.add(closeBtn, java.awt.BorderLayout.EAST);
		content.add(header, java.awt.BorderLayout.NORTH);

		// Body: tier/points, then message (no Complete button; claim is single-click on tile)
		JPanel body = new JPanel();
		body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
		body.setOpaque(false);
		JLabel detailsLabel = new JLabel("<html>Tier " + tile.getTier() + " &middot; " + tile.getPoints() + " point" + (tile.getPoints() != 1 ? "s" : "") + "</html>");
		detailsLabel.setForeground(textColor);
		body.add(detailsLabel);
		body.add(new JLabel(" "));

		if (isMystery)
		{
			JLabel mysteryLabel = new JLabel("<html>Unlock all required areas to reveal this task.</html>");
			mysteryLabel.setForeground(textColor);
			body.add(mysteryLabel);
		}
		else if (state == TaskState.COMPLETED_UNCLAIMED)
		{
			// Should not normally reach here (single-click claims); show Claim as fallback
			JButton claimBtn = newRectangleButton("Claim", buttonRect, textColor);
			claimBtn.addActionListener(e -> {
				LeagueScapeSounds.play(audioPlayer, LeagueScapeSounds.TASK_COMPLETE);
				taskGridService.setClaimed(areaId, tile.getId());
				detail.dispose();
				SwingUtilities.invokeLater(onRefresh);
			});
			body.add(claimBtn);
		}
		else if (state == TaskState.REVEALED)
		{
			JLabel revealLabel = new JLabel("<html>Complete this task then click 'Claim'.</html>");
			revealLabel.setForeground(textColor);
			body.add(revealLabel);
			JButton claimBtn = newRectangleButton("Claim", buttonRect, textColor);
			claimBtn.addActionListener(e -> {
				LeagueScapeSounds.play(audioPlayer, LeagueScapeSounds.TASK_COMPLETE);
				taskGridService.setCompleted(areaId, tile.getId());
				taskGridService.setClaimed(areaId, tile.getId());
				detail.dispose();
				SwingUtilities.invokeLater(onRefresh);
			});
			body.add(claimBtn);
		}
		else
		{
			JLabel doneLabel = new JLabel("Claimed");
			doneLabel.setForeground(textColor);
			body.add(doneLabel);
		}

		content.add(body, java.awt.BorderLayout.CENTER);

		detail.setContentPane(content);
		detail.getRootPane().setBorder(new javax.swing.border.LineBorder(POPUP_BORDER, 2));
		// Close when user clicks outside the popup (dialog loses focus)
		detail.addWindowFocusListener(new java.awt.event.WindowAdapter()
		{
			@Override
			public void windowLostFocus(java.awt.event.WindowEvent e)
			{
				detail.dispose();
			}
		});
		detail.pack();
		if (parentDialog != null)
			detail.setLocationRelativeTo(parentDialog);
		else
			detail.setLocationRelativeTo(client.getCanvas());
		detail.setVisible(true);
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
		if (editingAreaId != null || plugin.isEditingArea())
		{
			showMapEditContextMenu(event.getX(), event.getY());
			return event;
		}
		if (hoveredArea != null)
		{
			java.awt.Point p = new java.awt.Point(event.getX(), event.getY());
			SwingUtilities.convertPointToScreen(p, event.getComponent());
			showAreaDetailsPopup(hoveredArea, p.x, p.y);
		}
		return event;
	}

	@Override
	public MouseEvent mouseReleased(MouseEvent event) { return event; }

	@Override
	public MouseEvent mouseClicked(MouseEvent event)
	{
		if (event.getButton() != MouseEvent.BUTTON1) return event;
		Widget map = client.getWidget(InterfaceID.Worldmap.MAP_CONTAINER);
		if (map == null) return event;
		Rectangle worldMapRect = map.getBounds();
		if (!worldMapRect.contains(event.getX(), event.getY())) return event;
		WorldMap worldMap = client.getWorldMap();
		float pixelsPerTile = worldMap.getWorldMapZoom();
		WorldPoint wp = screenToWorldPoint(worldMap, worldMapRect, pixelsPerTile, event.getX(), event.getY());
		if (wp == null) return event;

		// Add New Area mode: Shift+left-click adds a corner at the clicked tile
		if (plugin.isAddNewAreaMode() && event.isShiftDown())
		{
			LeagueScapeSounds.play(audioPlayer, LeagueScapeSounds.BUTTON_PRESS);
			plugin.addCornerFromWorldPoint(wp);
			return event;
		}

		// Plugin Area Edit mode (config-panel edit): left-click adds or moves corner via plugin
		if (plugin.isEditingArea() && !plugin.isAddNewAreaMode())
		{
			if (plugin.getMoveCornerIndex() >= 0)
			{
				plugin.setCornerPosition(plugin.getMoveCornerIndex(), wp);
				plugin.setMoveCornerIndex(-1);
				return event;
			}
			LeagueScapeSounds.play(audioPlayer, LeagueScapeSounds.BUTTON_PRESS);
			plugin.addCornerFromWorldPoint(wp);
			return event;
		}

		// Overlay map-edit state (legacy): left-click adds or moves corner
		if (editingAreaId == null || editingCorners == null) return event;
		if (moveCornerIndex >= 0)
		{
			int idx = moveCornerIndex;
			if (idx < editingCorners.size())
			{
				editingCorners.set(idx, new int[]{ wp.getX(), wp.getY(), 0 });
			}
			moveCornerIndex = -1;
			return event;
		}
		editingCorners.add(new int[]{ wp.getX(), wp.getY(), 0 });
		return event;
	}

	private void showAddNeighborsDialog()
	{
		String areaId = plugin.getEditingAreaId();
		if (areaId == null) return;
		Area current = areaGraphService.getArea(areaId);
		String displayName = current != null && current.getDisplayName() != null ? current.getDisplayName() : areaId;
		List<String> currentNeighbors = plugin.getEditingNeighbors() != null ? plugin.getEditingNeighbors() : Collections.<String>emptyList();

		List<Area> others = new ArrayList<>(areaGraphService.getAreas());
		others.removeIf(a -> areaId.equals(a.getId()));
		others.sort(Comparator.comparing((Area a) -> a.getDisplayName() != null ? a.getDisplayName() : a.getId(), String.CASE_INSENSITIVE_ORDER)
			.thenComparing(Area::getId, String.CASE_INSENSITIVE_ORDER));

		SwingUtilities.invokeLater(() -> {
			Frame owner = null;
			java.awt.Window w = SwingUtilities.windowForComponent(client.getCanvas());
			if (w instanceof Frame) owner = (Frame) w;

			JDialog dialog = new JDialog(owner, "Neighbors for " + displayName, false);
			dialog.setUndecorated(true);

			JPanel content = new JPanel();
			content.setLayout(new java.awt.BorderLayout(8, 8));
			content.setBackground(POPUP_BG);
			content.setBorder(new javax.swing.border.CompoundBorder(
				new javax.swing.border.LineBorder(POPUP_BORDER, 2),
				new javax.swing.border.EmptyBorder(10, 12, 10, 12)));

			JLabel titleLabel = new JLabel("Select neighboring areas:");
			titleLabel.setForeground(POPUP_TEXT);
			content.add(titleLabel, java.awt.BorderLayout.NORTH);

			JPanel checkPanel = new JPanel();
			checkPanel.setLayout(new BoxLayout(checkPanel, BoxLayout.Y_AXIS));
			checkPanel.setBackground(POPUP_BG);
			List<JCheckBox> boxes = new ArrayList<>();
			for (Area a : others)
			{
				JCheckBox cb = new JCheckBox(a.getDisplayName() != null ? a.getDisplayName() : a.getId());
				cb.setName(a.getId());
				cb.setSelected(currentNeighbors.contains(a.getId()));
				cb.setForeground(POPUP_TEXT);
				cb.setBackground(POPUP_BG);
				checkPanel.add(cb);
				boxes.add(cb);
			}
			JScrollPane scroll = new JScrollPane(checkPanel);
			scroll.setPreferredSize(new Dimension(280, 200));
			scroll.getViewport().setBackground(POPUP_BG);
			content.add(scroll, java.awt.BorderLayout.CENTER);

			JPanel buttonPanel = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 8, 0));
			buttonPanel.setOpaque(false);
			JButton okBtn = new JButton("OK");
			okBtn.addActionListener(e -> {
				List<String> selected = new ArrayList<>();
				for (JCheckBox cb : boxes)
					if (cb.isSelected() && cb.getName() != null) selected.add(cb.getName());
				plugin.setEditingNeighbors(selected);
				LeagueScapeSounds.play(audioPlayer, LeagueScapeSounds.BUTTON_PRESS);
				dialog.dispose();
			});
			JButton cancelBtn = new JButton("Cancel");
			cancelBtn.addActionListener(e -> {
				LeagueScapeSounds.play(audioPlayer, LeagueScapeSounds.BUTTON_PRESS);
				dialog.dispose();
			});
			buttonPanel.add(cancelBtn);
			buttonPanel.add(okBtn);
			content.add(buttonPanel, java.awt.BorderLayout.SOUTH);

			dialog.setContentPane(content);
			dialog.pack();
			dialog.setLocationRelativeTo(client.getCanvas());
			dialog.setVisible(true);
		});
	}

	private void showMapEditContextMenu(int screenX, int screenY)
	{
		int cornerIdx = getCornerIndexAtScreen(screenX, screenY);
		boolean pluginDriven = plugin.isEditingArea();
		JPopupMenu menu = new JPopupMenu();
		if (cornerIdx >= 0)
		{
			JMenuItem moveItem = new JMenuItem("Move corner");
			int idx = cornerIdx;
			moveItem.addActionListener(e -> {
				if (pluginDriven) plugin.setMoveCornerIndex(idx);
				else moveCornerIndex = idx;
			});
			menu.add(moveItem);
			JMenuItem removeItem = new JMenuItem("Remove corner");
			removeItem.addActionListener(e -> {
				if (pluginDriven)
				{
					if (idx >= 0 && idx < plugin.getEditingCorners().size())
						plugin.removeCorner(idx);
				}
				else if (editingCorners != null && idx >= 0 && idx < editingCorners.size())
				{
					editingCorners.remove(idx);
					if (moveCornerIndex == idx) moveCornerIndex = -1;
					else if (moveCornerIndex > idx) moveCornerIndex--;
				}
			});
			menu.add(removeItem);
			menu.addSeparator();
		}
		List<int[]> currentPoly = plugin.isEditingArea() ? plugin.getEditingCorners() : (editingCorners != null ? editingCorners : Collections.<int[]>emptyList());
		if (currentPoly.size() >= 3)
		{
			JMenuItem fillItem = new JMenuItem("Fill using others' corners");
			fillItem.addActionListener(e -> {
				LeagueScapeSounds.play(audioPlayer, LeagueScapeSounds.BUTTON_PRESS);
				fillUsingOthersCorners();
			});
			menu.add(fillItem);
			menu.addSeparator();
		}
		JMenuItem beginNewItem = new JMenuItem("Begin new polygon");
		beginNewItem.addActionListener(e -> {
			LeagueScapeSounds.play(audioPlayer, LeagueScapeSounds.BUTTON_PRESS);
			plugin.startNewPolygon();
		});
		menu.add(beginNewItem);
		if (pluginDriven && plugin.getEditingAreaId() != null)
		{
			JMenuItem neighborsItem = new JMenuItem("Add neighbors");
			neighborsItem.addActionListener(e -> {
				LeagueScapeSounds.play(audioPlayer, LeagueScapeSounds.BUTTON_PRESS);
				showAddNeighborsDialog();
			});
			menu.add(neighborsItem);
		}
		menu.addSeparator();
		JMenuItem doneItem = new JMenuItem("Done editing");
		doneItem.addActionListener(e -> {
			LeagueScapeSounds.play(audioPlayer, LeagueScapeSounds.BUTTON_PRESS);
			exitMapEditMode(true);
		});
		menu.add(doneItem);
		JMenuItem cancelItem = new JMenuItem("Cancel editing");
		cancelItem.addActionListener(e -> {
			LeagueScapeSounds.play(audioPlayer, LeagueScapeSounds.BUTTON_PRESS);
			exitMapEditMode(false);
		});
		menu.add(cancelItem);
		SwingUtilities.invokeLater(() -> menu.show(client.getCanvas(), screenX, screenY));
	}

	@Override
	public MouseEvent mouseEntered(MouseEvent event) { return event; }
	@Override
	public MouseEvent mouseExited(MouseEvent event) { hoveredArea = null; return event; }
	@Override
	public MouseEvent mouseDragged(MouseEvent event) { return event; }
}

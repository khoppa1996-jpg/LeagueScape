package com.leaguescape.config;

import com.leaguescape.LeagueScapePlugin;
import com.leaguescape.area.AreaGraphService;
import com.leaguescape.data.Area;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Provider;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.WorldView;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Overlay shown during area edit mode. Renders all polygon corners from all areas
 * with labels (coordinates) so neighboring/new areas can share boundaries.
 * Renders UNDER_WIDGETS so inventory, minimap, chatbox, and all game UI stay on top and interactable.
 */
public class AreaEditOverlay extends Overlay
{
	private static final int CORNER_MARKER_SIZE = 6;
	private static final Color EDITING_AREA_COLOR = new Color(0, 200, 100, 200);
	private static final Color MOVE_TARGET_COLOR = new Color(255, 200, 0, 220);
	private static final Color OTHER_AREA_COLOR = new Color(100, 150, 255, 180);
	private static final Color LINE_COLOR = new Color(255, 255, 255, 120);
	private static final Color LABEL_BG = new Color(0, 0, 0, 200);
	private static final Color LABEL_TEXT = Color.WHITE;

	private final Client client;
	private final AreaGraphService areaGraphService;
	private final Provider<LeagueScapePlugin> pluginProvider;

	@Inject
	public AreaEditOverlay(Client client, AreaGraphService areaGraphService, Provider<LeagueScapePlugin> pluginProvider)
	{
		this.client = client;
		this.areaGraphService = areaGraphService;
		this.pluginProvider = pluginProvider;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.UNDER_WIDGETS);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		LeagueScapePlugin plugin = pluginProvider.get();
		if (plugin == null || !plugin.isEditingArea()) return null;

		var player = client.getLocalPlayer();
		if (player == null) return null;

		WorldView wv = player.getWorldView();
		if (wv == null) wv = client.getTopLevelWorldView();
		if (wv == null) return null;

		int plane = wv.getPlane();
		String editingId = plugin.getEditingAreaId();
		List<int[]> editingCorners = plugin.getEditingCorners();

		graphics.setFont(new Font("Arial", Font.PLAIN, 12));

		int moveIdx = plugin.getMoveCornerIndex();

		// Draw all saved areas
		for (Area area : areaGraphService.getAreas())
		{
			String areaLabel = area.getDisplayName() != null ? area.getDisplayName() : area.getId();
			Color areaColor = area.getId().equals(editingId) ? EDITING_AREA_COLOR : OTHER_AREA_COLOR;
			if (area.getId().equals(editingId))
			{
				// Editing this area: draw all completed polygons then current polygon (with move index)
				for (List<int[]> poly : plugin.getEditingPolygons())
				{
					if (poly != null && poly.size() >= 3)
						drawPolygonCorners(graphics, wv, plane, areaLabel, poly, areaColor, -1);
				}
				if (editingCorners.size() >= 2)
					drawPolygonCorners(graphics, wv, plane, areaLabel, editingCorners, areaColor, moveIdx);
			}
			else
			{
				List<int[]> corners = area.getPolygon() != null ? area.getPolygon() : List.of();
				if (corners.size() >= 3)
					drawPolygonCorners(graphics, wv, plane, areaLabel, corners, areaColor, -1);
			}
		}

		// If editing a new area (not in getAreas), draw all its polygons
		if (editingId != null && editingId.startsWith("new_"))
		{
			for (List<int[]> poly : plugin.getEditingPolygons())
			{
				if (poly != null && poly.size() >= 3)
					drawPolygonCorners(graphics, wv, plane, "New area", poly, EDITING_AREA_COLOR, -1);
			}
			if (editingCorners.size() >= 2)
				drawPolygonCorners(graphics, wv, plane, "New area", editingCorners, EDITING_AREA_COLOR, moveIdx);
		}

		return null;
	}

	private void drawPolygonCorners(Graphics2D graphics, WorldView wv, int plane, String areaLabel, List<int[]> corners, Color areaColor, int moveCornerIndex)
	{
		if (corners.isEmpty()) return;

		// Draw edges
		graphics.setStroke(new BasicStroke(2));
		graphics.setColor(LINE_COLOR);
		Point prevScreen = null;
		for (int i = 0; i <= corners.size(); i++)
		{
			int[] v = corners.get(i % corners.size());
			if (v.length < 3 || v[2] != plane) continue;

			Point screen = worldToScreen(wv, v[0], v[1], plane);
			if (screen == null) continue;

			if (prevScreen != null)
			{
				graphics.drawLine(prevScreen.x, prevScreen.y, screen.x, screen.y);
			}
			prevScreen = screen;
		}

		// Draw corners and labels
		for (int i = 0; i < corners.size(); i++)
		{
			int[] v = corners.get(i);
			if (v.length < 3 || v[2] != plane) continue;

			Point screen = worldToScreen(wv, v[0], v[1], plane);
			if (screen == null) continue;

			Color markerColor = (moveCornerIndex >= 0 && i == moveCornerIndex) ? MOVE_TARGET_COLOR : areaColor;
			graphics.setColor(markerColor);
			int size = (moveCornerIndex >= 0 && i == moveCornerIndex) ? CORNER_MARKER_SIZE + 2 : CORNER_MARKER_SIZE;
			graphics.fillOval(screen.x - size / 2, screen.y - size / 2, size, size);
			graphics.setColor(Color.WHITE);
			graphics.drawOval(screen.x - size / 2, screen.y - size / 2, size, size);

			String coordLabel = v[0] + ", " + v[1] + ", " + v[2];
			String label = areaLabel + " #" + i + " (" + coordLabel + ")";
			drawTextWithBackground(graphics, screen.x + 8, screen.y, label);
		}
	}

	private void drawTextWithBackground(Graphics2D g, int x, int y, String text)
	{
		FontMetrics fm = g.getFontMetrics();
		int w = fm.stringWidth(text);
		int h = fm.getAscent() + fm.getDescent();
		int pad = 2;
		g.setColor(LABEL_BG);
		g.fillRect(x, y - fm.getAscent() - pad, w + pad * 2, h + pad * 2);
		g.setColor(LABEL_TEXT);
		g.drawString(text, x + pad, y);
	}

	private Point worldToScreen(WorldView wv, int worldX, int worldY, int plane)
	{
		LocalPoint local = LocalPoint.fromWorld(wv, worldX, worldY);
		if (local == null) return null;

		net.runelite.api.Point p = Perspective.localToCanvas(client, local, plane);
		if (p == null) return null;

		return new Point(p.getX(), p.getY());
	}
}

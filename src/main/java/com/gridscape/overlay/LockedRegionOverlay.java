package com.leaguescape.overlay;

import com.leaguescape.LeagueScapeConfig;
import com.leaguescape.area.AreaGraphService;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.geom.GeneralPath;
import java.util.Set;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.Point;
import net.runelite.api.Tile;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.WorldView;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Displays locked regions like region-locker (https://github.com/slaytostay/region-locker).
 * Colors each locked tile individually. Optional 64x64 chunk borders.
 * Renders UNDER_WIDGETS so inventory, minimap, chatbox, and all game UI stay on top and interactable.
 */
public class LockedRegionOverlay extends Overlay
{
	private static final int LOCAL_TILE_SIZE = Perspective.LOCAL_TILE_SIZE;
	private static final int CHUNK_SIZE = 8;
	private static final int MAP_SQUARE_SIZE = CHUNK_SIZE * CHUNK_SIZE; // 64
	private static final int CULL_CHUNK_BORDERS_RANGE = 16;

	private final Client client;
	private final AreaGraphService areaGraphService;
	private final LeagueScapeConfig config;

	@Inject
	public LockedRegionOverlay(Client client, AreaGraphService areaGraphService, LeagueScapeConfig config)
	{
		this.client = client;
		this.areaGraphService = areaGraphService;
		this.config = config;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.UNDER_WIDGETS);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		var player = client.getLocalPlayer();
		if (player == null) return null;

		var wv = player.getWorldView();
		if (wv == null) return null;

		if (config.renderLockedOverlay())
		{
			renderLockedTileFill(graphics, wv);
		}

		if (config.renderRegionBorders())
		{
			renderChunkBorders(graphics);
		}

		return null;
	}

	private void renderLockedTileFill(Graphics2D graphics, WorldView wv)
	{
		var scene = wv.getScene();
		if (scene == null) return;

		Tile[][][] tiles = scene.getTiles();
		if (tiles == null) return;

		Color fillColor = config.lockedOverlayColor();
		int plane = wv.getPlane();
		Set<WorldPoint> lockedTiles = areaGraphService.getTilesInLockedAreas(plane);
		for (int x = 0; x < tiles[plane].length; x++)
		{
			for (int y = 0; y < tiles[plane][x].length; y++)
			{
				Tile tile = tiles[plane][x][y];
				if (tile == null) continue;

				WorldPoint world;
				if (client.isInInstancedRegion())
				{
					world = WorldPoint.fromLocalInstance(client, tile.getLocalLocation());
				}
				else
				{
					world = tile.getWorldLocation();
				}
				if (world == null) continue;

				// Only draw if this tile is in the set of tiles contained in locked areas (from areas.json)
				if (lockedTiles.contains(world))
				{
					LocalPoint local = tile.getLocalLocation();
					if (local == null) continue;

					Polygon poly = Perspective.getCanvasTilePoly(client, local);
					if (poly != null)
					{
						graphics.setColor(fillColor);
						graphics.fillPolygon(poly);
					}
				}
			}
		}
	}

	/**
	 * Renders 64x64 chunk border lines exactly like region-locker's RegionBorderOverlay.
	 */
	private void renderChunkBorders(Graphics2D graphics)
	{
		WorldPoint wp = client.getLocalPlayer().getWorldLocation();
		int startX = (wp.getX() - CULL_CHUNK_BORDERS_RANGE + MAP_SQUARE_SIZE - 1) / MAP_SQUARE_SIZE * MAP_SQUARE_SIZE;
		int startY = (wp.getY() - CULL_CHUNK_BORDERS_RANGE + MAP_SQUARE_SIZE - 1) / MAP_SQUARE_SIZE * MAP_SQUARE_SIZE;
		int endX = (wp.getX() + CULL_CHUNK_BORDERS_RANGE) / MAP_SQUARE_SIZE * MAP_SQUARE_SIZE;
		int endY = (wp.getY() + CULL_CHUNK_BORDERS_RANGE) / MAP_SQUARE_SIZE * MAP_SQUARE_SIZE;

		graphics.setStroke(new BasicStroke(config.regionBorderWidth()));
		graphics.setColor(config.regionBorderColor());

		var wv = client.getTopLevelWorldView();
		if (wv == null) return;

		GeneralPath path = new GeneralPath();
		int plane = client.getPlane();

		// Vertical lines
		for (int x = startX; x <= endX; x += MAP_SQUARE_SIZE)
		{
			LocalPoint lp1 = LocalPoint.fromWorld(wv, x, wp.getY() - CULL_CHUNK_BORDERS_RANGE);
			LocalPoint lp2 = LocalPoint.fromWorld(wv, x, wp.getY() + CULL_CHUNK_BORDERS_RANGE);
			if (lp1 == null || lp2 == null) continue;

			boolean first = true;
			for (int y = lp1.getY(); y <= lp2.getY(); y += LOCAL_TILE_SIZE)
			{
				Point p = Perspective.localToCanvas(client,
					new LocalPoint(lp1.getX() - LOCAL_TILE_SIZE / 2, y - LOCAL_TILE_SIZE / 2, wv),
					plane);
				if (p != null)
				{
					if (first)
					{
						path.moveTo(p.getX(), p.getY());
						first = false;
					}
					else
					{
						path.lineTo(p.getX(), p.getY());
					}
				}
			}
		}
		// Horizontal lines
		for (int y = startY; y <= endY; y += MAP_SQUARE_SIZE)
		{
			LocalPoint lp1 = LocalPoint.fromWorld(wv, wp.getX() - CULL_CHUNK_BORDERS_RANGE, y);
			LocalPoint lp2 = LocalPoint.fromWorld(wv, wp.getX() + CULL_CHUNK_BORDERS_RANGE, y);
			if (lp1 == null || lp2 == null) continue;

			boolean first = true;
			for (int x = lp1.getX(); x <= lp2.getX(); x += LOCAL_TILE_SIZE)
			{
				Point p = Perspective.localToCanvas(client,
					new LocalPoint(x - LOCAL_TILE_SIZE / 2, lp1.getY() - LOCAL_TILE_SIZE / 2, wv),
					plane);
				if (p != null)
				{
					if (first)
					{
						path.moveTo(p.getX(), p.getY());
						first = false;
					}
					else
					{
						path.lineTo(p.getX(), p.getY());
					}
				}
			}
		}
		graphics.draw(path);
	}
}

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
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Set;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
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
	private final AreaCompletionService areaCompletionService;
	private final LeagueScapePlugin plugin;
	private final TaskGridService taskGridService;

	private volatile Area hoveredArea = null;

	public LeagueScapeMapOverlay(Client client, AreaGraphService areaGraphService, LeagueScapeConfig config,
		PointsService pointsService, AreaCompletionService areaCompletionService, LeagueScapePlugin plugin,
		TaskGridService taskGridService)
	{
		this.client = client;
		this.areaGraphService = areaGraphService;
		this.config = config;
		this.pointsService = pointsService;
		this.areaCompletionService = areaCompletionService;
		this.plugin = plugin;
		this.taskGridService = taskGridService;
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
		java.util.Set<String> completedIds = (config.unlockMode() == LeagueScapeConfig.UnlockMode.POINTS_TO_COMPLETE)
			? areaCompletionService.getEffectiveCompletedAreaIds()
			: null;
		List<Area> unlockable = areaGraphService.getUnlockableNeighbors(completedIds);

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

	private static final Color POPUP_BG = new Color(0x54, 0x4D, 0x41);
	private static final Color POPUP_TEXT = new Color(0xC4, 0xB8, 0x96);
	private static final Color POPUP_BORDER = new Color(0x2a, 0x28, 0x24);
	private static final Color PRESSED_INSET_SHADOW = new Color(0, 0, 0, 70);
	private static final int PRESSED_INSET = 2;
	private static final Dimension RECTANGLE_BUTTON_SIZE = new Dimension(160, 28);
	private static final int TASK_ICON_SIZE = 28;

	/** Load a small icon for task tiles (quest-style from resources). */
	private static BufferedImage loadTaskIcon()
	{
		BufferedImage img = ImageUtil.loadImageResource(LeagueScapePlugin.class, "complete_checkmark.png");
		if (img != null)
		{
			return ImageUtil.resizeImage(img, TASK_ICON_SIZE, TASK_ICON_SIZE);
		}
		return null;
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

	private void showAreaDetailsPopup(Area area)
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
			closeBtn.addActionListener(e -> dialog.dispose());
			header.add(closeBtn, java.awt.BorderLayout.EAST);
			content.add(header, java.awt.BorderLayout.NORTH);

			// Center: status (with checkmark when complete) + cost
			JPanel center = new JPanel();
			center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));
			center.setOpaque(false);
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
			unlockBtn.setEnabled(canUnlock);
			if (canUnlock)
			{
				unlockBtn.addActionListener(e -> {
					if (plugin.unlockArea(area.getId(), cost)) dialog.dispose();
				});
			}
			southPanel.add(unlockBtn);
			content.add(southPanel, java.awt.BorderLayout.SOUTH);

			dialog.setContentPane(content);
			dialog.getRootPane().setBorder(new javax.swing.border.LineBorder(POPUP_BORDER, 2));
			dialog.pack();
			dialog.setLocationRelativeTo(client.getCanvas());
			dialog.setVisible(true);
		});
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
		BufferedImage taskIcon = loadTaskIcon();

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

			// Header: title "[area name] tasks" + close button (dedicated row, not cramped)
			JPanel header = new JPanel(new java.awt.BorderLayout(4, 0));
			header.setOpaque(false);
			header.setBorder(new javax.swing.border.EmptyBorder(0, 0, 8, 0));
			JLabel titleLabel = new JLabel(displayName + " tasks");
			titleLabel.setForeground(POPUP_TEXT);
			titleLabel.setFont(titleLabel.getFont().deriveFont(java.awt.Font.BOLD, 16f));
			header.add(titleLabel, java.awt.BorderLayout.CENTER);
			JButton closeBtn = newPopupButtonWithIcon(xBtnImg, POPUP_TEXT);
			closeBtn.addActionListener(e -> dialog.dispose());
			header.add(closeBtn, java.awt.BorderLayout.EAST);
			content.add(header, java.awt.BorderLayout.NORTH);

			// Grid panel: only non-locked tiles, inside scroll pane with vertical + horizontal scroll bars
			JPanel gridPanel = new JPanel();
			gridPanel.setOpaque(false);
			Runnable[] refreshHolder = new Runnable[1];
			refreshHolder[0] = () -> {
				gridPanel.removeAll();
				gridPanel.setLayout(new GridBagLayout());
				List<TaskTile> grid = taskGridService.getGridForArea(areaId);
				int center = 5;
				for (TaskTile tile : grid)
				{
					TaskState state = taskGridService.getState(areaId, tile.getId(), grid);
					if (state == TaskState.LOCKED)
					{
						continue; // hide locked tiles
					}
					int gx = tile.getCol() + center;
					int gy = center - tile.getRow();
					GridBagConstraints gbc = new GridBagConstraints();
					gbc.gridx = gx;
					gbc.gridy = gy;
					gbc.insets = new Insets(2, 2, 2, 2);
					JPanel cell = buildTaskCell(areaId, tile, state, checkmarkImg, padlockImg, tileSquare, buttonRect, taskIcon, POPUP_TEXT, refreshHolder[0], dialog, area);
					gridPanel.add(cell, gbc);
				}
				gridPanel.revalidate();
				gridPanel.repaint();
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

			// Back to area button (same style/size as Tasks and Unlock)
			JButton backBtn = newRectangleButton("Back to area", buttonRect, POPUP_TEXT);
			backBtn.addActionListener(e -> {
				dialog.dispose();
				showAreaDetailsPopup(area);
			});
			content.add(backBtn, java.awt.BorderLayout.SOUTH);

			dialog.setContentPane(content);
			dialog.getRootPane().setBorder(new javax.swing.border.LineBorder(POPUP_BORDER, 2));
			dialog.pack();
			dialog.setLocationRelativeTo(client.getCanvas());
			dialog.setVisible(true);
		});
	}

	private JPanel buildTaskCell(String areaId, TaskTile tile, TaskState state,
		BufferedImage checkmarkImg, BufferedImage padlockImg, BufferedImage tileBg, BufferedImage buttonRect,
		BufferedImage taskIcon, Color textColor, Runnable onRefresh, JDialog parentDialog, Area area)
	{
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
		cell.setLayout(new BoxLayout(cell, BoxLayout.Y_AXIS));
		cell.setOpaque(false);
		cell.setPreferredSize(new Dimension(72, 72));

		// Small icon (from SVG/resources)
		if (taskIcon != null)
		{
			JLabel iconLabel = new JLabel(new javax.swing.ImageIcon(taskIcon));
			iconLabel.setAlignmentX(java.awt.Component.CENTER_ALIGNMENT);
			cell.add(iconLabel);
		}
		JLabel nameLabel = new JLabel(tile.getDisplayName());
		nameLabel.setForeground(textColor);
		nameLabel.setFont(nameLabel.getFont().deriveFont(10f));
		nameLabel.setAlignmentX(java.awt.Component.CENTER_ALIGNMENT);
		cell.add(nameLabel);
		if (state == TaskState.CLAIMED && checkmarkImg != null)
		{
			cell.add(new JLabel(new javax.swing.ImageIcon(ImageUtil.resizeImage(checkmarkImg, 16, 16))));
		}

		// Left-click opens detail popup with task details and Complete/Claim button
		cell.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				if (e.getButton() != MouseEvent.BUTTON1) return;
				showTaskDetailPopup(parentDialog, areaId, tile, state, buttonRect, checkmarkImg, textColor, onRefresh);
			}
		});
		cell.setCursor(new java.awt.Cursor(java.awt.Cursor.HAND_CURSOR));

		return cell;
	}

	private void showTaskDetailPopup(JDialog parentDialog, String areaId, TaskTile tile, TaskState state,
		BufferedImage buttonRect, BufferedImage checkmarkImg, Color textColor, Runnable onRefresh)
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
		JDialog detail = new JDialog(frameOwner, tile.getDisplayName(), false);
		detail.setUndecorated(true);

		JPanel content = new JPanel();
		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		content.setBackground(POPUP_BG);
		content.setBorder(new javax.swing.border.CompoundBorder(
			new javax.swing.border.LineBorder(POPUP_BORDER, 2),
			new javax.swing.border.EmptyBorder(12, 14, 12, 14)));

		JLabel titleLabel = new JLabel(tile.getDisplayName());
		titleLabel.setForeground(textColor);
		titleLabel.setFont(titleLabel.getFont().deriveFont(java.awt.Font.BOLD, 13f));
		content.add(titleLabel);
		content.add(new JLabel(" "));
		JLabel detailsLabel = new JLabel("<html>Tier " + tile.getTier() + " &middot; " + tile.getPoints() + " point" + (tile.getPoints() != 1 ? "s" : "") + "</html>");
		detailsLabel.setForeground(textColor);
		content.add(detailsLabel);
		content.add(new JLabel(" "));

		if (state == TaskState.COMPLETED_UNCLAIMED)
		{
			JButton claimBtn = newRectangleButton("Claim", buttonRect, textColor);
			claimBtn.addActionListener(e -> {
				taskGridService.setClaimed(areaId, tile.getId());
				detail.dispose();
				onRefresh.run();
			});
			content.add(claimBtn);
		}
		else if (state == TaskState.REVEALED)
		{
			JButton completeBtn = newRectangleButton("Complete", buttonRect, textColor);
			completeBtn.addActionListener(e -> {
				taskGridService.setCompleted(areaId, tile.getId());
				detail.dispose();
				onRefresh.run();
			});
			content.add(completeBtn);
		}
		else
		{
			JLabel doneLabel = new JLabel("Claimed");
			doneLabel.setForeground(textColor);
			content.add(doneLabel);
		}

		detail.setContentPane(content);
		detail.getRootPane().setBorder(new javax.swing.border.LineBorder(POPUP_BORDER, 2));
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

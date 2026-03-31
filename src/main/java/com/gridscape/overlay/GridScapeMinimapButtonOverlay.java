package com.leaguescape.overlay;

import com.leaguescape.LeagueScapeConfig;
import com.leaguescape.LeagueScapePlugin;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import javax.inject.Inject;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.input.MouseListener;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.util.ImageUtil;

/**
 * Draws a LeagueScape button under the minimap, to the left of the world map orb (offset 28px south).
 * Left click: opens the Tasks panel (global in World Unlock mode, area in other modes).
 * Right click: shows a menu with "Tasks", "World Unlocks" (World Unlock mode only), and "Rules & Setup".
 */
public class LeagueScapeMinimapButtonOverlay extends Overlay implements MouseListener
{
	private static final int BUTTON_SIZE = 28;
	private static final int GAP = 6;
	/** Extra offset south from the orb row. */
	private static final int SOUTH_OFFSET = 28;

	private final Client client;
	private final LeagueScapeConfig config;
	private final LeagueScapePlugin plugin;

	private volatile Rectangle buttonBounds = null;
	private BufferedImage buttonImage;
	private BufferedImage buttonImageHovered;
	private volatile boolean taskIconHovered;

	@Inject
	public LeagueScapeMinimapButtonOverlay(Client client, LeagueScapeConfig config, LeagueScapePlugin plugin)
	{
		this.client = client;
		this.config = config;
		this.plugin = plugin;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		Widget orb = client.getWidget(InterfaceID.Orbs.ORB_WORLDMAP);
		if (orb == null || orb.isHidden())
		{
			buttonBounds = null;
			taskIconHovered = false;
			return null;
		}
		Rectangle orbBounds = orb.getBounds();
		int x = orbBounds.x - BUTTON_SIZE - GAP;
		int y = orbBounds.y + SOUTH_OFFSET;
		// Align vertically with orb row (orb may be taller than BUTTON_SIZE)
		if (orbBounds.height > BUTTON_SIZE)
			y = orbBounds.y + (orbBounds.height - BUTTON_SIZE) / 2 + SOUTH_OFFSET;
		buttonBounds = new Rectangle(x, y, BUTTON_SIZE, BUTTON_SIZE);

		if (buttonImage == null)
			buttonImage = ImageUtil.loadImageResource(LeagueScapePlugin.class, "task_icon.png");
		if (buttonImageHovered == null)
			buttonImageHovered = ImageUtil.loadImageResource(LeagueScapePlugin.class, "task_icon_hovered.png");
		BufferedImage source = taskIconHovered && buttonImageHovered != null ? buttonImageHovered : buttonImage;
		if (source != null)
		{
			Image scaled = source.getScaledInstance(BUTTON_SIZE, BUTTON_SIZE, Image.SCALE_SMOOTH);
			graphics.drawImage(scaled, x, y, null);
		}
		else
		{
			graphics.setColor(new java.awt.Color(0x54, 0x4D, 0x41));
			graphics.fillRect(x, y, BUTTON_SIZE, BUTTON_SIZE);
			graphics.setColor(new java.awt.Color(0xC4, 0xB8, 0x96));
			graphics.drawRect(x, y, BUTTON_SIZE, BUTTON_SIZE);
			graphics.drawString("L", x + 10, y + 20);
		}
		return new Dimension(BUTTON_SIZE + GAP + orbBounds.width, Math.max(BUTTON_SIZE, orbBounds.height));
	}

	@Override
	public java.awt.event.MouseEvent mouseClicked(java.awt.event.MouseEvent event)
	{
		return event;
	}

	@Override
	public java.awt.event.MouseEvent mousePressed(java.awt.event.MouseEvent event)
	{
		Rectangle bounds = buttonBounds;
		if (bounds == null) return event;
		int x = event.getX();
		int y = event.getY();
		if (!bounds.contains(x, y)) return event;
		event.consume();
		if (event.getButton() == java.awt.event.MouseEvent.BUTTON1)
			openTasksPanel();
		else if (event.getButton() == java.awt.event.MouseEvent.BUTTON3)
			showRightClickMenu(event.getX(), event.getY());
		return event;
	}

	@Override
	public java.awt.event.MouseEvent mouseReleased(java.awt.event.MouseEvent event)
	{
		return event;
	}

	@Override
	public java.awt.event.MouseEvent mouseEntered(java.awt.event.MouseEvent event)
	{
		return event;
	}

	@Override
	public java.awt.event.MouseEvent mouseExited(java.awt.event.MouseEvent event)
	{
		setHoveredAndMaybeRepaint(false);
		return event;
	}

	@Override
	public java.awt.event.MouseEvent mouseDragged(java.awt.event.MouseEvent event)
	{
		Rectangle bounds = buttonBounds;
		boolean over = bounds != null && bounds.contains(event.getX(), event.getY());
		setHoveredAndMaybeRepaint(over);
		return event;
	}

	@Override
	public java.awt.event.MouseEvent mouseMoved(java.awt.event.MouseEvent event)
	{
		Rectangle bounds = buttonBounds;
		boolean over = bounds != null && bounds.contains(event.getX(), event.getY());
		setHoveredAndMaybeRepaint(over);
		return event;
	}

	private void setHoveredAndMaybeRepaint(boolean over)
	{
		if (over == taskIconHovered)
			return;
		taskIconHovered = over;
		java.awt.Component canvas = client.getCanvas();
		if (canvas != null)
			canvas.repaint();
	}

	private void openTasksPanel()
	{
		if (config.unlockMode() == LeagueScapeConfig.UnlockMode.WORLD_UNLOCK)
			plugin.openGlobalTaskList();
		else
			plugin.openTasksForCurrentArea();
	}

	private void showRightClickMenu(int canvasX, int canvasY)
	{
		SwingUtilities.invokeLater(() -> {
			JPopupMenu menu = new JPopupMenu();
			JMenuItem tasksItem = new JMenuItem("Tasks");
			tasksItem.addActionListener(e -> openTasksPanel());
			menu.add(tasksItem);
			if (config.unlockMode() == LeagueScapeConfig.UnlockMode.WORLD_UNLOCK)
			{
				JMenuItem worldUnlocksItem = new JMenuItem("World Unlocks");
				worldUnlocksItem.addActionListener(e -> plugin.openWorldUnlockGrid());
				menu.add(worldUnlocksItem);
			}
			JMenuItem rulesSetupItem = new JMenuItem("Rules & Setup");
			rulesSetupItem.addActionListener(e -> plugin.openSetupDialog());
			menu.add(rulesSetupItem);
			java.awt.Component canvas = client.getCanvas();
			if (canvas != null && canvas.isShowing())
				menu.show(canvas, canvasX, canvasY);
		});
	}
}

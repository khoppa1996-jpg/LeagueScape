package com.leaguescape.overlay;

import com.leaguescape.task.TaskTile;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayPriority;

/**
 * Collection-log-style popup shown when a task is auto-completed. Displays for a few seconds then fades.
 */
@Singleton
public class TaskCompletionPopupOverlay extends Overlay
{
	private static final int DISPLAY_MS = 4000;
	private static final Color BG = new Color(30, 30, 30, 220);
	private static final Color BORDER = new Color(200, 180, 100, 255);
	private static final Color TEXT = new Color(255, 255, 255);

	private final Client client;

	private volatile String lastAreaId = null;
	private volatile TaskTile lastTile = null;
	private volatile int lastPoints = 0;
	private volatile long lastShownAt = 0;

	@Inject
	public TaskCompletionPopupOverlay(Client client)
	{
		this.client = client;
		setPosition(OverlayPosition.TOP_CENTER);
		setPriority(OverlayPriority.HIGH);
	}

	/**
	 * Show the task-complete popup for the given task. Called from the game thread after setCompleted.
	 */
	public void showCompleted(String areaId, TaskTile tile, int points)
	{
		this.lastAreaId = areaId;
		this.lastTile = tile;
		this.lastPoints = points;
		this.lastShownAt = System.currentTimeMillis();
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (lastTile == null) return null;
		long elapsed = System.currentTimeMillis() - lastShownAt;
		if (elapsed > DISPLAY_MS) return null;

		int padding = 12;
		int width = 260;
		int lineHeight = 18;
		int lines = 3; // title, task name, points
		int height = padding * 2 + lineHeight * lines;

		int canvasW = client.getCanvasWidth();
		int x = (canvasW - width) / 2;
		int y = 80;

		graphics.setColor(BG);
		graphics.fillRect(x, y, width, height);
		graphics.setColor(BORDER);
		graphics.drawRect(x, y, width, height);

		graphics.setColor(TEXT);
		graphics.setFont(graphics.getFont().deriveFont(14f));
		int ty = y + padding + lineHeight;
		graphics.drawString("Task complete!", x + padding, ty);
		ty += lineHeight;
		String name = lastTile.getDisplayName() != null ? lastTile.getDisplayName() : "Task";
		if (name.length() > 35)
			name = name.substring(0, 32) + "...";
		graphics.drawString(name, x + padding, ty);
		ty += lineHeight;
		graphics.drawString("Claim for " + lastPoints + " point" + (lastPoints != 1 ? "s" : ""), x + padding, ty);

		return new Dimension(width, height);
	}
}

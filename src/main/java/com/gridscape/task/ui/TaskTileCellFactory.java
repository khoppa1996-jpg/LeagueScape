package com.gridscape.task.ui;

import com.gridscape.util.ScaledImageCache;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.image.BufferedImage;
import javax.swing.JPanel;
import net.runelite.client.util.ImageUtil;

/**
 * Shared task-grid cell UI: fitted icon panels and claimed cells used by the global task panel,
 * world map task popup, and (for icon fitting) world unlock grid.
 */
public final class TaskTileCellFactory
{
	public static final int CLAIMED_CHECKMARK_SIZE = 18;
	public static final int CLAIMED_CHECKMARK_INSET = 4;
	public static final Color TASK_TILE_FALLBACK_BG = new Color(60, 55, 50);
	public static final Color CLAIMED_DESEATURATE_OVERLAY = new Color(120, 120, 120, 140);

	private TaskTileCellFactory()
	{
	}

	/** Tile background plus a single fitted icon (world unlock revealed-unclaimed cells). */
	public static void paintBackgroundAndFittedIcon(Graphics g, int cw, int ch, BufferedImage tileBg, BufferedImage iconImage, int margin)
	{
		if (tileBg != null)
		{
			ScaledImageCache.drawScaled(g, tileBg, 0, 0, cw, ch);
		}
		else
		{
			g.setColor(TASK_TILE_FALLBACK_BG);
			g.fillRect(0, 0, cw, ch);
		}
		if (iconImage != null)
		{
			paintIconFittedInMargin(g, iconImage, margin, cw, ch);
		}
	}

	/** Paints scaled tile background and optional center icon; pass component width/height. */
	public static void paintBackgroundAndCenterIcon(Graphics g, int cw, int ch, BufferedImage tileBg, BufferedImage centerIcon, boolean centerTile)
	{
		if (tileBg != null)
		{
			ScaledImageCache.drawScaled(g, tileBg, 0, 0, cw, ch);
		}
		else
		{
			g.setColor(TASK_TILE_FALLBACK_BG);
			g.fillRect(0, 0, cw, ch);
		}
		if (centerTile && centerIcon != null)
		{
			int size = Math.min(cw, ch) * 3 / 4;
			int x = (cw - size) / 2;
			int y = (ch - size) / 2;
			ScaledImageCache.drawScaled(g, centerIcon, x, y, size, size);
		}
	}

	/** Paints a task icon scaled to fit inside margins (same math as legacy task cells). */
	public static void paintIconFittedInMargin(Graphics g, BufferedImage iconImage, int margin, int cw, int ch)
	{
		if (iconImage == null) return;
		int innerW = Math.max(1, cw - 2 * margin);
		int innerH = Math.max(1, ch - 2 * margin);
		int iw = iconImage.getWidth();
		int ih = iconImage.getHeight();
		if (iw <= 0 || ih <= 0) return;
		double scale = Math.min((double) innerW / iw, (double) innerH / ih);
		int drawW = Math.max(1, (int) Math.round(iw * scale));
		int drawH = Math.max(1, (int) Math.round(ih * scale));
		int x = margin + (innerW - drawW) / 2;
		int y = margin + (innerH - drawH) / 2;
		ScaledImageCache.drawScaled(g, iconImage, x, y, drawW, drawH);
	}

	/**
	 * Icon area for a task tile: draws the icon fitted with margins. Caller adds to BorderLayout.CENTER and wires listeners.
	 */
	public static JPanel newFittedTaskIconPanel(BufferedImage iconImage, int margin)
	{
		JPanel iconPanel = new JPanel()
		{
			@Override
			protected void paintComponent(Graphics g)
			{
				super.paintComponent(g);
				paintIconFittedInMargin(g, iconImage, margin, getWidth(), getHeight());
			}
		};
		iconPanel.setOpaque(false);
		return iconPanel;
	}

	/**
	 * Claimed cell for area/global task grids: gray overlay + checkmark (center or corner).
	 */
	public static JPanel newClaimedTaskCellForTaskGrid(int tileSize, BufferedImage tileBg, BufferedImage checkmarkImg, BufferedImage centerTileIcon, boolean isCenter)
	{
		final BufferedImage bg = tileBg;
		final BufferedImage checkmark = checkmarkImg != null
			? ImageUtil.resizeImage(checkmarkImg, CLAIMED_CHECKMARK_SIZE, CLAIMED_CHECKMARK_SIZE) : null;
		final BufferedImage centerIcon = centerTileIcon;
		final boolean centerTile = isCenter;

		JPanel cell = new JPanel()
		{
			@Override
			protected void paintComponent(Graphics g)
			{
				if (bg != null)
				{
					ScaledImageCache.drawScaled(g, bg, 0, 0, getWidth(), getHeight());
				}
				else
				{
					g.setColor(TASK_TILE_FALLBACK_BG);
					g.fillRect(0, 0, getWidth(), getHeight());
				}
				if (centerTile && centerIcon != null)
				{
					int w = getWidth();
					int h = getHeight();
					int size = Math.min(w, h) * 3 / 4;
					int x = (w - size) / 2;
					int y = (h - size) / 2;
					ScaledImageCache.drawScaled(g, centerIcon, x, y, size, size);
				}
				g.setColor(CLAIMED_DESEATURATE_OVERLAY);
				g.fillRect(0, 0, getWidth(), getHeight());
				if (checkmark != null)
				{
					if (centerTile)
					{
						int x = (getWidth() - CLAIMED_CHECKMARK_SIZE) / 2;
						int y = (getHeight() - CLAIMED_CHECKMARK_SIZE) / 2;
						g.drawImage(checkmark, x, y, null);
					}
					else
					{
						g.drawImage(checkmark, getWidth() - CLAIMED_CHECKMARK_SIZE - CLAIMED_CHECKMARK_INSET,
							CLAIMED_CHECKMARK_INSET, null);
					}
				}
			}
		};
		cell.setOpaque(false);
		cell.setPreferredSize(new Dimension(tileSize, tileSize));
		cell.setCursor(new java.awt.Cursor(java.awt.Cursor.DEFAULT_CURSOR));
		return cell;
	}
}

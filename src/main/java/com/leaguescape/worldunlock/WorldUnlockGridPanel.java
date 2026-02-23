package com.leaguescape.worldunlock;

import com.leaguescape.LeagueScapePlugin;
import com.leaguescape.LeagueScapeSounds;
import com.leaguescape.points.PointsService;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Image;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Set;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import net.runelite.api.Client;
import net.runelite.client.audio.AudioPlayer;
import net.runelite.client.util.ImageUtil;

/**
 * Panel showing the World Unlock grid: tiles (hidden / revealed_locked / revealed_unlocked),
 * Goals button, and unlock flow. Used inside a dialog opened from the sidebar when mode is WORLD_UNLOCK.
 */
public class WorldUnlockGridPanel extends JPanel
{
	private static final Color BG = new Color(40, 38, 35);
	private static final Color BORDER = new Color(80, 75, 70);
	private static final Color TEXT = new Color(220, 215, 205);
	private static final int TILE_SIZE = 64;
	private static final int PAD = 4;

	private final WorldUnlockService worldUnlockService;
	private final PointsService pointsService;
	private final Runnable onClose;
	private final Runnable onOpenGoals;
	private final Client client;
	private final AudioPlayer audioPlayer;

	private BufferedImage padlockImg;
	private BufferedImage checkmarkImg;
	private BufferedImage tileBg;
	private JLabel pointsLabel;
	private JPanel gridPanel;

	public WorldUnlockGridPanel(WorldUnlockService worldUnlockService, PointsService pointsService,
		Runnable onClose, Runnable onOpenGoals, Client client, AudioPlayer audioPlayer)
	{
		this.worldUnlockService = worldUnlockService;
		this.pointsService = pointsService;
		this.onClose = onClose;
		this.onOpenGoals = onOpenGoals;
		this.client = client;
		this.audioPlayer = audioPlayer;
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setBackground(BG);
		setBorder(new javax.swing.border.CompoundBorder(
			new javax.swing.border.LineBorder(BORDER, 2),
			new javax.swing.border.EmptyBorder(10, 12, 10, 12)));

		padlockImg = ImageUtil.loadImageResource(LeagueScapePlugin.class, "padlock_icon.png");
		checkmarkImg = ImageUtil.loadImageResource(LeagueScapePlugin.class, "complete_checkmark.png");
		tileBg = ImageUtil.loadImageResource(LeagueScapePlugin.class, "empty_button_square.png");

		pointsLabel = new JLabel();
		pointsLabel.setForeground(TEXT);
		add(pointsLabel);

		gridPanel = new JPanel();
		gridPanel.setLayout(new GridBagLayout());
		gridPanel.setOpaque(false);
		JScrollPane scroll = new JScrollPane(gridPanel);
		scroll.setOpaque(false);
		scroll.getViewport().setOpaque(false);
		scroll.setPreferredSize(new Dimension(420, 340));
		add(scroll);

		JButton goalsBtn = new JButton("Goals");
		goalsBtn.setForeground(TEXT);
		goalsBtn.addActionListener(e -> {
			if (audioPlayer != null && client != null)
				LeagueScapeSounds.play(audioPlayer, LeagueScapeSounds.BUTTON_PRESS, client);
			if (onOpenGoals != null) onOpenGoals.run();
		});
		add(goalsBtn);

		JButton closeBtn = new JButton("Close");
		closeBtn.setForeground(TEXT);
		closeBtn.addActionListener(e -> {
			if (audioPlayer != null && client != null)
				LeagueScapeSounds.play(audioPlayer, LeagueScapeSounds.BUTTON_PRESS, client);
			if (onClose != null) onClose.run();
		});
		add(closeBtn);

		refresh();
	}

	public void refresh()
	{
		worldUnlockService.load();
		int spendable = pointsService.getEarnedTotal() - pointsService.getSpentTotal();
		pointsLabel.setText("Points: " + spendable);

		gridPanel.removeAll();
		Set<String> unlocked = worldUnlockService.getUnlockedIds();
		List<WorldUnlockTilePlacement> grid = worldUnlockService.getGrid();
		if (grid.isEmpty()) { gridPanel.revalidate(); gridPanel.repaint(); return; }

		int center = grid.stream()
			.mapToInt(p -> Math.max(Math.abs(p.getRow()), Math.abs(p.getCol())))
			.max().orElse(0);

		for (WorldUnlockTilePlacement placement : grid)
		{
			if (!worldUnlockService.isRevealed(placement, unlocked, grid))
				continue;

			WorldUnlockTile tile = placement.getTile();
			boolean unlockedTile = unlocked.contains(tile.getId());
			boolean canUnlock = !unlockedTile && worldUnlockService.isUnlockable(tile)
				&& (pointsService.getEarnedTotal() - pointsService.getSpentTotal()) >= tile.getCost();

			JPanel cell = buildTileCell(tile, unlockedTile, canUnlock);
			GridBagConstraints gbc = new GridBagConstraints();
			gbc.gridx = placement.getCol() + center;
			gbc.gridy = center - placement.getRow();
			gbc.insets = new Insets(PAD, PAD, PAD, PAD);
			gridPanel.add(cell, gbc);
		}
		gridPanel.revalidate();
		gridPanel.repaint();
	}

	private JPanel buildTileCell(WorldUnlockTile tile, boolean unlocked, boolean canUnlock)
	{
		final boolean showPadlock = !unlocked;
		final boolean showCheck = unlocked;
		final BufferedImage bg = tileBg;
		final BufferedImage padlock = padlockImg;
		final BufferedImage check = checkmarkImg != null ? ImageUtil.resizeImage(checkmarkImg, 20, 20) : null;
		final String name = tile.getDisplayName() != null ? tile.getDisplayName() : tile.getId();
		final int cost = tile.getCost();
		final String id = tile.getId();
		final int c = cost;

		JPanel cell = new JPanel()
		{
			@Override
			protected void paintComponent(Graphics g)
			{
				if (bg != null)
					g.drawImage(bg.getScaledInstance(getWidth(), getHeight(), Image.SCALE_SMOOTH), 0, 0, null);
				else
				{
					g.setColor(new Color(60, 55, 50));
					g.fillRect(0, 0, getWidth(), getHeight());
				}
				if (showPadlock && padlock != null)
				{
					int s = Math.min(getWidth(), getHeight()) * 3 / 4;
					g.drawImage(padlock.getScaledInstance(s, s, Image.SCALE_SMOOTH), (getWidth() - s) / 2, (getHeight() - s) / 2 - 8, null);
				}
				if (showCheck && check != null)
					g.drawImage(check, getWidth() - check.getWidth() - 2, 2, null);
				g.setColor(TEXT);
				g.setFont(getFont().deriveFont(10f));
				String line1 = name.length() > 12 ? name.substring(0, 11) + "…" : name;
				String line2 = cost + " pts";
				int w1 = g.getFontMetrics().stringWidth(line1);
				int w2 = g.getFontMetrics().stringWidth(line2);
				g.drawString(line1, (getWidth() - w1) / 2, getHeight() - 16);
				g.drawString(line2, (getWidth() - w2) / 2, getHeight() - 4);
			}
		};
		cell.setPreferredSize(new Dimension(TILE_SIZE, TILE_SIZE + 24));
		cell.setOpaque(false);

		if (!unlocked && canUnlock)
		{
			cell.addMouseListener(new MouseAdapter()
			{
				@Override
				public void mouseClicked(MouseEvent e)
				{
					if (e.getButton() != MouseEvent.BUTTON1) return;
					if (audioPlayer != null && client != null)
						LeagueScapeSounds.play(audioPlayer, LeagueScapeSounds.BUTTON_PRESS, client);
					if (worldUnlockService.unlock(id, c))
						SwingUtilities.invokeLater(() -> refresh());
				}
			});
			cell.setCursor(new java.awt.Cursor(java.awt.Cursor.HAND_CURSOR));
		}

		return cell;
	}
}

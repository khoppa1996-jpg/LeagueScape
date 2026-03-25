package com.leaguescape.worldunlock;

import com.leaguescape.LeagueScapePlugin;
import com.leaguescape.LeagueScapeSounds;
import com.leaguescape.grid.GridPos;
import com.leaguescape.util.RingBonusPopup;
import com.leaguescape.icons.IconCache;
import com.leaguescape.icons.IconResolver;
import com.leaguescape.icons.IconResources;
import com.leaguescape.points.PointsService;
import com.leaguescape.task.TaskState;
import com.leaguescape.task.TaskTile;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Image;
import java.awt.Insets;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import net.runelite.api.Client;
import net.runelite.client.audio.AudioPlayer;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.util.ImageUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Global task grid panel for World Unlock mode. Visually identical to the per-area Task Grid:
 * square icon tiles, spiral tier distribution, detail popups on click, zoom, drag-scroll.
 */
public class GlobalTaskListPanel extends JPanel
{
	private static final Logger log = LoggerFactory.getLogger(GlobalTaskListPanel.class);
	private static final Color POPUP_BG = com.leaguescape.util.LeagueScapeColors.POPUP_BG;
	private static final Color POPUP_TEXT = com.leaguescape.util.LeagueScapeColors.POPUP_TEXT;
	private static final Color POPUP_BORDER = new Color(0x2a, 0x28, 0x24);
	private static final Dimension RECTANGLE_BUTTON_SIZE = new Dimension(160, 28);
	private static final int BASE_TILE_SIZE = 72;
	private static final int TASK_TILE_ICON_MARGIN = 12;
	private static final int CLAIMED_CHECKMARK_SIZE = 18;
	private static final int CLAIMED_CHECKMARK_INSET = 4;

	private static final Map<String, BufferedImage> rawTaskIconCache = new ConcurrentHashMap<>();

	private final GlobalTaskListService globalTaskListService;
	private final PointsService pointsService;
	private final Runnable onClose;
	private final Runnable onOpenWorldUnlock;
	private final Client client;
	private final AudioPlayer audioPlayer;
	private final ClientThread clientThread;
	private final JDialog parentDialog;
	private final Runnable onOpenRulesSetup;

	private BufferedImage padlockImg;
	private BufferedImage checkmarkImg;
	private BufferedImage tileBg;
	private BufferedImage interfaceBg;
	private BufferedImage buttonRect;
	private BufferedImage xBtnImg;
	private BufferedImage defaultTaskIcon;
	private JLabel pointsLabel;
	private JPanel gridPanel;
	private JScrollPane scrollPane;
	private float zoom = 1.0f;
	private static final float ZOOM_MIN = 0.1f;
	private static final float ZOOM_MAX = 2.0f;
	private static final float ZOOM_STEP = 0.1f;

	private int layoutSeed;

	public GlobalTaskListPanel(GlobalTaskListService globalTaskListService, PointsService pointsService,
		Runnable onClose, Runnable onOpenWorldUnlock, Runnable onOpenRulesSetup, Client client, AudioPlayer audioPlayer, ClientThread clientThread, JDialog parentDialog)
	{
		this.globalTaskListService = globalTaskListService;
		this.pointsService = pointsService;
		this.onClose = onClose;
		this.onOpenWorldUnlock = onOpenWorldUnlock;
		this.onOpenRulesSetup = onOpenRulesSetup;
		this.client = client;
		this.audioPlayer = audioPlayer;
		this.clientThread = clientThread;
		this.parentDialog = parentDialog;
		this.layoutSeed = globalTaskListService.getOrCreateLayoutSeed();

		padlockImg = ImageUtil.loadImageResource(LeagueScapePlugin.class, "padlock_icon.png");
		checkmarkImg = ImageUtil.loadImageResource(LeagueScapePlugin.class, "complete_checkmark.png");
		tileBg = ImageUtil.loadImageResource(LeagueScapePlugin.class, "empty_button_square.png");
		interfaceBg = ImageUtil.loadImageResource(LeagueScapePlugin.class, "interface_template.png");
		buttonRect = ImageUtil.loadImageResource(LeagueScapePlugin.class, "empty_button_rectangle.png");
		xBtnImg = ImageUtil.loadImageResource(LeagueScapePlugin.class, "x_button.png");
		defaultTaskIcon = loadDefaultTaskIcon();

		setLayout(new BorderLayout(8, 8));
		setBackground(POPUP_BG);
		setBorder(new CompoundBorder(
			new LineBorder(POPUP_BORDER, 2),
			new EmptyBorder(10, 12, 10, 12)));
		setOpaque(true);

		JPanel header = new JPanel(new BorderLayout(4, 0));
		header.setOpaque(false);
		header.setBorder(new EmptyBorder(0, 0, 8, 0));
		JPanel titleRow = new JPanel(new BorderLayout(4, 0));
		titleRow.setOpaque(false);
		pointsLabel = new JLabel();
		pointsLabel.setForeground(POPUP_TEXT);
		titleRow.add(pointsLabel, BorderLayout.WEST);
		JLabel titleLabel = new JLabel("Global Tasks");
		titleLabel.setForeground(POPUP_TEXT);
		titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 16f));
		titleLabel.setHorizontalAlignment(JLabel.CENTER);
		titleRow.add(titleLabel, BorderLayout.CENTER);
		JButton closeBtn = newPopupButtonWithIcon(xBtnImg, POPUP_TEXT);
		closeBtn.addActionListener(e -> {
			playSound(LeagueScapeSounds.BUTTON_PRESS);
			if (onClose != null) onClose.run();
		});
		titleRow.add(closeBtn, BorderLayout.EAST);
		header.add(titleRow, BorderLayout.NORTH);
		add(header, BorderLayout.NORTH);

		gridPanel = new JPanel();
		gridPanel.setLayout(new GridBagLayout());
		gridPanel.setOpaque(false);

		scrollPane = new JScrollPane(gridPanel);
		scrollPane.setOpaque(false);
		scrollPane.getViewport().setOpaque(false);
		scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
		scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
		scrollPane.setPreferredSize(new Dimension(400, 320));
		scrollPane.setBorder(null);

		scrollPane.getViewport().addMouseWheelListener(e -> {
			float prev = zoom;
			if (e.getWheelRotation() < 0)
				zoom = Math.min(ZOOM_MAX, zoom + ZOOM_STEP);
			else
				zoom = Math.max(ZOOM_MIN, zoom - ZOOM_STEP);
			if (zoom != prev)
			{
				e.consume();
				SwingUtilities.invokeLater(this::refresh);
			}
		});

		final Point[] dragStart = new Point[1];
		scrollPane.getViewport().addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				dragStart[0] = e.getPoint();
			}
		});
		scrollPane.getViewport().addMouseMotionListener(new MouseMotionAdapter()
		{
			@Override
			public void mouseDragged(MouseEvent e)
			{
				if (dragStart[0] == null) return;
				Point vp = scrollPane.getViewport().getViewPosition();
				int dx = dragStart[0].x - e.getX();
				int dy = dragStart[0].y - e.getY();
				int nx = Math.max(0, Math.min(vp.x + dx, scrollPane.getViewport().getViewSize().width - scrollPane.getViewport().getExtentSize().width));
				int ny = Math.max(0, Math.min(vp.y + dy, scrollPane.getViewport().getViewSize().height - scrollPane.getViewport().getExtentSize().height));
				scrollPane.getViewport().setViewPosition(new Point(nx, ny));
				dragStart[0] = e.getPoint();
			}
		});
		add(scrollPane, BorderLayout.CENTER);

		JPanel southPanel = new JPanel(new BorderLayout(8, 0));
		southPanel.setOpaque(false);
		southPanel.setBorder(new EmptyBorder(0, 0, 8, 0));
		JPanel westButtons = new JPanel(new FlowLayout(FlowLayout.LEADING, 4, 0));
		westButtons.setOpaque(false);
		JButton worldUnlockBtn = newRectangleButton("World Unlock", buttonRect, POPUP_TEXT);
		worldUnlockBtn.addActionListener(e -> {
			playSound(LeagueScapeSounds.BUTTON_PRESS);
			if (onClose != null) onClose.run();
			if (onOpenWorldUnlock != null) onOpenWorldUnlock.run();
		});
		westButtons.add(worldUnlockBtn);
		JButton rulesSetupBtn = newRectangleButton("Rules & Setup", buttonRect, POPUP_TEXT);
		rulesSetupBtn.addActionListener(e -> {
			playSound(LeagueScapeSounds.BUTTON_PRESS);
			if (onOpenRulesSetup != null) onOpenRulesSetup.run();
		});
		westButtons.add(rulesSetupBtn);
		southPanel.add(westButtons, BorderLayout.WEST);
		JPanel zoomPanel = new JPanel(new FlowLayout(FlowLayout.TRAILING, 4, 0));
		zoomPanel.setOpaque(false);
		JButton zoomOutBtn = newRectangleButton("\u2212", tileBg, POPUP_TEXT);
		zoomOutBtn.setPreferredSize(new Dimension(28, 28));
		zoomOutBtn.setToolTipText("Zoom out");
		zoomOutBtn.addActionListener(e -> { zoom = Math.max(ZOOM_MIN, zoom - ZOOM_STEP); SwingUtilities.invokeLater(this::refresh); });
		JButton zoomInBtn = newRectangleButton("+", tileBg, POPUP_TEXT);
		zoomInBtn.setPreferredSize(new Dimension(28, 28));
		zoomInBtn.setToolTipText("Zoom in");
		zoomInBtn.addActionListener(e -> { zoom = Math.min(ZOOM_MAX, zoom + ZOOM_STEP); SwingUtilities.invokeLater(this::refresh); });
		zoomPanel.add(zoomOutBtn);
		zoomPanel.add(zoomInBtn);
		southPanel.add(zoomPanel, BorderLayout.EAST);
		add(southPanel, BorderLayout.SOUTH);

		refresh();
	}

	@Override
	protected void paintComponent(Graphics g)
	{
		super.paintComponent(g);
		if (interfaceBg != null)
			g.drawImage(interfaceBg.getScaledInstance(getWidth(), getHeight(), Image.SCALE_SMOOTH), 0, 0, null);
	}

	public void refresh()
	{
		// Update points display
		int spendable = pointsService.getEarnedTotal() - pointsService.getSpentTotal();
		pointsLabel.setText("Points: " + spendable);

		// Clear existing tiles before rebuilding
		gridPanel.removeAll();

		// Build grid from service (center + revealed adjacent + locked positions)
		List<TaskTile> grid = globalTaskListService.buildGlobalGrid(layoutSeed);

		// maxRing = grid extent for layout (gridx/gridy range 0..2*maxRing)
		int maxRing = grid.stream()
			.mapToInt(t -> Math.max(Math.abs(t.getRow()), Math.abs(t.getCol())))
			.max().orElse(5);

		int tileSize = Math.max(24, (int) (BASE_TILE_SIZE * zoom));
		int iconMargin = Math.max(1, (tileSize * TASK_TILE_ICON_MARGIN) / BASE_TILE_SIZE);
		int iconMaxFit = Math.max(1, tileSize - 2 * iconMargin);

		BufferedImage combatRaw = loadRawLocalIcon("Combat", null, null);
		BufferedImage combatScaled = combatRaw != null ? scaleToFitAllowUpscale(combatRaw, iconMaxFit, iconMaxFit) : null;
		int refSize = (combatScaled != null) ? Math.max(combatScaled.getWidth(), combatScaled.getHeight()) : iconMaxFit;

		final List<TaskTile> gridFinal = grid;
		int displayedCount = 0;
		// Iterate all tiles in grid; skip LOCKED (not revealed)
		for (TaskTile tile : grid)
		{
			TaskState state = globalTaskListService.getGlobalState(tile.getId(), gridFinal);
			if (state == TaskState.LOCKED) continue;  // Do not draw locked tiles

			displayedCount++;
			boolean isCenter = (tile.getRow() == 0 && tile.getCol() == 0);

			BufferedImage taskIcon = null;
			if (!isCenter)
			{
				String cacheKey = tile.getTaskType() != null ? ("type:" + tile.getTaskType()) : tile.getDisplayName();
				if (com.leaguescape.constants.TaskTypes.KILL_COUNT.equalsIgnoreCase(tile.getTaskType()) && tile.getBossId() != null && !tile.getBossId().isEmpty())
					cacheKey = "killCount:" + tile.getBossId();
				BufferedImage raw = rawTaskIconCache.get(cacheKey);
				if (raw == null)
				{
					raw = loadRawLocalIcon(tile.getTaskType(), tile.getDisplayName(), tile.getBossId());
					if (raw == null) raw = defaultTaskIcon;
					if (raw != null) rawTaskIconCache.put(cacheKey, raw);
				}
				if (raw != null)
					taskIcon = isIconMatchCombatSize(tile.getTaskType(), tile.getDisplayName())
						? scaleToLargestDimension(raw, refSize)
						: scaleToFitAllowUpscale(raw, iconMaxFit, iconMaxFit);
				else
					taskIcon = defaultTaskIcon != null ? scaleToFitAllowUpscale(defaultTaskIcon, iconMaxFit, iconMaxFit) : null;
			}

			// Layout: gridx = col + maxRing, gridy = maxRing - row (matches World Unlock panel)
			int gx = tile.getCol() + maxRing;
			int gy = maxRing - tile.getRow();
			GridBagConstraints gbc = new GridBagConstraints();
			gbc.gridx = gx;
			gbc.gridy = gy;
			gbc.insets = new Insets(2, 2, 2, 2);

			// Build cell (clickable, shows icon/state) and add to grid
			JPanel cell = buildTaskCell(tile, state, taskIcon, tileSize, iconMargin, isCenter, gridFinal);
			gridPanel.add(cell, gbc);
		}

		log.debug("[GlobalTaskPanel] refresh: grid size={}, displayed tiles={}", grid.size(), displayedCount);

		// Match World Unlock: no setPreferredSize — let GridBagLayout compute from components
		// so tiles at outer extents are fully included in the scrollable area
		gridPanel.revalidate();
		gridPanel.repaint();
		scrollPane.revalidate();

		// Always focus view on most recently viewed tile (or center if none)
		if (scrollPane != null && displayedCount > 0)
		{
			int[] last = globalTaskListService.loadLastViewedPosition();
			final int focusRow = (last != null && last.length >= 2) ? last[0] : 0;
			final int focusCol = (last != null && last.length >= 2) ? last[1] : 0;
			final int fmaxRing = maxRing;
			final int ftileSize = tileSize;
			final int fpad = 2 * 2;
			SwingUtilities.invokeLater(() -> {
				// Match World Unlock layout coords
				int gx = focusCol + fmaxRing;
				int gy = fmaxRing - focusRow;
				int cellW = ftileSize + fpad;
				int cellH = ftileSize + fpad;
				int px = gx * cellW;
				int py = gy * cellH;
				javax.swing.JViewport vp = scrollPane.getViewport();
				if (vp == null) return;
				int vw = vp.getExtentSize().width;
				int vh = vp.getExtentSize().height;
				java.awt.Dimension viewSize = gridPanel.getPreferredSize();
				int maxX = Math.max(0, viewSize.width - vw);
				int maxY = Math.max(0, viewSize.height - vh);
				// Center the view on the tile; clamp so outer extents remain visible
				int vpx = Math.max(0, Math.min(px - vw / 2 + cellW / 2, maxX));
				int vpy = Math.max(0, Math.min(py - vh / 2 + cellH / 2, maxY));
				vp.setViewPosition(new Point(vpx, vpy));
			});
		}
	}

	private JPanel buildTaskCell(TaskTile tile, TaskState state, BufferedImage taskIcon,
		int tileSize, int iconMargin, boolean isCenter, List<TaskTile> grid)
	{
		if (state == TaskState.CLAIMED)
		{
			return buildClaimedCell(tileSize, isCenter);
		}

		final BufferedImage tileBgFinal = tileBg;
		final BufferedImage padlockFinal = padlockImg;

		JPanel cell = new JPanel()
		{
			@Override
			protected void paintComponent(Graphics g)
			{
				if (tileBgFinal != null)
					g.drawImage(tileBgFinal.getScaledInstance(getWidth(), getHeight(), Image.SCALE_SMOOTH), 0, 0, null);
				else
				{
					g.setColor(new Color(60, 55, 50));
					g.fillRect(0, 0, getWidth(), getHeight());
				}
				if (isCenter && padlockFinal != null)
				{
					int w = getWidth(), h = getHeight();
					int size = Math.min(w, h) * 3 / 4;
					int x = (w - size) / 2, y = (h - size) / 2;
					g.drawImage(padlockFinal.getScaledInstance(size, size, Image.SCALE_SMOOTH), x, y, null);
				}
				super.paintComponent(g);
			}
		};
		cell.setLayout(new BorderLayout());
		cell.setOpaque(false);
		cell.setPreferredSize(new Dimension(tileSize, tileSize));

		if (taskIcon != null && !isCenter)
		{
			final BufferedImage iconImage = taskIcon;
			final int margin = iconMargin;
			JPanel iconPanel = new JPanel()
			{
				@Override
				protected void paintComponent(Graphics g)
				{
					super.paintComponent(g);
					int w = getWidth(), h = getHeight();
					int innerW = Math.max(1, w - 2 * margin);
					int innerH = Math.max(1, h - 2 * margin);
					int iw = iconImage.getWidth(), ih = iconImage.getHeight();
					if (iw <= 0 || ih <= 0) return;
					double scale = Math.min((double) innerW / iw, (double) innerH / ih);
					int drawW = Math.max(1, (int) Math.round(iw * scale));
					int drawH = Math.max(1, (int) Math.round(ih * scale));
					int x = margin + (innerW - drawW) / 2;
					int y = margin + (innerH - drawH) / 2;
					g.drawImage(iconImage.getScaledInstance(drawW, drawH, Image.SCALE_SMOOTH), x, y, null);
				}
			};
			iconPanel.setOpaque(false);
			cell.add(iconPanel, BorderLayout.CENTER);
		}

		if (state == TaskState.COMPLETED_UNCLAIMED && !isCenter)
		{
			final JPanel outerCell = cell;
			JPanel overlay = new JPanel()
			{
				@Override
				protected void paintComponent(Graphics g)
				{
					super.paintComponent(g);
					g.setColor(new Color(80, 160, 80, 50));
					g.fillRect(0, 0, getWidth(), getHeight());
				}
			};
			overlay.setOpaque(false);
			cell.add(overlay, BorderLayout.SOUTH);
		}

		// Click handler: center=claim, completed=claim, revealed=show details popup. Claiming allowed only after starter area unlocked on World Unlock grid.
		cell.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				if (e.getButton() != MouseEvent.BUTTON1) return;

				if (isCenter)
				{
					if (!globalTaskListService.isStarterAreaUnlockedOnGrid())
					{
						LeagueScapeSounds.play(audioPlayer, LeagueScapeSounds.LOCKED, client);
						JOptionPane.showMessageDialog(parentDialog, "Unlock the starter area on the World Unlock grid first.");
						return;
					}
					playSound(LeagueScapeSounds.TASK_COMPLETE);
					globalTaskListService.claimCenter();  // Unlocks starter + marks center claimed
					SwingUtilities.invokeLater(GlobalTaskListPanel.this::refresh);  // Refresh to show adjacent tiles
					return;
				}

				if (state == TaskState.COMPLETED_UNCLAIMED)
				{
					if (!globalTaskListService.isStarterAreaUnlockedOnGrid())
					{
						LeagueScapeSounds.play(audioPlayer, LeagueScapeSounds.LOCKED, client);
						JOptionPane.showMessageDialog(parentDialog, "Unlock the starter area on the World Unlock grid first.");
						return;
					}
					String key = GlobalTaskListService.taskKeyFromName(tile.getDisplayName());
					playSound(LeagueScapeSounds.TASK_COMPLETE);
					int ringBonus = globalTaskListService.claimTask(key, tile.getRow(), tile.getCol());
					showRingBonusPopupIfNeeded(ringBonus, tile.getRow(), tile.getCol());
					SwingUtilities.invokeLater(GlobalTaskListPanel.this::refresh);
					return;
				}

				// REVEALED: show detail popup (click opens details panel)
				playSound(LeagueScapeSounds.BUTTON_PRESS);
				showTaskDetailPopup(tile, state);
			}
		});
		cell.setCursor(new java.awt.Cursor(java.awt.Cursor.HAND_CURSOR));
		return cell;
	}

	private JPanel buildClaimedCell(int tileSize, boolean isCenter)
	{
		final BufferedImage bg = tileBg;
		final BufferedImage checkmark = checkmarkImg != null
			? ImageUtil.resizeImage(checkmarkImg, CLAIMED_CHECKMARK_SIZE, CLAIMED_CHECKMARK_SIZE) : null;
		final BufferedImage padlock = padlockImg;

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
				if (isCenter && padlock != null)
				{
					int w = getWidth(), h = getHeight();
					int size = Math.min(w, h) * 3 / 4;
					int x = (w - size) / 2, y = (h - size) / 2;
					g.drawImage(padlock.getScaledInstance(size, size, Image.SCALE_SMOOTH), x, y, null);
				}
				g.setColor(new Color(120, 120, 120, 140));
				g.fillRect(0, 0, getWidth(), getHeight());
				if (checkmark != null)
				{
					if (isCenter)
					{
						int x = (getWidth() - CLAIMED_CHECKMARK_SIZE) / 2;
						int y = (getHeight() - CLAIMED_CHECKMARK_SIZE) / 2;
						g.drawImage(checkmark, x, y, null);
					}
					else
					{
						int x = getWidth() - CLAIMED_CHECKMARK_SIZE - CLAIMED_CHECKMARK_INSET;
						int y = CLAIMED_CHECKMARK_INSET;
						g.drawImage(checkmark, x, y, null);
					}
				}
			}
		};
		cell.setOpaque(false);
		cell.setPreferredSize(new Dimension(tileSize, tileSize));
		return cell;
	}

	private void showRingBonusPopupIfNeeded(int bonusPoints, int row, int col)
	{
		if (bonusPoints <= 0) return;
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
		Component loc = parentDialog != null ? parentDialog : client.getCanvas();
		RingBonusPopup.showAsync(frameOwner, loc, client, audioPlayer, GridPos.ringNumber(row, col), bonusPoints, true, null);
	}

	private void showTaskDetailPopup(TaskTile tile, TaskState state)
	{
		globalTaskListService.saveLastViewedPosition(tile.getRow(), tile.getCol());
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

		String windowTitle = tile.getDisplayName();
		JDialog detail = new JDialog(frameOwner, windowTitle, false);
		detail.setUndecorated(true);
		LeagueScapePlugin.registerEscapeToClose(detail);

		JPanel content = new JPanel(new BorderLayout(8, 8));
		content.setBackground(POPUP_BG);
		content.setBorder(new CompoundBorder(
			new LineBorder(POPUP_BORDER, 2),
			new EmptyBorder(12, 14, 12, 14)));

		JPanel headerPanel = new JPanel(new BorderLayout(4, 0));
		headerPanel.setOpaque(false);
		JLabel titleLabel = new JLabel(windowTitle);
		titleLabel.setForeground(POPUP_TEXT);
		titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 13f));
		headerPanel.add(titleLabel, BorderLayout.CENTER);
		JButton closeBtn = newPopupButtonWithIcon(xBtnImg, POPUP_TEXT);
		closeBtn.addActionListener(e -> { playSound(LeagueScapeSounds.BUTTON_PRESS); detail.dispose(); });
		headerPanel.add(closeBtn, BorderLayout.EAST);
		content.add(headerPanel, BorderLayout.NORTH);

		JPanel body = new JPanel();
		body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
		body.setOpaque(false);

		JLabel detailsLabel = new JLabel("<html>Tier " + tile.getTier() + " &middot; " + tile.getPoints() + " point" + (tile.getPoints() != 1 ? "s" : "") + "</html>");
		detailsLabel.setForeground(POPUP_TEXT);
		body.add(detailsLabel);
		body.add(new JLabel(" "));

		String taskKey = GlobalTaskListService.taskKeyFromName(tile.getDisplayName());
		boolean alreadyClaimed = globalTaskListService.isClaimed(taskKey);

		if (alreadyClaimed)
		{
			JLabel doneLabel = new JLabel("Claimed");
			doneLabel.setForeground(new Color(120, 200, 120));
			body.add(doneLabel);
		}
		else if (state == TaskState.COMPLETED_UNCLAIMED)
		{
			JLabel revealLabel = new JLabel("<html>Task completed! Click Claim to earn points.</html>");
			revealLabel.setForeground(POPUP_TEXT);
			body.add(revealLabel);
			JButton claimBtn = newRectangleButton("Claim", buttonRect, POPUP_TEXT);
			int claimRow = tile.getRow(), claimCol = tile.getCol();
			claimBtn.addActionListener(e -> {
				playSound(LeagueScapeSounds.TASK_COMPLETE);
				int ringBonus = globalTaskListService.claimTask(taskKey, claimRow, claimCol);
				detail.dispose();
				if (ringBonus > 0)
					showRingBonusPopupIfNeeded(ringBonus, claimRow, claimCol);
				SwingUtilities.invokeLater(this::refresh);
			});
			body.add(claimBtn);
		}
		else if (state == TaskState.REVEALED)
		{
			JLabel revealLabel = new JLabel("<html>Complete this task then click Claim.</html>");
			revealLabel.setForeground(POPUP_TEXT);
			body.add(revealLabel);
			JButton claimBtn = newRectangleButton("Claim", buttonRect, POPUP_TEXT);
			int claimRow = tile.getRow(), claimCol = tile.getCol();
			claimBtn.addActionListener(e -> {
				playSound(LeagueScapeSounds.TASK_COMPLETE);
				globalTaskListService.setCompleted(taskKey);
				int ringBonus = globalTaskListService.claimTask(taskKey, claimRow, claimCol);
				detail.dispose();
				if (ringBonus > 0)
					showRingBonusPopupIfNeeded(ringBonus, claimRow, claimCol);
				SwingUtilities.invokeLater(this::refresh);
			});
			body.add(claimBtn);
		}

		content.add(body, BorderLayout.CENTER);
		detail.setContentPane(content);
		detail.getRootPane().setBorder(new LineBorder(POPUP_BORDER, 2));
		detail.addWindowFocusListener(new java.awt.event.WindowAdapter()
		{
			@Override
			public void windowLostFocus(java.awt.event.WindowEvent e)
			{
				SwingUtilities.invokeLater(() -> {
					if (detail.isDisplayable()) detail.dispose();
				});
			}
		});
		detail.pack();
		if (parentDialog != null)
			detail.setLocationRelativeTo(parentDialog);
		else
			detail.setLocationRelativeTo(client.getCanvas());
		detail.setVisible(true);
		detail.requestFocusInWindow();
	}

	// --- Icon helpers (same as LeagueScapeMapOverlay) ---

	private static BufferedImage loadRawLocalIcon(String taskType, String displayName, String bossId)
	{
		String path = IconResolver.resolveTaskTileLocalIconPath(taskType, displayName, bossId);
		if (path == null) return null;
		return rawTaskIconCache.computeIfAbsent(path, p -> IconCache.loadWithFallback(p, IconResources.GENERIC_TASK_ICON));
	}

	private static boolean isCollectionLogTask(String taskType, String displayName)
	{
		if (com.leaguescape.constants.TaskTypes.isCollectionLogType(taskType)) return true;
		return displayName != null && displayName.toLowerCase().contains("collection log");
	}

	private static boolean isIconMatchCombatSize(String taskType, String displayName)
	{
		if (taskType != null)
		{
			if (com.leaguescape.constants.TaskTypes.QUEST.equalsIgnoreCase(taskType)) return true;
			if (com.leaguescape.constants.TaskTypes.isAchievementDiaryType(taskType)) return true;
		}
		return isCollectionLogTask(taskType, displayName);
	}

	private static BufferedImage scaleToFitAllowUpscale(BufferedImage src, int maxW, int maxH) { return IconCache.scaleToFitAllowUpscale(src, maxW, maxH); }
	private static BufferedImage scaleToLargestDimension(BufferedImage src, int targetMaxDimension) { return IconCache.scaleToLargestDimension(src, targetMaxDimension); }

	private static BufferedImage loadDefaultTaskIcon()
	{
		return IconCache.loadWithFallback(IconResources.GENERIC_TASK_ICON,
			IconResources.TASK_ICONS_RESOURCE_PREFIX + "Other_icon.png");
	}

	// --- UI helpers ---

	private void playSound(String sound)
	{
		if (audioPlayer != null && client != null)
			LeagueScapeSounds.play(audioPlayer, sound, client);
	}

	private static JButton newRectangleButton(String text, BufferedImage buttonRect, Color textColor)
	{
		JButton b = com.leaguescape.util.LeagueScapeSwingUtil.newRectangleButton(text, buttonRect, textColor);
		b.setPreferredSize(RECTANGLE_BUTTON_SIZE);
		return b;
	}

	private static JButton newPopupButtonWithIcon(BufferedImage iconImg, Color fallbackTextColor)
	{
		return com.leaguescape.util.LeagueScapeSwingUtil.newPopupButtonWithIcon(iconImg, fallbackTextColor);
	}
}

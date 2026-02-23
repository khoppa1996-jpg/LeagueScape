package com.leaguescape.worldunlock;

import com.leaguescape.LeagueScapePlugin;
import com.leaguescape.LeagueScapeSounds;
import com.leaguescape.points.PointsService;
import com.leaguescape.task.TaskState;
import com.leaguescape.task.TaskTile;
import java.awt.BorderLayout;
import java.awt.Color;
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

/**
 * Global task grid panel for World Unlock mode. Visually identical to the per-area Task Grid:
 * square icon tiles, spiral tier distribution, detail popups on click, zoom, drag-scroll.
 */
public class GlobalTaskListPanel extends JPanel
{
	private static final Color POPUP_BG = new Color(0x54, 0x4D, 0x41);
	private static final Color POPUP_TEXT = new Color(0xC4, 0xB8, 0x96);
	private static final Color POPUP_BORDER = new Color(0x2a, 0x28, 0x24);
	private static final Color PRESSED_INSET_SHADOW = new Color(0, 0, 0, 70);
	private static final int PRESSED_INSET = 2;
	private static final Dimension RECTANGLE_BUTTON_SIZE = new Dimension(160, 28);
	private static final int BASE_TILE_SIZE = 72;
	private static final int TASK_TILE_ICON_MARGIN = 12;
	private static final int CLAIMED_CHECKMARK_SIZE = 18;
	private static final int CLAIMED_CHECKMARK_INSET = 4;

	private static final String TASK_ICONS_RESOURCE_PREFIX = "/com/taskIcons/";
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
		TASK_TYPE_LOCAL_ICON.put("Magic", "Magic_icon.png");
		TASK_TYPE_LOCAL_ICON.put("Hunter", "Hunter_icon_(detail).png");
		TASK_TYPE_LOCAL_ICON.put("Construction", "Construction_icon_(detail).png");
		TASK_TYPE_LOCAL_ICON.put("Slayer", "Slayer_icon_(detail).png");
		TASK_TYPE_LOCAL_ICON.put("Sailing", "Sailing_icon_(detail).png");
		TASK_TYPE_LOCAL_ICON.put("Quest", "Quest.png");
		TASK_TYPE_LOCAL_ICON.put("Achievement Diary", "Achievement_Diaries.png");
		TASK_TYPE_LOCAL_ICON.put("Diary", "Achievement_Diaries.png");
		TASK_TYPE_LOCAL_ICON.put("Other", "Other_icon.png");
		TASK_TYPE_LOCAL_ICON.put("Level", "Stats_icon.png");
	}

	private static final Map<String, BufferedImage> rawTaskIconCache = new ConcurrentHashMap<>();

	private final GlobalTaskListService globalTaskListService;
	private final PointsService pointsService;
	private final Runnable onClose;
	private final Client client;
	private final AudioPlayer audioPlayer;
	private final ClientThread clientThread;
	private final JDialog parentDialog;

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
	private static final float ZOOM_MIN = 0.5f;
	private static final float ZOOM_MAX = 2.0f;
	private static final float ZOOM_STEP = 0.15f;

	private int layoutSeed;
	private int lastTaskCount = 0;
	private boolean scrollFocusPending = true;

	public GlobalTaskListPanel(GlobalTaskListService globalTaskListService, PointsService pointsService,
		Runnable onClose, Client client, AudioPlayer audioPlayer, ClientThread clientThread, JDialog parentDialog)
	{
		this.globalTaskListService = globalTaskListService;
		this.pointsService = pointsService;
		this.onClose = onClose;
		this.client = client;
		this.audioPlayer = audioPlayer;
		this.clientThread = clientThread;
		this.parentDialog = parentDialog;
		this.layoutSeed = (int) System.nanoTime();

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
		JLabel titleLabel = new JLabel("Global Tasks");
		titleLabel.setForeground(POPUP_TEXT);
		titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 16f));
		titleRow.add(titleLabel, BorderLayout.CENTER);
		JButton closeBtn = newPopupButtonWithIcon(xBtnImg, POPUP_TEXT);
		closeBtn.addActionListener(e -> {
			playSound(LeagueScapeSounds.BUTTON_PRESS);
			if (onClose != null) onClose.run();
		});
		titleRow.add(closeBtn, BorderLayout.EAST);
		header.add(titleRow, BorderLayout.NORTH);
		pointsLabel = new JLabel();
		pointsLabel.setForeground(POPUP_TEXT);
		header.add(pointsLabel, BorderLayout.SOUTH);
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
		JPanel zoomPanel = new JPanel(new FlowLayout(FlowLayout.TRAILING, 4, 0));
		zoomPanel.setOpaque(false);
		JButton zoomOutBtn = newRectangleButton("\u2212", buttonRect, POPUP_TEXT);
		zoomOutBtn.setToolTipText("Zoom out");
		zoomOutBtn.addActionListener(e -> { zoom = Math.max(ZOOM_MIN, zoom - ZOOM_STEP); SwingUtilities.invokeLater(this::refresh); });
		JButton zoomInBtn = newRectangleButton("+", buttonRect, POPUP_TEXT);
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
		int spendable = pointsService.getEarnedTotal() - pointsService.getSpentTotal();
		pointsLabel.setText("Points: " + spendable);

		gridPanel.removeAll();

		List<TaskTile> grid = globalTaskListService.buildGlobalGrid(layoutSeed);

		if (grid.size() <= 1)
		{
			JLabel empty = new JLabel("No tasks yet. Unlock tiles on the World Unlock grid to add tasks.");
			empty.setForeground(POPUP_TEXT);
			GridBagConstraints gbc = new GridBagConstraints();
			gbc.gridx = 0;
			gbc.gridy = 0;
			gridPanel.add(empty, gbc);
			gridPanel.revalidate();
			gridPanel.repaint();
			return;
		}

		int center = grid.stream()
			.mapToInt(t -> Math.max(Math.abs(t.getRow()), Math.abs(t.getCol())))
			.max().orElse(5);

		int tileSize = Math.max(24, (int) (BASE_TILE_SIZE * zoom));
		int iconMargin = Math.max(1, (tileSize * TASK_TILE_ICON_MARGIN) / BASE_TILE_SIZE);
		int iconMaxFit = Math.max(1, tileSize - 2 * iconMargin);

		BufferedImage combatRaw = loadRawLocalIcon("Combat", null);
		BufferedImage combatScaled = combatRaw != null ? scaleToFitAllowUpscale(combatRaw, iconMaxFit, iconMaxFit) : null;
		int refSize = (combatScaled != null) ? Math.max(combatScaled.getWidth(), combatScaled.getHeight()) : iconMaxFit;

		final List<TaskTile> gridFinal = grid;
		for (TaskTile tile : grid)
		{
			TaskState state = globalTaskListService.getGlobalState(tile.getId(), gridFinal);
			if (state == TaskState.LOCKED) continue;

			boolean isCenter = (tile.getRow() == 0 && tile.getCol() == 0);

			BufferedImage taskIcon = null;
			if (!isCenter)
			{
				String cacheKey = tile.getTaskType() != null ? ("type:" + tile.getTaskType()) : tile.getDisplayName();
				BufferedImage raw = rawTaskIconCache.get(cacheKey);
				if (raw == null)
				{
					raw = loadRawLocalIcon(tile.getTaskType(), tile.getDisplayName());
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

			int gx = tile.getCol() + center;
			int gy = center - tile.getRow();
			GridBagConstraints gbc = new GridBagConstraints();
			gbc.gridx = gx;
			gbc.gridy = gy;
			gbc.insets = new Insets(2, 2, 2, 2);

			JPanel cell = buildTaskCell(tile, state, taskIcon, tileSize, iconMargin, isCenter, gridFinal);
			gridPanel.add(cell, gbc);
		}

		// Extend scrollable area beyond current tiles by at least one ring so the grid can grow
		// (e.g. when a tile at ring 7 is claimed, ring 8 is visible by scrolling)
		int minRow = grid.stream().mapToInt(TaskTile::getRow).min().orElse(0);
		int maxRow = grid.stream().mapToInt(TaskTile::getRow).max().orElse(0);
		int minCol = grid.stream().mapToInt(TaskTile::getCol).min().orElse(0);
		int maxCol = grid.stream().mapToInt(TaskTile::getCol).max().orElse(0);
		int pad = 2 * 2;
		int extraRing = 2; // extra space in each direction so next ring is scrollable
		int cols = (maxCol - minCol + 1) + 2 * extraRing;
		int rows = (maxRow - minRow + 1) + 2 * extraRing;
		int minScrollWidth = 400;
		int minScrollHeight = 320;
		gridPanel.setPreferredSize(new Dimension(
			Math.max(cols * (tileSize + pad), minScrollWidth),
			Math.max(rows * (tileSize + pad), minScrollHeight)));

		gridPanel.revalidate();
		gridPanel.repaint();

		// Focus view on center (first time) or last viewed tile when panel is opened
		if (scrollFocusPending && scrollPane != null)
		{
			scrollFocusPending = false;
			int[] last = globalTaskListService.loadLastViewedPosition();
			final int focusRow = (last != null && last.length >= 2) ? last[0] : 0;
			final int focusCol = (last != null && last.length >= 2) ? last[1] : 0;
			final int fcenter = center;
			final int ftileSize = tileSize;
			final int fpad = pad;
			SwingUtilities.invokeLater(() -> {
				int gx = focusCol + fcenter;
				int gy = fcenter - focusRow;
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

		cell.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				if (e.getButton() != MouseEvent.BUTTON1) return;

				if (isCenter)
				{
					playSound(LeagueScapeSounds.TASK_COMPLETE);
					globalTaskListService.claimCenter();
					SwingUtilities.invokeLater(GlobalTaskListPanel.this::refresh);
					return;
				}

				if (state == TaskState.COMPLETED_UNCLAIMED)
				{
					String key = GlobalTaskListService.taskKeyFromName(tile.getDisplayName());
					playSound(LeagueScapeSounds.TASK_COMPLETE);
					globalTaskListService.claimTask(key);
					SwingUtilities.invokeLater(GlobalTaskListPanel.this::refresh);
					return;
				}

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
			claimBtn.addActionListener(e -> {
				playSound(LeagueScapeSounds.TASK_COMPLETE);
				globalTaskListService.claimTask(taskKey);
				detail.dispose();
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
			claimBtn.addActionListener(e -> {
				playSound(LeagueScapeSounds.TASK_COMPLETE);
				globalTaskListService.setCompleted(taskKey);
				globalTaskListService.claimTask(taskKey);
				detail.dispose();
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

	private static BufferedImage loadRawLocalIcon(String taskType, String displayName)
	{
		String path = getLocalIconPath(taskType, displayName);
		if (path == null) return null;
		return rawTaskIconCache.computeIfAbsent(path, p -> ImageUtil.loadImageResource(LeagueScapePlugin.class, p));
	}

	private static String getLocalIconPath(String taskType, String displayName)
	{
		if (isCollectionLogTask(taskType, displayName))
			return TASK_ICONS_RESOURCE_PREFIX + "Collection_log_detail.png";
		if (isClueTask(taskType, displayName))
			return TASK_ICONS_RESOURCE_PREFIX + "Clue_scroll_v1.png";
		if (taskType != null && TASK_TYPE_LOCAL_ICON.containsKey(taskType))
			return TASK_ICONS_RESOURCE_PREFIX + TASK_TYPE_LOCAL_ICON.get(taskType);
		return null;
	}

	private static boolean isCollectionLogTask(String taskType, String displayName)
	{
		if (taskType != null && taskType.toLowerCase().contains("collection")) return true;
		return displayName != null && displayName.toLowerCase().contains("collection log");
	}

	private static boolean isClueTask(String taskType, String displayName)
	{
		if (taskType != null && ("Clue".equalsIgnoreCase(taskType) || taskType.toLowerCase().contains("clue"))) return true;
		return displayName != null && displayName.toLowerCase().contains("clue scroll");
	}

	private static boolean isIconMatchCombatSize(String taskType, String displayName)
	{
		if (taskType != null)
		{
			if ("Quest".equalsIgnoreCase(taskType)) return true;
			if ("Achievement Diary".equalsIgnoreCase(taskType) || "Achievement diary".equalsIgnoreCase(taskType) || "Diary".equalsIgnoreCase(taskType)) return true;
		}
		return isCollectionLogTask(taskType, displayName);
	}

	private static BufferedImage scaleToFitAllowUpscale(BufferedImage src, int maxW, int maxH)
	{
		if (src == null || maxW <= 0 || maxH <= 0) return null;
		int w = src.getWidth(), h = src.getHeight();
		if (w <= 0 || h <= 0) return null;
		double scale = Math.min((double) maxW / w, (double) maxH / h);
		int nw = Math.max(1, (int) Math.round(w * scale));
		int nh = Math.max(1, (int) Math.round(h * scale));
		return (nw == w && nh == h) ? src : ImageUtil.resizeImage(src, nw, nh);
	}

	private static BufferedImage scaleToLargestDimension(BufferedImage src, int targetMaxDimension)
	{
		if (src == null || targetMaxDimension <= 0) return null;
		int w = src.getWidth(), h = src.getHeight();
		if (w <= 0 || h <= 0) return null;
		int maxDim = Math.max(w, h);
		if (maxDim <= 0) return null;
		double scale = (double) targetMaxDimension / maxDim;
		int nw = Math.max(1, (int) Math.round(w * scale));
		int nh = Math.max(1, (int) Math.round(h * scale));
		return (nw == w && nh == h) ? src : ImageUtil.resizeImage(src, nw, nh);
	}

	private static BufferedImage loadDefaultTaskIcon()
	{
		BufferedImage img = ImageUtil.loadImageResource(LeagueScapePlugin.class, "complete_checkmark.png");
		if (img != null)
		{
			int maxW = 28, maxH = 28;
			int w = img.getWidth(), h = img.getHeight();
			double scale = Math.min((double) maxW / w, (double) maxH / h);
			scale = Math.min(scale, 1.0);
			int nw = Math.max(1, (int) Math.round(w * scale));
			int nh = Math.max(1, (int) Math.round(h * scale));
			return (nw == w && nh == h) ? img : ImageUtil.resizeImage(img, nw, nh);
		}
		return null;
	}

	// --- UI helpers ---

	private void playSound(String sound)
	{
		if (audioPlayer != null && client != null)
			LeagueScapeSounds.play(audioPlayer, sound, client);
	}

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
				else super.paintComponent(g);
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
			b.setIcon(new ImageIcon(ImageUtil.resizeImage(iconImg, 24, 24)));
		else
		{
			b.setText("X");
			b.setForeground(fallbackTextColor);
		}
		return b;
	}
}

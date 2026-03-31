package com.gridscape.worldunlock;

import com.gridscape.GridScapePlugin;
import com.gridscape.GridScapeSounds;
import com.gridscape.grid.GridPos;
import com.gridscape.util.FogTileCompositor;
import com.gridscape.util.FrontierFogHelpers;
import com.gridscape.util.GridClaimFocusAnimation;
import com.gridscape.util.RingBonusPopup;
import com.gridscape.icons.IconCache;
import com.gridscape.icons.IconResolver;
import com.gridscape.icons.IconResources;
import com.gridscape.points.PointsService;
import com.gridscape.task.TaskState;
import com.gridscape.task.TaskTile;
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
import javax.swing.JViewport;
import java.awt.RenderingHints;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import net.runelite.api.Client;
import net.runelite.client.audio.AudioPlayer;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.util.ImageUtil;
import com.gridscape.util.GridScapeFrameChromePanel;
import com.gridscape.util.GridScapeSwingUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Global task grid panel for World Unlock mode. Visually identical to the per-area Task Grid:
 * square icon tiles, spiral tier distribution, detail popups on click, zoom, drag-scroll.
 */
public class GlobalTaskListPanel extends JPanel
{
	private static final Logger log = LoggerFactory.getLogger(GlobalTaskListPanel.class);
	private static final Color POPUP_BG = com.gridscape.util.GridScapeColors.POPUP_BG;
	private static final Color POPUP_TEXT = com.gridscape.util.GridScapeColors.POPUP_TEXT;
	private static final Color POPUP_BORDER = new Color(0x2a, 0x28, 0x24);
	private static final Dimension RECTANGLE_BUTTON_SIZE = new Dimension(160, 28);
	private static final int BASE_TILE_SIZE = 72;
	private static final int TASK_TILE_ICON_MARGIN = 12;
	private static final int CLAIMED_CHECKMARK_SIZE = 18;
	private static final int CLAIMED_CHECKMARK_INSET = 4;
	/** Extra width beyond one-third of the task panel so the hub header buttons fit comfortably. */
	private static final int TASK_HUB_WIDTH_BONUS_PX = 8;

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
	/** Base fill for frontier fog cells; not {@link #tileBg} (revealed task button art). */
	private BufferedImage fogTileBg;
	private BufferedImage fogTopLeft;
	private BufferedImage fogTopRight;
	private BufferedImage fogBottomLeft;
	private BufferedImage fogBottomRight;
	private JLabel pointsLabel;
	private JPanel gridPanel;
	private JScrollPane scrollPane;
	private float zoom = 1.0f;
	/** Minimum zoom (furthest out); below {@link #ZOOM_INTERACTIVE_MIN} tile clicks zoom in and center before acting. */
	private static final float ZOOM_EXTREME_MIN = 0.01f;
	/** Minimum zoom at which tile actions run immediately (matches previous global panel floor). */
	private static final float ZOOM_INTERACTIVE_MIN = 0.1f;
	private static final float ZOOM_MAX = 2.0f;
	private static final float ZOOM_STEP = 0.1f;

	private int layoutSeed;

	/** Brief focus highlight from task hub / frontier / search jump. */
	private Integer highlightRow;
	private Integer highlightCol;
	/** Two pulse flashes after hub focus (ms). */
	private static final int FOCUS_FLASH_ON_MS = 280;
	private static final int FOCUS_FLASH_GAP_MS = 200;
	private Timer highlightFlashTimer;
	/**
	 * While non-null, {@link #refresh()} keeps the viewport centered on this cell (smooth zoom + pan, same as claim).
	 * Cleared when {@link GridClaimFocusAnimation#animateZoomToClaim} completes.
	 */
	private Integer focusLockRow;
	private Integer focusLockCol;
	private GlobalTaskHub taskHubPanel;
	/** Undecorated extension window docked to the left of {@link #parentDialog}; does not change the main panel size. */
	private JDialog taskHubDialog;
	private boolean taskHubPositionListenerInstalled;
	private boolean taskHubSidebarVisible = true;
	/** Debounces hub list refresh after bookmark/claim so zoom/pan {@link #refresh()} never rebuilds the hub table. */
	private Timer hubDataReloadTimer;

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

		padlockImg = ImageUtil.loadImageResource(GridScapePlugin.class, "padlock_icon.png");
		checkmarkImg = ImageUtil.loadImageResource(GridScapePlugin.class, "complete_checkmark.png");
		tileBg = ImageUtil.loadImageResource(GridScapePlugin.class, "empty_button_square.png");
		interfaceBg = ImageUtil.loadImageResource(GridScapePlugin.class, "interface_template.png");
		buttonRect = ImageUtil.loadImageResource(GridScapePlugin.class, "empty_button_rectangle.png");
		xBtnImg = ImageUtil.loadImageResource(GridScapePlugin.class, "x_button.png");
		defaultTaskIcon = loadDefaultTaskIcon();
		fogTileBg = ImageUtil.loadImageResource(GridScapePlugin.class, "/com/gridscape/fog_tile_base.png");
		fogTopLeft = ImageUtil.loadImageResource(GridScapePlugin.class, "/com/gridscape/fog_tile_corner_top_left.png");
		fogTopRight = ImageUtil.loadImageResource(GridScapePlugin.class, "/com/gridscape/fog_tile_corner_top_right.png");
		fogBottomLeft = ImageUtil.loadImageResource(GridScapePlugin.class, "/com/gridscape/fog_tile_corner_bottom_left.png");
		fogBottomRight = ImageUtil.loadImageResource(GridScapePlugin.class, "/com/gridscape/fog_tile_corner_bottom_right.png");

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
			playSound(GridScapeSounds.BUTTON_PRESS);
			if (onClose != null) onClose.run();
		});
		titleRow.add(closeBtn, BorderLayout.EAST);
		header.add(titleRow, BorderLayout.NORTH);
		GridScapeSwingUtil.installUndecoratedWindowDrag(parentDialog, titleRow);
		add(header, BorderLayout.NORTH);

		gridPanel = new JPanel();
		gridPanel.setLayout(new GridBagLayout());
		gridPanel.setOpaque(false);

		scrollPane = new JScrollPane(gridPanel);
		scrollPane.setOpaque(false);
		scrollPane.getViewport().setOpaque(false);
		scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
		scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
		scrollPane.setPreferredSize(WorldUnlockUiDimensions.GRID_SCROLL_PREFERRED);
		scrollPane.setMinimumSize(WorldUnlockUiDimensions.GRID_SCROLL_MINIMUM);
		scrollPane.setBorder(null);

		scrollPane.getViewport().addMouseWheelListener(e -> {
			float prev = zoom;
			if (e.getWheelRotation() < 0)
				zoom = Math.min(ZOOM_MAX, zoom + ZOOM_STEP);
			else
				zoom = Math.max(ZOOM_EXTREME_MIN, zoom - ZOOM_STEP);
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
			playSound(GridScapeSounds.BUTTON_PRESS);
			if (onClose != null) onClose.run();
			if (onOpenWorldUnlock != null) onOpenWorldUnlock.run();
		});
		westButtons.add(worldUnlockBtn);
		JButton rulesSetupBtn = newRectangleButton("Rules & Setup", buttonRect, POPUP_TEXT);
		rulesSetupBtn.addActionListener(e -> {
			playSound(GridScapeSounds.BUTTON_PRESS);
			if (onOpenRulesSetup != null) onOpenRulesSetup.run();
		});
		westButtons.add(rulesSetupBtn);
		JButton taskHubBtn = newRectangleButton("Hide task list", buttonRect, POPUP_TEXT);
		taskHubBtn.setToolTipText("Show or hide the task list sidebar");
		taskHubBtn.addActionListener(e -> toggleTaskHubSidebar(taskHubBtn));
		westButtons.add(taskHubBtn);
		southPanel.add(westButtons, BorderLayout.WEST);
		JPanel zoomPanel = new JPanel(new FlowLayout(FlowLayout.TRAILING, 4, 0));
		zoomPanel.setOpaque(false);
		JButton zoomOutBtn = newRectangleButton("\u2212", tileBg, POPUP_TEXT);
		zoomOutBtn.setPreferredSize(new Dimension(28, 28));
		zoomOutBtn.setToolTipText("Zoom out");
		zoomOutBtn.addActionListener(e -> { zoom = Math.max(ZOOM_EXTREME_MIN, zoom - ZOOM_STEP); SwingUtilities.invokeLater(this::refresh); });
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
	public Dimension getPreferredSize()
	{
		return new Dimension(WorldUnlockUiDimensions.PANEL_PREFERRED);
	}

	@Override
	public Dimension getMinimumSize()
	{
		return new Dimension(WorldUnlockUiDimensions.PANEL_PREFERRED);
	}

	@Override
	public Dimension getMaximumSize()
	{
		return new Dimension(WorldUnlockUiDimensions.PANEL_PREFERRED);
	}

	/**
	 * Builds the task hub once in a separate undecorated dialog (1/3 of this panel's width), docked to the left.
	 * The global task dialog size is unchanged; the hub is an extension window.
	 */
	public GlobalTaskHub attachTaskHub()
	{
		if (taskHubPanel != null)
			return taskHubPanel;
		BufferedImage hubMenuBtn = ImageUtil.loadImageResource(GridScapePlugin.class, "menu_button.png");
		BufferedImage hubSearchBtn = ImageUtil.loadImageResource(GridScapePlugin.class, "search_button.png");
		BufferedImage hubSortBtn = ImageUtil.loadImageResource(GridScapePlugin.class, "sort_button.png");
		if (hubMenuBtn == null)
			hubMenuBtn = tileBg;
		if (hubSearchBtn == null)
			hubSearchBtn = buttonRect;
		if (hubSortBtn == null)
			hubSortBtn = tileBg;
		taskHubPanel = new GlobalTaskHub(globalTaskListService, layoutSeed,
			this::focusTile,
			() -> playSound(GridScapeSounds.BUTTON_PRESS),
			parentDialog != null ? parentDialog : client.getCanvas(),
			this::scheduleHubDataReload,
			hubMenuBtn,
			hubSearchBtn,
			hubSortBtn,
			buttonRect,
			defaultTaskIcon);

		BufferedImage fill = ImageUtil.loadImageResource(GridScapePlugin.class, "fill_color.png");
		BufferedImage bTop = ImageUtil.loadImageResource(GridScapePlugin.class, "border_top.png");
		BufferedImage bBottom = ImageUtil.loadImageResource(GridScapePlugin.class, "border_bottom.png");
		BufferedImage bLeft = ImageUtil.loadImageResource(GridScapePlugin.class, "border_left.png");
		BufferedImage bRight = ImageUtil.loadImageResource(GridScapePlugin.class, "border_right.png");
		BufferedImage cTopLeft = ImageUtil.loadImageResource(GridScapePlugin.class, "top_left_corner.png");
		BufferedImage cTopRight = ImageUtil.loadImageResource(GridScapePlugin.class, "top_right_corner.png");
		BufferedImage cBottomLeft = ImageUtil.loadImageResource(GridScapePlugin.class, "bottom_left_corner.png");
		BufferedImage cBottomRight = ImageUtil.loadImageResource(GridScapePlugin.class, "bottom_right_corner.png");

		GridScapeFrameChromePanel chrome = new GridScapeFrameChromePanel(
			fill, cTopLeft, cTopRight, cBottomLeft, cBottomRight, bTop, bBottom, bLeft, bRight);
		chrome.setLayout(new BorderLayout(0, 0));
		JPanel hubInner = new JPanel(new BorderLayout(0, 0));
		hubInner.setOpaque(false);
		hubInner.setBorder(new EmptyBorder(chrome.getChromeInsets()));
		hubInner.add(taskHubPanel, BorderLayout.CENTER);
		chrome.add(hubInner, BorderLayout.CENTER);

		taskHubDialog = new JDialog(parentDialog, false);
		taskHubDialog.setUndecorated(true);
		taskHubDialog.setContentPane(chrome);
		taskHubDialog.setAlwaysOnTop(false);

		installTaskHubWindowListeners();

		taskHubDialog.setVisible(false);
		return taskHubPanel;
	}

	/** Call after the global tasks dialog is displayable so hub width/height match the host window. */
	public void syncTaskHubVisibilityAndPosition()
	{
		if (taskHubDialog == null)
			return;
		positionTaskHubDialog();
		taskHubDialog.setVisible(taskHubSidebarVisible);
	}

	/** Sizes and places the hub extension to the left of the global tasks dialog (1/3 of this panel's width plus {@link #TASK_HUB_WIDTH_BONUS_PX}, same height). */
	public void positionTaskHubDialog()
	{
		if (taskHubDialog == null || parentDialog == null)
			return;
		if (!parentDialog.isDisplayable())
			return;
		// Width from this task panel only — not combined with any other window — so hub width tracks panel size.
		int panelW = getWidth();
		if (panelW <= 0)
			panelW = parentDialog.getWidth();
		int ph = parentDialog.getHeight();
		int hubW = Math.max(64, (int) Math.round(panelW / 3.0)) + TASK_HUB_WIDTH_BONUS_PX;
		taskHubDialog.setSize(hubW, ph);
		taskHubDialog.setLocation(parentDialog.getX() - hubW, parentDialog.getY());
	}

	private void installTaskHubWindowListeners()
	{
		if (parentDialog == null || taskHubPositionListenerInstalled)
			return;
		taskHubPositionListenerInstalled = true;
		parentDialog.addComponentListener(new ComponentAdapter()
		{
			@Override
			public void componentMoved(ComponentEvent e)
			{
				positionTaskHubDialog();
			}

			@Override
			public void componentResized(ComponentEvent e)
			{
				positionTaskHubDialog();
			}
		});
		parentDialog.addWindowListener(new WindowAdapter()
		{
			@Override
			public void windowClosed(WindowEvent e)
			{
				if (taskHubDialog != null)
				{
					taskHubDialog.dispose();
					taskHubDialog = null;
				}
			}
		});
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

		int tileSize = Math.max(24, (int) (BASE_TILE_SIZE * zoom));
		int iconMargin = Math.max(1, (tileSize * TASK_TILE_ICON_MARGIN) / BASE_TILE_SIZE);
		int iconMaxFit = Math.max(1, tileSize - 2 * iconMargin);

		BufferedImage combatRaw = loadRawLocalIcon("Combat", null, null);
		BufferedImage combatScaled = combatRaw != null ? scaleToFitAllowUpscale(combatRaw, iconMaxFit, iconMaxFit) : null;
		int refSize = (combatScaled != null) ? Math.max(combatScaled.getWidth(), combatScaled.getHeight()) : iconMaxFit;

		final List<TaskTile> gridFinal = grid;
		Set<String> revealedPosSet = globalTaskListService.getRevealedPositionSet();
		Set<String> fogPositions = new HashSet<>();
		for (TaskTile t : gridFinal)
		{
			TaskState st = globalTaskListService.getGlobalState(t.getId(), gridFinal);
			if (!FrontierFogHelpers.isRevealedUnclaimedTaskState(st)) continue;
			int r = t.getRow(), c = t.getCol();
			int[][] deltas = { { -1, 0 }, { 0, 1 }, { 1, 0 }, { 0, -1 } };
			for (int[] d : deltas)
			{
				String nid = TaskTile.idFor(r + d[0], c + d[1]);
				if (!revealedPosSet.contains(nid))
					fogPositions.add(nid);
			}
		}
		int maxRing = grid.stream()
			.mapToInt(t -> Math.max(Math.abs(t.getRow()), Math.abs(t.getCol())))
			.max().orElse(5);
		for (String fp : fogPositions)
		{
			int[] rc = GridPos.parse(fp);
			if (rc != null)
				maxRing = Math.max(maxRing, Math.max(Math.abs(rc[0]), Math.abs(rc[1])));
		}

		int displayedCount = 0;
		// Iterate all tiles in grid; skip LOCKED (not revealed)
		for (TaskTile tile : grid)
		{
			TaskState state = globalTaskListService.getGlobalState(tile.getId(), gridFinal);
			if (state == TaskState.LOCKED) continue;

			displayedCount++;
			boolean isCenter = (tile.getRow() == 0 && tile.getCol() == 0);

			BufferedImage taskIcon = null;
			if (!isCenter)
			{
				String cacheKey = tile.getTaskType() != null ? ("type:" + tile.getTaskType()) : tile.getDisplayName();
				if (tile.getBossId() != null && !tile.getBossId().isEmpty()
					&& !com.gridscape.constants.TaskTypes.isCollectionLogType(tile.getTaskType()))
					cacheKey = "boss:" + tile.getBossId();
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

		for (String fp : fogPositions)
		{
			int[] rc = GridPos.parse(fp);
			if (rc == null) continue;
			displayedCount++;
			int gx = rc[1] + maxRing;
			int gy = maxRing - rc[0];
			GridBagConstraints gbc = new GridBagConstraints();
			gbc.gridx = gx;
			gbc.gridy = gy;
			gbc.insets = new Insets(2, 2, 2, 2);
			gridPanel.add(buildFogCell(rc[0], rc[1], gridFinal, tileSize), gbc);
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
			final int focusRow;
			final int focusCol;
			if (focusLockRow != null && focusLockCol != null)
			{
				focusRow = focusLockRow;
				focusCol = focusLockCol;
			}
			else
			{
				int[] last = globalTaskListService.loadLastViewedPosition();
				focusRow = (last != null && last.length >= 2) ? last[0] : 0;
				focusCol = (last != null && last.length >= 2) ? last[1] : 0;
			}
			final int fmaxRing = maxRing;
			final int ftileSize = tileSize;
			final int fpad = 2 * 2;
			/* Defer one extra frame so GridBagLayout has assigned non-zero cell bounds before centering. */
			SwingUtilities.invokeLater(() -> SwingUtilities.invokeLater(() -> {
				JViewport vp = scrollPane.getViewport();
				if (vp == null)
					return;
				scrollPane.validate();
				Point p = GridClaimFocusAnimation.computeViewPositionForTile(
					vp, gridPanel, focusRow, focusCol, fmaxRing, ftileSize, fpad);
				vp.setViewPosition(p);
			}));
		}
		else
		{
			focusLockRow = null;
			focusLockCol = null;
		}
	}

	/** Schedules a single hub table rebuild (not tied to grid zoom/pan refresh). */
	private void scheduleHubDataReload()
	{
		if (taskHubPanel == null || taskHubDialog == null || !taskHubDialog.isVisible())
			return;
		if (hubDataReloadTimer != null)
			hubDataReloadTimer.stop();
		hubDataReloadTimer = new Timer(280, e -> {
			hubDataReloadTimer.stop();
			if (taskHubPanel != null && taskHubDialog != null && taskHubDialog.isVisible())
				taskHubPanel.reloadFromService();
		});
		hubDataReloadTimer.setRepeats(false);
		hubDataReloadTimer.start();
	}

	private void toggleTaskHubSidebar(JButton taskHubBtn)
	{
		if (taskHubPanel == null || taskHubDialog == null)
			return;
		playSound(GridScapeSounds.BUTTON_PRESS);
		taskHubSidebarVisible = !taskHubSidebarVisible;
		if (taskHubSidebarVisible)
		{
			positionTaskHubDialog();
			taskHubPanel.reloadFromService();
		}
		taskHubDialog.setVisible(taskHubSidebarVisible);
		taskHubBtn.setText(taskHubSidebarVisible ? "Hide task list" : "Show task list");
		revalidate();
		repaint();
	}

	/**
	 * Same smooth zoom + pan as {@link #startClaimFocusAnimation(int, int)}, then two additional highlight flashes
	 * after the transition. Used by the task hub.
	 */
	public void focusTile(int row, int col)
	{
		if (highlightFlashTimer != null)
			highlightFlashTimer.stop();
		globalTaskListService.saveLastViewedPosition(row, col);
		focusLockRow = row;
		focusLockCol = col;
		highlightRow = row;
		highlightCol = col;
		float zs = zoom;
		float ze = 1.0f;
		GridClaimFocusAnimation.animateZoomToClaim(zs, ze, ZOOM_EXTREME_MIN, ZOOM_MAX, z -> zoom = z, this::refresh, () -> {
			focusLockRow = null;
			focusLockCol = null;
			scheduleHubDataReload();
			startFocusHighlightFlashes(row, col);
		});
		SwingUtilities.invokeLater(() -> {
			if (parentDialog != null)
				parentDialog.toFront();
			if (scrollPane != null)
			{
				scrollPane.setFocusable(true);
				scrollPane.requestFocusInWindow();
			}
		});
	}

	/**
	 * After hub focus zoom completes: two additional flashes (off → on → off → on → off) on top of the highlight
	 * already shown during the zoom.
	 */
	private void startFocusHighlightFlashes(final int row, final int col)
	{
		if (highlightFlashTimer != null)
			highlightFlashTimer.stop();
		final int[] phase = { 0 };
		highlightFlashTimer = new Timer(FOCUS_FLASH_ON_MS, null);
		highlightFlashTimer.addActionListener(e -> {
			Timer tm = (Timer) e.getSource();
			switch (phase[0])
			{
				case 0:
					highlightRow = null;
					highlightCol = null;
					refresh();
					phase[0] = 1;
					tm.setDelay(FOCUS_FLASH_GAP_MS);
					break;
				case 1:
					highlightRow = row;
					highlightCol = col;
					refresh();
					phase[0] = 2;
					tm.setDelay(FOCUS_FLASH_ON_MS);
					break;
				case 2:
					highlightRow = null;
					highlightCol = null;
					refresh();
					phase[0] = 3;
					tm.setDelay(FOCUS_FLASH_GAP_MS);
					break;
				case 3:
					highlightRow = row;
					highlightCol = col;
					refresh();
					phase[0] = 4;
					tm.setDelay(FOCUS_FLASH_ON_MS);
					break;
				case 4:
					highlightRow = null;
					highlightCol = null;
					refresh();
					tm.stop();
					highlightFlashTimer = null;
					break;
				default:
					tm.stop();
					highlightFlashTimer = null;
					break;
			}
		});
		highlightFlashTimer.setRepeats(true);
		highlightFlashTimer.start();
	}

	/** Opens the same task detail popup as a grid click (for the task hub). */
	public void openTaskDetail(TaskTile tile, TaskState state)
	{
		showTaskDetailPopup(tile, state);
	}

	private boolean isHighlightCell(int row, int col)
	{
		return highlightRow != null && highlightCol != null
			&& highlightRow == row && highlightCol == col;
	}

	private void attachBookmarkPopup(JPanel cell, TaskTile tile)
	{
		cell.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				maybeShow(e);
			}

			@Override
			public void mouseReleased(MouseEvent e)
			{
				maybeShow(e);
			}

			private void maybeShow(MouseEvent e)
			{
				if (!e.isPopupTrigger()) return;
				int r = tile.getRow(), c = tile.getCol();
				boolean bookmarked = globalTaskListService.isTaskHubBookmarked(r, c);
				JPopupMenu menu = new JPopupMenu();
				JMenuItem item = new JMenuItem(bookmarked ? "Remove bookmark" : "Add bookmark…");
				item.addActionListener(ev -> {
					playSound(GridScapeSounds.BUTTON_PRESS);
					if (bookmarked)
						globalTaskListService.removeTaskHubBookmark(r, c);
					else
					{
						String def = tile.getDisplayName();
						String label = JOptionPane.showInputDialog(parentDialog, "Bookmark label (optional):", def);
						if (label == null) return;
						String lk = label.trim().isEmpty() ? def : label.trim();
						globalTaskListService.addTaskHubBookmark(new GlobalTaskBookmark(
							GlobalTaskListService.taskKeyFromName(tile.getDisplayName()), r, c, lk));
					}
					SwingUtilities.invokeLater(() -> {
						refresh();
						scheduleHubDataReload();
					});
				});
				menu.add(item);
				menu.show(e.getComponent(), e.getX(), e.getY());
			}
		});
	}

	private JPanel buildTaskCell(TaskTile tile, TaskState state, BufferedImage taskIcon,
		int tileSize, int iconMargin, boolean isCenter, List<TaskTile> grid)
	{
		if (state == TaskState.CLAIMED)
		{
			return buildClaimedCell(tile, tileSize, isCenter);
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
		if (isHighlightCell(tile.getRow(), tile.getCol()))
			cell.setBorder(new LineBorder(new Color(255, 235, 140), 2));
		attachBookmarkPopup(cell, tile);

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
				handleTilePrimaryClick(tile, state, isCenter);
			}
		});
		cell.setCursor(new java.awt.Cursor(java.awt.Cursor.HAND_CURSOR));
		GridClaimFocusAnimation.putGridCellKeys(cell, tile.getRow(), tile.getCol());
		return cell;
	}

	/** Frontier fog for positions not yet revealed; non-interactive. Edge art toward revealed-unclaimed neighbors. */
	private JPanel buildFogCell(int row, int col, List<TaskTile> grid, int tileSize)
	{
		final BufferedImage bg = fogTileBg;
		final BufferedImage ftl = fogTopLeft;
		final BufferedImage ftr = fogTopRight;
		final BufferedImage fbl = fogBottomLeft;
		final BufferedImage fbr = fogBottomRight;
		Map<String, TaskTile> idMap = FrontierFogHelpers.idMap(grid);
		boolean[] f = FrontierFogHelpers.cardinalFlagsForHiddenCell(row, col,
			nid -> globalTaskListService.getGlobalState(nid, grid),
			nid -> idMap.containsKey(nid));
		JPanel cell = new JPanel()
		{
			@Override
			protected void paintComponent(Graphics g)
			{
				super.paintComponent(g);
				if (bg != null)
					g.drawImage(bg.getScaledInstance(getWidth(), getHeight(), Image.SCALE_SMOOTH), 0, 0, null);
				else
				{
					g.setColor(new Color(60, 55, 50));
					g.fillRect(0, 0, getWidth(), getHeight());
				}
				FogTileCompositor.paintFogQuadrants(g, getWidth(), getHeight(), f[0], f[1], f[2], f[3], ftl, ftr, fbl, fbr);
			}
		};
		cell.setOpaque(false);
		cell.setPreferredSize(new Dimension(tileSize, tileSize));
		cell.setCursor(new java.awt.Cursor(java.awt.Cursor.DEFAULT_CURSOR));
		GridClaimFocusAnimation.putGridCellKeys(cell, row, col);
		return cell;
	}

	/**
	 * When zoomed out past {@link #ZOOM_INTERACTIVE_MIN}, centers on the tile and zooms in first;
	 * then the same click is applied (after layout). Sounds play only on the final interactive pass.
	 */
	private void handleTilePrimaryClick(TaskTile tile, TaskState state, boolean isCenter)
	{
		if (zoom < ZOOM_INTERACTIVE_MIN)
		{
			zoom = ZOOM_INTERACTIVE_MIN;
			globalTaskListService.saveLastViewedPosition(tile.getRow(), tile.getCol());
			refresh();
			SwingUtilities.invokeLater(() -> SwingUtilities.invokeLater(() -> handleTilePrimaryClick(tile, state, isCenter)));
			return;
		}

		if (isCenter)
		{
			if (!globalTaskListService.isStarterAreaUnlockedOnGrid())
			{
				GridScapeSounds.play(audioPlayer, GridScapeSounds.LOCKED, client);
				JOptionPane.showMessageDialog(parentDialog, "Unlock the starter area on the World Unlock grid first.");
				return;
			}
			playSound(GridScapeSounds.TASK_COMPLETE);
			globalTaskListService.claimCenter();
			startClaimFocusAnimation(0, 0);
			return;
		}

		if (state == TaskState.COMPLETED_UNCLAIMED)
		{
			if (!globalTaskListService.isStarterAreaUnlockedOnGrid())
			{
				GridScapeSounds.play(audioPlayer, GridScapeSounds.LOCKED, client);
				JOptionPane.showMessageDialog(parentDialog, "Unlock the starter area on the World Unlock grid first.");
				return;
			}
			String key = GlobalTaskListService.taskKeyFromName(tile.getDisplayName());
			playSound(GridScapeSounds.TASK_COMPLETE);
			int ringBonus = globalTaskListService.claimTask(key, tile.getRow(), tile.getCol());
			showRingBonusPopupIfNeeded(ringBonus, tile.getRow(), tile.getCol());
			startClaimFocusAnimation(tile.getRow(), tile.getCol());
			return;
		}

		playSound(GridScapeSounds.BUTTON_PRESS);
		showTaskDetailPopup(tile, state);
	}

	/** After a claim, smooth zoom toward 1.0 and keep the viewport centered on the tile (same as task hub focus). */
	private void startClaimFocusAnimation(int row, int col)
	{
		globalTaskListService.saveLastViewedPosition(row, col);
		focusLockRow = row;
		focusLockCol = col;
		float zs = zoom;
		float ze = 1.0f;
		GridClaimFocusAnimation.animateZoomToClaim(zs, ze, ZOOM_EXTREME_MIN, ZOOM_MAX, z -> zoom = z, this::refresh, () -> {
			focusLockRow = null;
			focusLockCol = null;
			scheduleHubDataReload();
		});
	}

	private JPanel buildClaimedCell(TaskTile tile, int tileSize, boolean isCenter)
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
		if (isHighlightCell(tile.getRow(), tile.getCol()))
			cell.setBorder(new LineBorder(new Color(255, 235, 140), 2));
		attachBookmarkPopup(cell, tile);
		GridClaimFocusAnimation.putGridCellKeys(cell, tile.getRow(), tile.getCol());
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
		GridScapePlugin.registerEscapeToClose(detail);

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
		closeBtn.addActionListener(e -> { playSound(GridScapeSounds.BUTTON_PRESS); detail.dispose(); });
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
				playSound(GridScapeSounds.TASK_COMPLETE);
				int ringBonus = globalTaskListService.claimTask(taskKey, claimRow, claimCol);
				detail.dispose();
				if (ringBonus > 0)
					showRingBonusPopupIfNeeded(ringBonus, claimRow, claimCol);
				startClaimFocusAnimation(claimRow, claimCol);
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
				playSound(GridScapeSounds.TASK_COMPLETE);
				globalTaskListService.setCompleted(taskKey);
				int ringBonus = globalTaskListService.claimTask(taskKey, claimRow, claimCol);
				detail.dispose();
				if (ringBonus > 0)
					showRingBonusPopupIfNeeded(ringBonus, claimRow, claimCol);
				startClaimFocusAnimation(claimRow, claimCol);
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

	// --- Icon helpers (same as GridScapeMapOverlay) ---

	private static BufferedImage loadRawLocalIcon(String taskType, String displayName, String bossId)
	{
		String path = IconResolver.resolveTaskTileLocalIconPath(taskType, displayName, bossId);
		if (path == null) return null;
		return rawTaskIconCache.computeIfAbsent(path, p -> IconCache.loadWithFallback(p, IconResources.GENERIC_TASK_ICON));
	}

	private static boolean isCollectionLogTask(String taskType, String displayName)
	{
		if (com.gridscape.constants.TaskTypes.isCollectionLogType(taskType)) return true;
		return displayName != null && displayName.toLowerCase().contains("collection log");
	}

	private static boolean isIconMatchCombatSize(String taskType, String displayName)
	{
		if (taskType != null)
		{
			if (com.gridscape.constants.TaskTypes.QUEST.equalsIgnoreCase(taskType)) return true;
			if (com.gridscape.constants.TaskTypes.isAchievementDiaryType(taskType)) return true;
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
			GridScapeSounds.play(audioPlayer, sound, client);
	}

	private static JButton newRectangleButton(String text, BufferedImage buttonRect, Color textColor)
	{
		JButton b = com.gridscape.util.GridScapeSwingUtil.newRectangleButton(text, buttonRect, textColor);
		b.setPreferredSize(RECTANGLE_BUTTON_SIZE);
		return b;
	}

	private static JButton newPopupButtonWithIcon(BufferedImage iconImg, Color fallbackTextColor)
	{
		return com.gridscape.util.GridScapeSwingUtil.newPopupButtonWithIcon(iconImg, fallbackTextColor);
	}
}

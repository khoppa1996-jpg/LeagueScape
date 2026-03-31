package com.gridscape.worldunlock;

import com.gridscape.icons.IconCache;
import com.gridscape.icons.IconResolver;
import com.gridscape.icons.IconResources;
import com.gridscape.task.TaskDefinition;
import com.gridscape.task.TaskState;
import com.gridscape.task.TaskTile;
import com.gridscape.util.FrontierFogHelpers;
import com.gridscape.util.GridScapeColors;
import com.gridscape.util.GridScapeSwingUtil;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/**
 * Left sidebar for global tasks: hamburger menu (tier / type / area filters), Search toggles name filter bar,
 * scrollable rectangle tiles (icon + name). List filtering does not affect the grid.
 */
public final class GlobalTaskHub extends JPanel
{
	private static final Color POPUP_BG = GridScapeColors.POPUP_BG;
	private static final Color POPUP_TEXT = GridScapeColors.POPUP_TEXT;
	private static final Color POPUP_BORDER = new Color(0x2a, 0x28, 0x24);
	/** Tight inset around list row icon and text (px). */
	private static final int TASK_TILE_ICON_MARGIN = 1;
	/** Padding inside the hub chrome around header + list (px). */
	private static final int HUB_CONTENT_BUFFER_PX = 1;
	/** Large list rows (mockup-style rectangle tiles). */
	private static final int HUB_TILE_HEIGHT = 52;
	private static final String EMPTY_TYPE = "(none)";
	private static final String EMPTY_AREA = "(none)";

	private static final Map<String, BufferedImage> rawIconCache = new ConcurrentHashMap<>();

	/**
	 * {@link JMenuItem#setHideOnClick(boolean)} exists on the runtime JDK but may be absent from compile-only
	 * classpath stubs; use reflection. When unavailable, {@link #showFiltersMenu} re-opens the popup after each toggle.
	 */
	private static final Method MENU_ITEM_SET_HIDE_ON_CLICK;
	static
	{
		Method m = null;
		try
		{
			m = javax.swing.JMenuItem.class.getMethod("setHideOnClick", boolean.class);
		}
		catch (NoSuchMethodException | SecurityException ignored)
		{
			// JDK 8 API or minimal stubs
		}
		MENU_ITEM_SET_HIDE_ON_CLICK = m;
	}

	private final GlobalTaskListService service;
	private final int layoutSeed;
	private final BiConsumer<Integer, Integer> focusTileOnGrid;
	private final Runnable playSound;
	private final Component dialogParent;
	private final Runnable notifyParentRefresh;
	private final BufferedImage listRowRectangleBg;
	private final BufferedImage headerSquareButtonBg;
	private final BufferedImage defaultTaskIcon;

	private final List<HubRow> allRows = new ArrayList<>();
	private final JPanel tileListPanel;
	private final JTextField searchField = new JTextField();
	private final JPanel searchFieldPanel;
	private final AspectFitImageButton menuButton;
	private final AspectFitImageButton searchToggleButton;
	private boolean searchBarVisible;

	/** Unchecked in the multi-select UI = hide matching list rows. */
	private final Set<Integer> disabledTiers = new HashSet<>();
	private final Set<String> disabledTypes = new HashSet<>();
	private final Set<String> disabledAreas = new HashSet<>();

	private Timer searchDebounce;
	private boolean reloadPosted;

	public GlobalTaskHub(GlobalTaskListService service, int layoutSeed,
		BiConsumer<Integer, Integer> focusTileOnGrid,
		Runnable playSound,
		Component dialogParent,
		Runnable notifyParentRefresh,
		BufferedImage listRowRectangleBg,
		BufferedImage headerSquareButtonBg,
		BufferedImage defaultTaskIcon)
	{
		this.service = service;
		this.layoutSeed = layoutSeed;
		this.focusTileOnGrid = focusTileOnGrid;
		this.playSound = playSound;
		this.dialogParent = dialogParent;
		this.notifyParentRefresh = notifyParentRefresh != null ? notifyParentRefresh : () -> {};
		this.listRowRectangleBg = listRowRectangleBg;
		this.headerSquareButtonBg = headerSquareButtonBg;
		this.defaultTaskIcon = defaultTaskIcon != null ? defaultTaskIcon
			: IconCache.loadWithFallback(IconResources.GENERIC_TASK_ICON,
				IconResources.TASK_ICONS_RESOURCE_PREFIX + "Other_icon.png");

		setLayout(new BorderLayout());
		setBackground(POPUP_BG);
		setOpaque(false);

		menuButton = new AspectFitImageButton("\u2630", headerSquareButtonBg, POPUP_TEXT);
		menuButton.setToolTipText("Filters: tier, type, area");
		menuButton.setPreferredSize(new Dimension(40, 30));
		menuButton.addActionListener(e -> {
			playSound.run();
			showFiltersMenu(menuButton);
		});

		searchToggleButton = new AspectFitImageButton("Search", listRowRectangleBg, POPUP_TEXT);
		searchToggleButton.setToolTipText("Show or hide name search");
		searchToggleButton.addActionListener(e -> toggleSearchBar());

		JPanel headerRow = new JPanel(new BorderLayout(HUB_CONTENT_BUFFER_PX, 0));
		headerRow.setOpaque(false);
		headerRow.add(menuButton, BorderLayout.WEST);
		headerRow.add(searchToggleButton, BorderLayout.CENTER);

		searchField.setBackground(POPUP_BG);
		searchField.setForeground(POPUP_TEXT);
		searchField.setToolTipText("Filter list by task name");
		searchFieldPanel = new JPanel(new BorderLayout(0, 0));
		searchFieldPanel.setOpaque(false);
		searchFieldPanel.setBorder(new EmptyBorder(0, HUB_CONTENT_BUFFER_PX, 0, 0));
		searchFieldPanel.add(searchField, BorderLayout.CENTER);
		searchFieldPanel.setVisible(false);

		JPanel northStack = new JPanel(new BorderLayout(0, HUB_CONTENT_BUFFER_PX));
		northStack.setOpaque(false);
		northStack.add(headerRow, BorderLayout.NORTH);
		northStack.add(searchFieldPanel, BorderLayout.CENTER);

		tileListPanel = new JPanel();
		tileListPanel.setLayout(new BoxLayout(tileListPanel, BoxLayout.Y_AXIS));
		tileListPanel.setOpaque(false);

		JScrollPane scroll = new JScrollPane(tileListPanel);
		scroll.setOpaque(false);
		scroll.getViewport().setOpaque(false);
		scroll.setBorder(null);
		scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.getViewport().addComponentListener(new ComponentAdapter()
		{
			@Override
			public void componentResized(ComponentEvent e)
			{
				tileListPanel.revalidate();
			}
		});

		JPanel inner = new JPanel(new BorderLayout(0, HUB_CONTENT_BUFFER_PX));
		inner.setOpaque(false);
		inner.setBorder(new EmptyBorder(HUB_CONTENT_BUFFER_PX, HUB_CONTENT_BUFFER_PX, HUB_CONTENT_BUFFER_PX, HUB_CONTENT_BUFFER_PX));
		inner.add(northStack, BorderLayout.NORTH);
		inner.add(scroll, BorderLayout.CENTER);
		add(inner, BorderLayout.CENTER);

		searchField.getDocument().addDocumentListener(new DocumentListener()
		{
			private void schedule()
			{
				if (searchDebounce != null)
					searchDebounce.stop();
				searchDebounce = new Timer(200, ev -> {
					searchDebounce.stop();
					SwingUtilities.invokeLater(GlobalTaskHub.this::applyFilters);
				});
				searchDebounce.setRepeats(false);
				searchDebounce.start();
			}

			@Override
			public void insertUpdate(DocumentEvent e)
			{
				schedule();
			}

			@Override
			public void removeUpdate(DocumentEvent e)
			{
				schedule();
			}

			@Override
			public void changedUpdate(DocumentEvent e)
			{
				schedule();
			}
		});

		reloadFromServiceImpl();
	}

	private void toggleSearchBar()
	{
		playSound.run();
		searchBarVisible = !searchBarVisible;
		searchFieldPanel.setVisible(searchBarVisible);
		searchToggleButton.setToolTipText(searchBarVisible ? "Hide name search" : "Show name search");
		if (searchBarVisible)
			searchField.requestFocusInWindow();
		revalidate();
		repaint();
		Container p = getParent();
		if (p != null)
		{
			p.revalidate();
			p.repaint();
		}
	}

	private void showFiltersMenu(Component invoker)
	{
		final JPopupMenu root = new JPopupMenu();
		addFilterSectionHeader(root, "Tier");
		TreeSet<Integer> tiers = new TreeSet<>();
		for (HubRow r : allRows)
			tiers.add(r.difficultyTier);
		if (tiers.isEmpty())
			addFilterEmptyLine(root, "(no tiers)");
		else
		{
			for (int tier : tiers)
			{
				boolean on = !disabledTiers.contains(tier);
				JCheckBoxMenuItem cb = new JCheckBoxMenuItem("Tier " + tier, on);
				final int ft = tier;
				wireFilterCheckbox(root, invoker, cb, () -> {
					if (cb.isSelected())
						disabledTiers.remove(ft);
					else
						disabledTiers.add(ft);
				});
				root.add(cb);
			}
		}
		root.addSeparator();
		addFilterSectionHeader(root, "Type");
		Set<String> types = new LinkedHashSet<>();
		for (HubRow r : allRows)
			types.add(r.typeStr);
		if (types.isEmpty())
			addFilterEmptyLine(root, "(no types)");
		else
		{
			for (String ty : types)
			{
				boolean on = !disabledTypes.contains(ty);
				JCheckBoxMenuItem cb = new JCheckBoxMenuItem(ty, on);
				wireFilterCheckbox(root, invoker, cb, () -> {
					if (cb.isSelected())
						disabledTypes.remove(ty);
					else
						disabledTypes.add(ty);
				});
				root.add(cb);
			}
		}
		root.addSeparator();
		addFilterSectionHeader(root, "Area");
		Set<String> areas = new LinkedHashSet<>();
		for (HubRow r : allRows)
			for (String a : splitAreaTokens(r.areas))
				areas.add(a);
		if (areas.isEmpty())
			addFilterEmptyLine(root, "(no areas)");
		else
		{
			for (String a : areas)
			{
				boolean on = !disabledAreas.contains(a);
				JCheckBoxMenuItem cb = new JCheckBoxMenuItem(a, on);
				wireFilterCheckbox(root, invoker, cb, () -> {
					if (cb.isSelected())
						disabledAreas.remove(a);
					else
						disabledAreas.add(a);
				});
				root.add(cb);
			}
		}
		root.show(invoker, 0, invoker.getHeight());
	}

	private void wireFilterCheckbox(JPopupMenu root, Component invoker, JCheckBoxMenuItem cb, Runnable onToggle)
	{
		applyMenuHideOnClickFalse(cb);
		cb.addActionListener(e -> {
			onToggle.run();
			applyFilters();
			if (MENU_ITEM_SET_HIDE_ON_CLICK == null)
			{
				SwingUtilities.invokeLater(() -> {
					if (invoker.isShowing())
						root.show(invoker, 0, invoker.getHeight());
				});
			}
		});
	}

	private static void applyMenuHideOnClickFalse(JMenuItem item)
	{
		if (MENU_ITEM_SET_HIDE_ON_CLICK == null)
			return;
		try
		{
			MENU_ITEM_SET_HIDE_ON_CLICK.invoke(item, Boolean.FALSE);
		}
		catch (IllegalAccessException | InvocationTargetException ignored)
		{
		}
	}

	private static void addFilterSectionHeader(JPopupMenu menu, String title)
	{
		JMenuItem h = new JMenuItem(title);
		h.setEnabled(false);
		menu.add(h);
	}

	private static void addFilterEmptyLine(JPopupMenu menu, String text)
	{
		JMenuItem empty = new JMenuItem(text);
		empty.setEnabled(false);
		menu.add(empty);
	}

	/**
	 * Rebuilds hub rows from the service. Coalesced to one EDT pass when called repeatedly.
	 */
	public void reloadFromService()
	{
		if (reloadPosted)
			return;
		reloadPosted = true;
		SwingUtilities.invokeLater(() -> {
			reloadPosted = false;
			reloadFromServiceImpl();
		});
	}

	private void reloadFromServiceImpl()
	{
		allRows.clear();
		List<TaskTile> grid = service.buildGlobalGrid(layoutSeed);
		Map<String, TaskDefinition> defByKey = service.buildTaskDefinitionIndex();
		for (TaskTile t : grid)
		{
			TaskState st = service.getGlobalState(t.getId(), grid);
			if (st == TaskState.LOCKED)
				continue;
			if (!FrontierFogHelpers.isRevealedUnclaimedTaskState(st))
				continue;
			TaskDefinition def = defByKey.get(GlobalTaskListService.taskKeyFromName(t.getDisplayName()));
			int diffTier = def != null ? def.getDifficulty() : t.getTier();
			String typeStr = t.getTaskType() != null && !t.getTaskType().isEmpty() ? t.getTaskType() : EMPTY_TYPE;
			String areas = service.formatTaskAreaLabels(t);
			allRows.add(new HubRow(t, diffTier, typeStr, areas));
		}
		mergeFilterStateWithData();
		applyFilters();
	}

	private void mergeFilterStateWithData()
	{
		Set<Integer> presentTiers = new HashSet<>();
		Set<String> presentTypes = new LinkedHashSet<>();
		Set<String> presentAreas = new LinkedHashSet<>();
		for (HubRow r : allRows)
		{
			presentTiers.add(r.difficultyTier);
			presentTypes.add(r.typeStr);
			for (String a : splitAreaTokens(r.areas))
				presentAreas.add(a);
		}
		disabledTiers.removeIf(t -> !presentTiers.contains(t));
		disabledTypes.removeIf(ty -> !presentTypes.contains(ty));
		disabledAreas.removeIf(a -> !presentAreas.contains(a));
	}

	private static List<String> splitAreaTokens(String areas)
	{
		if (areas == null || areas.trim().isEmpty())
			return Collections.singletonList(EMPTY_AREA);
		String[] parts = areas.split(",");
		List<String> out = new ArrayList<>();
		for (String p : parts)
		{
			String s = p.trim();
			if (!s.isEmpty())
				out.add(s);
		}
		return out.isEmpty() ? Collections.singletonList(EMPTY_AREA) : out;
	}

	private void applyFilters()
	{
		String q = searchField.getText().trim().toLowerCase(Locale.ROOT);
		tileListPanel.removeAll();
		for (HubRow r : allRows)
		{
			if (disabledTiers.contains(r.difficultyTier))
				continue;
			if (disabledTypes.contains(r.typeStr))
				continue;
			if (!areaRowVisible(r))
				continue;
			if (!q.isEmpty())
			{
				String name = r.tile.getDisplayName() != null ? r.tile.getDisplayName().toLowerCase(Locale.ROOT) : "";
				if (!name.contains(q))
					continue;
			}
			tileListPanel.add(new HubTaskTilePanel(r));
			tileListPanel.add(Box.createVerticalStrut(HUB_CONTENT_BUFFER_PX));
		}
		tileListPanel.revalidate();
		tileListPanel.repaint();
	}

	private boolean areaRowVisible(HubRow r)
	{
		for (String a : splitAreaTokens(r.areas))
		{
			if (disabledAreas.contains(a))
				return false;
		}
		return true;
	}

	private final class HubTaskTilePanel extends JPanel
	{
		private final HubRow row;

		HubTaskTilePanel(HubRow row)
		{
			this.row = row;
			setLayout(new BorderLayout());
			setOpaque(false);
			setAlignmentX(Component.LEFT_ALIGNMENT);

			addMouseListener(new MouseAdapter()
			{
				@Override
				public void mousePressed(MouseEvent e)
				{
					if (e.isPopupTrigger())
						showBookmarkMenu(e);
				}

				@Override
				public void mouseReleased(MouseEvent e)
				{
					if (e.isPopupTrigger())
						showBookmarkMenu(e);
				}

				@Override
				public void mouseClicked(MouseEvent e)
				{
					if (e.getButton() != MouseEvent.BUTTON1)
						return;
					playSound.run();
					focusTileOnGrid.accept(row.tile.getRow(), row.tile.getCol());
				}
			});
			setCursor(new java.awt.Cursor(java.awt.Cursor.HAND_CURSOR));
		}

		private Font rowFont()
		{
			return getFont().deriveFont(Font.PLAIN, 13f);
		}

		/**
		 * Plank art scaled uniformly to the row width: height = ih * (w/iw). Matches {@link #paintComponent} background.
		 */
		private int plankHeightForRowWidth(int w)
		{
			if (listRowRectangleBg == null)
				return HUB_TILE_HEIGHT;
			int iw = listRowRectangleBg.getWidth();
			int ih = listRowRectangleBg.getHeight();
			if (iw <= 0 || ih <= 0)
				return HUB_TILE_HEIGHT;
			return Math.max(1, (int) Math.round(ih * (double) w / iw));
		}

		/** Icon column = 1/3 of row width (matches hub = 1/3 of task panel); remainder is text. */
		private int iconColumnWidth(int rowWidth)
		{
			if (rowWidth <= 0)
				return 1;
			return Math.max(1, (int) Math.round(rowWidth / 3.0));
		}

		private int computeHeightForWidth(int containerWidth)
		{
			FontMetrics fm = getFontMetrics(rowFont());
			int w = Math.max(40, containerWidth);
			int leftW = iconColumnWidth(w);
			int m = TASK_TILE_ICON_MARGIN;
			int textW = Math.max(8, w - leftW - 2 * m);
			String name = row.tile.getDisplayName() != null ? row.tile.getDisplayName() : "";
			List<String> lines = wrapLines(name, fm, textW);
			int lh = fm.getHeight();
			int textBlockH = lines.size() * lh;
			int plankH = plankHeightForRowWidth(w);
			return Math.max(HUB_TILE_HEIGHT, Math.max(plankH + 2 * m, textBlockH + 2 * m));
		}

		@Override
		public Dimension getPreferredSize()
		{
			Container p = getParent();
			int pw = p != null && p.getWidth() > 0 ? p.getWidth() : 120;
			int h = computeHeightForWidth(pw);
			return new Dimension(pw, h);
		}

		@Override
		public Dimension getMaximumSize()
		{
			Dimension d = getPreferredSize();
			return new Dimension(Integer.MAX_VALUE, d.height);
		}

		private void showBookmarkMenu(MouseEvent e)
		{
			TaskTile tile = row.tile;
			int r = tile.getRow(), c = tile.getCol();
			boolean bookmarked = service.isTaskHubBookmarked(r, c);
			JPopupMenu menu = new JPopupMenu();
			JMenuItem item = new JMenuItem(bookmarked ? "Remove bookmark" : "Add bookmark…");
			item.addActionListener(ev -> {
				playSound.run();
				if (bookmarked)
					service.removeTaskHubBookmark(r, c);
				else
				{
					String def = tile.getDisplayName();
					String label = JOptionPane.showInputDialog(dialogParent, "Bookmark label (optional):", def);
					if (label == null)
						return;
					String lk = label.trim().isEmpty() ? def : label.trim();
					service.addTaskHubBookmark(new GlobalTaskBookmark(
						GlobalTaskListService.taskKeyFromName(tile.getDisplayName()), r, c, lk));
				}
				notifyParentRefresh.run();
			});
			menu.add(item);
			menu.show(e.getComponent(), e.getX(), e.getY());
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			super.paintComponent(g);
			int w = getWidth(), h = getHeight();
			if (w <= 0 || h <= 0)
				return;
			int plankH = plankHeightForRowWidth(w);
			if (listRowRectangleBg != null)
			{
				/* Uniform scale to full row width (same aspect as source art; no non-uniform stretch). */
				drawImageAspectFit(g, listRowRectangleBg, 0, 0, w, plankH);
				if (h > plankH)
				{
					g.setColor(POPUP_BG);
					g.fillRect(0, plankH, w, h - plankH);
				}
			}

			int leftW = iconColumnWidth(w);
			int rightW = w - leftW;
			int m = TASK_TILE_ICON_MARGIN;
			int iconCellW = Math.max(8, leftW - 2 * m);
			int iconCellH = Math.max(8, h - 2 * m);

			BufferedImage icon = scaledIconForTile(row.tile, iconCellW, iconCellH);
			if (icon != null)
			{
				int iw = icon.getWidth(), ih = icon.getHeight();
				int ix = m + (iconCellW - iw) / 2;
				int iy = m + (iconCellH - ih) / 2;
				g.drawImage(icon, ix, iy, null);
			}

			String name = row.tile.getDisplayName() != null ? row.tile.getDisplayName() : "";
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			g2.setColor(POPUP_TEXT);
			g2.setFont(rowFont());
			int textX = leftW + m;
			int textW = Math.max(8, rightW - 2 * m);
			FontMetrics fm = g2.getFontMetrics();
			List<String> lines = wrapLines(name, fm, textW);
			int lh = fm.getHeight();
			int totalTextH = lines.size() * lh;
			int blockTop = m + (h - 2 * m - totalTextH) / 2;
			int y = blockTop + fm.getAscent();
			for (String line : lines)
			{
				int sw = fm.stringWidth(line);
				int tx = textX + Math.max(0, (textW - sw) / 2);
				g2.drawString(line, tx, y);
				y += lh;
			}
			g2.dispose();
		}
	}

	private static List<String> wrapLines(String text, FontMetrics fm, int maxW)
	{
		List<String> out = new ArrayList<>();
		if (text == null || text.isEmpty())
		{
			out.add("");
			return out;
		}
		if (maxW <= 4)
		{
			out.add(text);
			return out;
		}
		String[] words = text.split("\\s+");
		StringBuilder line = new StringBuilder();
		for (String word : words)
		{
			if (word.isEmpty())
				continue;
			String trial = line.length() == 0 ? word : line + " " + word;
			if (fm.stringWidth(trial) <= maxW)
			{
				if (line.length() > 0)
					line.append(' ');
				line.append(word);
				continue;
			}
			if (line.length() > 0)
			{
				out.add(line.toString());
				line = new StringBuilder();
			}
			if (fm.stringWidth(word) <= maxW)
			{
				line.append(word);
				continue;
			}
			int i = 0;
			while (i < word.length())
			{
				int j = i + 1;
				while (j <= word.length() && fm.stringWidth(word.substring(i, j)) <= maxW)
					j++;
				if (j == i + 1)
					j++;
				out.add(word.substring(i, j - 1));
				i = j - 1;
			}
		}
		if (line.length() > 0)
			out.add(line.toString());
		return out.isEmpty() ? Collections.singletonList("") : out;
	}

	private BufferedImage scaledIconForTile(TaskTile tile, int maxW, int maxH)
	{
		BufferedImage raw = loadRawLocalIcon(tile.getTaskType(), tile.getDisplayName(), tile.getBossId());
		if (raw == null)
			raw = defaultTaskIcon;
		if (raw == null)
			return null;
		return IconCache.scaleToFitAllowUpscale(raw, maxW, maxH);
	}

	private static BufferedImage loadRawLocalIcon(String taskType, String displayName, String bossId)
	{
		String path = IconResolver.resolveTaskTileLocalIconPath(taskType, displayName, bossId);
		if (path == null)
			return null;
		return rawIconCache.computeIfAbsent(path, p -> IconCache.loadWithFallback(p, IconResources.GENERIC_TASK_ICON));
	}

	/**
	 * Draws {@code img} scaled uniformly to fit inside the rect (letterboxed), preserving aspect ratio.
	 */
	private static void drawImageAspectFit(Graphics g, BufferedImage img, int rx, int ry, int rw, int rh)
	{
		if (img == null || rw <= 0 || rh <= 0)
			return;
		int iw = img.getWidth(), ih = img.getHeight();
		if (iw <= 0 || ih <= 0)
			return;
		double scale = Math.min((double) rw / iw, (double) rh / ih);
		int dw = Math.max(1, (int) Math.round(iw * scale));
		int dh = Math.max(1, (int) Math.round(ih * scale));
		int ox = rx + (rw - dw) / 2;
		int oy = ry + (rh - dh) / 2;
		g.drawImage(img.getScaledInstance(dw, dh, Image.SCALE_SMOOTH), ox, oy, null);
	}

	/** Header controls: stone art scaled uniformly inside bounds (no stretch). */
	private static final class AspectFitImageButton extends JButton
	{
		private final BufferedImage bg;

		AspectFitImageButton(String text, BufferedImage bg, Color fg)
		{
			super(text);
			this.bg = bg;
			setForeground(fg != null ? fg : POPUP_TEXT);
			setFocusPainted(false);
			setBorderPainted(false);
			setContentAreaFilled(false);
			setOpaque(false);
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			if (bg != null)
				drawImageAspectFit(g, bg, 0, 0, getWidth(), getHeight());
			g.setColor(getForeground());
			g.setFont(getFont());
			FontMetrics fm = g.getFontMetrics();
			String t = getText();
			int x = (getWidth() - fm.stringWidth(t)) / 2;
			int y = (getHeight() + fm.getAscent() - fm.getDescent()) / 2;
			g.drawString(t, x, y);
			if (getModel().isPressed())
			{
				g.setColor(GridScapeSwingUtil.PRESSED_INSET_SHADOW);
				g.fillRect(GridScapeSwingUtil.PRESSED_INSET, GridScapeSwingUtil.PRESSED_INSET,
					getWidth() - 2 * GridScapeSwingUtil.PRESSED_INSET,
					getHeight() - 2 * GridScapeSwingUtil.PRESSED_INSET);
			}
		}
	}

	private static final class HubRow
	{
		final TaskTile tile;
		final int difficultyTier;
		final String typeStr;
		final String areas;

		HubRow(TaskTile tile, int difficultyTier, String typeStr, String areas)
		{
			this.tile = tile;
			this.difficultyTier = difficultyTier;
			this.typeStr = typeStr;
			this.areas = areas;
		}
	}
}

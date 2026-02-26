package com.leaguescape.worldunlock;

import com.leaguescape.LeagueScapePlugin;
import com.leaguescape.LeagueScapeSounds;
import com.leaguescape.points.PointsService;
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
import java.util.Set;
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
import net.runelite.client.util.ImageUtil;

/**
 * World Unlock grid panel. Displays unlock tiles as square icon-only tiles in a spiral grid
 * (tier 1 near center, higher tiers outward). Text appears only in the detail popup.
 */
public class WorldUnlockGridPanel extends JPanel
{
	private static final Color POPUP_BG = new Color(0x54, 0x4D, 0x41);
	private static final Color POPUP_TEXT = new Color(0xC4, 0xB8, 0x96);
	private static final Color POPUP_BORDER = new Color(0x2a, 0x28, 0x24);
	private static final Color PRESSED_INSET_SHADOW = new Color(0, 0, 0, 70);
	private static final int PRESSED_INSET = 2;
	private static final Dimension RECTANGLE_BUTTON_SIZE = new Dimension(160, 28);
	private static final int BASE_TILE_SIZE = 72;
	private static final int TILE_ICON_MARGIN = 12;
	private static final int CLAIMED_CHECKMARK_SIZE = 18;
	private static final int CLAIMED_CHECKMARK_INSET = 4;

	private static final String TASK_ICONS_RESOURCE_PREFIX = "/com/taskIcons/";
	private static final String BOSS_ICONS_RESOURCE_PREFIX = "/com/bossicons/";
	/** Boss unlock tile id -> boss icon filename (e.g. game_icon_barrowschests.png) where id does not match filename. */
	private static final Map<String, String> BOSS_ICON_OVERRIDES = new HashMap<>();
	static
	{
		BOSS_ICON_OVERRIDES.put("barrows", "game_icon_barrowschests.png");
		BOSS_ICON_OVERRIDES.put("dagannoth_kings", "game_icon_dagannothrex.png");
		BOSS_ICON_OVERRIDES.put("calvarion_vetion", "game_icon_calvarion.png");
		BOSS_ICON_OVERRIDES.put("spindel_venenatis", "game_icon_venenatis.png");
		BOSS_ICON_OVERRIDES.put("artio_callisto", "game_icon_callisto.png");
		BOSS_ICON_OVERRIDES.put("crystalline_hunllef", "game_icon_thegauntlet.png");
		BOSS_ICON_OVERRIDES.put("corrupted_hunllef", "game_icon_thecorruptedgauntlet.png");
		BOSS_ICON_OVERRIDES.put("the_mimic", "game_icon_mimic.png");
		BOSS_ICON_OVERRIDES.put("tombs_of_amascut", "game_icon_tombsofamascutexpertmode.png");
		BOSS_ICON_OVERRIDES.put("the_nightmare", "game_icon_nightmare.png");
	}
	private static final Map<String, String> SKILL_ICON_MAP = new HashMap<>();
	static
	{
		SKILL_ICON_MAP.put("Combat", "Combat_icon_(detail).png");
		SKILL_ICON_MAP.put("Mining", "Mining_icon_(detail).png");
		SKILL_ICON_MAP.put("Fishing", "Fishing_icon_(detail).png");
		SKILL_ICON_MAP.put("Cooking", "Cooking_icon_(detail).png");
		SKILL_ICON_MAP.put("Woodcutting", "Woodcutting_icon_(detail).png");
		SKILL_ICON_MAP.put("Prayer", "Prayer_icon_(detail).png");
		SKILL_ICON_MAP.put("Crafting", "Crafting_icon_(detail).png");
		SKILL_ICON_MAP.put("Smithing", "Smithing_icon_(detail).png");
		SKILL_ICON_MAP.put("Fletching", "Fletching_icon_(detail).png");
		SKILL_ICON_MAP.put("Herblore", "Herblore_icon_(detail).png");
		SKILL_ICON_MAP.put("Thieving", "Thieving_icon_(detail).png");
		SKILL_ICON_MAP.put("Agility", "Agility_icon_(detail).png");
		SKILL_ICON_MAP.put("Firemaking", "Firemaking_icon_(detail).png");
		SKILL_ICON_MAP.put("Farming", "Farming_icon_(detail).png");
		SKILL_ICON_MAP.put("Runecraft", "Runecraft_icon_(detail).png");
		SKILL_ICON_MAP.put("Magic", "Magic_icon.png");
		SKILL_ICON_MAP.put("Hunter", "Hunter_icon_(detail).png");
		SKILL_ICON_MAP.put("Construction", "Construction_icon_(detail).png");
		SKILL_ICON_MAP.put("Slayer", "Slayer_icon_(detail).png");
		SKILL_ICON_MAP.put("Sailing", "Sailing_icon_(detail).png");
	}

	private static final Map<String, BufferedImage> iconCache = new ConcurrentHashMap<>();

	private final WorldUnlockService worldUnlockService;
	private final PointsService pointsService;
	private final Runnable onClose;
	private final Runnable onOpenGoals;
	private final Client client;
	private final AudioPlayer audioPlayer;
	private final JDialog parentDialog;

	private BufferedImage padlockImg;
	private BufferedImage checkmarkImg;
	private BufferedImage tileBg;
	private BufferedImage interfaceBg;
	private BufferedImage buttonRect;
	private BufferedImage xBtnImg;
	private JLabel pointsLabel;
	private JPanel gridPanel;
	private float zoom = 1.0f;
	private static final float ZOOM_MIN = 0.5f;
	private static final float ZOOM_MAX = 2.0f;
	private static final float ZOOM_STEP = 0.15f;

	public WorldUnlockGridPanel(WorldUnlockService worldUnlockService, PointsService pointsService,
		Runnable onClose, Runnable onOpenGoals, Client client, AudioPlayer audioPlayer, JDialog parentDialog)
	{
		this.worldUnlockService = worldUnlockService;
		this.pointsService = pointsService;
		this.onClose = onClose;
		this.onOpenGoals = onOpenGoals;
		this.client = client;
		this.audioPlayer = audioPlayer;
		this.parentDialog = parentDialog;

		padlockImg = ImageUtil.loadImageResource(LeagueScapePlugin.class, "padlock_icon.png");
		checkmarkImg = ImageUtil.loadImageResource(LeagueScapePlugin.class, "complete_checkmark.png");
		tileBg = ImageUtil.loadImageResource(LeagueScapePlugin.class, "empty_button_square.png");
		interfaceBg = ImageUtil.loadImageResource(LeagueScapePlugin.class, "interface_template.png");
		buttonRect = ImageUtil.loadImageResource(LeagueScapePlugin.class, "empty_button_rectangle.png");
		xBtnImg = ImageUtil.loadImageResource(LeagueScapePlugin.class, "x_button.png");

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
		JLabel titleLabel = new JLabel("World Unlock");
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

		JScrollPane scrollPane = new JScrollPane(gridPanel);
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
			public void mousePressed(MouseEvent e) { dragStart[0] = e.getPoint(); }
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

		JPanel south = new JPanel(new BorderLayout(8, 0));
		south.setOpaque(false);
		south.setBorder(new EmptyBorder(0, 0, 8, 0));
		JButton goalsBtn = newRectangleButton("Goals", buttonRect, POPUP_TEXT);
		goalsBtn.addActionListener(e -> {
			playSound(LeagueScapeSounds.BUTTON_PRESS);
			if (onOpenGoals != null) onOpenGoals.run();
		});
		south.add(goalsBtn, BorderLayout.WEST);

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
		south.add(zoomPanel, BorderLayout.EAST);
		add(south, BorderLayout.SOUTH);

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
		worldUnlockService.load();
		int spendable = pointsService.getEarnedTotal() - pointsService.getSpentTotal();
		pointsLabel.setText("Points: " + spendable);

		gridPanel.removeAll();
		Set<String> unlocked = worldUnlockService.getUnlockedIds();
		List<WorldUnlockTilePlacement> grid = worldUnlockService.getGrid();
		if (grid.isEmpty()) { gridPanel.revalidate(); gridPanel.repaint(); return; }

		int maxRing = grid.stream()
			.mapToInt(p -> Math.max(Math.abs(p.getRow()), Math.abs(p.getCol())))
			.max().orElse(0);

		int tileSize = Math.max(24, (int) (BASE_TILE_SIZE * zoom));
		int iconMargin = Math.max(1, (tileSize * TILE_ICON_MARGIN) / BASE_TILE_SIZE);
		int iconMaxFit = Math.max(1, tileSize - 2 * iconMargin);

		for (WorldUnlockTilePlacement placement : grid)
		{
			if (!worldUnlockService.isRevealed(placement, unlocked, grid))
				continue;

			WorldUnlockTile tile = placement.getTile();
			boolean isCenter = placement.getRow() == 0 && placement.getCol() == 0;
			boolean isUnlocked = unlocked.contains(tile.getId());

			BufferedImage tileIcon = isCenter ? null : loadUnlockTileIcon(tile, iconMaxFit);

			JPanel cell = buildTileCell(placement, isCenter, isUnlocked, tileIcon, tileSize, iconMargin, grid, unlocked);
			GridBagConstraints gbc = new GridBagConstraints();
			gbc.gridx = placement.getCol() + maxRing;
			gbc.gridy = maxRing - placement.getRow();
			gbc.insets = new Insets(2, 2, 2, 2);
			gridPanel.add(cell, gbc);
		}
		gridPanel.revalidate();
		gridPanel.repaint();
	}

	/** Loads the icon for an unlock tile based on its type. */
	private BufferedImage loadUnlockTileIcon(WorldUnlockTile tile, int iconMaxFit)
	{
		String type = tile.getType() != null ? tile.getType() : "";
		String cacheKey = "unlock:" + type + ":" + tile.getId();
		BufferedImage cached = iconCache.get(cacheKey);
		if (cached != null) return scaleToFitAllowUpscale(cached, iconMaxFit, iconMaxFit);

		BufferedImage raw = null;

		switch (type)
		{
			case "skill":
			{
				String skillName = null;
				if (tile.getTaskLink() != null && tile.getTaskLink().getSkillName() != null)
					skillName = tile.getTaskLink().getSkillName();
				if (skillName == null)
					skillName = extractSkillNameFromDisplay(tile.getDisplayName());
				if (skillName != null && SKILL_ICON_MAP.containsKey(skillName))
					raw = loadFromTaskIcons(SKILL_ICON_MAP.get(skillName));
				break;
			}
			case "quest":
				raw = loadFromTaskIcons("Quest.png");
				break;
			case "achievement_diary":
				raw = loadFromTaskIcons("Achievement_Diaries.png");
				break;
			case "boss":
				raw = loadBossIcon(tile.getId());
				if (raw == null)
					raw = loadFromTaskIcons("Combat_icon_(detail).png");
				break;
			case "area":
				raw = createLetterIcon("A", iconMaxFit);
				if (raw != null) { iconCache.put(cacheKey, raw); return raw; }
				break;
			default:
				raw = loadFromTaskIcons("Other_icon.png");
				break;
		}

		if (raw == null)
			raw = createLetterIcon("?", iconMaxFit);
		if (raw != null)
			iconCache.put(cacheKey, raw);
		return raw != null ? scaleToFitAllowUpscale(raw, iconMaxFit, iconMaxFit) : null;
	}

	private static String extractSkillNameFromDisplay(String displayName)
	{
		if (displayName == null) return null;
		for (String skill : SKILL_ICON_MAP.keySet())
		{
			if (displayName.startsWith(skill)) return skill;
		}
		return null;
	}

	private static BufferedImage loadFromTaskIcons(String filename)
	{
		String path = TASK_ICONS_RESOURCE_PREFIX + filename;
		return iconCache.computeIfAbsent(path, p -> ImageUtil.loadImageResource(LeagueScapePlugin.class, p));
	}

	/** Loads boss tile icon from com/bossicons/; returns null if not found. */
	private static BufferedImage loadBossIcon(String bossTileId)
	{
		if (bossTileId == null || bossTileId.isEmpty()) return null;
		String filename = BOSS_ICON_OVERRIDES.get(bossTileId);
		if (filename == null)
			filename = "game_icon_" + bossTileId.replace("_", "") + ".png";
		String path = BOSS_ICONS_RESOURCE_PREFIX + filename;
		BufferedImage img = iconCache.get(path);
		if (img != null) return img;
		try
		{
			img = ImageUtil.loadImageResource(LeagueScapePlugin.class, path);
			if (img != null) iconCache.put(path, img);
		}
		catch (Exception ignored) { }
		return img;
	}

	/** Generates a simple letter icon (e.g. "A" for area tiles). */
	private static BufferedImage createLetterIcon(String letter, int size)
	{
		if (size <= 0) size = 28;
		BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(new Color(0xC4, 0xB8, 0x96, 220));
		g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, Math.max(14, size - 4)));
		java.awt.FontMetrics fm = g.getFontMetrics();
		int x = (size - fm.stringWidth(letter)) / 2;
		int y = (size + fm.getAscent()) / 2 - 2;
		g.drawString(letter, x, y);
		g.dispose();
		return img;
	}

	private JPanel buildTileCell(WorldUnlockTilePlacement placement, boolean isCenter, boolean isUnlocked,
		BufferedImage tileIcon, int tileSize, int iconMargin,
		List<WorldUnlockTilePlacement> grid, Set<String> unlocked)
	{
		WorldUnlockTile tile = placement.getTile();

		if (isUnlocked)
		{
			return buildUnlockedCell(isCenter, tileSize);
		}

		final BufferedImage bg = tileBg;
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
					Graphics2D g2 = (Graphics2D) g.create();
					g2.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, 0.35f));
					int s = Math.min(getWidth(), getHeight()) * 3 / 4;
					g2.drawImage(padlock.getScaledInstance(s, s, Image.SCALE_SMOOTH), (getWidth() - s) / 2, (getHeight() - s) / 2, null);
					g2.dispose();
				}
				super.paintComponent(g);
			}
		};
		cell.setLayout(new BorderLayout());
		cell.setOpaque(false);
		cell.setPreferredSize(new Dimension(tileSize, tileSize));

		if (tileIcon != null && !isCenter)
		{
			final BufferedImage iconImage = tileIcon;
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

		cell.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				if (e.getButton() != MouseEvent.BUTTON1) return;
				playSound(LeagueScapeSounds.BUTTON_PRESS);
				showTileDetailPopup(tile, isCenter);
			}
		});
		cell.setCursor(new java.awt.Cursor(java.awt.Cursor.HAND_CURSOR));
		return cell;
	}

	private JPanel buildUnlockedCell(boolean isCenter, int tileSize)
	{
		final BufferedImage bg = tileBg;
		final BufferedImage padlock = padlockImg;
		final BufferedImage checkmark = checkmarkImg != null
			? ImageUtil.resizeImage(checkmarkImg, CLAIMED_CHECKMARK_SIZE, CLAIMED_CHECKMARK_SIZE) : null;

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
					Graphics2D g2 = (Graphics2D) g.create();
					g2.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, 0.35f));
					int s = Math.min(getWidth(), getHeight()) * 3 / 4;
					g2.drawImage(padlock.getScaledInstance(s, s, Image.SCALE_SMOOTH), (getWidth() - s) / 2, (getHeight() - s) / 2, null);
					g2.dispose();
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
						g.drawImage(checkmark, getWidth() - CLAIMED_CHECKMARK_SIZE - CLAIMED_CHECKMARK_INSET,
							CLAIMED_CHECKMARK_INSET, null);
					}
				}
			}
		};
		cell.setOpaque(false);
		cell.setPreferredSize(new Dimension(tileSize, tileSize));
		return cell;
	}

	private void showTileDetailPopup(WorldUnlockTile tile, boolean isCenter)
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

		String windowTitle = tile.getDisplayName() != null ? tile.getDisplayName() : tile.getId();
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

		String typeLabel = tile.getType() != null ? capitalize(tile.getType().replace("_", " ")) : "Unlock";
		JLabel typeInfo = new JLabel("<html>" + typeLabel + " &middot; Tier " + tile.getTier() + "</html>");
		typeInfo.setForeground(POPUP_TEXT);
		body.add(typeInfo);

		int spendable = pointsService.getEarnedTotal() - pointsService.getSpentTotal();
		boolean canAfford = spendable >= tile.getCost();
		boolean prereqsMet = worldUnlockService.isUnlockable(tile);
		boolean alreadyUnlocked = worldUnlockService.getUnlockedIds().contains(tile.getId());

		if (isCenter && !alreadyUnlocked)
		{
			JLabel freeLabel = new JLabel("<html>This is your starting area. Unlock it for free!</html>");
			freeLabel.setForeground(POPUP_TEXT);
			body.add(freeLabel);
			body.add(new JLabel(" "));
			JButton unlockBtn = newRectangleButton("Unlock (Free)", buttonRect, POPUP_TEXT);
			unlockBtn.addActionListener(e -> {
				if (worldUnlockService.unlock(tile.getId(), tile.getCost()))
				{
					playSound(LeagueScapeSounds.TASK_COMPLETE);
					detail.dispose();
					SwingUtilities.invokeLater(this::refresh);
				}
			});
			body.add(unlockBtn);
		}
		else if (alreadyUnlocked)
		{
			JLabel doneLabel = new JLabel("<html>Already unlocked.</html>");
			doneLabel.setForeground(new Color(120, 200, 120));
			body.add(doneLabel);
		}
		else
		{
			JLabel costLabel = new JLabel("Cost: " + tile.getCost() + " points");
			costLabel.setForeground(POPUP_TEXT);
			body.add(costLabel);
			JLabel pointsLbl = new JLabel("Your points: " + spendable);
			pointsLbl.setForeground(POPUP_TEXT);
			body.add(pointsLbl);
			body.add(new JLabel(" "));

			if (!prereqsMet)
			{
				JLabel prereqLabel = new JLabel("<html>Unlock prerequisites first.</html>");
				prereqLabel.setForeground(new Color(200, 120, 120));
				body.add(prereqLabel);
			}
			else if (!canAfford)
			{
				JLabel affordLabel = new JLabel("<html>Not enough points.</html>");
				affordLabel.setForeground(new Color(200, 120, 120));
				body.add(affordLabel);
			}
			else
			{
				JButton unlockBtn = newRectangleButton("Unlock (" + tile.getCost() + " pts)", buttonRect, POPUP_TEXT);
				unlockBtn.addActionListener(e -> {
					if (worldUnlockService.unlock(tile.getId(), tile.getCost()))
					{
						playSound(LeagueScapeSounds.TASK_COMPLETE);
						detail.dispose();
						SwingUtilities.invokeLater(this::refresh);
					}
				});
				body.add(unlockBtn);
			}
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

	// --- Icon helpers ---

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

	// --- UI helpers ---

	private void playSound(String sound)
	{
		if (audioPlayer != null && client != null)
			LeagueScapeSounds.play(audioPlayer, sound, client);
	}

	private static String capitalize(String s)
	{
		if (s == null || s.isEmpty()) return s;
		return s.substring(0, 1).toUpperCase() + s.substring(1);
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

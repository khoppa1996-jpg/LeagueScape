package com.leaguescape.config;

import com.leaguescape.LeagueScapeConfig;
import com.leaguescape.LeagueScapePlugin;
import com.leaguescape.LeagueScapeSounds;
import com.leaguescape.util.LeagueScapeColors;
import com.leaguescape.util.LeagueScapeSwingUtil;
import com.leaguescape.area.AreaGraphService;
import com.leaguescape.points.AreaCompletionService;
import com.leaguescape.points.PointsService;
import com.leaguescape.task.TaskGridService;
import com.leaguescape.wiki.OsrsWikiApiService;
import com.leaguescape.wiki.WikiTaskGenerator;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Frame;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BoxLayout;
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
import net.runelite.client.config.ConfigManager;
import net.runelite.client.util.ImageUtil;

/**
 * Moveable, resizable setup popup opened from the "Rules and Setup" button. Contains four tabs:
 * Rules, Game Mode, Area Configuration, Controls. Uses LeagueScape popup styling.
 */
public class LeagueScapeSetupFrame extends JDialog
{
	private static final Color POPUP_BG = LeagueScapeColors.POPUP_BG;
	private static final Color POPUP_TEXT = LeagueScapeColors.POPUP_TEXT;
	private static final Color POPUP_BORDER = new Color(0x2a, 0x28, 0x24);
	private static final Dimension RECTANGLE_BUTTON_SIZE = new Dimension(160, 28);
	private static final int TAB_STRIP_WIDTH = 140;
	private static final int MIN_WIDTH = 520;
	private static final int MIN_HEIGHT = 400;
	/** Outer edge of border_styles_fill_color.png that is not scaled (keeps border crisp). */
	private static final int BORDER_INSET = 6;

	private static final String CARD_RULES = "rules";
	private static final String CARD_GAME_MODE = "gameMode";
	private static final String CARD_AREA_CONFIG = "areaConfig";
	private static final String CARD_CONTROLS = "controls";

	private final JPanel contentCards;
	private final java.awt.CardLayout cardLayout;
	private final AudioPlayer audioPlayer;
	private final Client client;
	private BufferedImage buttonRect;
	private BufferedImage borderFillImg;

	public LeagueScapeSetupFrame(Frame owner, LeagueScapePlugin plugin, AreaGraphService areaGraphService,
		TaskGridService taskGridService, ConfigManager configManager, LeagueScapeConfig config,
		PointsService pointsService, AreaCompletionService areaCompletionService,
		OsrsWikiApiService wikiApi, WikiTaskGenerator wikiTaskGenerator, Client client, AudioPlayer audioPlayer)
	{
		super(owner, "LeagueScape – Rules and Setup", false);
		this.audioPlayer = audioPlayer;
		this.client = client;
		setModal(false);
		setResizable(true);
		setMinimumSize(new Dimension(MIN_WIDTH, MIN_HEIGHT));
		setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

		buttonRect = ImageUtil.loadImageResource(LeagueScapePlugin.class, "empty_button_rectangle.png");
		borderFillImg = ImageUtil.loadImageResource(LeagueScapePlugin.class, "border_styles_fill_color.png");

		JPanel root = new JPanel(new BorderLayout(0, 0))
		{
			@Override
			protected void paintComponent(Graphics g)
			{
				if (borderFillImg != null)
					paintBorderImageNinePatch(g, borderFillImg, 0, 0, getWidth(), getHeight());
				super.paintComponent(g);
			}
		};
		root.setOpaque(false);
		root.setBorder(new EmptyBorder(0, 0, 0, 0));

		// Title bar (drag region; window close used for closing)
		JPanel titleBar = new JPanel(new BorderLayout(4, 0));
		titleBar.setOpaque(false);
		titleBar.setBorder(new EmptyBorder(8, 12, 8, 12));
		javax.swing.JLabel titleLabel = new javax.swing.JLabel("LeagueScape – Rules and Setup");
		titleLabel.setForeground(POPUP_TEXT);
		titleLabel.setFont(titleLabel.getFont().deriveFont(java.awt.Font.BOLD, 14f));
		titleBar.add(titleLabel, BorderLayout.CENTER);
		// Drag to move (dialog position on screen)
		final int[] dragOffset = new int[2];
		titleBar.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				java.awt.Point loc = LeagueScapeSetupFrame.this.getLocationOnScreen();
				dragOffset[0] = e.getXOnScreen() - loc.x;
				dragOffset[1] = e.getYOnScreen() - loc.y;
			}
		});
		titleBar.addMouseMotionListener(new MouseAdapter()
		{
			@Override
			public void mouseDragged(MouseEvent e)
			{
				LeagueScapeSetupFrame.this.setLocation(e.getXOnScreen() - dragOffset[0], e.getYOnScreen() - dragOffset[1]);
			}
		});
		root.add(titleBar, BorderLayout.NORTH);

		// Left tab strip (divider box) + right content
		JPanel body = new JPanel(new BorderLayout(0, 0));
		body.setOpaque(false);
		body.setBorder(new EmptyBorder(0, 8, 12, 12));

		// Tab strip inside a divider box; border_styles_fill_color only (9-patch from root shows through)
		JPanel tabStripWrapper = new JPanel(new BorderLayout());
		tabStripWrapper.setOpaque(false);
		tabStripWrapper.setBorder(new EmptyBorder(6, 6, 6, 6));
		JPanel tabStrip = new JPanel();
		tabStrip.setLayout(new BoxLayout(tabStrip, BoxLayout.Y_AXIS));
		tabStrip.setOpaque(false);
		tabStrip.setPreferredSize(new Dimension(TAB_STRIP_WIDTH, 0));
		tabStrip.setBorder(new EmptyBorder(0, 0, 0, 0));

		cardLayout = new java.awt.CardLayout();
		contentCards = new JPanel(cardLayout);
		contentCards.setOpaque(false);

		// Tab content panels
		JPanel rulesCard = buildRulesTab();
		JPanel gameModeCard = buildGameModeTab(plugin, configManager, config, areaGraphService, pointsService, areaCompletionService, taskGridService, wikiApi, wikiTaskGenerator, client);
		JPanel areaConfigCard = new LeagueScapeAreaConfigSection(plugin, areaGraphService, configManager, config, true);
		JPanel controlsCard = buildControlsTab();

		contentCards.add(rulesCard, CARD_RULES);
		contentCards.add(gameModeCard, CARD_GAME_MODE);
		contentCards.add(areaConfigCard, CARD_AREA_CONFIG);
		contentCards.add(controlsCard, CARD_CONTROLS);

		List<JButton> tabButtons = new ArrayList<>();
		tabButtons.add(newTabButton("Rules", () -> cardLayout.show(contentCards, CARD_RULES), tabButtons));
		tabButtons.add(newTabButton("Game Mode", () -> cardLayout.show(contentCards, CARD_GAME_MODE), tabButtons));
		tabButtons.add(newTabButton("Area Configuration", () -> cardLayout.show(contentCards, CARD_AREA_CONFIG), tabButtons));
		tabButtons.add(newTabButton("Controls", () -> cardLayout.show(contentCards, CARD_CONTROLS), tabButtons));

		for (JButton b : tabButtons)
			tabStrip.add(b);
		// Select first tab visually
		if (!tabButtons.isEmpty())
			setTabSelected(tabButtons.get(0), tabButtons);

		tabStripWrapper.add(tabStrip, BorderLayout.CENTER);

		// Card content wrapper (transparent; root 9-patch shows through)
		JPanel cardWrapper = new JPanel(new BorderLayout());
		cardWrapper.setOpaque(false);
		cardWrapper.setBorder(new EmptyBorder(0, 8, 0, 0));
		cardWrapper.add(contentCards, BorderLayout.CENTER);

		body.add(tabStripWrapper, BorderLayout.WEST);
		body.add(cardWrapper, BorderLayout.CENTER);
		root.add(body, BorderLayout.CENTER);

		setContentPane(root);
		pack();
	}

	private static final Dimension TAB_BUTTON_SIZE = new Dimension(TAB_STRIP_WIDTH - 16, 28);

	private JButton newTabButton(String label, Runnable onSelect, List<JButton> allTabs)
	{
		JButton b = LeagueScapeSwingUtil.newRectangleButton(label, buttonRect, POPUP_TEXT);
		b.setPreferredSize(TAB_BUTTON_SIZE);
		b.setMinimumSize(TAB_BUTTON_SIZE);
		b.setMaximumSize(new Dimension(TAB_STRIP_WIDTH - 12, 28));
		b.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
		b.addActionListener(e -> {
			if (audioPlayer != null && client != null)
				LeagueScapeSounds.play(audioPlayer, LeagueScapeSounds.BUTTON_PRESS, client);
			setTabSelected(b, allTabs);
			onSelect.run();
		});
		return b;
	}

	private void setTabSelected(JButton selected, List<JButton> allTabs)
	{
		for (JButton b : allTabs)
		{
			b.setOpaque(b == selected);
			b.setContentAreaFilled(b == selected);
			if (b == selected)
				b.setBackground(POPUP_BORDER);
			b.repaint();
		}
	}

	private static final int OVERLAY_BUTTON_PREVIEW_SIZE = 32;

	private JPanel buildRulesTab()
	{
		JPanel panel = new JPanel(new BorderLayout());
		panel.setOpaque(false);

		// Overlay button preview + description
		JPanel buttonPreviewPanel = new JPanel(new BorderLayout(12, 0));
		buttonPreviewPanel.setOpaque(false);
		buttonPreviewPanel.setBorder(new EmptyBorder(12, 12, 8, 12));
		BufferedImage taskIcon = ImageUtil.loadImageResource(LeagueScapePlugin.class, "task_icon.png");
		if (taskIcon != null)
		{
			Image scaled = taskIcon.getScaledInstance(OVERLAY_BUTTON_PREVIEW_SIZE, OVERLAY_BUTTON_PREVIEW_SIZE, Image.SCALE_SMOOTH);
			JLabel iconLabel = new JLabel(new javax.swing.ImageIcon(scaled));
			iconLabel.setOpaque(false);
			buttonPreviewPanel.add(iconLabel, BorderLayout.WEST);
		}
		JLabel buttonDesc = new JLabel("<html><b>LeagueScape overlay button</b><br>"
			+ "Shown under the minimap, left of the world map orb.<br>"
			+ "• <b>Left-click:</b> Open Tasks (area task grid in Point buy / Points-to-complete; global task list in World Unlock mode).<br>"
			+ "• <b>Right-click:</b> Menu — <b>Tasks</b>, <b>World Unlocks</b> (only in World Unlock mode), <b>Rules &amp; Setup</b> (this window).</html>");
		buttonDesc.setForeground(POPUP_TEXT);
		buttonDesc.setFont(buttonDesc.getFont().deriveFont(13f));
		buttonDesc.setOpaque(false);
		buttonPreviewPanel.add(buttonDesc, BorderLayout.CENTER);
		panel.add(buttonPreviewPanel, BorderLayout.NORTH);

		String rulesText = getRulesText();
		javax.swing.JTextArea text = new javax.swing.JTextArea(rulesText);
		text.setEditable(false);
		text.setLineWrap(true);
		text.setWrapStyleWord(true);
		text.setForeground(POPUP_TEXT);
		text.setOpaque(false);
		text.setCaretColor(POPUP_TEXT);
		text.setBorder(new EmptyBorder(0, 12, 12, 12));
		text.setFont(text.getFont().deriveFont(13f));
		JScrollPane scroll = new JScrollPane(text);
		scroll.setBorder(null);
		scroll.setOpaque(false);
		scroll.getViewport().setOpaque(false);
		panel.add(scroll, BorderLayout.CENTER);
		return panel;
	}

	private static String getRulesText()
	{
		return "How to play LeagueScape\n"
			+ "LeagueScape is an area-based progression plugin for Old School RuneScape. You start in a chosen starter area with a set number of points. "
			+ "Complete tasks to earn points. Use points and area completion (depending on mode) to unlock new areas or tiles. "
			+ "Areas are connected by neighbors; progression is defined in Game Mode and Area Configuration.\n\n"
			+ "The LeagueScape overlay button (see above) is the quickest way to open Tasks or the right-click menu. You can also use the LeagueScape sidebar panel: "
			+ "the Tasks button opens the same task view as left-clicking the overlay button; in World Unlock mode use the panel or right-click menu to open the World Unlock grid.\n\n"
			+ "Unlocking tiles (World Unlock mode only)\n"
			+ "In World Unlock mode, open the World Unlocks grid via the overlay button right-click menu (World Unlocks) or the sidebar. "
			+ "The grid shows tiles for skills, quests, bosses, and areas. Spend points on a tile to unlock it; unlocked tiles may require meeting in-game conditions (e.g. quest completed). "
			+ "Once a tile is unlocked, its tasks become available in the global task list. Unlock tiles in any order that your points and requirements allow.\n\n"
			+ "Claiming tasks (all modes)\n"
			+ "• Point buy & Points to complete: Open the task grid for your current area (overlay left-click or sidebar Tasks). Click a task tile to claim it — you earn points when you complete the task in-game. "
			+ "Only tasks for areas you have unlocked (or the starter area) are available. In Points to complete, you must earn enough points in an area to \"complete\" it before you can unlock neighboring areas.\n"
			+ "• World Unlock: Open the global task list (overlay left-click or sidebar Tasks). Tasks are grouped by the tile they came from (e.g. a boss or skill). Click a task to claim it; complete it in-game to earn points, then spend points on more tiles or claim more tasks.\n\n"
			+ "You can also open a specific area's task grid from the world map: right-click an area and choose the option to view its details or task grid (in Point buy / Points to complete, this shows that area's tasks).\n\n"
			+ "Configuration (this popup)\n"
			+ "• Rules (this tab): How to play, overlay button, unlocking tiles, and claiming tasks.\n"
			+ "• Game Mode: Choose unlock mode (Point buy, Points to complete, World Unlock), task tier point scale, starter area, starting points, and reset progress. Manage the task list and task file path here.\n"
			+ "• Area Configuration: Import/export area JSON files, add or edit areas (polygon corners and holes), set area neighbors, restore removed areas. Use the game viewport or world map to add/move/remove polygon corners when editing (see Controls tab).\n"
			+ "• Controls: Keybinds and actions for area editing and map interactions.";
	}

	private JPanel buildGameModeTab(LeagueScapePlugin plugin, ConfigManager configManager, LeagueScapeConfig config,
		AreaGraphService areaGraphService, PointsService pointsService, AreaCompletionService areaCompletionService,
		TaskGridService taskGridService, OsrsWikiApiService wikiApi, WikiTaskGenerator wikiTaskGenerator, Client client)
	{
		LeagueScapeGameModeTabPanel p = new LeagueScapeGameModeTabPanel(plugin, configManager, config, areaGraphService,
			pointsService, areaCompletionService, taskGridService, wikiApi, wikiTaskGenerator, client,
			new Color(0, 0, 0, 0), POPUP_TEXT, this::newRectangleButton);
		return p;
	}

	private JPanel buildControlsTab()
	{
		JPanel panel = new JPanel(new BorderLayout());
		panel.setOpaque(false);
		String controlsText = getControlsText();
		javax.swing.JTextArea text = new javax.swing.JTextArea(controlsText);
		text.setEditable(false);
		text.setLineWrap(true);
		text.setWrapStyleWord(true);
		text.setForeground(POPUP_TEXT);
		text.setOpaque(false);
		text.setCaretColor(POPUP_TEXT);
		text.setBorder(new EmptyBorder(12, 12, 12, 12));
		text.setFont(text.getFont().deriveFont(13f));
		JScrollPane scroll = new JScrollPane(text);
		scroll.setBorder(null);
		scroll.setOpaque(false);
		scroll.getViewport().setOpaque(false);
		panel.add(scroll, BorderLayout.CENTER);
		return panel;
	}

	private static String getControlsText()
	{
		return "Area editing – Game viewport (when editing an area):\n"
			+ "• Shift + Right-click on a tile: Add polygon corner at that tile.\n"
			+ "• Shift + Right-click on an existing corner: Choose \"Move\" to enter move mode; then click another tile and choose \"Set new corner\" to move the corner there, or \"Cancel move\" to cancel.\n\n"
			+ "Area editing – World map (when editing an area):\n"
			+ "• Right-click: Move corner, Remove corner, Fill using others' corners, Begin new polygon, Add neighbors, Done editing, Cancel editing.\n\n"
			+ "LeagueScape UI: The overlay button (under the minimap, left of the world map orb) — left-click for Tasks, right-click for Tasks, World Unlocks (World Unlock mode), and Rules & Setup. "
			+ "You can also use the LeagueScape sidebar panel for Tasks and (in World Unlock mode) to open the World Unlock grid. Open the world map and right-click an area to see its details and unlock/tasks.";
	}

	private JButton newRectangleButton(String text)
	{
		JButton b = LeagueScapeSwingUtil.newRectangleButton(text, buttonRect, POPUP_TEXT);
		b.setPreferredSize(RECTANGLE_BUTTON_SIZE);
		return b;
	}

	/**
	 * Paints the border image in 9-patch style: the outer BORDER_INSET pixels on top, bottom,
	 * left, and right are drawn at 1:1 scale to preserve the border; the center is stretched to fill.
	 */
	private static void paintBorderImageNinePatch(Graphics g, BufferedImage img, int x, int y, int width, int height)
	{
		if (img == null || width <= 0 || height <= 0) return;
		int w = img.getWidth();
		int h = img.getHeight();
		int b = BORDER_INSET;
		if (w < 2 * b || h < 2 * b) return;
		int tw = Math.max(width, 2 * b);
		int th = Math.max(height, 2 * b);
		// Clamp so we don't read past image edges
		int sxMid = w - 2 * b;
		int syMid = h - 2 * b;
		int dxMid = tw - 2 * b;
		int dyMid = th - 2 * b;
		if (sxMid <= 0 || syMid <= 0 || dxMid <= 0 || dyMid <= 0) return;
		// Four corners (unscaled)
		g.drawImage(img, x, y, x + b, y + b, 0, 0, b, b, null);
		g.drawImage(img, x + tw - b, y, x + tw, y + b, w - b, 0, w, b, null);
		g.drawImage(img, x, y + th - b, x + b, y + th, 0, h - b, b, h, null);
		g.drawImage(img, x + tw - b, y + th - b, x + tw, y + th, w - b, h - b, w, h, null);
		// Four edges (stretch in one direction only; other dimension stays b pixels)
		g.drawImage(img, x + b, y, x + tw - b, y + b, b, 0, w - b, b, null);
		g.drawImage(img, x + b, y + th - b, x + tw - b, y + th, b, h - b, w - b, h, null);
		g.drawImage(img, x, y + b, x + b, y + th - b, 0, b, b, h - b, null);
		g.drawImage(img, x + tw - b, y + b, x + tw, y + th - b, w - b, b, w, h - b, null);
		// Center (stretched)
		g.drawImage(img, x + b, y + b, x + tw - b, y + th - b, b, b, w - b, h - b, null);
	}

}

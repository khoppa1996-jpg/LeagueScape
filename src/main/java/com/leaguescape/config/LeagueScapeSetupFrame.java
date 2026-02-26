package com.leaguescape.config;

import com.leaguescape.LeagueScapeConfig;
import com.leaguescape.LeagueScapePlugin;
import com.leaguescape.LeagueScapeSounds;
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
	private static final Color POPUP_BG = new Color(0x54, 0x4D, 0x41);
	private static final Color POPUP_TEXT = new Color(0xC4, 0xB8, 0x96);
	private static final Color POPUP_BORDER = new Color(0x2a, 0x28, 0x24);
	private static final Color PRESSED_INSET_SHADOW = new Color(0, 0, 0, 70);
	private static final int PRESSED_INSET = 2;
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
		JButton b = newRectangleButton(label, buttonRect, POPUP_TEXT);
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

	private JPanel buildRulesTab()
	{
		JPanel panel = new JPanel(new BorderLayout());
		panel.setOpaque(false);
		String rulesText = getRulesText();
		javax.swing.JTextArea text = new javax.swing.JTextArea(rulesText);
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

	private static String getRulesText()
	{
		return "LeagueScape is an area-based progression plugin for Old School RuneScape. "
			+ "You start in a chosen area with a set number of points. Areas are connected by neighbors; "
			+ "you unlock new areas by spending points (Point buy), or by earning points in each area and then spending to unlock the next (Points to complete), "
			+ "or by spending points on a World Unlock grid of tiles (skills, quests, bosses, areas).\n\n"
			+ "Tasks: Each area has a task grid. Complete tasks to earn points. In Point buy and Points-to-complete modes, use the Tasks button on the LeagueScape panel to open the task grid for your current area. "
			+ "In World Unlock mode, open the World Unlock grid and spend points on tiles; tasks come from unlocked tiles.\n\n"
			+ "Configuration (this popup):\n"
			+ "• Rules (this tab): Overview and how to use the setup.\n"
			+ "• Game Mode: Choose unlock mode, task tier point scale, starter area, starting points, and reset progress. You can also manage the task list and task file path here.\n"
			+ "• Area Configuration: Import/export area JSON files, add or edit areas (including polygon corners and holes), set area neighbors, and restore removed areas. "
			+ "Use the game viewport or world map to add/move/remove polygon corners when editing an area (see Controls tab).\n"
			+ "• Controls: Lists all custom keybinds and actions for area editing and map interactions.";
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
			+ "Other: Open the world map and right click an area to see its details and unlock/tasks. Use the LeagueScape sidebar panel for Tasks and World Unlock.";
	}

	private JButton newRectangleButton(String text)
	{
		return newRectangleButton(text, buttonRect, POPUP_TEXT);
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
				else
				{
					super.paintComponent(g);
				}
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

}

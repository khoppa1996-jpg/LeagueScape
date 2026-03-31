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
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.border.EmptyBorder;
import javax.swing.SwingUtilities;
import net.runelite.api.Client;
import net.runelite.client.audio.AudioPlayer;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.util.ImageUtil;
import com.leaguescape.util.LeagueScapeFrameChromePanel;
import com.leaguescape.util.LeagueScapeSwingUtil;

/**
 * Moveable, resizable setup popup opened from the "Rules and Setup" button. Contains four tabs:
 * Rules, Game Mode, Area Configuration, Controls. Tiled OSRS-style frame ({@code com/leaguescape/*.png}).
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

	private static final String CARD_RULES = "rules";
	private static final String CARD_GAME_MODE = "gameMode";
	private static final String CARD_AREA_CONFIG = "areaConfig";
	private static final String CARD_CONTROLS = "controls";

	private final JPanel contentCards;
	private final java.awt.CardLayout cardLayout;
	private final AudioPlayer audioPlayer;
	private final Client client;
	private BufferedImage buttonRect;

	public LeagueScapeSetupFrame(Frame owner, LeagueScapePlugin plugin, AreaGraphService areaGraphService,
		TaskGridService taskGridService, ConfigManager configManager, LeagueScapeConfig config,
		PointsService pointsService, AreaCompletionService areaCompletionService,
		OsrsWikiApiService wikiApi, WikiTaskGenerator wikiTaskGenerator, Client client, AudioPlayer audioPlayer)
	{
		super(owner, LeagueScapeSetupStrings.FRAME_WINDOW_TITLE, false);
		this.audioPlayer = audioPlayer;
		this.client = client;
		setModal(false);
		setResizable(true);
		setMinimumSize(new Dimension(MIN_WIDTH, MIN_HEIGHT));
		setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
		setUndecorated(true);

		buttonRect = ImageUtil.loadImageResource(LeagueScapePlugin.class, "empty_button_rectangle.png");
		BufferedImage xBtnImg = ImageUtil.loadImageResource(LeagueScapePlugin.class, "x_button.png");

		BufferedImage fill = loadFrameAsset("fill_color.png");
		BufferedImage tl = loadFrameAsset("top_left_corner.png");
		BufferedImage tr = loadFrameAsset("top_right_corner.png");
		BufferedImage bl = loadFrameAsset("bottom_left_corner.png");
		BufferedImage br = loadFrameAsset("bottom_right_corner.png");
		BufferedImage bTop = loadFrameAsset("border_top.png");
		BufferedImage bBottom = loadFrameAsset("border_bottom.png");
		BufferedImage bLeft = loadFrameAsset("border_left.png");
		BufferedImage bRight = loadFrameAsset("border_right.png");

		LeagueScapeFrameChromePanel chrome = new LeagueScapeFrameChromePanel(fill, tl, tr, bl, br, bTop, bBottom, bLeft, bRight);
		chrome.setLayout(new BorderLayout(0, 0));

		JPanel inner = new JPanel(new BorderLayout(0, 0));
		inner.setOpaque(false);
		inner.setBorder(new EmptyBorder(chrome.getChromeInsets()));

		// Title bar: drag region + x close (same behaviour as other LeagueScape popups: Esc via registerEscapeToClose on open)
		JPanel titleBar = new JPanel(new BorderLayout(4, 0));
		titleBar.setOpaque(false);
		titleBar.setBorder(new EmptyBorder(4, 8, 4, 4));
		javax.swing.JLabel titleLabel = new javax.swing.JLabel(LeagueScapeSetupStrings.FRAME_WINDOW_TITLE);
		titleLabel.setForeground(POPUP_TEXT);
		titleLabel.setFont(titleLabel.getFont().deriveFont(java.awt.Font.BOLD, 14f));
		titleBar.add(titleLabel, BorderLayout.CENTER);

		JButton closeBtn = LeagueScapeSwingUtil.newPopupButtonWithIcon(xBtnImg, POPUP_TEXT);
		closeBtn.setPreferredSize(new Dimension(28, 28));
		closeBtn.addActionListener(e -> {
			if (audioPlayer != null && client != null)
				LeagueScapeSounds.play(audioPlayer, LeagueScapeSounds.BUTTON_PRESS, client);
			dispose();
		});
		titleBar.add(closeBtn, BorderLayout.EAST);

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

		JPanel body = new JPanel(new BorderLayout(0, 0));
		body.setOpaque(false);
		body.setBorder(new EmptyBorder(0, 4, 8, 8));

		JPanel tabStripWrapper = new JPanel(new BorderLayout());
		tabStripWrapper.setBackground(POPUP_BG);
		tabStripWrapper.setOpaque(true);
		tabStripWrapper.setBorder(new EmptyBorder(4, 4, 4, 4));
		JPanel tabStrip = new JPanel();
		tabStrip.setLayout(new BoxLayout(tabStrip, BoxLayout.Y_AXIS));
		tabStrip.setOpaque(false);
		tabStrip.setPreferredSize(new Dimension(TAB_STRIP_WIDTH, 0));
		tabStrip.setBorder(new EmptyBorder(0, 0, 0, 0));

		cardLayout = new java.awt.CardLayout();
		contentCards = new JPanel(cardLayout);
		contentCards.setBackground(POPUP_BG);
		contentCards.setOpaque(true);

		JPanel rulesCard = buildRulesTab();
		JPanel gameModeCard = buildGameModeTab(plugin, configManager, config, areaGraphService, pointsService, areaCompletionService, taskGridService, wikiApi, wikiTaskGenerator, client);
		JPanel areaConfigCard = new LeagueScapeAreaConfigSection(plugin, areaGraphService, configManager, config);
		JPanel controlsCard = buildControlsTab();

		contentCards.add(rulesCard, CARD_RULES);
		contentCards.add(gameModeCard, CARD_GAME_MODE);
		contentCards.add(areaConfigCard, CARD_AREA_CONFIG);
		contentCards.add(controlsCard, CARD_CONTROLS);

		List<JButton> tabButtons = new ArrayList<>();
		tabButtons.add(newTabButton(LeagueScapeSetupStrings.TAB_RULES, () -> cardLayout.show(contentCards, CARD_RULES), tabButtons));
		tabButtons.add(newTabButton(LeagueScapeSetupStrings.TAB_GAME_MODE, () -> cardLayout.show(contentCards, CARD_GAME_MODE), tabButtons));
		tabButtons.add(newTabButton(LeagueScapeSetupStrings.TAB_AREA_CONFIGURATION, () -> cardLayout.show(contentCards, CARD_AREA_CONFIG), tabButtons));
		tabButtons.add(newTabButton(LeagueScapeSetupStrings.TAB_CONTROLS, () -> cardLayout.show(contentCards, CARD_CONTROLS), tabButtons));

		for (JButton b : tabButtons)
			tabStrip.add(b);
		if (!tabButtons.isEmpty())
			setTabSelected(tabButtons.get(0), tabButtons);

		tabStripWrapper.add(tabStrip, BorderLayout.CENTER);

		JPanel cardWrapper = new JPanel(new BorderLayout());
		cardWrapper.setBackground(POPUP_BG);
		cardWrapper.setOpaque(true);
		cardWrapper.setBorder(new EmptyBorder(0, 8, 0, 0));
		cardWrapper.add(contentCards, BorderLayout.CENTER);

		body.add(tabStripWrapper, BorderLayout.WEST);
		body.add(cardWrapper, BorderLayout.CENTER);

		inner.add(titleBar, BorderLayout.NORTH);
		inner.add(body, BorderLayout.CENTER);
		chrome.add(inner, BorderLayout.CENTER);

		setContentPane(chrome);

		addWindowFocusListener(new WindowAdapter()
		{
			@Override
			public void windowLostFocus(WindowEvent e)
			{
				// Do not close when a modal JOptionPane / file chooser (owned by this dialog) takes focus.
				if (isOppositeWindowOwnedByThisDialog(e.getOppositeWindow()))
					return;
				SwingUtilities.invokeLater(() -> {
					if (LeagueScapeSetupFrame.this.isDisplayable())
						LeagueScapeSetupFrame.this.dispose();
				});
			}
		});

		LeagueScapePlugin.registerEscapeToClose(this);
		pack();
	}

	/** True if focus moved to a top-level window whose owner chain includes this dialog (e.g. JOptionPane). */
	private boolean isOppositeWindowOwnedByThisDialog(Window opposite)
	{
		if (opposite == null) return false;
		for (Window w = opposite; w != null; w = w.getOwner())
		{
			if (w == this) return true;
		}
		return false;
	}

	private static BufferedImage loadFrameAsset(String name)
	{
		return ImageUtil.loadImageResource(LeagueScapePlugin.class, name);
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
		panel.setBackground(POPUP_BG);
		panel.setOpaque(true);

		JPanel inner = new JPanel();
		inner.setLayout(new BoxLayout(inner, BoxLayout.Y_AXIS));
		inner.setBackground(POPUP_BG);
		inner.setOpaque(true);

		javax.swing.JTextArea mainText = new javax.swing.JTextArea(LeagueScapeSetupStrings.RULES_MAIN);
		mainText.setEditable(false);
		mainText.setLineWrap(true);
		mainText.setWrapStyleWord(true);
		mainText.setForeground(POPUP_TEXT);
		mainText.setBackground(POPUP_BG);
		mainText.setCaretColor(POPUP_TEXT);
		mainText.setBorder(new EmptyBorder(12, 12, 8, 12));
		mainText.setFont(mainText.getFont().deriveFont(13f));
		mainText.setColumns(64);

		JPanel taskIconBlock = new JPanel(new BorderLayout(12, 0));
		taskIconBlock.setBackground(POPUP_BG);
		taskIconBlock.setOpaque(true);
		taskIconBlock.setBorder(new EmptyBorder(0, 12, 12, 12));

		JLabel iconLabel = new JLabel();
		BufferedImage taskIconImg = ImageUtil.loadImageResource(LeagueScapePlugin.class, "task_icon.png");
		if (taskIconImg != null)
		{
			Image scaled = taskIconImg.getScaledInstance(32, 32, Image.SCALE_SMOOTH);
			iconLabel.setIcon(new ImageIcon(scaled));
			iconLabel.setVerticalAlignment(JLabel.TOP);
		}
		iconLabel.setOpaque(false);

		javax.swing.JTextArea taskIconText = new javax.swing.JTextArea(LeagueScapeSetupStrings.RULES_TASK_ICON);
		taskIconText.setEditable(false);
		taskIconText.setLineWrap(true);
		taskIconText.setWrapStyleWord(true);
		taskIconText.setForeground(POPUP_TEXT);
		taskIconText.setBackground(POPUP_BG);
		taskIconText.setCaretColor(POPUP_TEXT);
		taskIconText.setFont(taskIconText.getFont().deriveFont(13f));
		taskIconText.setBorder(new EmptyBorder(0, 0, 0, 0));
		taskIconText.setColumns(52);

		taskIconBlock.add(iconLabel, BorderLayout.WEST);
		taskIconBlock.add(taskIconText, BorderLayout.CENTER);

		inner.add(mainText);
		inner.add(taskIconBlock);

		JScrollPane scroll = new JScrollPane(inner);
		scroll.setBorder(null);
		scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.getViewport().setBackground(POPUP_BG);
		panel.add(scroll, BorderLayout.CENTER);
		return panel;
	}

	private JPanel buildGameModeTab(LeagueScapePlugin plugin, ConfigManager configManager, LeagueScapeConfig config,
		AreaGraphService areaGraphService, PointsService pointsService, AreaCompletionService areaCompletionService,
		TaskGridService taskGridService, OsrsWikiApiService wikiApi, WikiTaskGenerator wikiTaskGenerator, Client client)
	{
		LeagueScapeGameModeTabPanel p = new LeagueScapeGameModeTabPanel(plugin, configManager, config, areaGraphService,
			pointsService, areaCompletionService, taskGridService, wikiApi, wikiTaskGenerator, client,
			POPUP_BG, POPUP_TEXT, this::newRectangleButton);
		return p;
	}

	private JPanel buildControlsTab()
	{
		JPanel panel = new JPanel(new BorderLayout());
		panel.setBackground(POPUP_BG);
		panel.setOpaque(true);
		javax.swing.JTextArea text = new javax.swing.JTextArea(LeagueScapeSetupStrings.CONTROLS_BODY);
		text.setEditable(false);
		text.setLineWrap(true);
		text.setWrapStyleWord(true);
		text.setForeground(POPUP_TEXT);
		text.setBackground(POPUP_BG);
		text.setCaretColor(POPUP_TEXT);
		text.setBorder(new EmptyBorder(12, 12, 12, 12));
		text.setFont(text.getFont().deriveFont(13f));
		JScrollPane scroll = new JScrollPane(text);
		scroll.setBorder(null);
		scroll.getViewport().setBackground(POPUP_BG);
		panel.add(scroll, BorderLayout.CENTER);
		return panel;
	}

	private JButton newRectangleButton(String text)
	{
		return newRectangleButton(text, buttonRect, POPUP_TEXT);
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

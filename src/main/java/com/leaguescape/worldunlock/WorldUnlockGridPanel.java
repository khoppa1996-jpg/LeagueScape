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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.concurrent.ConcurrentHashMap;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComboBox;
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
	private static final Color POPUP_BG = com.leaguescape.util.LeagueScapeColors.POPUP_BG;
	private static final Color POPUP_TEXT = com.leaguescape.util.LeagueScapeColors.POPUP_TEXT;
	private static final Color POPUP_BORDER = new Color(0x2a, 0x28, 0x24);
	private static final Dimension RECTANGLE_BUTTON_SIZE = new Dimension(160, 28);
	private static final int BASE_TILE_SIZE = 72;
	private static final int TILE_ICON_MARGIN = 12;
	private static final int CLAIMED_CHECKMARK_SIZE = 18;
	private static final int CLAIMED_CHECKMARK_INSET = 4;

	private static final String TASK_ICONS_RESOURCE_PREFIX = "/com/taskIcons/";
	private static final String BOSS_ICONS_RESOURCE_PREFIX = "/com/bossicons/";
	private static final String AREA_ICONS_RESOURCE_PREFIX = "/com/area_icons/";
	/** Area unlock tile id (from areas.json) -> area icon filename in com/area_icons/. Missing areas fall back to letter "A" icon.
	 *  Only isle_of_souls has no icon as of last check. */
	private static final Map<String, String> AREA_ICON_FILENAME = new HashMap<>();
	static
	{
		AREA_ICON_FILENAME.put("southeast_wilderness", "Wilderness_SE_icon.png");
		AREA_ICON_FILENAME.put("fossil_island", "Fossil_Island_icon.png");
		AREA_ICON_FILENAME.put("isle_of_souls", "Isle_Of_Souls_icon.png");
		AREA_ICON_FILENAME.put("silvarea", "Silvarea_icon.png");
		AREA_ICON_FILENAME.put("slepe", "Slepe_icon.png");
		AREA_ICON_FILENAME.put("grand_exchange", "Grand_Exchange_icon.png");
		AREA_ICON_FILENAME.put("edgeville", "Edgeville_icon.png");
		AREA_ICON_FILENAME.put("necropolis", "Necropolis_icon.png");
		AREA_ICON_FILENAME.put("lassar", "Lassar_icon.png");
		AREA_ICON_FILENAME.put("troll_country", "Troll_Country_icon.png");
		AREA_ICON_FILENAME.put("arceuus", "Arceuus_icon.png");
		AREA_ICON_FILENAME.put("northern_kourend", "Northern_Kourend_icon.png");
		AREA_ICON_FILENAME.put("tlati_rainforest", "Tlati_Rainforest_icon.png");
		AREA_ICON_FILENAME.put("lumbridge", "Lumbridge_icon.png");
		AREA_ICON_FILENAME.put("ape_atoll", "Ape_Atoll_icon.png");
		AREA_ICON_FILENAME.put("fremennik_isles", "Fremennik_Isles_icon.png");
		AREA_ICON_FILENAME.put("deep_wilderness", "Wilderness_Deep_icon.png");
		AREA_ICON_FILENAME.put("northeast_wilderness", "Wilderness_NE_icon.png");
		AREA_ICON_FILENAME.put("north_central_wilderness", "Wilderness_NC_icon.png");
		AREA_ICON_FILENAME.put("northwestern_wilderness", "Wilderness_NW_icon.png");
		AREA_ICON_FILENAME.put("southwestern_wilderness", "Wilderness_SW_icon.png");
		AREA_ICON_FILENAME.put("south_central_wilderness", "Wilderness_SC_icon.png");
		AREA_ICON_FILENAME.put("canifis", "Canifis_icon.png");
		AREA_ICON_FILENAME.put("haunted_wood", "Haunted_wood_icon.png");
		AREA_ICON_FILENAME.put("port_phasmatys", "Port_Phasmatys_icon.png");
		AREA_ICON_FILENAME.put("myreditch", "Myreditch_icon.png");
		AREA_ICON_FILENAME.put("darkmeyer", "Darkmeyer_icon.png");
		AREA_ICON_FILENAME.put("southern_morytania", "Southern_Morytania_icon.png");
		AREA_ICON_FILENAME.put("mort_myre_swamp", "Mort_Myre_swamp_icon.png");
		AREA_ICON_FILENAME.put("draynor", "Draynor_icon.png");
		AREA_ICON_FILENAME.put("al_kharid", "Al_Kharid_icon.png");
		AREA_ICON_FILENAME.put("desert", "Desert_icon.png");
		AREA_ICON_FILENAME.put("uzer_desert", "Uzer_Desert_icon.png");
		AREA_ICON_FILENAME.put("nardah_desert", "Nardah_Desert_icon.png");
		AREA_ICON_FILENAME.put("sophanem", "Sophanem_icon.png");
		AREA_ICON_FILENAME.put("polnivneach_desert", "Pollnivneach_icon.png");
		AREA_ICON_FILENAME.put("jaldraocht", "Jaldraocht_icon.png");
		AREA_ICON_FILENAME.put("ruins_of_unkah", "Ruins_of_Unkah_icon.png");
		AREA_ICON_FILENAME.put("falador", "Falador_icon.png");
		AREA_ICON_FILENAME.put("port_sarim_mudskipper", "Port_Sarim_icon.png");
		AREA_ICON_FILENAME.put("entrana", "Entrana_icon.png");
		AREA_ICON_FILENAME.put("pest_control", "Pest_Control_icon.png");
		AREA_ICON_FILENAME.put("musa_point", "Musa_Point_icon.png");
		AREA_ICON_FILENAME.put("karamja", "Karamja_icon.png");
		AREA_ICON_FILENAME.put("taverley", "Taverley_icon.png");
		AREA_ICON_FILENAME.put("burthorpe", "Burthorpe_icon.png");
		AREA_ICON_FILENAME.put("trollheim", "Trollheim_icon.png");
		AREA_ICON_FILENAME.put("weiss", "Weiss_icon.png");
		AREA_ICON_FILENAME.put("rellekka", "Rellekka_icon.png");
		AREA_ICON_FILENAME.put("camelot_seers", "Camelot_Seers_icon.png");
		AREA_ICON_FILENAME.put("hemenster", "Hemenster_icon.png");
		AREA_ICON_FILENAME.put("barbarian_waterfall", "Barbarian_waterfall.png");
		AREA_ICON_FILENAME.put("gnome_stronghold", "Gnome_stronghold_icon.png");
		AREA_ICON_FILENAME.put("piscatoris", "Piscatoris_icon.png");
		AREA_ICON_FILENAME.put("ardougne", "Ardougne_icon.png");
		AREA_ICON_FILENAME.put("varrock", "Varrock_icon.png");
		AREA_ICON_FILENAME.put("khazard_battlegrounds", "Khazard_icon.png");
		AREA_ICON_FILENAME.put("yanille", "Yanille_icon.png");
		AREA_ICON_FILENAME.put("feldip_hills", "Feldip_hills_icon.png");
		AREA_ICON_FILENAME.put("corsair_cove", "Corsair_Cove_icon.png");
		AREA_ICON_FILENAME.put("rimmington", "Rimmington_icon.png");
		AREA_ICON_FILENAME.put("isafdar", "Isafdar_icon.png");
		AREA_ICON_FILENAME.put("prifddinas", "Prifddinas_icon.png");
		AREA_ICON_FILENAME.put("port_piscarilius", "Port_Piscarilius_icon.png");
		AREA_ICON_FILENAME.put("lovakengj", "Lovakengj_icon.png");
		AREA_ICON_FILENAME.put("kingstown", "Kingstown_icon.png");
		AREA_ICON_FILENAME.put("hosidius", "Hosidius_icon.png");
		AREA_ICON_FILENAME.put("kourend_woodland", "Kourend_woodland_icon.png");
		AREA_ICON_FILENAME.put("shayzien", "Shayzien_icon.png");
		AREA_ICON_FILENAME.put("kebos_lowlands", "Kebos_LowLands_icon.png");
		AREA_ICON_FILENAME.put("kebos_swamp", "Kebos_Swamp_icon.png");
		AREA_ICON_FILENAME.put("custodia_mountains", "Custodia_Mountains_icon.png");
		AREA_ICON_FILENAME.put("auburnvale", "Auburnvale_icon.png");
		AREA_ICON_FILENAME.put("proudspire", "Proudspire_icon.png");
		AREA_ICON_FILENAME.put("western_varlamore", "Varlamore_W_icon.png");
		AREA_ICON_FILENAME.put("eastern_varlamore", "Varlamore_E_icon.png");
		AREA_ICON_FILENAME.put("aldarin", "Aldarin_icon.png");
		AREA_ICON_FILENAME.put("ardent_ne", "Ardent_NE_icon.png");
		AREA_ICON_FILENAME.put("ardent_nw", "Ardent_NW_icon.png");
		AREA_ICON_FILENAME.put("ardent_se", "Ardent_SE_icon.png");
		AREA_ICON_FILENAME.put("ardent_sw", "Ardent_SW_icon.png");
		AREA_ICON_FILENAME.put("unquiet", "Unquiet_icon.png");
		AREA_ICON_FILENAME.put("shrouded_e", "Shrouded_E_icon.png");
		AREA_ICON_FILENAME.put("shrouded_w", "Shrouded_W_icon.png");
		AREA_ICON_FILENAME.put("sunset", "Sunset_icon.png");
		AREA_ICON_FILENAME.put("western_s", "Western_S_icon.png");
		AREA_ICON_FILENAME.put("western_n", "Western_N_icon.png");
		AREA_ICON_FILENAME.put("northern_w", "Northern_W_icon.png");
		AREA_ICON_FILENAME.put("northern_e", "Northern_E_icon.png");
		AREA_ICON_FILENAME.put("mos_le_harmless", "Mos_Le_harmless_icon.png");
		AREA_ICON_FILENAME.put("catherby", "Catherby_icon.png");
	}
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
	private final Runnable onOpenTasks;
	private final Runnable onOpenRulesSetup;
	private final Consumer<String> onAreaUnlocked;
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
		Runnable onClose, Runnable onOpenTasks, Runnable onOpenRulesSetup, Consumer<String> onAreaUnlocked, Client client, AudioPlayer audioPlayer, JDialog parentDialog)
	{
		this.worldUnlockService = worldUnlockService;
		this.pointsService = pointsService;
		this.onClose = onClose;
		this.onOpenTasks = onOpenTasks;
		this.onOpenRulesSetup = onOpenRulesSetup;
		this.onAreaUnlocked = onAreaUnlocked;
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
		pointsLabel = new JLabel();
		pointsLabel.setForeground(POPUP_TEXT);
		titleRow.add(pointsLabel, BorderLayout.WEST);
		JLabel titleLabel = new JLabel("World Unlock");
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
		JPanel westButtons = new JPanel(new FlowLayout(FlowLayout.LEADING, 4, 0));
		westButtons.setOpaque(false);
		JButton tasksBtn = newRectangleButton("Tasks", buttonRect, POPUP_TEXT);
		tasksBtn.addActionListener(e -> {
			playSound(LeagueScapeSounds.BUTTON_PRESS);
			if (onClose != null) onClose.run();
			if (onOpenTasks != null) onOpenTasks.run();
		});
		westButtons.add(tasksBtn);
		JButton rulesSetupBtn = newRectangleButton("Rules & Setup", buttonRect, POPUP_TEXT);
		rulesSetupBtn.addActionListener(e -> {
			playSound(LeagueScapeSounds.BUTTON_PRESS);
			if (onOpenRulesSetup != null) onOpenRulesSetup.run();
		});
		westButtons.add(rulesSetupBtn);
		south.add(westButtons, BorderLayout.WEST);

		JButton showUnlocksBtn = newRectangleButton("Show Unlocks", buttonRect, POPUP_TEXT);
		showUnlocksBtn.addActionListener(e -> {
			playSound(LeagueScapeSounds.BUTTON_PRESS);
			showUnlocksDialog();
		});
		south.add(showUnlocksBtn, BorderLayout.CENTER);

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
		Set<String> claimed = worldUnlockService.getClaimedIds();
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
			if (!worldUnlockService.isRevealed(placement, claimed, grid))
				continue;

			WorldUnlockTile tile = placement.getTile();
			boolean isCenter = placement.getRow() == 0 && placement.getCol() == 0;
			boolean isUnlocked = unlocked.contains(tile.getId());
			boolean isClaimed = claimed.contains(tile.getId());

			BufferedImage tileIcon = loadUnlockTileIcon(tile, iconMaxFit);

			JPanel cell = buildTileCell(placement, isCenter, isUnlocked, isClaimed, tileIcon, tileSize, iconMargin, grid, claimed);
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
				raw = loadAreaIcon(tile.getId());
				if (raw == null)
					raw = createLetterIcon("A", iconMaxFit);
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

	/** For the Unlocks list: keep only the highest level bracket per skill (e.g. if Woodcutting 1-10, 11-20, 21-30 unlocked, keep only 21-30). */
	private static List<WorldUnlockTile> keepOnlyHighestSkillBracketPerSkill(List<WorldUnlockTile> tiles)
	{
		Map<String, WorldUnlockTile> bestBySkill = new HashMap<>();
		for (WorldUnlockTile t : tiles)
		{
			if (!"skill".equals(t.getType()) || t.getTaskLink() == null) continue;
			String skill = t.getTaskLink().getSkillName();
			if (skill == null) continue;
			int levelMax = t.getTaskLink().getLevelMax() != null ? t.getTaskLink().getLevelMax() : 0;
			WorldUnlockTile existing = bestBySkill.get(skill);
			int existingMax = existing != null && existing.getTaskLink() != null && existing.getTaskLink().getLevelMax() != null
				? existing.getTaskLink().getLevelMax() : -1;
			if (existing == null || levelMax > existingMax)
				bestBySkill.put(skill, t);
		}
		Set<String> keepSkillIds = new HashSet<>();
		for (WorldUnlockTile t : bestBySkill.values())
			keepSkillIds.add(t.getId());
		List<WorldUnlockTile> result = new ArrayList<>();
		for (WorldUnlockTile t : tiles)
		{
			if (!"skill".equals(t.getType()))
				result.add(t);
			else if (keepSkillIds.contains(t.getId()))
				result.add(t);
		}
		return result;
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

	/** Loads area tile icon from com/area_icons/; returns null if no icon for area or load fails. */
	private static BufferedImage loadAreaIcon(String areaId)
	{
		if (areaId == null || areaId.isEmpty()) return null;
		String filename = AREA_ICON_FILENAME.get(areaId);
		if (filename == null) return null;
		String path = AREA_ICONS_RESOURCE_PREFIX + filename;
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
		g.setColor(com.leaguescape.util.LeagueScapeColors.POPUP_TEXT_ALPHA);
		g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, Math.max(14, size - 4)));
		java.awt.FontMetrics fm = g.getFontMetrics();
		int x = (size - fm.stringWidth(letter)) / 2;
		int y = (size + fm.getAscent()) / 2 - 2;
		g.drawString(letter, x, y);
		g.dispose();
		return img;
	}

	private JPanel buildTileCell(WorldUnlockTilePlacement placement, boolean isCenter, boolean isUnlocked, boolean isClaimed,
		BufferedImage tileIcon, int tileSize, int iconMargin,
		List<WorldUnlockTilePlacement> grid, Set<String> claimed)
	{
		WorldUnlockTile tile = placement.getTile();

		if (isClaimed)
			return buildClaimedCell(tile, isCenter, tileIcon, tileSize, iconMargin);
		if (isUnlocked)
			return buildRevealedUnclaimedCell(tile, isCenter, tileIcon, tileSize, iconMargin);
		// else: revealed but not unlocked (locked) — padlock top-right, size scales with tile when zoomed
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
				if (padlock != null)
				{
					int w = getWidth(), h = getHeight();
					int s = Math.max(8, Math.min(w, h) / 4);
					int inset = Math.max(1, Math.min(w, h) / 18);
					int x = w - s - inset;
					int y = inset;
					Graphics2D g2 = (Graphics2D) g.create();
					g2.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, 0.35f));
					g2.drawImage(padlock.getScaledInstance(s, s, Image.SCALE_SMOOTH), x, y, null);
					g2.dispose();
				}
				super.paintComponent(g);
			}
		};
		cell.setLayout(new BorderLayout());
		cell.setOpaque(false);
		cell.setPreferredSize(new Dimension(tileSize, tileSize));

		if (tileIcon != null)
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

	/** Claimed = unlocked + action completed; shows icon (when present), dim overlay, and checkmark. */
	private JPanel buildClaimedCell(WorldUnlockTile tile, boolean isCenter, BufferedImage tileIcon, int tileSize, int iconMargin)
	{
		final BufferedImage bg = tileBg;
		final BufferedImage iconImage = tileIcon;
		final int margin = iconMargin;
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
				if (iconImage != null)
				{
					int w = getWidth(), h = getHeight();
					int innerW = Math.max(1, w - 2 * margin);
					int innerH = Math.max(1, h - 2 * margin);
					int iw = iconImage.getWidth(), ih = iconImage.getHeight();
					if (iw > 0 && ih > 0)
					{
						double scale = Math.min((double) innerW / iw, (double) innerH / ih);
						int drawW = Math.max(1, (int) Math.round(iw * scale));
						int drawH = Math.max(1, (int) Math.round(ih * scale));
						int x = margin + (innerW - drawW) / 2;
						int y = margin + (innerH - drawH) / 2;
						g.drawImage(iconImage.getScaledInstance(drawW, drawH, Image.SCALE_SMOOTH), x, y, null);
					}
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

	/** Revealed unclaimed = unlocked (paid) but not yet claimed. Same as locked but no padlock; claim after completing the action to reveal adjacent tiles. */
	private JPanel buildRevealedUnclaimedCell(WorldUnlockTile tile, boolean isCenter, BufferedImage tileIcon, int tileSize, int iconMargin)
	{
		final BufferedImage bg = tileBg;
		final BufferedImage iconImage = tileIcon;
		final int margin = iconMargin;

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
				if (iconImage != null)
				{
					int w = getWidth(), h = getHeight();
					int innerW = Math.max(1, w - 2 * margin);
					int innerH = Math.max(1, h - 2 * margin);
					int iw = iconImage.getWidth(), ih = iconImage.getHeight();
					if (iw > 0 && ih > 0)
					{
						double scale = Math.min((double) innerW / iw, (double) innerH / ih);
						int drawW = Math.max(1, (int) Math.round(iw * scale));
						int drawH = Math.max(1, (int) Math.round(ih * scale));
						int x = margin + (innerW - drawW) / 2;
						int y = margin + (innerH - drawH) / 2;
						g.drawImage(iconImage.getScaledInstance(drawW, drawH, Image.SCALE_SMOOTH), x, y, null);
					}
				}
			}
		};
		cell.setLayout(new BorderLayout());
		cell.setOpaque(false);
		cell.setPreferredSize(new Dimension(tileSize, tileSize));
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

	private void showUnlocksDialog()
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

		JDialog dialog = new JDialog(frameOwner, "Claimed Unlocks", false);
		dialog.setUndecorated(true);
		LeagueScapePlugin.registerEscapeToClose(dialog);

		JPanel content = new JPanel(new BorderLayout(8, 8));
		content.setBackground(POPUP_BG);
		content.setBorder(new CompoundBorder(
			new LineBorder(POPUP_BORDER, 2),
			new EmptyBorder(12, 14, 12, 14)));

		JPanel northPanel = new JPanel();
		northPanel.setLayout(new BoxLayout(northPanel, BoxLayout.Y_AXIS));
		northPanel.setOpaque(false);
		JPanel header = new JPanel(new BorderLayout(4, 0));
		header.setOpaque(false);
		JLabel titleLabel = new JLabel("Claimed Unlocks");
		titleLabel.setForeground(POPUP_TEXT);
		titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 13f));
		header.add(titleLabel, BorderLayout.CENTER);
		JButton closeBtn = newPopupButtonWithIcon(xBtnImg, POPUP_TEXT);
		closeBtn.addActionListener(e -> { playSound(LeagueScapeSounds.BUTTON_PRESS); dialog.dispose(); });
		header.add(closeBtn, BorderLayout.EAST);
		northPanel.add(header);
		// Filter: All, Skill, Area, Boss, Quest, Achievement diary
		String[] filterOptions = { "All", "Skill", "Area", "Boss", "Quest", "Achievement diary" };
		JComboBox<String> filterCombo = new JComboBox<>(filterOptions);
		filterCombo.setBackground(POPUP_BG);
		filterCombo.setForeground(POPUP_TEXT);
		filterCombo.setPreferredSize(new Dimension(180, 28));
		JPanel filterRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 4));
		filterRow.setOpaque(false);
		JLabel filterLabel = new JLabel("Filter by type:");
		filterLabel.setForeground(POPUP_TEXT);
		filterRow.add(filterLabel);
		filterRow.add(filterCombo);
		northPanel.add(filterRow);
		content.add(northPanel, BorderLayout.NORTH);

		JPanel listPanel = new JPanel();
		listPanel.setLayout(new BoxLayout(listPanel, BoxLayout.Y_AXIS));
		listPanel.setBackground(POPUP_BG);
		listPanel.setBorder(new EmptyBorder(4, 0, 4, 0));
		JScrollPane listScroll = new JScrollPane(listPanel);
		listScroll.setBorder(null);
		listScroll.setOpaque(false);
		listScroll.getViewport().setOpaque(false);
		listScroll.setPreferredSize(new Dimension(320, 220));
		content.add(listScroll, BorderLayout.CENTER);

		final int UNLOCK_LIST_ICON_SIZE = 24;
		java.util.function.Consumer<String> refreshList = filter -> {
			listPanel.removeAll();
			Set<String> unlockedIds = worldUnlockService.getUnlockedIds();
			List<WorldUnlockTile> tiles = new ArrayList<>();
			for (String id : unlockedIds)
			{
				WorldUnlockTile tile = worldUnlockService.getTileById(id);
				if (tile == null) continue;
				String type = tile.getType() != null ? tile.getType() : "";
				String filterType = null;
				if (!"All".equals(filter))
				{
					if ("Skill".equals(filter)) filterType = "skill";
					else if ("Area".equals(filter)) filterType = "area";
					else if ("Boss".equals(filter)) filterType = "boss";
					else if ("Quest".equals(filter)) filterType = "quest";
					else if ("Achievement diary".equals(filter)) filterType = "achievement_diary";
					if (filterType != null && !filterType.equals(type))
						continue;
				}
				tiles.add(tile);
			}
			tiles = keepOnlyHighestSkillBracketPerSkill(tiles);
			tiles.sort(Comparator
				.comparing(WorldUnlockTile::getType, Comparator.nullsFirst(String::compareTo))
				.thenComparing(t -> t.getDisplayName() != null ? t.getDisplayName() : t.getId(), String.CASE_INSENSITIVE_ORDER));
			for (WorldUnlockTile t : tiles)
			{
				String name = t.getDisplayName() != null ? t.getDisplayName() : t.getId();
				String typeStr = t.getType() != null ? capitalize(t.getType().replace("_", " ")) : "Unlock";
				JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
				row.setOpaque(false);
				BufferedImage icon = loadUnlockTileIcon(t, UNLOCK_LIST_ICON_SIZE);
				if (icon != null)
				{
					JLabel iconLabel = new JLabel(new ImageIcon(icon));
					iconLabel.setOpaque(false);
					row.add(iconLabel);
				}
				JLabel textLabel = new JLabel(name + "  ·  " + typeStr + "  ·  T" + t.getTier());
				textLabel.setForeground(POPUP_TEXT);
				row.add(textLabel);
				listPanel.add(row);
			}
			listPanel.revalidate();
			listPanel.repaint();
		};

		filterCombo.addItemListener(e -> {
			if (e.getStateChange() == java.awt.event.ItemEvent.SELECTED && e.getItem() != null)
				refreshList.accept(e.getItem().toString());
		});
		refreshList.accept("All");

		JButton closeBottomBtn = newRectangleButton("Close", buttonRect, POPUP_TEXT);
		closeBottomBtn.addActionListener(e -> { playSound(LeagueScapeSounds.BUTTON_PRESS); dialog.dispose(); });
		JPanel southPanel = new JPanel();
		southPanel.setOpaque(false);
		southPanel.add(closeBottomBtn);
		content.add(southPanel, BorderLayout.SOUTH);

		dialog.setContentPane(content);
		dialog.getRootPane().setBorder(new LineBorder(POPUP_BORDER, 2));
		dialog.pack();
		dialog.setLocationRelativeTo(parentDialog != null ? parentDialog : this);
		dialog.setVisible(true);
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

		// Use starter area display name from config/areas when this is the center (starter) tile so it matches Game Mode tab
		String windowTitle = isCenter && worldUnlockService.getTileCost(tile) == 0
			? worldUnlockService.getStarterAreaDisplayName()
			: (tile.getDisplayName() != null ? tile.getDisplayName() : tile.getId());
		if (windowTitle == null || windowTitle.isEmpty()) windowTitle = tile.getId();
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

		String typeLabel = tile.getType() != null ? capitalize(tile.getType().replace("_", " ")) : "Unlock";
		JLabel typeInfo = new JLabel("<html>" + typeLabel + " &middot; Tier " + tile.getTier() + "</html>");
		typeInfo.setForeground(POPUP_TEXT);
		body.add(typeInfo);

		int spendable = pointsService.getEarnedTotal() - pointsService.getSpentTotal();
		boolean canAfford = spendable >= worldUnlockService.getTileCost(tile);
		boolean prereqsMet = worldUnlockService.isUnlockable(tile);
		boolean alreadyUnlocked = worldUnlockService.getUnlockedIds().contains(tile.getId());
		boolean alreadyClaimed = worldUnlockService.getClaimedIds().contains(tile.getId());

		if (isCenter && !alreadyUnlocked)
		{
			String starterName = worldUnlockService.getStarterAreaDisplayName();
			String starterText = (starterName != null && !starterName.isEmpty())
				? "<html>This is your starting area (" + starterName + "). Unlock it for free!</html>"
				: "<html>This is your starting area. Unlock it for free!</html>";
			JLabel freeLabel = new JLabel(starterText);
			freeLabel.setForeground(POPUP_TEXT);
			body.add(freeLabel);
			body.add(new JLabel(" "));
			JButton unlockBtn = newRectangleButton("Unlock (Free)", buttonRect, POPUP_TEXT);
			unlockBtn.addActionListener(e -> {
				if (worldUnlockService.unlock(tile.getId(), worldUnlockService.getTileCost(tile)))
				{
					if (com.leaguescape.constants.WorldUnlockTileType.AREA.equals(tile.getType()) && onAreaUnlocked != null)
						onAreaUnlocked.accept(tile.getId());
					playSound(LeagueScapeSounds.TASK_COMPLETE);
					detail.dispose();
					SwingUtilities.invokeLater(this::refresh);
				}
			});
			body.add(unlockBtn);
		}
		else if (alreadyClaimed)
		{
			JLabel doneLabel = new JLabel("<html>Claimed. Adjacent tiles are revealed.</html>");
			doneLabel.setForeground(new Color(120, 200, 120));
			body.add(doneLabel);
		}
		else if (alreadyUnlocked)
		{
			JLabel actionLabel = new JLabel("<html>Complete the action for this unlock, then claim to reveal adjacent tiles.</html>");
			actionLabel.setForeground(POPUP_TEXT);
			body.add(actionLabel);
			body.add(new JLabel(" "));
			JButton claimBtn = newRectangleButton("Claim", buttonRect, POPUP_TEXT);
			claimBtn.addActionListener(e -> {
				if (worldUnlockService.claim(tile.getId()))
				{
					if (com.leaguescape.constants.WorldUnlockTileType.AREA.equals(tile.getType()) && onAreaUnlocked != null)
						onAreaUnlocked.accept(tile.getId());
					playSound(LeagueScapeSounds.TASK_COMPLETE);
					detail.dispose();
					SwingUtilities.invokeLater(this::refresh);
				}
			});
			body.add(claimBtn);
		}
		else
		{
			JLabel costLabel = new JLabel("Cost: " + worldUnlockService.getTileCost(tile) + " points");
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
				JButton unlockBtn = newRectangleButton("Unlock (" + worldUnlockService.getTileCost(tile) + " pts)", buttonRect, POPUP_TEXT);
				unlockBtn.addActionListener(e -> {
					if (worldUnlockService.unlock(tile.getId(), worldUnlockService.getTileCost(tile)))
					{
						if (com.leaguescape.constants.WorldUnlockTileType.AREA.equals(tile.getType()) && onAreaUnlocked != null)
							onAreaUnlocked.accept(tile.getId());
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
		JButton b = com.leaguescape.util.LeagueScapeSwingUtil.newRectangleButton(text, buttonRect, textColor);
		b.setPreferredSize(RECTANGLE_BUTTON_SIZE);
		return b;
	}

	private static JButton newPopupButtonWithIcon(BufferedImage iconImg, Color fallbackTextColor)
	{
		return com.leaguescape.util.LeagueScapeSwingUtil.newPopupButtonWithIcon(iconImg, fallbackTextColor);
	}
}

package com.leaguescape;

import com.leaguescape.area.AreaGraphService;
import com.leaguescape.data.Area;
import com.leaguescape.data.AreaStatus;
import com.leaguescape.points.AreaCompletionService;
import com.leaguescape.points.PointsService;
import java.awt.BorderLayout;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Set;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.border.EmptyBorder;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.ImageUtil;

public class LeagueScapePanel extends PluginPanel
{
	private static final String CONFIG_GROUP = "leaguescape";

	private final LeagueScapePlugin plugin;
	private final LeagueScapeConfig config;
	private final ConfigManager configManager;
	private final AreaGraphService areaGraphService;
	private final PointsService pointsService;
	private final AreaCompletionService areaCompletionService;

	private final JLabel currentAreaLabel;
	private final JLabel pointsLabel;
	private final JPanel unlockPanel;
	private final JPanel completionPanel;

	public LeagueScapePanel(LeagueScapePlugin plugin, LeagueScapeConfig config, ConfigManager configManager,
		AreaGraphService areaGraphService, PointsService pointsService, AreaCompletionService areaCompletionService)
	{
		this.plugin = plugin;
		this.config = config;
		this.configManager = configManager;
		this.areaGraphService = areaGraphService;
		this.pointsService = pointsService;
		this.areaCompletionService = areaCompletionService;
		setLayout(new BorderLayout());
		setBorder(new EmptyBorder(6, 6, 6, 6));

		JPanel content = new JPanel();
		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));

		JLabel title = new JLabel("LeagueScape");
		title.setAlignmentX(CENTER_ALIGNMENT);
		content.add(title);

		currentAreaLabel = new JLabel("Current area: " + config.startingArea());
		content.add(currentAreaLabel);

		pointsLabel = new JLabel();
		refreshPointsLabel();
		content.add(pointsLabel);

		content.add(new JLabel(" "));

		boolean pointsToComplete = config.unlockMode() == LeagueScapeConfig.UnlockMode.POINTS_TO_COMPLETE;
		if (pointsToComplete)
		{
			content.add(new JLabel("Complete areas (earn points in each):"));
			completionPanel = new JPanel();
			completionPanel.setLayout(new BoxLayout(completionPanel, BoxLayout.Y_AXIS));
			content.add(new JScrollPane(completionPanel));
			content.add(new JLabel("Next unlock (complete an area first):"));
		}
		else
		{
			completionPanel = null;
			content.add(new JLabel("Next unlock (affordable):"));
		}

		unlockPanel = new JPanel();
		unlockPanel.setLayout(new BoxLayout(unlockPanel, BoxLayout.Y_AXIS));
		content.add(unlockPanel);
		refreshUnlockButtons();

		content.add(new JLabel(" "));

		// Task system: difficulty and points per tier
		content.add(new JLabel("Task system"));
		JPanel taskPanel = new JPanel();
		taskPanel.setLayout(new BoxLayout(taskPanel, BoxLayout.Y_AXIS));
		JPanel difficultyRow = new JPanel(new BorderLayout());
		difficultyRow.add(new JLabel("Task difficulty (0.5=Easy, 1=Normal, 1.5=Hard):"), BorderLayout.WEST);
		JSpinner difficultySpinner = new JSpinner(new SpinnerNumberModel(config.taskDifficultyMultiplier(), 0.5, 2.0, 0.5));
		difficultySpinner.setMaximumSize(new java.awt.Dimension(80, difficultySpinner.getPreferredSize().height));
		difficultySpinner.addChangeListener(e -> configManager.setConfiguration(CONFIG_GROUP, "taskDifficultyMultiplier", ((Number) difficultySpinner.getValue()).doubleValue()));
		difficultyRow.add(difficultySpinner, BorderLayout.EAST);
		taskPanel.add(difficultyRow);
		for (int tier = 1; tier <= 5; tier++)
		{
			final int t = tier;
			int pts = tierPoints(tier);
			JPanel tierRow = new JPanel(new BorderLayout());
			tierRow.add(new JLabel("Tier " + tier + " points:"), BorderLayout.WEST);
			JSpinner tierSpinner = new JSpinner(new SpinnerNumberModel(pts, 0, 999, 1));
			tierSpinner.setMaximumSize(new java.awt.Dimension(80, tierSpinner.getPreferredSize().height));
			tierSpinner.addChangeListener(e -> configManager.setConfiguration(CONFIG_GROUP, "taskTier" + t + "Points", ((Number) tierSpinner.getValue()).intValue()));
			tierRow.add(tierSpinner, BorderLayout.EAST);
			taskPanel.add(tierRow);
		}
		content.add(taskPanel);

		content.add(new JLabel(" "));

		JButton worldMapBtn = new JButton("World Map");
		worldMapBtn.addActionListener(e -> { /* TODO: open map */ });
		content.add(worldMapBtn);

		JButton taskBoardBtn = new JButton("Task Board");
		taskBoardBtn.addActionListener(e -> { /* TODO: open task board */ });
		content.add(taskBoardBtn);

		JButton rulesSetupBtn = new JButton("Rules & Setup");
		rulesSetupBtn.addActionListener(e -> { /* TODO: open config / setup */ });
		content.add(rulesSetupBtn);

		add(content, BorderLayout.NORTH);
	}

	private void refreshPointsLabel()
	{
		pointsLabel.setText("Points: " + pointsService.getSpendable() + " / " + pointsService.getEarnedTotal());
	}

	private void refreshUnlockButtons()
	{
		unlockPanel.removeAll();
		Set<String> completedIds = (config.unlockMode() == LeagueScapeConfig.UnlockMode.POINTS_TO_COMPLETE)
			? areaCompletionService.getEffectiveCompletedAreaIds()
			: null;
		List<Area> unlockable = areaGraphService.getUnlockableNeighbors(completedIds);
		for (Area a : unlockable)
		{
			int cost = areaGraphService.getCost(a.getId());
			boolean canAfford = pointsService.getSpendable() >= cost;
			JButton unlockBtn = new JButton(a.getDisplayName() + " (" + cost + " pts)");
			unlockBtn.setEnabled(canAfford);
			if (canAfford)
			{
				unlockBtn.addActionListener(e -> unlockArea(a.getId(), cost));
			}
			unlockPanel.add(unlockBtn);
		}
		unlockPanel.revalidate();
		unlockPanel.repaint();
		if (completionPanel != null)
		{
			refreshCompletionPanel();
		}
	}

	private void refreshCompletionPanel()
	{
		if (completionPanel == null) return;
		completionPanel.removeAll();
		for (String areaId : areaGraphService.getUnlockedAreaIds())
		{
			Area area = areaGraphService.getArea(areaId);
			if (area == null) continue;
			int earned = areaCompletionService.getPointsEarnedInArea(areaId);
			int needed = areaCompletionService.getPointsToComplete(areaId);
			AreaStatus status = areaCompletionService.getAreaStatus(areaId);
			String text = area.getDisplayName() + ": " + earned + " / " + needed + " — " + status;
			if (status == AreaStatus.COMPLETE) text += " ✓";
			completionPanel.add(new JLabel(text));
		}
		completionPanel.revalidate();
		completionPanel.repaint();
	}

	public void refresh()
	{
		refreshPointsLabel();
		refreshUnlockButtons();
	}

	private void unlockArea(String areaId, int cost)
	{
		if (plugin.unlockArea(areaId, cost))
		{
			refreshPointsLabel();
			refreshCurrentAreaLabel(areaId);
			refreshUnlockButtons();
		}
	}

	private int tierPoints(int tier)
	{
		switch (tier)
		{
			case 1: return config.taskTier1Points();
			case 2: return config.taskTier2Points();
			case 3: return config.taskTier3Points();
			case 4: return config.taskTier4Points();
			case 5: return config.taskTier5Points();
			default: return tier;
		}
	}

	private void refreshCurrentAreaLabel(String areaId)
	{
		Area area = areaGraphService.getArea(areaId);
		String displayName = area != null ? area.getDisplayName() : areaId;
		currentAreaLabel.setText("Current area: " + displayName);
	}

	public BufferedImage getIcon()
	{
		BufferedImage icon = ImageUtil.loadImageResource(getClass(), "icon.png");
		if (icon != null)
		{
			return icon;
		}
		// Placeholder 16x16 until icon.png is added (max 48x72 for plugin hub)
		BufferedImage placeholder = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
		for (int x = 0; x < 16; x++)
			for (int y = 0; y < 16; y++)
				placeholder.setRGB(x, y, 0xFF00AA88); // teal
		return placeholder;
	}
}
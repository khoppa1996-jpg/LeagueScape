package com.leaguescape.config;

import com.leaguescape.LeagueScapeConfig;
import com.leaguescape.LeagueScapePlugin;
import com.leaguescape.area.AreaGraphService;
import com.leaguescape.data.Area;
import com.leaguescape.points.AreaCompletionService;
import com.leaguescape.points.PointsService;
import com.leaguescape.task.TaskGridService;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.border.EmptyBorder;
import net.runelite.api.Client;
import net.runelite.client.config.ConfigManager;

/**
 * Game Mode tab content: unlock mode, task system (mode + tier points), starting area,
 * starting points, reset progress. Styled with LeagueScape popup colors.
 */
public class LeagueScapeGameModeTabPanel extends JPanel
{
	private static final String CONFIG_GROUP = "leaguescape";
	/** Uniform width and height for all dropdowns when collapsed; height matches empty_button_rectangle.png. */
	private static final Dimension COMBO_SIZE = new Dimension(220, 28);

	public LeagueScapeGameModeTabPanel(LeagueScapePlugin plugin, ConfigManager configManager, LeagueScapeConfig config,
		AreaGraphService areaGraphService, PointsService pointsService, AreaCompletionService areaCompletionService,
		TaskGridService taskGridService, com.leaguescape.wiki.OsrsWikiApiService wikiApi,
		com.leaguescape.wiki.WikiTaskGenerator wikiTaskGenerator, Client client,
		Color bgColor, Color textColor, Function<String, JButton> buttonFactory)
	{
		boolean transparent = (bgColor != null && bgColor.getAlpha() == 0);
		setLayout(new BorderLayout());
		setBackground(bgColor);
		setOpaque(!transparent);

		JPanel content = new JPanel();
		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		content.setBackground(bgColor);
		content.setOpaque(!transparent);
		content.setBorder(new EmptyBorder(12, 12, 12, 12));

		// Unlock mode
		JPanel unlockRow = new JPanel(new BorderLayout());
		unlockRow.setBackground(bgColor);
		unlockRow.setOpaque(!transparent);
		JLabel unlockLabel = new JLabel("Unlock mode:");
		unlockLabel.setForeground(textColor);
		unlockRow.add(unlockLabel, BorderLayout.WEST);
		JComboBox<LeagueScapeConfig.UnlockMode> unlockCombo = new JComboBox<>(LeagueScapeConfig.UnlockMode.values());
		unlockCombo.setSelectedItem(config.unlockMode());
		sizeCombo(unlockCombo);
		styleCombo(unlockCombo, bgColor, textColor);
		unlockCombo.addItemListener(e -> {
			if (e.getStateChange() == java.awt.event.ItemEvent.SELECTED && e.getItem() instanceof LeagueScapeConfig.UnlockMode)
				configManager.setConfiguration(CONFIG_GROUP, "unlockMode", ((LeagueScapeConfig.UnlockMode) e.getItem()).name());
		});
		unlockRow.add(wrapComboFixedHeight(unlockCombo, bgColor), BorderLayout.EAST);
		content.add(unlockRow);

		// Task mode
		JPanel taskModeRow = new JPanel(new BorderLayout());
		taskModeRow.setBackground(bgColor);
		taskModeRow.setOpaque(!transparent);
		JLabel taskModeLabel = new JLabel("Task mode:");
		taskModeLabel.setForeground(textColor);
		taskModeRow.add(taskModeLabel, BorderLayout.WEST);
		JComboBox<LeagueScapeConfig.TaskMode> taskModeCombo = new JComboBox<>(LeagueScapeConfig.TaskMode.values());
		taskModeCombo.setSelectedItem(config.taskMode());
		sizeCombo(taskModeCombo);
		styleCombo(taskModeCombo, bgColor, textColor);
		taskModeCombo.addItemListener(e -> {
			if (e.getStateChange() == java.awt.event.ItemEvent.SELECTED && e.getItem() instanceof LeagueScapeConfig.TaskMode)
				configManager.setConfiguration(CONFIG_GROUP, "taskMode", ((LeagueScapeConfig.TaskMode) e.getItem()).name());
		});
		taskModeRow.add(wrapComboFixedHeight(taskModeCombo, bgColor), BorderLayout.EAST);
		content.add(taskModeRow);

		// Tier 1-5 points
		for (int tier = 1; tier <= 5; tier++)
		{
			final int t = tier;
			int pts = tierPoints(config, tier);
			JPanel tierRow = new JPanel(new BorderLayout());
			tierRow.setBackground(bgColor);
			tierRow.setOpaque(!transparent);
			JLabel tierLabel = new JLabel("Tier " + tier + " points:");
			tierLabel.setForeground(textColor);
			tierRow.add(tierLabel, BorderLayout.WEST);
			JSpinner tierSpinner = new JSpinner(new SpinnerNumberModel(pts, 0, 999, 1));
			tierSpinner.setMaximumSize(new Dimension(80, tierSpinner.getPreferredSize().height));
			styleSpinner(tierSpinner, bgColor, textColor);
			tierSpinner.addChangeListener(e -> configManager.setConfiguration(CONFIG_GROUP, "taskTier" + t + "Points", ((Number) tierSpinner.getValue()).intValue()));
			tierRow.add(tierSpinner, BorderLayout.EAST);
			content.add(tierRow);
		}

		// Starting area
		JPanel startAreaRow = new JPanel(new BorderLayout());
		startAreaRow.setBackground(bgColor);
		startAreaRow.setOpaque(!transparent);
		JLabel startAreaLabel = new JLabel("Starter area:");
		startAreaLabel.setForeground(textColor);
		startAreaRow.add(startAreaLabel, BorderLayout.WEST);
		JComboBox<String> startAreaCombo = new JComboBox<>();
		List<Area> areas = new ArrayList<>(areaGraphService.getAreas());
		areas.sort(Comparator.comparing((Area a) -> a.getDisplayName() != null ? a.getDisplayName() : a.getId(), String.CASE_INSENSITIVE_ORDER).thenComparing(Area::getId, String.CASE_INSENSITIVE_ORDER));
		for (Area a : areas)
			startAreaCombo.addItem(a.getDisplayName() != null ? a.getDisplayName() : a.getId());
		startAreaCombo.setSelectedItem(config.startingArea());
		// If config has ID but display list has display names, try to select by id
		String startId = config.startingArea();
		if (startId != null && !startId.isEmpty())
		{
			Area startArea = areaGraphService.getArea(startId);
			if (startArea != null)
			{
				String displayName = startArea.getDisplayName() != null ? startArea.getDisplayName() : startArea.getId();
				startAreaCombo.setSelectedItem(displayName);
			}
		}
		sizeCombo(startAreaCombo);
		styleCombo(startAreaCombo, bgColor, textColor);
		startAreaCombo.addItemListener(e -> {
			if (e.getStateChange() == java.awt.event.ItemEvent.SELECTED && e.getItem() != null)
			{
				String displayName = e.getItem().toString();
				// Find area id by display name
				for (Area a : areas)
					if ((a.getDisplayName() != null ? a.getDisplayName() : a.getId()).equals(displayName))
					{
						configManager.setConfiguration(CONFIG_GROUP, "startingArea", a.getId());
						break;
					}
			}
		});
		startAreaRow.add(wrapComboFixedHeight(startAreaCombo, bgColor), BorderLayout.EAST);
		content.add(startAreaRow);

		// Starting points
		JPanel startPointsRow = new JPanel(new BorderLayout());
		startPointsRow.setBackground(bgColor);
		startPointsRow.setOpaque(!transparent);
		JLabel startPointsLabel = new JLabel("Starting points:");
		startPointsLabel.setForeground(textColor);
		startPointsRow.add(startPointsLabel, BorderLayout.WEST);
		JSpinner startPointsSpinner = new JSpinner(new SpinnerNumberModel(config.startingPoints(), 0, 99999, 1));
		styleSpinner(startPointsSpinner, bgColor, textColor);
		startPointsSpinner.addChangeListener(e -> configManager.setConfiguration(CONFIG_GROUP, "startingPoints", ((Number) startPointsSpinner.getValue()).intValue()));
		startPointsRow.add(startPointsSpinner, BorderLayout.EAST);
		content.add(startPointsRow);

		content.add(new JLabel(" "));

		// Reset progress
		JButton resetBtn = buttonFactory.apply("Reset Progress");
		resetBtn.setAlignmentX(LEFT_ALIGNMENT);
		resetBtn.addActionListener(e -> showResetFlow(plugin, configManager, config, areaGraphService, pointsService, areaCompletionService, taskGridService, client));
		content.add(resetBtn);

		JScrollPane scroll = new JScrollPane(content);
		scroll.setBorder(null);
		scroll.getViewport().setBackground(bgColor);
		scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		add(scroll, BorderLayout.CENTER);
	}

	private static int tierPoints(LeagueScapeConfig config, int tier)
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

	private static void sizeCombo(JComboBox<?> combo)
	{
		combo.setPreferredSize(COMBO_SIZE);
		combo.setMinimumSize(COMBO_SIZE);
		combo.setMaximumSize(new Dimension(COMBO_SIZE.width, COMBO_SIZE.height));
	}

	/** Wraps a combo in a fixed-height panel so it does not stretch when the window is resized. */
	private static JPanel wrapComboFixedHeight(JComboBox<?> combo, Color bgColor)
	{
		JPanel wrap = new JPanel(new FlowLayout(FlowLayout.TRAILING, 0, 0));
		wrap.setPreferredSize(COMBO_SIZE);
		wrap.setMaximumSize(new Dimension(COMBO_SIZE.width, COMBO_SIZE.height));
		wrap.setMinimumSize(new Dimension(COMBO_SIZE.width, COMBO_SIZE.height));
		boolean transparent = (bgColor != null && bgColor.getAlpha() == 0);
		wrap.setBackground(bgColor);
		wrap.setOpaque(!transparent);
		wrap.add(combo);
		return wrap;
	}

	private static void styleCombo(JComboBox<?> combo, Color bg, Color fg)
	{
		combo.setBackground(bg);
		combo.setForeground(fg);
		// Use lightweight popup so the dropdown list does not use a separate window (avoids OS shadow under combos).
		combo.setLightWeightPopupEnabled(true);
		// Disable FlatLaf drop shadow painted under the dropdown popup.
		combo.putClientProperty("Popup.dropShadowPainted", false);
	}

	private static void styleSpinner(JSpinner spinner, Color bg, Color fg)
	{
		spinner.setBackground(bg);
		spinner.setForeground(fg);
		if (spinner.getEditor() instanceof JSpinner.DefaultEditor)
			((JSpinner.DefaultEditor) spinner.getEditor()).getTextField().setBackground(bg);
		((JSpinner.DefaultEditor) spinner.getEditor()).getTextField().setForeground(fg);
	}

	private static void showResetFlow(LeagueScapePlugin plugin, ConfigManager configManager, LeagueScapeConfig config,
		AreaGraphService areaGraphService, PointsService pointsService, AreaCompletionService areaCompletionService,
		TaskGridService taskGridService, Client client)
	{
		int confirm = JOptionPane.showConfirmDialog(null,
			"Reset all LeagueScape progress (points, area unlocks, and task completions)? This cannot be undone.",
			"Reset progress",
			JOptionPane.YES_NO_OPTION,
			JOptionPane.WARNING_MESSAGE);
		if (confirm != JOptionPane.YES_OPTION)
			return;
		String username = JOptionPane.showInputDialog(null,
			"Enter your account username to confirm:",
			"Confirm reset",
			JOptionPane.QUESTION_MESSAGE);
		if (username == null || username.isBlank())
			return;
		plugin.resetProgress();
		JOptionPane.showMessageDialog(null, "Progress has been reset.", "Reset complete", JOptionPane.INFORMATION_MESSAGE);
	}
}

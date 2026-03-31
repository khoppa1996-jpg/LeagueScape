package com.gridscape.config;

import com.gridscape.GridScapeConfig;
import com.gridscape.GridScapePlugin;
import com.gridscape.area.AreaGraphService;
import com.gridscape.data.Area;
import com.gridscape.points.AreaCompletionService;
import com.gridscape.points.PointsService;
import com.gridscape.task.TaskGridService;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
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
 * starting points, reset progress. Styled with GridScape popup colors.
 */
public class GridScapeGameModeTabPanel extends JPanel
{
	private static final String CONFIG_GROUP = com.gridscape.util.GridScapeConfigConstants.CONFIG_GROUP;
	/** Uniform width and height for all dropdowns and matching spinners in this tab. */
	private static final Dimension COMBO_SIZE = new Dimension(176, 28);
	/** Horizontal gap between setting label and the control column. */
	private static final int SETTINGS_ROW_HGAP = 10;
	/** Inset from the right edge of the panel to the right edge of controls (scales with width). */
	private static final int CONTROL_TRAILING_INSET = 8;

	private static final String[] WORLD_UNLOCK_MULT_TYPE_SUFFIX = {
		"Skill", "Area", "Boss", "Quest", "AchievementDiary"
	};
	/** Matches {@link GridScapeConfig} key names: worldUnlockTier{N}{Skill|Area|…}Multiplier */
	private static final String[] WORLD_UNLOCK_LEGACY_KEYS = {
		"worldUnlockSkillMultiplier",
		"worldUnlockAreaMultiplier",
		"worldUnlockBossMultiplier",
		"worldUnlockQuestMultiplier",
		"worldUnlockAchievementDiaryMultiplier"
	};
	private static final int[] WORLD_UNLOCK_TYPE_DEFAULTS = { 2, 7, 5, 2, 2 };

	static String worldUnlockPerTierKey(int tier, int typeIndex)
	{
		return "worldUnlockTier" + tier + WORLD_UNLOCK_MULT_TYPE_SUFFIX[typeIndex] + "Multiplier";
	}

	public GridScapeGameModeTabPanel(GridScapePlugin plugin, ConfigManager configManager, GridScapeConfig config,
		AreaGraphService areaGraphService, PointsService pointsService, AreaCompletionService areaCompletionService,
		TaskGridService taskGridService, com.gridscape.wiki.OsrsWikiApiService wikiApi,
		com.gridscape.wiki.WikiTaskGenerator wikiTaskGenerator, Client client,
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
		JLabel unlockLabel = new JLabel(GridScapeSetupStrings.GAME_MODE_UNLOCK_MODE);
		unlockLabel.setForeground(textColor);
		JComboBox<GridScapeConfig.UnlockMode> unlockCombo = new JComboBox<>(GridScapeConfig.UnlockMode.values());
		unlockCombo.setSelectedItem(config.unlockMode());
		styleCombo(unlockCombo, bgColor, textColor);
		JPanel worldUnlockMultipliersPanel = new JPanel();
		worldUnlockMultipliersPanel.setLayout(new BoxLayout(worldUnlockMultipliersPanel, BoxLayout.Y_AXIS));
		worldUnlockMultipliersPanel.setBackground(bgColor);
		worldUnlockMultipliersPanel.setOpaque(!transparent);
		JLabel worldUnlockMultLabel = new JLabel(GridScapeSetupStrings.GAME_MODE_WORLD_UNLOCK_COST_HTML);
		worldUnlockMultLabel.setForeground(textColor);
		worldUnlockMultLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
		worldUnlockMultipliersPanel.add(worldUnlockMultLabel);
		JLabel tierSelectLabel = new JLabel(GridScapeSetupStrings.GAME_MODE_MULTIPLIER_TIER);
		tierSelectLabel.setForeground(textColor);
		JComboBox<Integer> worldUnlockTierCombo = new JComboBox<>(new Integer[] { 1, 2, 3, 4, 5 });
		worldUnlockTierCombo.setSelectedItem(1);
		styleCombo(worldUnlockTierCombo, bgColor, textColor);
		JPanel tierSelectRow = formRow(tierSelectLabel, worldUnlockTierCombo, bgColor, transparent);
		worldUnlockMultipliersPanel.add(tierSelectRow);
		String[] multLabels = GridScapeSetupStrings.GAME_MODE_WORLD_UNLOCK_TYPE_LABELS;
		final JSpinner[] worldUnlockMultSpinners = new JSpinner[multLabels.length];
		final boolean[] suppressWorldUnlockMultWrite = { false };
		for (int i = 0; i < multLabels.length; i++)
		{
			final int typeIndex = i;
			int tier = worldUnlockTierCombo.getSelectedItem() instanceof Integer ? (Integer) worldUnlockTierCombo.getSelectedItem() : 1;
			int val = readWorldUnlockPerTierMultiplier(configManager, tier, typeIndex, WORLD_UNLOCK_TYPE_DEFAULTS[typeIndex]);
			JLabel lbl = new JLabel(multLabels[i]);
			lbl.setForeground(textColor);
			JSpinner spinner = new JSpinner(new SpinnerNumberModel(val, 1, 99, 1));
			styleSpinner(spinner, bgColor, textColor);
			spinner.addChangeListener(ev -> {
				if (suppressWorldUnlockMultWrite[0])
					return;
				Object sel = worldUnlockTierCombo.getSelectedItem();
				int t = sel instanceof Integer ? (Integer) sel : 1;
				configManager.setConfiguration(CONFIG_GROUP, worldUnlockPerTierKey(t, typeIndex), ((Number) spinner.getValue()).intValue());
			});
			JPanel multRow = formRow(lbl, spinner, bgColor, transparent);
			worldUnlockMultSpinners[i] = spinner;
			worldUnlockMultipliersPanel.add(multRow);
		}
		java.util.function.IntConsumer refreshWorldUnlockSpinnersForTier = tier -> {
			suppressWorldUnlockMultWrite[0] = true;
			try
			{
				for (int i = 0; i < worldUnlockMultSpinners.length; i++)
				{
					int v = readWorldUnlockPerTierMultiplier(configManager, tier, i, WORLD_UNLOCK_TYPE_DEFAULTS[i]);
					worldUnlockMultSpinners[i].setValue(v);
				}
			}
			finally
			{
				suppressWorldUnlockMultWrite[0] = false;
			}
		};
		worldUnlockTierCombo.addItemListener(e -> {
			if (e.getStateChange() == java.awt.event.ItemEvent.SELECTED && e.getItem() instanceof Integer)
				refreshWorldUnlockSpinnersForTier.accept((Integer) e.getItem());
		});
		JPanel wuPowerRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
		wuPowerRow.setBackground(bgColor);
		wuPowerRow.setOpaque(!transparent);
		wuPowerRow.setAlignmentX(Component.LEFT_ALIGNMENT);
		JButton resetTierBtn = buttonFactory.apply(GridScapeSetupStrings.GAME_MODE_RESET_TIER_DEFAULTS);
		resetTierBtn.addActionListener(e -> {
			Object sel = worldUnlockTierCombo.getSelectedItem();
			int t = sel instanceof Integer ? (Integer) sel : 1;
			for (int i = 0; i < WORLD_UNLOCK_MULT_TYPE_SUFFIX.length; i++)
				configManager.setConfiguration(CONFIG_GROUP, worldUnlockPerTierKey(t, i), WORLD_UNLOCK_TYPE_DEFAULTS[i]);
			refreshWorldUnlockSpinnersForTier.accept(t);
		});
		wuPowerRow.add(resetTierBtn);
		worldUnlockMultipliersPanel.add(wuPowerRow);
		worldUnlockMultipliersPanel.setVisible(config.unlockMode() == GridScapeConfig.UnlockMode.WORLD_UNLOCK);
		unlockCombo.addItemListener(e -> {
			if (e.getStateChange() == java.awt.event.ItemEvent.SELECTED && e.getItem() instanceof GridScapeConfig.UnlockMode)
			{
				GridScapeConfig.UnlockMode mode = (GridScapeConfig.UnlockMode) e.getItem();
				configManager.setConfiguration(CONFIG_GROUP, "unlockMode", mode.name());
				worldUnlockMultipliersPanel.setVisible(mode == GridScapeConfig.UnlockMode.WORLD_UNLOCK);
			}
		});
		JPanel unlockRow = formRow(unlockLabel, unlockCombo, bgColor, transparent);
		content.add(unlockRow);
		worldUnlockMultipliersPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
		content.add(worldUnlockMultipliersPanel);

		// Task mode
		JLabel taskModeLabel = new JLabel(GridScapeSetupStrings.GAME_MODE_TASK_MODE);
		taskModeLabel.setForeground(textColor);
		JComboBox<GridScapeConfig.TaskMode> taskModeCombo = new JComboBox<>(GridScapeConfig.TaskMode.values());
		taskModeCombo.setSelectedItem(config.taskMode());
		styleCombo(taskModeCombo, bgColor, textColor);
		taskModeCombo.addItemListener(e -> {
			if (e.getStateChange() == java.awt.event.ItemEvent.SELECTED && e.getItem() instanceof GridScapeConfig.TaskMode)
				configManager.setConfiguration(CONFIG_GROUP, "taskMode", ((GridScapeConfig.TaskMode) e.getItem()).name());
		});
		JPanel taskModeRow = formRow(taskModeLabel, taskModeCombo, bgColor, transparent);
		content.add(taskModeRow);

		// Tier 1-5 points
		final JSpinner[] tierSpinners = new JSpinner[5];
		for (int tier = 1; tier <= 5; tier++)
		{
			final int t = tier;
			int pts = tierPoints(config, tier);
			JLabel tierLabel = new JLabel(GridScapeSetupStrings.gameModeTierPointsLabel(tier));
			tierLabel.setForeground(textColor);
			JSpinner tierSpinner = new JSpinner(new SpinnerNumberModel(pts, 0, 999, 1));
			styleSpinner(tierSpinner, bgColor, textColor);
			tierSpinner.addChangeListener(e -> configManager.setConfiguration(CONFIG_GROUP, "taskTier" + t + "Points", ((Number) tierSpinner.getValue()).intValue()));
			JPanel tierRow = formRow(tierLabel, tierSpinner, bgColor, transparent);
			tierSpinners[tier - 1] = tierSpinner;
			content.add(tierRow);
		}

		// Starting area
		JLabel startAreaLabel = new JLabel(GridScapeSetupStrings.GAME_MODE_STARTER_AREA);
		startAreaLabel.setForeground(textColor);
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
		JPanel startAreaRow = formRow(startAreaLabel, startAreaCombo, bgColor, transparent);
		content.add(startAreaRow);

		// Starting points
		JLabel startPointsLabel = new JLabel(GridScapeSetupStrings.GAME_MODE_STARTING_POINTS);
		startPointsLabel.setForeground(textColor);
		JSpinner startPointsSpinner = new JSpinner(new SpinnerNumberModel(config.startingPoints(), 0, 99999, 1));
		styleSpinner(startPointsSpinner, bgColor, textColor);
		startPointsSpinner.addChangeListener(e -> configManager.setConfiguration(CONFIG_GROUP, "startingPoints", ((Number) startPointsSpinner.getValue()).intValue()));
		JPanel startPointsRow = formRow(startPointsLabel, startPointsSpinner, bgColor, transparent);
		content.add(startPointsRow);

		content.add(new JLabel(" "));

		// Update starting rules: apply current selections to config without resetting progress
		JButton updateRulesBtn = buttonFactory.apply(GridScapeSetupStrings.GAME_MODE_UPDATE_STARTING_RULES);
		updateRulesBtn.setAlignmentX(Component.LEFT_ALIGNMENT);
		updateRulesBtn.addActionListener(e -> showUpdateRulesFlow(this, configManager, unlockCombo, taskModeCombo, startAreaCombo, startPointsSpinner, tierSpinners, areas));
		content.add(updateRulesBtn);

		// Reset progress
		JButton resetBtn = buttonFactory.apply(GridScapeSetupStrings.GAME_MODE_RESET_PROGRESS);
		resetBtn.setAlignmentX(Component.LEFT_ALIGNMENT);
		resetBtn.addActionListener(e -> showResetFlow(this, plugin, configManager, config, areaGraphService, pointsService, areaCompletionService, taskGridService, client));
		content.add(resetBtn);

		JScrollPane scroll = new JScrollPane(content);
		scroll.setBorder(null);
		scroll.setOpaque(false);
		scroll.getViewport().setOpaque(false);
		scroll.getViewport().setBackground(bgColor);
		scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		add(scroll, BorderLayout.CENTER);
	}

	/**
	 * Label on the left; control on the right with {@link #CONTROL_TRAILING_INSET} px from the panel edge.
	 * Control uses {@link #COMBO_SIZE} so combos and spinners line up when the panel grows.
	 */
	private static JPanel formRow(JLabel label, JComponent control, Color bgColor, boolean transparent)
	{
		JPanel row = new JPanel(new GridBagLayout())
		{
			@Override
			public Dimension getMaximumSize()
			{
				return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
			}
		};
		row.setBackground(bgColor);
		row.setOpaque(!transparent);
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		uniformControlSize(control);

		GridBagConstraints left = new GridBagConstraints();
		left.gridx = 0;
		left.gridy = 0;
		left.weightx = 1.0;
		left.fill = GridBagConstraints.HORIZONTAL;
		left.anchor = GridBagConstraints.WEST;
		left.insets = new Insets(0, 0, 0, SETTINGS_ROW_HGAP);
		row.add(label, left);

		GridBagConstraints right = new GridBagConstraints();
		right.gridx = 1;
		right.gridy = 0;
		right.weightx = 0.0;
		right.fill = GridBagConstraints.NONE;
		right.anchor = GridBagConstraints.EAST;
		right.insets = new Insets(0, 0, 0, CONTROL_TRAILING_INSET);
		row.add(control, right);
		return row;
	}

	private static void uniformControlSize(JComponent control)
	{
		control.setPreferredSize(COMBO_SIZE);
		control.setMinimumSize(COMBO_SIZE);
		control.setMaximumSize(COMBO_SIZE);
	}

	private static int tierPoints(GridScapeConfig config, int tier)
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

	/**
	 * Reads per-tier multiplier; for tier 1 falls back to legacy global keys ({@code worldUnlockSkillMultiplier}, …) if
	 * the per-tier key was never set (migration from pre–per-tier config).
	 */
	private static int readWorldUnlockPerTierMultiplier(ConfigManager configManager, int tier, int typeIndex, int fallbackDefault)
	{
		String key = worldUnlockPerTierKey(tier, typeIndex);
		String raw = configManager.getConfiguration(CONFIG_GROUP, key);
		if (raw != null && !raw.trim().isEmpty())
		{
			try
			{
				return Math.max(1, Math.min(99, Integer.parseInt(raw.trim())));
			}
			catch (NumberFormatException ignored)
			{
			}
		}
		if (tier == 1 && typeIndex >= 0 && typeIndex < WORLD_UNLOCK_LEGACY_KEYS.length)
		{
			raw = configManager.getConfiguration(CONFIG_GROUP, WORLD_UNLOCK_LEGACY_KEYS[typeIndex]);
			if (raw != null && !raw.trim().isEmpty())
			{
				try
				{
					return Math.max(1, Math.min(99, Integer.parseInt(raw.trim())));
				}
				catch (NumberFormatException ignored)
				{
				}
			}
		}
		return Math.max(1, Math.min(99, fallbackDefault));
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
		spinner.putClientProperty("Popup.dropShadowPainted", false);
		if (spinner.getEditor() instanceof JSpinner.DefaultEditor)
			((JSpinner.DefaultEditor) spinner.getEditor()).getTextField().setBackground(bg);
		((JSpinner.DefaultEditor) spinner.getEditor()).getTextField().setForeground(fg);
	}

	private static void showUpdateRulesFlow(Component parent, ConfigManager configManager,
		JComboBox<GridScapeConfig.UnlockMode> unlockCombo, JComboBox<GridScapeConfig.TaskMode> taskModeCombo,
		JComboBox<String> startAreaCombo, JSpinner startPointsSpinner, JSpinner[] tierSpinners, List<Area> areas)
	{
		int choice = JOptionPane.showConfirmDialog(parent,
			GridScapeSetupStrings.GAME_MODE_UPDATE_RULES_CONFIRM,
			GridScapeSetupStrings.GAME_MODE_UPDATE_RULES_TITLE,
			JOptionPane.YES_NO_OPTION,
			JOptionPane.QUESTION_MESSAGE);
		if (choice != JOptionPane.YES_OPTION)
			return;
		applySelectionsToConfig(configManager, unlockCombo, taskModeCombo, startAreaCombo, startPointsSpinner, tierSpinners, areas);
		JOptionPane.showMessageDialog(parent, GridScapeSetupStrings.GAME_MODE_UPDATE_RULES_DONE, GridScapeSetupStrings.GAME_MODE_UPDATE_RULES_DONE_TITLE, JOptionPane.INFORMATION_MESSAGE);
	}

	private static void applySelectionsToConfig(ConfigManager configManager,
		JComboBox<GridScapeConfig.UnlockMode> unlockCombo, JComboBox<GridScapeConfig.TaskMode> taskModeCombo,
		JComboBox<String> startAreaCombo, JSpinner startPointsSpinner, JSpinner[] tierSpinners, List<Area> areas)
	{
		Object unlockSel = unlockCombo.getSelectedItem();
		if (unlockSel instanceof GridScapeConfig.UnlockMode)
			configManager.setConfiguration(CONFIG_GROUP, "unlockMode", ((GridScapeConfig.UnlockMode) unlockSel).name());
		Object taskModeSel = taskModeCombo.getSelectedItem();
		if (taskModeSel instanceof GridScapeConfig.TaskMode)
			configManager.setConfiguration(CONFIG_GROUP, "taskMode", ((GridScapeConfig.TaskMode) taskModeSel).name());
		Object startAreaSel = startAreaCombo.getSelectedItem();
		if (startAreaSel != null)
		{
			String displayName = startAreaSel.toString();
			for (Area a : areas)
			{
				String dn = a.getDisplayName() != null ? a.getDisplayName() : a.getId();
				if (dn.equals(displayName))
				{
					configManager.setConfiguration(CONFIG_GROUP, "startingArea", a.getId());
					break;
				}
			}
		}
		if (startPointsSpinner != null && startPointsSpinner.getValue() instanceof Number)
			configManager.setConfiguration(CONFIG_GROUP, "startingPoints", ((Number) startPointsSpinner.getValue()).intValue());
		if (tierSpinners != null)
		{
			for (int tier = 1; tier <= 5 && tier - 1 < tierSpinners.length; tier++)
			{
				JSpinner s = tierSpinners[tier - 1];
				if (s != null && s.getValue() instanceof Number)
					configManager.setConfiguration(CONFIG_GROUP, "taskTier" + tier + "Points", ((Number) s.getValue()).intValue());
			}
		}
	}

	private static void showResetFlow(Component parent, GridScapePlugin plugin, ConfigManager configManager, GridScapeConfig config,
		AreaGraphService areaGraphService, PointsService pointsService, AreaCompletionService areaCompletionService,
		TaskGridService taskGridService, Client client)
	{
		int choice = JOptionPane.showConfirmDialog(parent,
			GridScapeSetupStrings.GAME_MODE_RESET_CONFIRM,
			GridScapeSetupStrings.GAME_MODE_RESET_TITLE,
			JOptionPane.YES_NO_OPTION,
			JOptionPane.WARNING_MESSAGE);
		if (choice != JOptionPane.YES_OPTION)
			return;
		boolean confirmed = false;
		if (client != null && client.getLocalPlayer() != null && client.getLocalPlayer().getName() != null)
		{
			String expected = client.getLocalPlayer().getName();
			String typed = JOptionPane.showInputDialog(parent,
				GridScapeSetupStrings.GAME_MODE_RESET_INPUT_NAME,
				GridScapeSetupStrings.GAME_MODE_RESET_INPUT_NAME_TITLE,
				JOptionPane.WARNING_MESSAGE);
			confirmed = typed != null && typed.trim().equalsIgnoreCase(expected);
			if (!confirmed)
				JOptionPane.showMessageDialog(parent, GridScapeSetupStrings.GAME_MODE_RESET_NAME_MISMATCH, GridScapeSetupStrings.GAME_MODE_RESET_CANCELLED_TITLE, JOptionPane.INFORMATION_MESSAGE);
		}
		else
		{
			String typed = JOptionPane.showInputDialog(parent,
				GridScapeSetupStrings.GAME_MODE_RESET_INPUT_RESET,
				GridScapeSetupStrings.GAME_MODE_RESET_INPUT_NAME_TITLE,
				JOptionPane.WARNING_MESSAGE);
			confirmed = typed != null && "RESET".equals(typed.trim());
			if (!confirmed)
				JOptionPane.showMessageDialog(parent, GridScapeSetupStrings.GAME_MODE_RESET_CANCELLED, GridScapeSetupStrings.GAME_MODE_RESET_CANCELLED_TITLE, JOptionPane.INFORMATION_MESSAGE);
		}
		if (!confirmed)
			return;
		plugin.resetProgress();
		JOptionPane.showMessageDialog(parent, GridScapeSetupStrings.GAME_MODE_RESET_DONE, GridScapeSetupStrings.GAME_MODE_RESET_DONE_TITLE, JOptionPane.INFORMATION_MESSAGE);
	}
}

package com.leaguescape.config;

import com.leaguescape.LeagueScapeConfig;
import com.leaguescape.LeagueScapePlugin;
import com.leaguescape.area.AreaGraphService;
import com.leaguescape.data.Area;
import com.leaguescape.task.TaskDefinition;
import com.leaguescape.task.TaskGridService;
import com.leaguescape.wiki.OsrsWikiApiService;
import com.leaguescape.wiki.WikiTaskGenerator;
import com.leaguescape.wiki.WikiTaskSource;
import net.runelite.client.config.ConfigManager;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Frame;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JFileChooser;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JToggleButton;
import javax.swing.JComboBox;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.Scrollable;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.ImageUtil;
import java.awt.Container;
import java.awt.Rectangle;

/**
 * LeagueScape config/setup panel: area list (edit, remove, restore), "Make hole" for subtracting
 * regions from areas, task system settings (task mode, tier points, tasks file path),
 * task list with import/export and custom task add/edit (display name, type, difficulty, f2p, areas).
 * Areas and tasks are persisted via ConfigManager and AreaGraphService/TaskGridService. Opening
 * the panel does not reload areas/tasks from file until the user triggers reload or save.
 */
public class LeagueScapeConfigPanel extends PluginPanel
{
	/** Panel that stays within scroll viewport width (no horizontal scroll). */
	private static class ScrollableWidthPanel extends JPanel implements Scrollable
	{
		@Override
		public boolean getScrollableTracksViewportWidth() { return true; }
		@Override
		public boolean getScrollableTracksViewportHeight() { return false; }
		@Override
		public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
		@Override
		public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) { return 10; }
		@Override
		public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) { return visibleRect.height; }
	}

	private static JPanel newScrollableTrackWidthPanel()
	{
		return new ScrollableWidthPanel();
	}

	/** Builds a collapsible section: header (title + ▼/▶) and content panel. If headerOut is non-null, stores the header for external expand/collapse. */
	private JPanel createCollapsibleSection(String title, JPanel content, boolean expandedByDefault, JToggleButton[] headerOut)
	{
		JPanel wrapper = new JPanel(new BorderLayout());
		wrapper.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
		JToggleButton header = new JToggleButton(expandedByDefault ? "▼ " + title : "▶ " + title, expandedByDefault);
		header.setFocusPainted(false);
		header.setBorderPainted(false);
		header.setContentAreaFilled(false);
		header.setHorizontalAlignment(javax.swing.SwingConstants.LEFT);
		content.setVisible(expandedByDefault);
		final String titleFinal = title;
		header.addActionListener(e -> {
			boolean on = header.isSelected();
			content.setVisible(on);
			header.setText(on ? "▼ " + titleFinal : "▶ " + titleFinal);
			wrapper.revalidate();
			// Propagate revalidation up so the scroll pane view recalculates and the section returns to full height when expanded
			for (Container p = wrapper.getParent(); p != null; p = p.getParent())
			{
				p.revalidate();
			}
			wrapper.repaint();
		});
		wrapper.add(header, BorderLayout.NORTH);
		wrapper.add(content, BorderLayout.CENTER);
		if (headerOut != null && headerOut.length > 0)
			headerOut[0] = header;
		return wrapper;
	}

	private static final String CONFIG_GROUP = "leaguescape";

	private static final String[] TASK_TYPE_PRESETS = {
		"Achievement Diary", "Activity", "Quest", "Other", "Equipment", "Collection Log", "Clue Scroll", "Combat",
		"Agility", "Cooking", "Crafting", "Farming", "Firemaking", "Fletching", "Fishing", "Herblore", "Hunter",
		"Magic", "Mining", "Prayer", "Runecraft", "Slayer", "Smithing", "Sailing", "Thieving", "Woodcutting"
	};

	private final LeagueScapePlugin plugin;
	private final AreaGraphService areaGraphService;
	private final TaskGridService taskGridService;
	private final ConfigManager configManager;
	private final LeagueScapeConfig config;
	private final OsrsWikiApiService wikiApi;
	private final WikiTaskGenerator wikiTaskGenerator;
	private final ExecutorService helperExecutor = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "LeagueScape-TaskHelper");
		t.setDaemon(true);
		return t;
	});

	private JPanel mainPanel;
	private JPanel listPanel;
	private JPanel removedPanel;
	private JPanel editPanel;
	private JPanel makeHoleSectionPanel;
	private JToggleButton makeHoleSectionHeader;
	private JToggleButton editSectionHeader;
	private JTextField idField;
	private JTextField displayNameField;
	private JTextArea descriptionField;
	private JPanel cornersPanel;
	private JComboBox<String> makeHoleOuterCombo;
	private JComboBox<String> makeHoleInnerCombo;
	private JLabel holesCountLabel;
	private List<List<int[]>> editingHoles = new ArrayList<>();
	private JPanel holesListPanel;
	private JPanel neighborsPanel;
	private JSpinner unlockCostSpinner;
	private JSpinner pointsToCompleteSpinner;
	private JButton saveBtn;
	private JButton cancelBtn;

	private JPanel taskListPanel;
	private JPanel taskEditPanel;
	private JToggleButton taskEditSectionHeader;
	private JButton clearTasksOverrideBtn;
	private JTextField taskDisplayNameField;
	private JTextField taskTypeField;
	private JSpinner taskDifficultySpinner;
	private JCheckBox taskF2pCheckBox;
	private JPanel taskAreasPanel;
	private JTextField taskRequirementsField;
	private JComboBox<String> taskAreaRequirementCombo;
	private JCheckBox taskOnceOnlyCheckBox;
	private int editingTaskIndex = -1;

	public LeagueScapeConfigPanel(LeagueScapePlugin plugin, AreaGraphService areaGraphService, TaskGridService taskGridService,
		ConfigManager configManager, LeagueScapeConfig config, OsrsWikiApiService wikiApi, WikiTaskGenerator wikiTaskGenerator)
	{
		this.plugin = plugin;
		this.areaGraphService = areaGraphService;
		this.taskGridService = taskGridService;
		this.configManager = configManager;
		this.config = config;
		this.wikiApi = wikiApi;
		this.wikiTaskGenerator = wikiTaskGenerator;
		setLayout(new BorderLayout());
		setBorder(new EmptyBorder(6, 6, 6, 6));

		// Top section: title and action buttons (always visible, never scrolled)
		// Buttons stacked vertically so all show in narrow sidebar
		JPanel topSection = new JPanel();
		topSection.setLayout(new BoxLayout(topSection, BoxLayout.Y_AXIS));
		JLabel title = new JLabel("LeagueScape Area Config");
		title.setAlignmentX(CENTER_ALIGNMENT);
		topSection.add(title);
		topSection.add(new JLabel(" "));
		JButton addBtn = new JButton("Add new area");
		addBtn.addActionListener(e -> startEditingNew());
		addBtn.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
		topSection.add(addBtn);
		JButton importBtn = new JButton("Import Area JSON");
		importBtn.addActionListener(e -> importAreaJson());
		importBtn.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
		topSection.add(importBtn);
		JButton exportBtn = new JButton("Export Area JSON");
		exportBtn.addActionListener(e -> exportAreaJson());
		exportBtn.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
		topSection.add(exportBtn);
		add(topSection, BorderLayout.NORTH);

		// Scrollable content: collapsible sections for area list, removed list, edit form.
		mainPanel = new ScrollableWidthPanel();
		mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.Y_AXIS));

		listPanel = newScrollableTrackWidthPanel();
		listPanel.setLayout(new BoxLayout(listPanel, BoxLayout.Y_AXIS));
		JScrollPane listScroll = new JScrollPane(listPanel);
		listScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		JPanel areasContent = new JPanel(new BorderLayout());
		areasContent.add(listScroll, BorderLayout.CENTER);
		mainPanel.add(createCollapsibleSection("Areas", areasContent, true, null));

		mainPanel.add(new JLabel(" "));
		removedPanel = newScrollableTrackWidthPanel();
		removedPanel.setLayout(new BoxLayout(removedPanel, BoxLayout.Y_AXIS));
		JScrollPane removedScroll = new JScrollPane(removedPanel);
		removedScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		JPanel removedContent = new JPanel(new BorderLayout());
		removedContent.add(removedScroll, BorderLayout.CENTER);
		mainPanel.add(createCollapsibleSection("Removed areas (Restore to add back)", removedContent, false, null));

		editPanel = new JPanel();
		editPanel.setLayout(new BoxLayout(editPanel, BoxLayout.Y_AXIS));
		editPanel.setBorder(new EmptyBorder(10, 0, 0, 0));
		JToggleButton[] editHeaderRef = new JToggleButton[1];
		mainPanel.add(createCollapsibleSection("Edit area", editPanel, false, editHeaderRef));
		editSectionHeader = editHeaderRef[0];

		// Make hole section: stacked rows like Task system so nothing is clipped in narrow sidebar
		makeHoleSectionPanel = new JPanel();
		makeHoleSectionPanel.setLayout(new BoxLayout(makeHoleSectionPanel, BoxLayout.Y_AXIS));
		makeHoleSectionPanel.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
		makeHoleOuterCombo = new JComboBox<>();
		makeHoleInnerCombo = new JComboBox<>();
		JPanel removeRow = new JPanel(new BorderLayout());
		removeRow.add(new JLabel("Remove polygon"), BorderLayout.WEST);
		removeRow.add(makeHoleInnerCombo, BorderLayout.EAST);
		makeHoleSectionPanel.add(removeRow);
		JPanel fromRow = new JPanel(new BorderLayout());
		fromRow.add(new JLabel("from polygon"), BorderLayout.WEST);
		fromRow.add(makeHoleOuterCombo, BorderLayout.EAST);
		makeHoleSectionPanel.add(fromRow);
		JButton makeHoleBtn = new JButton("Make hole");
		makeHoleBtn.addActionListener(e -> applyMakeHole());
		makeHoleBtn.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
		makeHoleSectionPanel.add(makeHoleBtn);
		JToggleButton[] makeHoleHeaderRef = new JToggleButton[1];
		mainPanel.add(createCollapsibleSection("Make hole", makeHoleSectionPanel, false, makeHoleHeaderRef));
		makeHoleSectionHeader = makeHoleHeaderRef[0];

		mainPanel.add(new JLabel(" "));
		// Task system: task mode and points per tier
		JPanel taskSystemPanel = new JPanel();
		taskSystemPanel.setLayout(new BoxLayout(taskSystemPanel, BoxLayout.Y_AXIS));
		JPanel taskModeRow = new JPanel(new BorderLayout());
		taskModeRow.add(new JLabel("Task mode:"), BorderLayout.WEST);
		JComboBox<LeagueScapeConfig.TaskMode> taskModeCombo = new JComboBox<>(LeagueScapeConfig.TaskMode.values());
		taskModeCombo.setSelectedItem(config.taskMode());
		taskModeCombo.addItemListener(e -> {
			if (e.getStateChange() == java.awt.event.ItemEvent.SELECTED && e.getItem() instanceof LeagueScapeConfig.TaskMode)
				configManager.setConfiguration(CONFIG_GROUP, "taskMode", ((LeagueScapeConfig.TaskMode) e.getItem()).name());
		});
		taskModeRow.add(taskModeCombo, BorderLayout.EAST);
		taskSystemPanel.add(taskModeRow);
		for (int tier = 1; tier <= 5; tier++)
		{
			final int t = tier;
			int pts = tierPoints(tier);
			JPanel tierRow = new JPanel(new BorderLayout());
			tierRow.add(new JLabel("Tier " + tier + " points:"), BorderLayout.WEST);
			JSpinner tierSpinner = new JSpinner(new SpinnerNumberModel(pts, 0, 999, 1));
			tierSpinner.setMaximumSize(new Dimension(80, tierSpinner.getPreferredSize().height));
			tierSpinner.addChangeListener(e -> configManager.setConfiguration(CONFIG_GROUP, "taskTier" + t + "Points", ((Number) tierSpinner.getValue()).intValue()));
			tierRow.add(tierSpinner, BorderLayout.EAST);
			taskSystemPanel.add(tierRow);
		}
		taskSystemPanel.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
		mainPanel.add(createCollapsibleSection("Task system", taskSystemPanel, true, null));

		mainPanel.add(new JLabel(" "));
		JPanel tasksTop = new JPanel();
		tasksTop.setLayout(new BoxLayout(tasksTop, BoxLayout.Y_AXIS));
		JButton importTasksBtn = new JButton("Import Task JSON");
		importTasksBtn.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
		importTasksBtn.addActionListener(e -> importTaskJson());
		tasksTop.add(importTasksBtn);
		JButton exportTasksBtn = new JButton("Export Task JSON");
		exportTasksBtn.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
		exportTasksBtn.addActionListener(e -> exportTaskJson());
		tasksTop.add(exportTasksBtn);
		clearTasksOverrideBtn = new JButton("Clear imported tasks (use file/built-in again)");
		clearTasksOverrideBtn.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
		clearTasksOverrideBtn.addActionListener(e -> {
			taskGridService.clearTasksOverride();
			refreshTaskList();
		});
		tasksTop.add(clearTasksOverrideBtn);
		taskListPanel = newScrollableTrackWidthPanel();
		taskListPanel.setLayout(new BoxLayout(taskListPanel, BoxLayout.Y_AXIS));
		JScrollPane taskListScroll = new JScrollPane(taskListPanel);
		taskListScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		JPanel taskListContent = new JPanel(new BorderLayout());
		taskListContent.add(taskListScroll, BorderLayout.CENTER);
		JButton addTaskBtn = new JButton("Add custom task");
		addTaskBtn.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
		addTaskBtn.addActionListener(e -> startEditingNewTask());
		JPanel tasksContent = new JPanel();
		tasksContent.setLayout(new BoxLayout(tasksContent, BoxLayout.Y_AXIS));
		tasksContent.add(tasksTop);
		tasksContent.add(addTaskBtn);
		tasksContent.add(new JLabel("Custom tasks:"));
		tasksContent.add(taskListContent);
		taskEditPanel = new JPanel();
		taskEditPanel.setLayout(new BoxLayout(taskEditPanel, BoxLayout.Y_AXIS));
		taskEditPanel.setBorder(new EmptyBorder(10, 0, 0, 0));
		JToggleButton[] taskEditHeaderRef = new JToggleButton[1];
		mainPanel.add(createCollapsibleSection("Tasks", tasksContent, true, null));
		mainPanel.add(createCollapsibleSection("Edit task", taskEditPanel, false, taskEditHeaderRef));
		taskEditSectionHeader = taskEditHeaderRef[0];

		JPanel helperPanel = new JPanel(new BorderLayout());
		JButton openHelperBtn = new JButton("Open Task Creator Helper");
		openHelperBtn.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
		openHelperBtn.addActionListener(e -> openTaskCreatorHelperDialog());
		helperPanel.add(openHelperBtn, BorderLayout.NORTH);
		mainPanel.add(createCollapsibleSection("Task Creator Helper", helperPanel, false, null));

		JScrollPane scrollPane = new JScrollPane(mainPanel);
		scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
		add(scrollPane, BorderLayout.CENTER);
		refreshAreaList();
		refreshRemovedList();
		refreshTaskList();
	}

	private static final Comparator<Area> AREA_DISPLAY_ORDER = Comparator
		.comparing((Area a) -> a.getDisplayName() != null ? a.getDisplayName() : a.getId(), String.CASE_INSENSITIVE_ORDER)
		.thenComparing(Area::getId, String.CASE_INSENSITIVE_ORDER);

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

	private void refreshAreaList()
	{
		listPanel.removeAll();
		List<Area> areas = new ArrayList<>(areaGraphService.getAreas());
		areas.sort(AREA_DISPLAY_ORDER);
		for (Area a : areas)
		{
			JPanel row = new JPanel(new BorderLayout());
			String name = a.getDisplayName() != null ? a.getDisplayName() : a.getId();
			JLabel label = new JLabel(name);
			label.setToolTipText(name);
			label.setMinimumSize(new Dimension(0, 0));
			row.add(label, BorderLayout.CENTER);

			JPanel buttons = new JPanel();
			JButton editBtn = new JButton("Edit");
			editBtn.addActionListener(e -> startEditing(a.getId()));
			buttons.add(editBtn);
			JButton removeBtn = new JButton("Remove");
			removeBtn.addActionListener(e -> removeArea(a.getId()));
			buttons.add(removeBtn);
			row.add(buttons, BorderLayout.EAST);
			listPanel.add(row);
		}
		listPanel.revalidate();
		listPanel.repaint();
	}

	private void refreshRemovedList()
	{
		if (removedPanel == null) return;
		removedPanel.removeAll();
		List<String> removedIds = new ArrayList<>(areaGraphService.getRemovedAreaIds());
		removedIds.sort((a, b) -> {
			String na = getDisplayNameForRemoved(a);
			String nb = getDisplayNameForRemoved(b);
			return String.CASE_INSENSITIVE_ORDER.compare(na, nb);
		});
		for (String areaId : removedIds)
		{
			String displayName = getDisplayNameForRemoved(areaId);
			JPanel row = new JPanel(new BorderLayout());
			JLabel remLabel = new JLabel(displayName);
			remLabel.setToolTipText(displayName);
			remLabel.setMinimumSize(new Dimension(0, 0));
			row.add(remLabel, BorderLayout.CENTER);
			JButton restoreBtn = new JButton("Restore");
			restoreBtn.addActionListener(e -> {
				areaGraphService.restoreArea(areaId);
				refreshAreaList();
				refreshRemovedList();
			});
			row.add(restoreBtn, BorderLayout.EAST);
			removedPanel.add(row);
		}
		removedPanel.revalidate();
		removedPanel.repaint();
	}

	private void startEditingNew()
	{
		String tempId = "new_area_" + System.currentTimeMillis();
		plugin.startEditing(tempId, Collections.emptyList());
		plugin.setCornerUpdateCallback(this::refreshCornersDisplay);
		plugin.setNeighborUpdateCallback(this::refreshNeighborsFromPlugin);
		showEditForm(tempId, "", "", null, Collections.emptyList(), Collections.emptyList(), 0, 10, Collections.emptyList());
	}

	private void startEditing(String areaId)
	{
		Area a = areaGraphService.getArea(areaId);
		if (a == null) return;

		List<String> neighbors = a.getNeighbors() != null ? new ArrayList<>(a.getNeighbors()) : new ArrayList<>();
		List<int[]> firstPolygon = a.getPolygon() != null ? new ArrayList<>(a.getPolygon()) : new ArrayList<>();
		if (a.getPolygons() != null && a.getPolygons().size() > 1)
			plugin.startEditingWithPolygons(areaId, a.getPolygons());
		else
			plugin.startEditing(areaId, firstPolygon);
		plugin.setCornerUpdateCallback(this::refreshCornersDisplay);
		plugin.setNeighborUpdateCallback(this::refreshNeighborsFromPlugin);
		int ptsToComplete = a.getPointsToComplete() != null ? a.getPointsToComplete() : a.getUnlockCost();
		String desc = a.getDescription() != null ? a.getDescription() : "";
		List<List<int[]>> holes = new ArrayList<>();
		if (a.getHoles() != null)
			for (List<int[]> h : a.getHoles()) holes.add(new ArrayList<>(h));
		showEditForm(areaId, a.getId(), a.getDisplayName() != null ? a.getDisplayName() : "", desc, firstPolygon, neighbors, a.getUnlockCost(), ptsToComplete, holes);
	}

	private void showEditForm(String areaId, String id, String displayName, String description, List<int[]> corners, List<String> neighbors, int unlockCost, int pointsToComplete, List<List<int[]>> holes)
	{
		if (editSectionHeader != null)
		{
			editSectionHeader.setSelected(true);
			editSectionHeader.setText("▼ Edit area");
			editPanel.setVisible(true);
			if (makeHoleSectionHeader != null && makeHoleSectionPanel != null)
			{
				makeHoleSectionHeader.setSelected(true);
				makeHoleSectionHeader.setText("▼ Make hole");
				makeHoleSectionPanel.setVisible(true);
			}
			for (Container p = editSectionHeader.getParent(); p != null; p = p.getParent())
			{
				p.revalidate();
			}
		}
		editPanel.removeAll();
		editingHoles.clear();
		if (holes != null) for (List<int[]> h : holes) editingHoles.add(new ArrayList<>(h));

		editPanel.add(new JLabel("Area ID (slug, e.g. " + (areaId.startsWith("new_") ? "lumbridge):" : "):")));
		idField = new JTextField(id.startsWith("new_") ? "" : id, 12);
		idField.setEditable(areaId.startsWith("new_"));
		idField.setMaximumSize(new Dimension(Integer.MAX_VALUE, idField.getPreferredSize().height));
		editPanel.add(idField);

		editPanel.add(new JLabel("Display name:"));
		displayNameField = new JTextField(displayName, 12);
		displayNameField.setMaximumSize(new Dimension(Integer.MAX_VALUE, displayNameField.getPreferredSize().height));
		editPanel.add(displayNameField);

		editPanel.add(new JLabel("Description (shown in Area Details on world map):"));
		descriptionField = new JTextArea(description != null ? description : "", 3, 20);
		descriptionField.setLineWrap(true);
		descriptionField.setWrapStyleWord(true);
		descriptionField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 80));
		editPanel.add(new JScrollPane(descriptionField));

		editPanel.add(new JLabel(" "));
		JLabel cornersHint = new JLabel("<html>Corners: Shift+RMB to add; Shift+RMB corner to Move, then Set.</html>");
		cornersHint.setToolTipText("Shift+Right-click to add corner; Shift+Right-click a corner to Move it, then click another tile to Set.");
		editPanel.add(cornersHint);
		cornersPanel = new JPanel();
		cornersPanel.setLayout(new BoxLayout(cornersPanel, BoxLayout.Y_AXIS));
		JScrollPane cornersScroll = new JScrollPane(cornersPanel);
		cornersScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		int maxCornersHeight = 200;
		cornersScroll.setPreferredSize(new Dimension(Integer.MAX_VALUE, maxCornersHeight));
		cornersScroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, maxCornersHeight));
		editPanel.add(cornersScroll);

		editPanel.add(new JLabel(" "));
		holesCountLabel = new JLabel();
		editPanel.add(holesCountLabel);
		holesListPanel = new JPanel();
		holesListPanel.setLayout(new BoxLayout(holesListPanel, BoxLayout.Y_AXIS));
		JScrollPane holesScroll = new JScrollPane(holesListPanel);
		int maxHolesHeight = 80;
		holesScroll.setPreferredSize(new Dimension(Integer.MAX_VALUE, maxHolesHeight));
		holesScroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, maxHolesHeight));
		editPanel.add(holesScroll);
		refreshHolesDisplay();

		refreshMakeHoleCombos();

		editPanel.add(new JLabel(" "));
		editPanel.add(new JLabel("Neighbors:"));
		neighborsPanel = new JPanel();
		neighborsPanel.setLayout(new BoxLayout(neighborsPanel, BoxLayout.Y_AXIS));
		List<String> neighborsToShow = (plugin.getEditingNeighbors() != null) ? plugin.getEditingNeighbors() : neighbors;
		List<Area> others = new ArrayList<>(areaGraphService.getAreas());
		others.removeIf(a -> a.getId().equals(areaId));
		others.sort(AREA_DISPLAY_ORDER);
		for (Area other : others)
		{
			JCheckBox cb = new JCheckBox(other.getDisplayName() != null ? other.getDisplayName() : other.getId());
			cb.setName(other.getId()); // Store id for lookup
			cb.setSelected(neighborsToShow.contains(other.getId()));
			cb.addItemListener(e -> syncNeighborsToPlugin());
			neighborsPanel.add(cb);
		}
		JScrollPane neighborsScroll = new JScrollPane(neighborsPanel);
		neighborsScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		int maxNeighborsHeight = 120;
		neighborsScroll.setPreferredSize(new Dimension(Integer.MAX_VALUE, maxNeighborsHeight));
		neighborsScroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, maxNeighborsHeight));
		editPanel.add(neighborsScroll);

		editPanel.add(new JLabel("Unlock cost (points to spend to unlock this area):"));
		unlockCostSpinner = new JSpinner(new SpinnerNumberModel(unlockCost, 0, 9999, 1));
		editPanel.add(unlockCostSpinner);

		editPanel.add(new JLabel("Points to complete (points to earn in this area to complete it; used in Points-to-complete mode):"));
		pointsToCompleteSpinner = new JSpinner(new SpinnerNumberModel(pointsToComplete, 0, 9999, 1));
		editPanel.add(pointsToCompleteSpinner);

		editPanel.add(new JLabel(" "));
		JPanel saveCancel = new JPanel();
		saveBtn = new JButton("Save");
		saveBtn.addActionListener(e -> saveArea(areaId));
		cancelBtn = new JButton("Cancel");
		cancelBtn.addActionListener(e -> cancelEdit());
		saveCancel.add(saveBtn);
		saveCancel.add(cancelBtn);
		editPanel.add(saveCancel);

		refreshCornersDisplay(plugin.getEditingCorners());
		editPanel.revalidate();
		editPanel.repaint();
	}

	private void refreshMakeHoleCombos()
	{
		if (makeHoleOuterCombo == null || makeHoleInnerCombo == null) return;
		makeHoleOuterCombo.removeAllItems();
		makeHoleInnerCombo.removeAllItems();
		List<List<int[]>> all = plugin.getAllEditingPolygons();
		for (int i = 0; i < all.size(); i++)
		{
			String label = "Polygon " + (i + 1) + " (" + all.get(i).size() + " pts)";
			makeHoleOuterCombo.addItem(label);
			makeHoleInnerCombo.addItem(label);
		}
	}

	private void applyMakeHole()
	{
		if (makeHoleOuterCombo == null || makeHoleInnerCombo == null) return;
		int outerIdx = makeHoleOuterCombo.getSelectedIndex();
		int innerIdx = makeHoleInnerCombo.getSelectedIndex();
		if (outerIdx < 0 || innerIdx < 0) return;
		if (outerIdx == innerIdx)
		{
			javax.swing.JOptionPane.showMessageDialog(this, "Choose different polygons: outer and inner must differ.", "Make hole", javax.swing.JOptionPane.WARNING_MESSAGE);
			return;
		}
		List<List<int[]>> all = plugin.getAllEditingPolygons();
		if (outerIdx >= all.size() || innerIdx >= all.size()) return;
		List<int[]> outerPoly = all.get(outerIdx);
		List<int[]> innerPoly = all.get(innerIdx);
		if (outerPoly.size() < 3 || innerPoly.size() < 3)
		{
			javax.swing.JOptionPane.showMessageDialog(this, "Both polygons need at least 3 vertices.", "Make hole", javax.swing.JOptionPane.WARNING_MESSAGE);
			return;
		}
		if (!areaGraphService.isPolygonInsidePolygon(outerPoly, innerPoly))
		{
			javax.swing.JOptionPane.showMessageDialog(this, "The inner polygon must be entirely inside the outer polygon (e.g. island inside ocean).", "Make hole", javax.swing.JOptionPane.WARNING_MESSAGE);
			return;
		}
		List<int[]> removed = plugin.removeEditingPolygonAt(innerIdx);
		if (removed != null)
		{
			editingHoles.add(removed);
			plugin.setEditingHoles(editingHoles);
			refreshMakeHoleCombos();
			refreshCornersDisplay(plugin.getEditingCorners());
			refreshHolesDisplay();
			editPanel.revalidate();
			editPanel.repaint();
		}
	}

	private void syncNeighborsToPlugin()
	{
		if (neighborsPanel == null) return;
		List<String> selected = new ArrayList<>();
		for (int i = 0; i < neighborsPanel.getComponentCount(); i++)
		{
			if (neighborsPanel.getComponent(i) instanceof JCheckBox)
			{
				JCheckBox cb = (JCheckBox) neighborsPanel.getComponent(i);
				if (cb.isSelected() && cb.getName() != null) selected.add(cb.getName());
			}
		}
		plugin.setEditingNeighbors(selected);
	}

	private void refreshNeighborsFromPlugin(List<String> neighborIds)
	{
		if (neighborsPanel == null || neighborIds == null) return;
		for (int i = 0; i < neighborsPanel.getComponentCount(); i++)
		{
			if (neighborsPanel.getComponent(i) instanceof JCheckBox)
			{
				JCheckBox cb = (JCheckBox) neighborsPanel.getComponent(i);
				if (cb.getName() != null) cb.setSelected(neighborIds.contains(cb.getName()));
			}
		}
	}

	private void refreshHolesDisplay()
	{
		if (holesCountLabel == null || holesListPanel == null) return;
		holesCountLabel.setText("Holes (cut out): " + editingHoles.size());
		holesListPanel.removeAll();
		for (int i = 0; i < editingHoles.size(); i++)
		{
			List<int[]> h = editingHoles.get(i);
			holesListPanel.add(new JLabel("  Hole " + (i + 1) + ": " + h.size() + " vertices"));
		}
		holesListPanel.revalidate();
		holesListPanel.repaint();
	}

	private void refreshCornersDisplay(List<int[]> corners)
	{
		if (cornersPanel == null) return;
		cornersPanel.removeAll();
		for (int i = 0; i < corners.size(); i++)
		{
			int[] p = corners.get(i);
			JPanel row = new JPanel(new BorderLayout());
			// Button on left so it stays visible without horizontal scroll
			JButton removeBtn = new JButton("Remove");
			removeBtn.setActionCommand(String.valueOf(i));
			removeBtn.addActionListener(e -> {
				Object src = e.getSource();
				if (src instanceof JButton)
				{
					try
					{
						int index = Integer.parseInt(((JButton) src).getActionCommand());
						removeCorner(index);
					}
					catch (NumberFormatException ignored) { }
				}
			});
			row.add(removeBtn, BorderLayout.WEST);
			row.add(new JLabel("  " + (i + 1) + ": " + p[0] + ", " + p[1] + ", " + p[2]), BorderLayout.CENTER);
			cornersPanel.add(row);
		}
		cornersPanel.revalidate();
		cornersPanel.repaint();
		if (makeHoleOuterCombo != null) refreshMakeHoleCombos();
		// Sync holes from plugin so "Fill using others' corners" updates the panel's holes list
		if (plugin.getEditingHoles() != null)
		{
			editingHoles.clear();
			for (List<int[]> h : plugin.getEditingHoles()) editingHoles.add(new ArrayList<>(h));
			refreshHolesDisplay();
		}
	}

	private void removeCorner(int index)
	{
		if (index >= 0 && index < plugin.getEditingCorners().size())
		{
			plugin.removeCorner(index);
			refreshCornersDisplay(plugin.getEditingCorners());
		}
	}

	private void saveArea(String oldAreaId)
	{
		String id = idField.getText().trim().toLowerCase().replaceAll("[^a-z0-9_]", "_");
		if (id.isEmpty()) id = "area_" + System.currentTimeMillis();

		String displayName = displayNameField.getText().trim();
		if (displayName.isEmpty()) displayName = id;
		String description = descriptionField.getText().trim();
		if (description.isEmpty()) description = null;

		List<List<int[]>> allPolygons = plugin.getAllEditingPolygons();
		if (allPolygons.isEmpty() || allPolygons.stream().anyMatch(p -> p.size() < 3))
		{
			// Need at least one polygon with >= 3 corners
			return;
		}

		List<String> neighborsToSave = new ArrayList<>();
		if (plugin.getEditingNeighbors() != null)
			neighborsToSave.addAll(plugin.getEditingNeighbors());
		else if (neighborsPanel != null)
		{
			for (int i = 0; i < neighborsPanel.getComponentCount(); i++)
			{
				if (neighborsPanel.getComponent(i) instanceof JCheckBox)
				{
					JCheckBox cb = (JCheckBox) neighborsPanel.getComponent(i);
					if (cb.isSelected() && cb.getName() != null) neighborsToSave.add(cb.getName());
				}
			}
		}

		int cost = (Integer) unlockCostSpinner.getValue();
		int ptsToComplete = (Integer) pointsToCompleteSpinner.getValue();

		// Use plugin's holes so "Fill using others' corners" and Make hole are both persisted
		List<List<int[]>> holesToSave = new ArrayList<>();
		List<List<int[]>> sourceHoles = plugin.getEditingHoles() != null ? plugin.getEditingHoles() : editingHoles;
		for (List<int[]> h : sourceHoles) holesToSave.add(new ArrayList<>(h));

		Area area = Area.builder()
			.id(id)
			.displayName(displayName)
			.description(description)
			.polygons(allPolygons)
			.holes(holesToSave)
			.includes(Collections.emptyList()) // Computed by AreaGraphService
			.neighbors(neighborsToSave)
			.unlockCost(cost)
			.pointsToComplete(ptsToComplete)
			.build();

		areaGraphService.saveCustomArea(area);
		plugin.stopEditing();
		clearEditForm();
		refreshAreaList();
	}

	private void importAreaJson()
	{
		JFileChooser chooser = new JFileChooser();
		chooser.setDialogTitle("Import Area JSON");
		chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("JSON files", "json"));

		if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION)
		{
			File file = chooser.getSelectedFile();
			try
			{
				String json = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
				int count = areaGraphService.importCustomAreasFromJson(json);
				if (plugin.getEditingAreaId() != null)
				{
					plugin.stopEditing();
					clearEditForm();
				}
				refreshAreaList();
				refreshRemovedList();
				javax.swing.JOptionPane.showMessageDialog(this,
					"Imported " + count + " areas from " + file.getName() + ".\nImported areas replace your custom areas; built-in areas are unchanged.",
					"Import Complete",
					javax.swing.JOptionPane.INFORMATION_MESSAGE);
			}
			catch (IllegalArgumentException ex)
			{
				javax.swing.JOptionPane.showMessageDialog(this,
					"Invalid area JSON:\n\n" + ex.getMessage(),
					"Import Error",
					javax.swing.JOptionPane.ERROR_MESSAGE);
			}
			catch (Exception ex)
			{
				javax.swing.JOptionPane.showMessageDialog(this,
					"Import failed: " + ex.getMessage(),
					"Import Error",
					javax.swing.JOptionPane.ERROR_MESSAGE);
			}
		}
	}

	private void exportAreaJson()
	{
		JFileChooser chooser = new JFileChooser();
		chooser.setDialogTitle("Export Area JSON");
		chooser.setSelectedFile(new File("areas.json"));
		chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("JSON files", "json"));

		if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION)
		{
			File file = chooser.getSelectedFile();
			if (!file.getName().toLowerCase().endsWith(".json"))
			{
				file = new File(file.getParent(), file.getName() + ".json");
			}
			try
			{
				String json = areaGraphService.exportAreasToJson();
				Files.write(file.toPath(), json.getBytes(StandardCharsets.UTF_8));
				javax.swing.JOptionPane.showMessageDialog(this,
					"Exported " + areaGraphService.getAreas().size() + " areas to " + file.getName(),
					"Export Complete",
					javax.swing.JOptionPane.INFORMATION_MESSAGE);
			}
			catch (Exception ex)
			{
				javax.swing.JOptionPane.showMessageDialog(this,
					"Export failed: " + ex.getMessage(),
					"Export Error",
					javax.swing.JOptionPane.ERROR_MESSAGE);
			}
		}
	}

	private void refreshTaskList()
	{
		if (clearTasksOverrideBtn != null)
			clearTasksOverrideBtn.setVisible(taskGridService.hasTasksOverride());
		if (taskListPanel == null) return;
		taskListPanel.removeAll();
		List<TaskDefinition> custom = taskGridService.getCustomTasks();
		for (int i = 0; i < custom.size(); i++)
		{
			final int index = i;
			TaskDefinition t = custom.get(i);
			String name = t.getDisplayName() != null ? t.getDisplayName() : "(no name)";
			String type = t.getTaskType() != null ? t.getTaskType() : "";
			int diff = t.getDifficulty();
			List<String> areaIds = t.getRequiredAreaIds();
			String areaSummary = areaIds.isEmpty() ? "any" : String.join(", ", areaIds);
			JPanel row = new JPanel(new BorderLayout());
			JLabel label = new JLabel(name + "  |  " + type + "  |  " + diff + "  |  " + areaSummary);
			label.setToolTipText(name);
			label.setMinimumSize(new Dimension(0, 0));
			row.add(label, BorderLayout.CENTER);
			JPanel btns = new JPanel();
			JButton editTaskBtn = new JButton("Edit");
			editTaskBtn.addActionListener(e -> startEditingTask(index));
			btns.add(editTaskBtn);
			JButton removeTaskBtn = new JButton("Remove");
			removeTaskBtn.addActionListener(e -> {
				taskGridService.removeCustomTask(index);
				refreshTaskList();
			});
			btns.add(removeTaskBtn);
			row.add(btns, BorderLayout.EAST);
			taskListPanel.add(row);
		}
		taskListPanel.revalidate();
		taskListPanel.repaint();
	}

	private void importTaskJson()
	{
		JFileChooser chooser = new JFileChooser();
		chooser.setDialogTitle("Import Task JSON");
		chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("JSON files", "json"));
		if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
		File file = chooser.getSelectedFile();
		try
		{
			String json = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
			taskGridService.setTasksOverride(json);
			refreshTaskList();
			javax.swing.JOptionPane.showMessageDialog(this,
				"Imported tasks from " + file.getName() + ". They will be used instead of the file or built-in list until you clear the override.",
				"Import Complete", javax.swing.JOptionPane.INFORMATION_MESSAGE);
		}
		catch (IllegalArgumentException ex)
		{
			javax.swing.JOptionPane.showMessageDialog(this, "Invalid task JSON:\n\n" + ex.getMessage(),
				"Import Error", javax.swing.JOptionPane.ERROR_MESSAGE);
		}
		catch (Exception ex)
		{
			javax.swing.JOptionPane.showMessageDialog(this, "Import failed: " + ex.getMessage(),
				"Import Error", javax.swing.JOptionPane.ERROR_MESSAGE);
		}
	}

	private void exportTaskJson()
	{
		JFileChooser chooser = new JFileChooser();
		chooser.setDialogTitle("Export Task JSON");
		chooser.setSelectedFile(new File("tasks.json"));
		chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("JSON files", "json"));
		if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
		File file = chooser.getSelectedFile();
		if (!file.getName().toLowerCase().endsWith(".json"))
			file = new File(file.getParent(), file.getName() + ".json");
		try
		{
			String json = taskGridService.exportTasksToJson();
			Files.write(file.toPath(), json.getBytes(StandardCharsets.UTF_8));
			javax.swing.JOptionPane.showMessageDialog(this, "Exported tasks to " + file.getName(),
				"Export Complete", javax.swing.JOptionPane.INFORMATION_MESSAGE);
		}
		catch (Exception ex)
		{
			javax.swing.JOptionPane.showMessageDialog(this, "Export failed: " + ex.getMessage(),
				"Export Error", javax.swing.JOptionPane.ERROR_MESSAGE);
		}
	}

	private void startEditingNewTask()
	{
		editingTaskIndex = -1;
		TaskDefinition t = new TaskDefinition();
		t.setDisplayName("");
		t.setTaskType("");
		t.setDifficulty(1);
		t.setAreas(new ArrayList<>());
		showTaskEditForm(t);
	}

	private void startEditingTask(int index)
	{
		List<TaskDefinition> custom = taskGridService.getCustomTasks();
		if (index < 0 || index >= custom.size()) return;
		editingTaskIndex = index;
		showTaskEditForm(custom.get(index));
	}

	private void showTaskEditForm(TaskDefinition t)
	{
		if (taskEditSectionHeader != null)
		{
			taskEditSectionHeader.setSelected(true);
			taskEditSectionHeader.setText("▼ Edit task");
			taskEditPanel.setVisible(true);
			for (Container p = taskEditSectionHeader.getParent(); p != null; p = p.getParent())
				p.revalidate();
		}
		taskEditPanel.removeAll();
		taskEditPanel.add(new JLabel("Display name:"));
		taskDisplayNameField = new JTextField(t.getDisplayName() != null ? t.getDisplayName() : "", 20);
		taskDisplayNameField.setMaximumSize(new Dimension(Integer.MAX_VALUE, taskDisplayNameField.getPreferredSize().height));
		taskEditPanel.add(taskDisplayNameField);
		taskEditPanel.add(new JLabel("Task type (e.g. Combat, Mining):"));
		taskTypeField = new JTextField(t.getTaskType() != null ? t.getTaskType() : "", 20);
		taskTypeField.setMaximumSize(new Dimension(Integer.MAX_VALUE, taskTypeField.getPreferredSize().height));
		taskEditPanel.add(taskTypeField);
		taskEditPanel.add(new JLabel("Difficulty (1–5):"));
		taskDifficultySpinner = new JSpinner(new SpinnerNumberModel(
			Math.max(1, Math.min(5, t.getDifficulty())), 1, 5, 1));
		taskEditPanel.add(taskDifficultySpinner);
		taskF2pCheckBox = new JCheckBox("Free to Play (available in F2P worlds)", Boolean.TRUE.equals(t.getF2p()));
		taskEditPanel.add(taskF2pCheckBox);
		taskEditPanel.add(new JLabel("Area(s) – leave all unchecked for any area; or select areas this task appears in:"));
		taskAreasPanel = new JPanel();
		taskAreasPanel.setLayout(new BoxLayout(taskAreasPanel, BoxLayout.Y_AXIS));
		List<Area> areas = new ArrayList<>(areaGraphService.getAreas());
		areas.sort(AREA_DISPLAY_ORDER);
		List<String> selectedIds = t.getRequiredAreaIds();
		for (Area a : areas)
		{
			JCheckBox cb = new JCheckBox(a.getDisplayName() != null ? a.getDisplayName() : a.getId());
			cb.setName(a.getId());
			cb.setSelected(selectedIds != null && selectedIds.contains(a.getId()));
			taskAreasPanel.add(cb);
		}
		JScrollPane taskAreasScroll = new JScrollPane(taskAreasPanel);
		taskAreasScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		taskEditPanel.add(taskAreasScroll);
		taskEditPanel.add(new JLabel("Area requirement (when multiple areas):"));
		taskAreaRequirementCombo = new JComboBox<>(new String[] { "all", "any" });
		taskAreaRequirementCombo.setSelectedItem((t.getAreaRequirement() != null && "any".equalsIgnoreCase(t.getAreaRequirement())) ? "any" : "all");
		taskEditPanel.add(taskAreaRequirementCombo);
		taskOnceOnlyCheckBox = new JCheckBox("Appear only once (in one area, one slot)", Boolean.TRUE.equals(t.getOnceOnly()));
		taskEditPanel.add(taskOnceOnlyCheckBox);
		taskEditPanel.add(new JLabel("Requirements (optional):"));
		taskRequirementsField = new JTextField(t.getRequirements() != null ? t.getRequirements() : "", 20);
		taskRequirementsField.setMaximumSize(new Dimension(Integer.MAX_VALUE, taskRequirementsField.getPreferredSize().height));
		taskEditPanel.add(taskRequirementsField);
		taskEditPanel.add(new JLabel(" "));
		JPanel taskSaveCancel = new JPanel();
		JButton taskSaveBtn = new JButton("Save");
		taskSaveBtn.addActionListener(e -> saveTask());
		JButton taskCancelBtn = new JButton("Cancel");
		taskCancelBtn.addActionListener(e -> cancelTaskEdit());
		taskSaveCancel.add(taskSaveBtn);
		taskSaveCancel.add(taskCancelBtn);
		taskEditPanel.add(taskSaveCancel);
		taskEditPanel.revalidate();
		taskEditPanel.repaint();
	}

	private void saveTask()
	{
		String displayName = taskDisplayNameField.getText().trim();
		if (displayName.isEmpty()) displayName = "Unnamed task";
		String taskType = taskTypeField.getText().trim();
		int difficulty = (Integer) taskDifficultySpinner.getValue();
		List<String> areaIds = new ArrayList<>();
		for (int i = 0; i < taskAreasPanel.getComponentCount(); i++)
		{
			if (taskAreasPanel.getComponent(i) instanceof JCheckBox)
			{
				JCheckBox cb = (JCheckBox) taskAreasPanel.getComponent(i);
				if (cb.isSelected() && cb.getName() != null)
					areaIds.add(cb.getName());
			}
		}
		TaskDefinition def = new TaskDefinition();
		def.setDisplayName(displayName);
		def.setTaskType(taskType.isEmpty() ? null : taskType);
		def.setDifficulty(difficulty);
		def.setF2p(taskF2pCheckBox.isSelected());
		def.setAreaRequirement(taskAreaRequirementCombo != null && "any".equals(taskAreaRequirementCombo.getSelectedItem()) ? "any" : "all");
		def.setOnceOnly(taskOnceOnlyCheckBox != null && taskOnceOnlyCheckBox.isSelected());
		def.setRequirements(taskRequirementsField != null && taskRequirementsField.getText() != null && !taskRequirementsField.getText().trim().isEmpty() ? taskRequirementsField.getText().trim() : null);
		if (areaIds.isEmpty())
		{
			def.setArea(null);
			def.setAreas(null);
		}
		else if (areaIds.size() == 1)
		{
			def.setArea(areaIds.get(0));
			def.setAreas(null);
		}
		else
		{
			def.setArea(null);
			def.setAreas(areaIds);
		}
		if (editingTaskIndex >= 0)
			taskGridService.updateCustomTask(editingTaskIndex, def);
		else
			taskGridService.addCustomTask(def);
		cancelTaskEdit();
		refreshTaskList();
	}

	private void cancelTaskEdit()
	{
		editingTaskIndex = -1;
		taskEditPanel.removeAll();
		taskEditPanel.revalidate();
		taskEditPanel.repaint();
	}

	private String getDisplayNameForRemoved(String areaId)
	{
		Area builtIn = areaGraphService.getBuiltInArea(areaId);
		return builtIn != null && builtIn.getDisplayName() != null ? builtIn.getDisplayName() : areaId;
	}

	private void removeArea(String areaId)
	{
		areaGraphService.removeArea(areaId);
		if (plugin.getEditingAreaId() != null && plugin.getEditingAreaId().equals(areaId))
		{
			plugin.stopEditing();
			clearEditForm();
		}
		refreshAreaList();
		refreshRemovedList();
	}

	private void cancelEdit()
	{
		plugin.stopEditing();
		clearEditForm();
	}

	private void clearEditForm()
	{
		editPanel.removeAll();
		editPanel.revalidate();
		editPanel.repaint();
	}

	private void openTaskCreatorHelperDialog()
	{
		if (wikiApi == null || wikiTaskGenerator == null) return;
		Frame owner = null;
		java.awt.Window w = SwingUtilities.windowForComponent(this);
		if (w instanceof Frame) owner = (Frame) w;
		JDialog dialog = new JDialog(owner, "Task Creator Helper", true);
		dialog.setLayout(new BorderLayout(8, 8));

		JTabbedPane tabs = new JTabbedPane();
		// Manual tab
		JPanel manualPanel = new JPanel(new BorderLayout(6, 6));
		JPanel manualTop = new JPanel(new BorderLayout(4, 4));
		JPanel sourceRow = new JPanel(new BorderLayout(4, 0));
		sourceRow.add(new JLabel("Source:"), BorderLayout.WEST);
		JComboBox<WikiTaskSource> sourceCombo = new JComboBox<>(WikiTaskSource.values());
		sourceRow.add(sourceCombo, BorderLayout.CENTER);
		JButton fetchBtn = new JButton("Fetch from Wiki");
		sourceRow.add(fetchBtn, BorderLayout.EAST);
		manualTop.add(sourceRow, BorderLayout.NORTH);
		DefaultListModel<String> candidateModel = new DefaultListModel<>();
		JList<String> candidateList = new JList<>(candidateModel);
		candidateList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		manualTop.add(new JScrollPane(candidateList), BorderLayout.CENTER);
		manualPanel.add(manualTop, BorderLayout.NORTH);

		JPanel formPanel = new JPanel();
		formPanel.setLayout(new BoxLayout(formPanel, BoxLayout.Y_AXIS));
		formPanel.add(new JLabel("Display name:"));
		JTextField helperDisplayName = new JTextField(30);
		formPanel.add(helperDisplayName);
		formPanel.add(new JLabel("Task type:"));
		JComboBox<String> helperTaskType = new JComboBox<>(TASK_TYPE_PRESETS);
		formPanel.add(helperTaskType);
		formPanel.add(new JLabel("Difficulty (1-5):"));
		JSpinner helperDifficulty = new JSpinner(new SpinnerNumberModel(3, 1, 5, 1));
		formPanel.add(helperDifficulty);
		JCheckBox helperF2p = new JCheckBox("F2P", true);
		formPanel.add(helperF2p);
		formPanel.add(new JLabel("Area (optional):"));
		JPanel helperAreasPanel = new JPanel();
		helperAreasPanel.setLayout(new BoxLayout(helperAreasPanel, BoxLayout.Y_AXIS));
		List<Area> areasForHelper = new ArrayList<>(areaGraphService.getAreas());
		areasForHelper.sort(AREA_DISPLAY_ORDER);
		for (Area a : areasForHelper)
		{
			JCheckBox cb = new JCheckBox(a.getDisplayName() != null ? a.getDisplayName() : a.getId());
			cb.setName(a.getId());
			helperAreasPanel.add(cb);
		}
		formPanel.add(new JScrollPane(helperAreasPanel));
		formPanel.add(new JLabel("Area requirement (when multiple areas):"));
		JComboBox<String> helperAreaRequirement = new JComboBox<>(new String[] { "all", "any" });
		formPanel.add(helperAreaRequirement);
		JCheckBox helperOnceOnly = new JCheckBox("Appear only once", false);
		formPanel.add(helperOnceOnly);
		formPanel.add(new JLabel("Requirements (optional):"));
		JTextField helperRequirements = new JTextField(30);
		formPanel.add(helperRequirements);
		JPanel manualButtons = new JPanel();
		JButton addOneBtn = new JButton("Add to task list");
		JButton copyJsonBtn = new JButton("Copy JSON");
		JButton clearBtn = new JButton("Clear");
		manualButtons.add(addOneBtn);
		manualButtons.add(copyJsonBtn);
		manualButtons.add(clearBtn);
		formPanel.add(manualButtons);
		manualPanel.add(new JScrollPane(formPanel), BorderLayout.CENTER);

		candidateList.addListSelectionListener(e -> {
			if (e.getValueIsAdjusting()) return;
			int i = candidateList.getSelectedIndex();
			if (i >= 0 && i < candidateModel.getSize())
			{
				WikiTaskSource src = (WikiTaskSource) sourceCombo.getSelectedItem();
				String title = candidateModel.get(i);
				String prefix = src != null && src.getDisplayNamePrefix() != null ? src.getDisplayNamePrefix() : "";
				helperDisplayName.setText(prefix + title);
				if (src != null) helperTaskType.setSelectedItem(src.getDefaultTaskType());
			}
		});
		fetchBtn.addActionListener(e -> {
			fetchBtn.setEnabled(false);
			WikiTaskSource src = (WikiTaskSource) sourceCombo.getSelectedItem();
			if (src == null) { fetchBtn.setEnabled(true); return; }
			helperExecutor.execute(() -> {
				List<String> all = new ArrayList<>();
				String ct = null;
				do
				{
					OsrsWikiApiService.CategoryMembersResult res = wikiApi.listCategoryMembers(src.getCategoryName(), 500, ct);
					all.addAll(res.getTitles());
					ct = res.getNextContinue();
				}
				while (ct != null && !ct.isEmpty());
				SwingUtilities.invokeLater(() -> {
					candidateModel.clear();
					for (String t : all) candidateModel.addElement(t);
					fetchBtn.setEnabled(true);
				});
			});
		});
		addOneBtn.addActionListener(e -> {
			TaskDefinition def = buildTaskFromHelperForm(helperDisplayName, helperTaskType, helperDifficulty, helperF2p, helperAreasPanel, helperAreaRequirement, helperOnceOnly, helperRequirements);
			if (def != null) { taskGridService.addCustomTask(def); refreshTaskList(); JOptionPane.showMessageDialog(dialog, "Task added."); }
		});
		copyJsonBtn.addActionListener(e -> {
			TaskDefinition def = buildTaskFromHelperForm(helperDisplayName, helperTaskType, helperDifficulty, helperF2p, helperAreasPanel, helperAreaRequirement, helperOnceOnly, helperRequirements);
			if (def != null)
			{
				String json = taskGridService.exportTaskListAsJson(Collections.singletonList(def));
				Clipboard cl = dialog.getToolkit().getSystemClipboard();
				if (cl != null) cl.setContents(new StringSelection(json), null);
				JOptionPane.showMessageDialog(dialog, "JSON copied to clipboard.");
			}
		});
		clearBtn.addActionListener(e -> {
			helperDisplayName.setText("");
			helperTaskType.setSelectedIndex(0);
			helperDifficulty.setValue(3);
			helperF2p.setSelected(true);
			helperAreaRequirement.setSelectedItem("all");
			helperOnceOnly.setSelected(false);
			helperRequirements.setText("");
			for (int i = 0; i < helperAreasPanel.getComponentCount(); i++)
				if (helperAreasPanel.getComponent(i) instanceof JCheckBox)
					((JCheckBox) helperAreasPanel.getComponent(i)).setSelected(false);
		});

		tabs.addTab("Manual", manualPanel);

		// Generate tab
		JPanel genPanel = new JPanel(new BorderLayout(6, 6));
		JPanel genOptions = new JPanel();
		genOptions.setLayout(new BoxLayout(genOptions, BoxLayout.Y_AXIS));
		genOptions.add(new JLabel("Source types to include:"));
		JPanel sourceCheckboxes = new JPanel();
		sourceCheckboxes.setLayout(new BoxLayout(sourceCheckboxes, BoxLayout.Y_AXIS));
		for (WikiTaskSource src : WikiTaskSource.values())
		{
			JCheckBox cb = new JCheckBox(src.getDisplayName());
			cb.setName(src.name());
			sourceCheckboxes.add(cb);
		}
		genOptions.add(new JScrollPane(sourceCheckboxes));
		genOptions.add(new JLabel("Default difficulty:"));
		JSpinner genDefDiff = new JSpinner(new SpinnerNumberModel(3, 1, 5, 1));
		genOptions.add(genDefDiff);
		genOptions.add(new JLabel("Difficulty range (min-max):"));
		JPanel rangeRow = new JPanel();
		JSpinner genRangeMin = new JSpinner(new SpinnerNumberModel(1, 1, 5, 1));
		JSpinner genRangeMax = new JSpinner(new SpinnerNumberModel(5, 1, 5, 1));
		rangeRow.add(genRangeMin);
		rangeRow.add(new JLabel(" to "));
		rangeRow.add(genRangeMax);
		genOptions.add(rangeRow);
		JCheckBox genF2p = new JCheckBox("F2P by default", true);
		genOptions.add(genF2p);
		JButton generateBtn = new JButton("Generate");
		genOptions.add(generateBtn);
		JLabel progressLabel = new JLabel(" ");
		genOptions.add(progressLabel);
		genPanel.add(genOptions, BorderLayout.NORTH);
		DefaultTableModel previewModel = new DefaultTableModel(new String[] { "Display name", "Type", "Difficulty" }, 0);
		JTable previewTable = new JTable(previewModel);
		genPanel.add(new JScrollPane(previewTable), BorderLayout.CENTER);
		JPanel genActions = new JPanel();
		JButton addAllBtn = new JButton("Add all to task list");
		JButton exportJsonBtn = new JButton("Export as JSON");
		genActions.add(addAllBtn);
		genActions.add(exportJsonBtn);
		genPanel.add(genActions, BorderLayout.SOUTH);

		List<TaskDefinition>[] generatedHolder = new List[] { new ArrayList<>() };
		generateBtn.addActionListener(e -> {
			Set<WikiTaskSource> selected = new HashSet<>();
			for (int i = 0; i < sourceCheckboxes.getComponentCount(); i++)
				if (sourceCheckboxes.getComponent(i) instanceof JCheckBox)
				{
					JCheckBox cb = (JCheckBox) sourceCheckboxes.getComponent(i);
					if (cb.isSelected() && cb.getName() != null)
					{
						try { selected.add(WikiTaskSource.valueOf(cb.getName())); } catch (Exception ignored) { }
					}
				}
			if (selected.isEmpty()) { JOptionPane.showMessageDialog(dialog, "Select at least one source."); return; }
			generateBtn.setEnabled(false);
			WikiTaskGenerator.GeneratorDefaults defaults = new WikiTaskGenerator.GeneratorDefaults();
			defaults.setDefaultDifficulty((Integer) genDefDiff.getValue());
			defaults.setDefaultF2p(genF2p.isSelected());
			defaults.setDifficultyRangeMin((Integer) genRangeMin.getValue());
			defaults.setDifficultyRangeMax((Integer) genRangeMax.getValue());
			helperExecutor.execute(() -> {
				List<TaskDefinition> result = wikiTaskGenerator.generate(selected, defaults, msg ->
					SwingUtilities.invokeLater(() -> progressLabel.setText(msg)));
				generatedHolder[0] = result != null ? result : new ArrayList<>();
				SwingUtilities.invokeLater(() -> {
					previewModel.setRowCount(0);
					for (int i = 0; i < Math.min(50, generatedHolder[0].size()); i++)
					{
						TaskDefinition t = generatedHolder[0].get(i);
						previewModel.addRow(new Object[] {
							t.getDisplayName() != null ? t.getDisplayName() : "",
							t.getTaskType() != null ? t.getTaskType() : "",
							t.getDifficulty()
						});
					}
					progressLabel.setText("Generated " + generatedHolder[0].size() + " tasks.");
					generateBtn.setEnabled(true);
				});
			});
		});
		addAllBtn.addActionListener(e -> {
			if (generatedHolder[0].isEmpty()) { JOptionPane.showMessageDialog(dialog, "Generate first."); return; }
			taskGridService.addCustomTasks(generatedHolder[0]);
			refreshTaskList();
			JOptionPane.showMessageDialog(dialog, "Added " + generatedHolder[0].size() + " tasks.");
		});
		exportJsonBtn.addActionListener(e -> {
			if (generatedHolder[0].isEmpty()) { JOptionPane.showMessageDialog(dialog, "Generate first."); return; }
			String json = taskGridService.exportTaskListAsJson(generatedHolder[0]);
			Clipboard cl = dialog.getToolkit().getSystemClipboard();
			if (cl != null) cl.setContents(new StringSelection(json), null);
			JOptionPane.showMessageDialog(dialog, "JSON copied to clipboard (" + generatedHolder[0].size() + " tasks).");
		});

		tabs.addTab("Generate", genPanel);

		dialog.add(tabs, BorderLayout.CENTER);
		JButton closeBtn = new JButton("Close");
		closeBtn.addActionListener(ev -> dialog.dispose());
		JPanel closeRow = new JPanel();
		closeRow.add(closeBtn);
		dialog.add(closeRow, BorderLayout.SOUTH);
		dialog.pack();
		dialog.setSize(Math.min(600, dialog.getWidth()), Math.min(500, dialog.getHeight()));
		dialog.setLocationRelativeTo(this);
		dialog.setVisible(true);
	}

	private TaskDefinition buildTaskFromHelperForm(JTextField displayName, JComboBox<String> taskType, JSpinner difficulty,
		JCheckBox f2p, JPanel areasPanel, JComboBox<String> areaRequirement, JCheckBox onceOnly, JTextField requirements)
	{
		String name = displayName.getText() != null ? displayName.getText().trim() : "";
		if (name.isEmpty()) return null;
		TaskDefinition def = new TaskDefinition();
		def.setDisplayName(name);
		def.setTaskType(taskType.getSelectedItem() != null ? taskType.getSelectedItem().toString() : null);
		def.setDifficulty((Integer) difficulty.getValue());
		def.setF2p(f2p.isSelected());
		def.setAreaRequirement(areaRequirement != null && "any".equals(areaRequirement.getSelectedItem()) ? "any" : "all");
		def.setOnceOnly(onceOnly != null && onceOnly.isSelected());
		def.setRequirements(requirements.getText() != null && !requirements.getText().trim().isEmpty() ? requirements.getText().trim() : null);
		List<String> areaIds = new ArrayList<>();
		for (int i = 0; i < areasPanel.getComponentCount(); i++)
			if (areasPanel.getComponent(i) instanceof JCheckBox)
			{
				JCheckBox cb = (JCheckBox) areasPanel.getComponent(i);
				if (cb.isSelected() && cb.getName() != null) areaIds.add(cb.getName());
			}
		if (areaIds.isEmpty()) { def.setArea(null); def.setAreas(null); }
		else if (areaIds.size() == 1) { def.setArea(areaIds.get(0)); def.setAreas(null); }
		else { def.setArea(null); def.setAreas(areaIds); }
		return def;
	}

	public BufferedImage getIcon()
	{
		BufferedImage icon = ImageUtil.loadImageResource(LeagueScapePlugin.class, "icon.png");
		if (icon != null) return icon;
		BufferedImage placeholder = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
		for (int x = 0; x < 16; x++)
			for (int y = 0; y < 16; y++)
				placeholder.setRGB(x, y, 0xFF88AA00); // orange tint for config
		return placeholder;
	}
}

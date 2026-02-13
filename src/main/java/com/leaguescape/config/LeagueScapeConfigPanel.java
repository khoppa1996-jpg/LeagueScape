package com.leaguescape.config;

import com.leaguescape.LeagueScapePlugin;
import com.leaguescape.area.AreaGraphService;
import com.leaguescape.data.Area;
import com.leaguescape.task.TaskDefinition;
import com.leaguescape.task.TaskGridService;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import javax.swing.BoxLayout;
import javax.swing.JFileChooser;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JToggleButton;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.Scrollable;
import javax.swing.SpinnerNumberModel;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.ImageUtil;
import java.awt.Container;
import java.awt.Rectangle;

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

	private final LeagueScapePlugin plugin;
	private final AreaGraphService areaGraphService;
	private final TaskGridService taskGridService;

	private JPanel mainPanel;
	private JPanel listPanel;
	private JPanel removedPanel;
	private JPanel editPanel;
	private JToggleButton editSectionHeader;
	private JTextField idField;
	private JTextField displayNameField;
	private JPanel cornersPanel;
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
	private JPanel taskAreasPanel;
	private int editingTaskIndex = -1;

	public LeagueScapeConfigPanel(LeagueScapePlugin plugin, AreaGraphService areaGraphService, TaskGridService taskGridService)
	{
		this.plugin = plugin;
		this.areaGraphService = areaGraphService;
		this.taskGridService = taskGridService;
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
		showEditForm(tempId, "", "", Collections.emptyList(), Collections.emptyList(), 0, 10);
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
		int ptsToComplete = a.getPointsToComplete() != null ? a.getPointsToComplete() : a.getUnlockCost();
		showEditForm(areaId, a.getId(), a.getDisplayName() != null ? a.getDisplayName() : "", firstPolygon, neighbors, a.getUnlockCost(), ptsToComplete);
	}

	private void showEditForm(String areaId, String id, String displayName, List<int[]> corners, List<String> neighbors, int unlockCost, int pointsToComplete)
	{
		if (editSectionHeader != null)
		{
			editSectionHeader.setSelected(true);
			editSectionHeader.setText("▼ Edit area");
			editPanel.setVisible(true);
			for (Container p = editSectionHeader.getParent(); p != null; p = p.getParent())
			{
				p.revalidate();
			}
		}
		editPanel.removeAll();

		editPanel.add(new JLabel("Area ID (slug, e.g. " + (areaId.startsWith("new_") ? "lumbridge):" : "):")));
		idField = new JTextField(id.startsWith("new_") ? "" : id, 12);
		idField.setEditable(areaId.startsWith("new_"));
		idField.setMaximumSize(new Dimension(Integer.MAX_VALUE, idField.getPreferredSize().height));
		editPanel.add(idField);

		editPanel.add(new JLabel("Display name:"));
		displayNameField = new JTextField(displayName, 12);
		displayNameField.setMaximumSize(new Dimension(Integer.MAX_VALUE, displayNameField.getPreferredSize().height));
		editPanel.add(displayNameField);

		editPanel.add(new JLabel(" "));
		JLabel cornersHint = new JLabel("<html>Corners: Shift+RMB to add; Shift+RMB corner to Move, then Set.</html>");
		cornersHint.setToolTipText("Shift+Right-click to add corner; Shift+Right-click a corner to Move it, then click another tile to Set.");
		editPanel.add(cornersHint);
		cornersPanel = new JPanel();
		cornersPanel.setLayout(new BoxLayout(cornersPanel, BoxLayout.Y_AXIS));
		JScrollPane cornersScroll = new JScrollPane(cornersPanel);
		cornersScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		editPanel.add(cornersScroll);

		editPanel.add(new JLabel(" "));
		editPanel.add(new JLabel("Neighbors:"));
		neighborsPanel = new JPanel();
		neighborsPanel.setLayout(new BoxLayout(neighborsPanel, BoxLayout.Y_AXIS));
		List<Area> others = new ArrayList<>(areaGraphService.getAreas());
		others.removeIf(a -> a.getId().equals(areaId));
		others.sort(AREA_DISPLAY_ORDER);
		for (Area other : others)
		{
			JCheckBox cb = new JCheckBox(other.getDisplayName() != null ? other.getDisplayName() : other.getId());
			cb.setName(other.getId()); // Store id for lookup
			cb.setSelected(neighbors.contains(other.getId()));
			neighborsPanel.add(cb);
		}
		JScrollPane neighborsScroll = new JScrollPane(neighborsPanel);
		neighborsScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
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

		List<List<int[]>> allPolygons = plugin.getAllEditingPolygons();
		if (allPolygons.isEmpty() || allPolygons.stream().anyMatch(p -> p.size() < 3))
		{
			// Need at least one polygon with >= 3 corners
			return;
		}

		List<String> neighbors = new ArrayList<>();
		for (int i = 0; i < neighborsPanel.getComponentCount(); i++)
		{
			if (neighborsPanel.getComponent(i) instanceof JCheckBox)
			{
				JCheckBox cb = (JCheckBox) neighborsPanel.getComponent(i);
				if (cb.isSelected() && cb.getName() != null)
				{
					neighbors.add(cb.getName());
				}
			}
		}

		int cost = (Integer) unlockCostSpinner.getValue();
		int ptsToComplete = (Integer) pointsToCompleteSpinner.getValue();

		Area area = Area.builder()
			.id(id)
			.displayName(displayName)
			.polygons(allPolygons)
			.includes(Collections.emptyList()) // Computed by AreaGraphService
			.neighbors(neighbors)
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

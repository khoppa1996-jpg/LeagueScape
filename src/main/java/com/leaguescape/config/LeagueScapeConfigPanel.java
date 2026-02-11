package com.leaguescape.config;

import com.leaguescape.LeagueScapePlugin;
import com.leaguescape.area.AreaGraphService;
import com.leaguescape.data.Area;
import java.awt.BorderLayout;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import javax.swing.BoxLayout;
import javax.swing.JFileChooser;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.ImageUtil;

public class LeagueScapeConfigPanel extends PluginPanel
{
	private final LeagueScapeConfigPlugin plugin;
	private final AreaGraphService areaGraphService;

	private JPanel mainPanel;
	private JPanel listPanel;
	private JPanel removedPanel;
	private JPanel editPanel;
	private JTextField idField;
	private JTextField displayNameField;
	private JPanel cornersPanel;
	private JPanel neighborsPanel;
	private JSpinner unlockCostSpinner;
	private JButton saveBtn;
	private JButton cancelBtn;

	public LeagueScapeConfigPanel(LeagueScapeConfigPlugin plugin, AreaGraphService areaGraphService)
	{
		this.plugin = plugin;
		this.areaGraphService = areaGraphService;
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

		// Scrollable content: area list, removed list, edit form
		mainPanel = new JPanel();
		mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.Y_AXIS));

		JLabel listLabel = new JLabel("Areas:");
		mainPanel.add(listLabel);
		listPanel = new JPanel();
		listPanel.setLayout(new BoxLayout(listPanel, BoxLayout.Y_AXIS));
		mainPanel.add(new JScrollPane(listPanel));

		mainPanel.add(new JLabel(" "));
		mainPanel.add(new JLabel("Removed areas (click Restore to add back):"));
		removedPanel = new JPanel();
		removedPanel.setLayout(new BoxLayout(removedPanel, BoxLayout.Y_AXIS));
		mainPanel.add(new JScrollPane(removedPanel));

		editPanel = new JPanel();
		editPanel.setLayout(new BoxLayout(editPanel, BoxLayout.Y_AXIS));
		editPanel.setBorder(new EmptyBorder(10, 0, 0, 0));
		mainPanel.add(editPanel);

		add(new JScrollPane(mainPanel), BorderLayout.CENTER);
		refreshAreaList();
		refreshRemovedList();
	}

	private void refreshAreaList()
	{
		listPanel.removeAll();
		for (Area a : areaGraphService.getAreas())
		{
			JPanel row = new JPanel(new BorderLayout());
			JLabel label = new JLabel(a.getDisplayName() != null ? a.getDisplayName() : a.getId());
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
		for (String areaId : areaGraphService.getRemovedAreaIds())
		{
			Area builtIn = areaGraphService.getBuiltInArea(areaId);
			String displayName = builtIn != null && builtIn.getDisplayName() != null ? builtIn.getDisplayName() : areaId;
			JPanel row = new JPanel(new BorderLayout());
			row.add(new JLabel(displayName), BorderLayout.CENTER);
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
		showEditForm(tempId, "", "", Collections.emptyList(), Collections.emptyList(), 0);
	}

	private void startEditing(String areaId)
	{
		Area a = areaGraphService.getArea(areaId);
		if (a == null) return;

		List<int[]> corners = a.getPolygon() != null ? new ArrayList<>(a.getPolygon()) : new ArrayList<>();
		List<String> neighbors = a.getNeighbors() != null ? new ArrayList<>(a.getNeighbors()) : new ArrayList<>();
		plugin.startEditing(areaId, corners);
		plugin.setCornerUpdateCallback(this::refreshCornersDisplay);
		showEditForm(areaId, a.getId(), a.getDisplayName() != null ? a.getDisplayName() : "", corners, neighbors, a.getUnlockCost());
	}

	private void showEditForm(String areaId, String id, String displayName, List<int[]> corners, List<String> neighbors, int unlockCost)
	{
		editPanel.removeAll();

		editPanel.add(new JLabel("Area ID (slug, e.g. " + (areaId.startsWith("new_") ? "lumbridge):" : "):")));
		idField = new JTextField(id.startsWith("new_") ? "" : id, 20);
		idField.setEditable(areaId.startsWith("new_"));
		editPanel.add(idField);

		editPanel.add(new JLabel("Display name:"));
		displayNameField = new JTextField(displayName, 20);
		editPanel.add(displayNameField);

		editPanel.add(new JLabel(" "));
		editPanel.add(new JLabel("Polygon corners (Shift+Right-click to add; Shift+Right-click corner for Move, then Set new corner):"));
		cornersPanel = new JPanel();
		cornersPanel.setLayout(new BoxLayout(cornersPanel, BoxLayout.Y_AXIS));
		editPanel.add(new JScrollPane(cornersPanel));

		editPanel.add(new JLabel(" "));
		editPanel.add(new JLabel("Neighbors:"));
		neighborsPanel = new JPanel();
		neighborsPanel.setLayout(new BoxLayout(neighborsPanel, BoxLayout.Y_AXIS));
		for (Area other : areaGraphService.getAreas())
		{
			if (other.getId().equals(areaId)) continue;
			JCheckBox cb = new JCheckBox(other.getDisplayName() != null ? other.getDisplayName() : other.getId());
			cb.setName(other.getId()); // Store id for lookup
			cb.setSelected(neighbors.contains(other.getId()));
			neighborsPanel.add(cb);
		}
		editPanel.add(new JScrollPane(neighborsPanel));

		editPanel.add(new JLabel("Unlock cost:"));
		unlockCostSpinner = new JSpinner(new SpinnerNumberModel(unlockCost, 0, 9999, 1));
		editPanel.add(unlockCostSpinner);

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
		List<int[]> corners = new ArrayList<>(plugin.getEditingCorners());
		if (index >= 0 && index < corners.size())
		{
			corners.remove(index);
			plugin.startEditing(plugin.getEditingAreaId(), corners);
		}
	}

	private void saveArea(String oldAreaId)
	{
		String id = idField.getText().trim().toLowerCase().replaceAll("[^a-z0-9_]", "_");
		if (id.isEmpty()) id = "area_" + System.currentTimeMillis();

		String displayName = displayNameField.getText().trim();
		if (displayName.isEmpty()) displayName = id;

		List<int[]> corners = plugin.getEditingCorners();
		if (corners.size() < 3)
		{
			// Could show message
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

		Area area = Area.builder()
			.id(id)
			.displayName(displayName)
			.polygon(new ArrayList<>(corners))
			.includes(Collections.emptyList()) // Computed by AreaGraphService
			.neighbors(neighbors)
			.unlockCost(cost)
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

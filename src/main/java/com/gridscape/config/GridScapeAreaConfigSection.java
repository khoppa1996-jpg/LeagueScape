package com.gridscape.config;

import com.gridscape.GridScapeConfig;
import com.gridscape.GridScapePlugin;
import com.gridscape.area.AreaGraphService;
import com.gridscape.data.Area;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Rectangle;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.Scrollable;
import javax.swing.SpinnerNumberModel;
import javax.swing.border.EmptyBorder;
import net.runelite.client.config.ConfigManager;

/**
 * Area-only configuration section: import/export area JSON, area list (add/edit/remove),
 * removed areas (restore), edit area form (corners, holes, neighbors, costs), make hole.
 * Used inside the Setup popup's Area Configuration tab. Styled with GridScape popup colors.
 */
public class GridScapeAreaConfigSection extends JPanel
{
	private static final Color POPUP_BG = com.gridscape.util.GridScapeColors.POPUP_BG;
	private static final Color POPUP_TEXT = com.gridscape.util.GridScapeColors.POPUP_TEXT;
	private static final String CONFIG_GROUP = com.gridscape.util.GridScapeConfigConstants.CONFIG_GROUP;

	private static final Comparator<Area> AREA_DISPLAY_ORDER = Comparator
		.comparing((Area a) -> a.getDisplayName() != null ? a.getDisplayName() : a.getId(), String.CASE_INSENSITIVE_ORDER)
		.thenComparing(Area::getId, String.CASE_INSENSITIVE_ORDER);

	private final GridScapePlugin plugin;
	private final AreaGraphService areaGraphService;
	private final ConfigManager configManager;
	private final GridScapeConfig config;
	private final Color sectionBg;
	private final boolean sectionOpaque;

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

	public GridScapeAreaConfigSection(GridScapePlugin plugin, AreaGraphService areaGraphService,
		ConfigManager configManager, GridScapeConfig config)
	{
		this(plugin, areaGraphService, configManager, config, false);
	}

	public GridScapeAreaConfigSection(GridScapePlugin plugin, AreaGraphService areaGraphService,
		ConfigManager configManager, GridScapeConfig config, boolean transparentBackground)
	{
		this.plugin = plugin;
		this.areaGraphService = areaGraphService;
		this.configManager = configManager;
		this.config = config;
		this.sectionBg = transparentBackground ? new Color(0, 0, 0, 0) : POPUP_BG;
		this.sectionOpaque = !transparentBackground;
		setLayout(new BorderLayout());
		setBackground(sectionBg);
		setOpaque(sectionOpaque);
		setBorder(new EmptyBorder(8, 8, 8, 8));

		mainPanel = com.gridscape.util.GridScapeSwingUtil.newScrollableTrackWidthPanel();
		mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.Y_AXIS));
		mainPanel.setBackground(sectionBg);
		mainPanel.setOpaque(sectionOpaque);

		JPanel topButtons = new JPanel();
		topButtons.setLayout(new BoxLayout(topButtons, BoxLayout.Y_AXIS));
		topButtons.setBackground(sectionBg);
		topButtons.setOpaque(sectionOpaque);
		JButton addBtn = new JButton(GridScapeSetupStrings.AREA_ADD_NEW);
		addBtn.addActionListener(e -> startEditingNew());
		addBtn.setAlignmentX(LEFT_ALIGNMENT);
		styleButton(addBtn);
		topButtons.add(addBtn);
		JButton importBtn = new JButton(GridScapeSetupStrings.AREA_IMPORT_JSON);
		importBtn.addActionListener(e -> importAreaJson());
		importBtn.setAlignmentX(LEFT_ALIGNMENT);
		styleButton(importBtn);
		topButtons.add(importBtn);
		JButton exportBtn = new JButton(GridScapeSetupStrings.AREA_EXPORT_JSON);
		exportBtn.addActionListener(e -> exportAreaJson());
		exportBtn.setAlignmentX(LEFT_ALIGNMENT);
		styleButton(exportBtn);
		topButtons.add(exportBtn);
		mainPanel.add(topButtons);
		mainPanel.add(new JLabel(" "));

		listPanel = com.gridscape.util.GridScapeSwingUtil.newScrollableTrackWidthPanel();
		listPanel.setLayout(new BoxLayout(listPanel, BoxLayout.Y_AXIS));
		listPanel.setBackground(sectionBg);
		listPanel.setOpaque(sectionOpaque);
		JScrollPane listScroll = new JScrollPane(listPanel);
		listScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		listScroll.setOpaque(sectionOpaque);
		listScroll.getViewport().setBackground(sectionBg);
		listScroll.getViewport().setOpaque(sectionOpaque);
		mainPanel.add(createCollapsibleSection(GridScapeSetupStrings.AREA_SECTION_LIST, listScroll, true, null));

		mainPanel.add(new JLabel(" "));
		removedPanel = com.gridscape.util.GridScapeSwingUtil.newScrollableTrackWidthPanel();
		removedPanel.setLayout(new BoxLayout(removedPanel, BoxLayout.Y_AXIS));
		removedPanel.setBackground(sectionBg);
		removedPanel.setOpaque(sectionOpaque);
		JScrollPane removedScroll = new JScrollPane(removedPanel);
		removedScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		removedScroll.setOpaque(sectionOpaque);
		removedScroll.getViewport().setBackground(sectionBg);
		removedScroll.getViewport().setOpaque(sectionOpaque);
		mainPanel.add(createCollapsibleSection(GridScapeSetupStrings.AREA_SECTION_REMOVED, removedScroll, false, null));

		editPanel = new JPanel();
		editPanel.setLayout(new BoxLayout(editPanel, BoxLayout.Y_AXIS));
		editPanel.setBorder(new EmptyBorder(10, 0, 0, 0));
		editPanel.setBackground(sectionBg);
		editPanel.setOpaque(sectionOpaque);
		JToggleButton[] editHeaderRef = new JToggleButton[1];
		mainPanel.add(createCollapsibleSection(GridScapeSetupStrings.AREA_SECTION_EDIT, editPanel, false, editHeaderRef));
		editSectionHeader = editHeaderRef[0];

		makeHoleSectionPanel = new JPanel();
		makeHoleSectionPanel.setLayout(new BoxLayout(makeHoleSectionPanel, BoxLayout.Y_AXIS));
		makeHoleSectionPanel.setAlignmentX(LEFT_ALIGNMENT);
		makeHoleSectionPanel.setBackground(sectionBg);
		makeHoleSectionPanel.setOpaque(sectionOpaque);
		makeHoleOuterCombo = new JComboBox<>();
		makeHoleInnerCombo = new JComboBox<>();
		styleCombo(makeHoleOuterCombo);
		styleCombo(makeHoleInnerCombo);
		JPanel removeRow = new JPanel(new BorderLayout());
		removeRow.setBackground(sectionBg);
		removeRow.setOpaque(sectionOpaque);
		JLabel removeLbl = new JLabel(GridScapeSetupStrings.AREA_MAKE_HOLE_REMOVE_POLYGON);
		removeLbl.setForeground(POPUP_TEXT);
		removeRow.add(removeLbl, BorderLayout.WEST);
		removeRow.add(makeHoleInnerCombo, BorderLayout.EAST);
		makeHoleSectionPanel.add(removeRow);
		JPanel fromRow = new JPanel(new BorderLayout());
		fromRow.setBackground(sectionBg);
		fromRow.setOpaque(sectionOpaque);
		JLabel fromLbl = new JLabel(GridScapeSetupStrings.AREA_MAKE_HOLE_FROM_POLYGON);
		fromLbl.setForeground(POPUP_TEXT);
		fromRow.add(fromLbl, BorderLayout.WEST);
		fromRow.add(makeHoleOuterCombo, BorderLayout.EAST);
		makeHoleSectionPanel.add(fromRow);
		JButton makeHoleBtn = new JButton(GridScapeSetupStrings.AREA_MAKE_HOLE_BUTTON);
		makeHoleBtn.addActionListener(e -> applyMakeHole());
		makeHoleBtn.setAlignmentX(LEFT_ALIGNMENT);
		styleButton(makeHoleBtn);
		makeHoleSectionPanel.add(makeHoleBtn);
		JToggleButton[] makeHoleHeaderRef = new JToggleButton[1];
		mainPanel.add(createCollapsibleSection(GridScapeSetupStrings.AREA_SECTION_MAKE_HOLE, makeHoleSectionPanel, false, makeHoleHeaderRef));
		makeHoleSectionHeader = makeHoleHeaderRef[0];

		JScrollPane scrollPane = new JScrollPane(mainPanel);
		scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
		scrollPane.setOpaque(sectionOpaque);
		scrollPane.getViewport().setBackground(sectionBg);
		scrollPane.getViewport().setOpaque(sectionOpaque);
		scrollPane.setBorder(null);
		add(scrollPane, BorderLayout.CENTER);
		refreshAreaList();
		refreshRemovedList();
	}

	private void styleButton(JButton b)
	{
		b.setForeground(POPUP_TEXT);
		b.setBackground(sectionBg);
	}

	private void styleCombo(JComboBox<?> c)
	{
		c.setForeground(POPUP_TEXT);
		c.setBackground(sectionBg);
		c.setOpaque(sectionOpaque);
		// Use lightweight popup so the dropdown list does not use a separate window (avoids OS shadow under combos).
		c.setLightWeightPopupEnabled(true);
		// Disable FlatLaf drop shadow painted under the dropdown popup.
		c.putClientProperty("Popup.dropShadowPainted", false);
	}

	private void styleLabel(JLabel l)
	{
		l.setForeground(POPUP_TEXT);
	}

	private JPanel createCollapsibleSection(String title, java.awt.Component content, boolean expandedByDefault, JToggleButton[] headerOut)
	{
		JPanel wrapper = com.gridscape.util.GridScapeSwingUtil.createCollapsibleSection(title, content, expandedByDefault, headerOut);
		wrapper.setBackground(sectionBg);
		wrapper.setOpaque(sectionOpaque);
		if (headerOut != null && headerOut.length > 0 && headerOut[0] != null)
		{
			headerOut[0].setForeground(POPUP_TEXT);
			headerOut[0].setBackground(sectionBg);
		}
		return wrapper;
	}

	private void refreshAreaList()
	{
		listPanel.removeAll();
		List<Area> areas = new ArrayList<>(areaGraphService.getAreas());
		areas.sort(AREA_DISPLAY_ORDER);
		for (Area a : areas)
		{
			JPanel row = new JPanel(new BorderLayout());
			row.setBackground(sectionBg);
			row.setOpaque(sectionOpaque);
			String name = a.getDisplayName() != null ? a.getDisplayName() : a.getId();
			JLabel label = new JLabel(name);
			label.setForeground(POPUP_TEXT);
			label.setToolTipText(name);
			label.setMinimumSize(new Dimension(0, 0));
			row.add(label, BorderLayout.CENTER);
			JPanel buttons = new JPanel();
			buttons.setBackground(sectionBg);
			buttons.setOpaque(sectionOpaque);
			JButton editBtn = new JButton(GridScapeSetupStrings.AREA_ROW_EDIT);
			styleButton(editBtn);
			editBtn.addActionListener(e -> startEditing(a.getId()));
			buttons.add(editBtn);
			JButton removeBtn = new JButton(GridScapeSetupStrings.AREA_ROW_REMOVE);
			styleButton(removeBtn);
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
			row.setBackground(sectionBg);
			row.setOpaque(sectionOpaque);
			JLabel remLabel = new JLabel(displayName);
			remLabel.setForeground(POPUP_TEXT);
			remLabel.setToolTipText(displayName);
			remLabel.setMinimumSize(new Dimension(0, 0));
			row.add(remLabel, BorderLayout.CENTER);
			JButton restoreBtn = new JButton(GridScapeSetupStrings.AREA_ROW_RESTORE);
			styleButton(restoreBtn);
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
			editSectionHeader.setText(GridScapeSetupStrings.AREA_EDIT_HEADER_EXPANDED);
			editPanel.setVisible(true);
			if (makeHoleSectionHeader != null && makeHoleSectionPanel != null)
			{
				makeHoleSectionHeader.setSelected(true);
				makeHoleSectionHeader.setText(GridScapeSetupStrings.AREA_MAKE_HOLE_HEADER_EXPANDED);
				makeHoleSectionPanel.setVisible(true);
			}
			for (Container p = editSectionHeader.getParent(); p != null; p = p.getParent())
				p.revalidate();
		}
		editPanel.removeAll();
		editingHoles.clear();
		if (holes != null) for (List<int[]> h : holes) editingHoles.add(new ArrayList<>(h));

		JLabel idLbl = new JLabel(areaId.startsWith("new_") ? GridScapeSetupStrings.AREA_ID_LABEL_NEW : GridScapeSetupStrings.AREA_ID_LABEL_EXISTING);
		styleLabel(idLbl);
		editPanel.add(idLbl);
		idField = new JTextField(id.startsWith("new_") ? "" : id, 12);
		idField.setEditable(areaId.startsWith("new_"));
		idField.setMaximumSize(new Dimension(Integer.MAX_VALUE, idField.getPreferredSize().height));
		idField.setBackground(sectionBg);
		idField.setForeground(POPUP_TEXT);
		editPanel.add(idField);

		JLabel displayLbl = new JLabel(GridScapeSetupStrings.AREA_DISPLAY_NAME);
		styleLabel(displayLbl);
		editPanel.add(displayLbl);
		displayNameField = new JTextField(displayName, 12);
		displayNameField.setMaximumSize(new Dimension(Integer.MAX_VALUE, displayNameField.getPreferredSize().height));
		displayNameField.setBackground(sectionBg);
		displayNameField.setForeground(POPUP_TEXT);
		editPanel.add(displayNameField);

		JLabel descLbl = new JLabel(GridScapeSetupStrings.AREA_DESCRIPTION);
		styleLabel(descLbl);
		editPanel.add(descLbl);
		descriptionField = new JTextArea(description != null ? description : "", 3, 20);
		descriptionField.setLineWrap(true);
		descriptionField.setWrapStyleWord(true);
		descriptionField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 80));
		descriptionField.setBackground(sectionBg);
		descriptionField.setForeground(POPUP_TEXT);
		descriptionField.setCaretColor(POPUP_TEXT);
		editPanel.add(new JScrollPane(descriptionField));

		editPanel.add(new JLabel(" "));
		JLabel cornersHint = new JLabel("<html>Corners: Shift+RMB to add; Shift+RMB corner to Move, then Set.</html>");
		styleLabel(cornersHint);
		cornersHint.setToolTipText("Shift+Right-click to add corner; Shift+Right-click a corner to Move it, then click another tile to Set.");
		editPanel.add(cornersHint);
		cornersPanel = new JPanel();
		cornersPanel.setLayout(new BoxLayout(cornersPanel, BoxLayout.Y_AXIS));
		cornersPanel.setBackground(sectionBg);
		cornersPanel.setOpaque(sectionOpaque);
		JScrollPane cornersScroll = new JScrollPane(cornersPanel);
		cornersScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		cornersScroll.setOpaque(sectionOpaque);
		cornersScroll.getViewport().setBackground(sectionBg);
		cornersScroll.getViewport().setOpaque(sectionOpaque);
		int maxCornersHeight = 200;
		cornersScroll.setPreferredSize(new Dimension(Integer.MAX_VALUE, maxCornersHeight));
		cornersScroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, maxCornersHeight));
		editPanel.add(cornersScroll);

		editPanel.add(new JLabel(" "));
		holesCountLabel = new JLabel();
		styleLabel(holesCountLabel);
		editPanel.add(holesCountLabel);
		holesListPanel = new JPanel();
		holesListPanel.setLayout(new BoxLayout(holesListPanel, BoxLayout.Y_AXIS));
		holesListPanel.setBackground(sectionBg);
		holesListPanel.setOpaque(sectionOpaque);
		JScrollPane holesScroll = new JScrollPane(holesListPanel);
		holesScroll.setOpaque(sectionOpaque);
		holesScroll.getViewport().setBackground(sectionBg);
		holesScroll.getViewport().setOpaque(sectionOpaque);
		int maxHolesHeight = 80;
		holesScroll.setPreferredSize(new Dimension(Integer.MAX_VALUE, maxHolesHeight));
		holesScroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, maxHolesHeight));
		editPanel.add(holesScroll);
		refreshHolesDisplay();

		refreshMakeHoleCombos();

		editPanel.add(new JLabel(" "));
		JLabel neighborsLbl = new JLabel("Neighbors:");
		styleLabel(neighborsLbl);
		editPanel.add(neighborsLbl);
		neighborsPanel = new JPanel();
		neighborsPanel.setLayout(new BoxLayout(neighborsPanel, BoxLayout.Y_AXIS));
		neighborsPanel.setBackground(sectionBg);
		neighborsPanel.setOpaque(sectionOpaque);
		List<String> neighborsToShow = (plugin.getEditingNeighbors() != null) ? plugin.getEditingNeighbors() : neighbors;
		List<Area> others = new ArrayList<>(areaGraphService.getAreas());
		others.removeIf(a -> a.getId().equals(areaId));
		others.sort(AREA_DISPLAY_ORDER);
		for (Area other : others)
		{
			JCheckBox cb = new JCheckBox(other.getDisplayName() != null ? other.getDisplayName() : other.getId());
			cb.setForeground(POPUP_TEXT);
			cb.setBackground(sectionBg);
			cb.setOpaque(sectionOpaque);
			cb.setName(other.getId());
			cb.setSelected(neighborsToShow.contains(other.getId()));
			cb.addItemListener(e -> syncNeighborsToPlugin());
			neighborsPanel.add(cb);
		}
		JScrollPane neighborsScroll = new JScrollPane(neighborsPanel);
		neighborsScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		neighborsScroll.setOpaque(sectionOpaque);
		neighborsScroll.getViewport().setBackground(sectionBg);
		neighborsScroll.getViewport().setOpaque(sectionOpaque);
		int maxNeighborsHeight = 120;
		neighborsScroll.setPreferredSize(new Dimension(Integer.MAX_VALUE, maxNeighborsHeight));
		neighborsScroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, maxNeighborsHeight));
		editPanel.add(neighborsScroll);

		JLabel costLbl = new JLabel(GridScapeSetupStrings.AREA_UNLOCK_COST);
		styleLabel(costLbl);
		editPanel.add(costLbl);
		unlockCostSpinner = new JSpinner(new SpinnerNumberModel(unlockCost, 0, 9999, 1));
		styleSpinner(unlockCostSpinner);
		editPanel.add(unlockCostSpinner);

		JLabel ptsLbl = new JLabel(GridScapeSetupStrings.AREA_POINTS_TO_COMPLETE);
		styleLabel(ptsLbl);
		editPanel.add(ptsLbl);
		pointsToCompleteSpinner = new JSpinner(new SpinnerNumberModel(pointsToComplete, 0, 9999, 1));
		styleSpinner(pointsToCompleteSpinner);
		editPanel.add(pointsToCompleteSpinner);

		editPanel.add(new JLabel(" "));
		JPanel saveCancel = new JPanel();
		saveCancel.setBackground(sectionBg);
		saveCancel.setOpaque(sectionOpaque);
		saveBtn = new JButton("Save");
		styleButton(saveBtn);
		saveBtn.addActionListener(e -> saveArea(areaId));
		cancelBtn = new JButton(GridScapeSetupStrings.AREA_CANCEL);
		styleButton(cancelBtn);
		cancelBtn.addActionListener(e -> cancelEdit());
		saveCancel.add(saveBtn);
		saveCancel.add(cancelBtn);
		editPanel.add(saveCancel);

		refreshCornersDisplay(plugin.getEditingCorners());
		editPanel.revalidate();
		editPanel.repaint();
	}

	private void styleSpinner(JSpinner spinner)
	{
		spinner.setBackground(sectionBg);
		spinner.setForeground(POPUP_TEXT);
		if (spinner.getEditor() instanceof JSpinner.DefaultEditor)
		{
			((JSpinner.DefaultEditor) spinner.getEditor()).getTextField().setBackground(sectionBg);
			((JSpinner.DefaultEditor) spinner.getEditor()).getTextField().setForeground(POPUP_TEXT);
		}
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
			JOptionPane.showMessageDialog(this, GridScapeSetupStrings.AREA_MAKE_HOLE_DIFFERENT_POLYGONS, GridScapeSetupStrings.AREA_MAKE_HOLE_TITLE, JOptionPane.WARNING_MESSAGE);
			return;
		}
		List<List<int[]>> all = plugin.getAllEditingPolygons();
		if (outerIdx >= all.size() || innerIdx >= all.size()) return;
		List<int[]> outerPoly = all.get(outerIdx);
		List<int[]> innerPoly = all.get(innerIdx);
		if (outerPoly.size() < 3 || innerPoly.size() < 3)
		{
			JOptionPane.showMessageDialog(this, "Both polygons need at least 3 vertices.", "Make hole", JOptionPane.WARNING_MESSAGE);
			return;
		}
		if (!areaGraphService.isPolygonInsidePolygon(outerPoly, innerPoly))
		{
			JOptionPane.showMessageDialog(this, GridScapeSetupStrings.AREA_MAKE_HOLE_INNER_INSIDE, GridScapeSetupStrings.AREA_MAKE_HOLE_TITLE, JOptionPane.WARNING_MESSAGE);
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
		holesCountLabel.setText(GridScapeSetupStrings.areaHolesCountLabel(editingHoles.size()));
		holesListPanel.removeAll();
		for (int i = 0; i < editingHoles.size(); i++)
		{
			List<int[]> h = editingHoles.get(i);
			JLabel l = new JLabel(GridScapeSetupStrings.areaHoleRowLabel(i, h.size()));
			styleLabel(l);
			holesListPanel.add(l);
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
			row.setBackground(sectionBg);
			row.setOpaque(sectionOpaque);
			JButton removeBtn = new JButton(GridScapeSetupStrings.AREA_ROW_REMOVE);
			styleButton(removeBtn);
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
			JLabel coordLbl = new JLabel(GridScapeSetupStrings.areaCornerRowLabel(i, p[0], p[1], p[2]));
			styleLabel(coordLbl);
			row.add(coordLbl, BorderLayout.CENTER);
			cornersPanel.add(row);
		}
		cornersPanel.revalidate();
		cornersPanel.repaint();
		if (makeHoleOuterCombo != null) refreshMakeHoleCombos();
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
			return;
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
		List<List<int[]>> holesToSave = new ArrayList<>();
		List<List<int[]>> sourceHoles = plugin.getEditingHoles() != null ? plugin.getEditingHoles() : editingHoles;
		for (List<int[]> h : sourceHoles) holesToSave.add(new ArrayList<>(h));
		Area area = Area.builder()
			.id(id)
			.displayName(displayName)
			.description(description)
			.polygons(allPolygons)
			.holes(holesToSave)
			.includes(Collections.emptyList())
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
				JOptionPane.showMessageDialog(this,
					GridScapeSetupStrings.areaImportSuccess(count, file.getName()),
					GridScapeSetupStrings.AREA_IMPORT_COMPLETE_TITLE, JOptionPane.INFORMATION_MESSAGE);
			}
			catch (IllegalArgumentException ex)
			{
				JOptionPane.showMessageDialog(this, GridScapeSetupStrings.areaImportInvalidJson(ex.getMessage()), GridScapeSetupStrings.AREA_IMPORT_ERROR_TITLE, JOptionPane.ERROR_MESSAGE);
			}
			catch (Exception ex)
			{
				JOptionPane.showMessageDialog(this, GridScapeSetupStrings.areaImportFailed(ex.getMessage()), GridScapeSetupStrings.AREA_IMPORT_ERROR_TITLE, JOptionPane.ERROR_MESSAGE);
			}
		}
	}

	private void exportAreaJson()
	{
		File file = com.gridscape.util.GridScapeSwingUtil.showJsonSaveDialog(this, com.gridscape.util.ResourcePaths.DEFAULT_AREAS_FILENAME);
		if (file == null) return;
		try
		{
			String json = areaGraphService.exportAreasToJson();
			Files.write(file.toPath(), json.getBytes(StandardCharsets.UTF_8));
			JOptionPane.showMessageDialog(this, GridScapeSetupStrings.areaExportSuccess(areaGraphService.getAreas().size(), file.getName()), GridScapeSetupStrings.AREA_EXPORT_COMPLETE_TITLE, JOptionPane.INFORMATION_MESSAGE);
		}
		catch (Exception ex)
		{
			JOptionPane.showMessageDialog(this, GridScapeSetupStrings.areaExportFailed(ex.getMessage()), GridScapeSetupStrings.AREA_EXPORT_ERROR_TITLE, JOptionPane.ERROR_MESSAGE);
		}
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
		if (editPanel == null) return;
		editPanel.removeAll();
		editPanel.revalidate();
		editPanel.repaint();
	}
}

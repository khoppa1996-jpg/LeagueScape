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

/**
 * LeagueScape side panel: shows current area, points, and unlock buttons. In points-to-complete
 * mode also shows area completion progress. "Tasks" opens the task grid for the current area;
 * "Rules & Setup" is reserved for future config/setup UI.
 */
public class LeagueScapePanel extends PluginPanel
{
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
	private final net.runelite.client.audio.AudioPlayer audioPlayer;

	public LeagueScapePanel(LeagueScapePlugin plugin, LeagueScapeConfig config, ConfigManager configManager,
		AreaGraphService areaGraphService, PointsService pointsService, AreaCompletionService areaCompletionService,
		net.runelite.client.audio.AudioPlayer audioPlayer)
	{
		this.plugin = plugin;
		this.config = config;
		this.configManager = configManager;
		this.areaGraphService = areaGraphService;
		this.pointsService = pointsService;
		this.areaCompletionService = areaCompletionService;
		this.audioPlayer = audioPlayer;
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

		JButton tasksBtn = new JButton("Tasks");
		tasksBtn.addActionListener(e -> plugin.openTasksForCurrentArea());
		content.add(tasksBtn);

		JButton rulesSetupBtn = new JButton("Rules & Setup");
		rulesSetupBtn.addActionListener(e -> { /* TODO: open config / setup */ });
		content.add(rulesSetupBtn);

		add(content, BorderLayout.NORTH);
	}

	/** Updates the points label to show spendable and total earned. */
	private void refreshPointsLabel()
	{
		pointsLabel.setText("Points: " + pointsService.getSpendable() + " / " + pointsService.getEarnedTotal());
	}

	/**
	 * Rebuilds the unlock buttons from getUnlockableNeighbors (and in points-to-complete mode
	 * only neighbors of completed areas). Each button shows area name and cost; disabled if not enough points.
	 */
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

	/** Fills completionPanel with one line per unlocked area: name, earned/needed, status (and ✓ if complete). */
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

	/** Refreshes points label and unlock buttons (and completion panel if in points-to-complete mode). Call when points or unlock state may have changed. */
	public void refresh()
	{
		refreshPointsLabel();
		refreshUnlockButtons();
	}

	/** Attempts to unlock the area via plugin; on success plays sound and refreshes panel labels and buttons. */
	private void unlockArea(String areaId, int cost)
	{
		if (plugin.unlockArea(areaId, cost))
		{
			LeagueScapeSounds.play(audioPlayer, LeagueScapeSounds.LOCKED);
			refreshPointsLabel();
			refreshCurrentAreaLabel(areaId);
			refreshUnlockButtons();
		}
		else
		{
			LeagueScapeSounds.play(audioPlayer, LeagueScapeSounds.WRONG);
		}
	}

	/** Sets the "Current area" label to the display name of the given area (or areaId if area not found). */
	private void refreshCurrentAreaLabel(String areaId)
	{
		Area area = areaGraphService.getArea(areaId);
		String displayName = area != null ? area.getDisplayName() : areaId;
		currentAreaLabel.setText("Current area: " + displayName);
	}

	/** Returns the plugin panel icon from resources, or a small teal placeholder if missing. */
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
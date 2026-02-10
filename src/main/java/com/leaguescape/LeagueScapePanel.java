package com.leaguescape;

import com.leaguescape.area.AreaGraphService;
import com.leaguescape.data.Area;
import com.leaguescape.points.PointsService;
import java.awt.BorderLayout;
import java.awt.image.BufferedImage;
import java.util.Set;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.ImageUtil;

public class LeagueScapePanel extends PluginPanel
{
	private final LeagueScapePlugin plugin;
	private final LeagueScapeConfig config;
	private final AreaGraphService areaGraphService;
	private final PointsService pointsService;

	private final JLabel currentAreaLabel;
	private final JLabel pointsLabel;

	public LeagueScapePanel(LeagueScapePlugin plugin, LeagueScapeConfig config,
		AreaGraphService areaGraphService, PointsService pointsService)
	{
		this.plugin = plugin;
		this.config = config;
		this.areaGraphService = areaGraphService;
		this.pointsService = pointsService;
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
		content.add(new JLabel("Next unlock (affordable):"));
		for (Area a : areaGraphService.getUnlockableNeighbors())
		{
			int cost = areaGraphService.getCost(a.getId());
			if (pointsService.getSpendable() >= cost)
			{
				JButton unlockBtn = new JButton(a.getDisplayName() + " (" + cost + " pts)");
				unlockBtn.addActionListener(e -> unlockArea(a.getId(), cost));
				content.add(unlockBtn);
			}
		}

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

	private void unlockArea(String areaId, int cost)
	{
		if (plugin.unlockArea(areaId, cost))
		{
			refreshPointsLabel();
			// TODO: refresh unlock buttons (rebuild panel or update list)
		}
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

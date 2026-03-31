package com.gridscape.worldunlock;

import com.gridscape.GridScapeSounds;
import java.awt.BorderLayout;
import java.awt.Color;
import java.util.List;
import java.util.Set;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import net.runelite.api.Client;
import net.runelite.client.audio.AudioPlayer;

/**
 * Panel listing goals from goals.json; each goal shows progress or done/not-done.
 * For conditionType "world_unlock", complete when worldUnlockId is in unlocked set (stub).
 */
public class GoalTrackingPanel extends JPanel
{
	private static final Color BG = new Color(40, 38, 35);
	private static final Color TEXT = new Color(220, 215, 205);
	private static final Color DONE = new Color(120, 200, 120);

	private final GoalTrackingService goalTrackingService;
	private final WorldUnlockService worldUnlockService;
	private final Runnable onClose;
	private final Client client;
	private final AudioPlayer audioPlayer;

	private JPanel listPanel;

	public GoalTrackingPanel(GoalTrackingService goalTrackingService, WorldUnlockService worldUnlockService,
		Runnable onClose, Client client, AudioPlayer audioPlayer)
	{
		this.goalTrackingService = goalTrackingService;
		this.worldUnlockService = worldUnlockService;
		this.onClose = onClose;
		this.client = client;
		this.audioPlayer = audioPlayer;
		setLayout(new BorderLayout(8, 8));
		setBackground(BG);
		setBorder(new javax.swing.border.EmptyBorder(10, 12, 10, 12));

		JLabel title = new JLabel("Goals");
		title.setForeground(TEXT);
		add(title, BorderLayout.NORTH);

		listPanel = new JPanel();
		listPanel.setLayout(new BoxLayout(listPanel, BoxLayout.Y_AXIS));
		listPanel.setBackground(BG);
		add(new JScrollPane(listPanel), BorderLayout.CENTER);

		JButton closeBtn = new JButton("Close");
		closeBtn.setForeground(TEXT);
		closeBtn.addActionListener(e -> {
			if (audioPlayer != null && client != null)
				GridScapeSounds.play(audioPlayer, GridScapeSounds.BUTTON_PRESS, client);
			if (onClose != null) onClose.run();
		});
		JPanel south = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.TRAILING));
		south.setBackground(BG);
		south.add(closeBtn);
		add(south, BorderLayout.SOUTH);

		refresh();
	}

	public void refresh()
	{
		listPanel.removeAll();
		goalTrackingService.load();
		Set<String> unlocked = worldUnlockService.getUnlockedIds();
		List<Goal> goals = goalTrackingService.getGoals();
		for (Goal g : goals)
		{
			boolean done = isGoalComplete(g, unlocked);
			JPanel row = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 2));
			row.setBackground(BG);
			JLabel nameLabel = new JLabel(g.getDisplayName() != null ? g.getDisplayName() : g.getId());
			nameLabel.setForeground(done ? DONE : TEXT);
			row.add(nameLabel);
			JLabel doneLabel = new JLabel(done ? " ✓" : "");
			doneLabel.setForeground(DONE);
			row.add(doneLabel);
			if (g.getDescription() != null && !g.getDescription().isEmpty())
			{
				JLabel desc = new JLabel(" — " + g.getDescription());
				desc.setForeground(TEXT);
				row.add(desc);
			}
			listPanel.add(row);
		}
		if (goals.isEmpty())
		{
			JLabel empty = new JLabel("No goals defined.");
			empty.setForeground(TEXT);
			listPanel.add(empty);
		}
		listPanel.revalidate();
		listPanel.repaint();
	}

	private boolean isGoalComplete(Goal g, Set<String> unlocked)
	{
		if ("world_unlock".equalsIgnoreCase(g.getConditionType()) && g.getWorldUnlockId() != null)
			return unlocked.contains(g.getWorldUnlockId());
		// Stub: other condition types not implemented
		return false;
	}
}

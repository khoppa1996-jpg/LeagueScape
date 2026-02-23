package com.leaguescape.worldunlock;

import com.leaguescape.LeagueScapePlugin;
import com.leaguescape.LeagueScapeSounds;
import com.leaguescape.points.PointsService;
import com.leaguescape.task.TaskDefinition;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.util.List;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import net.runelite.api.Client;
import net.runelite.client.audio.AudioPlayer;

/**
 * Panel showing the global task list (World Unlock mode): tasks from all unlocked tiles,
 * with Complete / Claim actions and points display.
 */
public class GlobalTaskListPanel extends JPanel
{
	private static final Color BG = new Color(40, 38, 35);
	private static final Color TEXT = new Color(220, 215, 205);

	private final GlobalTaskListService globalTaskListService;
	private final PointsService pointsService;
	private final Runnable onClose;
	private final Client client;
	private final AudioPlayer audioPlayer;

	private JLabel pointsLabel;
	private JPanel listPanel;

	public GlobalTaskListPanel(GlobalTaskListService globalTaskListService, PointsService pointsService,
		Runnable onClose, Client client, AudioPlayer audioPlayer)
	{
		this.globalTaskListService = globalTaskListService;
		this.pointsService = pointsService;
		this.onClose = onClose;
		this.client = client;
		this.audioPlayer = audioPlayer;
		setLayout(new BorderLayout(8, 8));
		setBackground(BG);
		setBorder(new javax.swing.border.EmptyBorder(10, 12, 10, 12));

		pointsLabel = new JLabel();
		pointsLabel.setForeground(TEXT);
		add(pointsLabel, BorderLayout.NORTH);

		listPanel = new JPanel();
		listPanel.setLayout(new BoxLayout(listPanel, BoxLayout.Y_AXIS));
		listPanel.setBackground(BG);
		add(new JScrollPane(listPanel), BorderLayout.CENTER);

		JButton closeBtn = new JButton("Close");
		closeBtn.setForeground(TEXT);
		closeBtn.addActionListener(e -> {
			if (audioPlayer != null && client != null)
				LeagueScapeSounds.play(audioPlayer, LeagueScapeSounds.BUTTON_PRESS, client);
			if (onClose != null) onClose.run();
		});
		JPanel south = new JPanel(new FlowLayout(FlowLayout.TRAILING));
		south.setBackground(BG);
		south.add(closeBtn);
		add(south, BorderLayout.SOUTH);

		refresh();
	}

	public void refresh()
	{
		int spendable = pointsService.getEarnedTotal() - pointsService.getSpentTotal();
		pointsLabel.setText("Points: " + spendable);

		listPanel.removeAll();
		List<TaskDefinition> tasks = globalTaskListService.getGlobalTasks();
		for (TaskDefinition task : tasks)
		{
			String key = GlobalTaskListService.taskKey(task);
			boolean completed = globalTaskListService.isCompleted(key);
			boolean claimed = globalTaskListService.isClaimed(key);

			JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
			row.setBackground(BG);
			JLabel nameLabel = new JLabel(task.getDisplayName() != null ? task.getDisplayName() : key);
			nameLabel.setForeground(TEXT);
			row.add(nameLabel);
			int pts = globalTaskListService.getPointsForDifficulty(task.getDifficulty());
			JLabel pointsVal = new JLabel("(" + pts + " pts)");
			pointsVal.setForeground(TEXT);
			row.add(pointsVal);

			if (claimed)
			{
				JLabel claimedLabel = new JLabel("Claimed");
				claimedLabel.setForeground(new Color(120, 200, 120));
				row.add(claimedLabel);
			}
			else if (completed)
			{
				JButton claimBtn = new JButton("Claim");
				claimBtn.setForeground(TEXT);
				claimBtn.addActionListener(e -> {
					if (audioPlayer != null && client != null)
						LeagueScapeSounds.play(audioPlayer, LeagueScapeSounds.TASK_COMPLETE, client);
					globalTaskListService.claimTask(key);
					SwingUtilities.invokeLater(this::refresh);
				});
				row.add(claimBtn);
			}
			else
			{
				JButton completeBtn = new JButton("Complete");
				completeBtn.setForeground(TEXT);
				completeBtn.addActionListener(e -> {
					if (audioPlayer != null && client != null)
						LeagueScapeSounds.play(audioPlayer, LeagueScapeSounds.BUTTON_PRESS, client);
					globalTaskListService.setCompleted(key);
					SwingUtilities.invokeLater(this::refresh);
				});
				row.add(completeBtn);
			}
			listPanel.add(row);
		}
		if (tasks.isEmpty())
		{
			JLabel empty = new JLabel("No tasks yet. Unlock tiles on the World Unlock grid to add tasks.");
			empty.setForeground(TEXT);
			listPanel.add(empty);
		}
		listPanel.revalidate();
		listPanel.repaint();
	}
}

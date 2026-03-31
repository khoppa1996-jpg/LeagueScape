package com.leaguescape.util;

import com.leaguescape.LeagueScapePlugin;
import com.leaguescape.LeagueScapeSounds;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Frame;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import net.runelite.api.Client;
import net.runelite.client.audio.AudioPlayer;
import net.runelite.client.util.ImageUtil;

/**
 * Small undecorated popup matching task-detail styling; shown when a task-grid ring-completion bonus is awarded.
 */
public final class RingBonusPopup
{
	private static final Color POPUP_BG = LeagueScapeColors.POPUP_BG;
	private static final Color POPUP_TEXT = LeagueScapeColors.POPUP_TEXT;
	private static final Color POPUP_BORDER = new Color(0x2a, 0x28, 0x24);
	private static final Color BONUS_ACCENT = new Color(120, 200, 120);
	private static final Dimension OK_BUTTON_SIZE = new Dimension(160, 28);

	private RingBonusPopup() {}

	/**
	 * Shows a ring bonus notification, or no-ops if {@code bonusPoints} &lt;= 0.
	 * Safe to call from any thread; display runs on the EDT.
	 *
	 * @param globalGrid   if true, subtitle says "Global task grid"; else uses area display name
	 * @param areaLabel    when {@code globalGrid} is false, shown as "Area: …"
	 */
	public static void showAsync(Frame frameOwner, Component locationRelative, Client client, AudioPlayer audioPlayer,
		int ringNumber, int bonusPoints, boolean globalGrid, String areaLabel)
	{
		if (bonusPoints <= 0) return;
		SwingUtilities.invokeLater(() -> showInternal(frameOwner, locationRelative, client, audioPlayer,
			ringNumber, bonusPoints, globalGrid, areaLabel));
	}

	private static void showInternal(Frame frameOwner, Component locationRelative, Client client, AudioPlayer audioPlayer,
		int ringNumber, int bonusPoints, boolean globalGrid, String areaLabel)
	{
		if (audioPlayer != null && client != null)
			LeagueScapeSounds.play(audioPlayer, LeagueScapeSounds.COINS_JINGLE, client);

		JDialog detail = new JDialog(frameOwner, "Ring bonus", false);
		detail.setUndecorated(true);
		LeagueScapePlugin.registerEscapeToClose(detail);

		JPanel content = new JPanel(new BorderLayout(8, 8));
		content.setBackground(POPUP_BG);
		content.setBorder(new CompoundBorder(
			new LineBorder(POPUP_BORDER, 2),
			new EmptyBorder(12, 14, 12, 14)));

		JPanel header = new JPanel(new BorderLayout(4, 0));
		header.setOpaque(false);
		JLabel titleLabel = new JLabel("Ring " + ringNumber + " complete");
		titleLabel.setForeground(POPUP_TEXT);
		titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 13f));
		header.add(titleLabel, BorderLayout.CENTER);
		java.awt.image.BufferedImage xBtnImg = ImageUtil.loadImageResource(LeagueScapePlugin.class, "x_button.png");
		JButton closeBtn = LeagueScapeSwingUtil.newPopupButtonWithIcon(xBtnImg, POPUP_TEXT);
		closeBtn.addActionListener(e -> {
			if (audioPlayer != null && client != null)
				LeagueScapeSounds.play(audioPlayer, LeagueScapeSounds.BUTTON_PRESS, client);
			detail.dispose();
		});
		header.add(closeBtn, BorderLayout.EAST);
		content.add(header, BorderLayout.NORTH);

		JPanel body = new JPanel();
		body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
		body.setOpaque(false);

		String scope = globalGrid
			? "Global task grid"
			: ("Area: " + (areaLabel != null && !areaLabel.trim().isEmpty() ? areaLabel.trim() : "—"));
		JLabel scopeLabel = new JLabel("<html>" + scope + "</html>");
		scopeLabel.setForeground(POPUP_TEXT);
		body.add(scopeLabel);
		body.add(new JLabel(" "));

		JLabel bonusLabel = new JLabel("<html><b>+" + bonusPoints + "</b> bonus points</html>");
		bonusLabel.setForeground(BONUS_ACCENT);
		bonusLabel.setFont(bonusLabel.getFont().deriveFont(Font.BOLD, 14f));
		body.add(bonusLabel);

		java.awt.image.BufferedImage buttonRect = ImageUtil.loadImageResource(LeagueScapePlugin.class, "empty_button_rectangle.png");
		JButton okBtn = LeagueScapeSwingUtil.newRectangleButton("OK", buttonRect, POPUP_TEXT);
		okBtn.setPreferredSize(OK_BUTTON_SIZE);
		okBtn.setAlignmentX(Component.LEFT_ALIGNMENT);
		body.add(new JLabel(" "));
		okBtn.addActionListener(e -> {
			if (audioPlayer != null && client != null)
				LeagueScapeSounds.play(audioPlayer, LeagueScapeSounds.BUTTON_PRESS, client);
			detail.dispose();
		});
		body.add(okBtn);

		content.add(body, BorderLayout.CENTER);
		detail.setContentPane(content);
		detail.getRootPane().setBorder(new LineBorder(POPUP_BORDER, 2));
		detail.addWindowFocusListener(new java.awt.event.WindowAdapter()
		{
			@Override
			public void windowLostFocus(java.awt.event.WindowEvent e)
			{
				SwingUtilities.invokeLater(() -> {
					if (detail.isDisplayable()) detail.dispose();
				});
			}
		});
		detail.pack();
		if (locationRelative != null)
			detail.setLocationRelativeTo(locationRelative);
		else if (client != null && client.getCanvas() != null)
			detail.setLocationRelativeTo(client.getCanvas());
		detail.setVisible(true);
		detail.requestFocusInWindow();
	}
}

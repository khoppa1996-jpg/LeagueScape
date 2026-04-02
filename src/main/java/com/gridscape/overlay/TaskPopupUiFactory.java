package com.gridscape.overlay;

import com.gridscape.util.GridScapeSwingUtil;
import com.gridscape.util.ScaledImageCache;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Insets;
import java.awt.image.BufferedImage;
import javax.swing.JButton;
import net.runelite.client.util.ImageUtil;

/**
 * Popup chrome shared by {@link GridScapeMapOverlay} task/area dialogs: rectangle buttons and pressed shadow.
 */
public final class TaskPopupUiFactory
{
	public static final Dimension RECTANGLE_BUTTON_SIZE = new Dimension(160, 28);

	private TaskPopupUiFactory()
	{
	}

	public static JButton newRectangleButton(String text, BufferedImage buttonRect, Color textColor)
	{
		BufferedImage img = buttonRect;
		JButton b = new JButton(text)
		{
			@Override
			protected void paintComponent(Graphics g)
			{
				if (img != null)
				{
					ScaledImageCache.drawScaled(g, img, 0, 0, getWidth(), getHeight());
					g.setColor(getForeground());
					g.setFont(getFont());
					java.awt.FontMetrics fm = g.getFontMetrics();
					String t = getText();
					int x = (getWidth() - fm.stringWidth(t)) / 2;
					int y = (getHeight() + fm.getAscent() - fm.getDescent()) / 2;
					g.drawString(t, x, y);
				}
				else
				{
					super.paintComponent(g);
				}
				if (getModel().isPressed())
				{
					g.setColor(GridScapeSwingUtil.PRESSED_INSET_SHADOW);
					g.fillRect(GridScapeSwingUtil.PRESSED_INSET, GridScapeSwingUtil.PRESSED_INSET,
						getWidth() - 2 * GridScapeSwingUtil.PRESSED_INSET, getHeight() - 2 * GridScapeSwingUtil.PRESSED_INSET);
				}
			}
		};
		b.setForeground(textColor);
		b.setFocusPainted(false);
		b.setBorderPainted(false);
		b.setContentAreaFilled(img == null);
		b.setOpaque(img == null);
		b.setPreferredSize(RECTANGLE_BUTTON_SIZE);
		return b;
	}

	public static JButton newPopupButton(String text)
	{
		JButton b = new JButton(text)
		{
			@Override
			protected void paintComponent(Graphics g)
			{
				super.paintComponent(g);
				if (getModel().isPressed())
				{
					g.setColor(GridScapeSwingUtil.PRESSED_INSET_SHADOW);
					g.fillRect(GridScapeSwingUtil.PRESSED_INSET, GridScapeSwingUtil.PRESSED_INSET,
						getWidth() - 2 * GridScapeSwingUtil.PRESSED_INSET, getHeight() - 2 * GridScapeSwingUtil.PRESSED_INSET);
				}
			}
		};
		b.setFocusPainted(false);
		return b;
	}

	public static JButton newPopupButtonWithIcon(BufferedImage iconImg, Color fallbackTextColor)
	{
		JButton b = new JButton()
		{
			@Override
			protected void paintComponent(Graphics g)
			{
				super.paintComponent(g);
				if (getModel().isPressed())
				{
					g.setColor(GridScapeSwingUtil.PRESSED_INSET_SHADOW);
					g.fillRect(GridScapeSwingUtil.PRESSED_INSET, GridScapeSwingUtil.PRESSED_INSET,
						getWidth() - 2 * GridScapeSwingUtil.PRESSED_INSET, getHeight() - 2 * GridScapeSwingUtil.PRESSED_INSET);
				}
			}
		};
		b.setFocusPainted(false);
		b.setBorderPainted(false);
		b.setContentAreaFilled(false);
		b.setMargin(new Insets(0, 0, 0, 0));
		if (iconImg != null)
			b.setIcon(new javax.swing.ImageIcon(ImageUtil.resizeImage(iconImg, 24, 24)));
		else
		{
			b.setText("X");
			b.setForeground(fallbackTextColor);
		}
		return b;
	}
}

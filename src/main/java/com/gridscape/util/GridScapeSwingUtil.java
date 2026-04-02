package com.gridscape.util;

import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Window;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Insets;
import java.awt.Rectangle;
import java.io.File;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JPanel;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import net.runelite.client.util.ImageUtil;

import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import javax.swing.JComponent;
import javax.swing.JRootPane;
import javax.swing.RootPaneContainer;
import javax.swing.filechooser.FileNameExtensionFilter;

/**
 * Shared Swing helpers for GridScape: scrollable panel, collapsible section,
 * JSON save dialog, popup-style buttons, and Escape-to-close for windows.
 */
public final class GridScapeSwingUtil
{
	/** Used by popup-style buttons for pressed state; exposed for custom buttons that share the same look. */
	public static final Color PRESSED_INSET_SHADOW = new Color(0, 0, 0, 70);
	public static final int PRESSED_INSET = 2;

	private GridScapeSwingUtil() {}

	/** Panel that tracks viewport width in a scroll pane (no horizontal scroll). */
	public static final class ScrollableWidthPanel extends JPanel implements Scrollable
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

	/** Creates a new ScrollableWidthPanel. */
	public static JPanel newScrollableTrackWidthPanel()
	{
		return new ScrollableWidthPanel();
	}

	/**
	 * Builds a collapsible section: header (title + ▼/▶) and content. If headerOut is non-null and length > 0, stores the header button.
	 */
	public static JPanel createCollapsibleSection(String title, Component content, boolean expandedByDefault, JToggleButton[] headerOut)
	{
		JPanel wrapper = new JPanel(new java.awt.BorderLayout());
		wrapper.setAlignmentX(Component.LEFT_ALIGNMENT);
		JToggleButton header = new JToggleButton(expandedByDefault ? "▼ " + title : "▶ " + title, expandedByDefault);
		header.setFocusPainted(false);
		header.setBorderPainted(false);
		header.setContentAreaFilled(false);
		header.setHorizontalAlignment(SwingConstants.LEFT);
		content.setVisible(expandedByDefault);
		final String titleFinal = title;
		header.addActionListener(e -> {
			boolean on = header.isSelected();
			content.setVisible(on);
			header.setText(on ? "▼ " + titleFinal : "▶ " + titleFinal);
			wrapper.revalidate();
			for (Container p = wrapper.getParent(); p != null; p = p.getParent())
				p.revalidate();
			wrapper.repaint();
		});
		wrapper.add(header, java.awt.BorderLayout.NORTH);
		wrapper.add(content, java.awt.BorderLayout.CENTER);
		if (headerOut != null && headerOut.length > 0)
			headerOut[0] = header;
		return wrapper;
	}

	/**
	 * Shows a save dialog for JSON files with the given default filename. Ensures the selected file ends with .json.
	 * @return the selected file (with .json appended if needed), or null if cancelled
	 */
	public static File showJsonSaveDialog(Component parent, String defaultFileName)
	{
		JFileChooser chooser = new JFileChooser();
		chooser.setDialogTitle("Save JSON");
		chooser.setSelectedFile(new File(defaultFileName != null ? defaultFileName : "export.json"));
		chooser.setFileFilter(new FileNameExtensionFilter("JSON files", "json"));
		if (chooser.showSaveDialog(parent) != JFileChooser.APPROVE_OPTION)
			return null;
		File file = chooser.getSelectedFile();
		if (file != null && !file.getName().toLowerCase().endsWith(".json"))
			file = new File(file.getParent(), file.getName() + ".json");
		return file;
	}

	/** Button with rectangle image background and pressed shadow. Use GridScapeColors.POPUP_TEXT for text color. */
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
					super.paintComponent(g);
				if (getModel().isPressed())
				{
					g.setColor(PRESSED_INSET_SHADOW);
					g.fillRect(PRESSED_INSET, PRESSED_INSET, getWidth() - 2 * PRESSED_INSET, getHeight() - 2 * PRESSED_INSET);
				}
			}
		};
		b.setForeground(textColor != null ? textColor : GridScapeColors.POPUP_TEXT);
		b.setFocusPainted(false);
		b.setBorderPainted(false);
		b.setContentAreaFilled(img == null);
		b.setOpaque(img == null);
		return b;
	}

	/** Icon-only button (e.g. close) with pressed inset shadow. Use GridScapeColors.POPUP_TEXT for fallback text. */
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
					g.setColor(PRESSED_INSET_SHADOW);
					g.fillRect(PRESSED_INSET, PRESSED_INSET, getWidth() - 2 * PRESSED_INSET, getHeight() - 2 * PRESSED_INSET);
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
			b.setForeground(fallbackTextColor != null ? fallbackTextColor : GridScapeColors.POPUP_TEXT);
		}
		return b;
	}

	/**
	 * Makes an undecorated window draggable from {@code dragRegion} (e.g. title row), matching
	 * {@link com.gridscape.config.GridScapeSetupFrame} title-bar behaviour.
	 */
	public static void installUndecoratedWindowDrag(Window window, Component dragRegion)
	{
		if (window == null || dragRegion == null)
			return;
		final int[] dragOffset = new int[2];
		dragRegion.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				java.awt.Point loc = window.getLocationOnScreen();
				dragOffset[0] = e.getXOnScreen() - loc.x;
				dragOffset[1] = e.getYOnScreen() - loc.y;
			}
		});
		dragRegion.addMouseMotionListener(new MouseAdapter()
		{
			@Override
			public void mouseDragged(MouseEvent e)
			{
				window.setLocation(e.getXOnScreen() - dragOffset[0], e.getYOnScreen() - dragOffset[1]);
			}
		});
	}

	/** Registers Escape key to close the given window (dispose). Call after creating a JDialog/JFrame. */
	public static void registerEscapeToClose(java.awt.Window window)
	{
		if (window instanceof RootPaneContainer)
		{
			JRootPane rp = ((RootPaneContainer) window).getRootPane();
			rp.registerKeyboardAction(
				e -> window.dispose(),
				KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
				JComponent.WHEN_IN_FOCUSED_WINDOW);
		}
	}
}

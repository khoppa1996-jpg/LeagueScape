package com.gridscape.util;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import javax.swing.JPanel;

/**
 * OSRS-style tiled frame: {@code fill_color} in the center and {@code border_*} strips on edges.
 * Corner images are optional (nullable). Content should use {@link #getChromeInsets()} for padding.
 * Asset paths match {@link com.gridscape.GridScapePlugin} resources ({@code fill_color.png}, etc.).
 */
public final class GridScapeFrameChromePanel extends JPanel
{
	private static final Color FALLBACK_FILL = new Color(0x54, 0x4D, 0x41);

	private final BufferedImage fill;
	private final BufferedImage tl;
	private final BufferedImage tr;
	private final BufferedImage bl;
	private final BufferedImage br;
	private final BufferedImage bTop;
	private final BufferedImage bBottom;
	private final BufferedImage bLeft;
	private final BufferedImage bRight;
	private final Insets chromeInsets;

	public GridScapeFrameChromePanel(BufferedImage fill, BufferedImage tl, BufferedImage tr, BufferedImage bl, BufferedImage br,
		BufferedImage bTop, BufferedImage bBottom, BufferedImage bLeft, BufferedImage bRight)
	{
		this.fill = fill;
		this.tl = tl;
		this.tr = tr;
		this.bl = bl;
		this.br = br;
		this.bTop = bTop;
		this.bBottom = bBottom;
		this.bLeft = bLeft;
		this.bRight = bRight;
		int tlW = tl != null ? tl.getWidth() : 0;
		int tlH = tl != null ? tl.getHeight() : 0;
		int trW = tr != null ? tr.getWidth() : 0;
		int trH = tr != null ? tr.getHeight() : 0;
		int blW = bl != null ? bl.getWidth() : 0;
		int blH = bl != null ? bl.getHeight() : 0;
		int brW = br != null ? br.getWidth() : 0;
		int brH = br != null ? br.getHeight() : 0;
		int tH = bTop != null ? bTop.getHeight() : 0;
		int bH = bBottom != null ? bBottom.getHeight() : 0;
		int lW = bLeft != null ? bLeft.getWidth() : 0;
		int rW = bRight != null ? bRight.getWidth() : 0;
		int top = Math.max(Math.max(tlH, trH), tH);
		int bot = Math.max(Math.max(blH, brH), bH);
		int left = Math.max(Math.max(tlW, blW), lW);
		int right = Math.max(Math.max(trW, brW), rW);
		chromeInsets = new Insets(top, left, bot, right);
		setOpaque(false);
	}

	public Insets getChromeInsets()
	{
		return new Insets(chromeInsets.top, chromeInsets.left, chromeInsets.bottom, chromeInsets.right);
	}

	@Override
	protected void paintComponent(Graphics g)
	{
		Graphics2D g2 = (Graphics2D) g.create();
		try
		{
			g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
			paintFrame(g2, getWidth(), getHeight());
		}
		finally
		{
			g2.dispose();
		}
		super.paintComponent(g);
	}

	private void paintFrame(Graphics2D g, int w, int h)
	{
		if (w <= 0 || h <= 0)
			return;
		int tlW = tl != null ? tl.getWidth() : 0;
		int tlH = tl != null ? tl.getHeight() : 0;
		int trW = tr != null ? tr.getWidth() : 0;
		int trH = tr != null ? tr.getHeight() : 0;
		int blW = bl != null ? bl.getWidth() : 0;
		int blH = bl != null ? bl.getHeight() : 0;
		int brW = br != null ? br.getWidth() : 0;
		int brH = br != null ? br.getHeight() : 0;
		int tH = bTop != null ? bTop.getHeight() : 1;
		int bH = bBottom != null ? bBottom.getHeight() : 1;
		int lW = bLeft != null ? bLeft.getWidth() : 1;
		int rW = bRight != null ? bRight.getWidth() : 1;

		int leftInset = chromeInsets.left;
		int rightInset = chromeInsets.right;
		int topInset = chromeInsets.top;
		int bottomInset = chromeInsets.bottom;
		int innerW = w - leftInset - rightInset;
		int innerH = h - topInset - bottomInset;
		if (innerW > 0 && innerH > 0)
		{
			if (fill != null)
				tileImage(g, fill, leftInset, topInset, innerW, innerH);
			else
			{
				g.setColor(FALLBACK_FILL);
				g.fillRect(leftInset, topInset, innerW, innerH);
			}
		}

		if (bTop != null && w > tlW + trW)
		{
			int segW = w - tlW - trW;
			if (segW > 0)
				tileImage(g, bTop, tlW, 0, segW, Math.max(1, tH));
		}
		if (bBottom != null && w > blW + brW)
		{
			int segW = w - blW - brW;
			int bh = Math.max(1, bH);
			if (segW > 0)
				tileImage(g, bBottom, blW, h - bh, segW, bh);
		}
		if (bLeft != null && h > tlH + blH)
		{
			int segH = h - tlH - blH;
			if (segH > 0)
				tileImage(g, bLeft, 0, tlH, Math.max(1, lW), segH);
		}
		if (bRight != null && h > trH + brH)
		{
			int segH = h - trH - brH;
			int rw = Math.max(1, rW);
			if (segH > 0)
				tileImage(g, bRight, w - rw, trH, rw, segH);
		}

		if (tl != null)
			g.drawImage(tl, 0, 0, null);
		if (tr != null)
			g.drawImage(tr, w - trW, 0, null);
		if (bl != null)
			g.drawImage(bl, 0, h - blH, null);
		if (br != null)
			g.drawImage(br, w - brW, h - brH, null);
	}

	private static void tileImage(Graphics g, BufferedImage tile, int dx, int dy, int regionW, int regionH)
	{
		if (tile == null || regionW <= 0 || regionH <= 0)
			return;
		int tw = tile.getWidth();
		int th = tile.getHeight();
		if (tw <= 0 || th <= 0)
			return;
		for (int py = 0; py < regionH; py += th)
		{
			int sh = Math.min(th, regionH - py);
			for (int px = 0; px < regionW; px += tw)
			{
				int sw = Math.min(tw, regionW - px);
				g.drawImage(tile, dx + px, dy + py, dx + px + sw, dy + py + sh, 0, 0, sw, sh, null);
			}
		}
	}
}

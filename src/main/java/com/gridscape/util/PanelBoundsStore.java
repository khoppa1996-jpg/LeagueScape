package com.gridscape.util;

import java.awt.Component;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import javax.swing.JDialog;
import net.runelite.client.config.ConfigManager;

/**
 * Persists floating panel bounds in {@link GridScapeConfigConstants#STATE_GROUP} so dialogs reopen
 * where the user left them; cleared on progress reset.
 */
public final class PanelBoundsStore
{
	private PanelBoundsStore() {}

	private static final int MIN_W = 80;
	private static final int MIN_H = 60;

	public static final String KEY_WORLD_UNLOCK = "panelBoundsWorldUnlock";
	public static final String KEY_GLOBAL_TASKS = "panelBoundsGlobalTasks";
	public static final String KEY_AREA_TASK_GRID = "panelBoundsAreaTaskGrid";
	public static final String KEY_GOALS = "panelBoundsGoals";

	public static void applyBounds(JDialog dialog, ConfigManager cm, String key, Component canvas)
	{
		String raw = cm.getConfiguration(GridScapeConfigConstants.STATE_GROUP, key);
		Rectangle r = parseRectangle(raw);
		if (r != null && r.width >= MIN_W && r.height >= MIN_H)
		{
			Rectangle fitted = constrainToVisible(r);
			if (fitted != null)
			{
				dialog.setBounds(fitted);
				return;
			}
		}
		dialog.setLocationRelativeTo(canvas);
	}

	public static void installPersistence(JDialog dialog, ConfigManager cm, String key)
	{
		Runnable save = () -> {
			if (!dialog.isShowing())
			{
				return;
			}
			Rectangle b = dialog.getBounds();
			cm.setConfiguration(GridScapeConfigConstants.STATE_GROUP, key,
				b.x + "," + b.y + "," + b.width + "," + b.height);
		};
		dialog.addComponentListener(new ComponentAdapter()
		{
			@Override
			public void componentMoved(ComponentEvent e)
			{
				save.run();
			}

			@Override
			public void componentResized(ComponentEvent e)
			{
				save.run();
			}
		});
	}

	public static void clearPanelBounds(ConfigManager cm)
	{
		cm.unsetConfiguration(GridScapeConfigConstants.STATE_GROUP, KEY_WORLD_UNLOCK);
		cm.unsetConfiguration(GridScapeConfigConstants.STATE_GROUP, KEY_GLOBAL_TASKS);
		cm.unsetConfiguration(GridScapeConfigConstants.STATE_GROUP, KEY_AREA_TASK_GRID);
		cm.unsetConfiguration(GridScapeConfigConstants.STATE_GROUP, KEY_GOALS);
	}

	private static Rectangle parseRectangle(String raw)
	{
		if (raw == null || raw.isEmpty())
		{
			return null;
		}
		try
		{
			String[] p = raw.split(",");
			if (p.length != 4)
			{
				return null;
			}
			int x = Integer.parseInt(p[0].trim());
			int y = Integer.parseInt(p[1].trim());
			int w = Integer.parseInt(p[2].trim());
			int h = Integer.parseInt(p[3].trim());
			return new Rectangle(x, y, w, h);
		}
		catch (NumberFormatException e)
		{
			return null;
		}
	}

	private static Rectangle virtualScreenBounds()
	{
		GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
		Rectangle union = null;
		for (GraphicsDevice gd : ge.getScreenDevices())
		{
			Rectangle b = gd.getDefaultConfiguration().getBounds();
			union = union == null ? new Rectangle(b) : union.union(b);
		}
		return union;
	}

	/** Returns a position/size on the virtual screen, or null if the saved rect is unusable. */
	private static Rectangle constrainToVisible(Rectangle r)
	{
		Rectangle union = virtualScreenBounds();
		if (union == null || !union.intersects(r))
		{
			return null;
		}
		Rectangle inter = r.intersection(union);
		if (inter.width * inter.height < MIN_W * MIN_H / 4)
		{
			return null;
		}
		int w = r.width;
		int h = r.height;
		int x = r.x;
		int y = r.y;
		x = Math.max(union.x, Math.min(x, union.x + union.width - w));
		y = Math.max(union.y, Math.min(y, union.y + union.height - h));
		if (x + w > union.x + union.width)
		{
			x = union.x + union.width - w;
		}
		if (y + h > union.y + union.height)
		{
			y = union.y + union.height - h;
		}
		if (x < union.x)
		{
			x = union.x;
		}
		if (y < union.y)
		{
			y = union.y;
		}
		return new Rectangle(x, y, w, h);
	}
}

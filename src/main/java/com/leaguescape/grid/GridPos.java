package com.leaguescape.grid;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Grid position helpers: parse "row,col", Chebyshev distance, cardinal neighbors. */
public final class GridPos
{
	private GridPos() {}

	public static int[] parse(String pos)
	{
		if (pos == null) return null;
		String p = pos.trim();
		int comma = p.indexOf(',');
		if (comma <= 0 || comma >= p.length() - 1) return null;
		try
		{
			int r = Integer.parseInt(p.substring(0, comma).trim());
			int c = Integer.parseInt(p.substring(comma + 1).trim());
			return new int[]{ r, c };
		}
		catch (NumberFormatException e)
		{
			return null;
		}
	}

	public static String normalize(String pos)
	{
		int[] rc = parse(pos);
		return rc == null ? (pos == null ? "" : pos.trim()) : rc[0] + "," + rc[1];
	}

	public static List<String> neighbors4(String pos)
	{
		int[] rc = parse(pos);
		if (rc == null) return Collections.emptyList();
		return neighbors4(rc[0], rc[1]);
	}

	public static List<String> neighbors4(int row, int col)
	{
		List<String> out = new ArrayList<>(4);
		out.add((row + 1) + "," + col);
		out.add((row - 1) + "," + col);
		out.add(row + "," + (col + 1));
		out.add(row + "," + (col - 1));
		return out;
	}

	public static int chebyshevDist(int r1, int c1, int r2, int c2)
	{
		return Math.max(Math.abs(r1 - r2), Math.abs(c1 - c2));
	}

	/**
	 * Ring index for task grids centered at (0,0): {@code max(|row|,|col|)}. Center is 0; first ring of 8 cells is 1.
	 */
	public static int ringNumber(int row, int col)
	{
		return Math.max(Math.abs(row), Math.abs(col));
	}
}

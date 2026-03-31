package com.leaguescape.grid;

import java.util.ArrayList;
import java.util.List;

/** Spiral ring positions matching TaskGridService order. */
public final class Spiral
{
	private Spiral() {}

	public static List<int[]> ring(int tier)
	{
		if (tier < 1)
			return new ArrayList<>();
		List<int[]> out = new ArrayList<>(8 * tier);
		for (int c = 1 - tier; c <= tier; c++)
			out.add(new int[]{ tier, c });
		for (int r = tier - 1; r >= -tier; r--)
			out.add(new int[]{ r, tier });
		for (int c = tier - 1; c >= -tier; c--)
			out.add(new int[]{ -tier, c });
		for (int r = -tier + 1; r <= tier; r++)
			out.add(new int[]{ r, -tier });
		return out;
	}
}

package com.gridscape.config;

import com.gridscape.data.Area;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.runelite.api.coords.WorldPoint;

/**
 * Holds state and logic for area polygon editing (corners, polygons, holes, neighbors).
 * Used by GridScapePlugin and overlays; plugin remains the composition root and
 * wires callbacks (corner/neighbor updates) and client-dependent actions.
 */
public final class AreaEditState
{
	private String editingAreaId = null;
	private final List<List<int[]>> editingPolygons = new ArrayList<>();
	private final List<int[]> editingCorners = new ArrayList<>();
	private int moveCornerIndex = -1;
	private List<List<int[]>> editingHoles = null;
	private List<String> editingNeighbors = null;

	public void startEditing(String areaId, List<int[]> initialCorners, Area area)
	{
		this.editingAreaId = areaId;
		this.editingPolygons.clear();
		this.editingCorners.clear();
		this.editingHoles = (area != null && area.getHoles() != null) ? new ArrayList<>(area.getHoles()) : new ArrayList<>();
		this.editingNeighbors = (area != null && area.getNeighbors() != null) ? new ArrayList<>(area.getNeighbors()) : new ArrayList<>();
		if (initialCorners != null && !initialCorners.isEmpty())
			this.editingCorners.addAll(initialCorners);
	}

	public void startEditingWithPolygons(String areaId, List<List<int[]>> polygons, Area area)
	{
		this.editingAreaId = areaId;
		this.editingPolygons.clear();
		this.editingCorners.clear();
		this.editingHoles = (area != null && area.getHoles() != null) ? new ArrayList<>(area.getHoles()) : new ArrayList<>();
		this.editingNeighbors = (area != null && area.getNeighbors() != null) ? new ArrayList<>(area.getNeighbors()) : new ArrayList<>();
		if (polygons != null && !polygons.isEmpty())
		{
			for (int i = 0; i < polygons.size() - 1; i++)
			{
				List<int[]> poly = polygons.get(i);
				if (poly != null && poly.size() >= 3)
					this.editingPolygons.add(new ArrayList<>(poly));
			}
			List<int[]> last = polygons.get(polygons.size() - 1);
			if (last != null)
				this.editingCorners.addAll(last);
		}
	}

	public void stopEditing()
	{
		this.editingAreaId = null;
		this.editingPolygons.clear();
		this.editingCorners.clear();
		this.editingHoles = null;
		this.editingNeighbors = null;
		this.moveCornerIndex = -1;
	}

	public List<int[]> getEditingCorners()
	{
		return Collections.unmodifiableList(new ArrayList<>(editingCorners));
	}

	public List<List<int[]>> getEditingPolygons()
	{
		return Collections.unmodifiableList(new ArrayList<>(editingPolygons));
	}

	public List<List<int[]>> getAllEditingPolygons()
	{
		List<List<int[]>> all = new ArrayList<>(editingPolygons);
		if (editingCorners.size() >= 3)
			all.add(new ArrayList<>(editingCorners));
		return all;
	}

	public void startNewPolygon()
	{
		if (editingCorners.size() >= 3)
			editingPolygons.add(new ArrayList<>(editingCorners));
		editingCorners.clear();
		moveCornerIndex = -1;
	}

	/** @return the removed polygon, or null if index invalid */
	public List<int[]> removeEditingPolygonAt(int index)
	{
		if (index < 0) return null;
		if (index < editingPolygons.size())
		{
			List<int[]> removed = new ArrayList<>(editingPolygons.get(index));
			editingPolygons.remove(index);
			return removed;
		}
		if (index == editingPolygons.size() && editingCorners.size() >= 3)
		{
			List<int[]> removed = new ArrayList<>(editingCorners);
			editingCorners.clear();
			moveCornerIndex = -1;
			return removed;
		}
		return null;
	}

	public void removeCorner(int index)
	{
		if (index < 0 || index >= editingCorners.size()) return;
		editingCorners.remove(index);
		if (moveCornerIndex == index) moveCornerIndex = -1;
		else if (moveCornerIndex > index) moveCornerIndex--;
	}

	public void setCornerPosition(int index, WorldPoint wp)
	{
		if (wp == null || index < 0 || index >= editingCorners.size()) return;
		editingCorners.set(index, new int[]{ wp.getX(), wp.getY(), wp.getPlane() });
	}

	public boolean isEditingArea()
	{
		return editingAreaId != null;
	}

	public boolean isAddNewAreaMode()
	{
		return editingAreaId != null && editingAreaId.startsWith("new_");
	}

	public void addCornerFromWorldPoint(WorldPoint wp)
	{
		if (editingAreaId == null || wp == null) return;
		editingCorners.add(new int[]{ wp.getX(), wp.getY(), wp.getPlane() });
	}

	public String getEditingAreaId()
	{
		return editingAreaId;
	}

	public int getMoveCornerIndex()
	{
		return moveCornerIndex;
	}

	public void setMoveCornerIndex(int index)
	{
		this.moveCornerIndex = index;
	}

	public List<List<int[]>> getEditingHoles()
	{
		return editingHoles == null ? null : Collections.unmodifiableList(new ArrayList<>(editingHoles));
	}

	public void setEditingHoles(List<List<int[]>> holes)
	{
		this.editingHoles = (holes != null) ? new ArrayList<>(holes) : new ArrayList<>();
	}

	public List<String> getEditingNeighbors()
	{
		return editingNeighbors == null ? null : Collections.unmodifiableList(new ArrayList<>(editingNeighbors));
	}

	public void setEditingNeighbors(List<String> neighbors)
	{
		this.editingNeighbors = (neighbors != null) ? new ArrayList<>(neighbors) : new ArrayList<>();
	}

	public void setEditingCorners(List<int[]> corners)
	{
		editingCorners.clear();
		if (corners != null)
			editingCorners.addAll(corners);
		moveCornerIndex = -1;
	}

	/** Index of corner at (x, y, plane) or -1. */
	public int findCornerAt(int x, int y, int plane)
	{
		for (int i = 0; i < editingCorners.size(); i++)
		{
			int[] c = editingCorners.get(i);
			if (c.length >= 3 && c[0] == x && c[1] == y && c[2] == plane)
				return i;
		}
		return -1;
	}
}

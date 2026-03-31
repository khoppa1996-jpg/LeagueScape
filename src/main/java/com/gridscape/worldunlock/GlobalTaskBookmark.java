package com.gridscape.worldunlock;

/**
 * Persisted bookmark for the global task hub: stable task key plus grid position and optional label.
 */
public final class GlobalTaskBookmark
{
	private String taskKey;
	private int row;
	private int col;
	private String label;

	public GlobalTaskBookmark()
	{
	}

	public GlobalTaskBookmark(String taskKey, int row, int col, String label)
	{
		this.taskKey = taskKey;
		this.row = row;
		this.col = col;
		this.label = label;
	}

	public String getTaskKey()
	{
		return taskKey;
	}

	public void setTaskKey(String taskKey)
	{
		this.taskKey = taskKey;
	}

	public int getRow()
	{
		return row;
	}

	public void setRow(int row)
	{
		this.row = row;
	}

	public int getCol()
	{
		return col;
	}

	public void setCol(int col)
	{
		this.col = col;
	}

	public String getLabel()
	{
		return label;
	}

	public void setLabel(String label)
	{
		this.label = label;
	}
}

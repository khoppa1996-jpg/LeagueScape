package com.gridscape.worldunlock;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.gridscape.util.ResourceJsonLoader;
import com.gridscape.util.ResourcePaths;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Loads goals from goals.json and exposes the list. Progress (done/not-done) is computed by callers using WorldUnlockService. */
@Singleton
public class GoalTrackingService
{
	private static final Logger log = LoggerFactory.getLogger(GoalTrackingService.class);

	private final List<Goal> goals = new ArrayList<>();
	private boolean loaded = false;

	@Inject
	public GoalTrackingService() {}

	public void load()
	{
		if (loaded) return;
		goals.clear();
		Gson gson = new Gson();
		Type listType = new TypeToken<List<Goal>>(){}.getType();
		List<Goal> parsed = ResourceJsonLoader.load(getClass(), ResourcePaths.GOALS_JSON, listType, gson, log);
		if (parsed != null)
			goals.addAll(parsed);
		loaded = true;
	}

	public List<Goal> getGoals()
	{
		if (!loaded) load();
		return Collections.unmodifiableList(new ArrayList<>(goals));
	}
}

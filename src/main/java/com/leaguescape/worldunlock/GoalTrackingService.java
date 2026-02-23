package com.leaguescape.worldunlock;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
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
	private static final String GOALS_RESOURCE = "/goals.json";

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
		try (InputStream in = getClass().getResourceAsStream(GOALS_RESOURCE))
		{
			if (in == null)
			{
				log.warn("Goals resource not found: {}", GOALS_RESOURCE);
				loaded = true;
				return;
			}
			List<Goal> parsed = gson.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), listType);
			if (parsed != null)
				goals.addAll(parsed);
		}
		catch (Exception e)
		{
			log.error("Failed to load goals.json", e);
		}
		loaded = true;
	}

	public List<Goal> getGoals()
	{
		if (!loaded) load();
		return Collections.unmodifiableList(new ArrayList<>(goals));
	}
}

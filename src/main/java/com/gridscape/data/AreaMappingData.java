package com.gridscape.data;

import java.util.List;
import java.util.Map;
import lombok.Data;

/** Root JSON for area_mapping.json (kingdom + achievement diary area groupings). */
@Data
public class AreaMappingData
{
	private Map<String, List<String>> kingdomMapping;
	private Map<String, List<String>> achievementDiaryAreaMapping;
}

# OSRS Wiki API – URLs and JSON shape

Use these from the plugin when you need **item sources** (drops, shops, etc.) or any wiki page content. Send a descriptive `User-Agent` (e.g. `LeagueScape/1.0 (OSRS Wiki API; RuneLite plugin)`); the wiki may rate-limit generic clients.

---

## 1. Get page content (item page, drop sources, etc.)

Use this to fetch the **raw wikitext** of any page (e.g. an item’s main page or its “Drop sources” subpage). You can then parse the content for templates like `{{Drop sources|ItemName}}`, `{{Store locations list|ItemName}}`, and infobox `|id = 453`.

### URL

```
GET https://oldschool.runescape.wiki/api.php?action=query&prop=revisions&rvprop=content&rvslots=main&titles=<PAGE_TITLE>&format=json
```

- **`<PAGE_TITLE>`**: URL-encoded page title. Examples:
  - `Coal` – main item page
  - `Coal/Drop_sources` – drop sources subpage (if it exists)
  - `Item_name` – any wiki page

Example for Coal:

```
https://oldschool.runescape.wiki/api.php?action=query&prop=revisions&rvprop=content&rvslots=main&titles=Coal&format=json
```

### Response JSON shape

- **Root**: `batchcomplete` (string), `query` (object).
- **`query.pages`**: Object keyed by **page id** (string, e.g. `"9930"`). Missing page uses id `"-1"`.
- **Each page** (e.g. `query.pages["9930"]`):
  - `pageid` (number)
  - `ns` (number)
  - `title` (string), e.g. `"Coal"`
  - **`revisions`** (array of one revision):
    - **Current format**: `revisions[0].slots.main["*"]` is the wikitext string.
    - **Legacy format**: `revisions[0]["*"]` is the wikitext string (if `slots` is absent).

So the **wikitext content** is at:

- `query.pages[pageId].revisions[0].slots.main["*"]` (prefer), or  
- `query.pages[pageId].revisions[0]["*"]` (fallback).

### Minimal response example (structure only)

```json
{
  "batchcomplete": "",
  "query": {
    "pages": {
      "9930": {
        "pageid": 9930,
        "ns": 0,
        "title": "Coal",
        "revisions": [
          {
            "slots": {
              "main": {
                "contentmodel": "wikitext",
                "contentformat": "text/x-wiki",
                "*": "{{External|rs|rsc|dw}}\n{{Infobox Item\n|name = Coal\n|id = 453\n}}..."
              }
            }
          }
        ]
      }
    }
  }
}
```

The string in `["*"]` is the full page wikitext. Item pages typically include:

- `{{Infobox Item|...|id = 453|...}}` – in-game item ID.
- `==Item sources==\n{{Drop sources|Coal}}` – drop sources template.
- `{{Store locations list|Coal}}` – shop locations.

Parse these templates/sections to extract “sources” for tasks.

---

## 2. OpenSearch (find page title by name)

Use when you have an item name and need the **exact wiki page title** (e.g. for the query above).

### URL

```
GET https://oldschool.runescape.wiki/api.php?action=opensearch&search=<SEARCH_STRING>&limit=<1-500>&format=json
```

Example: `search=Coal&limit=5`

### Response JSON shape

OpenSearch returns a **single JSON array** of four elements:

1. **Index 0**: The search string (string).
2. **Index 1**: Array of page titles (strings).
3. **Index 2**: Array of descriptions (often empty).
4. **Index 3**: Array of wiki URLs.

So **page titles** are at: `response[1][0]`, `response[1][1]`, …

Example:

```json
["Coal", ["Coal", "Coal bag", "Coal rocks", ...], [], ["https://oldschool.runescape.wiki/w/Coal", ...]]
```

Use `response[1][0]` as `<PAGE_TITLE>` in the page-content URL above.

---

## 3. Items list (id + name) – osrsbox, not the wiki

The wiki does not expose a single “all items + IDs” API. Use **osrsbox** for the full item list:

### URL

```
GET https://www.osrsbox.com/osrsbox-db/items-summary.json
```

### Response JSON shape

- **Root**: JSON object. Keys are **item id as string** (e.g. `"453"`).
- **Each value**: `{ "id": <number>, "name": "<string>" }`.

Example:

```json
{
  "453": { "id": 453, "name": "Coal" },
  "617": { "id": 617, "name": "Coins" }
}
```

Use this to get in-game item IDs and names when building task lists. Item **sources** still come from the wiki (section 1) by loading the item’s page (title from section 2 if needed) and parsing wikitext.

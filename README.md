# GridScape

**GridScape** is a [RuneLite](https://runelite.net/) plugin for *Old School RuneScape* that adds **area-based progression**: you earn **points** by completing **tasks**, then **unlock** more of the world according to the **game mode** you choose. Locked regions can be greyed out on the game view and world map, with optional strict blocking of interactions in locked areas.

---

## Features at a glance

- **Three unlock modes**: spend points on neighboring areas (**Point buy**), complete point targets per area before unlocking neighbors (**Points to complete**), or unlock a spiral **World Unlock** tile grid (skills, quests, bosses, diaries, areas) that gates tasks and the map.
- **Per-area task grids** (Point buy & Points to complete) and a **global task grid** (World Unlock mode) built in **rings** around a center tile.
- **Points economy**: earn on task claim, spend on unlocks; configurable tier point values and (in World Unlock) per-tile-type multipliers.
- **World map integration**: see locked / unlocked / next-unlockable areas; open area details, **Unlock**, and **Tasks** from the map.
- **Minimap task icon**: quick access to tasks (and World Unlock tools in that mode).
- **Rules & Setup** window: rules text, game mode, area JSON import/export and polygon editing, controls reference, and **reset progress** (with confirmation).

---

## Requirements

- **RuneLite** client with **Plugin Hub** or ability to load an **external plugin** JAR (same major client version the plugin was built against).
- For **building from source**: **JDK 11** (see `build.gradle` toolchain).

---

## First launch

The **Rules & Setup** window opens **once per RuneScape account** the first time you run the plugin after logging in. You can reopen it anytime from:

- The **GridScape** sidebar panel → **Rules & Setup**
- The **minimap task icon** → right-click → **Rules & Setup**

Configure **starting area**, **starting points**, **unlock mode**, and task options on the **Game Mode** tab before or during play.

---

## Game modes (unlock modes)

Set in **RuneLite → Configuration → GridScape → Progression → Unlock mode** (or the **Game Mode** tab in Rules & Setup).

| Mode | Summary |
|------|--------|
| **Point buy** | Areas are linked by **neighbors**. Spend points to unlock any **neighboring** area you have not unlocked yet. Each unlocked area has its own **task grid**. |
| **Points to complete** | Earn points while playing in an area. When you reach that area’s **“points to complete”** target, you may unlock **one** of its configured connected neighbors. Task grids are still **per area**, like Point buy. |
| **World Unlock** | Spend points on a **spiral grid of unlock tiles** (skill brackets, quests, bosses, achievement diaries, areas, etc.). Unlocking tiles adds tasks to the **global** task pool and can gate **map** access. Use **World Unlock** from the sidebar or task icon menu; the **Tasks** button opens the **global** task grid (not only the area you stand in). |

**World Unlock tile cost** (when using that mode):

`cost = tile tier × tier points × type multiplier`

Tier is 1–5 (higher tiers use tier 5 multipliers). Type multipliers (skill, area, boss, quest, achievement diary) are configurable under **World Unlock** in plugin config; defaults are documented in `GridScapeConfig.java`.

---

## Points and tasks

- **Earning**: Complete a task on a grid, then **claim** it to add points to your total (and spendable balance where applicable).
- **Spending**: Unlock areas (Point buy / Points to complete / map) or World Unlock tiles, depending on mode.
- **Task mode** (*Members* vs *Free to Play*): Restricts which tasks appear from the task list (F2P-only vs full list). Configure under **Task system** in plugin config.
- **Task difficulty multiplier**: Scales how tasks are placed on grids (easier ↔ harder). See **Task system** in config.
- **Tier points**: Points awarded per task **tier** (1–5) when claimed—editable under **Task system** (defaults e.g. 5 / 10 / 20 / 50 / 100).

Optional **custom tasks file**: **Tasks file path** in config can point at an external `tasks.json`; if empty, built-in defaults are used.

---

## Ring completion bonus

Task grids (area grids **and** the global grid in World Unlock) are arranged in **rings** around the center. When you **claim the last task** in a **full ring** (every cell at the same ring distance from the center), you receive **bonus points**:

`ring number × tier points` for that ring’s dominant task tier, **capped at 250** points per ring. Each ring pays **at most once**. A small popup confirms the bonus.

---

## Controls and UI

### GridScape sidebar panel

- Shows **current area**, **points** (spendable / total earned), and unlock actions appropriate to your mode.
- **Tasks**: Opens the **global** task grid in **World Unlock** mode; otherwise opens tasks for **your current area** (player position).
- **Rules & Setup**: Opens the full setup window.
- **World Unlock** button (World Unlock mode only): Opens the World Unlock spiral grid.

### Minimap task icon

- **Location**: Small square icon near the **world map orb** (below the minimap).
- **Left-click**: Opens the task panel—**global** tasks in World Unlock mode, **current area** tasks otherwise.
- **Right-click**: Menu with **Tasks**, **World Unlocks** (World Unlock mode only), and **Rules & Setup**.

### World map

- With the **world map** open, GridScape can draw **locked**, **unlocked**, and **unlockable** areas (colors configurable under **Map overlay**).
- **Right-click an area** for details and, where allowed, **Unlock** and **Tasks**.

### Locked areas (game view)

- **Locked area overlay**: Optional grey overlay on tiles in locked regions (**Overlay appearance**).
- **Strict lock enforcement**: When **ON**, interactions on locked tiles are blocked; when **OFF**, the overlay is visual-only and you can still click through (**Overlay appearance**).

### Area editing (advanced)

Used from **Area Configuration** and edit mode on the map. Summarized in **Rules & Setup → Controls**:

- **Game view**: Shift + right-click to add/move polygon corners; context menu for move/cancel.
- **World map**: Right-click for corner operations, neighbors, done/cancel, etc.

---

## Rules & Setup tabs

| Tab | Purpose |
|-----|--------|
| **Rules** | Overview of modes, ring bonus, task icon behavior. |
| **Game Mode** | Unlock mode, task tier points, starter area, starting points, task list mode, World Unlock multipliers, **update starting rules**, **reset progress** (with name / RESET confirmation). |
| **Area Configuration** | Import/export area JSON, list areas, edit polygons, restore removed areas. |
| **Controls** | Keybinds and map editing summary. |

---

## Resetting progress

Use **Reset Progress** in **Game Mode** (Rules & Setup). You must confirm with your **character name** and typing **`RESET`**. This clears points, unlocks, task completions, World Unlock grid state, and related persisted data—**irreversible**.

---

## Configuration reference (RuneLite)

In RuneLite’s plugin list, configuration is under the group **`gridscape`** (`GridScapeConfigConstants.CONFIG_GROUP`). Sections include:

- **Overlay appearance** – locked overlay, strict enforcement, colors, boundaries, chunk borders.
- **Map overlay** – draw areas, colors, grid, labels, corner markers.
- **Progression** – starting area, starting points, unlock mode.
- **Task system** – F2P/Members, difficulty multiplier, tier points, optional tasks file path.
- **World Unlock** – per-tier, per-type tile cost multipliers.
- **Resetting progress** – documented entry point; actual reset is via Rules & Setup.

---

## Project layout (developers)

- `src/main/java/com/gridscape/` – plugin entry (`GridScapePlugin`), config, areas, points, tasks, world unlock, overlays, UI.
- `src/main/resources/` – `areas.json`, `tasks.json`, `world_unlocks.json`, icons, etc.

---

## Building

```bash
./gradlew shadowJar
```

Output: `build/libs/GridScape-*-all.jar`.

---

## License / attribution

Refer to your repository’s license file if present. This plugin targets the RuneLite client API and Old School RuneScape; follow Jagex’s terms of service when using third-party clients.

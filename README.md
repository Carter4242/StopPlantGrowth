# StopPlantGrowth

StopPlantGrowth lets players toggle growth locks on supported plants by right-clicking with shears.

## Features

- Toggle growth lock on supported plants with shears.
- Shear sound plays on every successful lock/unlock.
- Supports vertical plants, age-based crops/plants, saplings, and mushrooms.
- Blocks growth/spread/tree generation while locked (by plant group behavior).
- Supports bonemeal lock feedback messages.
- Supports lock inspection and cleanup with chunk radius or block radius modes.
- Configurable material groups and messages in `config.yml`.

## Behavior Summary

- Vertical groups (`upward-growth-plants`, `downward-growth-plants`):
  - Lock uses vertical anchor logic.
  - Blocks vertical grow/spread while locked.
- Age-based group (`age-growth-plants`):
  - Lock is on the exact block clicked.
  - Blocks age growth while locked.
  - Locked melon/pumpkin stems also block adjacent fruit spawn.
- Saplings (`sapling-growth-plants`):
  - Blocks sapling growth and tree structure growth while locked.
- Mushrooms (`mushroom-growth-plants`):
  - Blocks mushroom spread and huge mushroom structure growth while locked.

## Protection Rules

- Break protection applies only to locked harvestable crops:
  - `WHEAT`, `CARROTS`, `POTATOES`, `BEETROOTS`, `NETHER_WART`, `COCOA`, `SWEET_BERRY_BUSH`, `TORCHFLOWER_CROP`, `PITCHER_CROP`
- Trample protection applies when farmland has a locked crop above it:
  - `WHEAT`, `CARROTS`, `POTATOES`, `BEETROOTS`, `PUMPKIN_STEM`, `MELON_STEM`, `TORCHFLOWER_CROP`, `PITCHER_CROP`
- Right-click harvest protection currently applies to locked:
  - `SWEET_BERRY_BUSH`, `CAVE_VINES`, `CAVE_VINES_PLANT`

## Commands

- `/spg reload`
- `/spg chunk`
- `/spg stats [radius]`
- `/spg clear`
  - Shows clear mode help.
- `/spg clear chunks [radius] [type]`
  - Chunk radius mode (`1..9`).
- `/spg clear blocks [radius] [type]`
  - Block radius mode (`1..128`).
- `/spg breakbypass [on|off|toggle|status]`
  - Session-only bypass for crop break protection (trampling protection still active).

## Permissions

- `StopPlantGrowth.reload` (default: op)
- `StopPlantGrowth.chunk` (default: op)
- `StopPlantGrowth.stats` (default: op)
- `StopPlantGrowth.clear` (default: op)
- `StopPlantGrowth.breakbypass` (default: op)

## Config Groups

- `upward-growth-plants`
- `downward-growth-plants`
- `age-growth-plants`
- `sapling-growth-plants`
- `mushroom-growth-plants`

## Radius Notes

- `stats` and `clear chunks` use chunk radius.
- `clear blocks` uses a 2D horizontal block-distance radius (X/Z), not 3D.

## Message Placeholders

- `%plant%` or `{plant}` for plant display name
- `%count%` / `{count}` and `%types%` / `{types}` where applicable

## Build

```bash
mvn clean package
```

## Output

- Built jar: `target/StopPlantGrowth-1.0.0.jar`

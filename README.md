# StopPlantGrowth

StopPlantGrowth lets players toggle growth locks on supported plants by right-clicking with shears.

## Features

- Toggle growth lock on supported plants with shears.
- Configurable shears durability cost, including fractional chance-based wear.
- Shear sound plays on every successful lock/unlock.
- Supports vertical plants, age-based crops/plants, saplings, and mushrooms.
- Blocks growth/spread/tree generation while locked (by plant group behavior).
- Supports configurable melt locks, defaulting to `ICE` and `SNOW`.
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
- Melt prevention blocks:
  - Blocks in `melt-prevention-blocks` can be locked with shears.
  - Locked melt prevention blocks do not melt/fade.
  - Defaults to `ICE` and `SNOW`.

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
- `melt-prevention-blocks`
- `shears-durability-cost`

## Shears Durability Cost

- `0`: no durability consumed
- Between `0` and `1`: random chance to consume `1` durability (for example, `0.25` = 25%)
- `1` or higher: consumes the integer part every use, then rolls the decimal part as an extra chance (for example, `1.75`)

## Radius Notes

- `stats` and `clear chunks` use chunk radius.
- `clear blocks` uses a 2D horizontal block-distance radius (X/Z), not 3D.

## Message Placeholders

- `%plant%` or `{plant}` for locked block display name
- `%count%` / `{count}` and `%types%` / `{types}` where applicable

## Build

```bash
mvn clean package
```

## Output

- Built jar: `target/StopPlantGrowth-x.x.x.jar`

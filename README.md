# StopPlantGrowth

StopPlantGrowth lets players toggle growth for supported vertical plants by right-clicking with shears.

## Features

- Toggle growth lock on supported plants with shears.
- Supports both upward and downward-growing plants.
- Configurable material lists in `config.yml`.
- Admin commands to reload config and inspect/clear lock data in the current chunk.

## Commands

- `/igc reload`
- `/igc chunk`
- `/igc clear`

## Permissions

- `StopPlantGrowth.reload` (default: op)
- `StopPlantGrowth.chunk` (default: op)
- `StopPlantGrowth.clear` (default: op)

## Build

```bash
mvn clean package
```

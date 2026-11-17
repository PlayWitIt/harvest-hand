# Harvest Hand

A client-side Fabric mod for Minecraft 1.21.11. Stand in your walled farm and press **Numpad 4**. Every crop is
harvested the moment it's fully grown, and the same crop is planted straight back in.

- **Crops:** carrots, potatoes, wheat, beetroot and nether wart (on soul sand). Each plot gets back what grew there;
  empty farmland gets the farm's main crop.
- **Best tool first:** it harvests with the highest-Fortune item you carry (Fortune III gives the most drops). Hoes win
  a tie, then the one with the most uses left. Crops don't wear tools, but the tool guard still puts any tool aside with
  10 uses left so it never breaks. With no usable Fortune tool it harvests by hand, holding the seeds, and carries on.
- **Fast:** anything in reach is harvested and replanted in the same tick, up to 2 crops a tick (`/hh perTick 1-4`).
  It walks to the nearest ripe crop or dropped item and harvests everything it passes. With nothing to do it waits in
  the middle of the farm, where every plot is closest.
- **Full inventory:** with a chest or barrel at the farm's edge, it stores the harvest there and keeps one stack of each
  seed for replanting. With no chest it keeps harvesting and replanting, and the drops stay on the ground.
- **Trampled farmland** is tilled again (if you carry a hoe) and replanted.
- When you're hit it lets go of the keys for 3 seconds. Below 4 hearts it waits.

The farm is found when you start: every farmland plot you can walk to from where you stand, up to 64 blocks out
(`/hh radius`). Walls and fences bound it; water is crossed.

## Keys and commands

| Key | Command | |
|---|---|---|
| Numpad 4 | `/hh start [radius]` | start, or carry on after a pause |
| Numpad 5 | `/hh pause` | pause / carry on |
| Numpad 6 | `/hh stop` | stop |
| | `/hh` | status |

Settings are in `config/harvesthand.json`.

## Testing

`./gradlew runSelfTest` builds a 31 × 31 walled farm with a water source in the middle and runs the mod in survival.
It checks that the farm is found, that ripe crops are cleared with Fortune and pick-up works, and how long a single
ripe crop waits. It also checks keeping up at 13× growth, storing in the chest, harvesting by hand, and pause and stop.

# Harvest Hand

A client-side Fabric mod for Minecraft 1.21.11. Stand in your walled farm and press **Numpad 4**. Every crop is
harvested the moment it's fully grown, and the same crop is planted straight back in.

- **Crops:** carrots, potatoes, wheat, beetroot and nether wart (on soul sand). Each plot gets back what grew there;
  empty farmland gets the farm's main crop.
- **Best tool first:** it harvests with any tool that has Fortune, off hand included: the highest Fortune wins (Fortune
  III gives the most drops), a hoe wins a tie, then the one with the most uses left. The Fortune tool stays in your hand
  between crops, and the status lines show which one and how many harvests got Fortune. Crops don't wear tools, but the
  tool guard still puts any tool aside with 10 uses left so it never breaks. With no usable Fortune tool it harvests by
  hand, holding the seeds, and carries on.
- **AFK through kicks:** kicked, or the server restarts, while it's running: it reconnects by itself (after 10 s, then
  longer each try, up to 5 minutes; Cancel is on the disconnect screen) and carries on with the same farm, fetching a
  shulker box it had out first. Quitting yourself never brings it back. Turn it off in the settings (Numpad 6).
- **Fast:** anything in reach is harvested and replanted in the same tick, up to 2 crops a tick (`/hh perTick 1-4`).
  It walks to the nearest ripe crop or dropped item and harvests everything it passes. With nothing to do it waits in
  the middle of the farm, where every plot is closest.
- **Full inventory:** it stores the harvest and keeps one stack of each seed for replanting. It only uses chests you
  placed yourself (place a chest or barrel and it's remembered), or ones you add by looking at a chest, barrel or a
  shulker box you placed and typing `/hh chest`. It uses them at the farm or up to 16 blocks outside it, and a double chest
  counts once. `/hh chest` only ever adds; `/hh chest remove` takes one off and `/hh chests` lists them. It never places
  a chest, and never uses one it just finds.
- **Shulker boxes, three ways** (`/hh boxes`, or the settings):
  - **loader** (the default, for plenty of boxes): it does a shulker loader's job. Every box is filled to the top, and
    part-filled boxes are always topped up before an empty one is opened. Full boxes go into your chests, and empty
    ones are taken out of them. Harvest goes in loose only once there's no box left. Keep a chest of empty boxes at the
    farm and you get chests of full boxes back.
  - **spare** (a few boxes): your chests first, and the boxes you carry only once they're full. Part-filled boxes are
    topped up first here too.
  - **off**: chests only.

  **One crop per box** (on by default) keeps a box to carrots or to wheat, so you end up with one part-filled box per
  crop at most. It only fills boxes that hold nothing but harvest: a box with anything else in it is never touched.
  A box you carry is put against the wall at head height, filled, broken and picked back up. If you stop it while a
  box is out, it tells you where. With no chest and no box, it keeps harvesting and replanting, and the drops stay on
  the ground.
- **Every plot planted:** empty farmland is planted at once. On a brand-new farm with nothing growing, it plants what
  you carry most of, or picks up carrots, potatoes or seeds lying on the ground and plants those.
- **As much ground as possible:** with a hoe, it clears grass and flowers, tills bare dirt and grass the water reaches
  (within 4 blocks), and plants it. Trampled farmland is tilled again too.
- **Tabbed out or in your inventory:** it keeps walking, harvesting, planting and eating with the pause menu, your
  inventory, chat or the settings open. It only waits while you're dragging an item or using a chest of your own.
- Hurt, hungry or low on health, it keeps working. It only lets go of the keys for a moment when a mob or player is
  close and hitting you. If the server undoes harvests, it slows down by itself until they stick.

The farm is found when you start: every farmland plot you can walk to from where you stand, up to 64 blocks out
(`/hh radius`). Walls and fences bound it; water is crossed.

## Keys and commands

| Key | Command | |
|---|---|---|
| Numpad 4 | `/hh start [radius]` | start, or carry on after a pause |
| Numpad 5 | `/hh pause` | pause / carry on |
| Numpad 6 | `/hh menu` | settings, with Start / Stop |
| | `/hh stop` | stop |
| | `/hh chest` | add the chest, barrel or placed shulker box you're looking at |
| | `/hh chest remove` | stop it using the one you're looking at |
| | `/hh chests` | list them (`/hh chests clear` forgets them all here) |
| | `/hh boxes loader\|spare\|off\|onecrop` | how shulker boxes are used; `onecrop` turns one crop per box on or off |
| | `/hh` | status |

Settings are on Numpad 6, and in `config/harvesthand.json`.

## Testing

`./gradlew runSelfTest` builds a 31 × 31 walled farm with a water source in the middle and runs the mod in survival.
It checks that the farm is found, that ripe crops are cleared with Fortune and pick-up works, and how long a single
ripe crop waits. It also checks keeping up at 13× growth, storing only in the chest you placed, filling shulker boxes (one crop each) and picking them back up, topping up a part-filled box to the top before an empty one (and never touching a box of your own things), the shulker loader (empty boxes from your chest, full ones back, nothing loose), spare mode, harvesting by hand, and pause and stop.

`./gradlew runFarmTest` builds a farm like the ones people really make: nine 9 × 9 farms side by side, each with its water
source. It starts with nothing growing, no carrots on you and carrots lying on the ground, and one of the nine still
grass. It checks every plot gets planted and the grass tilled, that ripe crops right in front of you go at once even
when you're hurt and hungry, that it keeps up at 10× growth, that it keeps going tabbed out and with the inventory open,
and the Numpad 4, 5 and 6 keys.

`scripts/kicktest.sh` runs a local server (offline, flat) and the mod against it: farming, kicked, then the server
stopping and starting again. It checks it reconnects and carries on harvesting each time, and that quitting yourself
doesn't. Every crop break in the self-test is checked against what the server had in your hand.

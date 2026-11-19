package com.greysworkshop.harvesthand;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.item.BlockItem;
import net.minecraft.screen.ShulkerBoxScreenHandler;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * The job: every tick, anything in reach that's fully grown is broken (with the best Fortune tool you have, by hand if
 * there's none) and the same crop goes straight back in; empty farmland gets planted, trampled farmland tilled again.
 * Between times it walks to the next ripe crop or dropped item, harvesting whatever it passes, and waits in the middle
 * of the farm when there's nothing to do. A full inventory goes into a chest you placed yourself (never one it just
 * finds), or else into an empty shulker box you carry: put against the wall, filled, broken and picked back up.
 */
public final class Harvester {
	enum State {OFF, RUN, DEPOSIT}

	static State state = State.OFF;
	static Farm farm;
	static boolean paused;
	static Text status = Text.empty();
	static String stopReason = "";
	static int harvested, replanted, planted, tilled, deposited, ticks, fortuneHarvests;
	/** Fortune level of what it last harvested with (0: by hand), and what that was. */
	static int lastFortune = -1;
	static String toolName = "";
	/** Ticks from a crop being seen ripe to it being harvested: the total and how many, for the average. */
	static long waitTotal;
	static int waitCount, waitMax;

	static final Walker walker = new Walker();
	/** Cells it just worked: left alone for a moment while the server answers. */
	private static final Map<BlockPos, Integer> cool = new HashMap<>();
	/** Cells or drops it couldn't walk to: not tried again until this tick. */
	private static final Map<BlockPos, Integer> unreachable = new HashMap<>();
	/** When each ripe crop was first seen ripe. */
	private static final Map<BlockPos, Integer> ripeSince = new HashMap<>();
	private static BlockPos walkingTo;
	private static boolean walkingToDrop, holding, fullWarned;
	private static int fightUntil, depositStage, depositWait, idleTicks;
	private static float lastHealth = -1;
	private static BlockPos chest;
	private static final java.util.Set<BlockPos> fullChests = new java.util.HashSet<>();
	/** The shulker box it has put down to fill (null: none), the wall face it's on, and spots that didn't work. */
	static BlockPos box;
	private static BlockPos spot;
	private static BlockPos boxWall;
	private static Direction boxFace;
	private static int boxHeld, boxTicks;
	private static final java.util.Set<BlockPos> badSpots = new java.util.HashSet<>();
	private static boolean boxWarned;

	private Harvester() {}

	/** True while it's breaking its shulker box: vanilla mustn't cancel that (see MinecraftClientMixin). */
	public static boolean breakingBox() {
		return state == State.DEPOSIT && depositStage == 15 && box != null && !paused && !holding;
	}

	static boolean running() {
		return state != State.OFF;
	}

	// ---------------------------------------------------------------- start and stop

	static void startOrResume(MinecraftClient client) {
		if (paused) {
			togglePause(client);
			return;
		}
		start(client, HhConfig.INSTANCE.radius);
	}

	/** Where it was started, in which dimension and how far out it looked: to carry on there after a disconnect. */
	static BlockPos startAt;
	static String startDim;
	static int startRadius;

	static void start(MinecraftClient client, int radius) {
		if (client.player != null) start(client, radius, Walker.feet(client.player));
	}

	static void start(MinecraftClient client, int radius, BlockPos from) {
		ClientPlayerEntity p = client.player;
		if (p == null || client.world == null || running()) return;
		Farm f = Farm.scan(client.world, from, radius, Chests.mine(client), HhConfig.INSTANCE.till);
		if (f.cells.isEmpty()) {
			stopReason = "No farmland here: stand inside your farm and start again.";
			chat(p, Text.literal(stopReason).formatted(Formatting.RED));
			return;
		}
		farm = f;
		startAt = from.toImmutable();
		startDim = client.world.getRegistryKey().getValue().toString();
		startRadius = radius;
		state = State.RUN;
		paused = holding = fullWarned = false;
		harvested = replanted = planted = tilled = deposited = ticks = fortuneHarvests = 0;
		boxesFilled = boxesStored = 0;
		boxRound = false;
		waitTotal = waitCount = waitMax = 0;
		lastFortune = -1;
		fightUntil = 0;
		lastHealth = -1;
		cool.clear();
		unreachable.clear();
		ripeSince.clear();
		harvestedAt.clear();
		undone = 0;
		undoneWarned = false;
		fullChests.clear();
		badSpots.clear();
		boxWarned = false;
		walkingTo = null;
		stopReason = "";
		status = Text.literal("Watching");
		int x = f.maxX - f.minX + 1, z = f.maxZ - f.minZ + 1;
		chat(p, Text.literal("Farming " + f.cells.size() + " plots (" + x + " × " + z + ")"
				+ (f.chests.isEmpty() ? "" : ", " + f.chests.size() + " of your chest" + (f.chests.size() == 1 ? "" : "s") + " for a full inventory")
				+ ". Numpad 5 pauses, Numpad 6 has the settings and Stop.").formatted(Formatting.GREEN));
		if (HhConfig.INSTANCE.deposit) chat(p, Text.literal(storeNote(p.getInventory())).formatted(Formatting.GRAY));
		chat(p, Text.literal(toolNote(p.getInventory())).formatted(Formatting.GRAY));
		pickCrop(p.getInventory(), client.world);
		int bare = (int) f.cells.stream().filter(c -> client.world.getBlockState(c).isAir() || Farm.weed(client.world, c, client.world.getBlockState(c))).count();
		if (f.main == null) {
			chat(p, Text.literal("Nothing to plant: carry carrots, potatoes or seeds (or leave some on the ground) and it plants every plot.").formatted(Formatting.GOLD));
		} else if (bare > 0) {
			chat(p, Text.literal(bare + " empty plot" + (bare == 1 ? "" : "s") + ": planting " + Farm.seed(f.main).getName().getString().toLowerCase(java.util.Locale.ROOT)
					+ (f.bareGround.isEmpty() ? "" : ", and tilling " + f.bareGround.size() + " of dirt and grass by the water" + (hoeSlot(p.getInventory()) < 0 ? " (bring a hoe)" : ""))
					+ ".").formatted(Formatting.GRAY));
		}
		log("farm: {} cells, {} standing, {} chests, centre {}, main {}", f.cells.size(), f.stand.size(), f.chests.size(), f.centre, f.main);
	}

	/** Where a full inventory goes, in words. */
	static String storeNote(PlayerInventory inv) {
		int empty = emptyBoxes(inv), part = partBoxes(inv);
		String carried = empty + part == 0 ? "" : (part > 0 ? part + " part-filled" + (empty > 0 ? " and " : "") : "") + (empty > 0 ? empty + " empty" : "")
				+ " shulker box" + (empty + part == 1 ? "" : "es") + " on you";
		boolean chests = !farm.chests.isEmpty();
		return switch (mode()) {
			case OFF -> chests ? "A full inventory goes into your chests (shulker boxes are off)." : "No chest of yours here: place one at the farm's edge, or look at one and /hh chest.";
			case SPARE -> (chests ? "A full inventory goes into your chests" + (carried.isEmpty() ? "." : ", then the " + carried + " once they're full.")
					: carried.isEmpty() ? "No chest of yours and no shulker boxes on you: place a chest at the farm's edge, or /hh chest on one." : "A full inventory goes into the " + carried + ".")
					+ " Part-filled boxes are topped up first.";
			case LOADER -> (chests ? "Shulker loader: every box is filled to the top" + (HhConfig.INSTANCE.oneCropPerBox ? " with one crop" : "")
					+ " and full boxes go into your chests; empty boxes come out of them" + (carried.isEmpty() ? "" : " (" + carried + ")") + ". Loose only once there are no boxes left."
					: carried.isEmpty() ? "No chest of yours and no shulker boxes on you: place a chest at the farm's edge, or /hh chest on one."
					: "Shulker loader: the " + carried + " are filled to the top, part-filled first. Put empty boxes in a chest of yours and it fetches them.");
		};
	}

	/** Which tool it'll harvest with, in words. */
	static String toolNote(PlayerInventory inv) {
		int i = bestTool(inv);
		if (i >= 0) {
			ItemStack s = inv.getStack(i);
			return "Harvesting with " + s.getName().getString() + " (Fortune " + fortune(s) + ")"
					+ (s.isDamageable() ? ": crops don't wear it, and it's put aside with " + HhConfig.INSTANCE.toolReserve + " uses left all the same." : ".");
		}
		String aside = asideNote(inv);
		return aside.isEmpty() ? "No Fortune tool: harvesting by hand (fewer drops). Bring a Fortune III hoe for the most."
				: aside + " Harvesting by hand meanwhile.";
	}

	/** After a disconnect: a box it left on the wall, to break and pick up first (it opens towards the air it was hit from). */
	static void recoverBox(ClientWorld w, BlockPos pos) {
		BlockState s = w.getBlockState(pos);
		if (!(s.getBlock() instanceof ShulkerBoxBlock)) return;
		box = pos.toImmutable();
		boxFace = s.contains(ShulkerBoxBlock.FACING) ? s.get(ShulkerBoxBlock.FACING) : Direction.UP;
	}

	static void togglePause(MinecraftClient client) {
		if (!running() || client.player == null) return;
		paused = !paused;
		chat(client.player, Text.literal(paused ? "Paused. Numpad 5 or Numpad 4 to carry on." : "Carrying on.").formatted(Formatting.YELLOW));
	}

	static void stop(MinecraftClient client, String why) {
		if (!running()) return;
		if (eating > 0) client.options.useKey.setPressed(false);
		eating = 0;
		closeChest(client);
		if (box != null && client.world != null && client.player != null && client.world.getBlockState(box).getBlock() instanceof ShulkerBoxBlock) {
			chat(client.player, Text.literal("Your shulker box is still on the wall at " + box.toShortString() + ": break it to get it back.").formatted(Formatting.RED));
		}
		box = null;
		state = State.OFF;
		paused = false;
		stopReason = why;
		walker.clear(client);
		release(client);
		if (client.player != null) {
			chat(client.player, Text.literal(why + " " + summary()).formatted(Formatting.YELLOW));
		}
		HhConfig.INSTANCE.totalHarvested += harvested;
		// Stopped for any other reason (you, a death...): nothing to carry on with after a disconnect.
		if (!why.equals(Resume.DISCONNECTED)) HhConfig.INSTANCE.resume = null;
		HhConfig.save();
	}

	static String summary() {
		return "Harvested " + harvested + ", replanted " + replanted + (planted > 0 ? ", planted " + planted : "") + (tilled > 0 ? ", tilled " + tilled : "")
				+ (deposited > 0 ? ", " + deposited + " stored" : "") + ".";
	}

	// ---------------------------------------------------------------- every tick

	static void tick(MinecraftClient client) {
		if (state == State.OFF) return;
		ClientPlayerEntity p = client.player;
		ClientWorld w = client.world;
		if (p == null || w == null || client.interactionManager == null) return;
		ticks++;
		if (p.isDead()) {
			stop(client, "Stopped: you died.");
			return;
		}
		trackRipe(w);
		float hp = p.getHealth();
		// Hurt with a mob or player close by: let go for a moment. Hunger, a fall or a cactus is no reason to stop.
		if ((p.hurtTime > 0 || lastHealth >= 0 && hp < lastHealth) && attackerNear(p, w)) fightUntil = ticks + 60;
		lastHealth = hp;
		boolean low = HhConfig.INSTANCE.minHealth > 0 && hp <= HhConfig.INSTANCE.minHealth && attackerNear(p, w);
		if (ticks % 20 == 0 && farm.main == null) pickCrop(p.getInventory(), w);
		if (paused || low || ticks < fightUntil) {
			if (!holding) {
				holding = true;
				if (eating > 0) client.options.useKey.setPressed(false);
				eating = 0;
				closeChest(client);
				walker.clear(client);
				walkingTo = null;
			}
			status = Text.literal(paused ? "Paused (Numpad 5 to carry on)" : low ? "Holding: low health" : "Holding: hit");
			return;
		}
		holding = false;
		// It works tabbed out (the pause menu) and with your inventory, chat or the settings open. It only waits while you're
		// moving items about, or using a chest or furnace of your own: swapping tools then would get in your way.
		if (state != State.DEPOSIT && userBusy(p)) {
			walker.stop(client);
			status = Text.literal("Waiting: you're using your inventory");
			return;
		}
		cool.values().removeIf(t -> t <= ticks);
		if (state == State.DEPOSIT) {
			deposit(client, p, w);
			return;
		}
		if (box != null) {
			// A box is still out (a hit or a pause got in the way): get it back first.
			state = State.DEPOSIT;
			depositStage = 15;
			return;
		}
		if (shouldDeposit(p, w)) {
			state = State.DEPOSIT;
			depositStage = 0;
			boxRound = false;
			chest = null;
			walkingTo = null;
			walker.clear(client);
			return;
		}
		if (eat(client, p)) return;
		int acted = work(client, p, w);
		if (acted > 0) {
			// The keys stay down (no losing the sprint); the walker steers again next tick. This tick the look is the
			// crop's, so that's what the server sees with the harvest.
			idleTicks = 0;
			status = Text.literal("Harvesting");
			return;
		}
		move(client, p, w);
	}

	/** Notes when each crop turned ripe (for the average wait the HUD shows). */
	private static boolean userBusy(ClientPlayerEntity p) {
		ScreenHandler h = p.currentScreenHandler;
		return h != p.playerScreenHandler || !h.getCursorStack().isEmpty();
	}

	private static boolean attackerNear(ClientPlayerEntity p, ClientWorld w) {
		return !w.getEntitiesByClass(net.minecraft.entity.LivingEntity.class, p.getBoundingBox().expand(6),
				e -> e != p && e.isAlive() && (e instanceof net.minecraft.entity.mob.HostileEntity || e instanceof net.minecraft.entity.player.PlayerEntity
						|| e instanceof net.minecraft.entity.mob.Angerable)).isEmpty();
	}

	/**
	 * On a farm with nothing growing yet: plant what you carry most of, or else what's lying on the ground (it's
	 * picked up first).
	 */
	static void pickCrop(PlayerInventory inv, ClientWorld w) {
		if (farm == null || farm.main != null) return;
		Block[] options = {Blocks.CARROTS, Blocks.POTATOES, Blocks.WHEAT, Blocks.BEETROOTS};
		Block best = null;
		int bestN = 0;
		for (Block b : options) {
			int n = 0;
			for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) if (inv.getStack(i).isOf(Farm.seed(b))) n += inv.getStack(i).getCount();
			if (n > bestN) {
				best = b;
				bestN = n;
			}
		}
		if (best == null) {
			Box box = new Box(farm.minX - 1, farm.minY - 2, farm.minZ - 1, farm.maxX + 2, farm.maxY + 3, farm.maxZ + 2);
			for (Block b : options) {
				int n = w.getEntitiesByClass(ItemEntity.class, box, e -> e.getStack().isOf(Farm.seed(b))).stream().mapToInt(e -> e.getStack().getCount()).sum();
				if (n > bestN) {
					best = b;
					bestN = n;
				}
			}
		}
		if (best != null) {
			farm.main = best;
			log("bare farm: planting {}", best);
		}
	}

	/** When each harvested cell was harvested: a crop back there at once means the server undid it. */
	private static final Map<BlockPos, Integer> harvestedAt = new HashMap<>();
	private static int undone, undoneAt;
	private static boolean undoneWarned;

	private static void trackRipe(ClientWorld w) {
		if (ticks % 2 != 0) return;
		for (BlockPos c : farm.cells) {
			if (Farm.mature(w.getBlockState(c))) {
				ripeSince.putIfAbsent(c, ticks);
				Integer at = harvestedAt.remove(c);
				if (at != null && ticks - at < 40) {
					undone++;
					undoneAt = ticks;
					log("server undid the harvest at {}", c.toShortString());
				}
			} else ripeSince.remove(c);
		}
		harvestedAt.values().removeIf(t -> ticks - t >= 40);
	}

	/** What needs doing in a cell right now, or null. */
	private enum Need {HARVEST, PLANT, TILL, CLEAR}

	private static Need need(ClientWorld w, PlayerInventory inv, BlockPos c) {
		BlockState s = w.getBlockState(c);
		if (Farm.isCrop(s.getBlock())) {
			farm.crop.put(c, s.getBlock());
			return Farm.mature(s) ? Need.HARVEST : null;
		}
		BlockState below = w.getBlockState(c.down());
		boolean tillHere = HhConfig.INSTANCE.till && hoeSlot(inv) >= 0
				&& (Farm.tillable(below) && farm.crop.containsKey(c) || Farm.newGround(below) && farm.bareGround.contains(c));
		if (!s.isAir()) {
			// Grass or a flower where a crop should be: clear it.
			return Farm.weed(w, c, s) && (Farm.soil(below) || tillHere) ? Need.CLEAR : null;
		}
		if (Farm.soil(below)) {
			Block b = farm.wanted(w, c);
			return b != null && seedSlot(inv, b) >= 0 ? Need.PLANT : null;
		}
		return tillHere ? Need.TILL : null;
	}

	/** Does what's in reach, up to the per-tick limit. Returns how many cells it worked. */
	private static int work(MinecraftClient client, ClientPlayerEntity p, ClientWorld w) {
		PlayerInventory inv = p.getInventory();
		double range = p.getBlockInteractionRange() - 0.3;
		Vec3d eye = p.getEyePos();
		int done = 0, limit = HhConfig.INSTANCE.perTick;
		// The server undoing harvests (an anti-cheat, lag): one at a time, every few ticks, until it settles.
		boolean slow = undone >= 3 && ticks - undoneAt < 400;
		if (slow) {
			if (!undoneWarned) {
				undoneWarned = true;
				chat(p, Text.literal("The server is undoing some harvests: going slower so they stick.").formatted(Formatting.GOLD));
			}
			if (ticks % 4 != 0) return 0;
			limit = 1;
		} else if (undone > 0 && ticks - undoneAt >= 400) {
			undone = 0;
		}
		// Nearest first.
		BlockPos[] near = farm.cells.stream().filter(c -> !cool.containsKey(c) && eye.squaredDistanceTo(Vec3d.ofCenter(c)) <= range * range)
				.sorted(java.util.Comparator.comparingDouble(c -> eye.squaredDistanceTo(Vec3d.ofCenter(c)))).toArray(BlockPos[]::new);
		for (BlockPos c : near) {
			if (done >= limit) break;
			Need n = need(w, inv, c);
			if (n == null) continue;
			switch (n) {
				case HARVEST -> {
					Block b = w.getBlockState(c).getBlock();
					if (!harvest(client, p, w, c)) continue;
					Integer since = ripeSince.remove(c);
					if (since != null) {
						int waited = ticks - since;
						waitTotal += waited;
						waitCount++;
						waitMax = Math.max(waitMax, waited);
					}
					harvested++;
					harvestedAt.put(c, ticks);
					if (plant(client, p, w, c, b)) replanted++;
				}
				case PLANT -> {
					if (plant(client, p, w, c, farm.wanted(w, c))) planted++;
				}
				case TILL -> {
					if (till(client, p, c)) tilled++;
				}
				case CLEAR -> {
					look(p, Vec3d.ofCenter(c));
					client.interactionManager.attackBlock(c, Direction.UP);
					p.swingHand(Hand.MAIN_HAND);
				}
			}
			// Tilling isn't shown until the server answers: give it a second, not a few ticks.
			cool.put(c, ticks + (n == Need.TILL ? 20 : 6));
			done++;
		}
		return done;
	}

	/** Breaks a ripe crop. True once it's gone (crops break at once). */
	private static boolean harvest(MinecraftClient client, ClientPlayerEntity p, ClientWorld w, BlockPos c) {
		PlayerInventory inv = p.getInventory();
		int tool = bestTool(inv);
		if (tool >= 0) {
			hand(client, p, tool, -1);
		} else {
			// By hand: hold the seed if there is one (it saves swapping back to plant), else anything that can't wear.
			int seed = seedSlot(inv, w.getBlockState(c).getBlock());
			int hold = seed >= 0 ? seed : harmless(inv);
			if (hold >= 0) hand(client, p, hold, -1);
		}
		ItemStack held = p.getMainHandStack();
		lastFortune = fortune(held);
		toolName = lastFortune > 0 ? held.getName().getString() : "hand";
		look(p, Vec3d.ofCenter(c));
		client.interactionManager.attackBlock(c, Direction.UP);
		p.swingHand(Hand.MAIN_HAND);
		if (!w.getBlockState(c).isAir()) {
			client.interactionManager.cancelBlockBreaking();
			return false;
		}
		if (lastFortune > 0) fortuneHarvests++;
		return true;
	}

	/** Plants {@code crop} in an empty cell. */
	private static boolean plant(MinecraftClient client, ClientPlayerEntity p, ClientWorld w, BlockPos c, Block crop) {
		if (crop == null) return false;
		PlayerInventory inv = p.getInventory();
		int slot = seedSlot(inv, crop);
		if (slot < 0) return false;
		hand(client, p, slot, bestTool(inv));
		BlockPos soil = c.down();
		Vec3d hit = new Vec3d(c.getX() + 0.5, soil.getY() + w.getBlockState(soil).getOutlineShape(w, soil).getMax(Direction.Axis.Y), c.getZ() + 0.5);
		look(p, hit);
		client.interactionManager.interactBlock(p, Hand.MAIN_HAND, new BlockHitResult(hit, Direction.UP, soil, false));
		p.swingHand(Hand.MAIN_HAND);
		// Back to the Fortune tool, so that's what you see in hand (and it's ready for the next crop).
		int tool = bestTool(inv);
		if (tool >= 0 && tool < 9) hand(client, p, tool, -1);
		farm.crop.put(c, crop);
		return Farm.isCrop(w.getBlockState(c).getBlock());
	}

	private static boolean till(MinecraftClient client, ClientPlayerEntity p, BlockPos c) {
		PlayerInventory inv = p.getInventory();
		int hoe = hoeSlot(inv);
		if (hoe < 0) return false;
		hand(client, p, hoe, -1);
		BlockPos soil = c.down();
		Vec3d hit = Vec3d.ofCenter(soil).add(0, 0.5, 0);
		look(p, hit);
		client.interactionManager.interactBlock(p, Hand.MAIN_HAND, new BlockHitResult(hit, Direction.UP, soil, false));
		p.swingHand(Hand.MAIN_HAND);
		return true;
	}

	// ---------------------------------------------------------------- walking

	/** Out of things in reach: walk to the nearest ripe crop or drop, or back to the middle to wait. */
	private static void move(MinecraftClient client, ClientPlayerEntity p, ClientWorld w) {
		unreachable.values().removeIf(t -> t <= ticks);
		walker.sprint = HhConfig.INSTANCE.sprint;
		walker.careful = false;
		boolean valid = walkingTo != null && (walkingToDrop ? dropAt(w, p, walkingTo) : need(w, p.getInventory(), walkingTo) != null);
		if (!valid || walker.arrived() && !walker.moving()) {
			walkingTo = null;
			if (choose(w, p)) go(client, p, w);
		} else if (ticks % 10 == 0 && choose(w, p) && !chosen.equals(walkingTo)
				&& chosenScore < score(w, p, walkingTo, walkingToDrop) - 2) {
			// Something better turned up on the way (a crop just ripened nearer): go there instead.
			go(client, p, w);
		}
		if (walkingTo == null && !walker.moving() && walker.arrived()) {
			idle(client, p, w);
			return;
		}
		if (!walker.tick(client) && walkingTo != null) {
			unreachable.put(walkingTo, ticks + 100);
			walkingTo = null;
		}
	}

	private static BlockPos chosen;
	private static boolean chosenDrop;
	private static double chosenScore;

	/**
	 * Picks where to go next: the lowest score. A ripe crop scores its distance, less a block for every second it has
	 * waited, up to 15 (so no corner is left out, without zigzagging across the farm for it). A drop scores its distance plus 4, less a block for every 10 seconds on the
	 * ground (items despawn after 5 minutes); a seed you've run out of while plots wait for it comes first of all.
	 */
	private static boolean choose(ClientWorld w, ClientPlayerEntity p) {
		PlayerInventory inv = p.getInventory();
		Vec3d at = p.getEntityPos();
		chosen = null;
		chosenScore = Double.MAX_VALUE;
		for (BlockPos c : farm.cells) {
			if (unreachable.containsKey(c) || cool.containsKey(c)) continue;
			double d = Math.sqrt(at.squaredDistanceTo(Vec3d.ofCenter(c)));
			if (d - 60 >= chosenScore || need(w, inv, c) == null) continue;
			double score = cropScore(c, d);
			if (score < chosenScore) {
				chosenScore = score;
				chosen = c;
				chosenDrop = false;
			}
		}
		if (HhConfig.INSTANCE.pickUp) {
			java.util.Set<Item> missing = missingSeeds(w, inv);
			for (ItemEntity e : drops(w, p)) {
				BlockPos c = e.getBlockPos();
				if (unreachable.containsKey(c)) continue;
				double score = dropScore(e, Math.sqrt(at.squaredDistanceTo(e.getEntityPos())), missing);
				if (score < chosenScore) {
					chosenScore = score;
					chosen = c;
					chosenDrop = true;
				}
			}
		}
		return chosen != null;
	}

	private static double cropScore(BlockPos c, double d) {
		Integer since = ripeSince.get(c);
		return d - (since == null ? 0 : Math.min(15, (ticks - since) / 20.0));
	}

	/**
	 * Ripe crops come first: a fresh drop counts as 12 blocks further off than it is, a point less every 5 seconds it
	 * lies there, so it's fetched within about a minute (long before the 5 minutes it takes to despawn). A seed it has
	 * run out of is fetched at once.
	 */
	private static double dropScore(ItemEntity e, double d, java.util.Set<Item> missing) {
		return missing.contains(e.getStack().getItem()) ? d - 100 : d + 12 - e.getItemAge() / 100.0;
	}

	/** The score of where it's headed now (the best drop in that cell, for drops). */
	private static double score(ClientWorld w, ClientPlayerEntity p, BlockPos target, boolean drop) {
		Vec3d at = p.getEntityPos();
		if (!drop) return cropScore(target, Math.sqrt(at.squaredDistanceTo(Vec3d.ofCenter(target))));
		java.util.Set<Item> missing = missingSeeds(w, p.getInventory());
		double best = Double.MAX_VALUE;
		for (ItemEntity e : drops(w, p)) {
			if (e.getBlockPos().getX() == target.getX() && e.getBlockPos().getZ() == target.getZ()) {
				best = Math.min(best, dropScore(e, Math.sqrt(at.squaredDistanceTo(e.getEntityPos())), missing));
			}
		}
		return best;
	}

	/** Plans the way to {@link #chosen}. */
	private static void go(MinecraftClient client, ClientPlayerEntity p, ClientWorld w) {
		final BlockPos target = chosen;
		boolean drop = chosenDrop;
		double want = p.getBlockInteractionRange() - 1.2;
		Vec3d mid = Vec3d.ofCenter(target);
		Predicate<BlockPos> goal = drop
				? c -> c.getX() == target.getX() && c.getZ() == target.getZ() && Math.abs(c.getY() - target.getY()) <= 1
				: c -> Vec3d.ofBottomCenter(c).add(0, 1.62, 0).squaredDistanceTo(mid) <= want * want;
		if (!walker.plan(w, p, goal, bounds(p))) {
			log("no way to {}", target.toShortString());
			unreachable.put(target, ticks + 100);
			walkingTo = null;
			return;
		}
		walkingTo = target;
		walkingToDrop = drop;
		status = Text.literal(drop ? "Picking up" : "Walking to a ripe crop");
	}

	private static void idle(MinecraftClient client, ClientPlayerEntity p, ClientWorld w) {
		idleTicks++;
		status = Text.literal("Watching");
		BlockPos c = farm.centre;
		if (!HhConfig.INSTANCE.waitInMiddle || c == null || idleTicks < 10) return;
		Vec3d m = Vec3d.ofBottomCenter(c);
		if (Math.hypot(m.x - p.getX(), m.z - p.getZ()) < 2.5 || unreachable.containsKey(c)) return;
		if (walker.plan(w, p, q -> Math.abs(q.getX() - c.getX()) <= 1 && Math.abs(q.getZ() - c.getZ()) <= 1 && Math.abs(q.getY() - c.getY()) <= 1, bounds(p))) {
			walker.tick(client);
		} else {
			unreachable.put(c, ticks + 200);
		}
	}

	/** Seeds it has none of while an empty plot waits for them. */
	private static java.util.Set<Item> missingSeeds(ClientWorld w, PlayerInventory inv) {
		java.util.Set<Item> out = new java.util.HashSet<>();
		for (BlockPos c : farm.cells) {
			if (!w.getBlockState(c).isAir() || !Farm.soil(w.getBlockState(c.down()))) continue;
			Block b = farm.wanted(w, c);
			if (b != null && seedSlot(inv, b) < 0) out.add(Farm.seed(b));
		}
		return out;
	}

	/** Items lying in the farm that it has room for. */
	static List<ItemEntity> drops(ClientWorld w, ClientPlayerEntity p) {
		Box box = new Box(farm.minX - 1, farm.minY - 2, farm.minZ - 1, farm.maxX + 2, farm.maxY + 3, farm.maxZ + 2);
		PlayerInventory inv = p.getInventory();
		return w.getEntitiesByClass(ItemEntity.class, box, e -> e.isAlive() && room(inv, e.getStack()));
	}

	private static boolean dropAt(ClientWorld w, ClientPlayerEntity p, BlockPos cell) {
		return drops(w, p).stream().anyMatch(e -> e.getBlockPos().getX() == cell.getX() && e.getBlockPos().getZ() == cell.getZ());
	}

	static boolean room(PlayerInventory inv, ItemStack s) {
		for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) {
			ItemStack t = inv.getStack(i);
			if (t.isEmpty() || ItemStack.areItemsAndComponentsEqual(t, s) && t.getCount() < t.getMaxCount()) return true;
		}
		return false;
	}

	/** Where the walker may go: the farm, where you are and your chests (a marked one can be a little way off), with a margin. */
	private static int[] bounds(ClientPlayerEntity p) {
		int[] b = {Math.min(farm.minX, p.getBlockX()), Math.min(farm.minY, p.getBlockY()), Math.min(farm.minZ, p.getBlockZ()),
				Math.max(farm.maxX, p.getBlockX()), Math.max(farm.maxY, p.getBlockY()), Math.max(farm.maxZ, p.getBlockZ())};
		for (BlockPos c : farm.chests) {
			b[0] = Math.min(b[0], c.getX());
			b[1] = Math.min(b[1], c.getY());
			b[2] = Math.min(b[2], c.getZ());
			b[3] = Math.max(b[3], c.getX());
			b[4] = Math.max(b[4], c.getY());
			b[5] = Math.max(b[5], c.getZ());
		}
		return new int[] {b[0] - 3, b[1] - 6, b[2] - 3, b[3] + 3, b[4] + 6, b[5] + 3};
	}

	// ---------------------------------------------------------------- storing: chests and shulker boxes

	/** What the next visit to a container is for: loose harvest, or swapping boxes (full ones in, empty ones out). */
	private enum Job {LOOSE, BOXES}

	private static Job job = Job.LOOSE;
	/** This round of storing has already been to a chest for boxes: once is enough. */
	private static boolean boxRound, fetched;
	/** What goes in the box being filled (null: any of the harvest), and how many of its 27 slots were used when it opened. */
	private static Item boxItem;
	private static int boxUsedBefore;
	/** Boxes filled to the top, and boxes put in your chests, since it started. */
	static int boxesFilled, boxesStored;

	static HhConfig.Boxes mode() {
		return HhConfig.INSTANCE.boxMode();
	}

	static int emptySlots(PlayerInventory inv) {
		int n = 0;
		for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) if (inv.getStack(i).isEmpty()) n++;
		return n;
	}

	/** A container of yours it can still use, for loose harvest or (boxes) to swap boxes in. */
	private static boolean containerFree(ClientWorld w, Job j) {
		for (BlockPos c : farm.chests) {
			if (fullChests.contains(c)) continue;
			if (j == Job.BOXES && Chests.placedBox(w, c)) continue;
			return true;
		}
		return false;
	}

	private static boolean shouldDeposit(ClientPlayerEntity p, ClientWorld w) {
		PlayerInventory inv = p.getInventory();
		int empty = emptySlots(inv);
		// Filling boxes from your chests keeps one slot free, to take the next box out with.
		boolean loader = mode() == HhConfig.Boxes.LOADER && containerFree(w, Job.BOXES);
		if (empty > (loader ? 1 : 0)) {
			fullWarned = false;
			return false;
		}
		boolean chests = containerFree(w, Job.LOOSE);
		boolean boxes = mode() != HhConfig.Boxes.OFF && !boxWarned && pickBox(inv) >= 0;
		if (!HhConfig.INSTANCE.deposit || !chests && !boxes || depositable(inv).isEmpty()) {
			if (empty == 0 && !fullWarned) {
				fullWarned = true;
				chat(p, Text.literal(farm.chests.isEmpty() ? "Inventory full: still harvesting and replanting, but drops stay on the ground. Place a chest at the farm's edge (or /hh chest on one, or carry shulker boxes) to store them."
						: "Inventory full and your chests are full: still harvesting and replanting, drops stay on the ground.").formatted(Formatting.GOLD));
			}
			return false;
		}
		return true;
	}

	/** Inventory slots to put in the chest: all the produce, apart from one stack of each seed the farm plants. */
	static List<Integer> depositable(PlayerInventory inv) {
		java.util.Set<Item> keep = new java.util.HashSet<>();
		for (Block b : farm.crop.values()) keep.add(Farm.seed(b));
		if (farm.main != null) keep.add(Farm.seed(farm.main));
		Map<Item, Integer> kept = new HashMap<>();
		// Keep the biggest stack of each seed.
		for (Item k : keep) {
			int best = -1;
			for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) {
				ItemStack s = inv.getStack(i);
				if (s.isOf(k) && (best < 0 || s.getCount() > inv.getStack(best).getCount())) best = i;
			}
			if (best >= 0) kept.put(k, best);
		}
		List<Integer> out = new java.util.ArrayList<>();
		for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) {
			ItemStack s = inv.getStack(i);
			if (s.isEmpty() || !Farm.produce(s.getItem())) continue;
			if (Integer.valueOf(i).equals(kept.get(s.getItem()))) continue;
			out.add(i);
		}
		return out;
	}

	/** How much of each item there is to store. */
	private static Map<Item, Integer> toStore(PlayerInventory inv) {
		Map<Item, Integer> out = new java.util.LinkedHashMap<>();
		for (int i : depositable(inv)) out.merge(inv.getStack(i).getItem(), inv.getStack(i).getCount(), Integer::sum);
		return out;
	}

	/** The nearest container of yours for this job, a box on the ground first for loose harvest when filling boxes. */
	private static BlockPos nearestContainer(ClientPlayerEntity p, ClientWorld w, Job j) {
		boolean groundFirst = j == Job.LOOSE && mode() == HhConfig.Boxes.LOADER;
		return farm.chests.stream().filter(c -> !fullChests.contains(c) && (j == Job.LOOSE || !Chests.placedBox(w, c)))
				.min(java.util.Comparator.<BlockPos>comparingInt(c -> groundFirst && Chests.placedBox(w, c) ? 0 : 1)
						.thenComparingDouble(c -> p.getEntityPos().squaredDistanceTo(Vec3d.ofCenter(c)))).orElse(null);
	}

	/**
	 * What to do next while storing. Off: chests only. Spare: your chests first, then the boxes you carry. Loader:
	 * full boxes into your chests, then the boxes you carry (part-filled ones first), then a box on the ground you
	 * marked, then fresh boxes from your chests, and loose into a chest only once there are no boxes left.
	 */
	private static void plan(ClientPlayerEntity p, ClientWorld w) {
		PlayerInventory inv = p.getInventory();
		boolean produce = !depositable(inv).isEmpty();
		HhConfig.Boxes m = mode();
		chest = null;
		depositWait = 0;
		if (m == HhConfig.Boxes.LOADER) {
			if (storable(inv) >= 0 && (chest = nearestContainer(p, w, Job.BOXES)) != null) {
				job = Job.BOXES;
				fetched = false;
				depositStage = 1;
				return;
			}
			if (produce && !boxWarned && pickBox(inv) >= 0) {
				depositStage = 10;
				return;
			}
			if (produce && (chest = nearestContainer(p, w, Job.LOOSE)) != null && Chests.placedBox(w, chest)) {
				job = Job.LOOSE;
				depositStage = 1;
				return;
			}
			if (produce && !boxRound && (chest = nearestContainer(p, w, Job.BOXES)) != null) {
				job = Job.BOXES;
				fetched = false;
				depositStage = 1;
				return;
			}
		}
		if (produce && (chest = nearestContainer(p, w, Job.LOOSE)) != null) {
			job = Job.LOOSE;
			depositStage = 1;
			return;
		}
		if (produce && m == HhConfig.Boxes.SPARE && !boxWarned && pickBox(inv) >= 0) {
			depositStage = 10;
			return;
		}
		depositStage = 0;
		boxRound = false;
		state = State.RUN;
	}

	private static void deposit(MinecraftClient client, ClientPlayerEntity p, ClientWorld w) {
		status = Text.literal("Storing the harvest");
		switch (depositStage) {
			case 0 -> plan(p, w);
			case 1 -> {
				if (chest == null || !Chests.container(w, chest)) {
					depositStage = 0;
					return;
				}
				if (++depositWait > 600) {
					// Half a minute and still not there: leave that one be.
					log("never got to the chest at {}", chest.toShortString());
					fullChests.add(chest);
					walker.stop(client);
					depositStage = 0;
					return;
				}
				double range = p.getBlockInteractionRange() - 0.3;
				if (p.getEyePos().squaredDistanceTo(Vec3d.ofCenter(chest)) <= range * range && (walker.arrived() || !walker.moving())) {
					walker.stop(client);
					Direction face = Direction.getFacing(p.getEyePos().subtract(Vec3d.ofCenter(chest)));
					Vec3d hit = Vec3d.ofCenter(chest).add(Vec3d.of(face.getVector()).multiply(0.5));
					look(p, hit);
					client.interactionManager.interactBlock(p, Hand.MAIN_HAND, new BlockHitResult(hit, face, chest, false));
					p.swingHand(Hand.MAIN_HAND);
					depositStage = 2;
					depositWait = 40;
					return;
				}
				if (!walker.moving() || walker.arrived()) {
					BlockPos c = chest;
					double want = p.getBlockInteractionRange() - 1.0;
					if (!walker.plan(w, p, q -> Vec3d.ofBottomCenter(q).add(0, 1.62, 0).squaredDistanceTo(Vec3d.ofCenter(c)) <= want * want, bounds(p))) {
						fullChests.add(c);
						log("can't get to the chest at {}", c.toShortString());
						depositStage = 0;
						return;
					}
				}
				walker.tick(client);
			}
			case 2 -> {
				if (open(p)) {
					depositStage = 3;
					depositWait = 0;
				} else if (--depositWait <= 0) {
					fullChests.add(chest);
					depositStage = 0;
				}
			}
			case 3 -> {
				ScreenHandler h = p.currentScreenHandler;
				if (!open(p)) {
					depositStage = 0;
					return;
				}
				if (depositWait > 0) {
					depositWait--;
					return;
				}
				depositWait = 1;
				if (job == Job.BOXES) {
					swapBoxes(client, p, h);
					return;
				}
				List<Integer> todo = depositable(p.getInventory());
				int before = todo.size();
				boolean room = containerHasRoom(h, p, null);
				if (todo.isEmpty() || !room) {
					if (!room) fullChests.add(chest);
					shut(p);
					return;
				}
				// Two stacks a tick.
				for (int k = 0; k < Math.min(2, todo.size()); k++) {
					int inv = todo.get(k);
					Slot slot = playerSlot(h, p, inv);
					if (slot == null) continue;
					int count = p.getInventory().getStack(inv).getCount();
					client.interactionManager.clickSlot(h.syncId, slot.id, 0, SlotActionType.QUICK_MOVE, p);
					deposited += count - p.getInventory().getStack(inv).getCount();
				}
				if (depositable(p.getInventory()).size() == before) {
					fullChests.add(chest);
					shut(p);
				}
			}
			case 10, 11, 12, 13, 14, 15, 16 -> shulker(client, p, w);
			default -> {
				depositStage = 0;
				state = State.RUN;
			}
		}
	}

	/** A chest, barrel or box on the ground of yours is open. */
	private static boolean open(ClientPlayerEntity p) {
		return p.currentScreenHandler instanceof GenericContainerScreenHandler || p.currentScreenHandler instanceof ShulkerBoxScreenHandler && box == null;
	}

	/** Done with this chest: shut it and work out what's next. */
	private static void shut(ClientPlayerEntity p) {
		if (p.currentScreenHandler != p.playerScreenHandler) p.closeHandledScreen();
		depositStage = 0;
	}

	/**
	 * At a chest, one move a tick: full boxes go in; then for each crop still to store with no box on you that takes
	 * it, a box comes out (part-filled with that crop first, else an empty one). With no box to be had, the rest goes
	 * in loose.
	 */
	private static void swapBoxes(MinecraftClient client, ClientPlayerEntity p, ScreenHandler h) {
		PlayerInventory inv = p.getInventory();
		int full = storable(inv);
		if (full >= 0) {
			Slot slot = playerSlot(h, p, full);
			if (slot == null || !containerHasRoom(h, p, net.minecraft.item.Items.SHULKER_BOX)) {
				fullChests.add(chest);
				shut(p);
				return;
			}
			client.interactionManager.clickSlot(h.syncId, slot.id, 0, SlotActionType.QUICK_MOVE, p);
			if (!isBox(inv.getStack(full))) boxesStored++;
			return;
		}
		// Loose harvest in the chest (one stack put in to make room for a box, or what was there before): out again to be
		// boxed, as long as a box here or on you takes it. One slot stays free for the next box.
		if (emptySlots(inv) >= 2) {
			for (Slot s : h.slots) {
				ItemStack st = s.getStack();
				if (s.inventory == inv || st.isEmpty() || isBox(st) || !Farm.produce(st.getItem()) || !boxTakes(h, st.getItem())) continue;
				int count = st.getCount();
				Item item = st.getItem();
				client.interactionManager.clickSlot(h.syncId, s.id, 0, SlotActionType.QUICK_MOVE, p);
				deposited -= count - s.getStack().getCount();
				log("packing {} {} from the chest", count, item);
				return;
			}
		}
		Map<Item, Integer> need = toStore(inv);
		// Crops a box on you already takes need no other.
		for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) {
			ItemStack s = inv.getStack(i);
			if (!isBox(s)) continue;
			need.keySet().removeIf(item -> takes(s, item));
		}
		if (need.isEmpty()) {
			// Every crop has a box now. Nothing came out this time: no more coming from chests this round.
			if (!fetched) boxRound = true;
			shut(p);
			return;
		}
		Item item = need.keySet().iterator().next();
		Slot best = null;
		int bestUsed = -1;
		for (Slot s : h.slots) {
			if (s.inventory == inv || !isBox(s.getStack()) || !takes(s.getStack(), item)) continue;
			int u = used(s.getStack());
			if (u > bestUsed) {
				best = s;
				bestUsed = u;
			}
		}
		if (best == null) {
			// No box for it in this chest: if it's the only kind left, store the rest loose.
			log("no box for {} in the chest at {}", item, chest.toShortString());
			boxRound = true;
			job = Job.LOOSE;
			return;
		}
		if (emptySlots(inv) == 0) {
			// No room to take the box: one stack of the harvest goes in first.
			List<Integer> todo = depositable(inv);
			Slot s = todo.isEmpty() ? null : playerSlot(h, p, todo.getLast());
			if (s == null || !containerHasRoom(h, p, null)) {
				shut(p);
				return;
			}
			int count = s.getStack().getCount();
			client.interactionManager.clickSlot(h.syncId, s.id, 0, SlotActionType.QUICK_MOVE, p);
			deposited += count - s.getStack().getCount();
			return;
		}
		client.interactionManager.clickSlot(h.syncId, best.id, 0, SlotActionType.QUICK_MOVE, p);
		fetched = true;
	}

	/** A box in the open chest or on you that takes this item. */
	private static boolean boxTakes(ScreenHandler h, Item item) {
		for (Slot s : h.slots) if (isBox(s.getStack()) && takes(s.getStack(), item)) return true;
		return false;
	}

	// ---------------------------------------------------------------- the shulker box

	private static ContainerComponent contents(ItemStack s) {
		ContainerComponent c = s.get(DataComponentTypes.CONTAINER);
		return c == null ? ContainerComponent.DEFAULT : c;
	}

	static boolean emptyBox(ItemStack s) {
		return isBox(s) && contents(s).streamNonEmpty().findAny().isEmpty();
	}

	static boolean isBox(ItemStack s) {
		return s.getItem() instanceof BlockItem bi && bi.getBlock() instanceof ShulkerBoxBlock;
	}

	/** Slots used in a box, of its 27. */
	static int used(ItemStack box) {
		return (int) contents(box).streamNonEmpty().count();
	}

	/** A box holding nothing but harvest (or nothing): the only kind it fills. Your other boxes are never touched. */
	static boolean harvestBox(ItemStack s) {
		return isBox(s) && contents(s).streamNonEmpty().allMatch(t -> Farm.produce(t.getItem()));
	}

	/** Whether this box takes more of this item: harvest only, the same crop throughout with one crop per box on, and room. */
	static boolean takes(ItemStack box, Item item) {
		if (!harvestBox(box)) return false;
		boolean sorted = HhConfig.INSTANCE.oneCropPerBox;
		List<ItemStack> in = contents(box).streamNonEmpty().toList();
		if (sorted && in.stream().anyMatch(t -> !t.isOf(item))) return false;
		if (in.size() < 27) return true;
		return in.stream().anyMatch(t -> t.isOf(item) && t.getCount() < t.getMaxCount());
	}

	/** A box on you that's done (all 27 slots used, nothing more of this harvest fits): goes in your chest. -1: none. */
	static int storable(PlayerInventory inv) {
		java.util.Set<Item> items = toStore(inv).keySet();
		for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) {
			ItemStack s = inv.getStack(i);
			if (harvestBox(s) && used(s) == 27 && items.stream().noneMatch(item -> takes(s, item))) return i;
		}
		return -1;
	}

	/**
	 * The box on you to fill next, and what goes in it ({@link #boxItem}; null: any of the harvest). Part-filled boxes
	 * first, the fullest first, so you're never left with several half-full ones; an empty box only when none takes
	 * what there is. -1: none.
	 */
	static int pickBox(PlayerInventory inv) {
		Map<Item, Integer> items = toStore(inv);
		boolean sorted = HhConfig.INSTANCE.oneCropPerBox;
		int best = -1;
		long bestScore = -1;
		Item bestItem = null;
		for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) {
			ItemStack s = inv.getStack(i);
			if (!harvestBox(s)) continue;
			for (Map.Entry<Item, Integer> e : items.entrySet()) {
				if (!takes(s, e.getKey())) continue;
				long score = used(s) * 1_000_000L + (sorted ? e.getValue() : 0);
				if (score > bestScore) {
					best = i;
					bestScore = score;
					bestItem = sorted ? e.getKey() : null;
				}
			}
		}
		boxItem = bestItem;
		return best;
	}

	static int boxes(PlayerInventory inv) {
		int n = 0;
		for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) if (isBox(inv.getStack(i))) n += inv.getStack(i).getCount();
		return n;
	}

	/** Boxes on you with some harvest in and room left. */
	static int partBoxes(PlayerInventory inv) {
		int n = 0;
		for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) {
			ItemStack s = inv.getStack(i);
			if (harvestBox(s) && !emptyBox(s) && used(s) < 27) n++;
		}
		return n;
	}

	static int emptyBoxes(PlayerInventory inv) {
		int n = 0;
		for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) if (emptyBox(inv.getStack(i))) n++;
		return n;
	}

	/**
	 * Somewhere to put a box: at head height against a wall, beside a cell you can stand on, with air in front so it
	 * opens. Nearest first.
	 */
	private static BlockPos[] boxSpot(ClientPlayerEntity p, ClientWorld w) {
		BlockPos best = null, bestWall = null;
		double bestD = Double.MAX_VALUE;
		for (BlockPos n : farm.stand) {
			double d = n.getSquaredDistance(p.getEntityPos());
			if (d >= bestD || d > 40 * 40) continue;
			BlockPos spot = n.up();
			if (badSpots.contains(spot) || !w.getBlockState(spot).isAir()) continue;
			for (Direction dir : Direction.Type.HORIZONTAL) {
				BlockPos wall = spot.offset(dir);
				BlockState ws = w.getBlockState(wall);
				if (!ws.isSideSolidFullSquare(w, wall, dir.getOpposite()) || ws.hasBlockEntity() || farm.cells.contains(wall)) continue;
				if (!w.getBlockState(spot.offset(dir.getOpposite())).isAir()) continue;
				best = spot;
				bestWall = wall;
				bestD = d;
				break;
			}
		}
		return best == null ? null : new BlockPos[] {best, bestWall};
	}

	private static void shulker(MinecraftClient client, ClientPlayerEntity p, ClientWorld w) {
		PlayerInventory inv = p.getInventory();
		double range = p.getBlockInteractionRange() - 0.3;
		boxTicks++;
		switch (depositStage) {
			case 10 -> {
				status = Text.literal("Putting down a shulker box");
				BlockPos[] s = boxSpot(p, w);
				if (s == null) {
					giveUpBox(p, "Nowhere to put a shulker box: it needs a wall at head height with air in front.");
					return;
				}
				spot = s[0];
				boxWall = s[1];
				boxFace = Direction.getFacing(Vec3d.of(spot.subtract(boxWall)));
				BlockPos under = spot.down(), at = spot;
				Vec3d mid = Vec3d.ofCenter(spot);
				double want = range - 0.9;
				// Not under the box, or where it goes.
				if (!walker.plan(w, p, q -> !q.equals(under) && !q.equals(at) && Vec3d.ofBottomCenter(q).add(0, 1.62, 0).squaredDistanceTo(mid) <= want * want, bounds(p))) {
					badSpots.add(spot);
					spot = null;
					return;
				}
				boxTicks = 0;
				depositStage = 11;
			}
			case 11 -> {
				status = Text.literal("Putting down a shulker box");
				boolean inside = p.getBoundingBox().intersects(new Box(spot));
				if (!inside && p.getEyePos().squaredDistanceTo(Vec3d.ofCenter(spot)) <= range * range && (walker.arrived() || !walker.moving())) {
					walker.stop(client);
					int slot = pickBox(inv);
					if (slot < 0) {
						spot = null;
						depositStage = 0;
						return;
					}
					log("filling the box in slot {} ({} of 27 used) with {}", slot, used(inv.getStack(slot)), boxItem == null ? "any harvest" : boxItem);
					hand(client, p, slot, -1);
					boxHeld = boxes(inv);
					Vec3d hit = Vec3d.ofCenter(boxWall).add(Vec3d.of(boxFace.getVector()).multiply(0.5));
					look(p, hit);
					client.interactionManager.interactBlock(p, Hand.MAIN_HAND, new BlockHitResult(hit, boxFace, boxWall, false));
					p.swingHand(Hand.MAIN_HAND);
					boxTicks = 0;
					depositStage = 12;
					return;
				}
				if (!walker.tick(client) || walker.arrived() && !walker.moving() || boxTicks > 400) {
					badSpots.add(spot);
					spot = null;
					depositStage = 10;
				}
			}
			case 12 -> {
				// Placed (the server agrees: still there after a few ticks)? Then open it.
				if (boxTicks < 6) return;
				if (!(w.getBlockState(spot).getBlock() instanceof ShulkerBoxBlock)) {
					if (boxTicks < 30) return;
					log("box didn't go down at {}", spot.toShortString());
					badSpots.add(spot);
					spot = null;
					depositStage = badSpots.size() >= 4 ? 99 : 10;
					if (depositStage == 99) giveUpBox(p, "Couldn't put a shulker box down here.");
					return;
				}
				// It's down: from here on it has to come back.
				box = spot;
				spot = null;
				Vec3d hit = Vec3d.ofCenter(box).add(Vec3d.of(boxFace.getVector()).multiply(0.5));
				look(p, hit);
				client.interactionManager.interactBlock(p, Hand.MAIN_HAND, new BlockHitResult(hit, boxFace, box, false));
				p.swingHand(Hand.MAIN_HAND);
				boxTicks = 0;
				depositStage = 13;
			}
			case 13 -> {
				status = Text.literal("Filling the shulker box");
				if (p.currentScreenHandler instanceof ShulkerBoxScreenHandler h) {
					boxTicks = 0;
					boxUsedBefore = boxUsed(h, p);
					depositStage = 14;
				} else if (boxTicks > 40) {
					// Wouldn't open (something in front of it): take it back.
					log("box at {} wouldn't open", box.toShortString());
					badSpots.add(box);
					depositStage = 15;
				}
			}
			case 14 -> {
				status = Text.literal("Filling the shulker box");
				ScreenHandler h = p.currentScreenHandler;
				if (!(h instanceof ShulkerBoxScreenHandler)) {
					depositStage = 15;
					return;
				}
				if (boxTicks % 2 == 1) return;
				List<Integer> todo = depositable(inv);
				if (boxItem != null) todo.removeIf(i -> !inv.getStack(i).isOf(boxItem));
				if (todo.isEmpty() || !containerHasRoom(h, p, boxItem) || boxTicks > 200) {
					filledUp(h, p);
					p.closeHandledScreen();
					boxTicks = 0;
					depositStage = 15;
					return;
				}
				int before = todo.size();
				for (int k = 0; k < Math.min(2, todo.size()); k++) {
					int i = todo.get(k);
					Slot slot = playerSlot(h, p, i);
					if (slot == null) continue;
					int count = inv.getStack(i).getCount();
					client.interactionManager.clickSlot(h.syncId, slot.id, 0, SlotActionType.QUICK_MOVE, p);
					deposited += count - inv.getStack(i).getCount();
				}
				List<Integer> after = depositable(inv);
				if (boxItem != null) after.removeIf(i -> !inv.getStack(i).isOf(boxItem));
				if (after.size() == before) {
					filledUp(h, p);
					p.closeHandledScreen();
					boxTicks = 0;
					depositStage = 15;
				}
			}
			case 15 -> {
				// Break it: with a pickaxe if there's one to spare, by hand if not.
				status = Text.literal("Taking the shulker box back");
				if (p.currentScreenHandler != p.playerScreenHandler) p.closeHandledScreen();
				if (!(w.getBlockState(box).getBlock() instanceof ShulkerBoxBlock)) {
					client.interactionManager.cancelBlockBreaking();
					boxTicks = 0;
					depositStage = 16;
					return;
				}
				if (p.getEyePos().squaredDistanceTo(Vec3d.ofCenter(box)) > range * range) {
					if (!walker.moving()) {
						Vec3d mid = Vec3d.ofCenter(box);
						double want = range - 0.9;
						BlockPos under = box.down(), b = box;
						if (!walker.plan(w, p, q -> !q.equals(under) && !q.equals(b) && Vec3d.ofBottomCenter(q).add(0, 1.62, 0).squaredDistanceTo(mid) <= want * want, bounds(p))) {
							stop(client, "Stopped: can't get back to the shulker box.");
							return;
						}
					}
					walker.tick(client);
					return;
				}
				walker.stop(client);
				if (boxTicks <= 1 || !client.interactionManager.isBreakingBlock()) {
					int pick = -1;
					for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) {
						ItemStack s = inv.getStack(i);
						if (s.isIn(ItemTags.PICKAXES) && safe(s) && (pick < 0 || s.getMaxDamage() - s.getDamage() > inv.getStack(pick).getMaxDamage() - inv.getStack(pick).getDamage())) pick = i;
					}
					hand(client, p, pick >= 0 ? pick : harmless(inv), bestTool(inv));
					boxHeld = boxes(inv);
					look(p, Vec3d.ofCenter(box).add(Vec3d.of(boxFace.getVector()).multiply(0.5)));
					client.interactionManager.attackBlock(box, boxFace);
				} else {
					look(p, Vec3d.ofCenter(box).add(Vec3d.of(boxFace.getVector()).multiply(0.5)));
					client.interactionManager.updateBlockBreakingProgress(box, boxFace);
				}
				p.swingHand(Hand.MAIN_HAND);
			}
			case 16 -> {
				// Pick it up: walk onto it until it's back in the inventory.
				status = Text.literal("Picking up the shulker box");
				if (boxes(inv) > boxHeld) {
					log("box back after {} ticks", boxTicks);
					box = null;
					walker.clear(client);
					// More to store, another box to fill or full ones to put away: plan() sees.
					depositStage = 0;
					return;
				}
				BlockPos at = box;
				ItemEntity item = w.getEntitiesByClass(ItemEntity.class, new Box(at).expand(6), e -> e.isAlive() && isBox(e.getStack()))
						.stream().min(java.util.Comparator.comparingDouble(e -> e.squaredDistanceTo(Vec3d.ofCenter(at)))).orElse(null);
				if (item == null) {
					if (boxTicks > 100) {
						chat(p, Text.literal("Lost sight of the shulker box from " + at.toShortString() + ": pick it up yourself if it's lying there.").formatted(Formatting.RED));
						box = null;
						depositStage = 0;
						state = State.RUN;
					}
					return;
				}
				boxTicks = Math.min(boxTicks, 50);
				BlockPos ip = item.getBlockPos();
				if (!walker.moving() || walker.arrived()) {
					if (!walker.plan(w, p, q -> q.getX() == ip.getX() && q.getZ() == ip.getZ() && Math.abs(q.getY() - ip.getY()) <= 1, bounds(p))) {
						// Can't stand on it: get as close as can be, the pick-up reach does the rest.
						walker.plan(w, p, q -> Math.abs(q.getX() - ip.getX()) <= 1 && Math.abs(q.getZ() - ip.getZ()) <= 1 && Math.abs(q.getY() - ip.getY()) <= 1, bounds(p));
					}
				}
				walker.tick(client);
			}
			default -> {
			}
		}
	}

	private static void giveUpBox(ClientPlayerEntity p, String why) {
		boxWarned = true;
		box = null;
		depositStage = 0;
		state = State.RUN;
		chat(p, Text.literal(why + " Still harvesting; drops stay on the ground.").formatted(Formatting.GOLD));
	}

	/** Room in the open container for this item (null: any of the harvest; a shulker box needs an empty slot). */
	private static boolean containerHasRoom(ScreenHandler h, ClientPlayerEntity p, Item item) {
		for (Slot s : h.slots) {
			if (s.inventory == p.getInventory()) continue;
			ItemStack st = s.getStack();
			if (st.isEmpty()) return true;
			if (item == net.minecraft.item.Items.SHULKER_BOX) continue;
			if ((item == null ? Farm.produce(st.getItem()) : st.isOf(item)) && st.getCount() < st.getMaxCount()) return true;
		}
		return false;
	}

	/** Slots used in the open box. */
	private static int boxUsed(ScreenHandler h, ClientPlayerEntity p) {
		int n = 0;
		for (Slot s : h.slots) if (s.inventory != p.getInventory() && !s.getStack().isEmpty()) n++;
		return n;
	}

	/** Closing a box: count it if this filled it to the top. */
	private static void filledUp(ScreenHandler h, ClientPlayerEntity p) {
		int now = boxUsed(h, p);
		if (now == 27 && boxUsedBefore < 27) boxesFilled++;
		log("box closed: {} of 27 used (was {})", now, boxUsedBefore);
	}

	private static Slot playerSlot(ScreenHandler h, ClientPlayerEntity p, int index) {
		for (Slot s : h.slots) if (s.inventory == p.getInventory() && s.getIndex() == index) return s;
		return null;
	}

	private static void closeChest(MinecraftClient client) {
		ClientPlayerEntity p = client.player;
		if (state == State.DEPOSIT && p != null && p.currentScreenHandler != p.playerScreenHandler) p.closeHandledScreen();
		if (client.interactionManager != null && box != null) client.interactionManager.cancelBlockBreaking();
		// A box that's out stays the job (tick goes back for it); anything else is dropped.
		if (state == State.DEPOSIT) state = State.RUN;
	}

	// ---------------------------------------------------------------- eating

	private static int eating, eatFood, eatCooldown;

	/** Eats when hungry, so it can keep sprinting and never starves. True while eating. */
	private static boolean eat(MinecraftClient client, ClientPlayerEntity p) {
		if (eatCooldown > 0) eatCooldown--;
		int food = p.getHungerManager().getFoodLevel();
		if (eating > 0) {
			eating++;
			status = Text.literal("Eating");
			// Up at the sky: eating while looking at farmland would plant a carrot in it.
			p.setPitch(-90);
			client.options.useKey.setPressed(true);
			// With a screen open (inventory, tabbed out) the use key does nothing: start eating directly.
			if (!p.isUsingItem() && eating % 5 == 2) client.interactionManager.interactItem(p, Hand.MAIN_HAND);
			if (food > eatFood || eating > 60) {
				client.options.useKey.setPressed(false);
				if (food <= eatFood) eatCooldown = 600;
				eating = 0;
			}
			return true;
		}
		if (!HhConfig.INSTANCE.autoEat || eatCooldown > 0 || food > 14 && !(food < 20 && p.getHealth() <= 12)) return false;
		int slot = foodSlot(p.getInventory());
		if (slot < 0) {
			eatCooldown = 400;
			if (food <= 6) chat(p, Text.literal("Hungry and nothing to eat: bring food (it eats spare carrots too).").formatted(Formatting.YELLOW));
			return false;
		}
		walker.stop(client);
		hand(client, p, slot, bestTool(p.getInventory()));
		eating = 1;
		eatFood = food;
		return true;
	}

	private static final List<Item> FOOD = List.of(net.minecraft.item.Items.GOLDEN_CARROT, net.minecraft.item.Items.COOKED_BEEF, net.minecraft.item.Items.COOKED_PORKCHOP,
			net.minecraft.item.Items.COOKED_MUTTON, net.minecraft.item.Items.BAKED_POTATO, net.minecraft.item.Items.COOKED_CHICKEN, net.minecraft.item.Items.COOKED_SALMON,
			net.minecraft.item.Items.BREAD, net.minecraft.item.Items.COOKED_COD, net.minecraft.item.Items.PUMPKIN_PIE, net.minecraft.item.Items.APPLE,
			net.minecraft.item.Items.MELON_SLICE, net.minecraft.item.Items.COOKIE, net.minecraft.item.Items.DRIED_KELP);

	/** Real food first; then the harvest itself, but only spare carrots, potatoes or beetroot (a stack is kept to plant). */
	static int foodSlot(PlayerInventory inv) {
		for (Item item : FOOD) {
			for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) if (inv.getStack(i).isOf(item)) return i;
		}
		for (Item item : new Item[] {net.minecraft.item.Items.CARROT, net.minecraft.item.Items.BEETROOT, net.minecraft.item.Items.POTATO}) {
			int total = 0, slot = -1;
			for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) {
				if (!inv.getStack(i).isOf(item)) continue;
				total += inv.getStack(i).getCount();
				slot = i;
			}
			if (total > 64 || item == net.minecraft.item.Items.BEETROOT && total > 0) return slot;
		}
		return -1;
	}

	// ---------------------------------------------------------------- hands and tools

	static int fortune(ItemStack s) {
		for (var e : s.getEnchantments().getEnchantmentEntries()) if (e.getKey().matchesKey(Enchantments.FORTUNE)) return e.getIntValue();
		return 0;
	}

	/**
	 * Whether it may use this tool. With the tool guard on (the default) a tool is put aside with
	 * {@code toolReserve} uses left, so you can mend it and it never breaks.
	 */
	static boolean safe(ItemStack s) {
		if (!s.isDamageable()) return true;
		int left = s.getMaxDamage() - s.getDamage();
		return HhConfig.INSTANCE.protectTools ? left > HhConfig.INSTANCE.toolReserve : left > 1;
	}

	/** Where a tool can be: the hotbar, the inventory and the off hand. */
	static final int[] TOOL_SLOTS = toolSlots();

	private static int[] toolSlots() {
		int[] a = new int[PlayerInventory.MAIN_SIZE + 1];
		for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) a[i] = i;
		a[PlayerInventory.MAIN_SIZE] = PlayerInventory.OFF_HAND_SLOT;
		return a;
	}

	/**
	 * The slot with the Fortune tool it may use, or -1: by hand. Any tool with Fortune will do: the highest Fortune
	 * wins (that's what gives the most drops); on the same level a hoe, then the one with the most uses left.
	 */
	static int bestTool(PlayerInventory inv) {
		int best = -1, bestLevel = 0, bestLeft = -1;
		boolean bestHoe = false;
		for (int i : TOOL_SLOTS) {
			ItemStack s = inv.getStack(i);
			if (s.isEmpty() || !safe(s)) continue;
			int level = fortune(s);
			if (level <= 0) continue;
			int left = s.isDamageable() ? s.getMaxDamage() - s.getDamage() : Integer.MAX_VALUE;
			boolean hoe = s.isIn(ItemTags.HOES);
			if (level > bestLevel || level == bestLevel && (hoe && !bestHoe || hoe == bestHoe && left > bestLeft)) {
				best = i;
				bestLevel = level;
				bestLeft = left;
				bestHoe = hoe;
			}
		}
		return best;
	}

	/** Why the Fortune tool you have isn't used: it's down to its last uses. Empty if not that. */
	static String asideNote(PlayerInventory inv) {
		for (int i : TOOL_SLOTS) {
			ItemStack s = inv.getStack(i);
			if (!s.isEmpty() && fortune(s) > 0 && !safe(s)) {
				return "Your " + s.getName().getString() + " is down to " + (s.getMaxDamage() - s.getDamage()) + " uses: put aside so it never breaks (mend it and it's used again).";
			}
		}
		return "";
	}

	static int hoeSlot(PlayerInventory inv) {
		int best = -1;
		for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) {
			ItemStack s = inv.getStack(i);
			if (s.isIn(ItemTags.HOES) && safe(s) && (best < 0 || fortune(s) < fortune(inv.getStack(best)))) best = i;
		}
		return best;
	}

	static int seedSlot(PlayerInventory inv, Block crop) {
		Item seed = Farm.seed(crop);
		if (seed == null) return -1;
		int best = -1;
		for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) {
			ItemStack s = inv.getStack(i);
			if (!s.isOf(seed)) continue;
			// The hotbar first: no swapping.
			if (i < 9) return i;
			if (best < 0) best = i;
		}
		return best;
	}

	/** An empty slot, or something that can't wear out, to hold while picking by hand. -1: none. */
	static int harmless(PlayerInventory inv) {
		for (int i = 0; i < 9; i++) if (inv.getStack(i).isEmpty()) return i;
		for (int i = 0; i < 9; i++) if (!inv.getStack(i).isDamageable()) return i;
		for (int i = 9; i < PlayerInventory.MAIN_SIZE; i++) if (inv.getStack(i).isEmpty() || !inv.getStack(i).isDamageable()) return i;
		return -1;
	}

	/**
	 * Puts inventory slot {@code slot} in hand. From the main inventory it's swapped into the hotbar, never onto
	 * {@code avoid}'s slot (the tool, so the two don't keep trading places).
	 */
	static void hand(MinecraftClient client, ClientPlayerEntity p, int slot, int avoid) {
		PlayerInventory inv = p.getInventory();
		if (slot < 0) return;
		if (slot < 9) {
			inv.setSelectedSlot(slot);
			sync(client);
			return;
		}
		int target = -1;
		for (int i = 0; i < 9 && target < 0; i++) if (inv.getStack(i).isEmpty()) target = i;
		for (int i = 8; i >= 0 && target < 0; i--) {
			ItemStack s = inv.getStack(i);
			if (i != avoid && fortune(s) == 0 && !s.isIn(ItemTags.HOES) && !Farm.produce(s.getItem())) target = i;
		}
		for (int i = 8; i >= 0 && target < 0; i--) if (i != avoid) target = i;
		if (slot == PlayerInventory.OFF_HAND_SLOT) {
			// From the off hand: swap it with that hotbar slot (the same as pressing F over it).
			client.interactionManager.clickSlot(p.playerScreenHandler.syncId, 36 + target, PlayerInventory.OFF_HAND_SLOT, SlotActionType.SWAP, p);
		} else {
			client.interactionManager.clickSlot(p.playerScreenHandler.syncId, slot, target, SlotActionType.SWAP, p);
		}
		inv.setSelectedSlot(target);
		sync(client);
	}

	/** The server must know the slot before a crop is hit, or it breaks it with the old item (no Fortune). */
	private static void sync(MinecraftClient client) {
		if (client.interactionManager != null) ((com.greysworkshop.harvesthand.mixin.InteractionManagerAccess) client.interactionManager).harvesthand$syncSelectedSlot();
	}

	static void look(ClientPlayerEntity p, Vec3d at) {
		Vec3d d = at.subtract(p.getEyePos());
		double flat = Math.sqrt(d.x * d.x + d.z * d.z);
		p.setYaw((float) (MathHelper.atan2(d.z, d.x) * 180 / Math.PI) - 90);
		p.setPitch((float) -(MathHelper.atan2(d.y, flat) * 180 / Math.PI));
	}

	private static void release(MinecraftClient client) {
		client.options.forwardKey.setPressed(false);
		client.options.jumpKey.setPressed(false);
		client.options.sneakKey.setPressed(false);
		client.options.sprintKey.setPressed(false);
	}

	/** Crops ripe right now. */
	static int ripe(ClientWorld w) {
		int n = 0;
		for (BlockPos c : farm.cells) if (Farm.mature(w.getBlockState(c))) n++;
		return n;
	}

	static void say(MinecraftClient client, String s) {
		if (client.player != null) chat(client.player, Text.literal(s).formatted(Formatting.GRAY));
	}

	static void chestAdded(MinecraftClient client, BlockPos c, String msg) {
		if (running() && farm != null) {
			BlockPos other = client.world == null ? null : Chests.otherHalf(client.world, c);
			if (other == null || !farm.chests.contains(other)) farm.chests.add(c);
			fullChests.remove(c);
			if (other != null) fullChests.remove(other);
			fullWarned = false;
		}
		if (!msg.isEmpty()) say(client, msg);
	}

	static void chestRemoved(MinecraftClient client, BlockPos c) {
		BlockPos other = client.world == null ? null : Chests.otherHalf(client.world, c);
		if (farm != null) {
			farm.chests.remove(c);
			if (other != null) farm.chests.remove(other);
		}
		say(client, "Harvest Hand won't use this chest any more.");
	}

	static void chat(ClientPlayerEntity p, Text t) {
		p.sendMessage(Text.literal("[Harvest Hand] ").formatted(Formatting.GOLD).append(t), false);
	}

	static void log(String msg, Object... args) {
		if (Boolean.getBoolean("harvesthand.debug")) HarvestHand.LOGGER.info("[job] " + msg, args);
	}
}

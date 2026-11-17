package com.greysworkshop.harvesthand;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
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
 * of the farm when there's nothing to do. A full inventory goes into a chest or barrel at the farm's edge.
 */
final class Harvester {
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

	private Harvester() {}

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

	static void start(MinecraftClient client, int radius) {
		ClientPlayerEntity p = client.player;
		if (p == null || client.world == null || running()) return;
		Farm f = Farm.scan(client.world, Walker.feet(p), radius);
		if (f.cells.isEmpty()) {
			stopReason = "No farmland here: stand inside your farm and start again.";
			chat(p, Text.literal(stopReason).formatted(Formatting.RED));
			return;
		}
		farm = f;
		state = State.RUN;
		paused = holding = fullWarned = false;
		harvested = replanted = planted = tilled = deposited = ticks = fortuneHarvests = 0;
		waitTotal = waitCount = waitMax = 0;
		lastFortune = -1;
		fightUntil = 0;
		lastHealth = -1;
		cool.clear();
		unreachable.clear();
		ripeSince.clear();
		fullChests.clear();
		walkingTo = null;
		stopReason = "";
		status = Text.literal("Watching");
		int x = f.maxX - f.minX + 1, z = f.maxZ - f.minZ + 1;
		chat(p, Text.literal("Farming " + f.cells.size() + " plots (" + x + " × " + z + ")"
				+ (f.chests.isEmpty() ? "" : ", " + f.chests.size() + " chest" + (f.chests.size() == 1 ? "" : "s") + " for a full inventory")
				+ ". Numpad 5 pauses, Numpad 6 stops.").formatted(Formatting.GREEN));
		chat(p, Text.literal(toolNote(p.getInventory())).formatted(Formatting.GRAY));
		log("farm: {} cells, {} standing, {} chests, centre {}, main {}", f.cells.size(), f.stand.size(), f.chests.size(), f.centre, f.main);
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
		state = State.OFF;
		paused = false;
		stopReason = why;
		walker.clear(client);
		release(client);
		if (client.player != null) {
			chat(client.player, Text.literal(why + " " + summary()).formatted(Formatting.YELLOW));
		}
		HhConfig.INSTANCE.totalHarvested += harvested;
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
		if (p.hurtTime > 0 || lastHealth >= 0 && hp < lastHealth) fightUntil = ticks + 60;
		lastHealth = hp;
		boolean low = hp <= HhConfig.INSTANCE.minHealth;
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
		// Your own screen open (inventory, chat): wait. Ours (the chest) is fine.
		if (client.currentScreen != null && state != State.DEPOSIT) {
			walker.stop(client);
			return;
		}
		cool.values().removeIf(t -> t <= ticks);
		if (state == State.DEPOSIT) {
			deposit(client, p, w);
			return;
		}
		if (shouldDeposit(p)) {
			state = State.DEPOSIT;
			depositStage = 0;
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
	private static void trackRipe(ClientWorld w) {
		if (ticks % 2 != 0) return;
		for (BlockPos c : farm.cells) {
			if (Farm.mature(w.getBlockState(c))) ripeSince.putIfAbsent(c, ticks);
			else ripeSince.remove(c);
		}
	}

	/** What needs doing in a cell right now, or null. */
	private enum Need {HARVEST, PLANT, TILL}

	private static Need need(ClientWorld w, PlayerInventory inv, BlockPos c) {
		BlockState s = w.getBlockState(c);
		if (Farm.isCrop(s.getBlock())) {
			farm.crop.put(c, s.getBlock());
			return Farm.mature(s) ? Need.HARVEST : null;
		}
		if (!s.isAir()) return null;
		BlockState below = w.getBlockState(c.down());
		if (Farm.soil(below)) {
			Block b = farm.wanted(w, c);
			return b != null && seedSlot(inv, b) >= 0 ? Need.PLANT : null;
		}
		if (HhConfig.INSTANCE.till && Farm.tillable(below) && farm.crop.containsKey(c) && hoeSlot(inv) >= 0) return Need.TILL;
		return null;
	}

	/** Does what's in reach, up to the per-tick limit. Returns how many cells it worked. */
	private static int work(MinecraftClient client, ClientPlayerEntity p, ClientWorld w) {
		PlayerInventory inv = p.getInventory();
		double range = p.getBlockInteractionRange() - 0.3;
		Vec3d eye = p.getEyePos();
		int done = 0, limit = HhConfig.INSTANCE.perTick;
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
					if (plant(client, p, w, c, b)) replanted++;
				}
				case PLANT -> {
					if (plant(client, p, w, c, farm.wanted(w, c))) planted++;
				}
				case TILL -> {
					if (till(client, p, c)) tilled++;
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

	private static double dropScore(ItemEntity e, double d, java.util.Set<Item> missing) {
		return missing.contains(e.getStack().getItem()) ? d - 100 : d + 4 - e.getItemAge() / 200.0;
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

	private static int[] bounds(ClientPlayerEntity p) {
		return new int[] {Math.min(farm.minX, p.getBlockX()) - 3, Math.min(farm.minY, p.getBlockY()) - 6, Math.min(farm.minZ, p.getBlockZ()) - 3,
				Math.max(farm.maxX, p.getBlockX()) + 3, Math.max(farm.maxY, p.getBlockY()) + 6, Math.max(farm.maxZ, p.getBlockZ()) + 3};
	}

	// ---------------------------------------------------------------- the chest

	private static boolean shouldDeposit(ClientPlayerEntity p) {
		PlayerInventory inv = p.getInventory();
		if (inv.getEmptySlot() >= 0) {
			fullWarned = false;
			return false;
		}
		if (!HhConfig.INSTANCE.deposit || farm.chests.isEmpty() || fullChests.containsAll(farm.chests) || depositable(inv).isEmpty()) {
			if (!fullWarned) {
				fullWarned = true;
				chat(p, Text.literal(farm.chests.isEmpty() ? "Inventory full: still harvesting and replanting, but drops stay on the ground. Put a chest at the farm's edge and restart to store them."
						: "Inventory full and the chests are full: still harvesting and replanting, drops stay on the ground.").formatted(Formatting.GOLD));
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

	private static void deposit(MinecraftClient client, ClientPlayerEntity p, ClientWorld w) {
		status = Text.literal("Storing the harvest");
		switch (depositStage) {
			case 0 -> {
				// The nearest chest that isn't full.
				chest = farm.chests.stream().filter(c -> !fullChests.contains(c))
						.min(java.util.Comparator.comparingDouble(c -> p.getEntityPos().squaredDistanceTo(Vec3d.ofCenter(c)))).orElse(null);
				if (chest == null) {
					state = State.RUN;
					return;
				}
				BlockPos c = chest;
				double want = p.getBlockInteractionRange() - 1.0;
				if (!walker.plan(w, p, q -> Vec3d.ofBottomCenter(q).add(0, 1.62, 0).squaredDistanceTo(Vec3d.ofCenter(c)) <= want * want, bounds(p))) {
					fullChests.add(c);
					log("can't get to the chest at {}", c.toShortString());
					return;
				}
				depositStage = 1;
			}
			case 1 -> {
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
				if (!walker.tick(client) || walker.arrived() && !walker.moving()) depositStage = 0;
			}
			case 2 -> {
				if (p.currentScreenHandler instanceof GenericContainerScreenHandler) {
					depositStage = 3;
					depositWait = 0;
				} else if (--depositWait <= 0) {
					fullChests.add(chest);
					depositStage = 0;
				}
			}
			case 3 -> {
				ScreenHandler h = p.currentScreenHandler;
				if (!(h instanceof GenericContainerScreenHandler)) {
					depositStage = 0;
					return;
				}
				if (depositWait > 0) {
					depositWait--;
					return;
				}
				List<Integer> todo = depositable(p.getInventory());
				int before = todo.size();
				boolean room = containerHasRoom(h, p);
				if (todo.isEmpty() || !room) {
					if (!room) fullChests.add(chest);
					closeChest(client);
					state = State.RUN;
					depositStage = 0;
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
					closeChest(client);
					depositStage = 0;
				}
				depositWait = 1;
			}
			default -> depositStage = 0;
		}
	}

	private static boolean containerHasRoom(ScreenHandler h, ClientPlayerEntity p) {
		for (Slot s : h.slots) {
			if (s.inventory == p.getInventory()) continue;
			ItemStack st = s.getStack();
			if (st.isEmpty() || Farm.produce(st.getItem()) && st.getCount() < st.getMaxCount()) return true;
		}
		return false;
	}

	private static Slot playerSlot(ScreenHandler h, ClientPlayerEntity p, int index) {
		for (Slot s : h.slots) if (s.inventory == p.getInventory() && s.getIndex() == index) return s;
		return null;
	}

	private static void closeChest(MinecraftClient client) {
		ClientPlayerEntity p = client.player;
		if (state == State.DEPOSIT && p != null && p.currentScreenHandler != p.playerScreenHandler) p.closeHandledScreen();
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

	/** The slot with the highest Fortune it may use (most uses left on a tie, hoes first), or -1: by hand. */
	static int bestTool(PlayerInventory inv) {
		int best = -1, bestLevel = 0, bestLeft = -1;
		boolean bestHoe = false;
		for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) {
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
		for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) {
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
			return;
		}
		int target = -1;
		for (int i = 0; i < 9 && target < 0; i++) if (inv.getStack(i).isEmpty()) target = i;
		for (int i = 8; i >= 0 && target < 0; i--) {
			ItemStack s = inv.getStack(i);
			if (i != avoid && fortune(s) == 0 && !s.isIn(ItemTags.HOES) && !Farm.produce(s.getItem())) target = i;
		}
		for (int i = 8; i >= 0 && target < 0; i--) if (i != avoid) target = i;
		client.interactionManager.clickSlot(p.playerScreenHandler.syncId, slot, target, SlotActionType.SWAP, p);
		inv.setSelectedSlot(target);
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

	static void chat(ClientPlayerEntity p, Text t) {
		p.sendMessage(Text.literal("[Harvest Hand] ").formatted(Formatting.GOLD).append(t), false);
	}

	static void log(String msg, Object... args) {
		if (Boolean.getBoolean("harvesthand.debug")) HarvestHand.LOGGER.info("[job] " + msg, args);
	}
}

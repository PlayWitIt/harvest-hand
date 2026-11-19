package com.greysworkshop.harvesthand;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CropBlock;
import net.minecraft.block.FarmlandBlock;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.resource.DataConfiguration;
import net.minecraft.resource.featuretoggle.FeatureFlags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.world.Difficulty;
import net.minecraft.world.GameMode;
import net.minecraft.world.gen.GeneratorOptions;
import net.minecraft.world.gen.WorldPresets;
import net.minecraft.world.level.LevelInfo;
import net.minecraft.world.rule.GameRules;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Development-only, {@code ./gradlew runSelfTest}. A 31 × 31 walled farm on a superflat world, a water source in the
 * middle, carrots with a strip of wheat, an empty patch, a chest in a corner and farmland outside the wall. Survival,
 * with a Fortune III hoe and a worn one. Checks it finds the farm, clears what's ripe, keeps up when crops ripen one at
 * a time and in a rush, stores a full inventory, harvests by hand without a usable tool, and pauses and stops.
 */
final class SelfTest {
	private static final String WORLD = "harvesthand-selftest";
	private static final int R = 15, Y = -60;
	private static final BlockPos CHEST = new BlockPos(R, Y, R);
	/** A chest that was just there (the server put it down, not you): never used. */
	private static final BlockPos OTHER = new BlockPos(-R, Y, R);
	private static boolean started;
	private static int tick = -1, phaseStart, passed, failed;
	private static String phase = "setup";
	private static final java.util.Random RNG = new java.util.Random(7);
	private static final List<Integer> waits = new ArrayList<>();
	private static BlockPos trial;
	private static int trialStart, mark, maxRipe, rushCount;
	private static long rushWait;
	private static List<BlockPos> carrotCells = new ArrayList<>();
	private static final List<BlockPos> ripened = new ArrayList<>();

	private SelfTest() {}

	static void start() {
		log("Self-test armed");
		ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
			if (!started && screen instanceof net.minecraft.client.gui.screen.AccessibilityOnboardingScreen) {
				client.options.onboardAccessibility = false;
				client.send(() -> client.setScreen(new TitleScreen()));
				return;
			}
			if (started || !(screen instanceof TitleScreen)) return;
			started = true;
			client.send(() -> openWorld(client));
		});
		ClientTickEvents.END_CLIENT_TICK.register(SelfTest::tick);
	}

	private static void openWorld(MinecraftClient client) {
		client.options.pauseOnLostFocus = false;
		deleteTree(client.runDirectory.toPath().resolve("saves").resolve(WORLD));
		try {
			HhConfig.useFile(Files.createTempDirectory("harvesthand").resolve("harvesthand.json"));
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		LevelInfo info = new LevelInfo(WORLD, GameMode.SURVIVAL, false, Difficulty.PEACEFUL, true,
				new GameRules(FeatureFlags.DEFAULT_ENABLED_FEATURES), DataConfiguration.SAFE_MODE);
		client.createIntegratedServerLoader().createAndStart(WORLD, info, GeneratorOptions.createTestWorld(),
				WorldPresets::createTestOptions, new TitleScreen());
	}

	private static void deleteTree(Path dir) {
		try (Stream<Path> files = Files.exists(dir) ? Files.walk(dir) : Stream.empty()) {
			files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
		} catch (IOException e) {
			log("Could not clear " + dir + ": " + e);
		}
	}

	private static boolean wheatRow(int z) {
		return z >= -R && z <= -R + 2;
	}

	private static boolean emptyPatch(int x, int z) {
		return x >= 5 && x <= 7 && z >= 5 && z <= 7;
	}

	private static void tick(MinecraftClient client) {
		if (client.player == null || client.world == null || client.getServer() == null) return;
		// The game menu (the window losing focus on this desktop) pauses the whole world: shut it, and a lone-crop
		// trial it interrupted doesn't count.
		if (client.currentScreen instanceof net.minecraft.client.gui.screen.GameMenuScreen) {
			client.setScreen(null);
			log("game menu opened by itself: closed" + (trial != null ? ", trial at " + trial.toShortString() + " not counted" : ""));
			if (trial != null) {
				BlockPos t = trial;
				server(client, srv -> {
					if (srv.getOverworld().getBlockState(t).isOf(Blocks.CARROTS)) srv.getOverworld().setBlockState(t, Blocks.CARROTS.getDefaultState(), 3);
					return 0;
				});
				trial = null;
			}
			return;
		}
		tick++;
		int since = tick - phaseStart;
		PlayerInventory inv = client.player.getInventory();
		switch (phase) {
			case "setup" -> {
				if (tick == 20) build(client);
				if (tick == 140) {
					command(client, "tp @p 0.5 " + Y + " 2.5");
					command(client, "item replace entity @p hotbar.2 with minecraft:diamond_hoe[minecraft:enchantments={\"minecraft:fortune\":3}]");
					// Fortune III too, but down to its last 10 uses: the tool guard keeps it aside.
					command(client, "item replace entity @p hotbar.5 with minecraft:netherite_hoe[minecraft:enchantments={\"minecraft:fortune\":3},minecraft:damage=2021]");
					command(client, "item replace entity @p hotbar.0 with minecraft:carrot 10");
					command(client, "item replace entity @p inventory.4 with minecraft:wheat_seeds 3");
					command(client, "item replace entity @p hotbar.8 with minecraft:bread 16");
					command(client, "tp @p " + (R - 1.5) + " " + Y + " " + (R - 1.5));
					command(client, "item replace entity @p hotbar.7 with minecraft:chest");
				}
				// You put the chest down yourself, the way a player does: that's the one it may use.
				if (tick == 165) {
					inv.setSelectedSlot(7);
					net.minecraft.util.math.Vec3d top = net.minecraft.util.math.Vec3d.ofCenter(CHEST.down()).add(0, 0.5, 0);
					Harvester.look(client.player, top);
					client.interactionManager.interactBlock(client.player, net.minecraft.util.Hand.MAIN_HAND,
							new net.minecraft.util.hit.BlockHitResult(top, net.minecraft.util.math.Direction.UP, CHEST.down(), false));
				}
				if (tick == 185) {
					inv.setSelectedSlot(0);
					command(client, "tp @p 0.5 " + Y + " 2.5");
				}
				if (tick == 200) {
					Harvester.start(client, HhConfig.INSTANCE.radius);
					Farm f = Harvester.farm;
					int expected = 31 * 31 - 3;
					check("found the farm: " + (f == null ? 0 : f.cells.size()) + " plots (expected " + expected + ")", f != null && f.cells.size() == expected);
					check("nothing from outside the wall", f != null && f.cells.stream().allMatch(c -> Math.abs(c.getX()) <= R && Math.abs(c.getZ()) <= R));
					check("uses the chest you placed, not the one that was just there (" + (f == null ? "" : f.chests) + ")",
							f != null && f.chests.contains(CHEST) && !f.chests.contains(OTHER) && server(client, s -> s.getOverworld().getBlockState(CHEST).isOf(Blocks.CHEST) ? 1 : 0) == 1);
					Chests.toggle(client, OTHER);
					boolean on = f != null && f.chests.contains(OTHER);
					Chests.toggle(client, OTHER);
					check("/hh chest marks and unmarks a chest", on && f != null && !f.chests.contains(OTHER) && Chests.mine(client).equals(java.util.Set.of(CHEST)));
					check("main crop is carrots (" + (f == null ? "" : f.main) + ")", f != null && f.main == Blocks.CARROTS);
					check("waits in the middle (" + (f == null ? "" : f.centre) + ")", f != null && f.centre != null && Math.abs(f.centre.getX()) <= 1 && Math.abs(f.centre.getZ()) <= 1);
					int best = Harvester.bestTool(inv);
					check("picks the Fortune III diamond hoe, not the worn netherite one (slot " + best + ")", best == 2);
					check("running", Harvester.running());
					carrotCells = f.cells.stream().filter(c -> !wheatRow(c.getZ()) && !emptyPatch(c.getX(), c.getZ())).toList();
					watchBreaks();
					serverBreaks = serverFortune = 0;
					mark = server(client, s -> ripe(s.getOverworld()));
					log("ripe at start: " + mark);
					next("clear");
				}
			}
			case "clear" -> {
				if (since % 100 == 0) log("clear: " + Harvester.status.getString() + " · " + Harvester.summary() + " · ripe " + Harvester.ripe(client.world)
						+ (Harvester.ripe(client.world) < 15 ? " at " + Harvester.farm.cells.stream().filter(c -> Farm.mature(client.world.getBlockState(c))).map(BlockPos::toShortString).toList()
						+ " me " + client.player.getBlockPos().toShortString() : ""));
				int[] r = since % 10 == 0 ? server(client, s -> new int[] {ripe(s.getOverworld()), bare(s.getOverworld())}, null) : null;
				if (r != null && r[0] == 0 && r[1] == 0) {
					log("cleared in " + since + " ticks (" + since / 20 + " s): " + Harvester.summary());
					check("every ripe crop harvested (" + Harvester.harvested + " of " + mark + ")", Harvester.harvested >= mark);
					check("all replanted, empty patch planted (" + Harvester.replanted + " replanted at once, " + Harvester.planted + " planted)",
							Harvester.replanted + Harvester.planted >= Harvester.harvested + 9 && Harvester.planted >= 9);
					check("every harvest with Fortune (" + Harvester.fortuneHarvests + " of " + Harvester.harvested + ")", Harvester.fortuneHarvests == Harvester.harvested);
					check("the Fortune hoe is what you see in hand between crops (" + client.player.getMainHandStack().getName().getString() + ")",
							Harvester.fortune(client.player.getMainHandStack()) > 0);
					// What counts for the drops is what the server thinks is in your hand.
					check("the server broke every crop with the Fortune hoe (" + serverFortune + " of " + serverBreaks + ")",
							serverBreaks >= mark && serverFortune == serverBreaks);
					int[] k = server(client, s -> kinds(s.getOverworld()), null);
					check("the wheat strip is still wheat and the rest carrots (" + k[0] + " wheat wrong, " + k[1] + " carrots wrong)", k[0] == 0 && k[1] == 0);
					check("worn netherite hoe untouched (" + inv.getStack(5).getDamage() + ")", inv.getStack(5).getDamage() == 2021);
					screenshot(client, "1_cleared");
					next("pickup");
				} else if (since > 20 * 120) fail(client, "didn't clear the ripe crops in 2 minutes: " + Harvester.summary());
			}
			case "pickup" -> {
				int left = since % 20 == 0 ? server(client, s -> drops(s.getOverworld())) : -1;
				if (mark >= 0 && (left >= 0 && left <= 3 || since >= 20 * 60)) {
					if (left < 0) left = server(client, s -> drops(s.getOverworld()));
					if (left > 3) log("drops left at " + server(client, s -> { s.getOverworld().getEntitiesByClass(ItemEntity.class, new Box(-R, Y - 2, -R, R + 1, Y + 3, R + 1), e -> true)
							.forEach(e -> log("  " + e.getBlockPos().toShortString() + " " + e.getStack() + " age " + e.getItemAge())); return 0; }));
					// Crops keep ripening all the while, and come first; but no drop is left lying for more than a minute or so.
					int oldest = server(client, s -> s.getOverworld().getEntitiesByClass(ItemEntity.class, new Box(-R, Y - 2, -R, R + 1, Y + 3, R + 1), e -> true)
							.stream().mapToInt(ItemEntity::getItemAge).max().orElse(0));
					check("drops picked up while crops keep ripening (" + left + " on the ground after " + since / 20 + " s, oldest " + oldest / 20 + " s), carrots now " + count(inv, Items.CARROT),
							(left <= 3 || oldest <= 20 * 90) && count(inv, Items.CARROT) > 10);
					mark = -1;
					phaseStart = tick - 201;
					since = 201;
					// Trampled farmland where carrots grew: it tills it again and replants.
					server(client, s -> {
						ServerWorld w = s.getOverworld();
						for (BlockPos c : new BlockPos[] {new BlockPos(-10, Y, 10), new BlockPos(10, Y, -8)}) {
							w.setBlockState(c, Blocks.AIR.getDefaultState(), 3);
							w.setBlockState(c.down(), Blocks.DIRT.getDefaultState(), 3);
						}
						return 0;
					});
					command(client, "give @p minecraft:wooden_hoe");
				}
				if (mark == -1 && since == 600) {
					boolean ok = server(client, s -> {
						ServerWorld w = s.getOverworld();
						int n = 0;
						for (BlockPos c : new BlockPos[] {new BlockPos(-10, Y, 10), new BlockPos(10, Y, -8)}) {
							if (w.getBlockState(c.down()).isOf(Blocks.FARMLAND) && w.getBlockState(c).isOf(Blocks.CARROTS)) n++;
						}
						return n;
					}) == 2;
					check("trampled farmland tilled and replanted (" + Harvester.tilled + " tilled)", ok && Harvester.tilled >= 2);
					next("single");
				}
			}
			// One crop ripens at a time, anywhere in the farm: how long until it's harvested and replanted?
			case "single" -> {
				if (trial == null && since % 50 == 0 && waits.size() < 30) {
					trial = carrotCells.get(RNG.nextInt(carrotCells.size()));
					BlockPos t = trial;
					server(client, s -> {
						s.getOverworld().setBlockState(t, Blocks.CARROTS.getDefaultState().with(CropBlock.AGE, 7), 3);
						return 0;
					});
					trialStart = tick;
					log(String.format("trial at %s, %.1f blocks off, %s", t.toShortString(), Math.sqrt(client.player.getEntityPos().squaredDistanceTo(t.toCenterPos())), Harvester.status.getString()));
				} else if (trial != null) {
					BlockPos t = trial;
					int age = server(client, s -> {
						BlockState st = s.getOverworld().getBlockState(t);
						return st.isOf(Blocks.CARROTS) ? st.get(CropBlock.AGE) : -1;
					});
					if (age >= 0 && age < 7) {
						waits.add(tick - trialStart);
						log("  done in " + (tick - trialStart) + " ticks");
						trial = null;
					} else if (tick - trialStart > 20 * 20) {
						waits.add(tick - trialStart);
						log("trial at " + t.toShortString() + " not done in 20 s (age " + age + ")");
						trial = null;
					}
				}
				if (waits.size() >= 30 && trial == null) {
					List<Integer> sorted = waits.stream().sorted().toList();
					double avg = waits.stream().mapToInt(Integer::intValue).average().orElse(0) / 20;
					log("single crops: waits (ticks) " + sorted);
					int p90 = sorted.get(sorted.size() * 9 / 10);
					check(String.format("a lone ripe crop anywhere is replanted in %.1f s on average (median %.2f s, 9 in 10 within %.1f s, worst %.1f s)", avg,
							sorted.get(sorted.size() / 2) / 20.0, p90 / 20.0, sorted.get(sorted.size() - 1) / 20.0), avg <= 5.0 && p90 <= 20 * 12);
					next("rush");
					command(client, "gamerule random_tick_speed 40");
					command(client, "gamerule randomTickSpeed 40");
					mark = Harvester.harvested;
					maxRipe = 0;
					rushWait = Harvester.waitTotal;
					rushCount = Harvester.waitCount;
				}
			}
			// Random ticks 13x: crops ripen all over the farm. It has to keep up for two minutes.
			case "rush" -> {
				if (since % 20 == 0) {
					int ripe = server(client, s -> ripe(s.getOverworld()));
					if (since > 400) maxRipe = Math.max(maxRipe, ripe);
					if (since % 200 == 0) log("rush " + since / 20 + " s: " + ripe + " ripe · " + Harvester.summary() + " · " + Harvester.status.getString()
							+ String.format(" · avg wait %.1f s", Harvester.waitTotal / (double) Math.max(1, Harvester.waitCount) / 20));
				}
				if (since == 1200) screenshot(client, "2_rush");
				if (since == 20 * 120) {
					command(client, "gamerule random_tick_speed 3");
					command(client, "gamerule randomTickSpeed 3");
					int done = Harvester.harvested - mark;
					int[] r = server(client, s -> new int[] {bare(s.getOverworld()), farmland(s.getOverworld()), drops(s.getOverworld())}, null);
					log("rush: " + done + " harvested in 2 min, most ripe at once " + maxRipe + ", bare " + r[0] + ", farmland " + r[1] + ", drops " + r[2]);
					double avg = (Harvester.waitTotal - rushWait) / (double) Math.max(1, Harvester.waitCount - rushCount) / 20;
					check(String.format("kept up with the rush (%d harvested, %.1f s average wait, at most %d ripe at once)", done, avg, maxRipe), done > 100 && avg <= 15 && maxRipe <= 40);
					check("no plot left bare (" + r[0] + ") and none trampled (" + r[1] + " of " + (31 * 31 - 3) + " farmland)", r[0] <= 3 && r[1] >= 31 * 31 - 3 - 2);
					check("walker rarely stuck (" + Walker.stuckCount + ")", Walker.stuckCount <= 3);
					next("full");
				}
			}
			// Inventory full: the harvest goes into the chest, one stack of each seed is kept.
			case "full" -> {
				if (since == 1) {
					command(client, "give @p minecraft:carrot 192");
					command(client, "give @p minecraft:wheat 128");
				}
				if (since == 10) {
					for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) {
						if (inv.getStack(i).isEmpty()) command(client, "item replace entity @p " + (i < 9 ? "hotbar." + i : "inventory." + (i - 9)) + " with minecraft:cobblestone 64");
					}
					mark = server(client, s -> chestCount(s.getOverworld()));
				}
				if (since > 20 && Harvester.state == Harvester.State.DEPOSIT && since % 10 == 0) log("depositing: " + Harvester.status.getString());
				if (since == 20 * 30) {
					int inChest = server(client, s -> chestCount(s.getOverworld()));
					check("full inventory stored in the chest (" + inChest + " items, " + Harvester.deposited + " counted)", inChest > 300 && Harvester.deposited > 300);
					check("kept a stack of carrots to plant (" + count(inv, Items.CARROT) + ") and the hoe", count(inv, Items.CARROT) > 0 && inv.getStack(2).isOf(Items.DIAMOND_HOE));
					check("back to farming (" + Harvester.state + ", screen " + client.currentScreen + ")", Harvester.state == Harvester.State.RUN && client.currentScreen == null);
					command(client, "clear @p minecraft:cobblestone");
					next("box");
				}
			}
			// No chest to use: an empty shulker box goes against the wall, is filled, broken and picked back up.
			case "box" -> {
				if (since == 1) {
					Chests.toggle(client, CHEST);
					command(client, "item replace entity @p inventory.20 with minecraft:shulker_box");
					command(client, "item replace entity @p inventory.21 with minecraft:blue_shulker_box");
					command(client, "give @p minecraft:carrot 192");
					command(client, "give @p minecraft:wheat 128");
				}
				if (since == 10) {
					for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) {
						if (inv.getStack(i).isEmpty()) command(client, "item replace entity @p " + (i < 9 ? "hotbar." + i : "inventory." + (i - 9)) + " with minecraft:cobblestone 64");
					}
					mark = Harvester.deposited;
				}
				if (since > 20 && since % 20 == 0) log("box: " + Harvester.status.getString() + " · " + Harvester.state + " · box " + Harvester.box);
				// One crop per box: the carrots in one, the wheat in the other.
				boolean back = since > 60 && Harvester.box == null && Harvester.state == Harvester.State.RUN && Harvester.boxes(inv) == 2 && filledBoxes(inv) == 2;
				if (back || since == 20 * 60) {
					int placed = server(client, s -> shulkersOut(s.getOverworld()));
					check("full inventory went into the shulker boxes, one crop each (" + (Harvester.deposited - mark) + " items, " + filledBoxes(inv) + " filled, "
							+ boxKinds(inv) + ", after " + since / 20 + " s)", Harvester.deposited - mark > 300 && filledBoxes(inv) == 2 && boxKinds(inv).equals("carrot,wheat"));
					check("both shulker boxes back in the inventory, none left out (" + Harvester.boxes(inv) + " held, " + placed + " placed)", Harvester.boxes(inv) == 2 && placed == 0);
					check("the chest that was just there never touched (" + server(client, s -> chestCount(s.getOverworld(), OTHER)) + " items)",
							server(client, s -> chestCount(s.getOverworld(), OTHER)) == 0);
					check("back to farming, carrots kept (" + Harvester.state + ", " + count(inv, Items.CARROT) + ")", Harvester.state == Harvester.State.RUN && count(inv, Items.CARROT) > 0);
					command(client, "clear @p minecraft:cobblestone");
					command(client, "clear @p minecraft:shulker_box");
					command(client, "clear @p minecraft:blue_shulker_box");
					next("topup");
				}
			}
			// A part-filled box is topped up to the top before an empty one is opened; a box of your own stuff is never touched.
			case "topup" -> {
				if (since == 1) {
					StringBuilder c = new StringBuilder();
					for (int i = 0; i < 20; i++) c.append(i == 0 ? "" : ",").append("{slot:").append(i).append(",item:{id:\"minecraft:carrot\",count:64}}");
					command(client, "item replace entity @p inventory.20 with minecraft:blue_shulker_box[minecraft:container=[" + c + "]]");
					command(client, "item replace entity @p inventory.21 with minecraft:shulker_box");
					command(client, "item replace entity @p inventory.22 with minecraft:red_shulker_box[minecraft:container=[{slot:0,item:{id:\"minecraft:diamond\",count:1}}]]");
					command(client, "give @p minecraft:carrot 768");
				}
				if (since == 10) {
					fillRest(client, inv, 0);
					mark = Harvester.deposited;
				}
				if (since > 20 && since % 20 == 0) log("topup: " + Harvester.status.getString() + " · " + Harvester.state + " · box " + Harvester.box);
				ItemStack blue = find(inv, Items.BLUE_SHULKER_BOX), white = find(inv, Items.SHULKER_BOX), red = find(inv, Items.RED_SHULKER_BOX);
				boolean back = since > 60 && Harvester.box == null && Harvester.state == Harvester.State.RUN && Harvester.boxes(inv) == 3 && !Harvester.emptyBox(white);
				if (back || since == 20 * 60) {
					check("the part-filled box was filled to the top first (" + Harvester.used(blue) + " of 27 used, " + carrotsIn(blue) + " carrots)",
							Harvester.used(blue) == 27 && carrotsIn(blue) == 27 * 64);
					check("only then the empty one, and just one box is left part-filled (" + Harvester.used(white) + " used, " + Harvester.partBoxes(inv) + " part-filled)",
							Harvester.used(white) > 0 && Harvester.partBoxes(inv) == 1);
					check("the box with your diamond never touched (" + Harvester.used(red) + " used, " + (red.isEmpty() ? "gone" : "here") + ")",
							!red.isEmpty() && Harvester.used(red) == 1 && !Harvester.harvestBox(red));
					check("counted as filled to the top (" + Harvester.boxesFilled + ")", Harvester.boxesFilled >= 1);
					check("all three boxes back, none left out (" + Harvester.boxes(inv) + ", " + server(client, s -> shulkersOut(s.getOverworld())) + " placed)",
							Harvester.boxes(inv) == 3 && server(client, s -> shulkersOut(s.getOverworld())) == 0);
					command(client, "clear @p minecraft:cobblestone");
					command(client, "clear @p minecraft:shulker_box");
					command(client, "clear @p minecraft:blue_shulker_box");
					command(client, "clear @p minecraft:red_shulker_box");
					next("loader");
				}
			}
			// Shulker loader: empty boxes come out of your chest, are filled to the top and full ones go back in. Nothing loose.
			case "loader" -> {
				if (since == 1) {
					// /hh chest twice keeps it on the list (it used to take it off).
					net.minecraft.util.math.Vec3d top = net.minecraft.util.math.Vec3d.ofCenter(CHEST).add(0, 0.5, 0);
					client.crosshairTarget = new net.minecraft.util.hit.BlockHitResult(top, net.minecraft.util.math.Direction.UP, CHEST, false);
					Chests.markLooked(client);
					client.crosshairTarget = new net.minecraft.util.hit.BlockHitResult(top, net.minecraft.util.math.Direction.UP, CHEST, false);
					Chests.markLooked(client);
					check("/hh chest twice keeps the chest (" + Chests.mine(client) + ", farm " + Harvester.farm.chests + ")",
							Chests.mine(client).contains(CHEST) && Harvester.farm.chests.contains(CHEST));
					server(client, s -> {
						if (s.getOverworld().getBlockEntity(CHEST) instanceof ChestBlockEntity c) {
							for (int i = 0; i < c.size(); i++) c.setStack(i, ItemStack.EMPTY);
							for (int i = 0; i < 4; i++) c.setStack(i, new ItemStack(Items.SHULKER_BOX));
							c.markDirty();
						}
						return 0;
					});
					// As if you'd opened the chest and put the boxes in yourself (that's what tells it to look again).
					Harvester.chestUsed(client, CHEST);
					command(client, "clear @p minecraft:wheat");
					command(client, "give @p minecraft:carrot 1856");
				}
				if (since == 10) {
					fillRest(client, inv, 2);
					mark = Harvester.deposited;
				}
				if (since > 20 && since % 20 == 0) log("loader: " + Harvester.status.getString() + " · " + Harvester.state + " · box " + Harvester.box + " · stored " + Harvester.boxesStored);
				boolean back = since > 100 && Harvester.box == null && Harvester.state == Harvester.State.RUN && Harvester.boxesStored >= 1 && count(inv, Items.CARROT) <= 128;
				if (back || since == 20 * 90) {
					int[] c = server(client, s -> chestBoxes(s.getOverworld()), null);
					check("full boxes put in your chest (" + c[0] + " full of carrots in the chest, " + Harvester.boxesStored + " put in, carrots on you " + count(inv, Items.CARROT) + ")",
							c[0] >= 1 && Harvester.boxesStored >= 1);
					check("nothing loose in the chest, only boxes (" + c[1] + " loose items)", c[1] == 0);
					check("no full box left on you, at most one part-filled per crop (" + Harvester.storable(inv) + ", " + Harvester.partBoxes(inv) + " part-filled)",
							Harvester.storable(inv) < 0 && Harvester.partBoxes(inv) <= 2);
					check("back to farming (" + Harvester.state + ")", Harvester.state == Harvester.State.RUN);
					command(client, "clear @p minecraft:cobblestone");
					command(client, "clear @p minecraft:shulker_box");
					next("spare");
				}
			}
			// Spare: your chest first; the box you carry stays empty while the chest has room.
			case "spare" -> {
				if (since == 1) {
					HhConfig.INSTANCE.boxes = HhConfig.Boxes.SPARE;
					command(client, "item replace entity @p inventory.21 with minecraft:shulker_box");
					command(client, "give @p minecraft:carrot 640");
				}
				if (since == 10) {
					fillRest(client, inv, 0);
					mark = Harvester.deposited;
				}
				if (since == 20 * 25) {
					check("spare: stored loose in the chest, the box on you still empty (" + (Harvester.deposited - mark) + " stored, box "
							+ (Harvester.emptyBox(find(inv, Items.SHULKER_BOX)) ? "empty" : "used") + ")",
							Harvester.deposited - mark > 300 && Harvester.emptyBox(find(inv, Items.SHULKER_BOX)));
					HhConfig.INSTANCE.boxes = HhConfig.Boxes.AUTO;
					command(client, "clear @p minecraft:cobblestone");
					command(client, "clear @p minecraft:shulker_box");
					next("drain");
				}
			}
			// Your chest drains into storage through a hopper: auto sends the harvest straight in, never takes anything
			// out, uses boxes only while it's backed up (part-filled first) and tries it again once it's drained.
			case "drain" -> {
				BlockPos hopper = CHEST.down(), sink = CHEST.down(2);
				if (since == 1) {
					server(client, s -> {
						ServerWorld w = s.getOverworld();
						floor = w.getBlockState(hopper);
						below = w.getBlockState(sink);
						if (w.getBlockEntity(CHEST) instanceof ChestBlockEntity c) for (int i = 0; i < c.size(); i++) c.setStack(i, ItemStack.EMPTY);
						w.setBlockState(sink, Blocks.CHEST.getDefaultState(), 3);
						w.setBlockState(hopper, Blocks.HOPPER.getDefaultState().with(net.minecraft.block.HopperBlock.FACING, net.minecraft.util.math.Direction.DOWN), 3);
						return 0;
					});
					StringBuilder c = new StringBuilder();
					for (int i = 0; i < 5; i++) c.append(i == 0 ? "" : ",").append("{slot:").append(i).append(",item:{id:\"minecraft:carrot\",count:64}}");
					command(client, "item replace entity @p inventory.20 with minecraft:blue_shulker_box[minecraft:container=[" + c + "]]");
					command(client, "item replace entity @p inventory.21 with minecraft:shulker_box");
					command(client, "give @p minecraft:carrot 1920");
				}
				if (since == 10) {
					fillRest(client, inv, 0);
					mark = Harvester.deposited;
				}
				if (since > 20 && since % 40 == 0) log("drain: " + Harvester.status.getString() + " · " + Harvester.state + " · deposited " + Harvester.deposited);
				ItemStack blue = find(inv, Items.BLUE_SHULKER_BOX), white = find(inv, Items.SHULKER_BOX);
				if (since == 20 * 25) {
					int[] a = server(client, s -> chestBoxes(s.getOverworld(), CHEST), null), b = server(client, s -> chestBoxes(s.getOverworld(), sink), null);
					check("sees the hopper under your chest, with boxes on you it's a loader (" + Chests.drained(client.world, CHEST) + ", " + Harvester.mode() + ")",
							Chests.drained(client.world, CHEST) && Harvester.mode() == HhConfig.Boxes.LOADER);
					check("the part-filled box was filled to the top and sent down the drain (" + (a[0] + b[0]) + " full boxes in the chest or drained on, "
							+ (a[2] + b[2]) + " loose carrots, " + Harvester.partBoxes(inv) + " part-filled on you)",
							a[0] + b[0] >= 1 && a[2] + b[2] == 0 && Harvester.partBoxes(inv) <= 1 && blue.isEmpty());
					command(client, "clear @p minecraft:shulker_box");
					command(client, "clear @p minecraft:blue_shulker_box");
					// Backed up: full of carrots that are on their way to storage.
					server(client, s -> {
						if (s.getOverworld().getBlockEntity(CHEST) instanceof ChestBlockEntity c) for (int i = 0; i < c.size(); i++) c.setStack(i, new ItemStack(Items.CARROT, 64));
						if (s.getOverworld().getBlockEntity(sink) instanceof ChestBlockEntity c) for (int i = 0; i < c.size(); i++) c.setStack(i, ItemStack.EMPTY);
						return 0;
					});
					command(client, "clear @p minecraft:cobblestone");
					command(client, "give @p minecraft:carrot 1280");
				}
				if (since == 20 * 25 + 10) fillRest(client, inv, 0);
				if (since == 20 * 55) {
					int total = server(client, s -> chestCount(s.getOverworld(), CHEST) + chestCount(s.getOverworld(), sink));
					check("no boxes: stores loose (" + Harvester.mode() + ")", Harvester.mode() == HhConfig.Boxes.SPARE);
					check("nothing taken out of the drained chest (" + total + " in it and drained on, at least " + 27 * 64 + ")", total >= 27 * 64);
					// It drains: emptied, and it's used again without a restart.
					server(client, s -> {
						if (s.getOverworld().getBlockEntity(CHEST) instanceof ChestBlockEntity c) for (int i = 0; i < c.size(); i++) c.setStack(i, ItemStack.EMPTY);
						return 0;
					});
					command(client, "clear @p minecraft:cobblestone");
					command(client, "give @p minecraft:carrot 640");
				}
				if (since == 20 * 55 + 10) {
					fillRest(client, inv, 0);
					mark = Harvester.deposited;
				}
				if (since == 20 * 85) {
					check("drained chest tried again once it emptied (" + (Harvester.deposited - mark) + " stored)", Harvester.deposited - mark > 300);
					command(client, "clear @p minecraft:cobblestone");
					command(client, "clear @p minecraft:shulker_box");
					command(client, "clear @p minecraft:blue_shulker_box");
					server(client, s -> {
						ServerWorld w = s.getOverworld();
						if (w.getBlockEntity(sink) instanceof ChestBlockEntity c) for (int i = 0; i < c.size(); i++) c.setStack(i, ItemStack.EMPTY);
						w.setBlockState(hopper, floor, 3);
						w.setBlockState(sink, below, 3);
						return 0;
					});
					Chests.toggle(client, CHEST);
					next("hand");
				}
			}
			// No usable tool: the good hoe is gone, the worn one is kept aside. It goes on by hand.
			case "hand" -> {
				if (since == 1) command(client, "clear @p minecraft:diamond_hoe");
				if (since == 10) {
					check("no usable Fortune tool now (" + Harvester.bestTool(inv) + "): " + Harvester.toolNote(inv),
							Harvester.bestTool(inv) < 0 && Harvester.toolNote(inv).contains("put aside"));
					mark = Harvester.harvested;
					ripen(client, 6);
				}
				int notDone = since > 10 && since % 20 == 0 ? server(client, s -> (int) ripened.stream().filter(c -> !s.getOverworld().getBlockState(c).isOf(Blocks.CARROTS)
						|| s.getOverworld().getBlockState(c).get(CropBlock.AGE) == 7).count()) : -1;
				if (notDone == 0 || since == 20 * 40) {
					int ripe = server(client, s -> (int) ripened.stream().filter(c -> !s.getOverworld().getBlockState(c).isOf(Blocks.CARROTS)
							|| s.getOverworld().getBlockState(c).get(CropBlock.AGE) == 7).count());
					check("harvested by hand and replanted (" + (Harvester.harvested - mark) + " harvested, last with " + Harvester.toolName + ", " + ripe + " of the 6 not done after " + since / 20 + " s)",
							Harvester.harvested - mark >= 6 && Harvester.lastFortune == 0 && ripe == 0);
					check("worn netherite hoe still never used (" + inv.getStack(5).getDamage() + ")", inv.getStack(5).isOf(Items.NETHERITE_HOE) && inv.getStack(5).getDamage() == 2021);
					next("anytool");
				}
			}
			// No hoe, but a Fortune pickaxe in the off hand: any Fortune tool will do.
			case "anytool" -> {
				if (since == 1) command(client, "item replace entity @p weapon.offhand with minecraft:diamond_pickaxe[minecraft:enchantments={\"minecraft:fortune\":2}]");
				if (since == 10) {
					int best = Harvester.bestTool(inv);
					// It may already have it in hand (swapped from the off hand into the hotbar for a crop in reach).
					check("with no hoe it takes the Fortune pickaxe, even from the off hand (slot " + best + ")",
							best >= 0 && inv.getStack(best).isOf(Items.DIAMOND_PICKAXE) && Harvester.fortune(inv.getStack(best)) == 2);
					mark = Harvester.harvested;
					serverBreaks = serverFortune = 0;
					ripen(client, 6);
				}
				int notDone = since > 10 && since % 20 == 0 ? server(client, s -> (int) ripened.stream().filter(c -> !s.getOverworld().getBlockState(c).isOf(Blocks.CARROTS)
						|| s.getOverworld().getBlockState(c).get(CropBlock.AGE) == 7).count()) : -1;
				if (notDone == 0 || since == 20 * 40) {
					check("harvested with the Fortune pickaxe (" + (Harvester.harvested - mark) + " harvested, last with " + Harvester.toolName + ", Fortune " + Harvester.lastFortune
							+ "; the server had Fortune for " + serverFortune + " of " + serverBreaks + ")",
							Harvester.harvested - mark >= 6 && Harvester.lastFortune == 2 && serverBreaks >= 6 && serverFortune == serverBreaks && notDone == 0);
					// Same Fortune level: the hoe is preferred.
					command(client, "item replace entity @p container.27 with minecraft:iron_hoe[minecraft:enchantments={\"minecraft:fortune\":2}]");
					next("hoefirst");
				}
			}
			case "hoefirst" -> {
				if (since == 10) {
					int best = Harvester.bestTool(inv);
					check("a hoe wins over a pickaxe with the same Fortune (slot " + best + ": " + (best < 0 ? "none" : inv.getStack(best).getName().getString()) + ")",
							best >= 0 && inv.getStack(best).isOf(Items.IRON_HOE));
					next("pause");
				}
			}
			case "pause" -> {
				if (since == 1) Harvester.togglePause(client);
				if (since == 5) {
					mark = Harvester.harvested;
					ripen(client, 4);
				}
				if (since == 100) {
					check("paused: nothing harvested, keys up (" + (Harvester.harvested - mark) + ")", Harvester.paused && Harvester.harvested == mark && !client.options.forwardKey.isPressed());
					Harvester.startOrResume(client);
				}
				if (since == 400) {
					check("carries on after Numpad 4 (" + (Harvester.harvested - mark) + ")", !Harvester.paused && Harvester.harvested - mark >= 4);
					screenshot(client, "3_hud");
					Harvester.stop(client, "Stopped.");
					check("stopped", !Harvester.running() && !client.options.forwardKey.isPressed());
					finish(client);
				}
			}
			default -> {}
		}
	}

	private static void ripen(MinecraftClient client, int n) {
		server(client, s -> {
			List<BlockPos> shuffled = new ArrayList<>(carrotCells);
			java.util.Collections.shuffle(shuffled, RNG);
			ripened.clear();
			for (int i = 0; i < n; i++) {
				BlockPos c = shuffled.get(i);
				ripened.add(c);
				s.getOverworld().setBlockState(c, Blocks.CARROTS.getDefaultState().with(CropBlock.AGE, 7), 3);
			}
			return 0;
		});
	}

	private static void build(MinecraftClient client) {
		command(client, "gamerule doDaylightCycle false");
		command(client, "gamerule advance_time false");
		command(client, "time set 6000");
		command(client, "gamerule doWeatherCycle false");
		command(client, "gamerule advance_weather false");
		command(client, "weather clear");
		server(client, s -> {
			ServerWorld w = s.getOverworld();
			for (int x = -R - 1; x <= R + 1; x++) {
				for (int z = -R - 1; z <= R + 1; z++) {
					BlockPos soil = new BlockPos(x, Y - 1, z);
					if (Math.abs(x) == R + 1 || Math.abs(z) == R + 1) {
						for (int y = 0; y < 3; y++) w.setBlockState(new BlockPos(x, Y + y, z), Blocks.STONE_BRICKS.getDefaultState(), Block.NOTIFY_LISTENERS);
						continue;
					}
					if (x == 0 && z == 0) {
						w.setBlockState(soil, Blocks.WATER.getDefaultState(), 3);
						continue;
					}
					w.setBlockState(soil, Blocks.FARMLAND.getDefaultState().with(FarmlandBlock.MOISTURE, 7), Block.NOTIFY_LISTENERS);
					if (emptyPatch(x, z)) continue;
					int h = Math.floorMod(x * 73 + z * 31 + x * z, 10);
					Block crop = wheatRow(z) ? Blocks.WHEAT : Blocks.CARROTS;
					int age = h < 3 ? 7 : h % 7;
					w.setBlockState(soil.up(), crop.getDefaultState().with(CropBlock.AGE, age), Block.NOTIFY_LISTENERS);
				}
			}
			// Stone in a corner for you to put your chest on, and a chest in another corner that you didn't place.
			w.setBlockState(CHEST, Blocks.AIR.getDefaultState(), 3);
			w.setBlockState(CHEST.down(), Blocks.STONE.getDefaultState(), 3);
			w.setBlockState(OTHER, Blocks.AIR.getDefaultState(), 3);
			w.setBlockState(OTHER.down(), Blocks.STONE.getDefaultState(), 3);
			w.setBlockState(OTHER, Blocks.CHEST.getDefaultState(), 3);
			// Torches on the wall, and someone else's farmland just outside it.
			for (int x = 20; x <= 22; x++) for (int z = -1; z <= 1; z++) {
				w.setBlockState(new BlockPos(x, Y - 1, z), Blocks.FARMLAND.getDefaultState(), 3);
				w.setBlockState(new BlockPos(x, Y, z), Blocks.CARROTS.getDefaultState().with(CropBlock.AGE, 7), 3);
			}
			return 0;
		});
	}

	// ---------------------------------------------------------------- server-side checks

	private static boolean inside(BlockPos c) {
		return Math.abs(c.getX()) <= R && Math.abs(c.getZ()) <= R && !(c.getX() == 0 && c.getZ() == 0) && !(c.getX() == R && c.getZ() == R) && !(c.getX() == -R && c.getZ() == R);
	}

	private static int ripe(ServerWorld w) {
		int n = 0;
		for (int x = -R; x <= R; x++) for (int z = -R; z <= R; z++) {
			BlockPos c = new BlockPos(x, Y, z);
			if (inside(c) && Farm.mature(w.getBlockState(c))) n++;
		}
		return n;
	}

	/** Farmland inside with nothing growing on it. */
	private static int bare(ServerWorld w) {
		int n = 0;
		for (int x = -R; x <= R; x++) for (int z = -R; z <= R; z++) {
			BlockPos c = new BlockPos(x, Y, z);
			if (inside(c) && w.getBlockState(c).isAir()) n++;
		}
		return n;
	}

	private static int farmland(ServerWorld w) {
		int n = 0;
		for (int x = -R; x <= R; x++) for (int z = -R; z <= R; z++) {
			BlockPos c = new BlockPos(x, Y - 1, z);
			if (inside(c.up()) && w.getBlockState(c).isOf(Blocks.FARMLAND)) n++;
		}
		return n;
	}

	/** {wheat cells that aren't wheat, other cells that aren't carrots}. */
	private static int[] kinds(ServerWorld w) {
		int[] r = new int[2];
		for (int x = -R; x <= R; x++) for (int z = -R; z <= R; z++) {
			BlockPos c = new BlockPos(x, Y, z);
			if (!inside(c)) continue;
			BlockState s = w.getBlockState(c);
			if (wheatRow(z) && !s.isOf(Blocks.WHEAT)) r[0]++;
			if (!wheatRow(z) && !s.isOf(Blocks.CARROTS)) r[1]++;
		}
		return r;
	}

	private static int drops(ServerWorld w) {
		return w.getEntitiesByClass(ItemEntity.class, new Box(-R, Y - 2, -R, R + 1, Y + 3, R + 1), e -> true).stream().mapToInt(e -> e.getStack().getCount()).sum();
	}

	private static int chestCount(ServerWorld w) {
		return chestCount(w, CHEST);
	}

	private static int chestCount(ServerWorld w, BlockPos at) {
		if (!(w.getBlockEntity(at) instanceof ChestBlockEntity c)) return -1;
		int n = 0;
		for (int i = 0; i < c.size(); i++) n += c.getStack(i).getCount();
		return n;
	}

	/** What was under the chest before the drain test put a hopper and a chest there. */
	private static BlockState floor, below;

	/** Empty slots filled with cobblestone, leaving {@code leave} free. */
	private static void fillRest(MinecraftClient client, PlayerInventory inv, int leave) {
		int left = leave;
		for (int i = PlayerInventory.MAIN_SIZE - 1; i >= 0; i--) {
			if (!inv.getStack(i).isEmpty()) continue;
			if (left-- > 0) continue;
			command(client, "item replace entity @p " + (i < 9 ? "hotbar." + i : "inventory." + (i - 9)) + " with minecraft:cobblestone 64");
		}
	}

	private static ItemStack find(PlayerInventory inv, net.minecraft.item.Item item) {
		for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) if (inv.getStack(i).isOf(item)) return inv.getStack(i);
		return ItemStack.EMPTY;
	}

	private static int carrotsIn(ItemStack box) {
		var c = box.get(net.minecraft.component.DataComponentTypes.CONTAINER);
		return c == null ? 0 : c.streamNonEmpty().filter(s -> s.isOf(Items.CARROT)).mapToInt(ItemStack::getCount).sum();
	}

	/** What the filled boxes on you hold, sorted: "carrot,wheat" when each holds one crop. "mixed" if one holds two. */
	private static String boxKinds(PlayerInventory inv) {
		java.util.TreeSet<String> kinds = new java.util.TreeSet<>();
		for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) {
			ItemStack s = inv.getStack(i);
			if (!Harvester.isBox(s) || Harvester.emptyBox(s)) continue;
			var set = s.get(net.minecraft.component.DataComponentTypes.CONTAINER).streamNonEmpty().map(x -> net.minecraft.registry.Registries.ITEM.getId(x.getItem()).getPath()).distinct().toList();
			if (set.size() != 1) return "mixed";
			kinds.add(set.getFirst());
		}
		return String.join(",", kinds);
	}

	/** {boxes in the chest full of carrots, loose items in the chest, loose carrots}. */
	private static int[] chestBoxes(ServerWorld w) {
		return chestBoxes(w, CHEST);
	}

	private static int[] chestBoxes(ServerWorld w, BlockPos at) {
		int[] r = new int[3];
		if (!(w.getBlockEntity(at) instanceof ChestBlockEntity c)) return r;
		for (int i = 0; i < c.size(); i++) {
			ItemStack s = c.getStack(i);
			if (s.isEmpty()) continue;
			if (Harvester.isBox(s)) {
				if (carrotsIn(s) == 27 * 64) r[0]++;
			} else {
				r[1] += s.getCount();
				if (s.isOf(Items.CARROT)) r[2] += s.getCount();
			}
		}
		return r;
	}

	private static int filledBoxes(PlayerInventory inv) {
		int n = 0;
		for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) if (Harvester.isBox(inv.getStack(i)) && !Harvester.emptyBox(inv.getStack(i))) n++;
		return n;
	}

	private static int shulkersOut(ServerWorld w) {
		int n = 0;
		for (BlockPos q : BlockPos.iterate(-R - 2, Y - 2, -R - 2, R + 2, Y + 4, R + 2)) if (w.getBlockState(q).getBlock() instanceof net.minecraft.block.ShulkerBoxBlock) n++;
		return n;
	}

	private static int count(PlayerInventory inv, net.minecraft.item.Item item) {
		int n = 0;
		for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) {
			ItemStack s = inv.getStack(i);
			if (s.isOf(item)) n += s.getCount();
		}
		return n;
	}

	/** Crop breaks the server saw, and how many of those were with a Fortune tool in the main hand. */
	private static volatile int serverBreaks, serverFortune;
	private static boolean watching;

	private static void watchBreaks() {
		if (watching) return;
		watching = true;
		net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, be) -> {
			if (!Farm.isCrop(state.getBlock())) return;
			serverBreaks++;
			if (Harvester.fortune(player.getMainHandStack()) > 0) serverFortune++;
		});
	}

	// ---------------------------------------------------------------- plumbing

	private static int server(MinecraftClient client, Function<MinecraftServer, Integer> job) {
		MinecraftServer s = client.getServer();
		try {
			return s.submit(() -> job.apply(s)).get();
		} catch (InterruptedException | ExecutionException e) {
			log("server job failed: " + e);
			return Integer.MIN_VALUE;
		}
	}

	private static int[] server(MinecraftClient client, Function<MinecraftServer, int[]> job, Void unused) {
		MinecraftServer s = client.getServer();
		try {
			return s.submit(() -> job.apply(s)).get();
		} catch (InterruptedException | ExecutionException e) {
			log("server job failed: " + e);
			return new int[8];
		}
	}

	private static void command(MinecraftClient client, String command) {
		server(client, s -> {
			s.getCommandManager().parseAndExecute(s.getCommandSource(), command);
			return 0;
		});
	}

	private static void next(String name) {
		phase = name;
		phaseStart = tick;
		log("phase " + name);
	}

	private static void fail(MinecraftClient client, String why) {
		check(why, false);
		finish(client);
	}

	private static void finish(MinecraftClient client) {
		log("RESULT " + passed + " passed, " + failed + " failed");
		next("done");
		client.scheduleStop();
	}

	private static void screenshot(MinecraftClient client, String name) {
		client.getToastManager().clear();
		ScreenshotRecorder.saveScreenshot(client.runDirectory, "hh_" + name + ".png", client.getFramebuffer(), 1, message -> {});
		log("Screenshot " + name);
	}

	private static void check(String what, boolean ok) {
		if (ok) passed++;
		else failed++;
		log((ok ? "PASS " : "FAIL ") + what);
	}

	private static void log(String message) {
		HarvestHand.LOGGER.info("[SELFTEST] {}", message);
	}
}

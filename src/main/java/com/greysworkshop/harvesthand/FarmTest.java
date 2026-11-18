package com.greysworkshop.harvesthand;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CropBlock;
import net.minecraft.block.FarmlandBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.resource.DataConfiguration;
import net.minecraft.resource.featuretoggle.FeatureFlags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Difficulty;
import net.minecraft.world.GameMode;
import net.minecraft.world.gen.GeneratorOptions;
import net.minecraft.world.gen.WorldPresets;
import net.minecraft.world.level.LevelInfo;
import net.minecraft.world.rule.GameRules;
import org.lwjgl.glfw.GLFW;

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
 * Development-only, {@code ./gradlew runFarmTest}: the user's own kind of farm. Nine 9 × 9 farms side by side, 27 × 27,
 * each with its water source in the middle, walled. It starts with nothing growing, no carrots on you, only carrots
 * lying on the ground, one of the nine still grass and dirt, and a plain iron hoe. It has to pick the carrots up and
 * plant every plot, till the grass farm and plant that too; harvest what's ripe right in front of you even hurt and
 * at 3 hearts; keep up as it grows; and answer Numpad 4, 5 and 6.
 */
final class FarmTest {
	private static final String WORLD = "harvesthand-farmtest";
	private static final int R = 13, Y = -60;
	/** The plots: everything inside the wall but the nine water sources. */
	private static final int PLOTS = 27 * 27 - 9;
	private static boolean started;
	private static int tick = -1, phaseStart, passed, failed, mark, countMark;
	private static long waitMark;
	private static String phase = "setup";
	private static final List<BlockPos> front = new ArrayList<>();

	private FarmTest() {}

	static void start() {
		log("Farm test armed");
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
		ClientTickEvents.END_CLIENT_TICK.register(FarmTest::tick);
	}

	private static void openWorld(MinecraftClient client) {
		client.options.pauseOnLostFocus = false;
		deleteTree(client.runDirectory.toPath().resolve("saves").resolve(WORLD));
		try {
			HhConfig.useFile(Files.createTempDirectory("harvesthand").resolve("harvesthand.json"));
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		// Easy, not peaceful: health doesn't come straight back, so it really works hurt.
		LevelInfo info = new LevelInfo(WORLD, GameMode.SURVIVAL, false, Difficulty.EASY, true,
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

	private static boolean water(int x, int z) {
		return Math.floorMod(x, 9) == 0 && Math.floorMod(z, 9) == 0;
	}

	/** The farm that's still grass and dirt: x -13..-5, z 5..13. */
	private static boolean grassFarm(int x, int z) {
		return x <= -5 && z >= 5;
	}

	private static void tick(MinecraftClient client) {
		if (client.player == null || client.world == null || client.getServer() == null) return;
		if (client.currentScreen instanceof GameMenuScreen && !phase.equals("screens")) {
			client.setScreen(null);
			log("game menu opened by itself: closed");
			return;
		}
		tick++;
		int since = tick - phaseStart;
		switch (phase) {
			case "setup" -> {
				if (tick == 20) build(client);
				if (tick == 140) {
					command(client, "tp @p 0.5 " + Y + " 2.5");
					command(client, "clear @p");
					command(client, "item replace entity @p hotbar.3 with minecraft:iron_hoe");
					command(client, "item replace entity @p hotbar.8 with minecraft:bread 16");
					// No carrots on you: twelve stacks lying about the farm.
					server(client, s -> {
						ServerWorld w = s.getOverworld();
						int[][] piles = {{-10, -10}, {-2, -11}, {10, -10}, {-11, -1}, {2, 3}, {11, 2}, {-6, 11}, {3, 11}, {11, 11}, {6, -6}, {-6, -4}, {4, 6}};
						for (int[] q : piles) {
							ItemEntity e = new ItemEntity(w, q[0] + 0.5, Y + 0.2, q[1] + 0.5, new ItemStack(Items.CARROT, 64), 0, 0, 0);
							w.spawnEntity(e);
						}
						return 0;
					});
				}
				if (tick == 200) {
					Harvester.start(client, HhConfig.INSTANCE.radius);
					Farm f = Harvester.farm;
					check("found all nine farms: " + (f == null ? 0 : f.cells.size()) + " plots (expected " + PLOTS + "), " + (f == null ? 0 : f.bareGround.size()) + " of them grass and dirt (expected 80)",
							f != null && f.cells.size() == PLOTS && f.bareGround.size() == 80);
					check("nothing growing and no carrots on you: it plants the carrots lying about (" + (f == null ? null : f.main) + ")", f != null && f.main == Blocks.CARROTS);
					check("running", Harvester.running());
					next("plant");
				}
			}
			// Pick the carrots up, plant every plot, till the grass farm and plant it too.
			case "plant" -> {
				if (since % 200 == 0) {
					int[] r = server(client, s -> state(s.getOverworld()), null);
					log("plant " + since / 20 + " s: " + r[0] + " planted, " + r[1] + " farmland of " + PLOTS + " · " + Harvester.status.getString() + " · " + Harvester.summary());
					if (r[0] == PLOTS && r[1] == PLOTS || since >= 20 * 300) {
						check("every plot planted, the grass farm tilled too (" + r[0] + " planted, " + r[1] + " farmland of " + PLOTS + ", in " + since / 20 + " s; "
								+ Harvester.tilled + " tilled)", r[0] == PLOTS && r[1] == PLOTS);
						screenshot(client, "farm_1_planted");
						next("front");
					}
				}
			}
			// Hurt, down to 3.5 hearts and hungry: ripe crops right in front of you are still harvested at once.
			case "front" -> {
				if (since == 1) {
					command(client, "effect give @p minecraft:hunger 3 255 true");
					command(client, "damage @p 13 minecraft:starve");
				}
				if (since == 60) {
					log("health " + client.player.getHealth() + ", food " + client.player.getHungerManager().getFoodLevel() + ", " + Harvester.status.getString());
					BlockPos me = client.player.getBlockPos();
					front.clear();
					for (BlockPos c : Harvester.farm.cells) {
						if (c.getSquaredDistance(me.up()) <= 2.5 * 2.5 && front.size() < 8) front.add(c);
					}
					List<BlockPos> cells = List.copyOf(front);
					server(client, s -> {
						for (BlockPos c : cells) s.getOverworld().setBlockState(c, Blocks.CARROTS.getDefaultState().with(CropBlock.AGE, 7), 3);
						return 0;
					});
					mark = tick;
				}
				if (since > 60) {
					List<BlockPos> cells = List.copyOf(front);
					int left = server(client, s -> (int) cells.stream().filter(c -> {
						BlockState st = s.getOverworld().getBlockState(c);
						return !st.isOf(Blocks.CARROTS) || st.get(CropBlock.AGE) == 7;
					}).count());
					if (left == 0 || since > 60 + 100) {
						check(String.format("ripe crops right in front of you harvested and replanted at once, hurt and hungry (%d of %d done in %.2f s, health %.1f)",
								cells.size() - left, cells.size(), (tick - mark) / 20.0, client.player.getHealth()), left == 0 && !cells.isEmpty() && tick - mark <= 30
								&& client.player.getHealth() <= 8);
						next("grow");
						// A farm that's been going a while: every age, a few ripe already.
						server(client, s -> {
							java.util.Random rng = new java.util.Random(3);
							for (int x = -R; x <= R; x++) for (int z = -R; z <= R; z++) {
								BlockPos c = new BlockPos(x, Y, z);
								if (s.getOverworld().getBlockState(c).isOf(Blocks.CARROTS)) s.getOverworld().setBlockState(c, Blocks.CARROTS.getDefaultState().with(CropBlock.AGE, rng.nextInt(7)), 3);
							}
							return 0;
						});
						command(client, "gamerule random_tick_speed 30");
						command(client, "gamerule randomTickSpeed 30");
						mark = Harvester.harvested;
						waitMark = Harvester.waitTotal;
						countMark = Harvester.waitCount;
					}
				}
			}
			// Ten times the growth for two minutes: it keeps every plot growing.
			case "grow" -> {
				if (since % 400 == 0 && since > 0) log("grow " + since / 20 + " s: ripe " + Harvester.ripe(client.world) + " · " + Harvester.summary());
				if (since == 20 * 120) {
					command(client, "gamerule random_tick_speed 3");
					command(client, "gamerule randomTickSpeed 3");
					int[] r = server(client, s -> state(s.getOverworld()), null);
					int ripe = Harvester.ripe(client.world);
					double avg = (Harvester.waitTotal - waitMark) / (double) Math.max(1, Harvester.waitCount - countMark) / 20;
					check(String.format("kept every plot growing at 10x growth (%d harvested, %.1f s average wait, %d planted, %d farmland of %d, %d ripe now)",
							Harvester.harvested - mark, avg, r[0], r[1], PLOTS, ripe), Harvester.harvested - mark > 150 && avg <= 15 && r[0] >= PLOTS - 3 && r[1] >= PLOTS - 1 && ripe <= 40);
					next("screens");
				}
			}
			// Tabbed out (the pause menu, which doesn't stop a server) and with the inventory open: it keeps walking,
			// harvesting, planting and eating.
			case "screens" -> {
				if (since == 1) {
					// Open to LAN: like a real server, the pause menu no longer stops the world.
					client.getServer().openToLan(GameMode.SURVIVAL, false, 25599);
					client.options.pauseOnLostFocus = true;
				}
				if (since == 20) {
					client.onWindowFocusChanged(false);
					log("tabbed out: screen " + (client.currentScreen == null ? "none" : client.currentScreen.getClass().getSimpleName()) + ", paused " + client.isPaused());
					ripenAway(client);
					mark = tick;
				}
				if (mark > 0 && since > 20 && (since % 10 == 0 && left(client) == 0 || since == 20 + 20 * 25)) {
					check(String.format("tabbed out (pause menu open): crops a walk away harvested and replanted (%d left after %.1f s, screen %s)", left(client), (tick - mark) / 20.0,
							client.currentScreen == null ? "none" : client.currentScreen.getClass().getSimpleName()),
							left(client) == 0 && client.currentScreen instanceof GameMenuScreen && !client.isPaused());
					client.onWindowFocusChanged(true);
					client.setScreen(new net.minecraft.client.gui.screen.ingame.InventoryScreen(client.player));
					// Hungry: 3 drumsticks (no effect draining it while it eats).
					server(client, srv -> {
						srv.getPlayerManager().getPlayerList().forEach(pl -> pl.getHungerManager().setFoodLevel(6));
						return 0;
					});
					ripenAway(client);
					mark = -tick;
				}
				if (mark < 0) {
					int start = -mark;
					if ((tick - start) % 10 == 0 && left(client) == 0 && client.player.getHungerManager().getFoodLevel() > 14 || tick - start == 20 * 30) {
						check(String.format("inventory open: crops a walk away harvested and replanted, and it ate (%d left after %.1f s, food %d, screen %s)", left(client),
								(tick - start) / 20.0, client.player.getHungerManager().getFoodLevel(), client.currentScreen == null ? "none" : client.currentScreen.getClass().getSimpleName()),
								left(client) == 0 && client.currentScreen instanceof net.minecraft.client.gui.screen.ingame.InventoryScreen && client.player.getHungerManager().getFoodLevel() > 14);
						client.options.pauseOnLostFocus = false;
						client.setScreen(null);
						mark = 0;
						next("keys");
					}
				}
			}
			// Numpad 6 opens the settings; Numpad 5 pauses; Numpad 4 carries on.
			case "keys" -> {
				if (since == 2) press(GLFW.GLFW_KEY_KP_6);
				if (since == 10) {
					check("Numpad 6 opens the settings (" + (client.currentScreen == null ? "nothing" : client.currentScreen.getClass().getSimpleName()) + ")",
							client.currentScreen instanceof HhScreen);
				}
				if (since == 16) screenshot(client, "farm_2_settings");
				if (since == 19) client.setScreen(null);
				if (since == 20) press(GLFW.GLFW_KEY_KP_5);
				if (since == 26) check("Numpad 5 pauses", Harvester.paused);
				if (since == 30) press(GLFW.GLFW_KEY_KP_4);
				if (since == 36) {
					check("Numpad 4 carries on", Harvester.running() && !Harvester.paused);
					Harvester.stop(client, "Stopped.");
					finish(client);
				}
			}
			default -> {
			}
		}
	}

	/** Ripens ten crops 6 to 12 blocks from you, so it has to walk. */
	private static void ripenAway(MinecraftClient client) {
		BlockPos me = client.player.getBlockPos();
		front.clear();
		for (BlockPos c : Harvester.farm.cells) {
			double d = Math.sqrt(c.getSquaredDistance(me.up()));
			if (d >= 6 && d <= 12 && Math.floorMod(c.getX() * 5 + c.getZ() * 3, 7) == 0 && front.size() < 10) front.add(c);
		}
		List<BlockPos> cells = List.copyOf(front);
		server(client, s -> {
			for (BlockPos c : cells) s.getOverworld().setBlockState(c, Blocks.CARROTS.getDefaultState().with(CropBlock.AGE, 7), 3);
			return 0;
		});
		log("ripened " + cells.size() + " crops a walk away");
	}

	/** Of those, how many aren't harvested and replanted yet. */
	private static int left(MinecraftClient client) {
		List<BlockPos> cells = List.copyOf(front);
		return server(client, s -> (int) cells.stream().filter(c -> {
			BlockState st = s.getOverworld().getBlockState(c);
			return !st.isOf(Blocks.CARROTS) || st.get(CropBlock.AGE) == 7;
		}).count());
	}

	private static void press(int key) {
		InputUtil.Key k = InputUtil.Type.KEYSYM.createFromCode(key);
		KeyBinding.setKeyPressed(k, true);
		KeyBinding.onKeyPressed(k);
		KeyBinding.setKeyPressed(k, false);
	}

	private static void build(MinecraftClient client) {
		command(client, "gamerule doDaylightCycle false");
		command(client, "gamerule advance_time false");
		command(client, "time set 6000");
		command(client, "gamerule doWeatherCycle false");
		command(client, "gamerule advance_weather false");
		command(client, "weather clear");
		command(client, "gamerule doMobSpawning false");
		command(client, "gamerule spawn_mobs false");
		server(client, s -> {
			ServerWorld w = s.getOverworld();
			for (int x = -R - 1; x <= R + 1; x++) {
				for (int z = -R - 1; z <= R + 1; z++) {
					BlockPos soil = new BlockPos(x, Y - 1, z);
					if (Math.abs(x) == R + 1 || Math.abs(z) == R + 1) {
						for (int y = 0; y < 3; y++) w.setBlockState(new BlockPos(x, Y + y, z), Blocks.STONE_BRICKS.getDefaultState(), Block.NOTIFY_LISTENERS);
						continue;
					}
					w.setBlockState(soil.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS);
					if (water(x, z)) {
						w.setBlockState(soil, Blocks.WATER.getDefaultState(), 3);
					} else if (grassFarm(x, z)) {
						// Never farmed: grass with tufts on it, and some plain dirt.
						boolean dirt = Math.floorMod(x * 7 + z * 3, 4) == 0;
						w.setBlockState(soil, dirt ? Blocks.DIRT.getDefaultState() : Blocks.GRASS_BLOCK.getDefaultState(), Block.NOTIFY_LISTENERS);
						if (!dirt && Math.floorMod(x + z, 2) == 0) w.setBlockState(soil.up(), Blocks.SHORT_GRASS.getDefaultState(), Block.NOTIFY_LISTENERS);
					} else {
						w.setBlockState(soil, Blocks.FARMLAND.getDefaultState().with(FarmlandBlock.MOISTURE, 7), Block.NOTIFY_LISTENERS);
					}
				}
			}
			return 0;
		});
	}

	/** {plots with a crop, plots on farmland}. */
	private static int[] state(ServerWorld w) {
		int[] r = new int[2];
		for (int x = -R; x <= R; x++) for (int z = -R; z <= R; z++) {
			if (water(x, z)) continue;
			BlockPos c = new BlockPos(x, Y, z);
			if (Farm.isCrop(w.getBlockState(c).getBlock())) r[0]++;
			if (w.getBlockState(c.down()).isOf(Blocks.FARMLAND)) r[1]++;
		}
		return r;
	}

	private static void command(MinecraftClient client, String command) {
		server(client, s -> {
			s.getCommandManager().parseAndExecute(s.getCommandSource(), command);
			return 0;
		});
	}

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

	private static void next(String name) {
		phase = name;
		phaseStart = tick;
		log("phase " + name);
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
		HarvestHand.LOGGER.info("[FARMTEST] {}", message);
	}
}

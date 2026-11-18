package com.greysworkshop.harvesthand;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.DisconnectedScreen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.util.ScreenshotRecorder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Development-only, {@code scripts/kicktest.sh}: on a real (local) server, kicked while farming, then the server
 * stopping and coming back. Each time it must reconnect by itself and carry on harvesting the same farm. Quitting
 * yourself must not bring it back.
 */
final class KickTest {
	private static final int Y = -60;
	private static int tick, phaseStart, passed, failed, mark, kickedAt, screens;
	private static boolean sawButton;
	private static String phase = "join";

	private KickTest() {}

	static void start() {
		log("Kick test armed");
		try {
			HhConfig.useFile(Files.createTempDirectory("harvesthand").resolve("harvesthand.json"));
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		HhConfig.INSTANCE.reconnectSeconds = 3;
		ClientTickEvents.END_CLIENT_TICK.register(KickTest::tick);
	}

	private static int since() {
		return tick - phaseStart;
	}

	private static void tick(MinecraftClient client) {
		tick++;
		client.options.pauseOnLostFocus = false;
		boolean in = client.player != null && client.world != null;
		if (client.currentScreen instanceof DisconnectedScreen d) {
			for (ClickableWidget b : Screens.getButtons(d)) if (b.getMessage().getString().startsWith("Harvest Hand: reconnecting")) sawButton = true;
		}
		switch (phase) {
			case "join" -> {
				if (!in) {
					if (since() > 20 * 120) fail(client, "never joined the server");
					return;
				}
				if (since() < 100) return;
				cmd(client, "gamerule randomTickSpeed 0");
				cmd(client, "gamerule doMobSpawning false");
				cmd(client, "difficulty peaceful");
				cmd(client, "time set day");
				cmd(client, "fill -6 " + (Y - 1) + " -6 6 " + (Y + 2) + " 6 air");
				cmd(client, "fill -6 " + (Y - 1) + " -6 6 " + (Y - 1) + " 6 grass_block");
				cmd(client, "fill -5 " + Y + " -5 5 " + Y + " 5 oak_fence");
				cmd(client, "fill -4 " + Y + " -4 4 " + Y + " 4 air");
				cmd(client, "fill -4 " + (Y - 1) + " -4 4 " + (Y - 1) + " 4 farmland[moisture=7]");
				cmd(client, "setblock 0 " + (Y - 1) + " 0 water");
				cmd(client, "fill -4 " + Y + " -4 4 " + Y + " 4 carrots[age=7] replace air");
				cmd(client, "setblock 0 " + Y + " 0 air");
				cmd(client, "clear @s");
				cmd(client, "give @s diamond_hoe[enchantments={\"minecraft:fortune\":3}]");
				cmd(client, "give @s carrot 32");
				cmd(client, "tp @s 2.5 " + Y + " 2.5");
				next("start");
			}
			case "start" -> {
				if (since() == 60) {
					Harvester.start(client, 16);
					check("started on the server (" + (Harvester.farm == null ? 0 : Harvester.farm.cells.size()) + " plots)", Harvester.running() && Harvester.farm.cells.size() == 80);
					next("farm");
				}
			}
			case "farm" -> {
				if (Harvester.harvested >= 20) {
					log("harvested " + Harvester.harvested + " before the kick");
					screenshot(client, "kick_0_farming");
					sawButton = false;
					cmd(client, "kick HhKick Testing the auto-reconnect");
					kickedAt = tick;
					next("kicked");
				} else if (since() > 20 * 60) fail(client, "didn't harvest before the kick: " + Harvester.summary());
			}
			case "kicked" -> {
				if (client.currentScreen instanceof DisconnectedScreen && screens == 0) {
					screens++;
					screenshot(client, "kick_1_disconnected");
				}
				if (in && Harvester.running() && Resume.resumed == 1) {
					check("kicked: the disconnect screen counts down to reconnect", sawButton);
					check("kicked: reconnected and carrying on with the farm by itself (" + (tick - kickedAt) / 20 + " s, " + Harvester.farm.cells.size() + " plots)",
							Harvester.farm.cells.size() == 80);
					next("after-kick");
				} else if (since() > 20 * 90) fail(client, "didn't come back after the kick (screen " + name(client) + ", resume " + HhConfig.INSTANCE.resume + ")");
			}
			case "after-kick" -> {
				if (since() == 40) {
					mark = Harvester.harvested;
					cmd(client, "fill -4 " + Y + " -4 4 " + Y + " 4 carrots[age=7] replace carrots");
				}
				if (since() > 40 && Harvester.harvested - mark >= 30) {
					check("after the kick: harvesting again (" + (Harvester.harvested - mark) + " more)", true);
					screenshot(client, "kick_2_back");
					sawButton = false;
					screens = 0;
					// The server going down and coming back (the script starts it again).
					cmd(client, "stop");
					kickedAt = tick;
					next("restart");
				} else if (since() > 20 * 60) fail(client, "not harvesting after the kick: " + Harvester.summary());
			}
			case "restart" -> {
				if (in && Harvester.running() && Resume.resumed == 2) {
					check("server restart: kept trying until it was back, then carried on (" + (tick - kickedAt) / 20 + " s, " + Resume.lastAttempts + " tries)",
							sawButton && Harvester.farm.cells.size() == 80);
					next("after-restart");
				} else if (since() > 20 * 240) fail(client, "didn't come back after the restart (screen " + name(client) + ")");
			}
			case "after-restart" -> {
				if (since() == 60) {
					mark = Harvester.harvested;
					cmd(client, "fill -4 " + Y + " -4 4 " + Y + " 4 carrots[age=7] replace carrots");
				}
				if (since() > 60 && Harvester.harvested - mark >= 30) {
					check("after the restart: harvesting again (" + (Harvester.harvested - mark) + " more)", true);
					// Quitting yourself: it stops and must not start again on its own.
					client.disconnect(new MultiplayerScreen(new TitleScreen()), false);
					next("quit");
				} else if (since() > 20 * 60) fail(client, "not harvesting after the restart: " + Harvester.summary());
			}
			case "quit" -> {
				if (since() == 60) {
					check("you quit: no reconnect countdown (" + name(client) + ")", !(client.currentScreen instanceof DisconnectedScreen) && Resume.countdown < 0);
					ServerInfo info = new ServerInfo("Kick test", "localhost:25601", ServerInfo.ServerType.OTHER);
					ConnectScreen.connect(new MultiplayerScreen(new TitleScreen()), client, ServerAddress.parse(info.address), info, false, null);
				}
				if (since() > 60 && in && since() > 60 + 20 * 15) {
					check("you quit and came back yourself: it waits for you to start it (running " + Harvester.running() + ")", !Harvester.running() && Resume.resumed == 2);
					finish(client);
				} else if (since() > 20 * 120) fail(client, "never rejoined after quitting");
			}
			case "end" -> {
				if (since() == 40) client.scheduleStop();
			}
			default -> {}
		}
	}

	private static String name(MinecraftClient client) {
		return client.currentScreen == null ? "none" : client.currentScreen.getClass().getSimpleName();
	}

	private static void cmd(MinecraftClient client, String command) {
		if (client.player != null) client.player.networkHandler.sendChatCommand(command);
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
		// Tell the script not to start the server again, and stop it.
		try {
			Files.writeString(Path.of(System.getProperty("harvesthand.kicktest.done", "kicktest.done")), "done");
		} catch (IOException e) {
			log("couldn't write the done file: " + e);
		}
		cmd(client, "stop");
		next("end");
	}

	private static void screenshot(MinecraftClient client, String name) {
		ScreenshotRecorder.saveScreenshot(client.runDirectory, "hh_" + name + ".png", client.getFramebuffer(), 1, message -> {});
	}

	private static void check(String what, boolean ok) {
		if (ok) passed++;
		else failed++;
		log((ok ? "PASS " : "FAIL ") + what);
	}

	private static void log(String message) {
		HarvestHand.LOGGER.info("[KICKTEST] {}", message);
	}
}

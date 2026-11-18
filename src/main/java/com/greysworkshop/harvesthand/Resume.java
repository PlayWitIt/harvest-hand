package com.greysworkshop.harvesthand;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.DisconnectedScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;

/**
 * Keeps it going while you're AFK: kicked (or the connection drops) while it's running, it reconnects by itself and
 * carries on with the same farm once you're back in. Quitting yourself doesn't count: that never brings it back.
 */
final class Resume {
	/** What to carry on with, kept in the config so it survives the game closing too. */
	static final class Pending {
		String server, dim;
		int x, y, z, radius;
		boolean paused;
		/** A shulker box it had out on the wall, to fetch first. */
		int[] box;
		/** Set once the disconnect screen shows: kicked or dropped, not quit. */
		boolean kicked;
	}

	static final String DISCONNECTED = "Stopped: disconnected.";
	/** The server you're on, for reconnecting. */
	static ServerInfo server;
	static int attempts, lastAttempts;
	/** Ticks left before reconnecting, on {@link #waitingOn}. -1: not counting. */
	static int countdown = -1;
	private static Screen waitingOn;
	private static ButtonWidget button;
	/** Ticks since joining while a resume is pending. -1: not waiting. */
	static int joined = -1;
	/** For the test: resumes so far. */
	static int resumed;

	private Resume() {}

	static void init() {
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> client.execute(() -> {
			ServerInfo s = client.getCurrentServerEntry();
			if (s != null && !client.isInSingleplayer()) server = s;
			Pending p = HhConfig.INSTANCE.resume;
			if (p != null && !p.kicked) {
				// You quit yourself last time: don't start on its own.
				HhConfig.INSTANCE.resume = null;
				HhConfig.save();
			}
			joined = HhConfig.INSTANCE.resume != null ? 0 : -1;
		}));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			// At once if it can: the disconnect screen comes straight after and must find what to carry on with.
			Runnable r = () -> {
				remember(client);
				Harvester.stop(client, DISCONNECTED);
			};
			if (client.isOnThread()) r.run();
			else client.execute(r);
		});
		ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
			if (screen instanceof DisconnectedScreen) onDisconnected(client, screen);
		});
		ClientTickEvents.END_CLIENT_TICK.register(Resume::tick);
	}

	static String serverKey(MinecraftClient client) {
		if (client.isInSingleplayer()) return "singleplayer";
		ServerInfo s = client.getCurrentServerEntry();
		return s != null ? s.address : server != null ? server.address : "?";
	}

	/** Disconnected: note what it was doing, if it was running. */
	private static void remember(MinecraftClient client) {
		HarvestHand.LOGGER.info("Disconnected: running {}, started at {}", Harvester.running(), Harvester.startAt);
		if (!Harvester.running() || Harvester.startAt == null) return;
		Pending p = new Pending();
		p.server = serverKey(client);
		p.dim = Harvester.startDim;
		p.x = Harvester.startAt.getX();
		p.y = Harvester.startAt.getY();
		p.z = Harvester.startAt.getZ();
		p.radius = Harvester.startRadius;
		p.paused = Harvester.paused;
		if (Harvester.box != null) p.box = new int[] {Harvester.box.getX(), Harvester.box.getY(), Harvester.box.getZ()};
		HhConfig.INSTANCE.resume = p;
		HhConfig.save();
	}

	/** Kicked or dropped: count down and reconnect. */
	private static void onDisconnected(MinecraftClient client, Screen screen) {
		Pending p = HhConfig.INSTANCE.resume;
		HarvestHand.LOGGER.info("Disconnect screen: to carry on {}, server {}", p == null ? null : p.server, server == null ? null : server.address);
		if (p == null || server == null || !p.server.equals(server.address)) return;
		if (!p.kicked) {
			p.kicked = true;
			HhConfig.save();
		}
		if (!HhConfig.INSTANCE.reconnect) return;
		if (waitingOn != screen) {
			// 10 s, then 20, 40, ... up to 5 minutes while the server stays away.
			int seconds = (int) Math.min(300, (long) HhConfig.INSTANCE.reconnectSeconds << Math.min(attempts, 5));
			countdown = seconds * 20;
			waitingOn = screen;
		}
		button = ButtonWidget.builder(label(), b -> cancel()).dimensions(screen.width / 2 - 120, 6, 240, 20).build();
		Screens.getButtons(screen).add(button);
	}

	private static Text label() {
		return countdown >= 0 ? Text.literal("Harvest Hand: reconnecting in " + (countdown + 19) / 20 + " s · Cancel").formatted(Formatting.GREEN)
				: Text.literal("Harvest Hand: won't reconnect").formatted(Formatting.GRAY);
	}

	static void cancel() {
		countdown = -1;
		waitingOn = null;
		HhConfig.INSTANCE.resume = null;
		HhConfig.save();
		if (button != null) {
			button.setMessage(label());
			button.active = false;
		}
	}

	private static void tick(MinecraftClient client) {
		// The disconnect screen came up before the disconnect was noted (or without it, when the connection just
		// dropped): pick it up now.
		if (countdown < 0 && client.currentScreen instanceof DisconnectedScreen d && waitingOn != d) {
			if (HhConfig.INSTANCE.resume == null && Harvester.running()) {
				remember(client);
				Harvester.stop(client, DISCONNECTED);
			}
			if (HhConfig.INSTANCE.resume != null && !HhConfig.INSTANCE.resume.kicked) onDisconnected(client, d);
		}
		if (countdown >= 0) {
			if (client.currentScreen != waitingOn) {
				// Something else took over (you clicked Back, another mod reconnected): leave it be.
				countdown = -1;
				waitingOn = null;
			} else if (--countdown <= 0) {
				countdown = -1;
				waitingOn = null;
				attempts++;
				HarvestHand.LOGGER.info("Reconnecting to {} (try {})", server.address, attempts);
				ConnectScreen.connect(new MultiplayerScreen(new TitleScreen()), client, ServerAddress.parse(server.address), server, false, null);
				return;
			} else if (button != null && countdown % 20 == 0) {
				button.setMessage(label());
			}
		}
		if (joined >= 0) carryOn(client);
	}

	/** Back in: once the farm's around you again, carry on with it. */
	private static void carryOn(MinecraftClient client) {
		Pending p = HhConfig.INSTANCE.resume;
		if (p == null || Harvester.running()) {
			joined = -1;
			return;
		}
		if (client.player == null || client.world == null) return;
		joined++;
		String why = null;
		if (!p.server.equals(serverKey(client))) why = "a different server";
		else if (!p.dim.equals(client.world.getRegistryKey().getValue().toString())) why = "you're in another dimension";
		BlockPos at = new BlockPos(p.x, p.y, p.z);
		boolean near = client.player.getBlockPos().getSquaredDistance(at) <= (double) (p.radius + 32) * (p.radius + 32);
		boolean loaded = client.world.getChunkManager().isChunkLoaded(at.getX() >> 4, at.getZ() >> 4);
		if (why == null && joined < 60) return; // A few seconds for the world to come in.
		if (why == null && !near && joined > 600) why = "you're not at the farm any more (" + client.player.getBlockPos().toShortString() + ")";
		if (why == null && (!near || !loaded)) return;
		if (why != null) {
			HhConfig.INSTANCE.resume = null;
			HhConfig.save();
			joined = -1;
			Harvester.chat(client.player, Text.literal("Not carrying on after the disconnect: " + why + ".").formatted(Formatting.YELLOW));
			return;
		}
		joined = -1;
		HhConfig.INSTANCE.resume = null;
		HhConfig.save();
		Harvester.chat(client.player, Text.literal("Back after the disconnect: carrying on with the farm.").formatted(Formatting.GREEN));
		Harvester.start(client, p.radius, at);
		if (!Harvester.running()) return;
		resumed++;
		lastAttempts = attempts;
		attempts = 0;
		if (p.box != null) {
			Harvester.recoverBox(client.world, new BlockPos(p.box[0], p.box[1], p.box[2]));
		}
		if (p.paused) Harvester.paused = true;
	}
}

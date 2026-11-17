package com.greysworkshop.harvesthand;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

/** Harvest Hand: harvests every crop in your farm the moment it's fully grown, and replants it straight away. */
public final class HarvestHand implements ClientModInitializer {
	public static final String MOD_ID = "harvesthand";
	static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
	static KeyBinding startKey, pauseKey, stopKey;

	@Override
	public void onInitializeClient() {
		HhConfig.load();
		KeyBinding.Category category = KeyBinding.Category.create(Identifier.of(MOD_ID, "main"));
		startKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.harvesthand.start", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_KP_4, category));
		pauseKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.harvesthand.pause", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_KP_5, category));
		stopKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.harvesthand.stop", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_KP_6, category));
		ClientTickEvents.END_CLIENT_TICK.register(HarvestHand::tick);
		HudElementRegistry.addLast(Identifier.of(MOD_ID, "hud"), Hud::render);
		ClientCommandRegistrationCallback.EVENT.register((d, access) -> {
			for (String name : new String[] {"harvesthand", "hh"}) {
				d.register(literal(name)
						.executes(c -> {
							MinecraftClient client = MinecraftClient.getInstance();
							if (client.player != null) Harvester.chat(client.player, Text.literal(Harvester.running() ? Hud.lines(client).stream().map(Text::getString).reduce((a, b) -> a + " · " + b).orElse("")
									: "/hh start [radius] while standing in your farm (Numpad 4), /hh pause (Numpad 5), /hh stop (Numpad 6), /hh perTick <1-4>, /hh radius <8-128>.").formatted(Formatting.GRAY));
							return 1;
						})
						.then(literal("start").executes(c -> run(client -> Harvester.startOrResume(client)))
								.then(argument("radius", IntegerArgumentType.integer(4, 128)).executes(c -> {
									int r = IntegerArgumentType.getInteger(c, "radius");
									return run(client -> Harvester.start(client, r));
								})))
						.then(literal("pause").executes(c -> run(Harvester::togglePause)))
						.then(literal("stop").executes(c -> run(client -> Harvester.stop(client, "Stopped."))))
						.then(literal("radius").then(argument("blocks", IntegerArgumentType.integer(4, 128)).executes(c -> {
							HhConfig.INSTANCE.radius = IntegerArgumentType.getInteger(c, "blocks");
							HhConfig.save();
							return say("Farms are looked for up to " + HhConfig.INSTANCE.radius + " blocks out.");
						})))
						.then(literal("perTick").then(argument("crops", IntegerArgumentType.integer(1, 4)).executes(c -> {
							HhConfig.INSTANCE.perTick = IntegerArgumentType.getInteger(c, "crops");
							HhConfig.save();
							return say("Up to " + HhConfig.INSTANCE.perTick + " crops a tick (" + HhConfig.INSTANCE.perTick * 20 + " a second).");
						}))));
			}
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(() -> Harvester.stop(client, "Stopped: disconnected.")));
		if (Boolean.getBoolean("harvesthand.selftest")) {
			try {
				Class.forName("com.greysworkshop.harvesthand.SelfTest").getDeclaredMethod("start").invoke(null);
			} catch (ReflectiveOperationException e) {
				LOGGER.error("Self-test missing", e);
			}
		}
	}

	private static int run(java.util.function.Consumer<MinecraftClient> job) {
		MinecraftClient client = MinecraftClient.getInstance();
		client.send(() -> job.accept(client));
		return 1;
	}

	private static int say(String s) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player != null) Harvester.chat(client.player, Text.literal(s).formatted(Formatting.GRAY));
		return 1;
	}

	private static void tick(MinecraftClient client) {
		while (startKey.wasPressed()) if (client.currentScreen == null) Harvester.startOrResume(client);
		while (pauseKey.wasPressed()) if (client.currentScreen == null) Harvester.togglePause(client);
		while (stopKey.wasPressed()) if (client.currentScreen == null) Harvester.stop(client, "Stopped.");
		Harvester.tick(client);
	}
}

package com.greysworkshop.harvesthand;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;

/** The settings (Numpad 6): start or stop, and every option, saved as you change them. */
final class HhScreen extends Screen {
	private static final int W = 320, GOLD = 0xFFFFC040, GRAY = 0xFFA0A0B0;

	HhScreen() {
		super(Text.literal("Harvest Hand"));
	}

	@Override
	protected void init() {
		// 854 × 480 at GUI scale 2 is only 240 tall: rows of 22.
		int step = height < 300 ? 22 : 24;
		int x = (width - W) / 2, y = 28, half = (W - 4) / 2;
		HhConfig cf = HhConfig.INSTANCE;
		boolean running = Harvester.running();
		addDrawableChild(ButtonWidget.builder(Text.literal(running ? "Stop" : "Start here"), b -> {
			MinecraftClient c = MinecraftClient.getInstance();
			close();
			if (Harvester.running()) Harvester.stop(c, "Stopped.");
			else Harvester.start(c, HhConfig.INSTANCE.radius);
		}).dimensions(x, y, half, 20).tooltip(Tooltip.of(Text.literal("Start finds the farm around where you stand (Numpad 4 does the same)."))).build());
		ButtonWidget pause = ButtonWidget.builder(Text.literal(Harvester.paused ? "Carry on" : "Pause"), b -> {
			Harvester.togglePause(MinecraftClient.getInstance());
			close();
		}).dimensions(x + half + 4, y, half, 20).build();
		pause.active = running;
		addDrawableChild(pause);
		y += step;
		addDrawableChild(slider(x, y, half, 8, 128, cf.radius, v -> cf.radius = v, v -> "Farm size: " + v + " blocks out",
				"How far from you it looks for farmland when it starts."));
		addDrawableChild(slider(x + half + 4, y, half, 1, 4, cf.perTick, v -> cf.perTick = v, v -> "Speed: " + v + " crop" + (v == 1 ? "" : "s") + " a tick",
				"Crops harvested and replanted per tick, at most. If the server undoes harvests it slows down by itself."));
		y += step;
		addDrawableChild(toggle(x, y, half, "Till dirt and grass", () -> cf.till, v -> cf.till = v,
				"Bare dirt and grass by the water (and trampled farmland) is tilled with a hoe and planted, so as much ground as possible grows."));
		addDrawableChild(toggle(x + half + 4, y, half, "Pick up drops", () -> cf.pickUp, v -> cf.pickUp = v,
				"Walk over what's lying on the ground. Seeds it's run out of are fetched first."));
		y += step;
		addDrawableChild(toggle(x, y, half, "Store when full", () -> cf.deposit, v -> cf.deposit = v,
				"In a chest you placed (or marked with /hh chest), else an empty shulker box it puts on the wall and picks back up."));
		addDrawableChild(toggle(x + half + 4, y, half, "Sprint", () -> cf.sprint, v -> cf.sprint = v, "Sprint on longer walks."));
		y += step;
		addDrawableChild(toggle(x, y, half, "Eat when hungry", () -> cf.autoEat, v -> cf.autoEat = v, "Food first, then spare carrots or potatoes."));
		addDrawableChild(toggle(x + half + 4, y, half, "Tool guard", () -> cf.protectTools, v -> cf.protectTools = v,
				"Put a tool aside with " + cf.toolReserve + " uses left, so it never breaks."));
		y += step;
		addDrawableChild(toggle(x, y, half, "Wait in the middle", () -> cf.waitInMiddle, v -> cf.waitInMiddle = v,
				"With nothing to do, wait where every crop is closest."));
		addDrawableChild(toggle(x + half + 4, y, half, "Status on screen", () -> cf.hud, v -> cf.hud = v, "The lines at the top left."));
		y += step;
		addDrawableChild(toggle(x, y, W, "Reconnect and carry on when kicked", () -> cf.reconnect, v -> cf.reconnect = v,
				"Kicked or dropped while it's running: it reconnects after " + cf.reconnectSeconds + " s (longer each try, up to 5 minutes) and carries on with the same farm. Quitting yourself never brings it back."));
		y += step;
		addDrawableChild(ButtonWidget.builder(boxLabel(), b -> {
			cf.boxes = cf.boxMode().next();
			cf.boxesChosen = true;
			b.setMessage(boxLabel());
			b.setTooltip(Tooltip.of(Text.literal(boxTip())));
			HhConfig.save();
		}).dimensions(x, y, half, 20).tooltip(Tooltip.of(Text.literal(boxTip()))).build());
		addDrawableChild(toggle(x + half + 4, y, half, "One crop per box", () -> cf.oneCropPerBox, v -> cf.oneCropPerBox = v,
				"Each shulker box holds one crop: a box of carrots, a box of wheat. Off: any of the harvest goes in any box (fewer boxes part-filled)."));
		y += step + 4;
		addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> close()).dimensions(x + W / 4, y, W / 2, 20).build());
	}

	private static Text boxLabel() {
		return Text.literal("Shulker boxes: " + HhConfig.INSTANCE.boxMode().word);
	}

	private static String boxTip() {
		return switch (HhConfig.INSTANCE.boxMode()) {
			case AUTO -> "Auto (best for most): a chest of yours that hoppers drain is your storage, so the harvest goes straight in and nothing is taken out of it; boxes only catch what it can't take, part-filled first. With no drained chest it works as a loader. Click to change.";
			case OFF -> "Off: never uses shulker boxes, only your chests. Click to change.";
			case SPARE -> "Spare (a few boxes): your chests first, then the boxes you carry once the chests are full. Part-filled boxes are topped up before an empty one is used. Click to change.";
			case LOADER -> "Loader (plenty of boxes): does a shulker loader's job. Every box is filled to the top, part-filled first; full boxes go into your chests and empty ones come out of them. Loose in a chest only once no box is left. Click to change.";
		};
	}

	@Override
	public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
		super.render(ctx, mouseX, mouseY, delta);
		ctx.drawCenteredTextWithShadow(textRenderer, title, width / 2, 8, GOLD);
		ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(Harvester.running() ? Harvester.status.getString() + " · " + Harvester.harvested + " harvested, " + (Harvester.planted + Harvester.replanted) + " planted"
				: "Numpad 4 starts, Numpad 5 pauses, Numpad 6 opens this."), width / 2, 17, GRAY);
	}

	@Override
	public void removed() {
		HhConfig.save();
	}

	@Override
	public boolean shouldPause() {
		return false;
	}

	private static ButtonWidget toggle(int x, int y, int w, String name, BooleanSupplier get, Consumer<Boolean> set, String tip) {
		return ButtonWidget.builder(label(name, get), b -> {
			set.accept(!get.getAsBoolean());
			b.setMessage(label(name, get));
			HhConfig.save();
		}).dimensions(x, y, w, 20).tooltip(Tooltip.of(Text.literal(tip))).build();
	}

	private static Text label(String name, BooleanSupplier get) {
		return Text.literal(name + ": " + (get.getAsBoolean() ? "on" : "off"));
	}

	private static SliderWidget slider(int x, int y, int w, int min, int max, int value, IntConsumer set, IntFunction<String> fmt, String tip) {
		SliderWidget s = new SliderWidget(x, y, w, 20, Text.empty(), (value - min) / (double) (max - min)) {
			{
				updateMessage();
			}

			private int current() {
				return min + (int) Math.round(MathHelper.clamp(this.value, 0, 1) * (max - min));
			}

			@Override
			protected void updateMessage() {
				setMessage(Text.literal(fmt.apply(current())));
			}

			@Override
			protected void applyValue() {
				set.accept(current());
			}
		};
		s.setTooltip(Tooltip.of(Text.literal(tip)));
		return s;
	}
}

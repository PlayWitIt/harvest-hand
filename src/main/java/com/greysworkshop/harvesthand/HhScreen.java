package com.greysworkshop.harvesthand;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;

/**
 * Numpad 6. On top, always: what it's doing right now and the one button that matters (Start here, or Stop and
 * Pause). Below, three tabs: Farming, Storage (where a full inventory goes, said in words, with your chests and
 * boxes counted) and Safety. Fits 854 × 480 at GUI scale 2 (427 × 240). Everything is saved as you change it.
 */
final class HhScreen extends Screen {
	enum Tab {FARMING("Farming"), STORAGE("Storage"), SAFETY("Safety");

		final String label;

		Tab(String label) {
			this.label = label;
		}
	}

	private static final int W = 320, GOLD = 0xFFFFC040, GRAY = 0xFFA0A0B0, WHITE = 0xFFFFFFFF, GREEN = 0xFF60E070, YELLOW = 0xFFFFE060,
			AQUA = 0xFF60D8E8, PANEL = 0xA0000000, EDGE = 0x40FFFFFF;
	/** The tab you were on last: it opens there again. */
	static Tab tab = Tab.FARMING;

	private int x, panelTop, panelBottom, contentTop, infoTop;

	HhScreen() {
		super(Text.literal("Harvest Hand"));
	}

	@Override
	protected void init() {
		HhConfig cf = HhConfig.INSTANCE;
		boolean small = height < 300;
		int row = small ? 22 : 24;
		x = (width - W) / 2;
		int half = (W - 4) / 2;
		int y = small ? 4 : 10;

		// The status card: title, what it's doing, the numbers (drawn in render), then the main buttons.
		panelTop = y;
		y += 34;
		boolean running = Harvester.running();
		if (running) {
			addDrawableChild(ButtonWidget.builder(Text.literal("■ Stop"), b -> {
				close();
				Harvester.stop(MinecraftClient.getInstance(), "Stopped.");
			}).dimensions(x + 4, y, half - 4, 20).tooltip(Tooltip.of(Text.literal("Stops and lets go of everything. Numpad 4 starts again."))).build());
			addDrawableChild(ButtonWidget.builder(Text.literal(Harvester.paused ? "▶ Carry on" : "❚❚ Pause"), b -> {
				Harvester.togglePause(MinecraftClient.getInstance());
				close();
			}).dimensions(x + half + 4, y, half - 4, 20).tooltip(Tooltip.of(Text.literal("Numpad 5 does the same."))).build());
		} else {
			addDrawableChild(ButtonWidget.builder(Text.literal("▶ Start here"), b -> {
				MinecraftClient c = MinecraftClient.getInstance();
				close();
				Harvester.start(c, HhConfig.INSTANCE.radius);
			}).dimensions(x + 4, y, W - 8, 20).tooltip(Tooltip.of(Text.literal("Stand in your farm: it finds every plot up to " + cf.radius
					+ " blocks out and starts. Numpad 4 does the same."))).build());
		}
		y += 24;
		panelBottom = y;
		y += 4;

		// Tabs.
		int tw = (W - 8) / 3;
		for (Tab t : Tab.values()) {
			ButtonWidget b = ButtonWidget.builder(Text.literal(t == tab ? "» " + t.label + " «" : t.label), btn -> {
				tab = t;
				clearAndInit();
			}).dimensions(x + t.ordinal() * (tw + 4), y, tw, 20).build();
			b.active = t != tab;
			addDrawableChild(b);
		}
		y += row + 2;
		contentTop = y;

		switch (tab) {
			case FARMING -> {
				addDrawableChild(slider(x, y, half, 8, 128, cf.radius, v -> cf.radius = v, v -> "Farm size: " + v + " blocks out",
						"How far from you it looks for farmland when it starts."));
				addDrawableChild(slider(x + half + 4, y, half, 1, 4, cf.perTick, v -> cf.perTick = v, v -> "Speed: " + v + " crop" + (v == 1 ? "" : "s") + " a tick",
						"Crops harvested and replanted per tick, at most. If the server undoes harvests it slows down by itself."));
				y += row;
				addDrawableChild(toggle(x, y, half, "Till dirt and grass", () -> cf.till, v -> cf.till = v,
						"Bare dirt and grass by the water (and trampled farmland) is tilled with a hoe and planted, so as much ground as possible grows."));
				addDrawableChild(toggle(x + half + 4, y, half, "Pick up drops", () -> cf.pickUp, v -> cf.pickUp = v,
						"Walk over what's lying on the ground. Seeds it's run out of are fetched first."));
				y += row;
				addDrawableChild(toggle(x, y, half, "Wait in the middle", () -> cf.waitInMiddle, v -> cf.waitInMiddle = v,
						"With nothing to do, wait where every crop is closest."));
				addDrawableChild(toggle(x + half + 4, y, half, "Sprint", () -> cf.sprint, v -> cf.sprint = v, "Sprint on longer walks."));
				y += row;
			}
			case STORAGE -> {
				addDrawableChild(toggle(x, y, half, "Store when full", () -> cf.deposit, v -> cf.deposit = v,
						"A full inventory goes into your chests (and shulker boxes, as set here). Off: it keeps farming and drops stay on the ground."));
				addDrawableChild(ButtonWidget.builder(boxLabel(), b -> {
					cf.boxes = cf.boxMode().next();
					cf.boxesChosen = true;
					HhConfig.save();
					clearAndInit();
				}).dimensions(x + half + 4, y, half, 20).tooltip(Tooltip.of(Text.literal(boxTip()))).build());
				y += row;
				addDrawableChild(toggle(x, y, half, "One crop per box", () -> cf.oneCropPerBox, v -> cf.oneCropPerBox = v,
						"Each shulker box holds one crop: a box of carrots, a box of wheat. Off: any of the harvest goes in any box (fewer boxes part-filled)."));
				addDrawableChild(ButtonWidget.builder(Text.literal("Add the chest I look at"), b -> {
					close();
					Chests.markLooked(MinecraftClient.getInstance());
				}).dimensions(x + half + 4, y, half, 20).tooltip(Tooltip.of(Text.literal("Closes this, then adds the chest, barrel or placed shulker box under your crosshair (/hh chest). /hh chest remove takes one off."))).build());
				y += row;
			}
			case SAFETY -> {
				addDrawableChild(toggle(x, y, half, "Eat when hungry", () -> cf.autoEat, v -> cf.autoEat = v, "Food first, then spare carrots or potatoes."));
				addDrawableChild(toggle(x + half + 4, y, half, "Tool guard", () -> cf.protectTools, v -> cf.protectTools = v,
						"Put a tool aside with " + cf.toolReserve + " uses left, so it never breaks."));
				y += row;
				addDrawableChild(toggle(x, y, half, "Reconnect when kicked", () -> cf.reconnect, v -> cf.reconnect = v,
						"Kicked or dropped while it's running: it reconnects after " + cf.reconnectSeconds + " s (longer each try, up to 5 minutes) and carries on with the same farm. Quitting yourself never brings it back."));
				addDrawableChild(toggle(x + half + 4, y, half, "Status on screen", () -> cf.hud, v -> cf.hud = v, "The lines at the top left while it works."));
				y += row;
				addDrawableChild(slider(x, y, W, 0, 19, cf.minHealth, v -> cf.minHealth = v,
						v -> v == 0 ? "Hold when hurt: never" : "Hold at " + v / 2.0 + " hearts, while something's hitting you",
						"With a mob or player hitting you and health this low, it lets go of the keys so you (or your other mods) can deal with it."));
				y += row;
			}
		}
		infoTop = y + 2;
		addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> close()).dimensions(x + W / 4, height - (small ? 24 : 30), W / 2, 20).build());
	}

	@Override
	public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
		super.renderBackground(ctx, mouseX, mouseY, delta);
		// The status card, behind its buttons.
		ctx.fill(x, panelTop, x + W, panelBottom, PANEL);
		ctx.fill(x, panelTop, x + W, panelTop + 1, EDGE);
		ctx.fill(x, panelBottom - 1, x + W, panelBottom, EDGE);
		ctx.fill(x, panelTop + 1, x + 1, panelBottom - 1, EDGE);
		ctx.fill(x + W - 1, panelTop + 1, x + W, panelBottom - 1, EDGE);
	}

	@Override
	public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
		super.render(ctx, mouseX, mouseY, delta);
		MinecraftClient client = MinecraftClient.getInstance();
		boolean running = Harvester.running();
		String state = !running ? "Not running" : Harvester.paused ? "Paused" : Harvester.status.getString();
		int stateColour = !running ? GRAY : Harvester.paused ? YELLOW : GREEN;
		ctx.drawTextWithShadow(textRenderer, Text.literal("Harvest Hand"), x + 6, panelTop + 5, GOLD);
		String dot = "● " + state;
		ctx.drawTextWithShadow(textRenderer, Text.literal(dot), x + W - 6 - textRenderer.getWidth(dot), panelTop + 5, stateColour);
		ctx.drawTextWithShadow(textRenderer, Text.literal(statsLine(client)), x + 6, panelTop + 19, running ? WHITE : GRAY);

		// What the tab means, in words.
		int y = infoTop;
		int limit = height - (height < 300 ? 28 : 34);
		for (Line l : info(client)) {
			for (OrderedText t : textRenderer.wrapLines(Text.literal(l.text), W - 8)) {
				if (y + 9 > limit) return;
				ctx.drawTextWithShadow(textRenderer, t, x + 4, y, l.colour);
				y += 10;
			}
			y += 2;
		}
	}

	private static String statsLine(MinecraftClient client) {
		if (!Harvester.running() || Harvester.farm == null || client.world == null) {
			return "Numpad 4 starts · 5 pauses · 6 opens this";
		}
		StringBuilder b = new StringBuilder();
		b.append(Harvester.harvested).append(" harvested · ").append(Harvester.ripe(client.world)).append(" ripe of ").append(Harvester.farm.cells.size());
		if (Harvester.deposited > 0) b.append(" · ").append(Harvester.deposited).append(" stored");
		if (Harvester.boxesFilled > 0) b.append(" · ").append(Harvester.boxesFilled).append(" box").append(Harvester.boxesFilled == 1 ? "" : "es").append(" filled");
		return b.toString();
	}

	private record Line(String text, int colour) {}

	private List<Line> info(MinecraftClient client) {
		List<Line> out = new ArrayList<>();
		if (client.player == null) return out;
		PlayerInventory inv = client.player.getInventory();
		switch (tab) {
			case FARMING -> {
				out.add(new Line(Harvester.toolNote(inv), Harvester.bestTool(inv) >= 0 ? GREEN : YELLOW));
				if (Harvester.running() && Harvester.waitCount > 0) {
					out.add(new Line(String.format("A ripe crop waits %.1f s on average before it's harvested.", Harvester.waitTotal / (double) Harvester.waitCount / 20), GRAY));
				}
			}
			case STORAGE -> {
				List<BlockPos> mine = Chests.distinct(client, Chests.mine(client));
				int drained = 0, boxes = 0;
				for (BlockPos p : mine) {
					if (client.world == null || !client.world.isChunkLoaded(p.getX() >> 4, p.getZ() >> 4)) continue;
					if (Chests.drained(client.world, p)) drained++;
					if (Chests.placedBox(client.world, p)) boxes++;
				}
				int empty = Harvester.emptyBoxes(inv), part = Harvester.partBoxes(inv);
				out.add(new Line("Chests: " + (mine.isEmpty() ? "none yet (place one, or look at one and /hh chest)" : mine.size() + (drained > 0 ? ", " + drained + " drained by a hopper" : "")
						+ (boxes > 0 ? ", " + boxes + " placed shulker box" + (boxes == 1 ? "" : "es") : "")) + " · Boxes on you: "
						+ (empty + part == 0 ? "none" : (empty > 0 ? empty + " empty" : "") + (empty > 0 && part > 0 ? ", " : "") + (part > 0 ? part + " part-filled" : "")), AQUA));
				if (!HhConfig.INSTANCE.deposit) out.add(new Line("Storing is off: when your inventory is full it keeps farming and drops stay on the ground.", YELLOW));
				else if (Harvester.running() && Harvester.farm != null) out.add(new Line(Harvester.storeNote(inv), WHITE));
				else out.add(new Line(boxTip().replace(" Click to change.", ""), WHITE));
			}
			case SAFETY -> out.add(new Line("It keeps working tabbed out, in your inventory and when hungry. It only lets go when something is hitting you.", GRAY));
		}
		return out;
	}

	@Override
	public void removed() {
		HhConfig.save();
	}

	@Override
	public boolean shouldPause() {
		return false;
	}

	/** For the farm test: every button and slider on screen and none on top of another. */
	List<String> layoutProblems() {
		List<String> out = new ArrayList<>();
		List<ClickableWidget> ws = new ArrayList<>();
		for (var c : children()) if (c instanceof ClickableWidget w) ws.add(w);
		for (ClickableWidget a : ws) {
			if (a.getX() < 0 || a.getY() < 0 || a.getRight() > width || a.getBottom() > height) out.add(a.getMessage().getString() + " off screen");
			if (a.getY() < panelBottom && a.getY() >= panelTop && a.getBottom() > panelBottom) out.add(a.getMessage().getString() + " over the card edge");
			for (ClickableWidget b : ws) {
				if (a == b) continue;
				if (a.getX() < b.getRight() && b.getX() < a.getRight() && a.getY() < b.getBottom() && b.getY() < a.getBottom()) out.add(a.getMessage().getString() + " over " + b.getMessage().getString());
			}
		}
		return out;
	}

	private static Text boxLabel() {
		return Text.literal("Shulker boxes: " + HhConfig.INSTANCE.boxMode().word);
	}

	private static String boxTip() {
		return switch (HhConfig.INSTANCE.boxMode()) {
			case AUTO -> "Auto (best for most): a shulker loader whenever there are boxes to fill (on you, or in a chest of yours): every box filled to the top, part-filled first, full ones into your chest; a chest drained by hoppers sends them on to your storage and nothing is ever taken out of it. No boxes: the harvest goes in loose. Click to change.";
			case OFF -> "Off: never uses shulker boxes, only your chests. Click to change.";
			case SPARE -> "Spare (a few boxes): your chests first, then the boxes you carry once the chests are full. Part-filled boxes are topped up before an empty one is used. Click to change.";
			case LOADER -> "Loader (plenty of boxes): does a shulker loader's job. Every box is filled to the top, part-filled first; full boxes go into your chests and empty ones come out of them. Loose in a chest only once no box is left. Click to change.";
		};
	}

	private static ButtonWidget toggle(int x, int y, int w, String name, BooleanSupplier get, Consumer<Boolean> set, String tip) {
		return ButtonWidget.builder(label(name, get), b -> {
			set.accept(!get.getAsBoolean());
			b.setMessage(label(name, get));
			HhConfig.save();
		}).dimensions(x, y, w, 20).tooltip(Tooltip.of(Text.literal(tip))).build();
	}

	private static Text label(String name, BooleanSupplier get) {
		return Text.literal(name + ": " + (get.getAsBoolean() ? "§aon" : "§7off"));
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

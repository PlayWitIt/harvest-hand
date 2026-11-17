package com.greysworkshop.harvesthand;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;

/** Two small lines at the top left while it works. */
final class Hud {
	private Hud() {}

	static void render(DrawContext context, RenderTickCounter counter) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (!HhConfig.INSTANCE.hud || client.player == null || client.options.hudHidden || client.getDebugHud().shouldShowDebugHud()) return;
		if (!Harvester.running()) return;
		int y = 4;
		for (Text line : lines(client)) {
			int w = client.textRenderer.getWidth(line);
			context.fill(2, y - 2, 6 + w, y + 9, 0x90000000);
			context.drawTextWithShadow(client.textRenderer, line, 4, y, 0xFFFFFFFF);
			y += 11;
		}
	}

	static List<Text> lines(MinecraftClient client) {
		List<Text> lines = new ArrayList<>();
		lines.add(Text.literal("Harvest Hand · ").formatted(Formatting.GOLD).append(Harvester.status.copy().formatted(Formatting.WHITE)));
		if (Harvester.farm == null || client.world == null) return lines;
		StringBuilder b = new StringBuilder();
		b.append(Harvester.harvested).append(" harvested · ").append(Harvester.ripe(client.world)).append(" ripe of ").append(Harvester.farm.cells.size());
		if (Harvester.waitCount > 0) b.append(" · ").append(String.format("%.1f", Harvester.waitTotal / (double) Harvester.waitCount / 20)).append(" s wait");
		b.append(" · ").append(Harvester.lastFortune > 0 ? "Fortune " + Harvester.lastFortune : Harvester.lastFortune == 0 ? "by hand" : "");
		boolean full = client.player.getInventory().getEmptySlot() < 0;
		lines.add(Text.literal(b + (full ? " · inventory full" : "")).formatted(full ? Formatting.RED : Formatting.GRAY));
		return lines;
	}
}

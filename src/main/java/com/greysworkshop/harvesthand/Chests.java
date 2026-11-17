package com.greysworkshop.harvesthand;

import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.block.BarrelBlock;
import net.minecraft.block.Block;
import net.minecraft.block.ChestBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * The chests Harvest Hand may store the harvest in: only ones you placed yourself, or marked with {@code /hh chest}.
 * It never places a chest, and never touches a chest it just happens to find. Kept per server and dimension in the
 * config.
 */
final class Chests {
	private Chests() {}

	/** Chests being placed by hand right now: confirmed once the block is still there half a second later. */
	private record Pending(BlockPos pos, long due) {}
	private static final List<Pending> pending = new ArrayList<>();
	private static long now;

	static void init() {
		UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
			if (!world.isClient()) return ActionResult.PASS;
			MinecraftClient client = MinecraftClient.getInstance();
			if (player != client.player) return ActionResult.PASS;
			ItemStack held = player.getStackInHand(hand);
			if (held.getItem() instanceof BlockItem bi && container(bi.getBlock())) {
				for (BlockPos c : new BlockPos[] {hit.getBlockPos(), hit.getBlockPos().offset(hit.getSide())}) {
					if (!container(world, c)) pending.add(new Pending(c.toImmutable(), now + 10));
				}
			}
			return ActionResult.PASS;
		});
	}

	static void tick(MinecraftClient client) {
		now++;
		if (pending.isEmpty()) return;
		if (client.world == null) {
			pending.clear();
			return;
		}
		for (Iterator<Pending> it = pending.iterator(); it.hasNext(); ) {
			Pending p = it.next();
			if (now < p.due()) continue;
			it.remove();
			if (container(client.world, p.pos()) && add(client, p.pos())) {
				Harvester.chestAdded(client, p.pos(), "You placed a chest: the harvest will go in it when your inventory is full.");
			}
		}
	}

	static boolean container(Block b) {
		return b instanceof ChestBlock || b instanceof BarrelBlock;
	}

	static boolean container(World w, BlockPos p) {
		return container(w.getBlockState(p).getBlock());
	}

	/** Server (or world) and dimension: chest positions only mean something there. */
	static String where(MinecraftClient client) {
		ServerInfo s = client.getCurrentServerEntry();
		String server = s != null ? s.address : client.getServer() != null ? "sp:" + client.getServer().getSaveProperties().getLevelName() : "?";
		return server + "|" + (client.world == null ? "?" : client.world.getRegistryKey().getValue());
	}

	private static String entry(MinecraftClient client, BlockPos p) {
		return where(client) + "|" + p.getX() + "," + p.getY() + "," + p.getZ();
	}

	/** Your chests in this world. Ones that have gone (broken) are forgotten. */
	static Set<BlockPos> mine(MinecraftClient client) {
		Set<BlockPos> out = new HashSet<>();
		String prefix = where(client) + "|";
		boolean changed = false;
		for (Iterator<String> it = HhConfig.INSTANCE.chests.iterator(); it.hasNext(); ) {
			String e = it.next();
			if (!e.startsWith(prefix)) continue;
			String[] xyz = e.substring(prefix.length()).split(",");
			try {
				BlockPos p = new BlockPos(Integer.parseInt(xyz[0]), Integer.parseInt(xyz[1]), Integer.parseInt(xyz[2]));
				if (client.world != null && client.world.isChunkLoaded(p.getX() >> 4, p.getZ() >> 4) && !container(client.world, p)) {
					it.remove();
					changed = true;
					continue;
				}
				out.add(p);
			} catch (RuntimeException ex) {
				it.remove();
				changed = true;
			}
		}
		if (changed) HhConfig.save();
		return out;
	}

	static boolean add(MinecraftClient client, BlockPos p) {
		String e = entry(client, p);
		if (HhConfig.INSTANCE.chests.contains(e)) return false;
		HhConfig.INSTANCE.chests.add(e);
		HhConfig.save();
		return true;
	}

	static boolean remove(MinecraftClient client, BlockPos p) {
		boolean r = HhConfig.INSTANCE.chests.remove(entry(client, p));
		if (r) HhConfig.save();
		return r;
	}

	/** {@code /hh chest}: mark or unmark the chest you're looking at. */
	static void toggleLooked(MinecraftClient client) {
		if (client.player == null || client.world == null) return;
		if (!(client.crosshairTarget instanceof BlockHitResult hit) || hit.getType() == HitResult.Type.MISS || !container(client.world, hit.getBlockPos())) {
			Harvester.say(client, "Look at a chest or barrel, then /hh chest to let Harvest Hand use it (again to stop).");
			return;
		}
		toggle(client, hit.getBlockPos().toImmutable());
	}

	static void toggle(MinecraftClient client, BlockPos p) {
		if (remove(client, p)) Harvester.chestRemoved(client, p);
		else if (add(client, p)) Harvester.chestAdded(client, p, "Marked this chest: the harvest will go in it when your inventory is full.");
	}
}

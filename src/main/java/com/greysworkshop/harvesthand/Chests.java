package com.greysworkshop.harvesthand;

import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.block.BarrelBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.block.enums.ChestType;
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
 * The chests Harvest Hand may store the harvest in: only ones you placed yourself, or marked with {@code /hh chest}
 * (a chest, barrel or a shulker box you placed). It never places a chest, and never touches one it just happens to
 * find. A double chest counts once. Kept per server and dimension in the config.
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
			// Placing a chest or barrel adds it (a shulker box only when you mark it: it puts its own down to fill).
			if (held.getItem() instanceof BlockItem bi && chestOrBarrel(bi.getBlock())) {
				for (BlockPos c : new BlockPos[] {hit.getBlockPos(), hit.getBlockPos().offset(hit.getSide())}) {
					if (!chestOrBarrel(world.getBlockState(c).getBlock())) pending.add(new Pending(c.toImmutable(), now + 10));
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
			if (chestOrBarrel(client.world.getBlockState(p.pos()).getBlock()) && add(client, p.pos())) {
				Harvester.chestAdded(client, p.pos(), "You placed a chest: the harvest will go in it when your inventory is full.");
			}
		}
	}

	static boolean chestOrBarrel(Block b) {
		return b instanceof ChestBlock || b instanceof BarrelBlock;
	}

	/** Anything it can store in once it's yours: a chest, a barrel or a shulker box on the ground. */
	static boolean container(Block b) {
		return chestOrBarrel(b) || b instanceof ShulkerBoxBlock;
	}

	/** A shulker box on the ground: it takes loose harvest, never other boxes. */
	static boolean placedBox(World w, BlockPos p) {
		return w.getBlockState(p).getBlock() instanceof ShulkerBoxBlock;
	}

	/** The other half of a double chest, or null. */
	static BlockPos otherHalf(World w, BlockPos p) {
		BlockState s = w.getBlockState(p);
		if (!(s.getBlock() instanceof ChestBlock) || s.get(ChestBlock.CHEST_TYPE) == ChestType.SINGLE) return null;
		return p.offset(ChestBlock.getFacing(s)).toImmutable();
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

	/** Adds a container (and the other half of a double chest). False: it was on the list already. */
	static boolean add(MinecraftClient client, BlockPos p) {
		boolean added = false;
		BlockPos other = client.world == null ? null : otherHalf(client.world, p);
		for (BlockPos q : other == null ? new BlockPos[] {p} : new BlockPos[] {p, other}) {
			String e = entry(client, q);
			if (HhConfig.INSTANCE.chests.contains(e)) continue;
			HhConfig.INSTANCE.chests.add(e);
			added = true;
		}
		if (added) HhConfig.save();
		return added;
	}

	static boolean remove(MinecraftClient client, BlockPos p) {
		boolean r = HhConfig.INSTANCE.chests.remove(entry(client, p));
		BlockPos other = client.world == null ? null : otherHalf(client.world, p);
		if (other != null) r |= HhConfig.INSTANCE.chests.remove(entry(client, other));
		if (r) HhConfig.save();
		return r;
	}

	/** The chest, barrel or shulker box you're looking at, or null (and says what to do). */
	private static BlockPos looked(MinecraftClient client, String what) {
		if (client.player == null || client.world == null) return null;
		if (!(client.crosshairTarget instanceof BlockHitResult hit) || hit.getType() == HitResult.Type.MISS || !container(client.world, hit.getBlockPos())) {
			Harvester.say(client, "Look at a chest, barrel or shulker box, then " + what + ".");
			return null;
		}
		return hit.getBlockPos().toImmutable();
	}

	/** {@code /hh chest}: let it use the chest you're looking at. Only ever adds: {@code /hh chest remove} takes one off. */
	static void markLooked(MinecraftClient client) {
		BlockPos p = looked(client, "/hh chest to let Harvest Hand use it");
		if (p == null) return;
		String kind = placedBox(client.world, p) ? "shulker box" : "chest";
		if (add(client, p)) {
			Harvester.chestAdded(client, p, "Added this " + kind + " (" + count(client) + " here now): the harvest goes in when your inventory is full. /hh chests lists them.");
		} else {
			if (Harvester.running() && Harvester.farm != null) Harvester.chestAdded(client, p, "");
			Harvester.say(client, "This " + kind + " is already on the list (" + count(client) + " here). /hh chest remove takes it off.");
		}
	}

	static void removeLooked(MinecraftClient client) {
		BlockPos p = looked(client, "/hh chest remove to stop Harvest Hand using it");
		if (p == null) return;
		if (remove(client, p)) Harvester.chestRemoved(client, p);
		else Harvester.say(client, "That one isn't on the list.");
	}

	/** Containers on the list here, a double chest once. */
	static int count(MinecraftClient client) {
		return distinct(client, mine(client)).size();
	}

	static List<BlockPos> distinct(MinecraftClient client, Set<BlockPos> all) {
		List<BlockPos> out = new ArrayList<>();
		for (BlockPos p : all.stream().sorted(java.util.Comparator.comparingLong(BlockPos::asLong)).toList()) {
			BlockPos o = client.world == null ? null : otherHalf(client.world, p);
			if (o != null && out.contains(o)) continue;
			out.add(p);
		}
		return out;
	}

	/** {@code /hh chests}: the list for this world. */
	static void list(MinecraftClient client) {
		List<BlockPos> all = distinct(client, mine(client));
		if (all.isEmpty()) {
			Harvester.say(client, "No chests yet. Place one (it's added by itself), or look at a chest, barrel or placed shulker box and /hh chest.");
			return;
		}
		StringBuilder b = new StringBuilder(all.size() + " to store in: ");
		for (int i = 0; i < all.size(); i++) {
			BlockPos p = all.get(i);
			if (i > 0) b.append(", ");
			b.append(p.toShortString());
			if (client.world != null && client.world.isChunkLoaded(p.getX() >> 4, p.getZ() >> 4)) {
				if (placedBox(client.world, p)) b.append(" (shulker box)");
				else if (otherHalf(client.world, p) != null) b.append(" (double)");
			}
		}
		Harvester.say(client, b + ". /hh chest remove while looking at one takes it off; /hh chests clear forgets them all here.");
	}

	static void clear(MinecraftClient client) {
		String prefix = where(client) + "|";
		int before = HhConfig.INSTANCE.chests.size();
		HhConfig.INSTANCE.chests.removeIf(e -> e.startsWith(prefix));
		HhConfig.save();
		if (Harvester.farm != null) Harvester.farm.chests.clear();
		Harvester.say(client, "Forgot " + (before - HhConfig.INSTANCE.chests.size()) + " here. Place a chest or /hh chest to add one.");
	}

	/** For the self-test: mark or unmark, as {@code /hh chest} used to. */
	static void toggle(MinecraftClient client, BlockPos p) {
		if (remove(client, p)) Harvester.chestRemoved(client, p);
		else if (add(client, p)) Harvester.chestAdded(client, p, "Marked.");
	}
}

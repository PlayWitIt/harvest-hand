package com.greysworkshop.harvesthand;

import net.minecraft.block.BarrelBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.CropBlock;
import net.minecraft.block.NetherWartBlock;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The farm: every farmland (and soul sand) cell you can walk to from where you stand, inside the walls, plus the chests
 * and barrels along its edge. Found once, at the start, by walking the ground from the player outwards: walls and
 * fences stop it, water is crossed.
 */
final class Farm {
	/** The crops it knows how to harvest and replant. */
	static final Block[] CROPS = {Blocks.CARROTS, Blocks.POTATOES, Blocks.WHEAT, Blocks.BEETROOTS, Blocks.NETHER_WART};

	/** Cells a crop grows in (the air above the farmland). */
	final List<BlockPos> cells = new ArrayList<>();
	/** What last grew in each cell, so the same goes back in. */
	final Map<BlockPos, Block> crop = new HashMap<>();
	/** Cells you can stand in. */
	final List<BlockPos> stand = new ArrayList<>();
	final Set<BlockPos> chests = new LinkedHashSet<>();
	/** Where to wait when there's nothing to do: the standing cell nearest the middle of the crops. */
	BlockPos centre;
	int minX, minY, minZ, maxX, maxY, maxZ;
	/**
	 * The crop most of the farm grows: what goes in empty farmland nobody remembers. On a bare farm it's whatever seed
	 * you carry or is lying about (the job picks it).
	 */
	Block main;
	/** Bare dirt and grass the water reaches: tilled and planted, so as much ground as possible grows something. */
	final Set<BlockPos> bareGround = new HashSet<>();

	static boolean isCrop(Block b) {
		for (Block c : CROPS) if (c == b) return true;
		return false;
	}

	static boolean mature(BlockState s) {
		Block b = s.getBlock();
		if (b == Blocks.NETHER_WART) return s.get(NetherWartBlock.AGE) >= NetherWartBlock.MAX_AGE;
		return isCrop(b) && b instanceof CropBlock c && c.isMature(s);
	}

	/** The item that plants this crop. */
	static Item seed(Block b) {
		if (b == Blocks.CARROTS) return Items.CARROT;
		if (b == Blocks.POTATOES) return Items.POTATO;
		if (b == Blocks.WHEAT) return Items.WHEAT_SEEDS;
		if (b == Blocks.BEETROOTS) return Items.BEETROOT_SEEDS;
		if (b == Blocks.NETHER_WART) return Items.NETHER_WART;
		return null;
	}

	/** What a harvest gives: these go in the chest when the inventory fills. */
	static boolean produce(Item i) {
		return i == Items.CARROT || i == Items.POTATO || i == Items.POISONOUS_POTATO || i == Items.WHEAT || i == Items.WHEAT_SEEDS
				|| i == Items.BEETROOT || i == Items.BEETROOT_SEEDS || i == Items.NETHER_WART;
	}

	static boolean soil(BlockState s) {
		return s.isOf(Blocks.FARMLAND) || s.isOf(Blocks.SOUL_SAND);
	}

	/** Farmland that's been trampled or dried back to dirt: a hoe makes it farmland again. */
	static boolean tillable(BlockState s) {
		return s.isOf(Blocks.DIRT) || s.isOf(Blocks.GRASS_BLOCK) || s.isOf(Blocks.DIRT_PATH) || s.isOf(Blocks.COARSE_DIRT) || s.isOf(Blocks.ROOTED_DIRT);
	}

	/** Ground a hoe turns into farmland in one go. */
	static boolean newGround(BlockState s) {
		return s.isOf(Blocks.DIRT) || s.isOf(Blocks.GRASS_BLOCK) || s.isOf(Blocks.DIRT_PATH);
	}

	/** Water within 4 blocks, level with it or one up: the farmland stays wet and never dries back to dirt. */
	static boolean hydrated(ClientWorld w, BlockPos soil) {
		for (BlockPos q : BlockPos.iterate(soil.add(-4, 0, -4), soil.add(4, 1, 4))) {
			if (w.getFluidState(q).isIn(net.minecraft.registry.tag.FluidTags.WATER)) return true;
		}
		return false;
	}

	/** Grass, flowers and the like on the ground: in the way of the hoe and the seed, gone in one hit. */
	static boolean weed(ClientWorld w, BlockPos c, BlockState s) {
		return !isCrop(s.getBlock()) && s.isReplaceable() && s.getFluidState().isEmpty() && s.getHardness(w, c) == 0;
	}

	boolean in(BlockPos p) {
		return p.getX() >= minX && p.getX() <= maxX && p.getZ() >= minZ && p.getZ() <= maxZ && p.getY() >= minY - 2 && p.getY() <= maxY + 2;
	}

	/** Walks the ground from {@code start} up to {@code radius} blocks out. */
	static Farm scan(ClientWorld w, BlockPos start, int radius, Set<BlockPos> mine, boolean till) {
		Farm f = new Farm();
		Set<BlockPos> seen = new HashSet<>();
		Set<BlockPos> cellSet = new HashSet<>();
		ArrayDeque<BlockPos> open = new ArrayDeque<>();
		BlockPos s = start;
		for (int i = 0; i < 3 && !Walker.standable(w, s) && !wet(w, s); i++) s = s.down();
		open.add(s);
		seen.add(s);
		while (!open.isEmpty() && seen.size() < 120_000) {
			BlockPos n = open.poll();
			if (Walker.standable(w, n) && w.getFluidState(n).isEmpty()) f.stand.add(n);
			BlockState under = w.getBlockState(n.down());
			if (soil(under) && cellSet.add(n)) f.cells.add(n);
			else if (till && newGround(under) && hydrated(w, n.down()) && w.getFluidState(n).isEmpty() && cellSet.add(n)) {
				f.cells.add(n);
				f.bareGround.add(n);
			}
			for (Direction d : Direction.Type.HORIZONTAL) {
				for (int dy = -1; dy <= 1; dy++) {
					BlockPos side = n.offset(d).up(dy);
					Block b = w.getBlockState(side).getBlock();
					// Only your own chests: never one it just happens to find.
					if ((b instanceof ChestBlock || b instanceof BarrelBlock) && mine.contains(side)) f.chests.add(side.toImmutable());
				}
				for (int dy = 1; dy >= -3; dy--) {
					BlockPos next = n.offset(d).up(dy);
					if (Math.abs(next.getX() - start.getX()) > radius || Math.abs(next.getZ() - start.getZ()) > radius
							|| Math.abs(next.getY() - start.getY()) > 8) break;
					if (dy > 0 && !Walker.passable(w, n.up(2))) continue;
					boolean ok = Walker.standable(w, next) || wet(w, next);
					if (!ok) continue;
					// Dropping down: room to fall.
					boolean clear = true;
					for (int y = next.getY() + 1; y <= n.getY() + 1 && clear; y++) clear = Walker.passable(w, new BlockPos(next.getX(), y, next.getZ()));
					if (!clear) continue;
					if (seen.add(next)) open.add(next);
					break;
				}
			}
		}
		if (f.cells.isEmpty()) return f;
		f.minX = f.minY = f.minZ = Integer.MAX_VALUE;
		f.maxX = f.maxY = f.maxZ = Integer.MIN_VALUE;
		long sx = 0, sz = 0;
		Map<Block, Integer> counts = new HashMap<>();
		for (BlockPos c : f.cells) {
			f.minX = Math.min(f.minX, c.getX());
			f.minY = Math.min(f.minY, c.getY());
			f.minZ = Math.min(f.minZ, c.getZ());
			f.maxX = Math.max(f.maxX, c.getX());
			f.maxY = Math.max(f.maxY, c.getY());
			f.maxZ = Math.max(f.maxZ, c.getZ());
			sx += c.getX();
			sz += c.getZ();
			Block b = w.getBlockState(c).getBlock();
			if (isCrop(b)) {
				f.crop.put(c, b);
				counts.merge(b, 1, Integer::sum);
			}
		}
		f.main = counts.entrySet().stream().filter(e -> e.getKey() != Blocks.NETHER_WART).max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
		double cx = (double) sx / f.cells.size() + 0.5, cz = (double) sz / f.cells.size() + 0.5;
		double best = Double.MAX_VALUE;
		for (BlockPos p : f.stand) {
			double d = sq(p.getX() + 0.5 - cx) + sq(p.getZ() + 0.5 - cz);
			if (d < best) {
				best = d;
				f.centre = p;
			}
		}
		return f;
	}

	/** What to plant in an empty cell: what grew there last, else the farm's main crop. On soul sand only wart. */
	Block wanted(ClientWorld w, BlockPos cell) {
		if (w.getBlockState(cell.down()).isOf(Blocks.SOUL_SAND)) return Blocks.NETHER_WART;
		Block b = crop.get(cell);
		return b != null && b != Blocks.NETHER_WART ? b : main;
	}

	private static boolean wet(ClientWorld w, BlockPos p) {
		return !w.getFluidState(p).isEmpty() && Walker.passable(w, p.up());
	}

	private static double sq(double d) {
		return d * d;
	}
}

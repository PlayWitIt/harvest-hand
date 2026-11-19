package com.greysworkshop.harvesthand;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Settings, in config/harvesthand.json. */
final class HhConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	static HhConfig INSTANCE = new HhConfig();
	private static Path file = FabricLoader.getInstance().getConfigDir().resolve("harvesthand.json");

	/** How far out from you it looks for the farm, in blocks. */
	int radius = 64;
	/** Crops harvested (and replanted) per tick, at most. */
	int perTick = 2;
	/** Walk over dropped items to pick them up. */
	boolean pickUp = true;
	/** Put the harvest in a chest or barrel at the farm's edge when the inventory is full. */
	boolean deposit = true;
	/** Till farmland that's been trampled back to dirt (needs a hoe). */
	boolean till = true;
	/** With nothing to do, wait in the middle of the farm, where every crop is closest. */
	boolean waitInMiddle = true;
	/** Sprint on longer walks (needs more than 3 drumsticks of food). */
	boolean sprint = true;
	/** Eat when hungry: food first, then spare carrots or potatoes. */
	boolean autoEat = true;
	/** Hold off when health drops to this (half hearts). */
	int minHealth = 8;
	/** Tool guard: put a tool aside with {@link #toolReserve} uses left, so it can be mended instead of breaking. */
	boolean protectTools = true;
	int toolReserve = 10;
	boolean hud = true;
	/**
	 * How shulker boxes are used. OFF: never. SPARE (few boxes): only once your chests are full or there are none.
	 * LOADER (plenty of boxes): instead of a shulker loader. Every box is filled to the top, part-filled ones first;
	 * full boxes go into your chests and empty ones come out of them; loose harvest only once no box is left.
	 */
	Boxes boxes = Boxes.LOADER;
	/** Each box holds one crop (a box of carrots, a box of wheat...), the way a sorter-loader would. */
	boolean oneCropPerBox = true;
	/** Chests you placed or marked ({@code /hh chest}), as "server|dimension|x,y,z": the only ones it stores in. */
	java.util.List<String> chests = new java.util.ArrayList<>();
	long totalHarvested;
	/** Kicked while it's running: reconnect by itself (after {@link #reconnectSeconds}, longer each try) and carry on. */
	boolean reconnect = true;
	int reconnectSeconds = 10;
	/** What to carry on with after a disconnect, until it does. */
	Resume.Pending resume;

	enum Boxes {
		OFF("off"), SPARE("spare"), LOADER("loader");

		final String word;

		Boxes(String word) {
			this.word = word;
		}

		Boxes next() {
			return values()[(ordinal() + 1) % values().length];
		}
	}

	Boxes boxMode() {
		return boxes == null ? Boxes.LOADER : boxes;
	}

	static void load() {
		try {
			if (Files.exists(file)) {
				HhConfig c = GSON.fromJson(Files.readString(file), HhConfig.class);
				if (c != null) INSTANCE = c;
			}
		} catch (IOException | RuntimeException e) {
			HarvestHand.LOGGER.warn("Could not read {}", file, e);
		}
		HhConfig c = INSTANCE;
		if (c.chests == null) c.chests = new java.util.ArrayList<>();
		if (c.boxes == null) c.boxes = Boxes.LOADER;
		c.radius = Math.max(4, Math.min(128, c.radius));
		c.perTick = Math.max(1, Math.min(4, c.perTick));
		c.minHealth = Math.max(0, Math.min(19, c.minHealth));
		c.toolReserve = Math.max(2, Math.min(500, c.toolReserve));
		c.reconnectSeconds = Math.max(3, Math.min(300, c.reconnectSeconds));
	}

	static void save() {
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, GSON.toJson(INSTANCE));
		} catch (IOException e) {
			HarvestHand.LOGGER.warn("Could not save {}", file, e);
		}
	}

	/** For the self-test: settings in a file of its own. */
	static void useFile(Path f) {
		file = f;
		INSTANCE = new HhConfig();
	}
}

package net.travelbag.config;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.level.GameType;
import net.travelbag.TravelBagMod;

public final class TravelBagConfigManager {
	private static final String CONFIG_FILE_NAME = "travelbag.properties";
	private final Path dataDirectory = FabricLoader.getInstance().getConfigDir().resolve(TravelBagMod.MOD_ID);
	private final Path configPath = this.dataDirectory.resolve(CONFIG_FILE_NAME);
	private TravelBagConfig config = new TravelBagConfig();

	public Path getDataDirectory() {
		return this.dataDirectory;
	}

	public Path getConfigPath() {
		return this.configPath;
	}

	public TravelBagConfig getConfig() {
		return this.config;
	}

	public void save(TravelBagConfig config) throws IOException {
		config.sanitize();
		Files.createDirectories(this.dataDirectory);
		try (Writer writer = Files.newBufferedWriter(this.configPath, StandardCharsets.UTF_8)) {
			this.writeConfig(writer, config);
		}
		this.config = config;
	}

	public void reload() {
		try {
			Files.createDirectories(this.dataDirectory);
			if (Files.notExists(this.configPath)) {
				this.writeDefaultConfig();
			}
			this.config = this.readConfig();
		} catch (IOException exception) {
			TravelBagMod.LOGGER.warn("[TravelBag] Failed to load config, using defaults.", exception);
			this.config = new TravelBagConfig();
			this.config.sanitize();
		}
	}

	private TravelBagConfig readConfig() throws IOException {
		Properties properties = new Properties();
		try (Reader reader = Files.newBufferedReader(this.configPath, StandardCharsets.UTF_8)) {
			properties.load(reader);
		}

		TravelBagConfig loaded = new TravelBagConfig();
		loaded.setTravelBagTitle(properties.getProperty("TravelBagTitle", loaded.getTravelBagTitle()));
		loaded.setTravelBagTitleOther(properties.getProperty("TravelBagTitleOther", loaded.getTravelBagTitleOther()));
		loaded.setDropOnDeath(readBoolean(properties, "DropOnDeath", loaded.isDropOnDeath()));
		loaded.setHonorKeepInventoryOnDeath(readBoolean(properties, "HonorKeepInventoryOnDeath", loaded.isHonorKeepInventoryOnDeath()));
		loaded.setSize(readInt(properties, "Size", loaded.getSize()));
		loaded.setLuckPerms(readBoolean(properties, "LuckPerms", loaded.isLuckPerms()));
		loaded.setCooldown(readInt(properties, "Cooldown", loaded.getCooldown()));
		loaded.setEnableShortcutItem(readBoolean(properties, "EnableShortcutItem", loaded.isEnableShortcutItem()));
		loaded.setShortcutPreferredSlot(readInt(properties, "ShortcutPreferredSlot", loaded.getShortcutPreferredSlot()));
		loaded.setEnableOpenSound(readBoolean(properties, "EnableOpenSound", loaded.isEnableOpenSound()));
		loaded.setTravelBagOpenSound(properties.getProperty("TravelBagOpenSound", loaded.getTravelBagOpenSound()));
		loaded.setHeadTextureValue(properties.getProperty("HeadTextureValue", loaded.getHeadTextureValue()));
		loaded.setCollectItems(readBoolean(properties, "CollectItems", loaded.isCollectItems()));
		loaded.setCheckInterval(readInt(properties, "CheckInterval", loaded.getCheckInterval()));
		loaded.setCollectRadius(readDouble(properties, "CollectRadius", loaded.getCollectRadius()));
		loaded.setPreventShulkerInTravelBag(readBoolean(properties, "PreventShulkerInTravelBag", loaded.isPreventShulkerInTravelBag()));
		loaded.setItemFilterEnabled(readBoolean(properties, "ItemFilter", loaded.isItemFilterEnabled()));
		loaded.setFilteredMaterials(readList(properties, "Materials"));
		loaded.setClearCommand(readBoolean(properties, "ClearCommand", loaded.isClearCommand()));
		loaded.setAllowedGameModes(readGameModes(properties.getProperty("AllowedGameModes", "SURVIVAL")));
		loaded.setAliases(readList(properties, "Aliases"));
		loaded.sanitize();
		return loaded;
	}

	private void writeDefaultConfig() throws IOException {
		TravelBagConfig defaults = new TravelBagConfig();
		defaults.sanitize();

		try (Writer writer = Files.newBufferedWriter(this.configPath, StandardCharsets.UTF_8)) {
			this.writeConfig(writer, defaults);
		}
	}

	private void writeConfig(Writer writer, TravelBagConfig config) throws IOException {
		writer.write("# Title shown when another player's TravelBag is opened. Supports {OwnerName}\n");
		writer.write("TravelBagTitleOther=" + config.getTravelBagTitleOther() + "\n\n");
		writer.write("# Title shown to the owner of the TravelBag\n");
		writer.write("TravelBagTitle=" + config.getTravelBagTitle() + "\n\n");
		writer.write("# Drop the TravelBag contents on death\n");
		writer.write("DropOnDeath=" + config.isDropOnDeath() + "\n\n");
		writer.write("# If keepInventory is active, do not drop the TravelBag\n");
		writer.write("HonorKeepInventoryOnDeath=" + config.isHonorKeepInventoryOnDeath() + "\n\n");
		writer.write("# Default TravelBag size when permission sizes are not used (1-6 rows)\n");
		writer.write("Size=" + config.getSize() + "\n\n");
		writer.write("# Use permission nodes for TravelBag features and sizes\n");
		writer.write("LuckPerms=" + config.isLuckPerms() + "\n\n");
		writer.write("# Cooldown in ticks before reopening the TravelBag\n");
		writer.write("Cooldown=" + config.getCooldown() + "\n\n");
		writer.write("# Give every player the TravelBag shortcut head item\n");
		writer.write("EnableShortcutItem=" + config.isEnableShortcutItem() + "\n\n");
		writer.write("# Preferred inventory slot for new shortcut items (0-35)\n");
		writer.write("ShortcutPreferredSlot=" + config.getShortcutPreferredSlot() + "\n\n");
		writer.write("# Play a sound when a TravelBag is opened\n");
		writer.write("EnableOpenSound=" + config.isEnableOpenSound() + "\n\n");
		writer.write("# Sound event id used when the TravelBag opens\n");
		writer.write("TravelBagOpenSound=" + config.getTravelBagOpenSound() + "\n\n");
		writer.write("# Texture value for the shortcut head item\n");
		writer.write("HeadTextureValue=" + config.getHeadTextureValue() + "\n\n");
		writer.write("# Automatically collect nearby items into the TravelBag when the inventory is full\n");
		writer.write("CollectItems=" + config.isCollectItems() + "\n\n");
		writer.write("# Interval in ticks for automatic item collection\n");
		writer.write("CheckInterval=" + config.getCheckInterval() + "\n\n");
		writer.write("# Radius in blocks for automatic item collection\n");
		writer.write("CollectRadius=" + config.getCollectRadius() + "\n\n");
		writer.write("# Prevent shulker boxes from being stored in the TravelBag\n");
		writer.write("PreventShulkerInTravelBag=" + config.isPreventShulkerInTravelBag() + "\n\n");
		writer.write("# Enable the TravelBag item filter\n");
		writer.write("ItemFilter=" + config.isItemFilterEnabled() + "\n\n");
		writer.write("# Comma-separated item identifiers blocked from the TravelBag. Example: minecraft:iron_block,minecraft:diamond_block\n");
		writer.write("Materials=" + String.join(",", config.getFilteredMaterials()) + "\n\n");
		writer.write("# Allow /travelbag clean without permissions when LuckPerms is disabled\n");
		writer.write("ClearCommand=" + config.isClearCommand() + "\n\n");
		writer.write("# Allowed game modes: SURVIVAL, ADVENTURE, CREATIVE, SPECTATOR\n");
		writer.write("AllowedGameModes=" + config.getAllowedGameModes().stream().map(mode -> mode.name().toUpperCase(Locale.ROOT)).sorted().collect(Collectors.joining(",")) + "\n\n");
		writer.write("# Command aliases without leading slash\n");
		writer.write("Aliases=" + String.join(",", config.getAliases()) + "\n");
	}

	private static boolean readBoolean(Properties properties, String key, boolean fallback) {
		String raw = properties.getProperty(key);
		return raw == null ? fallback : Boolean.parseBoolean(raw.trim());
	}

	private static int readInt(Properties properties, String key, int fallback) {
		String raw = properties.getProperty(key);
		if (raw == null) {
			return fallback;
		}

		try {
			return Integer.parseInt(raw.trim());
		} catch (NumberFormatException ignored) {
			return fallback;
		}
	}

	private static double readDouble(Properties properties, String key, double fallback) {
		String raw = properties.getProperty(key);
		if (raw == null) {
			return fallback;
		}

		try {
			return Double.parseDouble(raw.trim());
		} catch (NumberFormatException ignored) {
			return fallback;
		}
	}

	private static List<String> readList(Properties properties, String key) {
		String raw = properties.getProperty(key, "");
		List<String> values = new ArrayList<>();
		for (String part : raw.split(",")) {
			String cleaned = part.trim();
			if (!cleaned.isBlank()) {
				values.add(cleaned);
			}
		}
		return values;
	}

	private static Set<GameType> readGameModes(String raw) {
		EnumSet<GameType> result = EnumSet.noneOf(GameType.class);
		for (String part : raw.split(",")) {
			String cleaned = part.trim();
			if (cleaned.isBlank()) {
				continue;
			}
			try {
				result.add(GameType.valueOf(cleaned.toUpperCase(Locale.ROOT)));
			} catch (IllegalArgumentException ignored) {
				// Ignore invalid game modes in the config.
			}
		}
		return result.isEmpty() ? EnumSet.of(GameType.SURVIVAL) : result;
	}
}

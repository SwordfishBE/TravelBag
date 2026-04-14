package net.travelbag.client.config;

import java.io.IOException;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.GameType;
import net.travelbag.TravelBagMod;
import net.travelbag.config.TravelBagConfig;
import net.travelbag.config.TravelBagConfigManager;

public final class TravelBagClothConfigScreenFactory {
	private TravelBagClothConfigScreenFactory() {
	}

	public static Screen create(Screen parent) {
		TravelBagConfigManager configManager = TravelBagMod.getInstance().getConfigManager();
		TravelBagConfig source = configManager.getConfig();
		TravelBagConfig workingCopy = copyOf(source);

		ConfigBuilder builder = ConfigBuilder.create()
			.setParentScreen(parent)
			.setTitle(Component.literal("TravelBag Config"));
		ConfigEntryBuilder entries = builder.entryBuilder();
		ConfigCategory general = builder.getOrCreateCategory(Component.literal("General"));
		ConfigCategory pickup = builder.getOrCreateCategory(Component.literal("Pickup"));
		ConfigCategory filter = builder.getOrCreateCategory(Component.literal("Filter"));

		general.addEntry(entries.startStrField(Component.literal("TravelBag title"), workingCopy.getTravelBagTitle())
			.setDefaultValue(source.getTravelBagTitle())
			.setSaveConsumer(workingCopy::setTravelBagTitle)
			.build());
		general.addEntry(entries.startStrField(Component.literal("Other-player title"), workingCopy.getTravelBagTitleOther())
			.setDefaultValue(source.getTravelBagTitleOther())
			.setSaveConsumer(workingCopy::setTravelBagTitleOther)
			.build());
		general.addEntry(entries.startBooleanToggle(Component.literal("Drop on death"), workingCopy.isDropOnDeath())
			.setDefaultValue(source.isDropOnDeath())
			.setSaveConsumer(workingCopy::setDropOnDeath)
			.build());
		general.addEntry(entries.startBooleanToggle(Component.literal("Honor keepInventory"), workingCopy.isHonorKeepInventoryOnDeath())
			.setDefaultValue(source.isHonorKeepInventoryOnDeath())
			.setSaveConsumer(workingCopy::setHonorKeepInventoryOnDeath)
			.build());
		general.addEntry(entries.startIntField(Component.literal("Default size (rows)"), workingCopy.getSize())
			.setDefaultValue(source.getSize())
			.setMin(1)
			.setMax(6)
			.setSaveConsumer(workingCopy::setSize)
			.build());
		general.addEntry(entries.startBooleanToggle(Component.literal("Use LuckPerms size/features"), workingCopy.isLuckPerms())
			.setDefaultValue(source.isLuckPerms())
			.setSaveConsumer(workingCopy::setLuckPerms)
			.build());
		general.addEntry(entries.startIntField(Component.literal("Open cooldown (ticks)"), workingCopy.getCooldown())
			.setDefaultValue(source.getCooldown())
			.setMin(0)
			.setSaveConsumer(workingCopy::setCooldown)
			.build());
		general.addEntry(entries.startBooleanToggle(Component.literal("Enable shortcut head item"), workingCopy.isEnableShortcutItem())
			.setDefaultValue(source.isEnableShortcutItem())
			.setSaveConsumer(workingCopy::setEnableShortcutItem)
			.build());
		general.addEntry(entries.startIntField(Component.literal("Shortcut preferred slot"), workingCopy.getShortcutPreferredSlot())
			.setDefaultValue(source.getShortcutPreferredSlot())
			.setMin(0)
			.setMax(35)
			.setSaveConsumer(workingCopy::setShortcutPreferredSlot)
			.build());
		general.addEntry(entries.startBooleanToggle(Component.literal("Enable open sound"), workingCopy.isEnableOpenSound())
			.setDefaultValue(source.isEnableOpenSound())
			.setSaveConsumer(workingCopy::setEnableOpenSound)
			.build());
		general.addEntry(entries.startStrField(Component.literal("Open sound id"), workingCopy.getTravelBagOpenSound())
			.setDefaultValue(source.getTravelBagOpenSound())
			.setSaveConsumer(workingCopy::setTravelBagOpenSound)
			.build());
		general.addEntry(entries.startStrField(Component.literal("Shortcut head texture"), workingCopy.getHeadTextureValue())
			.setDefaultValue(source.getHeadTextureValue())
			.setSaveConsumer(workingCopy::setHeadTextureValue)
			.build());
		general.addEntry(entries.startBooleanToggle(Component.literal("Fallback clean command"), workingCopy.isClearCommand())
			.setDefaultValue(source.isClearCommand())
			.setSaveConsumer(workingCopy::setClearCommand)
			.build());
		general.addEntry(entries.startStrField(Component.literal("Allowed game modes (CSV)"), formatGameModes(workingCopy))
			.setDefaultValue(formatGameModes(source))
			.setSaveConsumer(value -> workingCopy.setAllowedGameModes(parseGameModes(value)))
			.build());
		general.addEntry(entries.startStrField(Component.literal("Aliases (CSV)"), String.join(",", workingCopy.getAliases()))
			.setDefaultValue(String.join(",", source.getAliases()))
			.setSaveConsumer(value -> workingCopy.setAliases(parseCsv(value)))
			.build());

		pickup.addEntry(entries.startBooleanToggle(Component.literal("Collect items on full inventory"), workingCopy.isCollectItems())
			.setDefaultValue(source.isCollectItems())
			.setSaveConsumer(workingCopy::setCollectItems)
			.build());
		pickup.addEntry(entries.startIntField(Component.literal("Pickup interval (ticks)"), workingCopy.getCheckInterval())
			.setDefaultValue(source.getCheckInterval())
			.setMin(1)
			.setSaveConsumer(workingCopy::setCheckInterval)
			.build());
		pickup.addEntry(entries.startDoubleField(Component.literal("Pickup radius"), workingCopy.getCollectRadius())
			.setDefaultValue(source.getCollectRadius())
			.setMin(0.25D)
			.setSaveConsumer(workingCopy::setCollectRadius)
			.build());

		filter.addEntry(entries.startBooleanToggle(Component.literal("Prevent shulkers in TravelBag"), workingCopy.isPreventShulkerInTravelBag())
			.setDefaultValue(source.isPreventShulkerInTravelBag())
			.setSaveConsumer(workingCopy::setPreventShulkerInTravelBag)
			.build());
		filter.addEntry(entries.startBooleanToggle(Component.literal("Enable item filter"), workingCopy.isItemFilterEnabled())
			.setDefaultValue(source.isItemFilterEnabled())
			.setSaveConsumer(workingCopy::setItemFilterEnabled)
			.build());
		filter.addEntry(entries.startStrField(Component.literal("Filtered items (CSV)"), String.join(",", workingCopy.getFilteredMaterials()))
			.setDefaultValue(String.join(",", source.getFilteredMaterials()))
			.setSaveConsumer(value -> workingCopy.setFilteredMaterials(parseCsv(value)))
			.build());

		builder.setSavingRunnable(() -> saveConfig(configManager, workingCopy));
		return builder.build();
	}

	private static void saveConfig(TravelBagConfigManager configManager, TravelBagConfig config) {
		try {
			configManager.save(config);
		} catch (IOException exception) {
			TravelBagMod.LOGGER.warn("[TravelBag] Failed to save config from Mod Menu.", exception);
		}
	}

	private static TravelBagConfig copyOf(TravelBagConfig source) {
		TravelBagConfig copy = new TravelBagConfig();
		copy.setTravelBagTitle(source.getTravelBagTitle());
		copy.setTravelBagTitleOther(source.getTravelBagTitleOther());
		copy.setDropOnDeath(source.isDropOnDeath());
		copy.setHonorKeepInventoryOnDeath(source.isHonorKeepInventoryOnDeath());
		copy.setSize(source.getSize());
		copy.setLuckPerms(source.isLuckPerms());
		copy.setCooldown(source.getCooldown());
		copy.setEnableShortcutItem(source.isEnableShortcutItem());
		copy.setShortcutPreferredSlot(source.getShortcutPreferredSlot());
		copy.setEnableOpenSound(source.isEnableOpenSound());
		copy.setTravelBagOpenSound(source.getTravelBagOpenSound());
		copy.setHeadTextureValue(source.getHeadTextureValue());
		copy.setCollectItems(source.isCollectItems());
		copy.setCheckInterval(source.getCheckInterval());
		copy.setCollectRadius(source.getCollectRadius());
		copy.setPreventShulkerInTravelBag(source.isPreventShulkerInTravelBag());
		copy.setItemFilterEnabled(source.isItemFilterEnabled());
		copy.setFilteredMaterials(List.copyOf(source.getFilteredMaterials()));
		copy.setClearCommand(source.isClearCommand());
		copy.setAllowedGameModes(EnumSet.copyOf(source.getAllowedGameModes()));
		copy.setAliases(List.copyOf(source.getAliases()));
		copy.sanitize();
		return copy;
	}

	private static List<String> parseCsv(String value) {
		return Arrays.stream(value.split(","))
			.map(part -> part.trim().toLowerCase(Locale.ROOT))
			.filter(part -> !part.isBlank())
			.collect(Collectors.toList());
	}

	private static EnumSet<GameType> parseGameModes(String value) {
		EnumSet<GameType> gameModes = EnumSet.noneOf(GameType.class);
		for (String part : value.split(",")) {
			String cleaned = part.trim();
			if (cleaned.isBlank()) {
				continue;
			}
			try {
				gameModes.add(GameType.valueOf(cleaned.toUpperCase(Locale.ROOT)));
			} catch (IllegalArgumentException ignored) {
				// Invalid values are ignored and sanitized later.
			}
		}
		return gameModes.isEmpty() ? EnumSet.of(GameType.SURVIVAL) : gameModes;
	}

	private static String formatGameModes(TravelBagConfig config) {
		return config.getAllowedGameModes().stream()
			.map(GameType::name)
			.sorted()
			.collect(Collectors.joining(","));
	}
}

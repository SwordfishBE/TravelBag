package net.travelbag.config;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import net.minecraft.world.level.GameType;

public final class TravelBagConfig {
	private String travelBagTitleOther = "&b{OwnerName}'s TravelBag";
	private String travelBagTitle = "&bTravelBag";
	private boolean dropOnDeath = true;
	private boolean honorKeepInventoryOnDeath = false;
	private int size = 6;
	private boolean luckPerms = false;
	private int cooldown = 20;
	private boolean enableShortcutItem = true;
	private int shortcutPreferredSlot = 0;
	private boolean enableOpenSound = true;
	private String travelBagOpenSound = "block.crafter.craft";
	private String headTextureValue = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMjMwOGJmNWNjM2U5ZGVjYWYwNzcwYzNmZGFkMWUwNDIxMjFjZjM5Y2MyNTA1YmJiODY2ZTE4YzZkMjNjY2QwYyJ9fX0=";
	private boolean collectItems = false;
	private int checkInterval = 20;
	private double collectRadius = 1.5D;
	private boolean preventShulkerInTravelBag = true;
	private boolean itemFilterEnabled = false;
	private List<String> filteredMaterials = new ArrayList<>();
	private boolean clearCommand = true;
	private Set<GameType> allowedGameModes = EnumSet.of(GameType.SURVIVAL);
	private List<String> aliases = new ArrayList<>(List.of("bp", "backpack"));

	public void sanitize() {
		this.size = clamp(this.size, 1, 6);
		this.cooldown = Math.max(0, this.cooldown);
		this.checkInterval = Math.max(1, this.checkInterval);
		this.collectRadius = Math.max(0.25D, this.collectRadius);
		this.shortcutPreferredSlot = clamp(this.shortcutPreferredSlot, 0, 35);
		this.travelBagOpenSound = this.travelBagOpenSound == null ? "block.crafter.craft" : this.travelBagOpenSound.trim().toLowerCase(Locale.ROOT);
		if (this.travelBagOpenSound.isBlank()) {
			this.travelBagOpenSound = "block.crafter.craft";
		}
		this.filteredMaterials = this.filteredMaterials.stream()
			.map(value -> value.trim().toLowerCase(Locale.ROOT))
			.filter(value -> !value.isBlank())
			.distinct()
			.collect(Collectors.toCollection(ArrayList::new));
		this.aliases = this.aliases.stream()
			.map(value -> value.replace("/", "").trim().toLowerCase(Locale.ROOT))
			.filter(value -> !value.isBlank())
			.filter(value -> !value.equals("travelbag"))
			.distinct()
			.collect(Collectors.toCollection(ArrayList::new));
		if (this.allowedGameModes.isEmpty()) {
			this.allowedGameModes = EnumSet.of(GameType.SURVIVAL);
		}
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}

	public String getTravelBagTitleOther() {
		return travelBagTitleOther;
	}

	public void setTravelBagTitleOther(String travelBagTitleOther) {
		this.travelBagTitleOther = travelBagTitleOther;
	}

	public String getTravelBagTitle() {
		return travelBagTitle;
	}

	public void setTravelBagTitle(String travelBagTitle) {
		this.travelBagTitle = travelBagTitle;
	}

	public boolean isDropOnDeath() {
		return dropOnDeath;
	}

	public void setDropOnDeath(boolean dropOnDeath) {
		this.dropOnDeath = dropOnDeath;
	}

	public boolean isHonorKeepInventoryOnDeath() {
		return honorKeepInventoryOnDeath;
	}

	public void setHonorKeepInventoryOnDeath(boolean honorKeepInventoryOnDeath) {
		this.honorKeepInventoryOnDeath = honorKeepInventoryOnDeath;
	}

	public int getSize() {
		return size;
	}

	public void setSize(int size) {
		this.size = size;
	}

	public boolean isLuckPerms() {
		return luckPerms;
	}

	public void setLuckPerms(boolean luckPerms) {
		this.luckPerms = luckPerms;
	}

	public int getCooldown() {
		return cooldown;
	}

	public void setCooldown(int cooldown) {
		this.cooldown = cooldown;
	}

	public boolean isEnableShortcutItem() {
		return enableShortcutItem;
	}

	public void setEnableShortcutItem(boolean enableShortcutItem) {
		this.enableShortcutItem = enableShortcutItem;
	}

	public int getShortcutPreferredSlot() {
		return shortcutPreferredSlot;
	}

	public void setShortcutPreferredSlot(int shortcutPreferredSlot) {
		this.shortcutPreferredSlot = shortcutPreferredSlot;
	}

	public boolean isEnableOpenSound() {
		return enableOpenSound;
	}

	public void setEnableOpenSound(boolean enableOpenSound) {
		this.enableOpenSound = enableOpenSound;
	}

	public String getTravelBagOpenSound() {
		return travelBagOpenSound;
	}

	public void setTravelBagOpenSound(String travelBagOpenSound) {
		this.travelBagOpenSound = travelBagOpenSound;
	}

	public String getHeadTextureValue() {
		return headTextureValue;
	}

	public void setHeadTextureValue(String headTextureValue) {
		this.headTextureValue = headTextureValue;
	}

	public boolean isCollectItems() {
		return collectItems;
	}

	public void setCollectItems(boolean collectItems) {
		this.collectItems = collectItems;
	}

	public int getCheckInterval() {
		return checkInterval;
	}

	public void setCheckInterval(int checkInterval) {
		this.checkInterval = checkInterval;
	}

	public double getCollectRadius() {
		return collectRadius;
	}

	public void setCollectRadius(double collectRadius) {
		this.collectRadius = collectRadius;
	}

	public boolean isPreventShulkerInTravelBag() {
		return preventShulkerInTravelBag;
	}

	public void setPreventShulkerInTravelBag(boolean preventShulkerInTravelBag) {
		this.preventShulkerInTravelBag = preventShulkerInTravelBag;
	}

	public boolean isItemFilterEnabled() {
		return itemFilterEnabled;
	}

	public void setItemFilterEnabled(boolean itemFilterEnabled) {
		this.itemFilterEnabled = itemFilterEnabled;
	}

	public List<String> getFilteredMaterials() {
		return filteredMaterials;
	}

	public void setFilteredMaterials(List<String> filteredMaterials) {
		this.filteredMaterials = filteredMaterials;
	}

	public boolean isClearCommand() {
		return clearCommand;
	}

	public void setClearCommand(boolean clearCommand) {
		this.clearCommand = clearCommand;
	}

	public Set<GameType> getAllowedGameModes() {
		return allowedGameModes;
	}

	public void setAllowedGameModes(Set<GameType> allowedGameModes) {
		this.allowedGameModes = EnumSet.copyOf(allowedGameModes);
	}

	public List<String> getAliases() {
		return aliases;
	}

	public void setAliases(List<String> aliases) {
		this.aliases = aliases;
	}
}

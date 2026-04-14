package net.travelbag;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import com.mojang.authlib.GameProfile;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.Util;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.gamerules.GameRules;
import net.travelbag.bag.TravelBagInventory;
import net.travelbag.bag.TravelBagScreenHandler;
import net.travelbag.command.TravelBagCommands;
import net.travelbag.config.TravelBagConfig;
import net.travelbag.config.TravelBagConfigManager;
import net.travelbag.permission.PermissionService;
import net.travelbag.pickup.TravelBagPickupService;
import net.travelbag.shortcut.ShortcutItemManager;
import net.travelbag.storage.PlayerBagData;
import net.travelbag.storage.TravelBagStorage;
import net.travelbag.util.ModrinthUpdateChecker;
import net.travelbag.util.TextUtil;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class TravelBagMod implements ModInitializer {
	public static final String MOD_ID = "travelbag";
	public static final String MOD_NAME = "TravelBag";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_NAME);
	private static final int MAX_ROWS = 6;
	private static final int SHORTCUT_RESYNC_TICKS = 40;
	private static TravelBagMod instance;

	private final Map<UUID, Long> lastOpenTimes = new ConcurrentHashMap<>();
	private final Map<UUID, Integer> pendingShortcutSyncs = new ConcurrentHashMap<>();
	private final Map<UUID, List<TravelBagInventory>> openBags = new ConcurrentHashMap<>();
	private TravelBagConfigManager configManager;
	private TravelBagStorage storage;
	private PermissionService permissionService;
	private TravelBagPickupService pickupService;
	private ShortcutItemManager shortcutItemManager;
	private MinecraftServer server;

	public static TravelBagMod getInstance() {
		return instance;
	}

	@Override
	public void onInitialize() {
		instance = this;
		this.configManager = new TravelBagConfigManager();
		this.configManager.reload();

		Path dataDirectory = this.configManager.getDataDirectory();
		this.storage = new TravelBagStorage(dataDirectory.resolve("players"));
		this.permissionService = new PermissionService(this);
		this.pickupService = new TravelBagPickupService(this);
		this.shortcutItemManager = new ShortcutItemManager(this);

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> TravelBagCommands.register(this, dispatcher));

		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			this.server = server;
			this.storage.prepare(server);
			this.storage.createBackups();
			this.pendingShortcutSyncs.clear();
			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				this.shortcutItemManager.syncShortcutItem(player);
				this.compactBag(player.getUUID(), this.getRowsFor(player));
				this.scheduleShortcutResync(player.getUUID());
			}
			ModrinthUpdateChecker.checkOnceAsync();
		});

		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			this.storage.saveAll();
			this.pendingShortcutSyncs.clear();
			this.openBags.clear();
			this.server = null;
		});

		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			this.storage.prepare(server);
			this.shortcutItemManager.syncShortcutItem(handler.player);
			this.compactBag(handler.player.getUUID(), this.getRowsFor(handler.player));
			this.scheduleShortcutResync(handler.player.getUUID());
		});

		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			this.pendingShortcutSyncs.remove(handler.player.getUUID());
			this.storage.save(handler.player.getUUID());
		});
		ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
			this.shortcutItemManager.syncShortcutItem(newPlayer);
			this.compactBag(newPlayer.getUUID(), this.getRowsFor(newPlayer));
			this.scheduleShortcutResync(newPlayer.getUUID());
		});
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) -> {
			if (entity instanceof ServerPlayer player) {
				this.handlePlayerDeath(player);
			}
		});
		ServerTickEvents.END_SERVER_TICK.register(server -> this.processPendingShortcutSyncs(server));

		UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
			if (player instanceof ServerPlayer serverPlayer && this.shortcutItemManager.isShortcutItem(serverPlayer.getItemInHand(hand))) {
				this.openOwnBag(serverPlayer);
				return InteractionResult.SUCCESS;
			}
			return InteractionResult.PASS;
		});

		UseItemCallback.EVENT.register((player, world, hand) -> {
			if (player instanceof ServerPlayer serverPlayer && this.shortcutItemManager.isShortcutItem(serverPlayer.getItemInHand(hand))) {
				this.openOwnBag(serverPlayer);
				return InteractionResult.SUCCESS;
			}
			return InteractionResult.PASS;
		});

		this.pickupService.register();
		LOGGER.info("[TravelBag] Mod initialized. Version: {}", getModVersion());
	}

	public String getModVersion() {
		return Objects.requireNonNull(net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer(MOD_ID).orElseThrow().getMetadata().getVersion().getFriendlyString());
	}

	public static String prefix() {
		return "[" + MOD_NAME + "]";
	}

	public TravelBagConfigManager getConfigManager() {
		return this.configManager;
	}

	public TravelBagConfig getConfig() {
		return this.configManager.getConfig();
	}

	public TravelBagStorage getStorage() {
		return this.storage;
	}

	public PermissionService getPermissionService() {
		return this.permissionService;
	}

	public ShortcutItemManager getShortcutItemManager() {
		return this.shortcutItemManager;
	}

	public void registerOpenBag(UUID ownerUuid, TravelBagInventory inventory) {
		this.openBags.computeIfAbsent(ownerUuid, ignored -> new CopyOnWriteArrayList<>()).add(inventory);
	}

	public void unregisterOpenBag(UUID ownerUuid, TravelBagInventory inventory) {
		List<TravelBagInventory> inventories = this.openBags.get(ownerUuid);
		if (inventories == null) {
			return;
		}
		inventories.remove(inventory);
		if (inventories.isEmpty()) {
			this.openBags.remove(ownerUuid);
		}
	}

	public MinecraftServer getServer() {
		return this.server;
	}

	public int getRowsFor(UUID playerUuid) {
		return this.permissionService.getRowsFor(playerUuid);
	}

	public int getRowsFor(ServerPlayer player) {
		return this.permissionService.getRowsFor(player);
	}

	public boolean canAccessInCurrentGameMode(ServerPlayer player) {
		if (this.permissionService.hasAdmin(player) || this.permissionService.canBypassGameMode(player)) {
			return true;
		}

		GameType gameType = player.gameMode.getGameModeForPlayer();
		return this.getConfig().getAllowedGameModes().contains(gameType);
	}

	public boolean isOnCooldown(ServerPlayer player) {
		if (this.permissionService.canIgnoreCooldown(player)) {
			return false;
		}

		long cooldownMillis = Math.max(0L, this.getConfig().getCooldown()) * 50L;
		long lastOpen = this.lastOpenTimes.getOrDefault(player.getUUID(), 0L);
		return cooldownMillis > 0L && Util.getMillis() - lastOpen < cooldownMillis;
	}

	public long getRemainingCooldownMillis(ServerPlayer player) {
		long cooldownMillis = Math.max(0L, this.getConfig().getCooldown()) * 50L;
		long lastOpen = this.lastOpenTimes.getOrDefault(player.getUUID(), 0L);
		return Math.max(0L, cooldownMillis - (Util.getMillis() - lastOpen));
	}

	public void openOwnBag(ServerPlayer player) {
		this.shortcutItemManager.syncShortcutItem(player);
		this.compactBag(player.getUUID(), this.getRowsFor(player));
		this.openBag(player, player.getGameProfile(), true, true);
	}

	public void openBag(ServerPlayer viewer, GameProfile ownerProfile, boolean allowEdit, boolean enforceOwnerPermissions) {
		if (viewer == null) {
			return;
		}

		boolean ownerView = viewer.getUUID().equals(ownerProfile.id());
		if (ownerView) {
			if (!this.permissionService.canUse(viewer)) {
				viewer.sendSystemMessage(Component.literal("You do not have permission to use TravelBag."));
				return;
			}
			if (!this.canAccessInCurrentGameMode(viewer)) {
				viewer.sendSystemMessage(Component.literal("You cannot open TravelBag in this game mode."));
				return;
			}
			if (this.isOnCooldown(viewer)) {
				long remainingTicks = Math.max(1L, this.getRemainingCooldownMillis(viewer) / 50L);
				viewer.sendSystemMessage(Component.literal("TravelBag is on cooldown for " + remainingTicks + " more tick(s)."));
				return;
			}
		} else if (!this.permissionService.canOpenOthers(viewer)) {
			viewer.sendSystemMessage(Component.literal("You do not have permission to open another player's TravelBag."));
			return;
		}

		int rows = enforceOwnerPermissions ? this.permissionService.getRowsFor(ownerProfile.id()) : MAX_ROWS;
		rows = Math.max(1, Math.min(MAX_ROWS, rows));
		PlayerBagData data = this.storage.getOrLoad(ownerProfile.id());
		this.compactBag(ownerProfile.id(), rows);
		String ownerName = ownerProfile.name() == null || ownerProfile.name().isBlank() ? ownerProfile.id().toString() : ownerProfile.name();
		boolean editable = ownerView || allowEdit && this.permissionService.canEditOthers(viewer);
		TravelBagInventory inventory = new TravelBagInventory(this, ownerProfile.id(), ownerName, data, rows, editable);
		Component title = ownerView ? TextUtil.fromLegacy(this.getConfig().getTravelBagTitle(), Map.of()) : TextUtil.fromLegacy(this.getConfig().getTravelBagTitleOther(), Map.of("OwnerName", ownerName));

		viewer.openMenu(new SimpleMenuProvider((syncId, playerInventory, player) -> new TravelBagScreenHandler(syncId, playerInventory, inventory), title));
		this.playOpenSound(viewer);
		this.lastOpenTimes.put(viewer.getUUID(), Util.getMillis());
	}

	private void playOpenSound(ServerPlayer player) {
		if (!this.getConfig().isEnableOpenSound()) {
			return;
		}

		try {
			SoundEvent soundEvent = BuiltInRegistries.SOUND_EVENT.getOptional(Identifier.parse(this.getConfig().getTravelBagOpenSound())).orElse(null);
			if (soundEvent != null) {
				player.level().playSound(null, player.getX(), player.getY(), player.getZ(), soundEvent, player.getSoundSource(), 1.0F, 1.0F);
			}
		} catch (Exception exception) {
			TravelBagMod.LOGGER.warn("[TravelBag] Failed to resolve TravelBag open sound '{}'.", this.getConfig().getTravelBagOpenSound());
		}
	}

	public boolean canStoreItem(UUID ownerUuid, ItemStack stack) {
		if (stack.isEmpty()) {
			return false;
		}

		if (this.shortcutItemManager.isShortcutItem(stack)) {
			return false;
		}

		if (this.getConfig().isPreventShulkerInTravelBag() && stack.getItem() instanceof BlockItem blockItem && blockItem.getBlock() instanceof ShulkerBoxBlock) {
			return false;
		}

		if (!this.getConfig().isItemFilterEnabled()) {
			return true;
		}

		if (this.permissionService.canIgnoreBlacklist(ownerUuid)) {
			return true;
		}

		return !this.getConfig().getFilteredMaterials().contains(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
	}

	public boolean canFullyStoreInBag(UUID ownerUuid, ItemStack stack) {
		int rows = this.permissionService.getRowsFor(ownerUuid);
		return this.canFullyStoreInBag(ownerUuid, rows, stack);
	}

	public boolean canFullyStoreInBag(UUID ownerUuid, int rows, ItemStack stack) {
		if (stack.isEmpty()) {
			return true;
		}
		if (!this.canStoreItem(ownerUuid, stack)) {
			return false;
		}

		PlayerBagData data = this.storage.getOrLoad(ownerUuid);
		int maxSlots = Math.max(1, Math.min(MAX_ROWS, rows)) * 9;
		ItemStack remaining = stack.copy();
		List<ItemStack> simulated = new ArrayList<>(maxSlots);
		for (int slot = 0; slot < maxSlots; slot++) {
			simulated.add(data.getStack(slot).copy());
		}

		for (ItemStack existing : simulated) {
			if (!existing.isEmpty() && ItemStack.isSameItemSameComponents(existing, remaining)) {
				int transferable = Math.min(remaining.getCount(), existing.getMaxStackSize() - existing.getCount());
				if (transferable > 0) {
					existing.grow(transferable);
					remaining.shrink(transferable);
					if (remaining.isEmpty()) {
						return true;
					}
				}
			}
		}

		for (ItemStack existing : simulated) {
			if (existing.isEmpty()) {
				return remaining.getCount() <= remaining.getMaxStackSize();
			}
		}

		return false;
	}

	public boolean tryStoreInBag(UUID ownerUuid, ItemStack stack) {
		int rows = this.permissionService.getRowsFor(ownerUuid);
		return this.tryStoreInBag(ownerUuid, rows, stack);
	}

	public boolean tryStoreInBag(UUID ownerUuid, int rows, ItemStack stack) {
		if (stack.isEmpty()) {
			return true;
		}

		PlayerBagData data = this.storage.getOrLoad(ownerUuid);
		int maxSlots = Math.max(1, Math.min(MAX_ROWS, rows)) * 9;

		for (int slot = 0; slot < maxSlots; slot++) {
			ItemStack existing = data.getStack(slot);
			if (!existing.isEmpty() && ItemStack.isSameItemSameComponents(existing, stack)) {
				int transferable = Math.min(stack.getCount(), existing.getMaxStackSize() - existing.getCount());
				if (transferable > 0) {
					existing.grow(transferable);
					stack.shrink(transferable);
					data.markDirty();
					if (stack.isEmpty()) {
						this.refreshOpenBags(ownerUuid);
						this.storage.save(ownerUuid);
						return true;
					}
				}
			}
		}

		for (int slot = 0; slot < maxSlots; slot++) {
			ItemStack existing = data.getStack(slot);
			if (existing.isEmpty()) {
				data.setStack(slot, stack.copyAndClear());
				data.markDirty();
				this.refreshOpenBags(ownerUuid);
				this.storage.save(ownerUuid);
				return true;
			}
		}

		this.refreshOpenBags(ownerUuid);
		this.storage.save(ownerUuid);
		return stack.isEmpty();
	}

	public boolean compactBag(UUID ownerUuid) {
		PlayerBagData data = this.storage.getOrLoad(ownerUuid);
		boolean changed = this.compactBagData(data);
		if (changed) {
			this.refreshOpenBags(ownerUuid);
			this.storage.save(ownerUuid);
		}
		return changed;
	}

	public boolean compactBag(UUID ownerUuid, int visibleRows) {
		PlayerBagData data = this.storage.getOrLoad(ownerUuid);
		if (!this.hasHiddenItems(data, visibleRows)) {
			return false;
		}
		boolean changed = this.compactBagData(data);
		if (changed) {
			this.refreshOpenBags(ownerUuid);
			this.storage.save(ownerUuid);
		}
		return changed;
	}

	private boolean hasHiddenItems(PlayerBagData data, int visibleRows) {
		int firstHiddenSlot = Math.max(0, Math.min(MAX_ROWS, visibleRows)) * 9;
		for (int slot = firstHiddenSlot; slot < data.size(); slot++) {
			if (!data.getStack(slot).isEmpty()) {
				return true;
			}
		}
		return false;
	}

	private boolean compactBagData(PlayerBagData data) {
		List<ItemStack> mergedStacks = new ArrayList<>();
		for (int slot = 0; slot < data.size(); slot++) {
			ItemStack source = data.getStack(slot);
			if (source.isEmpty()) {
				continue;
			}

			ItemStack remaining = source.copy();
			for (ItemStack merged : mergedStacks) {
				if (!ItemStack.isSameItemSameComponents(merged, remaining)) {
					continue;
				}
				int transferable = Math.min(remaining.getCount(), merged.getMaxStackSize() - merged.getCount());
				if (transferable > 0) {
					merged.grow(transferable);
					remaining.shrink(transferable);
					if (remaining.isEmpty()) {
						break;
					}
				}
			}
			if (!remaining.isEmpty()) {
				mergedStacks.add(remaining);
			}
		}

		ItemStack[] compacted = new ItemStack[data.size()];
		for (int slot = 0; slot < compacted.length; slot++) {
			compacted[slot] = ItemStack.EMPTY;
		}
		for (int slot = 0; slot < mergedStacks.size() && slot < compacted.length; slot++) {
			compacted[slot] = mergedStacks.get(slot);
		}

		boolean changed = false;
		for (int slot = 0; slot < data.size(); slot++) {
			ItemStack current = data.getStack(slot);
			ItemStack target = compacted[slot];
			if (!this.areStacksEqual(current, target)) {
				changed = true;
				break;
			}
		}
		if (!changed) {
			return false;
		}

		for (int slot = 0; slot < data.size(); slot++) {
			data.setStack(slot, compacted[slot].isEmpty() ? ItemStack.EMPTY : compacted[slot].copy());
		}
		data.markDirty();
		return true;
	}

	private boolean areStacksEqual(ItemStack first, ItemStack second) {
		if (first.isEmpty() && second.isEmpty()) {
			return true;
		}
		if (first.isEmpty() != second.isEmpty()) {
			return false;
		}
		return first.getCount() == second.getCount() && ItemStack.isSameItemSameComponents(first, second);
	}

	public void handlePlayerDeath(ServerPlayer player) {
		PlayerBagData data = this.storage.getOrLoad(player.getUUID());
		if (data.isEmpty()) {
			return;
		}

		boolean dropOnDeath = this.getConfig().isDropOnDeath();
		if (this.permissionService.keepOnDeath(player)) {
			dropOnDeath = false;
		}

		if (dropOnDeath && this.getConfig().isHonorKeepInventoryOnDeath() && player.level().getGameRules().get(GameRules.KEEP_INVENTORY)) {
			dropOnDeath = false;
		}

		if (!dropOnDeath) {
			return;
		}

		for (int slot = 0; slot < data.size(); slot++) {
			ItemStack stack = data.getStack(slot);
			if (!stack.isEmpty()) {
				player.drop(stack.copy(), true, false);
				data.setStack(slot, ItemStack.EMPTY);
			}
		}
		data.markDirty();
		this.refreshOpenBags(player.getUUID());
		this.storage.save(player.getUUID());
	}

	public void cleanBag(UUID ownerUuid) {
		PlayerBagData data = this.storage.getOrLoad(ownerUuid);
		data.clear();
		this.refreshOpenBags(ownerUuid);
		this.storage.save(ownerUuid);
	}

	public void backupNow() {
		this.storage.saveAll();
		this.storage.createBackups();
	}

	public void reload() throws IOException {
		this.storage.saveAll();
		this.storage.createBackups();
		this.configManager.reload();
		if (this.server != null) {
			for (ServerPlayer player : this.server.getPlayerList().getPlayers()) {
				this.shortcutItemManager.syncShortcutItem(player);
				this.compactBag(player.getUUID(), this.getRowsFor(player));
				this.scheduleShortcutResync(player.getUUID());
			}
		}
	}

	private void scheduleShortcutResync(UUID playerUuid) {
		this.pendingShortcutSyncs.put(playerUuid, SHORTCUT_RESYNC_TICKS);
	}

	private void processPendingShortcutSyncs(MinecraftServer server) {
		if (this.pendingShortcutSyncs.isEmpty()) {
			return;
		}

		for (Map.Entry<UUID, Integer> entry : new ArrayList<>(this.pendingShortcutSyncs.entrySet())) {
			ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
			if (player == null) {
				this.pendingShortcutSyncs.remove(entry.getKey());
				continue;
			}

			this.shortcutItemManager.syncShortcutItem(player);
			int remainingTicks = entry.getValue() - 1;
			if (remainingTicks <= 0) {
				this.pendingShortcutSyncs.remove(entry.getKey());
			} else {
				this.pendingShortcutSyncs.put(entry.getKey(), remainingTicks);
			}
		}
	}

	private void refreshOpenBags(UUID ownerUuid) {
		List<TravelBagInventory> inventories = this.openBags.get(ownerUuid);
		if (inventories == null || inventories.isEmpty()) {
			return;
		}
		for (TravelBagInventory inventory : inventories) {
			inventory.refreshFromData();
		}
	}
}

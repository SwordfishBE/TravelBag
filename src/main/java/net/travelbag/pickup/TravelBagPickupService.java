package net.travelbag.pickup;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.travelbag.TravelBagMod;

public final class TravelBagPickupService {
	private final TravelBagMod mod;
	private int ticks;

	public TravelBagPickupService(TravelBagMod mod) {
		this.mod = mod;
	}

	public void register() {
		ServerTickEvents.END_SERVER_TICK.register(this::onEndTick);
	}

	private void onEndTick(MinecraftServer server) {
		this.ticks++;
		if (this.ticks < this.mod.getConfig().getCheckInterval()) {
			return;
		}
		this.ticks = 0;

		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			this.collectFor(player);
		}
	}

	private void collectFor(ServerPlayer player) {
		if (!this.mod.getPermissionService().canAutoPickup(player)) {
			return;
		}
		if (!this.mod.canAccessInCurrentGameMode(player) || this.mod.getRowsFor(player) <= 0) {
			return;
		}

		double radius = this.mod.getConfig().getCollectRadius();
		for (ItemEntity itemEntity : player.level().getEntitiesOfClass(ItemEntity.class, player.getBoundingBox().inflate(radius), entity -> entity.isAlive() && !entity.getItem().isEmpty())) {
			ItemStack stack = itemEntity.getItem();
			if (!this.mod.canStoreItem(player.getUUID(), stack.copy())) {
				continue;
			}
			if (hasRoomInInventory(player.getInventory(), stack)) {
				continue;
			}
			if (!this.mod.canFullyStoreInBag(player.getUUID(), stack.copy())) {
				continue;
			}
			int pickedUpCount = stack.getCount();
			if (this.mod.tryStoreInBag(player.getUUID(), stack) && stack.isEmpty()) {
				player.take(itemEntity, pickedUpCount);
				itemEntity.discard();
			}
		}
	}

	private static boolean hasRoomInInventory(Inventory inventory, ItemStack stack) {
		for (int slot = 0; slot < 36; slot++) {
			ItemStack current = inventory.getItem(slot);
			if (current.isEmpty()) {
				return true;
			}
			if (ItemStack.isSameItemSameComponents(current, stack) && current.getCount() < current.getMaxStackSize()) {
				return true;
			}
		}
		return false;
	}
}

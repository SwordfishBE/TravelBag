package net.travelbag.bag;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.travelbag.TravelBagMod;

public final class TravelBagSlot extends Slot {
	private final TravelBagInventory bagInventory;
	private final boolean enabled;

	public TravelBagSlot(TravelBagInventory inventory, int index, int x, int y) {
		super(inventory, index, x, y);
		this.bagInventory = inventory;
		this.enabled = index < inventory.getVisibleSlotCount();
	}

	@Override
	public boolean mayPlace(ItemStack stack) {
		return this.enabled && this.bagInventory.isEditable() && TravelBagMod.getInstance().canStoreItem(this.bagInventory.getOwnerUuid(), stack);
	}

	@Override
	public boolean mayPickup(Player player) {
		return this.enabled && this.bagInventory.isEditable();
	}

	@Override
	public boolean isActive() {
		return this.enabled;
	}
}

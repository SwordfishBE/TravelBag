package net.travelbag.bag;

import java.util.UUID;

import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.travelbag.TravelBagMod;
import net.travelbag.storage.PlayerBagData;

public final class TravelBagInventory extends SimpleContainer {
	private final TravelBagMod mod;
	private final UUID ownerUuid;
	private final String ownerName;
	private final PlayerBagData data;
	private final int rows;
	private final boolean editable;
	private boolean suppressSync;

	public TravelBagInventory(TravelBagMod mod, UUID ownerUuid, String ownerName, PlayerBagData data, int rows, boolean editable) {
		super(54);
		this.mod = mod;
		this.ownerUuid = ownerUuid;
		this.ownerName = ownerName;
		this.data = data;
		this.rows = Math.max(1, Math.min(6, rows));
		this.editable = editable;
		this.suppressSync = true;
		for (int slot = 0; slot < data.size(); slot++) {
			super.setItem(slot, data.getStack(slot).copy());
		}
		this.suppressSync = false;
	}

	public UUID getOwnerUuid() {
		return ownerUuid;
	}

	public String getOwnerName() {
		return ownerName;
	}

	public int getRows() {
		return rows;
	}

	public boolean isEditable() {
		return editable;
	}

	public int getVisibleSlotCount() {
		return this.rows * 9;
	}

	public void startOpen(Player player) {
		super.startOpen(player);
		this.mod.registerOpenBag(this.ownerUuid, this);
	}

	@Override
	public void setChanged() {
		super.setChanged();
		if (!this.suppressSync) {
			this.syncToData();
		}
	}

	@Override
	public boolean stillValid(Player player) {
		return true;
	}

	public void stopOpen(Player player) {
		super.stopOpen(player);
		this.mod.unregisterOpenBag(this.ownerUuid, this);
		this.syncToData();
	}

	public void refreshFromData() {
		this.suppressSync = true;
		for (int slot = 0; slot < this.getContainerSize(); slot++) {
			super.setItem(slot, this.data.getStack(slot).copy());
		}
		this.suppressSync = false;
	}

	private void syncToData() {
		for (int slot = 0; slot < this.getContainerSize(); slot++) {
			this.data.setStack(slot, super.getItem(slot).copy());
		}
		this.data.markDirty();
		this.mod.getStorage().save(this.ownerUuid);
	}
}

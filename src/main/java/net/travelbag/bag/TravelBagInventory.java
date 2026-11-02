package net.travelbag.bag;

import java.util.UUID;

import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.server.level.ServerPlayer;
import net.travelbag.TravelBagMod;
import net.travelbag.storage.PlayerBagData;

public final class TravelBagInventory extends SimpleContainer {
	private final TravelBagMod mod;
	private final UUID ownerUuid;
	private final String ownerName;
	private final PlayerBagData data;
	private final int rows;
	private final boolean editable;
	private final boolean enforceOwnerPermissions;
	private boolean suppressSync;
	private boolean editing;

	public TravelBagInventory(TravelBagMod mod, UUID ownerUuid, String ownerName, PlayerBagData data, int rows, boolean editable) {
		this(mod, ownerUuid, ownerName, data, rows, editable, true);
	}

	public TravelBagInventory(TravelBagMod mod, UUID ownerUuid, String ownerName, PlayerBagData data, int rows, boolean editable, boolean enforceOwnerPermissions) {
		super(54);
		this.mod = mod;
		this.ownerUuid = ownerUuid;
		this.ownerName = ownerName;
		this.data = data;
		this.rows = Math.max(1, Math.min(6, rows));
		this.editable = editable;
		this.enforceOwnerPermissions = enforceOwnerPermissions;
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
		if (!this.suppressSync && !this.editing) {
			this.syncToData();
		}
	}

	@Override
	public boolean stillValid(Player player) {
		if (!(player instanceof ServerPlayer serverPlayer) || this.data.isSaveBlocked()) {
			return false;
		}
		ServerPlayer owner = serverPlayer.level().getServer().getPlayerList().getPlayer(this.ownerUuid);
		int allowedRows = this.enforceOwnerPermissions
			? (owner != null ? this.mod.getRowsFor(owner) : this.mod.getRowsFor(this.ownerUuid)) : 6;
		boolean ownerView = player.getUUID().equals(this.ownerUuid);
		if (!ownerView) {
			allowedRows = Math.max(1, Math.min(6, allowedRows));
		}
		if (allowedRows != this.rows) {
			return false;
		}
		if (ownerView) {
			return this.mod.getPermissionService().canUse(serverPlayer) && this.mod.canAccessInCurrentGameMode(serverPlayer);
		}
		return this.mod.getPermissionService().canOpenOthers(serverPlayer)
			&& (!this.editable || this.mod.getPermissionService().canEditOthers(serverPlayer));
	}

	public void stopOpen(Player player) {
		super.stopOpen(player);
		this.mod.unregisterOpenBag(this.ownerUuid, this);
	}

	public void refreshFromData() {
		this.suppressSync = true;
		for (int slot = 0; slot < this.getContainerSize(); slot++) {
			super.setItem(slot, this.data.getStack(slot).copy());
		}
		this.suppressSync = false;
	}

	public PlayerBagData getData() {
		return this.data;
	}

	public void beginEdit() {
		this.editing = true;
	}

	public void endEdit() {
		this.editing = false;
	}

	public boolean commitEdit() {
		boolean changed = false;
		for (int slot = 0; slot < this.getContainerSize(); slot++) {
			changed |= !ItemStack.matches(this.data.getStack(slot), super.getItem(slot));
		}
		if (!changed) {
			return true;
		}
		for (int slot = 0; slot < this.getContainerSize(); slot++) {
			this.data.setStack(slot, super.getItem(slot).copy());
		}
		return this.mod.getStorage().save(this.ownerUuid);
	}

	public void publishChanges() {
		this.mod.refreshOpenBags(this.ownerUuid, this);
	}

	private void syncToData() {
		var before = this.data.snapshot();
		if (!this.commitEdit()) {
			this.data.restore(before);
			this.refreshFromData();
			return;
		}
		this.publishChanges();
	}
}

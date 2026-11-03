package net.travelbag.bag;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.network.chat.Component;
import net.travelbag.TravelBagMod;

public final class TravelBagScreenHandler extends AbstractContainerMenu {
	private static final int BAG_COLUMNS = 9;
	private static final ThreadLocal<TravelBagScreenHandler> ACTIVE_EDIT = new ThreadLocal<>();
	private final TravelBagInventory inventory;
	private final int rows;
	private final Player viewer;
	private final List<ItemEntity> transactionDrops = new ArrayList<>();
	private boolean editing;
	private boolean dropFailed;

	public TravelBagScreenHandler(int syncId, Inventory playerInventory, TravelBagInventory inventory) {
		super(resolveType(inventory.getRows()), syncId);
		this.inventory = inventory;
		this.rows = inventory.getRows();
		this.viewer = playerInventory.player;
		checkContainerSize(inventory, 54);
		inventory.startOpen(playerInventory.player);
		this.addBagSlots();
		this.addPlayerSlots(playerInventory);
	}

	private static MenuType<?> resolveType(int rows) {
		return switch (rows) {
			case 1 -> MenuType.GENERIC_9x1;
			case 2 -> MenuType.GENERIC_9x2;
			case 3 -> MenuType.GENERIC_9x3;
			case 4 -> MenuType.GENERIC_9x4;
			case 5 -> MenuType.GENERIC_9x5;
			default -> MenuType.GENERIC_9x6;
		};
	}

	private void addBagSlots() {
		for (int row = 0; row < this.rows; row++) {
			for (int column = 0; column < BAG_COLUMNS; column++) {
				int index = column + row * BAG_COLUMNS;
				this.addSlot(new TravelBagSlot(this.inventory, index, 8 + column * 18, 18 + row * 18));
			}
		}
	}

	private void addPlayerSlots(Inventory playerInventory) {
		int inventoryY = 18 + this.rows * 18 + 14;
		for (int row = 0; row < 3; row++) {
			for (int column = 0; column < 9; column++) {
				this.addSlot(new Slot(playerInventory, column + row * 9 + 9, 8 + column * 18, inventoryY + row * 18, Slot.Section.INVENTORY));
			}
		}

		for (int column = 0; column < 9; column++) {
			this.addSlot(new Slot(playerInventory, column, 8 + column * 18, inventoryY + 58, Slot.Section.HOTBAR));
		}
	}

	public TravelBagInventory getTravelBagInventory() {
		return this.inventory;
	}

	@Override
	public boolean stillValid(Player player) {
		return this.inventory.stillValid(player);
	}

	@Override
	public ItemStack quickMoveStack(Player player, int slotIndex) {
		return this.editing ? this.moveStack(player, slotIndex) : this.transact(player, () -> this.moveStack(player, slotIndex));
	}

	private ItemStack moveStack(Player player, int slotIndex) {
		if (slotIndex < 0 || slotIndex >= this.slots.size() || !this.inventory.isEditable()) {
			return ItemStack.EMPTY;
		}
		ItemStack moved = ItemStack.EMPTY;
		Slot slot = this.slots.get(slotIndex);
		if (slot.hasItem()) {
			ItemStack originalStack = slot.getItem();
			moved = originalStack.copy();
			int bagSlots = this.rows * BAG_COLUMNS;
			if (slotIndex < bagSlots) {
				if (!this.moveItemStackTo(originalStack, bagSlots, this.slots.size(), true)) {
					return ItemStack.EMPTY;
				}
			} else if (!this.slots.get(0).mayPlace(originalStack)
				|| !this.moveItemStackTo(originalStack, 0, bagSlots, false)) {
				return ItemStack.EMPTY;
			}

			if (originalStack.isEmpty()) {
				slot.set(ItemStack.EMPTY);
			} else {
				slot.setChanged();
			}
		}
		return moved;
	}

	@Override
	public void clicked(int slot, int button, net.minecraft.world.inventory.ContainerInput input, Player player) {
		this.transact(player, () -> {
			super.clicked(slot, button, input, player);
			return ItemStack.EMPTY;
		});
	}

	@Override
	public void setSelectedBundleItemIndex(int slot, int selected) {
		this.transact(this.viewer, () -> {
			super.setSelectedBundleItemIndex(slot, selected);
			return ItemStack.EMPTY;
		});
	}

	public static void recordDrop(Player player, ItemEntity entity, boolean hadItems) {
		TravelBagScreenHandler active = ACTIVE_EDIT.get();
		if (active != null && active.viewer == player) {
			if (entity != null) active.transactionDrops.add(entity);
			else if (hadItems) active.dropFailed = true;
		}
	}

	private ItemStack transact(Player player, Supplier<ItemStack> action) {
		if (!this.stillValid(player) || !TravelBagMod.getInstance().getStorage().save(this.inventory.getOwnerUuid())) {
			this.resetQuickCraft();
			this.broadcastFullState();
			return ItemStack.EMPTY;
		}
		if (ACTIVE_EDIT.get() != null) {
			return ItemStack.EMPTY;
		}
		var before = this.inventory.getData().snapshot();
		List<ItemStack> playerItems = new ArrayList<>();
		for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
			playerItems.add(player.getInventory().getItem(slot).copy());
		}
		ItemStack cursor = this.getCarried().copy();
		boolean committed = false;
		this.editing = true;
		this.dropFailed = false;
		this.inventory.beginEdit();
		ACTIVE_EDIT.set(this);
		try {
			ItemStack result = action.get();
			if (!this.dropFailed && this.inventory.commitEdit()) {
				committed = true;
				this.inventory.publishChanges();
				return result;
			}
			player.sendSystemMessage(Component.literal("TravelBag could not complete this transfer. Your items have been returned. Check the server log."));
			return ItemStack.EMPTY;
		} finally {
			try {
				if (!committed) {
					this.transactionDrops.forEach(ItemEntity::discard);
					this.inventory.getData().restore(before);
					this.inventory.refreshFromData();
					for (int slot = 0; slot < playerItems.size(); slot++) {
						player.getInventory().setItem(slot, playerItems.get(slot));
					}
					this.setCarried(cursor);
					this.resetQuickCraft();
				}
			} finally {
				this.transactionDrops.clear();
				ACTIVE_EDIT.remove();
				this.inventory.endEdit();
				this.editing = false;
				if (!committed) this.broadcastFullState();
			}
		}
	}


	@Override
	public void removed(Player player) {
		super.removed(player);
		this.inventory.stopOpen(player);
	}
}

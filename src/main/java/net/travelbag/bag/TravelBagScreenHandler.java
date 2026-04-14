package net.travelbag.bag;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

public final class TravelBagScreenHandler extends AbstractContainerMenu {
	private static final int BAG_COLUMNS = 9;
	private final TravelBagInventory inventory;
	private final int rows;

	public TravelBagScreenHandler(int syncId, Inventory playerInventory, TravelBagInventory inventory) {
		super(resolveType(inventory.getRows()), syncId);
		this.inventory = inventory;
		this.rows = inventory.getRows();
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
				this.addSlot(new Slot(playerInventory, column + row * 9 + 9, 8 + column * 18, inventoryY + row * 18));
			}
		}

		for (int column = 0; column < 9; column++) {
			this.addSlot(new Slot(playerInventory, column, 8 + column * 18, inventoryY + 58));
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
			} else if (!this.moveItemStackTo(originalStack, 0, bagSlots, false)) {
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
	public void removed(Player player) {
		super.removed(player);
		this.inventory.stopOpen(player);
	}
}

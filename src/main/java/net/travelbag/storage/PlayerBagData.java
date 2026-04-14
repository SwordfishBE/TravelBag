package net.travelbag.storage;

import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;

public final class PlayerBagData {
	private final NonNullList<ItemStack> stacks = NonNullList.withSize(54, ItemStack.EMPTY);
	private boolean dirty;
	private boolean shortcutGranted;

	public int size() {
		return this.stacks.size();
	}

	public ItemStack getStack(int slot) {
		return this.stacks.get(slot);
	}

	public void setStack(int slot, ItemStack stack) {
		this.stacks.set(slot, stack);
		this.dirty = true;
	}

	public void markDirty() {
		this.dirty = true;
	}

	public boolean isDirty() {
		return this.dirty;
	}

	public void clearDirty() {
		this.dirty = false;
	}

	public boolean isShortcutGranted() {
		return shortcutGranted;
	}

	public void setShortcutGranted(boolean shortcutGranted) {
		this.shortcutGranted = shortcutGranted;
		this.dirty = true;
	}

	public void clear() {
		for (int slot = 0; slot < this.stacks.size(); slot++) {
			this.stacks.set(slot, ItemStack.EMPTY);
		}
		this.dirty = true;
	}

	public boolean isEmpty() {
		for (ItemStack stack : this.stacks) {
			if (!stack.isEmpty()) {
				return false;
			}
		}
		return true;
	}
}

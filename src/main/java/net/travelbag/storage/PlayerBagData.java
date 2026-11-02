package net.travelbag.storage;

import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;

public final class PlayerBagData {
	public record Snapshot(java.util.List<ItemStack> stacks, boolean dirty, boolean shortcutGranted) {
	}

	public Snapshot snapshot() {
		return new Snapshot(this.stacks.stream().map(ItemStack::copy).toList(), this.dirty, this.shortcutGranted);
	}

	public void restore(Snapshot snapshot) {
		for (int slot = 0; slot < this.stacks.size(); slot++) {
			this.stacks.set(slot, snapshot.stacks().get(slot).copy());
		}
		this.dirty = snapshot.dirty();
		this.shortcutGranted = snapshot.shortcutGranted();
	}
	private final NonNullList<ItemStack> stacks = NonNullList.withSize(54, ItemStack.EMPTY);
	private boolean dirty;
	private boolean shortcutGranted;
	private boolean saveBlocked;
	private boolean legacyVersionChoiceRequired;
	private String saveBlockReason = "";

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

	public boolean isSaveBlocked() {
		return this.saveBlocked;
	}

	public String getSaveBlockReason() {
		return this.saveBlockReason;
	}

	public boolean isLegacyVersionChoiceRequired() {
		return this.legacyVersionChoiceRequired;
	}

	void requireLegacyVersionChoice() {
		this.legacyVersionChoiceRequired = true;
	}

	public void blockSaving(String reason) {
		this.saveBlocked = true;
		this.saveBlockReason = reason == null ? "" : reason;
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

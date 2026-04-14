package net.travelbag.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.core.NonNullList;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.travelbag.TravelBagMod;

@Mixin(AbstractContainerMenu.class)
abstract class ScreenHandlerMixin {
	@Shadow
	@Final
	public NonNullList<Slot> slots;

	@Shadow
	public abstract ItemStack getCarried();

	@Inject(method = "doClick", at = @At("HEAD"), cancellable = true)
	private void travelbag$preventShortcutTransfers(int slotIndex, int button, ContainerInput actionType, Player player, CallbackInfo ci) {
		Slot clickedSlot = slotIndex >= 0 && slotIndex < this.slots.size() ? this.slots.get(slotIndex) : null;
		ItemStack clickedStack = clickedSlot == null ? ItemStack.EMPTY : clickedSlot.getItem();
		ItemStack cursorStack = this.getCarried();
		ItemStack swapStack = actionType == ContainerInput.SWAP && button >= 0 && button < 9 ? player.getInventory().getItem(button) : ItemStack.EMPTY;

		boolean clickedShortcut = TravelBagMod.getInstance().getShortcutItemManager().isShortcutItem(clickedStack);
		boolean cursorShortcut = TravelBagMod.getInstance().getShortcutItemManager().isShortcutItem(cursorStack);
		boolean swapShortcut = TravelBagMod.getInstance().getShortcutItemManager().isShortcutItem(swapStack);
		boolean playerInventorySlot = clickedSlot != null && clickedSlot.container instanceof Inventory;

		if (!clickedShortcut && !cursorShortcut && !swapShortcut) {
			return;
		}

		if (actionType == ContainerInput.THROW || actionType == ContainerInput.QUICK_MOVE || actionType == ContainerInput.CLONE || actionType == ContainerInput.PICKUP_ALL) {
			ci.cancel();
			return;
		}

		if (actionType == ContainerInput.SWAP) {
			if (playerInventorySlot) {
				return;
			}
			if (swapShortcut || clickedShortcut) {
				ci.cancel();
			}
			return;
		}

		if (slotIndex < 0) {
			if (cursorShortcut && actionType != ContainerInput.QUICK_CRAFT) {
				ci.cancel();
			}
			return;
		}

		if (playerInventorySlot) {
			return;
		}

		if (cursorShortcut || clickedShortcut || swapShortcut) {
			ci.cancel();
		}
	}
}

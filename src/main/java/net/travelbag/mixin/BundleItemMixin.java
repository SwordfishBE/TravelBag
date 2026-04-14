package net.travelbag.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.world.entity.SlotAccess;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickAction;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.BundleItem;
import net.minecraft.world.item.ItemStack;
import net.travelbag.TravelBagMod;

@Mixin(BundleItem.class)
abstract class BundleItemMixin {
	@Inject(method = "overrideStackedOnOther", at = @At("HEAD"), cancellable = true)
	private void travelbag$preventShortcutIntoBundle(ItemStack bundleStack, Slot slot, ClickAction clickAction, Player player, CallbackInfoReturnable<Boolean> cir) {
		if (TravelBagMod.getInstance().getShortcutItemManager().isShortcutItem(slot.getItem())) {
			cir.setReturnValue(true);
		}
	}

	@Inject(method = "overrideOtherStackedOnMe", at = @At("HEAD"), cancellable = true)
	private void travelbag$preventShortcutCursorIntoBundle(ItemStack bundleStack, ItemStack otherStack, Slot slot, ClickAction clickAction, Player player, SlotAccess access, CallbackInfoReturnable<Boolean> cir) {
		if (TravelBagMod.getInstance().getShortcutItemManager().isShortcutItem(otherStack)) {
			cir.setReturnValue(true);
		}
	}
}

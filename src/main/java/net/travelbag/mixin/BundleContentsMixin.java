package net.travelbag.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BundleContents;
import net.travelbag.TravelBagMod;

@Mixin(BundleContents.class)
abstract class BundleContentsMixin {
	@Inject(method = "canItemBeInBundle", at = @At("HEAD"), cancellable = true)
	private static void travelbag$preventShortcutInBundle(ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
		if (TravelBagMod.getInstance() != null && TravelBagMod.getInstance().getShortcutItemManager().isShortcutItem(stack)) {
			cir.setReturnValue(false);
		}
	}
}

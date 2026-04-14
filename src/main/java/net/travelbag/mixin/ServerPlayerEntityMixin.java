package net.travelbag.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.server.level.ServerPlayer;
import net.travelbag.TravelBagMod;

@Mixin(ServerPlayer.class)
abstract class ServerPlayerEntityMixin {
	@Inject(method = "drop(Z)V", at = @At("HEAD"), cancellable = true)
	private void travelbag$preventShortcutDrop(boolean entireStack, CallbackInfo ci) {
		ServerPlayer player = (ServerPlayer) (Object) this;
		if (TravelBagMod.getInstance().getShortcutItemManager().isShortcutItem(player.getInventory().getSelectedItem())) {
			ci.cancel();
			TravelBagMod.getInstance().getShortcutItemManager().syncShortcutItem(player);
			player.inventoryMenu.sendAllDataToRemote();
			player.containerMenu.broadcastChanges();
		}
	}
}

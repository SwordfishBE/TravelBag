package net.travelbag.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.server.level.ServerPlayer;
import net.travelbag.TravelBagMod;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.util.Prediction;
import net.travelbag.bag.TravelBagScreenHandler;

@Mixin(ServerPlayer.class)
abstract class ServerPlayerEntityMixin {
	@WrapMethod(method = "drop(Lnet/minecraft/world/item/ItemStack;ZLnet/minecraft/util/Prediction;)Lnet/minecraft/world/entity/item/ItemEntity;")
	private ItemEntity travelbag$trackTransactionalDrop(ItemStack stack, boolean random, Prediction prediction, Operation<ItemEntity> original) {
		boolean hadItems = !stack.isEmpty();
		ItemEntity result = original.call(stack, random, prediction);
		TravelBagScreenHandler.recordDrop((ServerPlayer) (Object) this, result, hadItems);
		return result;
	}
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

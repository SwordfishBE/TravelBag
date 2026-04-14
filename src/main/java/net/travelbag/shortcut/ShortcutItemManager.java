package net.travelbag.shortcut;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import com.google.common.collect.ImmutableMultimap;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ResolvableProfile;
import net.travelbag.TravelBagMod;
import net.travelbag.storage.PlayerBagData;

public final class ShortcutItemManager {
	private static final String SHORTCUT_MARKER_KEY = "TravelBagShortcut";
	private final TravelBagMod mod;

	public ShortcutItemManager(TravelBagMod mod) {
		this.mod = mod;
	}

	public ItemStack createShortcutItem(ServerPlayer player) {
		ItemStack stack = new ItemStack(Items.PLAYER_HEAD);
		stack.set(DataComponents.CUSTOM_NAME, Component.literal(this.getShortcutDisplayName(player)).withStyle(ChatFormatting.YELLOW));

		CompoundTag customData = new CompoundTag();
		customData.putBoolean(SHORTCUT_MARKER_KEY, true);
		stack.set(DataComponents.CUSTOM_DATA, CustomData.of(customData));

		String textureValue = this.mod.getConfig().getHeadTextureValue();
		if (!textureValue.isBlank()) {
			PropertyMap properties = new PropertyMap(ImmutableMultimap.of("textures", new Property("textures", textureValue)));
			GameProfile profile = new GameProfile(UUID.nameUUIDFromBytes(("travelbag-shortcut" + textureValue).getBytes(StandardCharsets.UTF_8)), "TravelBag", properties);
			stack.set(DataComponents.PROFILE, ResolvableProfile.createResolved(profile));
		}

		return stack;
	}

	public boolean isShortcutItem(ItemStack stack) {
		if (stack.isEmpty() || !stack.is(Items.PLAYER_HEAD)) {
			return false;
		}

		CustomData component = stack.get(DataComponents.CUSTOM_DATA);
		return component != null && component.copyTag().getBoolean(SHORTCUT_MARKER_KEY).orElse(false);
	}

	public void syncShortcutItem(ServerPlayer player) {
		PlayerBagData data = this.mod.getStorage().getOrLoad(player.getUUID());
		this.sanitizeBundles(player);
		if (!this.mod.getConfig().isEnableShortcutItem()) {
			this.removeShortcutItems(player);
			return;
		}

		if (!this.mod.getPermissionService().canUse(player)) {
			this.removeShortcutItems(player);
			return;
		}

		int shortcutSlot = this.normalizeShortcutItems(player);
		if (shortcutSlot >= 0) {
			if (!data.isShortcutGranted()) {
				data.setShortcutGranted(true);
				this.mod.getStorage().save(player.getUUID());
			}
			return;
		}

		if (!data.isShortcutGranted()) {
			if (this.tryGiveShortcutItem(player)) {
				data.setShortcutGranted(true);
				this.mod.getStorage().save(player.getUUID());
			}
			return;
		}

		this.tryGiveShortcutItem(player);
	}

	public void openFromShortcut(ServerPlayer player, InteractionHand hand) {
		if (this.isShortcutItem(player.getItemInHand(hand))) {
			this.mod.openOwnBag(player);
		}
	}

	public boolean tryGiveShortcutItem(ServerPlayer player) {
		ItemStack shortcut = this.createShortcutItem(player);
		int preferredSlot = this.mod.getConfig().getShortcutPreferredSlot();
		ItemStack preferredStack = player.getInventory().getItem(preferredSlot);

		if (preferredStack.isEmpty()) {
			player.getInventory().setItem(preferredSlot, shortcut);
			return true;
		}

		ItemStack movablePreferred = preferredStack.copy();
		if (this.mod.canStoreItem(player.getUUID(), movablePreferred) && this.mod.tryStoreInBag(player.getUUID(), movablePreferred)) {
			player.getInventory().setItem(preferredSlot, shortcut);
			return true;
		}

		for (int slot = 0; slot < 36; slot++) {
			if (player.getInventory().getItem(slot).isEmpty()) {
				player.getInventory().setItem(slot, shortcut);
				return true;
			}
		}

		for (int slot = 0; slot < 36; slot++) {
			ItemStack stack = player.getInventory().getItem(slot);
			if (stack.isEmpty() || this.isShortcutItem(stack)) {
				continue;
			}

			ItemStack movable = stack.copy();
			if (!this.mod.canStoreItem(player.getUUID(), movable)) {
				continue;
			}

			if (this.mod.tryStoreInBag(player.getUUID(), movable) && movable.isEmpty()) {
				player.getInventory().setItem(slot, shortcut);
				return true;
			}
		}

		return false;
	}

	public int findShortcutSlot(ServerPlayer player) {
		for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
			if (this.isShortcutItem(player.getInventory().getItem(slot))) {
				return slot;
			}
		}
		return -1;
	}

	public void removeShortcutItems(ServerPlayer player) {
		for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
			if (this.isShortcutItem(player.getInventory().getItem(slot))) {
				player.getInventory().setItem(slot, ItemStack.EMPTY);
			}
		}
	}

	private int normalizeShortcutItems(ServerPlayer player) {
		ItemStack canonicalShortcut = this.createShortcutItem(player);
		int primarySlot = -1;

		for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
			ItemStack stack = player.getInventory().getItem(slot);
			if (!this.isShortcutItem(stack)) {
				continue;
			}

			if (primarySlot < 0) {
				primarySlot = slot;
				player.getInventory().setItem(slot, canonicalShortcut.copy());
			} else {
				player.getInventory().setItem(slot, ItemStack.EMPTY);
			}
		}

		return primarySlot;
	}

	private String getShortcutDisplayName(ServerPlayer player) {
		String playerName = player.getGameProfile().name();
		if (playerName == null || playerName.isBlank()) {
			return "TravelBag";
		}
		return playerName.endsWith("s") || playerName.endsWith("S") ? playerName + "' TravelBag" : playerName + "'s TravelBag";
	}

	private void sanitizeBundles(ServerPlayer player) {
		for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
			ItemStack stack = player.getInventory().getItem(slot);
			BundleContents contents = stack.get(DataComponents.BUNDLE_CONTENTS);
			if (contents == null || contents.isEmpty()) {
				continue;
			}

			BundleContents.Mutable mutable = new BundleContents.Mutable(contents);
			mutable.clearItems();
			boolean removedShortcut = false;

			for (ItemStack bundledStack : contents.itemCopyStream().toList()) {
				if (this.isShortcutItem(bundledStack)) {
					removedShortcut = true;
					continue;
				}
				mutable.tryInsert(bundledStack.copy());
			}

			if (removedShortcut) {
				stack.set(DataComponents.BUNDLE_CONTENTS, mutable.toImmutable());
			}
		}
	}
}

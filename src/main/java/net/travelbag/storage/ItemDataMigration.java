package net.travelbag.storage;

import java.io.IOException;

import com.mojang.serialization.Dynamic;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.References;

final class ItemDataMigration {
    static final String VERSION_KEY = "MinecraftDataVersion";
    static final int LEGACY_26_3_VERSION = 5023;

    private ItemDataMigration() {
    }

    static final class ChoiceRequiredException extends IOException {
        ChoiceRequiredException() {
            super("Missing Minecraft data version and ambiguous item sounds. Use /travelbag upgrade with legacy or current after reviewing the original file.");
        }
    }

    static int currentVersion() {
        return SharedConstants.getCurrentVersion().dataVersion().version();
    }

    static Integer readVersion(CompoundTag root) throws IOException {
        if (!root.contains(VERSION_KEY)) {
            if (hasAmbiguousBagItems(root)) {
                throw new ChoiceRequiredException();
            }
            return null;
        }
        int version = root.get(VERSION_KEY) instanceof IntTag integer ? integer.intValue() : -1;
        if (version < 0 || version > currentVersion()) {
            throw new IOException("Invalid or future Minecraft data version: " + version);
        }
        return version;
    }

    static CompoundTag upgradeStack(CompoundTag stack, Integer version) throws IOException {
        if (version == null || version == currentVersion()) {
            return stack;
        }
        Tag upgraded = DataFixers.getDataFixer().update(References.ITEM_STACK,
            new Dynamic<Tag>(NbtOps.INSTANCE, stack), version, currentVersion()).getValue();
        if (!(upgraded instanceof CompoundTag compound)) {
            throw new IOException("Minecraft item migration did not return a compound tag.");
        }
        return compound;
    }

    private static boolean hasAmbiguousBagItems(CompoundTag root) {
        ListTag items = root.getList("Items").orElse(null);
        if (items == null) return false;
        for (Tag entry : items) {
            if (entry instanceof CompoundTag item && hasAmbiguousStack(item.getCompound("Stack").orElse(null))) return true;
        }
        return false;
    }

    private static boolean hasAmbiguousStack(CompoundTag stack) {
        if (stack == null) return false;
        CompoundTag components = stack.getCompound("components").orElse(null);
        if (components == null) return false;
        CompoundTag consumable = components.getCompound("minecraft:consumable").orElse(null);
        CompoundTag equippable = components.getCompound("minecraft:equippable").orElse(null);
        if (consumable != null && !consumable.contains("sound")
            || equippable != null && (!equippable.contains("equip_sound") || !equippable.contains("shearing_sound"))) {
            return true;
        }
        // Only known item-bearing component fields are stacks; arbitrary custom data stays opaque.
        ListTag container = components.getList("minecraft:container").orElse(null);
        if (container != null) {
            for (Tag entry : container) {
                if (entry instanceof CompoundTag item && hasAmbiguousStack(item.getCompound("item").orElse(null))) return true;
            }
        }
        for (String key : java.util.List.of("minecraft:bundle_contents", "minecraft:charged_projectiles")) {
            ListTag contents = components.getList(key).orElse(null);
            if (contents != null) {
                for (Tag entry : contents) {
                    if (entry instanceof CompoundTag item && hasAmbiguousStack(item)) return true;
                }
            }
        }
        return hasAmbiguousStack(components.getCompound("minecraft:use_remainder").orElse(null));
    }
}

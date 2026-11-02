package net.travelbag;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.mojang.authlib.GameProfile;
import me.lucko.fabric.api.permissions.v0.PermissionCheckEvent;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.fabric.api.util.TriState;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.travelbag.bag.TravelBagInventory;
import net.travelbag.bag.TravelBagScreenHandler;
import net.travelbag.storage.TravelBagStorage;

public class TravelBagFollowupTests {
    @GameTest
    public void adminCanInspectOwnerWithoutUsePermissionWhileRevocationStillApplies(GameTestHelper helper) {
        var mod = TravelBagMod.getInstance();
        var viewer = helper.makeMockServerPlayerInLevel();
        UUID owner = UUID.randomUUID();
        AtomicReference<UUID> actor = new AtomicReference<>(viewer.getUUID());
        AtomicBoolean admin = new AtomicBoolean(true);
        PermissionCheckEvent.EVENT.register((source, permission) -> {
            if (source instanceof CommandSourceStack command && command.getEntity() != null
                && command.getEntity().getUUID().equals(actor.get()) && permission.equals("travelbag.admin")) {
                return TriState.of(admin.get());
            }
            return TriState.DEFAULT;
        });
        boolean previous = mod.getConfig().isLuckPerms();
        try {
            mod.getConfig().setLuckPerms(true);
            var data = mod.getStorage().getOrLoad(owner);
            data.setStack(0, new ItemStack(Items.DIAMOND, 4));
            helper.assertTrue(mod.getStorage().save(owner), "Baseline must save");
            helper.assertTrue(mod.getPermissionService().canOpenOthers(viewer) && mod.getRowsFor(owner) == 0,
                "Admin may inspect a target without a use grant");
            mod.openBag(viewer, new GameProfile(owner, "review-owner"), true, true);
            helper.assertTrue(viewer.containerMenu instanceof TravelBagScreenHandler, "Admin can open the menu");
            var menu = (TravelBagScreenHandler) viewer.containerMenu;
            helper.assertTrue(menu.getTravelBagInventory().getRows() == 1 && menu.stillValid(viewer),
                "Minimum inspection rows must match both construction and validation");
            menu.quickMoveStack(viewer, 0);
            helper.assertTrue(viewer.getInventory().countItem(Items.DIAMOND) == 4 && data.isEmpty(),
                "Admin editing must persist a transfer exactly once");
            admin.set(false);
            helper.assertTrue(!menu.stillValid(viewer), "Revoking inspection permission must still invalidate the menu");
            var own = new TravelBagInventory(mod, viewer.getUUID(), "Self", mod.getStorage().getOrLoad(viewer.getUUID()), 1, true);
            helper.assertTrue(!own.stillValid(viewer), "The inspection clamp must not grant owner access without use permission");
        } finally {
            viewer.closeContainer();
            mod.getConfig().setLuckPerms(previous);
            actor.set(null);
        }
        helper.succeed();
    }

    @GameTest
    public void arbitraryCustomDataStaysOpaqueAndRoundTrips(GameTestHelper helper) throws Exception {
        Path directory = Files.createTempDirectory(helper.getLevel().getServer().getServerDirectory(), "travelbag-custom-data-");
        var storage = new TravelBagStorage(directory);
        storage.prepare(helper.getLevel().getServer());
        try {
            CompoundTag fakeComponents = new CompoundTag();
            fakeComponents.put("minecraft:consumable", new CompoundTag());
            CompoundTag metadata = new CompoundTag();
            metadata.put("components", fakeComponents);
            metadata.put("item", ambiguousApple());
            CompoundTag components = new CompoundTag();
            components.put("minecraft:custom_data", metadata);
            CompoundTag stack = stack("minecraft:diamond", components);
            var parsed = ItemStack.OPTIONAL_CODEC.parse(helper.getLevel().registryAccess().createSerializationContext(NbtOps.INSTANCE), stack);
            helper.assertTrue(parsed.result().isPresent(), "Fixture must be a valid diamond with arbitrary metadata");
            UUID uuid = UUID.randomUUID();
            Path source = writeBag(directory, uuid, stack);
            byte[] original = Files.readAllBytes(source);
            var data = storage.getOrLoad(uuid);
            helper.assertTrue(!data.isSaveBlocked() && !data.isLegacyVersionChoiceRequired()
                && data.getStack(0).is(Items.DIAMOND), "Custom data must not cause a false whole-bag lock");
            helper.assertTrue(data.getStack(0).get(DataComponents.CONSUMABLE) == null
                && data.getStack(0).get(DataComponents.CUSTOM_DATA).copyTag().equals(metadata), "Opaque metadata must not become actual item components");
            helper.assertTrue(java.util.Arrays.equals(original, Files.readAllBytes(source)), "Loading must not rewrite legacy data");
            data.markDirty();
            helper.assertTrue(storage.save(uuid), "The unchanged item must save");
            storage.close();
            storage.prepare(helper.getLevel().getServer());
            helper.assertTrue(storage.getOrLoad(uuid).getStack(0).get(DataComponents.CUSTOM_DATA).copyTag().equals(metadata),
                "Every custom-data field must survive save and reopen");
        } finally {
            storage.close();
            remove(directory);
        }
        helper.succeed();
    }

    @GameTest
    public void realNestedItemsStillRequireLegacySoundChoice(GameTestHelper helper) throws Exception {
        Path directory = Files.createTempDirectory(helper.getLevel().getServer().getServerDirectory(), "travelbag-nested-sounds-");
        var storage = new TravelBagStorage(directory);
        storage.prepare(helper.getLevel().getServer());
        try {
            for (String component : java.util.List.of("minecraft:container", "minecraft:bundle_contents",
                "minecraft:charged_projectiles", "minecraft:use_remainder")) {
                CompoundTag components = new CompoundTag();
                String itemId;
                if (component.equals("minecraft:use_remainder")) {
                    components.put(component, ambiguousApple());
                    itemId = "minecraft:milk_bucket";
                } else {
                    ListTag contents = new ListTag();
                    if (component.equals("minecraft:container")) {
                        CompoundTag entry = new CompoundTag();
                        entry.putInt("slot", 0);
                        entry.put("item", ambiguousApple());
                        contents.add(entry);
                        itemId = "minecraft:shulker_box";
                    } else {
                        contents.add(ambiguousApple());
                        itemId = component.equals("minecraft:bundle_contents") ? "minecraft:bundle" : "minecraft:crossbow";
                    }
                    components.put(component, contents);
                }
                CompoundTag outer = stack(itemId, components);
                helper.assertTrue(ItemStack.OPTIONAL_CODEC.parse(helper.getLevel().registryAccess().createSerializationContext(NbtOps.INSTANCE), outer)
                    .result().isPresent(), "Nested fixture must match the actual component codec: " + component);
                UUID uuid = UUID.randomUUID();
                Path source = writeBag(directory, uuid, outer);
                byte[] original = Files.readAllBytes(source);
                helper.assertTrue(storage.getOrLoad(uuid).isSaveBlocked() && storage.getOrLoad(uuid).isLegacyVersionChoiceRequired(),
                    "Real nested ambiguity must still require a choice: " + component);
                helper.assertTrue(java.util.Arrays.equals(original, Files.readAllBytes(source)), "Ambiguous nested data must stay unchanged");
            }
        } finally {
            storage.close();
            remove(directory);
        }
        helper.succeed();
    }

    private static CompoundTag ambiguousApple() {
        CompoundTag consumable = new CompoundTag();
        consumable.putFloat("consume_seconds", 3.0F);
        CompoundTag components = new CompoundTag();
        components.put("minecraft:consumable", consumable);
        return stack("minecraft:apple", components);
    }

    private static CompoundTag stack(String id, CompoundTag components) {
        CompoundTag stack = new CompoundTag();
        stack.putString("id", id);
        stack.putInt("count", 1);
        stack.put("components", components);
        return stack;
    }

    private static Path writeBag(Path directory, UUID uuid, CompoundTag stack) throws IOException {
        CompoundTag entry = new CompoundTag();
        entry.putInt("Slot", 0);
        entry.put("Stack", stack);
        ListTag items = new ListTag();
        items.add(entry);
        CompoundTag root = new CompoundTag();
        root.putInt("DataVersion", 1);
        root.put("Items", items);
        Path path = directory.resolve(uuid + ".dat");
        try (var output = Files.newOutputStream(path)) {
            NbtIo.writeCompressed(root, output);
        }
        return path;
    }

    private static void remove(Path directory) throws IOException {
        java.util.List<Path> paths;
        try (var files = Files.walk(directory)) {
            paths = files.sorted(Comparator.reverseOrder()).toList();
        }
        for (Path path : paths) Files.delete(path);
    }
}

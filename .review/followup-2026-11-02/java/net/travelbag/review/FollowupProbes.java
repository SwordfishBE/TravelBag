package net.travelbag.review;

import java.nio.file.Files;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.mojang.authlib.GameProfile;
import me.lucko.fabric.api.permissions.v0.PermissionCheckEvent;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.fabric.api.util.TriState;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.world.item.ItemStack;
import net.travelbag.TravelBagMod;
import net.travelbag.bag.TravelBagScreenHandler;
import net.travelbag.storage.TravelBagStorage;

public class FollowupProbes {
    @GameTest
    public void reproduceAdminMenuInvalidForOwnerWithoutUsePermission(GameTestHelper helper) {
        var mod = TravelBagMod.getInstance();
        var viewer = helper.makeMockServerPlayerInLevel();
        UUID owner = UUID.randomUUID();
        AtomicReference<UUID> admin = new AtomicReference<>(viewer.getUUID());
        PermissionCheckEvent.EVENT.register((source, permission) -> {
            if (source instanceof CommandSourceStack command && command.getEntity() != null
                && command.getEntity().getUUID().equals(admin.get()) && permission.equals("travelbag.admin")) {
                return TriState.TRUE;
            }
            return TriState.DEFAULT;
        });
        boolean previous = mod.getConfig().isLuckPerms();
        try {
            mod.getConfig().setLuckPerms(true);
            helper.assertTrue(mod.getPermissionService().canOpenOthers(viewer) && mod.getRowsFor(owner) == 0,
                "Admin can inspect others while this target has no use/size grant");
            mod.openBag(viewer, new GameProfile(owner, "review-owner"), true, true);
            helper.assertTrue(viewer.containerMenu instanceof TravelBagScreenHandler, "Admin command creates a bag menu");
            var menu = (TravelBagScreenHandler) viewer.containerMenu;
            helper.assertTrue(menu.getTravelBagInventory().getRows() == 1 && !menu.stillValid(viewer),
                "REPRODUCED: constructor clamps zero rows to one but validity compares against zero");
            TravelBagMod.LOGGER.info("[Follow-up] REPRODUCED admin menu is immediately invalid when owner lacks travelbag.use.");
        } finally {
            viewer.closeContainer();
            mod.getConfig().setLuckPerms(previous);
            admin.set(null);
        }
        helper.succeed();
    }

    @GameTest
    public void reproduceCustomDataMistakenForActualItemComponents(GameTestHelper helper) throws Exception {
        var directory = Files.createTempDirectory(helper.getLevel().getServer().getServerDirectory(), "travelbag-followup-");
        var storage = new TravelBagStorage(directory);
        storage.prepare(helper.getLevel().getServer());
        try {
            CompoundTag unrelated = new CompoundTag();
            unrelated.put("minecraft:consumable", new CompoundTag());
            CompoundTag customData = new CompoundTag();
            customData.put("components", unrelated);
            CompoundTag actualComponents = new CompoundTag();
            actualComponents.put("minecraft:custom_data", customData);
            CompoundTag stack = new CompoundTag();
            stack.putString("id", "minecraft:diamond");
            stack.putInt("count", 1);
            stack.put("components", actualComponents);
            var parsed = ItemStack.OPTIONAL_CODEC.parse(helper.getLevel().registryAccess().createSerializationContext(NbtOps.INSTANCE), stack);
            helper.assertTrue(parsed.result().isPresent(), "This is a valid ordinary diamond with arbitrary custom metadata");
            CompoundTag entry = new CompoundTag();
            entry.putInt("Slot", 0);
            entry.put("Stack", stack);
            ListTag items = new ListTag();
            items.add(entry);
            CompoundTag root = new CompoundTag();
            root.putInt("DataVersion", 1);
            root.put("Items", items);
            UUID uuid = UUID.randomUUID();
            var path = directory.resolve(uuid + ".dat");
            try (var output = Files.newOutputStream(path)) {
                NbtIo.writeCompressed(root, output);
            }
            helper.assertTrue(storage.getOrLoad(uuid).isSaveBlocked()
                && storage.getOrLoad(uuid).isLegacyVersionChoiceRequired(),
                "REPRODUCED: unrelated custom metadata causes the entire valid legacy bag to be locked");
            TravelBagMod.LOGGER.info("[Follow-up] REPRODUCED unrelated custom_data components key triggers false ambiguous-sounds lock.");
        } finally {
            storage.close();
            java.util.List<java.nio.file.Path> paths;
            try (var files = Files.walk(directory)) {
                paths = files.sorted(Comparator.reverseOrder()).toList();
            }
            for (var path : paths) Files.delete(path);
        }
        helper.succeed();
    }
}

package net.travelbag.review;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.travelbag.TravelBagMod;
import net.travelbag.bag.TravelBagInventory;
import net.travelbag.bag.TravelBagScreenHandler;
import net.travelbag.command.TravelBagCommands;
import net.travelbag.config.TravelBagConfig;
import net.travelbag.storage.TravelBagStorage;

public class ReviewProbes {
    @GameTest
    public void reproduceWithdrawalDuplicationAfterPersistentSaveFailure(GameTestHelper helper) throws Exception {
        var mod = TravelBagMod.getInstance();
        var server = helper.getLevel().getServer();
        Path directory = Files.createTempDirectory(server.getServerDirectory(), "travelbag-review-io-");
        var originalStorage = mod.getStorage();
        var storageField = TravelBagMod.class.getDeclaredField("storage");
        storageField.setAccessible(true);
        var isolated = new TravelBagStorage(directory);
        isolated.prepare(server);
        storageField.set(mod, isolated);
        TravelBagScreenHandler menu = null;
        try {
            var player = helper.makeMockServerPlayerInLevel();
            var data = isolated.getOrLoad(player.getUUID());
            data.setStack(0, new ItemStack(Items.DIAMOND, 10));
            helper.assertTrue(isolated.saveAll(), "Initial bag must save successfully");
            Path obstruction = directory.resolve(player.getUUID() + ".dat.tmp");
            Files.createDirectory(obstruction);
            Files.writeString(obstruction.resolve("keep"), "Deliberate persistent I/O failure");
            menu = new TravelBagScreenHandler(1, player.getInventory(),
                new TravelBagInventory(mod, player.getUUID(), "Review", data, 6, true));
            menu.quickMoveStack(player, 0);
            helper.assertTrue(player.getInventory().countItem(Items.DIAMOND) == 10 && data.isEmpty() && data.isDirty(),
                "Withdrawal succeeds although persistence fails");
            menu.removed(player);
            menu = null;
            isolated.close();
            isolated.prepare(server);
            helper.assertTrue(player.getInventory().countItem(Items.DIAMOND) == 10
                && isolated.getOrLoad(player.getUUID()).getStack(0).getCount() == 10,
                "REPRODUCED: withdrawn items remain in inventory and reappear in bag after reopening");
            TravelBagMod.LOGGER.info("[Review] REPRODUCED persistent save failure: ten withdrawn diamonds plus ten reloaded diamonds.");
        } finally {
            if (menu != null) menu.removed(null);
            isolated.close();
            storageField.set(mod, originalStorage);
            remove(directory);
        }
        helper.succeed();
    }

    @GameTest
    public void reproduceMissingConsumableSoundInLegacyComponent(GameTestHelper helper) throws Exception {
        Path directory = Files.createTempDirectory(helper.getLevel().getServer().getServerDirectory(), "travelbag-review-codec-");
        var storage = new TravelBagStorage(directory);
        storage.prepare(helper.getLevel().getServer());
        UUID owner = UUID.randomUUID();
        try {
            // Valid pre-26.4 component layout: omitted sound means generic eating sound.
            CompoundTag consumable = new CompoundTag();
            consumable.putFloat("consume_seconds", 3.0F);
            CompoundTag components = new CompoundTag();
            components.put("minecraft:consumable", consumable);
            CompoundTag stack = new CompoundTag();
            stack.putString("id", "minecraft:apple");
            stack.putInt("count", 1);
            stack.put("components", components);
            CompoundTag item = new CompoundTag();
            item.putInt("Slot", 0);
            item.put("Stack", stack);
            ListTag items = new ListTag();
            items.add(item);
            CompoundTag root = new CompoundTag();
            root.putInt("DataVersion", 1);
            root.put("Items", items);
            try (var output = Files.newOutputStream(directory.resolve(owner + ".dat"))) {
                NbtIo.writeCompressed(root, output);
            }
            var bag = storage.getOrLoad(owner);
            var loaded = bag.getStack(0).get(DataComponents.CONSUMABLE);
            helper.assertTrue(!bag.isSaveBlocked() && loaded != null && loaded.sound().isEmpty(),
                "REPRODUCED: old valid consumable component loads with no eating sound");
            bag.markDirty();
            helper.assertTrue(storage.saveAll(), "The changed component is accepted as a valid save");
            TravelBagMod.LOGGER.info("[Review] REPRODUCED old consumable component loses implicit default eating sound.");
        } finally {
            storage.close();
            remove(directory);
        }
        helper.succeed();
    }

    @GameTest
    public void reproduceOpenMenuKeepsRevokedRows(GameTestHelper helper) {
        var mod = TravelBagMod.getInstance();
        var player = helper.makeMockServerPlayerInLevel();
        var data = mod.getStorage().getOrLoad(player.getUUID());
        data.setStack(53, new ItemStack(Items.DIAMOND));
        var menu = new TravelBagScreenHandler(1, player.getInventory(),
            new TravelBagInventory(mod, player.getUUID(), "Review", data, 6, true));
        int oldRows = mod.getConfig().getSize();
        try {
            mod.getConfig().setSize(1);
            helper.assertTrue(mod.getRowsFor(player) == 1 && menu.stillValid(player), "Old six-row menu remains valid after downgrade");
            menu.quickMoveStack(player, 53);
            helper.assertTrue(player.getInventory().countItem(Items.DIAMOND) == 1,
                "REPRODUCED: last-row item remains withdrawable with only one allowed row");
            TravelBagMod.LOGGER.info("[Review] REPRODUCED open six-row menu ignores downgrade to one row.");
        } finally {
            mod.getConfig().setSize(oldRows);
            menu.removed(player);
        }
        helper.succeed();
    }

    @GameTest
    public void reproduceOnlineOwnerRestoreRejection(GameTestHelper helper) throws Exception {
        var mod = TravelBagMod.getInstance();
        var server = helper.getLevel().getServer();
        var player = helper.makeMockServerPlayerInLevel();
        var previousOwner = server.getSingleplayerProfile();
        var canRestore = TravelBagCommands.class.getDeclaredMethod("canRestoreTarget", TravelBagMod.class,
            net.minecraft.commands.CommandSourceStack.class, NameAndId.class);
        canRestore.setAccessible(true);
        try {
            server.setSingleplayerProfile(player.getGameProfile());
            helper.assertTrue(mod.canMigrateLegacyBag(player), "Test must emulate an integrated world owner");
            mod.getStorage().getOrLoad(player.getUUID()).blockSaving("Deliberate review lock");
            boolean permitted = (boolean) canRestore.invoke(null, mod, player.createCommandSourceStack(),
                new NameAndId(player.getUUID(), player.getGameProfile().name()));
            helper.assertTrue(!permitted, "REPRODUCED: singleplayer owner cannot satisfy offline restore requirement");
            TravelBagMod.LOGGER.info("[Review] REPRODUCED locked integrated owner cannot use restore while world is running.");
        } finally {
            server.setSingleplayerProfile(previousOwner);
        }
        helper.succeed();
    }

    @GameTest
    public void reproduceNonFinitePickupRadiusSurvivesSanitize(GameTestHelper helper) {
        for (double value : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.MAX_VALUE}) {
            var config = new TravelBagConfig();
            config.setCollectRadius(value);
            config.sanitize();
            helper.assertTrue(config.getCollectRadius() == value || Double.isNaN(value) && Double.isNaN(config.getCollectRadius()),
                "REPRODUCED: unsafe pickup radius survives sanitization");
        }
        TravelBagMod.LOGGER.info("[Review] REPRODUCED NaN, infinity and extreme pickup radii survive sanitization; no huge entity query executed.");
        helper.succeed();
    }

    private static void remove(Path directory) throws Exception {
        java.util.List<Path> paths;
        try (var files = Files.walk(directory)) {
            paths = files.sorted(Comparator.reverseOrder()).toList();
        }
        for (Path path : paths) Files.delete(path);
    }
}

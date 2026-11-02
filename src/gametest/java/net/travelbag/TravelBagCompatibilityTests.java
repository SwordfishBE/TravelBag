package net.travelbag;

import java.nio.file.Files;
import java.util.List;
import java.util.UUID;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.impl.game.minecraft.McVersionLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.phys.Vec3;
import net.travelbag.bag.TravelBagInventory;
import net.travelbag.bag.TravelBagScreenHandler;
import net.travelbag.pickup.TravelBagPickupService;
import net.travelbag.storage.PlayerBagData;
import net.travelbag.storage.TravelBagStorage;
import net.travelbag.util.ModrinthUpdateChecker;

public class TravelBagCompatibilityTests {
    @GameTest
    public void minecraftVersionRangeMatchesSnapshotSixThroughFinal(GameTestHelper helper) throws Exception {
        var dependency = FabricLoader.getInstance().getModContainer("travelbag").orElseThrow().getMetadata()
            .getDependencies().stream().filter(value -> value.getModId().equals("minecraft")).findFirst().orElseThrow();
        for (String accepted : List.of("26.4-snapshot-6", "26.4-snapshot-7", "26.4-snapshot-10", "26.4-pre-1", "26.4-rc-1", "26.4")) {
            helper.assertTrue(dependency.matches(normalize(accepted)), "Supported Minecraft version: " + accepted);
        }
        for (String rejected : List.of("26.3", "26.4-snapshot-1", "26.4-snapshot-5", "26.4.1-pre-1", "26.4.1", "26.5-snapshot-1", "26.5")) {
            helper.assertTrue(!dependency.matches(normalize(rejected)), "Unsupported Minecraft version: " + rejected);
        }
        helper.succeed();
    }

    @GameTest
    public void updateCheckerUsesRawSnapshotIdAndCompatibleReleases(GameTestHelper helper) throws Exception {
        var current = ModrinthUpdateChecker.class.getDeclaredMethod("currentMinecraftVersion");
        current.setAccessible(true);
        helper.assertTrue("26.4-snapshot-6".equals(current.invoke(null)), "Modrinth requires the raw Minecraft snapshot ID");
        var extract = ModrinthUpdateChecker.class.getDeclaredMethod("extractLatestVersion", String.class, String.class);
        extract.setAccessible(true);
        String incompatible = """
            [{"version_number":"99.0.0","date_published":"2026-10-30T00:00:00Z","version_type":"release",
              "loaders":["fabric"],"game_versions":["26.3"]},
             {"version_number":"98.0.0","date_published":"2026-10-30T00:00:00Z","version_type":"release",
              "loaders":["neoforge"],"game_versions":["26.4-snapshot-6"]}]
            """;
        var noMatch = (java.util.Optional<?>) extract.invoke(null, incompatible, "26.4-snapshot-6");
        helper.assertTrue(noMatch.isEmpty(), "Do not recommend releases for another version or loader");
        String compatible = incompatible.substring(0, incompatible.lastIndexOf(']')) + """
            ,{"version_number":"1.1.1","date_published":"2026-10-29T00:00:00Z","version_type":"release",
              "loaders":["fabric"],"game_versions":["26.4-snapshot-6"]}]
            """;
        var match = (java.util.Optional<?>) extract.invoke(null, compatible, "26.4-snapshot-6");
        helper.assertTrue(match.isPresent() && match.get().toString().contains("versionNumber=1.1.1"),
            "Recommend the compatible Fabric release even when incompatible releases are newer");
        helper.succeed();
    }

    @GameTest
    public void previousReleaseBagAndUnversionedFormatRemainReadable(GameTestHelper helper) throws Exception {
        var directory = Files.createTempDirectory(helper.getLevel().getServer().getServerDirectory(), "travelbag-legacy-");
        var storage = new TravelBagStorage(directory);
        storage.prepare(helper.getLevel().getServer());
        try {
            UUID original = UUID.randomUUID();
            var path = directory.resolve(original + ".dat");
            try (var input = TravelBagCompatibilityTests.class.getResourceAsStream("/fixtures/travelbag-1.0.4-mc26.3.dat")) {
                if (input == null) throw new IllegalStateException("Missing historical fixture");
                Files.copy(input, path);
            }
            var oldBag = storage.getOrLoad(original);
            helper.assertTrue(!oldBag.isSaveBlocked() && count(oldBag, Items.DIAMOND) == 10,
                "Actual TravelBag 1.0.4 / Minecraft 26.3 data must retain ten diamonds");
            net.minecraft.nbt.CompoundTag root;
            try (var input = Files.newInputStream(path)) {
                root = net.minecraft.nbt.NbtIo.readCompressed(input, net.minecraft.nbt.NbtAccounter.unlimitedHeap());
            }
            root.remove("DataVersion");
            UUID unversioned = UUID.randomUUID();
            try (var output = Files.newOutputStream(directory.resolve(unversioned + ".dat"))) {
                net.minecraft.nbt.NbtIo.writeCompressed(root, output);
            }
            var unversionedBag = storage.getOrLoad(unversioned);
            helper.assertTrue(!unversionedBag.isSaveBlocked() && count(unversionedBag, Items.DIAMOND) == 10,
                "Legacy format without DataVersion must retain the same contents");
            oldBag.markDirty();
            helper.assertTrue(storage.saveAll(), "Imported old bag must save safely on the new version");
            storage.close();
            storage.prepare(helper.getLevel().getServer());
            helper.assertTrue(count(storage.getOrLoad(original), Items.DIAMOND) == 10,
                "Imported items must remain after resaving and reopening");
        } finally {
            storage.close();
            try (var files = Files.list(directory)) {
                for (var path : files.toList()) Files.delete(path);
            }
            Files.delete(directory);
        }
        helper.succeed();
    }

    private static Version normalize(String version) throws Exception {
        return Version.parse(McVersionLookup.normalizeVersion(version, McVersionLookup.getRelease(version)));
    }

    @GameTest
    public void expectedRuntimeIsLoaded(GameTestHelper helper) {
        var moonrise = FabricLoader.getInstance().getModContainer("moonrise");
        boolean expected = Boolean.getBoolean("travelbag.compat.moonrise");
        helper.assertTrue(moonrise.isPresent() == expected, "The requested baseline/Moonrise runtime must actually be loaded");
        if (expected) {
            helper.assertTrue(moonrise.orElseThrow().getMetadata().getVersion().getFriendlyString().equals("1.2.1+e53de7f"),
                "Moonrise version must match the verified artifact");
        }
        helper.assertTrue(!FabricLoader.getInstance().isModLoaded("c2me"), "C2ME must not be included in this run");
        helper.succeed();
    }

    @GameTest
    public void enchantedStorageBackupRestoreAndReopen(GameTestHelper helper) throws Exception {
        var directory = Files.createTempDirectory(helper.getLevel().getServer().getServerDirectory(), "travelbag-compat-");
        var storage = new TravelBagStorage(directory);
        var uuid = UUID.randomUUID();
        storage.prepare(helper.getLevel().getServer());
        try {
            ItemStack pickaxe = new ItemStack(Items.DIAMOND_PICKAXE);
            pickaxe.enchant(helper.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT)
                .getOrThrow(Enchantments.SILK_TOUCH), 1);
            PlayerBagData data = storage.getOrLoad(uuid);
            data.setStack(53, pickaxe);
            data.markDirty();
            helper.assertTrue(storage.saveAll(), "Enchanted bag must save completely");
            helper.assertTrue(storage.createBackups(), "First backup must succeed");
            data.setStack(0, new ItemStack(Items.DIAMOND, 7));
            data.markDirty();
            helper.assertTrue(storage.saveAll() && storage.createBackups(), "Second backup must succeed");
            helper.assertTrue(storage.inspectBackup(uuid, 1).itemCount() == 8, "Latest backup must contain all eight items");
            helper.assertTrue(storage.inspectBackup(uuid, 2).itemCount() == 1, "Previous backup must retain the enchanted tool");
            data.blockSaving("Compatibility test recovery");
            var preview = storage.inspectBackup(uuid, 1);
            storage.restoreBackup(uuid, 1, preview.modifiedAtMillis());
            helper.assertTrue(!storage.getOrLoad(uuid).isSaveBlocked(), "Successful restore must unlock the bag");
            storage.close();
            storage.prepare(helper.getLevel().getServer());
            var reopened = storage.getOrLoad(uuid);
            helper.assertTrue(!reopened.isSaveBlocked() && reopened.getStack(0).getCount() == 7,
                "Reopened bag must retain its normal stack");
            helper.assertTrue(ItemStack.isSameItemSameComponents(reopened.getStack(53), pickaxe),
                "Last-slot enchanted item must round-trip without component loss");
        } finally {
            storage.close();
            try (var files = Files.list(directory)) {
                for (var path : files.toList()) Files.delete(path);
            }
            Files.delete(directory);
        }
        helper.succeed();
    }

    @GameTest
    public void shiftClickAndReadOnlyProtection(GameTestHelper helper) {
        var mod = TravelBagMod.getInstance();
        var player = helper.makeMockServerPlayerInLevel();
        player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        var data = mod.getStorage().getOrLoad(player.getUUID());
        data.setStack(0, new ItemStack(Items.DIAMOND, 10));
        player.getInventory().setItem(9, new ItemStack(Items.DIAMOND, 5));
        var readOnly = menu(mod, player, data, false);
        try {
            readOnly.quickMoveStack(player, 54);
            helper.assertTrue(data.getStack(0).getCount() == 10 && player.getInventory().getItem(9).getCount() == 5,
                "Read-only bag must reject shift-click merges");
        } finally {
            readOnly.removed(player);
        }
        var editable = menu(mod, player, data, true);
        try {
            editable.quickMoveStack(player, 54);
            helper.assertTrue(data.getStack(0).getCount() == 15 && player.getInventory().getItem(9).isEmpty(),
                "Editable bag must accept exactly five diamonds");
            editable.quickMoveStack(player, 0);
            helper.assertTrue(data.getStack(0).isEmpty() && player.getInventory().countItem(Items.DIAMOND) == 15,
                "Withdrawal must conserve the exact item count");
        } finally {
            editable.removed(player);
        }
        helper.succeed();
    }

    @GameTest
    public void blacklistPreventsOccupiedSlotMerge(GameTestHelper helper) {
        var mod = TravelBagMod.getInstance();
        var config = mod.getConfig();
        boolean enabled = config.isItemFilterEnabled();
        List<String> filtered = config.getFilteredMaterials();
        var player = helper.makeMockServerPlayerInLevel();
        player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        var data = mod.getStorage().getOrLoad(player.getUUID());
        data.setStack(0, new ItemStack(Items.DIAMOND, 10));
        player.getInventory().setItem(9, new ItemStack(Items.DIAMOND, 5));
        var menu = menu(mod, player, data, true);
        try {
            config.setItemFilterEnabled(true);
            config.setFilteredMaterials(List.of("minecraft:diamond"));
            menu.quickMoveStack(player, 54);
            helper.assertTrue(data.getStack(0).getCount() == 10 && player.getInventory().getItem(9).getCount() == 5,
                "Blacklisted items must not merge into existing stacks");
        } finally {
            config.setItemFilterEnabled(enabled);
            config.setFilteredMaterials(filtered);
            menu.removed(player);
        }
        helper.succeed();
    }

    @GameTest
    public void offhandShortcutCannotEnterChest(GameTestHelper helper) {
        var mod = TravelBagMod.getInstance();
        var player = helper.makeMockServerPlayerInLevel();
        var chest = new SimpleContainer(27);
        chest.setItem(0, new ItemStack(Items.DIAMOND, 3));
        player.getInventory().setItem(40, mod.getShortcutItemManager().createShortcutItem(player));
        var menu = ChestMenu.threeRows(1, player.getInventory(), chest);
        menu.clicked(0, 40, ContainerInput.SWAP, player);
        helper.assertTrue(chest.getItem(0).is(Items.DIAMOND) && chest.getItem(0).getCount() == 3,
            "Offhand shortcut swap must preserve chest contents");
        helper.assertTrue(mod.getShortcutItemManager().isShortcutItem(player.getInventory().getItem(40)),
            "Offhand shortcut must remain in the player's inventory");
        menu.removed(player);
        helper.succeed();
    }

    @GameTest(maxTicks = 80)
    public void pickupUsesLiveEntityLookupAndHonorsEligibility(GameTestHelper helper) throws Exception {
        var mod = TravelBagMod.getInstance();
        var player = helper.makeMockServerPlayerInLevel();
        player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        Vec3 position = helper.absoluteVec(new Vec3(1, 2, 1));
        player.setPos(position.x, position.y, position.z);
        for (int slot = 0; slot < 36; slot++) player.getInventory().setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
        ItemEntity eligible = drop(helper, position, Items.DIAMOND, 7);
        ItemEntity delayed = drop(helper, position.add(0.4, 0, 0), Items.EMERALD, 3);
        delayed.setPickUpDelay(40);
        ItemEntity reserved = drop(helper, position.add(0, 0, 0.4), Items.GOLD_INGOT, 4);
        reserved.setTarget(UUID.randomUUID());
        ItemEntity distant = drop(helper, position.add(5, 0, 0), Items.IRON_INGOT, 2);
        var collect = TravelBagPickupService.class.getDeclaredMethod("collectFor", ServerPlayer.class);
        collect.setAccessible(true);
        helper.runAfterDelay(3, () -> {
            var config = mod.getConfig();
            boolean oldCollect = config.isCollectItems();
            try {
                config.setCollectItems(true);
                helper.assertTrue(mod.canAccessInCurrentGameMode(player) && player.isAlive() && !player.isSpectator(),
                    "Pickup test player must be eligible for TravelBag");
                helper.assertTrue(player.level().getEntitiesOfClass(ItemEntity.class,
                    player.getBoundingBox().inflate(config.getCollectRadius())).contains(eligible),
                    "Eligible drop must be visible in the live entity lookup");
                var service = new TravelBagPickupService(mod);
                collect.invoke(service, player);
                collect.invoke(service, player);
                var bag = mod.getStorage().getOrLoad(player.getUUID());
                helper.assertTrue(eligible.isRemoved() && count(bag, Items.DIAMOND) == 7,
                    "Live entity lookup must collect exactly seven diamonds, once; removed=" + eligible.isRemoved()
                        + ", bagCount=" + count(bag, Items.DIAMOND));
                helper.assertTrue(count(bag, Items.EMERALD) == 0 && count(bag, Items.GOLD_INGOT) == 0
                    && count(bag, Items.IRON_INGOT) == 0, "Ineligible drops must never enter the bag");
                helper.assertTrue(!delayed.isRemoved() && delayed.getItem().getCount() == 3,
                    "Pickup delay must be respected");
                helper.assertTrue(!reserved.isRemoved() && reserved.getItem().getCount() == 4,
                    "Drops reserved for another player must be respected");
                helper.assertTrue(!distant.isRemoved() && distant.getItem().getCount() == 2,
                    "Out-of-radius items must remain in the world");
                helper.succeed();
            } catch (ReflectiveOperationException exception) {
                throw new RuntimeException(exception);
            } finally {
                config.setCollectItems(oldCollect);
                eligible.discard();
                delayed.discard();
                reserved.discard();
                distant.discard();
            }
        });
    }

    private static TravelBagScreenHandler menu(TravelBagMod mod, ServerPlayer player, PlayerBagData data, boolean editable) {
        return new TravelBagScreenHandler(1, player.getInventory(),
            new TravelBagInventory(mod, player.getUUID(), "Compatibility", data, 6, editable));
    }

    private static int count(PlayerBagData bag, net.minecraft.world.item.Item item) {
        int total = 0;
        for (int slot = 0; slot < bag.size(); slot++) {
            if (bag.getStack(slot).is(item)) total += bag.getStack(slot).getCount();
        }
        return total;
    }

    private static ItemEntity drop(GameTestHelper helper, Vec3 position, net.minecraft.world.item.Item item, int count) {
        var entity = new ItemEntity(helper.getLevel(), position.x, position.y, position.z, new ItemStack(item, count));
        entity.setNoGravity(true);
        entity.setDeltaMovement(Vec3.ZERO);
        entity.setNoPickUpDelay();
        helper.assertTrue(helper.getLevel().addFreshEntity(entity), "Drop must enter the live entity lookup");
        return entity;
    }
}

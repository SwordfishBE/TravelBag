package net.travelbag;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.LevelResource;
import net.travelbag.bag.TravelBagInventory;
import net.travelbag.storage.TravelBagStorage;

public class TravelBagWorldStorageTests {
    @GameTest
    public void runtimeSelectsWorldStorageAndDedicatedPathStaysCentral(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        var mod = TravelBagMod.getInstance();
        Path config = mod.getConfigManager().getDataDirectory();
        Path root = server.getWorldPath(LevelResource.ROOT);
        helper.assertTrue(server.isDedicatedServer() != Boolean.getBoolean("travelbag.test.integrated"),
            "The requested dedicated/integrated storage mode must actually be active");
        Path expected = server.isDedicatedServer() ? config.resolve("players") : root.resolve("travelbag/players");
        helper.assertTrue(mod.getStorage().getPlayerDirectory().equals(expected), "The active server must select the expected storage directory");
        helper.assertTrue(TravelBagStorage.resolvePlayerDirectory(true, config, root).equals(config.resolve("players")),
            "Dedicated servers must keep their existing central path");
        helper.succeed();
    }

    @GameTest
    public void samePlayerHasIndependentItemsAndBackupsAcrossWorlds(GameTestHelper helper) throws Exception {
        var server = helper.getLevel().getServer();
        Path root = Files.createTempDirectory(server.getServerDirectory(), "travelbag-worlds-");
        Path config = root.resolve("config/travelbag");
        var first = new TravelBagStorage(TravelBagStorage.resolvePlayerDirectory(false, config, root.resolve("world-a")));
        var second = new TravelBagStorage(TravelBagStorage.resolvePlayerDirectory(false, config, root.resolve("world-b")));
        UUID player = UUID.randomUUID();
        try {
            first.prepare(server);
            var firstBag = first.getOrLoad(player);
            firstBag.setStack(53, new ItemStack(Items.DIAMOND, 7));
            firstBag.markDirty();
            helper.assertTrue(first.saveAll() && first.createBackups(), "First world must save its own bag and backup");
            first.close();
            second.prepare(server);
            helper.assertTrue(second.getOrLoad(player).isEmpty(), "Another world must not inherit the first world's items");
            var secondBag = second.getOrLoad(player);
            secondBag.setStack(0, new ItemStack(Items.EMERALD, 3));
            secondBag.markDirty();
            helper.assertTrue(second.saveAll() && second.createBackups(), "Second world must save independently");
            second.close();
            first.prepare(server);
            second.prepare(server);
            helper.assertTrue(first.getOrLoad(player).getStack(53).is(Items.DIAMOND)
                && first.getOrLoad(player).getStack(53).getCount() == 7, "Returning to the first world must restore its items");
            helper.assertTrue(second.getOrLoad(player).getStack(0).is(Items.EMERALD)
                && second.getOrLoad(player).getStack(0).getCount() == 3, "Second world must retain its different items");
            helper.assertTrue(first.inspectBackup(player, 1).itemCount() == 7 && second.inspectBackup(player, 1).itemCount() == 3,
                "Backups must be scoped to their own worlds");
            helper.assertTrue(Files.notExists(config), "Singleplayer storage must not create central player data");
        } finally {
            first.close();
            second.close();
            removeTestDirectory(root);
        }
        helper.succeed();
    }

    @GameTest
    public void migrationClaimsLegacyFileOnceAndPreservesRecoveryData(GameTestHelper helper) throws Exception {
        var server = helper.getLevel().getServer();
        Path root = Files.createTempDirectory(server.getServerDirectory(), "travelbag-migration-");
        Path legacy = root.resolve("central");
        var first = new TravelBagStorage(root.resolve("world-a/players"));
        var second = new TravelBagStorage(root.resolve("world-b/players"));
        UUID player = UUID.randomUUID();
        try {
            Path source = fixture(legacy, player);
            byte[] original = Files.readAllBytes(source);
            Path oldBackup = legacy.resolve(player + ".backup.1.dat");
            Files.copy(source, oldBackup);
            first.prepare(server);
            second.prepare(server);
            var preview = first.inspectLegacyBag(player, legacy);
            helper.assertTrue(preview.itemCount() == 10, "Migration preview must count the actual old data");
            Path archive = first.migrateLegacyBag(player, legacy, preview.fingerprint());
            helper.assertTrue(Files.notExists(source) && java.util.Arrays.equals(original, Files.readAllBytes(archive)),
                "Old active source must be claimed, preserving an exact recovery archive");
            helper.assertTrue(java.util.Arrays.equals(original, Files.readAllBytes(oldBackup)), "Old backups must remain untouched");
            helper.assertTrue(first.getOrLoad(player).getStack(0).getCount() == 10 && first.createBackups(),
                "Imported data and new world-local backup must be usable");
            expectIoFailure(helper, () -> second.migrateLegacyBag(player, legacy, preview.fingerprint()));
            helper.assertTrue(second.getOrLoad(player).isEmpty(), "The same old bag must not be imported into a second world");
            first.close();
            first.prepare(server);
            helper.assertTrue(first.getOrLoad(player).getStack(0).getCount() == 10, "Migration must survive reopening storage");
        } finally {
            first.close();
            second.close();
            removeTestDirectory(root);
        }
        helper.succeed();
    }

    @GameTest
    public void unsafeMigrationLeavesSourceAndCurrentBagUntouched(GameTestHelper helper) throws Exception {
        var server = helper.getLevel().getServer();
        Path root = Files.createTempDirectory(server.getServerDirectory(), "travelbag-rejected-migration-");
        Path legacy = root.resolve("central");
        var storage = new TravelBagStorage(root.resolve("world/players"));
        UUID player = UUID.randomUUID();
        try {
            Path source = fixture(legacy, player);
            byte[] original = Files.readAllBytes(source);
            storage.prepare(server);
            var preview = storage.inspectLegacyBag(player, legacy);
            var current = storage.getOrLoad(player);
            current.setStack(0, new ItemStack(Items.EMERALD, 3));
            current.markDirty();
            expectIoFailure(helper, () -> storage.migrateLegacyBag(player, legacy, preview.fingerprint()));
            helper.assertTrue(current.getStack(0).getCount() == 3 && java.util.Arrays.equals(original, Files.readAllBytes(source)),
                "Nonempty destination must not overwrite either bag");
            current.clear();
            expectIoFailure(helper, () -> storage.migrateLegacyBag(player, legacy, "stale-preview"));
            helper.assertTrue(java.util.Arrays.equals(original, Files.readAllBytes(source)), "Stale previews must not claim the source");
            Files.write(source, new byte[] {1, 2, 3});
            expectIoFailure(helper, () -> storage.migrateLegacyBag(player, legacy, preview.fingerprint()));
            helper.assertTrue(Files.size(source) == 3 && current.isEmpty(), "Malformed data must remain untouched");
            Files.write(source, original);
            // A directory at the target filename forces a staging/commit rejection.
            Files.createDirectories(storage.getPlayerDirectory().resolve(player + ".dat"));
            expectIoFailure(helper, () -> storage.migrateLegacyBag(player, legacy, preview.fingerprint()));
            helper.assertTrue(java.util.Arrays.equals(original, Files.readAllBytes(source)), "I/O failure must preserve the legacy source");
            Files.delete(storage.getPlayerDirectory().resolve(player + ".dat"));
        } finally {
            storage.close();
            removeTestDirectory(root);
        }
        helper.succeed();
    }

    @GameTest
    public void migrationCommandRequiresOwnerPreviewEmptyBagAndClosedViewers(GameTestHelper helper) throws Exception {
        var mod = TravelBagMod.getInstance();
        var server = helper.getLevel().getServer();
        var previousOwner = server.getSingleplayerProfile();
        var owner = helper.makeMockServerPlayerInLevel();
        var guest = helper.makeMockServerPlayerInLevel();
        if (server.isDedicatedServer()) {
            try {
                server.setSingleplayerProfile(owner.getGameProfile());
                helper.assertTrue(!mod.canMigrateLegacyBag(owner) && !mod.canMigrateLegacyBag(guest),
                    "Migration must be unavailable on dedicated servers even for a matching owner profile");
            } finally {
                server.setSingleplayerProfile(previousOwner);
            }
            helper.succeed();
            return;
        }
        Path legacy = mod.getLegacyPlayerDirectory();
        Path source = fixture(legacy, owner.getUUID());
        var dispatcher = server.getCommands().getDispatcher();
        var commandSource = owner.createCommandSourceStack();
        try {
            server.setSingleplayerProfile(owner.getGameProfile());
            helper.assertTrue(mod.canMigrateLegacyBag(owner) && !mod.canMigrateLegacyBag(guest),
                "LAN guests must not be allowed to import host files");
            helper.assertTrue(dispatcher.execute("travelbag migrate confirm", commandSource) == 0 && Files.exists(source),
                "Confirmation without preview must not import anything");
            var bag = mod.getStorage().getOrLoad(owner.getUUID());
            var viewer = new TravelBagInventory(mod, owner.getUUID(), "Migration", bag, 6, false);
            viewer.startOpen(owner);
            try {
                helper.assertTrue(dispatcher.execute("travelbag migrate", commandSource) == 0, "Open viewers must block migration");
            } finally {
                viewer.stopOpen(owner);
            }
            helper.assertTrue(dispatcher.execute("travelbag migrate", commandSource) == 1, "World owner can preview their own legacy bag");
            bag.setStack(0, new ItemStack(Items.EMERALD));
            bag.markDirty();
            helper.assertTrue(dispatcher.execute("travelbag migrate confirm", commandSource) == 0 && Files.exists(source),
                "Items added after preview must block confirmation");
            bag.clear();
            helper.assertTrue(dispatcher.execute("travelbag migrate", commandSource) == 1
                && dispatcher.execute("travelbag migrate confirm", commandSource) == 1, "Explicit confirmed migration must succeed");
            helper.assertTrue(mod.getStorage().getOrLoad(owner.getUUID()).getStack(0).getCount() == 10 && Files.notExists(source),
                "Command must import exactly ten diamonds and consume the old active source");
        } finally {
            server.setSingleplayerProfile(previousOwner);
            try (var files = Files.list(legacy)) {
                for (Path path : files.filter(path -> path.getFileName().toString().startsWith(owner.getUUID().toString())).toList()) {
                    Files.delete(path);
                }
            }
        }
        helper.succeed();
    }

    private static Path fixture(Path directory, UUID player) throws IOException {
        Files.createDirectories(directory);
        Path target = directory.resolve(player + ".dat");
        try (var input = TravelBagWorldStorageTests.class.getResourceAsStream("/fixtures/travelbag-1.0.4-mc26.3.dat")) {
            if (input == null) throw new IOException("Missing fixture");
            Files.copy(input, target);
        }
        return target;
    }

    private static void expectIoFailure(GameTestHelper helper, IoAction action) throws Exception {
        try {
            action.run();
        } catch (IOException expected) {
            return;
        }
        helper.assertTrue(false, "Unsafe migration must fail");
    }

    @FunctionalInterface
    private interface IoAction {
        void run() throws Exception;
    }

    private static void removeTestDirectory(Path directory) throws IOException {
        java.util.List<Path> paths;
        try (var files = Files.walk(directory)) {
            paths = files.sorted(Comparator.reverseOrder()).toList();
        }
        for (Path path : paths) Files.delete(path);
    }
}

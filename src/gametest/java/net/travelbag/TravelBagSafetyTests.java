package net.travelbag;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import me.lucko.fabric.api.permissions.v0.PermissionCheckEvent;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.fabric.api.util.TriState;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.travelbag.bag.TravelBagInventory;
import net.travelbag.bag.TravelBagScreenHandler;
import net.travelbag.config.TravelBagConfig;
import net.travelbag.pickup.TravelBagPickupService;
import net.travelbag.storage.TravelBagStorage;

public class TravelBagSafetyTests {
    @GameTest
    public void failedMenuWritesRollBackShiftCursorSwapThrowAndDrag(GameTestHelper helper) throws Exception {
        try (var test = new IsolatedStorage(helper)) {
            var bag = test.storage.getOrLoad(test.player.getUUID());
            bag.setStack(0, new ItemStack(Items.DIAMOND, 10));
            helper.assertTrue(test.storage.saveAll(), "Baseline must save");
            test.obstruct();
            var menu = test.menu();
            try {
                helper.assertTrue(menu.quickMoveStack(test.player, 0).isEmpty(), "Failed withdrawal must report no move");
                helper.assertTrue(test.player.getInventory().countItem(Items.DIAMOND) == 0 && bag.getStack(0).getCount() == 10,
                    "Shift-click withdrawal must roll back without duplicate items");
                menu.clicked(0, 0, ContainerInput.PICKUP, test.player);
                helper.assertTrue(menu.getCarried().isEmpty() && bag.getStack(0).getCount() == 10, "Cursor withdrawal must roll back");
                menu.setCarried(new ItemStack(Items.EMERALD, 3));
                menu.clicked(1, 0, ContainerInput.PICKUP, test.player);
                helper.assertTrue(menu.getCarried().getCount() == 3 && bag.getStack(1).isEmpty(), "Cursor deposit must return the source stack");
                menu.setCarried(ItemStack.EMPTY);
                test.player.getInventory().setItem(9, new ItemStack(Items.EMERALD, 3));
                menu.quickMoveStack(test.player, 54);
                helper.assertTrue(test.player.getInventory().getItem(9).getCount() == 3 && bag.getStack(1).isEmpty(), "Shift deposit must not consume source items");
                test.player.getInventory().setItem(8, new ItemStack(Items.GOLD_INGOT, 4));
                menu.clicked(0, 8, ContainerInput.SWAP, test.player);
                helper.assertTrue(test.player.getInventory().getItem(8).is(Items.GOLD_INGOT) && bag.getStack(0).is(Items.DIAMOND),
                    "Hotbar swap must restore both sides");
                int dropsBefore = diamondDrops(test.player);
                menu.clicked(0, 0, ContainerInput.THROW, test.player);
                helper.assertTrue(diamondDrops(test.player) == dropsBefore && bag.getStack(0).getCount() == 10,
                    "Failed throw must remove its spawned drop and restore the bag");
                menu.setCarried(new ItemStack(Items.EMERALD, 3));
                menu.clicked(-999, 0, ContainerInput.QUICK_CRAFT, test.player);
                menu.clicked(1, 1, ContainerInput.QUICK_CRAFT, test.player);
                menu.clicked(2, 1, ContainerInput.QUICK_CRAFT, test.player);
                menu.clicked(-999, 2, ContainerInput.QUICK_CRAFT, test.player);
                helper.assertTrue(menu.getCarried().getCount() == 3 && bag.getStack(1).isEmpty() && bag.getStack(2).isEmpty(),
                    "Failed drag must return all cursor items and reset all destinations");
                menu.setCarried(ItemStack.EMPTY);
            } finally {
                menu.removed(test.player);
            }
            test.storage.close();
            test.storage.prepare(helper.getLevel().getServer());
            helper.assertTrue(test.storage.getOrLoad(test.player.getUUID()).getStack(0).getCount() == 10
                && test.player.getInventory().countItem(Items.DIAMOND) == 0, "Reopening after failure must not resurrect withdrawn copies");
            test.repair();
            var recoveredMenu = test.menu();
            try {
                recoveredMenu.quickMoveStack(test.player, 0);
                helper.assertTrue(test.player.getInventory().countItem(Items.DIAMOND) == 10 && test.storage.getOrLoad(test.player.getUUID()).isEmpty(),
                    "Transfers must recover once storage works again");
            } finally {
                recoveredMenu.removed(test.player);
            }
        }
        helper.succeed();
    }

    @GameTest
    public void pickupAndShortcutKeepSourcesWhenStorageFails(GameTestHelper helper) throws Exception {
        try (var test = new IsolatedStorage(helper)) {
            var mod = TravelBagMod.getInstance();
            var bag = test.storage.getOrLoad(test.player.getUUID());
            bag.setStack(0, new ItemStack(Items.DIAMOND, 10));
            helper.assertTrue(test.storage.saveAll(), "Baseline must save");
            for (int slot = 0; slot < 36; slot++) test.player.getInventory().setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
            test.obstruct();
            ItemStack incoming = new ItemStack(Items.DIAMOND, 3);
            helper.assertTrue(!mod.tryStoreInBag(test.player.getUUID(), incoming) && incoming.getCount() == 3 && bag.getStack(0).getCount() == 10,
                "Failed insertion must leave both the source stack and bag unchanged");
            helper.assertTrue(!mod.getShortcutItemManager().tryGiveShortcutItem(test.player)
                && test.player.getInventory().countItem(Items.COBBLESTONE) == 36 * 64,
                "Shortcut allocation must not consume an inventory stack when it cannot persist it");
            var drop = new ItemEntity(helper.getLevel(), test.player.getX(), test.player.getY(), test.player.getZ(), new ItemStack(Items.DIAMOND, 3));
            drop.setNoPickUpDelay();
            helper.assertTrue(helper.getLevel().addFreshEntity(drop), "Drop must register");
            boolean collect = mod.getConfig().isCollectItems();
            try {
                mod.getConfig().setCollectItems(true);
                var method = TravelBagPickupService.class.getDeclaredMethod("collectFor", ServerPlayer.class);
                method.setAccessible(true);
                var service = new TravelBagPickupService(mod);
                method.invoke(service, test.player);
                method.invoke(service, test.player);
                helper.assertTrue(!drop.isRemoved() && drop.getItem().getCount() == 3 && bag.getStack(0).getCount() == 10,
                    "Repeated failed pickup must not consume or duplicate the entity stack");
                test.repair();
                method.invoke(service, test.player);
                helper.assertTrue(drop.isRemoved() && bag.getStack(0).getCount() == 13, "Recovered pickup must store exactly once");
            } finally {
                mod.getConfig().setCollectItems(collect);
                drop.discard();
            }
        }
        helper.succeed();
    }

    @GameTest
    public void deathAndCleanRollbackOnFailedWrites(GameTestHelper helper) throws Exception {
        try (var test = new IsolatedStorage(helper)) {
            var mod = TravelBagMod.getInstance();
            var bag = test.storage.getOrLoad(test.player.getUUID());
            bag.setStack(0, new ItemStack(Items.DIAMOND, 10));
            helper.assertTrue(test.storage.saveAll(), "Baseline must save");
            test.obstruct();
            helper.assertTrue(!mod.cleanBag(test.player.getUUID()) && bag.getStack(0).getCount() == 10, "Clean must roll back if it cannot save");
            boolean death = mod.getConfig().isDropOnDeath();
            boolean keep = mod.getConfig().isHonorKeepInventoryOnDeath();
            try {
                mod.getConfig().setDropOnDeath(true);
                mod.getConfig().setHonorKeepInventoryOnDeath(false);
                int before = diamondDrops(test.player);
                mod.handlePlayerDeath(test.player);
                helper.assertTrue(bag.getStack(0).getCount() == 10 && diamondDrops(test.player) == before,
                    "Failed death transfer must retain items in the bag and discard attempted drops");
                test.repair();
                mod.handlePlayerDeath(test.player);
                helper.assertTrue(bag.isEmpty() && diamondDrops(test.player) == before + 10, "Successful death transfer must drop exactly once");
                mod.handlePlayerDeath(test.player);
                helper.assertTrue(diamondDrops(test.player) == before + 10, "Repeated death handling must not duplicate drops");
            } finally {
                mod.getConfig().setDropOnDeath(death);
                mod.getConfig().setHonorKeepInventoryOnDeath(keep);
                test.player.level().getEntitiesOfClass(ItemEntity.class, test.player.getBoundingBox().inflate(3)).forEach(ItemEntity::discard);
            }
        }
        helper.succeed();
    }

    @GameTest
    public void openMenusRejectSizeModeAndPermissionChanges(GameTestHelper helper) throws Exception {
        try (var test = new IsolatedStorage(helper)) {
            var mod = TravelBagMod.getInstance();
            var bag = test.storage.getOrLoad(test.player.getUUID());
            bag.setStack(53, new ItemStack(Items.DIAMOND));
            helper.assertTrue(test.storage.saveAll(), "Baseline must save");
            var menu = test.menu();
            int rows = mod.getConfig().getSize();
            try {
                mod.getConfig().setSize(1);
                helper.assertTrue(!menu.stillValid(test.player) && menu.quickMoveStack(test.player, 53).isEmpty()
                    && bag.getStack(53).getCount() == 1, "An existing six-row menu must not expose revoked rows");
                mod.getConfig().setSize(6);
                test.player.setGameMode(GameType.CREATIVE);
                helper.assertTrue(!menu.stillValid(test.player), "A newly disallowed game mode must invalidate the menu");
                test.player.setGameMode(GameType.SURVIVAL);
                helper.assertTrue(menu.stillValid(test.player), "Unchanged valid access must remain usable");
            } finally {
                mod.getConfig().setSize(rows);
                menu.removed(test.player);
            }
            AtomicReference<UUID> actor = new AtomicReference<>(test.player.getUUID());
            AtomicBoolean edit = new AtomicBoolean(true);
            AtomicBoolean open = new AtomicBoolean(true);
            PermissionCheckEvent.EVENT.register((source, permission) -> {
                if (!(source instanceof CommandSourceStack command) || command.getEntity() == null
                    || !command.getEntity().getUUID().equals(actor.get())) return TriState.DEFAULT;
                if (permission.equals("travelbag.others")) return TriState.of(open.get());
                if (permission.equals("travelbag.others.edit")) return TriState.of(edit.get());
                if (permission.equals("travelbag.admin")) return TriState.FALSE;
                return TriState.DEFAULT;
            });
            UUID owner = UUID.randomUUID();
            var other = test.storage.getOrLoad(owner);
            var otherMenu = new TravelBagScreenHandler(2, test.player.getInventory(),
                new TravelBagInventory(mod, owner, "Other", other, 6, true, false));
            try {
                helper.assertTrue(otherMenu.stillValid(test.player), "Initial explicit other-bag edit permission must apply");
                edit.set(false);
                helper.assertTrue(!otherMenu.stillValid(test.player), "Revoked edit permission must invalidate an editable menu");
                edit.set(true);
                open.set(false);
                helper.assertTrue(!otherMenu.stillValid(test.player), "Revoked view permission must invalidate the menu");
            } finally {
                actor.set(null);
                otherMenu.removed(test.player);
            }
        }
        helper.succeed();
    }

    @GameTest
    public void ownerCanRestoreLockedBagWithoutCheatsButNotOthers(GameTestHelper helper) throws Exception {
        try (var test = new IsolatedStorage(helper)) {
            var mod = TravelBagMod.getInstance();
            var server = helper.getLevel().getServer();
            var owner = server.getSingleplayerProfile();
            var dispatcher = server.getCommands().getDispatcher();
            var source = commandSource(test.player);
            try {
                server.setSingleplayerProfile(test.player.getGameProfile());
                var bag = test.storage.getOrLoad(test.player.getUUID());
                bag.setStack(0, new ItemStack(Items.DIAMOND, 10));
                helper.assertTrue(test.storage.saveAll() && test.storage.createBackups(), "Baseline backup must be valid");
                bag.blockSaving("Deliberate restore test lock");
                String command = "travelbag restore " + test.commandName + " latest";
                if (server.isDedicatedServer()) {
                    helper.assertTrue(!dispatcher.getRoot().getChild("travelbag").getChild("restore").canUse(source)
                        && !dispatcher.getRoot().getChild("travelbag").getChild("upgrade").canUse(source),
                        "Dedicated users must not gain owner fallback permissions");
                } else {
                    helper.assertTrue(dispatcher.execute(command + " confirm", source) == 0, "Restore needs a preview");
                    var viewer = new TravelBagInventory(mod, test.player.getUUID(), "Owner", bag, 6, false);
                    viewer.startOpen(test.player);
                    try {
                        helper.assertTrue(dispatcher.execute(command, source) == 0, "Viewers must block owner restore");
                    } finally {
                        viewer.stopOpen(test.player);
                    }
                    UUID other = UUID.randomUUID();
                    helper.assertTrue(dispatcher.execute("travelbag restore " + other + " latest", source) == 0,
                        "Owner fallback must not grant access to another player's bag");
                    helper.assertTrue(dispatcher.execute(command, source) == 1 && dispatcher.execute(command + " confirm", source) == 1,
                        "The integrated owner can restore their own locked bag without cheats");
                    helper.assertTrue(test.storage.getOrLoad(test.player.getUUID()).getStack(0).getCount() == 10
                        && !test.storage.getOrLoad(test.player.getUUID()).isSaveBlocked(), "Restore must unlock without granting extra items");
                    helper.assertTrue(dispatcher.execute(command + " confirm", source) == 0, "A restore confirmation cannot be reused");
                }
            } finally {
                server.setSingleplayerProfile(owner);
            }
        }
        helper.succeed();
    }

    @GameTest
    public void legacyAndSilentComponentsRequireExplicitChoiceAndKeepRecovery(GameTestHelper helper) throws Exception {
        try (var test = new IsolatedStorage(helper)) {
            UUID legacy = UUID.randomUUID();
            Path file = writeBag(test.directory, legacy, consumableStack(), null);
            byte[] original = Files.readAllBytes(file);
            helper.assertTrue(test.storage.getOrLoad(legacy).isSaveBlocked(), "Ambiguous unversioned sounds must not be silently changed");
            helper.assertTrue(java.util.Arrays.equals(original, Files.readAllBytes(file)), "Loading must not rewrite the original");
            var preview = test.storage.inspectLegacyUpgrade(legacy, 0, true);
            Path recovery = test.storage.upgradeLegacyFile(legacy, 0, true, preview.fingerprint());
            helper.assertTrue(java.util.Arrays.equals(original, Files.readAllBytes(recovery)), "Exact original bytes must remain recoverable");
            var migrated = test.storage.getOrLoad(legacy).getStack(0);
            helper.assertTrue(migrated.get(DataComponents.CONSUMABLE).sound().isPresent(), "Legacy choice must restore the old default eating sound");
            helper.assertTrue(migrated.get(DataComponents.EQUIPPABLE).equipSound().isPresent()
                && migrated.get(DataComponents.EQUIPPABLE).shearingSound().isPresent(), "Legacy equipment defaults must migrate too");
            test.storage.getOrLoad(legacy).markDirty();
            helper.assertTrue(test.storage.save(legacy), "Migrated data must persist in the new versioned format");
            UUID silent = UUID.randomUUID();
            writeBag(test.directory, silent, consumableStack(), null);
            helper.assertTrue(test.storage.getOrLoad(silent).isSaveBlocked(), "Unversioned silence must require a choice");
            var quiet = test.storage.inspectLegacyUpgrade(silent, 0, false);
            test.storage.upgradeLegacyFile(silent, 0, false, quiet.fingerprint());
            helper.assertTrue(test.storage.getOrLoad(silent).getStack(0).get(DataComponents.CONSUMABLE).sound().isEmpty(),
                "Current choice must preserve deliberate silence");
            UUID versionedSilent = UUID.randomUUID();
            writeBag(test.directory, versionedSilent, consumableStack(), currentVersion());
            helper.assertTrue(!test.storage.getOrLoad(versionedSilent).isSaveBlocked()
                && test.storage.getOrLoad(versionedSilent).getStack(0).get(DataComponents.CONSUMABLE).sound().isEmpty(),
                "New files must never have silence converted into an old default");
            UUID knownLegacy = UUID.randomUUID();
            writeBag(test.directory, knownLegacy, consumableStack(), 5023);
            helper.assertTrue(!test.storage.getOrLoad(knownLegacy).isSaveBlocked()
                && test.storage.getOrLoad(knownLegacy).getStack(0).get(DataComponents.CONSUMABLE).sound().isPresent(),
                "Recorded old Minecraft versions must migrate automatically");
            test.storage.close();
            test.storage.prepare(helper.getLevel().getServer());
            helper.assertTrue(test.storage.getOrLoad(legacy).getStack(0).get(DataComponents.CONSUMABLE).sound().isPresent()
                && test.storage.getOrLoad(silent).getStack(0).get(DataComponents.CONSUMABLE).sound().isEmpty(),
                "Both choices must survive reopening");
        }
        helper.succeed();
    }

    @GameTest
    public void nestedOldItemsMigrateAndInvalidVersionsRemainUntouched(GameTestHelper helper) throws Exception {
        try (var test = new IsolatedStorage(helper)) {
            UUID nested = UUID.randomUUID();
            CompoundTag containerEntry = new CompoundTag();
            containerEntry.putInt("slot", 0);
            containerEntry.put("item", consumableStack());
            ListTag contents = new ListTag();
            contents.add(containerEntry);
            CompoundTag components = new CompoundTag();
            components.put("minecraft:container", contents);
            CompoundTag shulker = new CompoundTag();
            shulker.putString("id", "minecraft:shulker_box");
            shulker.putInt("count", 1);
            shulker.put("components", components);
            writeBag(test.directory, nested, shulker, 5023);
            var loaded = test.storage.getOrLoad(nested);
            helper.assertTrue(!loaded.isSaveBlocked() && loaded.getStack(0).get(DataComponents.CONTAINER)
                .nonEmptyItemCopyStream().findFirst().orElseThrow().get(DataComponents.CONSUMABLE).sound().isPresent(),
                "Minecraft item data fixes must also migrate nested container stacks");
            UUID future = UUID.randomUUID();
            Path futureFile = writeBag(test.directory, future, consumableStack(), currentVersion() + 1);
            byte[] before = Files.readAllBytes(futureFile);
            helper.assertTrue(test.storage.getOrLoad(future).isSaveBlocked() && java.util.Arrays.equals(before, Files.readAllBytes(futureFile)),
                "Future data versions must lock without rewriting");
            UUID missing = UUID.randomUUID();
            CompoundTag missingItem = new CompoundTag();
            missingItem.putString("id", "missingmod:important_item");
            missingItem.putInt("count", 1);
            Path missingFile = writeBag(test.directory, missing, missingItem, 5023);
            byte[] missingBytes = Files.readAllBytes(missingFile);
            helper.assertTrue(test.storage.getOrLoad(missing).isSaveBlocked()
                && java.util.Arrays.equals(missingBytes, Files.readAllBytes(missingFile)), "Unknown mod items must not be stripped by migration");
        }
        helper.succeed();
    }

    @GameTest
    public void ownerCanUpgradeAmbiguousBackupThenRestoreIt(GameTestHelper helper) throws Exception {
        try (var test = new IsolatedStorage(helper)) {
            var server = helper.getLevel().getServer();
            if (server.isDedicatedServer()) {
                helper.succeed();
                return;
            }
            var previousOwner = server.getSingleplayerProfile();
            try {
                server.setSingleplayerProfile(test.player.getGameProfile());
                Path primary = writeBag(test.directory, test.player.getUUID(), consumableStack(), null);
                byte[] original = Files.readAllBytes(primary);
                Path backup = primary.resolveSibling(test.player.getUUID() + ".backup.1.dat");
                Files.copy(primary, backup);
                test.storage.close();
                test.storage.prepare(server);
                helper.assertTrue(test.storage.getOrLoad(test.player.getUUID()).isSaveBlocked(), "Ambiguous primary must lock");
                var source = commandSource(test.player);
                var dispatcher = server.getCommands().getDispatcher();
                String upgrade = "travelbag upgrade " + test.commandName + " legacy latest";
                helper.assertTrue(dispatcher.execute(upgrade + " confirm", source) == 0, "Upgrade needs preview");
                helper.assertTrue(dispatcher.execute(upgrade, source) == 1, "Old backup must preview");
                CompoundTag changed;
                try (var input = Files.newInputStream(backup)) {
                    changed = NbtIo.readCompressed(input, net.minecraft.nbt.NbtAccounter.unlimitedHeap());
                }
                ((CompoundTag) changed.getList("Items").orElseThrow().get(0)).getCompound("Stack").orElseThrow().putInt("count", 2);
                try (var output = Files.newOutputStream(backup)) {
                    NbtIo.writeCompressed(changed, output);
                }
                byte[] changedBytes = Files.readAllBytes(backup);
                helper.assertTrue(dispatcher.execute(upgrade + " confirm", source) == 0
                    && java.util.Arrays.equals(changedBytes, Files.readAllBytes(backup)), "Changed backup must invalidate the preview without overwriting it");
                Files.write(backup, original);
                helper.assertTrue(dispatcher.execute(upgrade, source) == 1 && dispatcher.execute(upgrade + " confirm", source) == 1,
                    "Owner can explicitly upgrade an old backup");
                helper.assertTrue(java.util.Arrays.equals(original, Files.readAllBytes(primary))
                    && test.storage.getOrLoad(test.player.getUUID()).isSaveBlocked(), "Backup upgrade must not overwrite or unlock the active bag");
                String restore = "travelbag restore " + test.commandName + " latest";
                helper.assertTrue(dispatcher.execute(restore, source) == 1 && dispatcher.execute(restore + " confirm", source) == 1,
                    "An upgraded backup must be restorable by the integrated owner");
                helper.assertTrue(test.storage.getOrLoad(test.player.getUUID()).getStack(0).get(DataComponents.CONSUMABLE).sound().isPresent(),
                    "Restored item must retain legacy eating behavior");
            } finally {
                server.setSingleplayerProfile(previousOwner);
            }
        }
        helper.succeed();
    }

    @GameTest
    public void pickupRadiusIsFiniteAndBoundedAtEveryEntryPoint(GameTestHelper helper) {
        for (double input : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
            Double.MAX_VALUE, -100, 0, 0.25, 1.5, 16, 1_000_000_000}) {
            var config = new TravelBagConfig();
            config.setCollectRadius(input);
            helper.assertTrue(Double.isFinite(config.getCollectRadius()) && config.getCollectRadius() >= 0.25
                && config.getCollectRadius() <= TravelBagConfig.MAX_COLLECT_RADIUS, "Setter must reject unsafe radii");
            config.sanitize();
            helper.assertTrue(Double.isFinite(config.getCollectRadius()) && config.getCollectRadius() >= 0.25
                && config.getCollectRadius() <= TravelBagConfig.MAX_COLLECT_RADIUS, "Sanitizer must reject unsafe radii");
            if (input >= 0.25 && input <= TravelBagConfig.MAX_COLLECT_RADIUS) {
                helper.assertTrue(config.getCollectRadius() == input, "Normal configured radii must not change");
            }
        }
        helper.succeed();
    }

    @GameTest
    public void sortingAndMultipleViewersNeverPublishFailedChanges(GameTestHelper helper) throws Exception {
        try (var test = new IsolatedStorage(helper)) {
            var mod = TravelBagMod.getInstance();
            var data = test.storage.getOrLoad(test.player.getUUID());
            data.setStack(53, new ItemStack(Items.DIAMOND, 10));
            helper.assertTrue(test.storage.saveAll(), "Baseline must save");
            var menu = test.menu();
            var reader = new TravelBagInventory(mod, test.player.getUUID(), "Read only", data, 6, false);
            reader.startOpen(test.player);
            try {
                test.obstruct();
                helper.assertTrue(!mod.compactBag(test.player.getUUID()) && data.getStack(0).isEmpty()
                    && data.getStack(53).getCount() == 10 && reader.getItem(53).getCount() == 10,
                    "Failed sorting must preserve source positions and other viewers");
                menu.quickMoveStack(test.player, 53);
                helper.assertTrue(reader.getItem(53).getCount() == 10 && test.player.getInventory().countItem(Items.DIAMOND) == 0,
                    "Rolled-back withdrawal must not publish transient data to another viewer");
                test.repair();
                helper.assertTrue(mod.compactBag(test.player.getUUID()) && reader.getItem(0).getCount() == 10 && reader.getItem(53).isEmpty(),
                    "Successful sorting must refresh every viewer");
                menu.quickMoveStack(test.player, 0);
                helper.assertTrue(reader.getItem(0).isEmpty() && data.isEmpty() && test.player.getInventory().countItem(Items.DIAMOND) == 10,
                    "Successful withdrawal must publish one committed result");
            } finally {
                reader.stopOpen(test.player);
                menu.removed(test.player);
            }
            test.storage.close();
            test.storage.prepare(helper.getLevel().getServer());
            helper.assertTrue(test.storage.getOrLoad(test.player.getUUID()).isEmpty(), "Closing readers must not resurrect withdrawn items");
        }
        helper.succeed();
    }

    @GameTest
    public void codecErrorsReturnSourceAndPreserveThePreviousFile(GameTestHelper helper) throws Exception {
        try (var test = new IsolatedStorage(helper)) {
            var mod = TravelBagMod.getInstance();
            var bag = test.storage.getOrLoad(test.player.getUUID());
            bag.setStack(0, new ItemStack(Items.DIAMOND, 10));
            helper.assertTrue(test.storage.saveAll(), "Baseline must save");
            Path primary = test.directory.resolve(test.player.getUUID() + ".dat");
            byte[] original = Files.readAllBytes(primary);
            ItemStack invalid = new ItemStack(Items.DIAMOND_PICKAXE);
            invalid.set(DataComponents.MAX_DAMAGE, -1);
            helper.assertTrue(!mod.tryStoreInBag(test.player.getUUID(), invalid) && invalid.getCount() == 1,
                "A component encoding error must not consume the source item");
            helper.assertTrue(bag.getStack(1).isEmpty() && bag.getStack(0).getCount() == 10
                && java.util.Arrays.equals(original, Files.readAllBytes(primary)), "Failed encoding must not replace the previous file");
            invalid.set(DataComponents.MAX_DAMAGE, 10);
            helper.assertTrue(mod.tryStoreInBag(test.player.getUUID(), invalid) && invalid.isEmpty(), "Corrected components must be storable without restart");
        }
        helper.succeed();
    }

    @GameTest
    public void reloadClosesActiveMenusAndReturnsCommittedCursorItems(GameTestHelper helper) throws Exception {
        var mod = TravelBagMod.getInstance();
        var player = helper.makeMockServerPlayerInLevel();
        player.setGameMode(GameType.SURVIVAL);
        var data = mod.getStorage().getOrLoad(player.getUUID());
        data.setStack(0, new ItemStack(Items.DIAMOND, 10));
        helper.assertTrue(mod.getStorage().save(player.getUUID()), "Baseline must save");
        var menu = new TravelBagScreenHandler(1, player.getInventory(),
            new TravelBagInventory(mod, player.getUUID(), "Reload", data, 6, true));
        player.containerMenu = menu;
        menu.clicked(0, 0, ContainerInput.PICKUP, player);
        helper.assertTrue(menu.getCarried().getCount() == 10 && data.isEmpty(), "Cursor withdrawal must commit before reload");
        mod.reload();
        helper.assertTrue(player.containerMenu == player.inventoryMenu && !mod.hasOpenBag(player.getUUID()), "Reload must close registered bag menus");
        helper.assertTrue(player.getInventory().countItem(Items.DIAMOND) == 10 && data.isEmpty(), "Closing the old menu must return exactly the committed cursor items");
        helper.succeed();
    }

    private static int diamondDrops(ServerPlayer player) {
        return player.level().getEntitiesOfClass(ItemEntity.class, player.getBoundingBox().inflate(3),
            entity -> entity.isAlive() && entity.getItem().is(Items.DIAMOND)).stream().mapToInt(entity -> entity.getItem().getCount()).sum();
    }

    private static CommandSourceStack commandSource(ServerPlayer player) {
        return player.createCommandSourceStack().withPermission(PermissionSet.NO_PERMISSIONS).withSource(new net.minecraft.commands.CommandSource() {
            @Override
            public void sendSystemMessage(net.minecraft.network.chat.Component message) {
                TravelBagMod.LOGGER.info("[Safety command] {}", message.getString());
            }

            @Override
            public boolean acceptsSuccess() { return true; }

            @Override
            public boolean acceptsFailure() { return true; }

            @Override
            public boolean shouldInformAdmins() { return false; }
        });
    }

    private static int currentVersion() {
        return SharedConstants.getCurrentVersion().dataVersion().version();
    }

    private static CompoundTag consumableStack() {
        CompoundTag consumable = new CompoundTag();
        consumable.putFloat("consume_seconds", 3.0F);
        CompoundTag equipment = new CompoundTag();
        equipment.putString("slot", "head");
        CompoundTag components = new CompoundTag();
        components.put("minecraft:consumable", consumable);
        components.put("minecraft:equippable", equipment);
        CompoundTag stack = new CompoundTag();
        stack.putString("id", "minecraft:apple");
        stack.putInt("count", 1);
        stack.put("components", components);
        return stack;
    }

    private static Path writeBag(Path directory, UUID uuid, CompoundTag stack, Integer minecraftVersion) throws IOException {
        CompoundTag item = new CompoundTag();
        item.putInt("Slot", 0);
        item.put("Stack", stack);
        ListTag items = new ListTag();
        items.add(item);
        CompoundTag root = new CompoundTag();
        root.putInt("DataVersion", 1);
        if (minecraftVersion != null) root.putInt("MinecraftDataVersion", minecraftVersion);
        root.put("Items", items);
        Path path = directory.resolve(uuid + ".dat");
        try (var output = Files.newOutputStream(path)) {
            NbtIo.writeCompressed(root, output);
        }
        return path;
    }

    private static final class IsolatedStorage implements AutoCloseable {
        final Path directory;
        final TravelBagStorage storage;
        final ServerPlayer player;
        final String commandName;
        private final TravelBagStorage previous;
        private final java.lang.reflect.Field field;

        IsolatedStorage(GameTestHelper helper) throws Exception {
            var mod = TravelBagMod.getInstance();
            this.directory = Files.createTempDirectory(helper.getLevel().getServer().getServerDirectory(), "travelbag-safety-");
            this.previous = mod.getStorage();
            this.storage = new TravelBagStorage(this.directory);
            this.storage.prepare(helper.getLevel().getServer());
            this.field = TravelBagMod.class.getDeclaredField("storage");
            this.field.setAccessible(true);
            this.field.set(mod, this.storage);
            this.player = helper.makeMockServerPlayerInLevel();
            this.commandName = "safety" + this.player.getUUID().toString().substring(0, 8);
            helper.getLevel().getServer().services().nameToIdCache().add(
                new net.minecraft.server.players.NameAndId(this.player.getUUID(), this.commandName));
            this.player.setGameMode(GameType.SURVIVAL);
            Vec3 position = helper.absoluteVec(new Vec3(1, 2, 1));
            this.player.setPos(position.x, position.y, position.z);
        }

        TravelBagScreenHandler menu() {
            return new TravelBagScreenHandler(1, this.player.getInventory(), new TravelBagInventory(TravelBagMod.getInstance(),
                this.player.getUUID(), "Safety", this.storage.getOrLoad(this.player.getUUID()), 6, true));
        }

        void obstruct() throws IOException {
            Path path = this.directory.resolve(this.player.getUUID() + ".dat.tmp");
            Files.createDirectory(path);
            Files.writeString(path.resolve("keep"), "Deliberate persistent write failure");
        }

        void repair() throws IOException {
            Path path = this.directory.resolve(this.player.getUUID() + ".dat.tmp");
            Files.delete(path.resolve("keep"));
            Files.delete(path);
        }

        @Override
        public void close() throws Exception {
            try {
                this.storage.close();
            } finally {
                this.field.set(TravelBagMod.getInstance(), this.previous);
                java.util.List<Path> paths;
                try (var files = Files.walk(this.directory)) {
                    paths = files.sorted(Comparator.reverseOrder()).toList();
                }
                for (Path path : paths) Files.delete(path);
            }
        }
    }
}

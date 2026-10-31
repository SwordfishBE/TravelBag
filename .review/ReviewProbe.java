import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

// Reproduces vanilla behavior relied on by TravelBag; this is not shipped in the mod.
public final class ReviewProbe {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        // Standalone probe has no world loader to bind the item's default components.
        Items.DIAMOND.builtInRegistryHolder().bindComponents(net.minecraft.core.component.DataComponentMap.builder()
                .set(net.minecraft.core.component.DataComponents.MAX_STACK_SIZE, 64).build());
        SimpleContainer target = new SimpleContainer(1);
        target.setItem(0, new ItemStack(Items.DIAMOND, 10));
        ProbeMenu menu = new ProbeMenu(target);
        ItemStack incoming = new ItemStack(Items.DIAMOND, 5);
        if (!menu.transfer(incoming) || !incoming.isEmpty() || target.getItem(0).getCount() != 15) {
            throw new AssertionError("Expected forbidden merge into occupied slot");
        }
        System.out.println("REPRODUCED: moveItemStackTo merged 5 diamonds into a slot whose mayPlace returns false.");
        CompoundTag root = new CompoundTag();
        root.putString("Items", "invalid-list");
        ListTag items = root.getList("Items").orElseGet(ListTag::new);
        if (!items.isEmpty()) {
            throw new AssertionError("Expected invalid Items to fall back to empty list");
        }
        System.out.println("REPRODUCED: malformed Items tag is interpreted as an empty inventory by the current fallback.");
        testStorageFixes();
    }

    private static void testStorageFixes() throws Exception {
        Class<?> type = net.travelbag.storage.TravelBagStorage.class;
        var read = type.getDeclaredMethod("readItems", CompoundTag.class);
        read.setAccessible(true);
        expectIoFailure(() -> read.invoke(null, new CompoundTag()));
        CompoundTag invalid = new CompoundTag();
        invalid.putString("Items", "invalid");
        expectIoFailure(() -> read.invoke(null, invalid));
        CompoundTag legacy = new CompoundTag();
        ListTag originalItems = new ListTag();
        CompoundTag item = new CompoundTag();
        item.putInt("Slot", 53);
        CompoundTag stack = new CompoundTag();
        stack.putString("id", "minecraft:diamond");
        item.put("Stack", stack);
        originalItems.add(item);
        legacy.put("Items", originalItems);
        if (read.invoke(null, legacy) != originalItems) throw new AssertionError("Legacy items changed");
        legacy.putInt("DataVersion", 1);
        if (read.invoke(null, legacy) != originalItems) throw new AssertionError("Version 1 items changed");
        legacy.putInt("DataVersion", 2);
        expectIoFailure(() -> read.invoke(null, legacy));
        var encode = type.getDeclaredMethod("requireCompleteEncoding", com.mojang.serialization.DataResult.class);
        encode.setAccessible(true);
        var partial = com.mojang.serialization.DataResult.<net.minecraft.nbt.Tag>error(() -> "injected codec failure", stack);
        expectIoFailure(() -> encode.invoke(null, partial));
        if (encode.invoke(null, com.mojang.serialization.DataResult.success(stack)) != stack) {
            throw new AssertionError("Successful encode changed");
        }
        var dir = java.nio.file.Files.createTempDirectory("travelbag-regression-");
        try {
            var storage = new net.travelbag.storage.TravelBagStorage(dir);
            var field = type.getDeclaredField("cache");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            var cache = (java.util.Map<java.util.UUID, net.travelbag.storage.PlayerBagData>) field.get(storage);
            var cleanId = java.util.UUID.randomUUID();
            var activeId = java.util.UUID.randomUUID();
            var dirtyId = java.util.UUID.randomUUID();
            cache.put(cleanId, new net.travelbag.storage.PlayerBagData());
            cache.put(activeId, new net.travelbag.storage.PlayerBagData());
            var dirty = new net.travelbag.storage.PlayerBagData();
            dirty.markDirty();
            cache.put(dirtyId, dirty);
            // No server is attached, so a dirty save cannot succeed and must not be evicted.
            storage.evictUnused(activeId::equals);
            if (cache.containsKey(cleanId) || !cache.containsKey(activeId) || !cache.containsKey(dirtyId)) {
                throw new AssertionError("Unsafe cache eviction");
            }
            if (storage.saveAll()) throw new AssertionError("Failed save reported success");
            dirty.clearDirty();
            storage.close();
            if (!cache.isEmpty()) throw new AssertionError("Shutdown retained bags");
            var serverField = type.getDeclaredField("server");
            serverField.setAccessible(true);
            if (serverField.get(storage) != null) throw new AssertionError("Shutdown retained server");
            var blocker = dir.resolve("not-a-directory");
            java.nio.file.Files.writeString(blocker, "block storage directory creation");
            var failing = new net.travelbag.storage.TravelBagStorage(blocker);
            if (failing.createBackups() || !failing.hasChangesSinceBackup()) {
                throw new AssertionError("Backup failure reported success or lost retry flag");
            }
            java.nio.file.Files.delete(blocker);
        } finally {
            java.nio.file.Files.delete(dir);
        }
        System.out.println("PASS: malformed/future roots rejected; legacy/v1 items preserved; partial encodes rejected; safe eviction, shutdown and backup failure handling.");
    }

    private static void expectIoFailure(CheckedAction action) throws Exception {
        try {
            action.run();
        } catch (java.lang.reflect.InvocationTargetException exception) {
            if (exception.getCause() instanceof java.io.IOException) return;
            throw exception;
        }
        throw new AssertionError("Expected IOException");
    }

    private interface CheckedAction { void run() throws Exception; }

    private static final class ProbeMenu extends AbstractContainerMenu {
        ProbeMenu(SimpleContainer target) {
            super(MenuType.GENERIC_9x1, 0);
            addSlot(new Slot(target, 0, 0, 0) {
                @Override public boolean mayPlace(ItemStack stack) { return false; }
            });
        }
        boolean transfer(ItemStack stack) { return moveItemStackTo(stack, 0, 1, false); }
        @Override public boolean stillValid(Player player) { return true; }
        @Override public ItemStack quickMoveStack(Player player, int index) { return ItemStack.EMPTY; }
    }
}

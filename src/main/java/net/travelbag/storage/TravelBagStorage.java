package net.travelbag.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.travelbag.TravelBagMod;

public final class TravelBagStorage {
	private static final int DATA_VERSION = 1;
	private final Path playerDirectory;
	private final Map<UUID, PlayerBagData> cache = new ConcurrentHashMap<>();
	private MinecraftServer server;

	public TravelBagStorage(Path playerDirectory) {
		this.playerDirectory = playerDirectory;
	}

	public void prepare(MinecraftServer server) {
		this.server = server;
		try {
			Files.createDirectories(this.playerDirectory);
		} catch (IOException exception) {
			TravelBagMod.LOGGER.warn("[TravelBag] Failed to create TravelBag storage directory.", exception);
		}
	}

	public PlayerBagData getOrLoad(UUID uuid) {
		return this.cache.computeIfAbsent(uuid, this::loadInternal);
	}

	public void save(UUID uuid) {
		PlayerBagData data = this.cache.get(uuid);
		if (data != null) {
			this.save(uuid, data);
		}
	}

	public void saveAll() {
		for (Map.Entry<UUID, PlayerBagData> entry : this.cache.entrySet()) {
			this.save(entry.getKey(), entry.getValue());
		}
	}

	public void createBackups() {
		try {
			Files.createDirectories(this.playerDirectory);
			try (DirectoryStream<Path> stream = Files.newDirectoryStream(this.playerDirectory)) {
				for (Path path : stream) {
					if (!Files.isRegularFile(path) || !this.isPrimaryDataFile(path)) {
						continue;
					}
					Files.copy(path, this.toBackupPath(path), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
				}
			}
		} catch (IOException exception) {
			TravelBagMod.LOGGER.warn("[TravelBag] Failed to create TravelBag backups.", exception);
		}
	}

	private PlayerBagData loadInternal(UUID uuid) {
		PlayerBagData data = new PlayerBagData();
		Path path = this.getPlayerPath(uuid);
		if (Files.notExists(path) || this.server == null) {
			return data;
		}

		try (InputStream inputStream = Files.newInputStream(path)) {
			CompoundTag root = NbtIo.readCompressed(inputStream, NbtAccounter.unlimitedHeap());
			if (root == null) {
				return data;
			}

			data.setShortcutGranted(root.getBoolean("ShortcutGranted").orElse(false));
			data.clearDirty();
			ListTag items = root.getList("Items").orElseGet(ListTag::new);
			for (Tag element : items) {
				if (!(element instanceof CompoundTag itemTag)) {
					continue;
				}

				int slot = itemTag.getInt("Slot").orElse(-1);
				if (slot < 0 || slot >= data.size()) {
					continue;
				}

				ItemStack stack = ItemStack.OPTIONAL_CODEC.parse(net.minecraft.nbt.NbtOps.INSTANCE, itemTag.getCompound("Stack").orElse(new CompoundTag())).result().orElse(ItemStack.EMPTY);
				data.setStack(slot, stack);
			}
			data.clearDirty();
		} catch (Exception exception) {
			TravelBagMod.LOGGER.warn("[TravelBag] Failed to load TravelBag data for {}", uuid, exception);
		}

		return data;
	}

	private void save(UUID uuid, PlayerBagData data) {
		if (this.server == null || data == null || !data.isDirty()) {
			return;
		}

		try {
			Files.createDirectories(this.playerDirectory);
			Path target = this.getPlayerPath(uuid);
			Path temp = target.resolveSibling(target.getFileName() + ".tmp");
			CompoundTag root = new CompoundTag();
			root.putInt("DataVersion", DATA_VERSION);
			root.putBoolean("ShortcutGranted", data.isShortcutGranted());

			ListTag items = new ListTag();
			for (int slot = 0; slot < data.size(); slot++) {
				ItemStack stack = data.getStack(slot);
				if (stack.isEmpty()) {
					continue;
				}

				CompoundTag stackTag = new CompoundTag();
				stackTag.putInt("Slot", slot);
				net.minecraft.nbt.Tag encoded = ItemStack.OPTIONAL_CODEC.encodeStart(net.minecraft.nbt.NbtOps.INSTANCE, stack).result().orElse(new CompoundTag());
				stackTag.put("Stack", encoded);
				items.add(stackTag);
			}
			root.put("Items", items);

			try (OutputStream outputStream = Files.newOutputStream(temp)) {
				NbtIo.writeCompressed(root, outputStream);
			}

			Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			data.clearDirty();
		} catch (Exception exception) {
			TravelBagMod.LOGGER.warn("[TravelBag] Failed to save TravelBag data for {}", uuid, exception);
		}
	}

	private Path getPlayerPath(UUID uuid) {
		return this.playerDirectory.resolve(uuid + ".dat");
	}

	private boolean isPrimaryDataFile(Path path) {
		String fileName = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
		return fileName.endsWith(".dat") && !fileName.contains(".backup.");
	}

	private Path toBackupPath(Path source) {
		String fileName = source.getFileName().toString();
		int extensionIndex = fileName.lastIndexOf('.');
		if (extensionIndex < 0) {
			return source.resolveSibling(fileName + ".backup");
		}
		return source.resolveSibling(fileName.substring(0, extensionIndex) + ".backup" + fileName.substring(extensionIndex));
	}
}

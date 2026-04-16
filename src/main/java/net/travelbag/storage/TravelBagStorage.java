package net.travelbag.storage;

import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.travelbag.TravelBagMod;

public final class TravelBagStorage {
	private static final int DATA_VERSION = 1;
	private static final int MAX_BACKUPS_PER_PLAYER = 2;
	private final Path playerDirectory;
	private final Map<UUID, PlayerBagData> cache = new ConcurrentHashMap<>();
	private MinecraftServer server;
	private volatile boolean changesSinceBackup;

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
					this.rotateBackups(path);
					Files.copy(path, this.toBackupPath(path, 1), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
				}
			}
			this.changesSinceBackup = false;
		} catch (IOException exception) {
			TravelBagMod.LOGGER.warn("[TravelBag] Failed to create TravelBag backups.", exception);
		}
	}

	public boolean hasChangesSinceBackup() {
		return this.changesSinceBackup;
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
			DynamicOps<Tag> ops = this.createRegistryAwareNbtOps();
			List<String> loadErrors = new ArrayList<>();
			for (Tag element : items) {
				if (!(element instanceof CompoundTag itemTag)) {
					continue;
				}

				int slot = itemTag.getInt("Slot").orElse(-1);
				if (slot < 0 || slot >= data.size()) {
					continue;
				}
				final int currentSlot = slot;

				CompoundTag stackTag = itemTag.getCompound("Stack").orElse(null);
				if (stackTag == null) {
					loadErrors.add("slot " + currentSlot + ": missing Stack tag");
					continue;
				}

				DataResult<ItemStack> parseResult = ItemStack.OPTIONAL_CODEC.parse(ops, stackTag);
				Optional<ItemStack> parsedStack = parseResult.resultOrPartial(error -> loadErrors.add("slot " + currentSlot + ": " + error));
				if (parsedStack.isPresent()) {
					data.setStack(currentSlot, parsedStack.get());
				}
			}
			data.clearDirty();
			if (!loadErrors.isEmpty()) {
				String reason = "TravelBag data could not be fully decoded from " + path.getFileName() + ". Saving is blocked to prevent item loss.";
				data.blockSaving(reason);
				this.createFailureSnapshot(path, "load-failed");
				TravelBagMod.LOGGER.error("[TravelBag] {} Errors: {}", reason, String.join(" | ", loadErrors));
			}
		} catch (Exception exception) {
			data.blockSaving("TravelBag data could not be loaded safely. Saving is blocked to prevent item loss.");
			this.createFailureSnapshot(path, "load-exception");
			TravelBagMod.LOGGER.warn("[TravelBag] Failed to load TravelBag data for {}", uuid, exception);
		}

		return data;
	}

	private void save(UUID uuid, PlayerBagData data) {
		if (this.server == null || data == null || !data.isDirty()) {
			return;
		}
		if (data.isSaveBlocked()) {
			TravelBagMod.LOGGER.error("[TravelBag] Refusing to save locked TravelBag data for {}. {}", uuid, data.getSaveBlockReason());
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
			DynamicOps<Tag> ops = this.createRegistryAwareNbtOps();
			for (int slot = 0; slot < data.size(); slot++) {
				ItemStack stack = data.getStack(slot);
				if (stack.isEmpty()) {
					continue;
				}
				final int currentSlot = slot;

				CompoundTag stackTag = new CompoundTag();
				stackTag.putInt("Slot", currentSlot);
				DataResult<Tag> encodeResult = ItemStack.OPTIONAL_CODEC.encodeStart(ops, stack);
				Optional<Tag> encodedTag = encodeResult.resultOrPartial(error -> TravelBagMod.LOGGER.error("[TravelBag] Failed to encode bag slot {} for {}: {}", currentSlot, uuid, error));
				if (encodedTag.isEmpty()) {
					throw new IllegalStateException("Failed to encode TravelBag slot " + currentSlot + " for " + uuid);
				}
				net.minecraft.nbt.Tag encoded = encodedTag.get();
				stackTag.put("Stack", encoded);
				items.add(stackTag);
			}
			root.put("Items", items);

			try (OutputStream outputStream = Files.newOutputStream(temp)) {
				NbtIo.writeCompressed(root, outputStream);
			}

			Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			data.clearDirty();
			this.changesSinceBackup = true;
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
		return this.toBackupPath(source, 1);
	}

	private Path toBackupPath(Path source, int index) {
		String fileName = source.getFileName().toString();
		int extensionIndex = fileName.lastIndexOf('.');
		if (extensionIndex < 0) {
			return source.resolveSibling(fileName + ".backup." + index);
		}
		return source.resolveSibling(fileName.substring(0, extensionIndex) + ".backup." + index + fileName.substring(extensionIndex));
	}

	private void rotateBackups(Path source) throws IOException {
		for (int index = MAX_BACKUPS_PER_PLAYER; index >= 1; index--) {
			Path current = this.toBackupPath(source, index);
			if (Files.notExists(current)) {
				continue;
			}

			if (index == MAX_BACKUPS_PER_PLAYER) {
				Files.deleteIfExists(current);
				continue;
			}

			Files.move(current, this.toBackupPath(source, index + 1), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		}
	}

	private void createFailureSnapshot(Path source, String reason) {
		try {
			if (Files.notExists(source) || !Files.isRegularFile(source)) {
				return;
			}
			String fileName = source.getFileName().toString();
			int extensionIndex = fileName.lastIndexOf('.');
			String baseName = extensionIndex < 0 ? fileName : fileName.substring(0, extensionIndex);
			String extension = extensionIndex < 0 ? "" : fileName.substring(extensionIndex);
			Path snapshot = source.resolveSibling(baseName + "." + reason + "." + System.currentTimeMillis() + extension);
			Files.copy(source, snapshot, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
		} catch (IOException exception) {
			TravelBagMod.LOGGER.warn("[TravelBag] Failed to create failure snapshot for {}", source, exception);
		}
	}

	private DynamicOps<Tag> createRegistryAwareNbtOps() {
		return this.server.registryAccess().createSerializationContext(NbtOps.INSTANCE);
	}
}

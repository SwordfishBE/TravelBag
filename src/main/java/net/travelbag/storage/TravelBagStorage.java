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

	public record BackupInfo(int index, String fileName, long modifiedAtMillis, int occupiedSlots, long itemCount) {
	}

	public record RestoreResult(BackupInfo backup, String recoveryFileName) {
	}

	private record DecodedBag(PlayerBagData data, List<String> errors, Exception exception) {
		private boolean isValid() {
			return this.errors.isEmpty() && this.exception == null;
		}

		private String errorSummary() {
			if (!this.errors.isEmpty()) {
				return String.join(" | ", this.errors);
			}
			if (this.exception == null) {
				return "unknown decode error";
			}
			String message = this.exception.getMessage();
			return this.exception.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
		}
	}

	private record ValidatedBackup(Path path, BackupInfo info) {
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

	public boolean isSaveBlocked(UUID uuid) {
		return this.getOrLoad(uuid).isSaveBlocked();
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
					this.createBackup(path);
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

	public BackupInfo inspectBackup(UUID uuid, int index) throws IOException {
		return this.validateBackup(uuid, index).info();
	}

	public synchronized RestoreResult restoreBackup(UUID uuid, int index, long expectedModifiedAtMillis) throws IOException {
		PlayerBagData currentData = this.getOrLoad(uuid);
		if (!currentData.isSaveBlocked()) {
			throw new IOException("The TravelBag is not locked. Restore is only allowed for locked bags.");
		}

		ValidatedBackup validatedBackup = this.validateBackup(uuid, index);
		if (validatedBackup.info().modifiedAtMillis() != expectedModifiedAtMillis) {
			throw new IOException("The selected backup changed after preview. Run the restore command again.");
		}

		Path target = this.getPlayerPath(uuid);
		if (Files.notExists(target)) {
			throw new IOException("The active TravelBag data file no longer exists.");
		}

		Path recovery = this.createSnapshot(target, "pre-restore");
		Path temp = target.resolveSibling(target.getFileName() + ".restore.tmp");
		try {
			Files.copy(validatedBackup.path(), temp, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
			Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

			DecodedBag restored = this.decodeFile(target);
			if (!restored.isValid()) {
				throw new IOException("Restored data failed verification: " + restored.errorSummary());
			}

			this.cache.put(uuid, restored.data());
			return new RestoreResult(validatedBackup.info(), recovery.getFileName().toString());
		} catch (Exception restoreFailure) {
			try {
				Files.deleteIfExists(temp);
			} catch (IOException cleanupFailure) {
				restoreFailure.addSuppressed(cleanupFailure);
			}
			try {
				Path rollbackTemp = target.resolveSibling(target.getFileName() + ".rollback.tmp");
				Files.copy(recovery, rollbackTemp, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
				Files.move(rollbackTemp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (Exception rollbackFailure) {
				restoreFailure.addSuppressed(rollbackFailure);
				TravelBagMod.LOGGER.error("[TravelBag] Failed to roll back TravelBag data for {} after a restore failure.", uuid, rollbackFailure);
			}
			this.cache.put(uuid, currentData);
			if (restoreFailure instanceof IOException ioException) {
				throw ioException;
			}
			throw new IOException("Failed to restore TravelBag backup.", restoreFailure);
		}
	}

	private PlayerBagData loadInternal(UUID uuid) {
		Path path = this.getPlayerPath(uuid);
		if (Files.notExists(path) || this.server == null) {
			return new PlayerBagData();
		}

		DecodedBag decoded = this.decodeFile(path);
		if (decoded.isValid()) {
			return decoded.data();
		}

		String reason = "TravelBag data could not be safely decoded from " + path.getFileName() + ". Saving is blocked to prevent item loss.";
		decoded.data().blockSaving(reason);
		this.createFailureSnapshot(path, decoded.exception() == null ? "load-failed" : "load-exception");
		if (decoded.exception() == null) {
			TravelBagMod.LOGGER.error("[TravelBag] {} Errors: {}", reason, decoded.errorSummary());
		} else {
			TravelBagMod.LOGGER.warn("[TravelBag] Failed to load TravelBag data for {}. {}", uuid, decoded.errorSummary(), decoded.exception());
		}
		return decoded.data();
	}

	private DecodedBag decodeFile(Path path) {
		PlayerBagData data = new PlayerBagData();
		List<String> errors = new ArrayList<>();
		if (this.server == null) {
			errors.add("server registry access is unavailable");
			return new DecodedBag(data, errors, null);
		}

		try (InputStream inputStream = Files.newInputStream(path)) {
			CompoundTag root = NbtIo.readCompressed(inputStream, NbtAccounter.unlimitedHeap());
			if (root == null) {
				errors.add("empty root tag");
				return new DecodedBag(data, errors, null);
			}

			data.setShortcutGranted(root.getBoolean("ShortcutGranted").orElse(false));
			data.clearDirty();
			ListTag items = root.getList("Items").orElseGet(ListTag::new);
			DynamicOps<Tag> ops = this.createRegistryAwareNbtOps();
			boolean[] seenSlots = new boolean[data.size()];
			int elementIndex = 0;
			for (Tag element : items) {
				if (!(element instanceof CompoundTag itemTag)) {
					errors.add("item entry " + elementIndex + ": expected compound tag");
					elementIndex++;
					continue;
				}

				int slot = itemTag.getInt("Slot").orElse(-1);
				if (slot < 0 || slot >= data.size()) {
					errors.add("item entry " + elementIndex + ": invalid slot " + slot);
					elementIndex++;
					continue;
				}
				final int currentSlot = slot;
				if (seenSlots[currentSlot]) {
					errors.add("item entry " + elementIndex + ": duplicate slot " + currentSlot);
					elementIndex++;
					continue;
				}
				seenSlots[currentSlot] = true;

				CompoundTag stackTag = itemTag.getCompound("Stack").orElse(null);
				if (stackTag == null) {
					errors.add("slot " + currentSlot + ": missing Stack tag");
					elementIndex++;
					continue;
				}

				DataResult<ItemStack> parseResult = ItemStack.OPTIONAL_CODEC.parse(ops, stackTag);
				Optional<ItemStack> parsedStack = parseResult.resultOrPartial(error -> errors.add("slot " + currentSlot + ": " + error));
				if (parsedStack.isPresent() && parsedStack.get().isEmpty()) {
					errors.add("slot " + currentSlot + ": decoded to an empty item stack");
				} else {
					parsedStack.ifPresent(stack -> data.setStack(currentSlot, stack));
				}
				elementIndex++;
			}
			data.clearDirty();
			return new DecodedBag(data, errors, null);
		} catch (Exception exception) {
			data.clearDirty();
			return new DecodedBag(data, errors, exception);
		}
	}

	private void save(UUID uuid, PlayerBagData data) {
		if (this.server == null || data == null || !data.isDirty()) {
			return;
		}
		if (data.isSaveBlocked()) {
			TravelBagMod.LOGGER.error("[TravelBag] Refusing to save locked TravelBag data for {}. {}", uuid, data.getSaveBlockReason());
			return;
		}

		Path target = this.getPlayerPath(uuid);
		Path temp = target.resolveSibling(target.getFileName() + ".tmp");
		try {
			Files.createDirectories(this.playerDirectory);
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
				stackTag.put("Stack", encodedTag.get());
				items.add(stackTag);
			}
			root.put("Items", items);

			try (OutputStream outputStream = Files.newOutputStream(temp)) {
				NbtIo.writeCompressed(root, outputStream);
			}
			DecodedBag verification = this.decodeFile(temp);
			if (!verification.isValid()) {
				throw new IOException("New TravelBag data failed verification: " + verification.errorSummary(), verification.exception());
			}

			Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			data.clearDirty();
			this.changesSinceBackup = true;
		} catch (Exception exception) {
			try {
				Files.deleteIfExists(temp);
			} catch (IOException cleanupFailure) {
				exception.addSuppressed(cleanupFailure);
			}
			TravelBagMod.LOGGER.warn("[TravelBag] Failed to save TravelBag data for {}", uuid, exception);
		}
	}

	private ValidatedBackup validateBackup(UUID uuid, int index) throws IOException {
		Path backup = this.resolveBackupPath(uuid, index);
		if (backup == null) {
			throw new IOException(index == 1 ? "No latest backup was found." : "No previous backup was found.");
		}

		DecodedBag decoded = this.decodeFile(backup);
		if (!decoded.isValid()) {
			throw new IOException("Backup " + backup.getFileName() + " could not be decoded safely: " + decoded.errorSummary(), decoded.exception());
		}

		int occupiedSlots = 0;
		long itemCount = 0L;
		for (int slot = 0; slot < decoded.data().size(); slot++) {
			ItemStack stack = decoded.data().getStack(slot);
			if (!stack.isEmpty()) {
				occupiedSlots++;
				itemCount += stack.getCount();
			}
		}
		BackupInfo info = new BackupInfo(index, backup.getFileName().toString(), Files.getLastModifiedTime(backup).toMillis(), occupiedSlots, itemCount);
		return new ValidatedBackup(backup, info);
	}

	private Path resolveBackupPath(UUID uuid, int index) {
		if (index < 1 || index > MAX_BACKUPS_PER_PLAYER) {
			return null;
		}
		Path primary = this.getPlayerPath(uuid);
		Path backup = this.toBackupPath(primary, index);
		if (Files.isRegularFile(backup)) {
			return backup;
		}
		if (index == 1) {
			Path legacyBackup = this.toLegacyBackupPath(primary);
			if (Files.isRegularFile(legacyBackup)) {
				return legacyBackup;
			}
		}
		return null;
	}

	private Path getPlayerPath(UUID uuid) {
		return this.playerDirectory.resolve(uuid + ".dat");
	}

	private boolean isPrimaryDataFile(Path path) {
		String fileName = path.getFileName().toString();
		if (!fileName.toLowerCase(java.util.Locale.ROOT).endsWith(".dat")) {
			return false;
		}
		String uuidPart = fileName.substring(0, fileName.length() - 4);
		try {
			UUID.fromString(uuidPart);
			return true;
		} catch (IllegalArgumentException ignored) {
			return false;
		}
	}

	private void createBackup(Path source) throws IOException {
		Path newest = this.toBackupPath(source, 1);
		if (Files.isRegularFile(newest) && Files.mismatch(source, newest) == -1L) {
			return;
		}
		DecodedBag decoded = this.decodeFile(source);
		if (!decoded.isValid()) {
			TravelBagMod.LOGGER.error("[TravelBag] Refusing to back up invalid TravelBag data file {}. Errors: {}", source.getFileName(), decoded.errorSummary());
			return;
		}

		Path temp = newest.resolveSibling(newest.getFileName() + ".tmp");
		try {
			Files.copy(source, temp, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
			this.rotateBackups(source);
			Files.move(temp, newest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} finally {
			Files.deleteIfExists(temp);
		}
	}

	private Path toBackupPath(Path source, int index) {
		String fileName = source.getFileName().toString();
		int extensionIndex = fileName.lastIndexOf('.');
		if (extensionIndex < 0) {
			return source.resolveSibling(fileName + ".backup." + index);
		}
		return source.resolveSibling(fileName.substring(0, extensionIndex) + ".backup." + index + fileName.substring(extensionIndex));
	}

	private Path toLegacyBackupPath(Path source) {
		String fileName = source.getFileName().toString();
		int extensionIndex = fileName.lastIndexOf('.');
		if (extensionIndex < 0) {
			return source.resolveSibling(fileName + ".backup");
		}
		return source.resolveSibling(fileName.substring(0, extensionIndex) + ".backup" + fileName.substring(extensionIndex));
	}

	private void rotateBackups(Path source) throws IOException {
		for (int index = MAX_BACKUPS_PER_PLAYER - 1; index >= 1; index--) {
			Path current = this.toBackupPath(source, index);
			if (Files.notExists(current)) {
				continue;
			}
			Files.move(current, this.toBackupPath(source, index + 1), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		}
	}

	private void createFailureSnapshot(Path source, String reason) {
		try {
			this.createSnapshot(source, reason);
		} catch (IOException exception) {
			TravelBagMod.LOGGER.warn("[TravelBag] Failed to create failure snapshot for {}", source, exception);
		}
	}

	private Path createSnapshot(Path source, String reason) throws IOException {
		if (Files.notExists(source) || !Files.isRegularFile(source)) {
			throw new IOException("Cannot snapshot missing TravelBag data file " + source.getFileName());
		}
		String fileName = source.getFileName().toString();
		int extensionIndex = fileName.lastIndexOf('.');
		String baseName = extensionIndex < 0 ? fileName : fileName.substring(0, extensionIndex);
		String extension = extensionIndex < 0 ? "" : fileName.substring(extensionIndex);
		Path snapshot = source.resolveSibling(baseName + "." + reason + "." + System.currentTimeMillis() + extension);
		Files.copy(source, snapshot, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
		return snapshot;
	}

	private DynamicOps<Tag> createRegistryAwareNbtOps() {
		return this.server.registryAccess().createSerializationContext(NbtOps.INSTANCE);
	}
}

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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

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
	private final java.util.Set<UUID> failedWrites = ConcurrentHashMap.newKeySet();
	private MinecraftServer server;
	private volatile boolean changesSinceBackup;

	public TravelBagStorage(Path playerDirectory) {
		this.playerDirectory = playerDirectory;
	}

	public static Path resolvePlayerDirectory(boolean dedicatedServer, Path configDirectory, Path worldDirectory) {
		return (dedicatedServer ? configDirectory : worldDirectory.resolve(TravelBagMod.MOD_ID)).resolve("players");
	}

	public Path getPlayerDirectory() {
		return this.playerDirectory;
	}

	public record LegacyBagInfo(String fingerprint, int occupiedSlots, long itemCount) {
	}

	public LegacyBagInfo inspectLegacyUpgrade(UUID uuid, int backupIndex, boolean legacySounds) throws IOException {
		Path path = this.upgradePath(uuid, backupIndex);
		String before = fingerprint(path);
		DecodedBag decoded = this.decodeFile(path, legacySounds ? ItemDataMigration.LEGACY_26_3_VERSION : ItemDataMigration.currentVersion());
		if (!decoded.isValid()) {
			throw new IOException("Cannot upgrade this file safely: " + decoded.errorSummary(), decoded.exception());
		}
		if (!before.equals(fingerprint(path))) {
			throw new IOException("The file changed during preview.");
		}
		int slots = 0;
		long items = 0;
		for (int slot = 0; slot < decoded.data().size(); slot++) {
			if (!decoded.data().getStack(slot).isEmpty()) {
				slots++;
				items += decoded.data().getStack(slot).getCount();
			}
		}
		return new LegacyBagInfo(before, slots, items);
	}

	public synchronized Path upgradeLegacyFile(UUID uuid, int backupIndex, boolean legacySounds, String expectedFingerprint) throws IOException {
		if (!this.getOrLoad(uuid).isSaveBlocked()) {
			throw new IOException("The bag must be locked before choosing a legacy data version.");
		}
		Path target = this.upgradePath(uuid, backupIndex);
		if (!this.inspectLegacyUpgrade(uuid, backupIndex, legacySounds).fingerprint().equals(expectedFingerprint)) {
			throw new IOException("The file changed after preview. Preview it again.");
		}
		CompoundTag root;
		try (InputStream input = Files.newInputStream(target)) {
			root = NbtIo.readCompressed(input, NbtAccounter.unlimitedHeap());
		}
		root.putInt(ItemDataMigration.VERSION_KEY, legacySounds ? ItemDataMigration.LEGACY_26_3_VERSION : ItemDataMigration.currentVersion());
		Path temp = Files.createTempFile(this.playerDirectory, uuid + ".upgrade-", ".tmp");
		try {
			try (OutputStream output = Files.newOutputStream(temp)) {
				NbtIo.writeCompressed(root, output);
			}
			DecodedBag verified = this.decodeFile(temp);
			if (!verified.isValid() || !fingerprint(target).equals(expectedFingerprint)) {
				throw new IOException("Upgrade verification failed or the original changed.");
			}
			Path recovery = this.createSnapshot(target, "pre-upgrade");
			Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			if (backupIndex == 0) {
				this.cache.put(uuid, verified.data());
				this.failedWrites.remove(uuid);
				this.changesSinceBackup = true;
			}
			return recovery;
		} finally {
			Files.deleteIfExists(temp);
		}
	}

	private Path upgradePath(UUID uuid, int backupIndex) throws IOException {
		Path path = backupIndex == 0 ? this.getPlayerPath(uuid) : this.resolveBackupPath(uuid, backupIndex);
		if (path == null || !Files.isRegularFile(path)) {
			throw new IOException("The selected TravelBag file does not exist.");
		}
		return path;
	}

	public LegacyBagInfo inspectLegacyBag(UUID uuid, Path legacyDirectory) throws IOException {
		Path source = legacyDirectory.resolve(uuid + ".dat");
		if (!Files.isRegularFile(source)) {
			throw new IOException("No old central TravelBag was found for this player.");
		}
		String before = fingerprint(source);
		DecodedBag decoded = this.decodeFile(source);
		if (!decoded.isValid()) {
			throw new IOException("The old TravelBag cannot be decoded safely: " + decoded.errorSummary(), decoded.exception());
		}
		if (!before.equals(fingerprint(source))) {
			throw new IOException("The old TravelBag changed while being inspected. Try again.");
		}
		int slots = 0;
		long items = 0;
		for (int slot = 0; slot < decoded.data().size(); slot++) {
			ItemStack stack = decoded.data().getStack(slot);
			if (!stack.isEmpty()) {
				slots++;
				items += stack.getCount();
			}
		}
		return new LegacyBagInfo(before, slots, items);
	}

	public synchronized Path migrateLegacyBag(UUID uuid, Path legacyDirectory, String expectedFingerprint) throws IOException {
		Path source = legacyDirectory.resolve(uuid + ".dat");
		Path target = this.getPlayerPath(uuid);
		if (source.toAbsolutePath().normalize().equals(target.toAbsolutePath().normalize())) {
			throw new IOException("Migration requires separate world storage.");
		}
		PlayerBagData current = this.getOrLoad(uuid);
		if (current.isSaveBlocked() || !current.isEmpty()) {
			throw new IOException("The current world's TravelBag must be empty and unlocked.");
		}
		if (!this.inspectLegacyBag(uuid, legacyDirectory).fingerprint().equals(expectedFingerprint)) {
			throw new IOException("The old TravelBag changed after preview. Preview it again.");
		}
		Files.createDirectories(this.playerDirectory);
		Path temp = Files.createTempFile(this.playerDirectory, uuid + ".migration-", ".tmp");
		Path archive = source.resolveSibling(uuid + ".migrated." + UUID.randomUUID() + ".dat");
		boolean claimed = false;
		try {
			Files.copy(source, temp, StandardCopyOption.REPLACE_EXISTING);
			DecodedBag imported = this.decodeFile(temp);
			if (!imported.isValid() || !fingerprint(temp).equals(expectedFingerprint)) {
				throw new IOException("Staged migration data failed verification.");
			}
			if (Files.exists(target)) {
				this.createSnapshot(target, "pre-migration");
			}
			// Claim the source first: another world must never import the same active file.
			Files.move(source, archive, StandardCopyOption.ATOMIC_MOVE);
			claimed = true;
			if (!fingerprint(archive).equals(expectedFingerprint)) {
				throw new IOException("The old TravelBag changed during migration.");
			}
			Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			this.cache.put(uuid, imported.data());
			this.failedWrites.remove(uuid);
			this.changesSinceBackup = true;
			return archive;
		} catch (Exception failure) {
			if (claimed) {
				try {
					// Do not replace any file that appeared at the original path meanwhile.
					if (Files.exists(source)) {
						throw new IOException("The original migration source path is occupied.");
					}
					Files.move(archive, source, StandardCopyOption.ATOMIC_MOVE);
				} catch (IOException rollbackFailure) {
					failure.addSuppressed(rollbackFailure);
					TravelBagMod.LOGGER.error("[TravelBag] Migration rollback failed. Original data is preserved at {}.", archive, rollbackFailure);
				}
			}
			throw new IOException("TravelBag migration failed. Check the log before retrying.", failure);
		} finally {
			try {
				Files.deleteIfExists(temp);
			} catch (IOException exception) {
				TravelBagMod.LOGGER.warn("[TravelBag] Could not remove migration staging file {}.", temp, exception);
			}
		}
	}

	private static String fingerprint(Path path) throws IOException {
		try (InputStream input = Files.newInputStream(path)) {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] buffer = new byte[8192];
			int read;
			while ((read = input.read(buffer)) != -1) {
				digest.update(buffer, 0, read);
			}
			return java.util.HexFormat.of().formatHex(digest.digest());
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable.", exception);
		}
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
		if (this.server == null) {
			throw new IllegalStateException("TravelBag storage is not attached to a server.");
		}
		return this.cache.computeIfAbsent(uuid, this::loadInternal);
	}

	public void evictUnused(Predicate<UUID> inUse) {
		this.cache.entrySet().removeIf(entry -> {
			if (inUse.test(entry.getKey())) {
				return false;
			}
			PlayerBagData data = entry.getValue();
			if (data.isSaveBlocked()) {
				this.failedWrites.remove(entry.getKey());
				return true; // The unchanged source file remains authoritative for locked bags.
			}
			this.save(entry.getKey(), data);
			if (!data.isDirty()) {
				this.failedWrites.remove(entry.getKey());
				return true;
			}
			return false;
		});
	}

	public void close() {
		try {
			if (!this.saveAll()) {
				TravelBagMod.LOGGER.error("[TravelBag] Some bags could not be saved before shutdown. Check earlier storage errors.");
			}
		} finally {
			this.cache.clear();
			this.failedWrites.clear();
			this.server = null;
			this.changesSinceBackup = false;
		}
	}

	public boolean isSaveBlocked(UUID uuid) {
		return this.getOrLoad(uuid).isSaveBlocked();
	}

	public boolean hasWriteFailure(UUID uuid) {
		return this.failedWrites.contains(uuid);
	}

	public boolean save(UUID uuid) {
		PlayerBagData data = this.cache.get(uuid);
		return data == null || this.save(uuid, data);
	}

	public boolean saveAll() {
		boolean success = true;
		for (Map.Entry<UUID, PlayerBagData> entry : this.cache.entrySet()) {
			this.save(entry.getKey(), entry.getValue());
			if (entry.getValue().isDirty() && !entry.getValue().isSaveBlocked()) {
				success = false;
			}
		}
		return success;
	}

	public boolean createBackups() {
		boolean success = true;
		try {
			Files.createDirectories(this.playerDirectory);
			try (DirectoryStream<Path> stream = Files.newDirectoryStream(this.playerDirectory)) {
				for (Path path : stream) {
					if (!Files.isRegularFile(path) || !this.isPrimaryDataFile(path)) {
						continue;
					}
					try {
						this.createBackup(path);
					} catch (IOException exception) {
						success = false;
						TravelBagMod.LOGGER.warn("[TravelBag] Failed to back up {}.", path, exception);
					}
				}
			}
		} catch (IOException exception) {
			success = false;
			TravelBagMod.LOGGER.warn("[TravelBag] Failed to create TravelBag backups.", exception);
		}
		this.changesSinceBackup = !success;
		return success;
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
			this.failedWrites.remove(uuid);
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
		if (decoded.exception() instanceof ItemDataMigration.ChoiceRequiredException) {
			decoded.data().requireLegacyVersionChoice();
		}
		this.createFailureSnapshot(path, decoded.exception() == null ? "load-failed" : "load-exception");
		if (decoded.exception() == null) {
			TravelBagMod.LOGGER.error("[TravelBag] {} Errors: {}", reason, decoded.errorSummary());
		} else {
			TravelBagMod.LOGGER.warn("[TravelBag] Failed to load TravelBag data for {}. {}", uuid, decoded.errorSummary(), decoded.exception());
		}
		return decoded.data();
	}

	private DecodedBag decodeFile(Path path) {
		return this.decodeFile(path, null);
	}

	private DecodedBag decodeFile(Path path, Integer chosenVersion) {
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
			if (chosenVersion != null) {
				if (root.contains(ItemDataMigration.VERSION_KEY)) {
					throw new IOException("This file already records a Minecraft data version.");
				}
				root.putInt(ItemDataMigration.VERSION_KEY, chosenVersion);
			}
			Integer minecraftVersion = ItemDataMigration.readVersion(root);

			data.setShortcutGranted(root.getBoolean("ShortcutGranted").orElse(false));
			data.clearDirty();
			ListTag items = readItems(root);
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

				DataResult<ItemStack> parseResult = ItemStack.OPTIONAL_CODEC.parse(ops, ItemDataMigration.upgradeStack(stackTag, minecraftVersion));
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

	private boolean save(UUID uuid, PlayerBagData data) {
		if (data == null || !data.isDirty()) {
			return true;
		}
		if (this.server == null) {
			return false;
		}
		if (data.isSaveBlocked()) {
			TravelBagMod.LOGGER.error("[TravelBag] Refusing to save locked TravelBag data for {}. {}", uuid, data.getSaveBlockReason());
			return false;
		}

		Path target = this.getPlayerPath(uuid);
		Path temp = target.resolveSibling(target.getFileName() + ".tmp");
		try {
			Files.createDirectories(this.playerDirectory);
			CompoundTag root = new CompoundTag();
			root.putInt("DataVersion", DATA_VERSION);
			root.putInt(ItemDataMigration.VERSION_KEY, ItemDataMigration.currentVersion());
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
				stackTag.put("Stack", requireCompleteEncoding(encodeResult));
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
			this.failedWrites.remove(uuid);
			return true;
		} catch (Exception exception) {
			try {
				Files.deleteIfExists(temp);
			} catch (IOException cleanupFailure) {
				exception.addSuppressed(cleanupFailure);
			}
			if (this.failedWrites.add(uuid)) {
				TravelBagMod.LOGGER.warn("[TravelBag] Failed to save TravelBag data for {}. Repeated failures are logged at DEBUG until a save succeeds.", uuid, exception);
			} else {
				TravelBagMod.LOGGER.debug("[TravelBag] Still unable to save TravelBag data for {}.", uuid, exception);
			}
			return false;
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

	private static ListTag readItems(CompoundTag root) throws IOException {
		// Older files may omit DataVersion; the Items list is part of the original format.
		if (root.contains("DataVersion") && root.getInt("DataVersion").orElse(-1) != DATA_VERSION) {
			throw new IOException("Unsupported TravelBag DataVersion.");
		}
		return root.getList("Items").orElseThrow(() -> new IOException("Missing or invalid Items list."));
	}

	private static Tag requireCompleteEncoding(DataResult<Tag> result) throws IOException {
		return result.result().orElseThrow(() -> new IOException("Incomplete item encoding: "
			+ result.error().map(error -> error.message()).orElse("no encoded value")));
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
		DecodedBag decoded = this.decodeFile(source);
		if (!decoded.isValid()) {
			throw new IOException("Refusing to back up invalid data: " + decoded.errorSummary(), decoded.exception());
		}
		if (Files.isRegularFile(newest) && Files.mismatch(source, newest) == -1L) {
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

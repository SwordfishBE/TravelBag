package net.travelbag.command;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.travelbag.TravelBagMod;
import net.travelbag.storage.TravelBagStorage.BackupInfo;
import net.travelbag.storage.TravelBagStorage.RestoreResult;

public final class TravelBagCommands {
	private static final long RESTORE_CONFIRMATION_MILLIS = 60_000L;
	private static final DateTimeFormatter BACKUP_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z").withZone(ZoneId.systemDefault());
	private static final Map<RestoreKey, PendingRestore> PENDING_RESTORES = new ConcurrentHashMap<>();
	private static final Map<UUID, PendingMigration> PENDING_MIGRATIONS = new ConcurrentHashMap<>();

	private record RestoreKey(String actorId, UUID ownerUuid, int backupIndex) {
	}

	private record PendingRestore(long expiresAtMillis, long backupModifiedAtMillis) {
	}

	private record PendingMigration(long expiresAtMillis, String fingerprint) {
	}

	private TravelBagCommands() {
	}

	public static void clearPendingRestores() {
		PENDING_RESTORES.clear();
		PENDING_MIGRATIONS.clear();
		TravelBagUpgradeCommands.clear();
	}

	public static void expirePendingRestores() {
		long now = System.currentTimeMillis();
		PENDING_RESTORES.entrySet().removeIf(entry -> entry.getValue().expiresAtMillis() < now);
		PENDING_MIGRATIONS.entrySet().removeIf(entry -> entry.getValue().expiresAtMillis() < now);
		TravelBagUpgradeCommands.expire();
	}

	public static void register(TravelBagMod mod, CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(createRoot("travelbag", mod));
		for (String alias : mod.getConfig().getAliases()) {
			dispatcher.register(createRoot(alias, mod));
		}
	}

	private static LiteralArgumentBuilder<CommandSourceStack> createRoot(String name, TravelBagMod mod) {
		return Commands.literal(name)
			.executes(context -> openSelf(mod, context.getSource()))
			.then(Commands.literal("help")
				.executes(context -> help(mod, context.getSource())))
			.then(Commands.literal("migrate")
				.requires(source -> source.getEntity() instanceof ServerPlayer player && mod.canMigrateLegacyBag(player))
				.executes(context -> previewMigration(mod, context.getSource()))
				.then(Commands.literal("confirm")
					.executes(context -> confirmMigration(mod, context.getSource()))))
			.then(Commands.literal("backup")
				.requires(source -> source.getEntity() == null ? mod.getPermissionService().canRunBackup(source) : source.getEntity() instanceof ServerPlayer player && mod.getPermissionService().canRunBackup(player))
				.executes(context -> backup(mod, context.getSource())))
			.then(Commands.literal("reload")
				.requires(source -> source.getEntity() == null ? mod.getPermissionService().canRunReload(source) : source.getEntity() instanceof ServerPlayer player && mod.getPermissionService().canRunReload(player))
				.executes(context -> reload(mod, context.getSource())))
			.then(Commands.literal("restore")
				.requires(source -> canRunRecovery(mod, source))
				.then(Commands.argument("player", GameProfileArgument.gameProfile())
					.then(createRestoreChoice("latest", 1, mod))
					.then(createRestoreChoice("previous", 2, mod))))
			.then(TravelBagUpgradeCommands.create(mod))
			.then(Commands.literal("sort")
				.executes(context -> sortSelf(mod, context.getSource())))
			.then(Commands.literal("clean")
				.executes(context -> cleanSelf(mod, context.getSource()))
				.then(Commands.argument("player", GameProfileArgument.gameProfile())
					.executes(context -> cleanOther(mod, context.getSource(), getSingleProfile(context)))))
			.then(Commands.argument("player", GameProfileArgument.gameProfile())
				.executes(context -> openOther(mod, context.getSource(), getSingleProfile(context))));
	}

	private static int help(TravelBagMod mod, CommandSourceStack source) {
		source.sendSuccess(() -> Component.literal("/travelbag - Open your TravelBag"), false);
		source.sendSuccess(() -> Component.literal("/travelbag help - Show TravelBag commands"), false);
		ServerPlayer player = getPlayer(source);
		if (player != null && mod.canMigrateLegacyBag(player)) {
			source.sendSuccess(() -> Component.literal("/travelbag migrate - Preview old central data and migration options"), false);
		}
		if (player != null && mod.getPermissionService().canSortOwn(player)) {
			source.sendSuccess(() -> Component.literal("/travelbag sort - Compact and sort your TravelBag"), false);
		}
		if (player != null && mod.getPermissionService().canCleanOwn(player)) {
			source.sendSuccess(() -> Component.literal("/travelbag clean - Remove all items from your TravelBag"), false);
		}
		if (player != null && mod.getPermissionService().canOpenOthers(player)) {
			source.sendSuccess(() -> Component.literal("/travelbag <player> - Open another player's TravelBag"), false);
		}
		if (player != null && mod.getPermissionService().canCleanOther(player)) {
			source.sendSuccess(() -> Component.literal("/travelbag clean <player> - Remove all items from another TravelBag"), false);
		}
		if (player == null ? mod.getPermissionService().canRunBackup(source) : mod.getPermissionService().canRunBackup(player)) {
			source.sendSuccess(() -> Component.literal("/travelbag backup - Create TravelBag backups"), false);
		}
		if (player == null ? mod.getPermissionService().canRunReload(source) : mod.getPermissionService().canRunReload(player)) {
			source.sendSuccess(() -> Component.literal("/travelbag reload - Reload the TravelBag config"), false);
		}
		if (canRunRecovery(mod, source)) {
			source.sendSuccess(() -> Component.literal("/travelbag restore <player> <latest|previous> - Preview and restore a locked TravelBag backup"), false);
			source.sendSuccess(() -> Component.literal("/travelbag upgrade <player> <legacy|current> [latest|previous] - Preview the data version choice for a locked bag or backup"), false);
		}
		return Command.SINGLE_SUCCESS;
	}

	private static LiteralArgumentBuilder<CommandSourceStack> createRestoreChoice(String name, int backupIndex, TravelBagMod mod) {
		return Commands.literal(name)
			.executes(context -> previewRestore(mod, context.getSource(), getSingleProfile(context), backupIndex))
			.then(Commands.literal("confirm")
				.executes(context -> confirmRestore(mod, context.getSource(), getSingleProfile(context), backupIndex)));
	}

	private static int openSelf(TravelBagMod mod, CommandSourceStack source) {
		ServerPlayer player = getPlayer(source);
		if (player == null) {
			source.sendFailure(Component.literal("Only players can open their own TravelBag."));
			return 0;
		}
		mod.openOwnBag(player);
		return Command.SINGLE_SUCCESS;
	}

	private static int sortSelf(TravelBagMod mod, CommandSourceStack source) {
		ServerPlayer player = getPlayer(source);
		if (player == null) {
			source.sendFailure(Component.literal("Only players can sort their own TravelBag."));
			return 0;
		}
		if (!mod.getPermissionService().canSortOwn(player)) {
			source.sendFailure(Component.literal("You do not have permission to sort your TravelBag."));
			return 0;
		}
		if (mod.getStorage().isSaveBlocked(player.getUUID())) {
			source.sendFailure(Component.literal("Your TravelBag is locked to prevent item loss."));
			return 0;
		}

		boolean changed = mod.compactBag(player.getUUID());
		if (!changed && mod.getStorage().hasWriteFailure(player.getUUID())) {
			source.sendFailure(Component.literal("TravelBag could not save the sorting operation. Items were kept. Check the server log."));
			return 0;
		}
		source.sendSuccess(() -> Component.literal(changed ? "Your TravelBag has been sorted." : "Your TravelBag was already compact."), false);
		return Command.SINGLE_SUCCESS;
	}

	private static int openOther(TravelBagMod mod, CommandSourceStack source, NameAndId profile) {
		ServerPlayer player = getPlayer(source);
		if (player == null) {
			source.sendFailure(Component.literal("Only players can open another player's TravelBag."));
			return 0;
		}
		mod.openBag(player, new com.mojang.authlib.GameProfile(profile.id(), profile.name()), true, true);
		return Command.SINGLE_SUCCESS;
	}

	private static int cleanSelf(TravelBagMod mod, CommandSourceStack source) {
		ServerPlayer player = getPlayer(source);
		if (player == null) {
			source.sendFailure(Component.literal("Only players can clean their own TravelBag."));
			return 0;
		}
		if (!mod.getPermissionService().canCleanOwn(player)) {
			source.sendFailure(Component.literal("You do not have permission to clean your TravelBag."));
			return 0;
		}
		if (!mod.cleanBag(player.getUUID())) {
			source.sendFailure(Component.literal("Your TravelBag could not be cleaned because it is locked or storage is unavailable. Items were kept."));
			return 0;
		}
		source.sendSuccess(() -> Component.literal("Your TravelBag has been cleaned."), false);
		return Command.SINGLE_SUCCESS;
	}

	private static int cleanOther(TravelBagMod mod, CommandSourceStack source, NameAndId profile) {
		ServerPlayer player = getPlayer(source);
		if (player == null) {
			source.sendFailure(Component.literal("Only players can clean another TravelBag."));
			return 0;
		}
		if (!mod.getPermissionService().canCleanOther(player)) {
			source.sendFailure(Component.literal("You do not have permission to clean another player's TravelBag."));
			return 0;
		}
		if (!mod.cleanBag(profile.id())) {
			source.sendFailure(Component.literal("That TravelBag could not be cleaned because it is locked or storage is unavailable. Items were kept."));
			return 0;
		}
		source.sendSuccess(() -> Component.literal("Cleaned TravelBag of " + profile.name() + "."), false);
		return Command.SINGLE_SUCCESS;
	}

	private static int backup(TravelBagMod mod, CommandSourceStack source) {
		if (!mod.backupNow()) {
			source.sendFailure(Component.literal("TravelBag backup was incomplete or failed. Check the server log."));
			return 0;
		}
		source.sendSuccess(() -> Component.literal("TravelBag backups created."), false);
		return Command.SINGLE_SUCCESS;
	}

	private static boolean canMigrate(TravelBagMod mod, CommandSourceStack source, ServerPlayer player) {
		if (player == null || !mod.canMigrateLegacyBag(player)) {
			source.sendFailure(Component.literal("Only the singleplayer world owner can migrate their own old TravelBag."));
			return false;
		}
		if (mod.hasOpenBag(player.getUUID())) {
			source.sendFailure(Component.literal("Close all viewers of your TravelBag before migrating."));
			return false;
		}
		var data = mod.getStorage().getOrLoad(player.getUUID());
		if (data.isSaveBlocked() || !data.isEmpty()) {
			source.sendFailure(Component.literal("This world's TravelBag must be empty and unlocked before migration."));
			return false;
		}
		return true;
	}

	private static int previewMigration(TravelBagMod mod, CommandSourceStack source) {
		ServerPlayer player = getPlayer(source);
		if (player == null || !mod.canMigrateLegacyBag(player)) {
			return 0;
		}
		String fileName = player.getUUID() + ".dat";
		source.sendSuccess(() -> Component.literal("Manual migration: exit the world first. Move "
			+ mod.getLegacyPlayerDirectory().resolve(fileName) + " to " + mod.getStorage().getPlayerDirectory().resolve(fileName)
			+ ". Do not overwrite an existing bag with items. Keep old backups for recovery; do not reuse the same data in another world."), false);
		if (!canMigrate(mod, source, player)) {
			return 0;
		}
		try {
			var preview = mod.getStorage().inspectLegacyBag(player.getUUID(), mod.getLegacyPlayerDirectory());
			PENDING_MIGRATIONS.put(player.getUUID(), new PendingMigration(System.currentTimeMillis() + RESTORE_CONFIRMATION_MILLIS, preview.fingerprint()));
			source.sendSuccess(() -> Component.literal("Old TravelBag is valid: " + preview.occupiedSlots() + " occupied slot(s), "
				+ preview.itemCount() + " item(s). Run /travelbag migrate confirm within 60 seconds to import it into this world only."
				+ " The central file will be archived to prevent repeat imports; its old backups remain untouched."), false);
			return Command.SINGLE_SUCCESS;
		} catch (IOException exception) {
			PENDING_MIGRATIONS.remove(player.getUUID());
			source.sendFailure(Component.literal("Cannot migrate TravelBag: " + exception.getMessage()));
			return 0;
		}
	}

	private static int confirmMigration(TravelBagMod mod, CommandSourceStack source) {
		ServerPlayer player = getPlayer(source);
		if (!canMigrate(mod, source, player)) {
			return 0;
		}
		PendingMigration pending = PENDING_MIGRATIONS.remove(player.getUUID());
		if (pending == null || pending.expiresAtMillis() < System.currentTimeMillis()) {
			source.sendFailure(Component.literal("Migration confirmation is missing or expired. Run /travelbag migrate first."));
			return 0;
		}
		try {
			var archive = mod.getStorage().migrateLegacyBag(player.getUUID(), mod.getLegacyPlayerDirectory(), pending.fingerprint());
			TravelBagMod.LOGGER.info("[TravelBag] Migrated legacy bag for {} into {}. Original archived at {}.",
				player.getUUID(), mod.getStorage().getPlayerDirectory(), archive);
			boolean backedUp = mod.getStorage().createBackups();
			source.sendSuccess(() -> Component.literal("Your old TravelBag has been imported into this world."
				+ (backedUp ? " A world-local backup was created." : " Backup creation failed; check the log. The imported bag and original archive are preserved.")), false);
			return Command.SINGLE_SUCCESS;
		} catch (IOException exception) {
			TravelBagMod.LOGGER.warn("[TravelBag] Legacy bag migration failed for {}.", player.getUUID(), exception);
			source.sendFailure(Component.literal(exception.getMessage()));
			return 0;
		}
	}

	private static int reload(TravelBagMod mod, CommandSourceStack source) {
		try {
			mod.reload();
			source.sendSuccess(() -> Component.literal("TravelBag config reloaded. Aliases update after restart."), false);
			return Command.SINGLE_SUCCESS;
		} catch (IOException exception) {
			source.sendFailure(Component.literal("Failed to reload TravelBag config. See server log."));
			return 0;
		}
	}

	private static int previewRestore(TravelBagMod mod, CommandSourceStack source, NameAndId profile, int backupIndex) {
		if (!canRestoreTarget(mod, source, profile)) {
			return 0;
		}

		try {
			BackupInfo backup = mod.getStorage().inspectBackup(profile.id(), backupIndex);
			long now = System.currentTimeMillis();
			PENDING_RESTORES.entrySet().removeIf(entry -> entry.getValue().expiresAtMillis() < now);
			RestoreKey key = new RestoreKey(getActorId(source), profile.id(), backupIndex);
			PENDING_RESTORES.put(key, new PendingRestore(now + RESTORE_CONFIRMATION_MILLIS, backup.modifiedAtMillis()));

			String backupName = backupIndex == 1 ? "latest" : "previous";
			String modifiedAt = BACKUP_TIME_FORMAT.format(Instant.ofEpochMilli(backup.modifiedAtMillis()));
			source.sendSuccess(() -> Component.literal("Backup is valid: " + backup.fileName() + " from " + modifiedAt + ", " + backup.occupiedSlots() + " occupied slot(s), " + backup.itemCount() + " item(s)."), false);
			source.sendSuccess(() -> Component.literal("Run /travelbag restore " + profile.name() + " " + backupName + " confirm within 60 seconds to restore it."), false);
			return Command.SINGLE_SUCCESS;
		} catch (IOException exception) {
			source.sendFailure(Component.literal("Cannot restore TravelBag: " + exception.getMessage()));
			return 0;
		}
	}

	private static int confirmRestore(TravelBagMod mod, CommandSourceStack source, NameAndId profile, int backupIndex) {
		RestoreKey key = new RestoreKey(getActorId(source), profile.id(), backupIndex);
		PendingRestore pending = PENDING_RESTORES.remove(key);
		long now = System.currentTimeMillis();
		if (pending == null || pending.expiresAtMillis() < now) {
			source.sendFailure(Component.literal("Restore confirmation is missing or expired. Preview the backup again first."));
			return 0;
		}
		if (!canRestoreTarget(mod, source, profile)) {
			return 0;
		}

		try {
			RestoreResult result = mod.getStorage().restoreBackup(profile.id(), backupIndex, pending.backupModifiedAtMillis());
			String actorName = source.getEntity() instanceof ServerPlayer player ? player.getGameProfile().name() : "console";
			TravelBagMod.LOGGER.info("[TravelBag] {} restored {} for {} from {}. Recovery snapshot: {}", actorName, result.backup().fileName(), profile.id(), profile.name(), result.recoveryFileName());
			source.sendSuccess(() -> Component.literal("Restored TravelBag of " + profile.name() + " from " + result.backup().fileName() + "."), true);
			return Command.SINGLE_SUCCESS;
		} catch (IOException exception) {
			source.sendFailure(Component.literal("TravelBag restore failed: " + exception.getMessage()));
			return 0;
		}
	}

	static boolean canRunRecovery(TravelBagMod mod, CommandSourceStack source) {
		return source.getEntity() instanceof ServerPlayer player
			? mod.getPermissionService().canRunRestore(player) || mod.canMigrateLegacyBag(player)
			: source.getEntity() == null && mod.getPermissionService().canRunRestore(source);
	}

	static boolean canRestoreTarget(TravelBagMod mod, CommandSourceStack source, NameAndId profile) {
		boolean ownIntegratedBag = source.getEntity() instanceof ServerPlayer actor
			&& actor.getUUID().equals(profile.id()) && mod.canMigrateLegacyBag(actor);
		if (!ownIntegratedBag && !mod.getPermissionService().canRunRestore(source)) {
			source.sendFailure(Component.literal("You may only recover your own singleplayer TravelBag."));
			return false;
		}
		if (source.getServer().getPlayerList().getPlayer(profile.id()) != null && !ownIntegratedBag) {
			source.sendFailure(Component.literal("The target player must be offline before restoring their TravelBag."));
			return false;
		}
		if (mod.hasOpenBag(profile.id())) {
			source.sendFailure(Component.literal("The TravelBag must be closed by all viewers before it can be restored."));
			return false;
		}
		if (!mod.getStorage().isSaveBlocked(profile.id())) {
			source.sendFailure(Component.literal("This TravelBag is not locked. Restore is only allowed for locked bags."));
			return false;
		}
		return true;
	}

	private static String getActorId(CommandSourceStack source) {
		return source.getEntity() instanceof ServerPlayer player ? player.getUUID().toString() : "console";
	}

	private static ServerPlayer getPlayer(CommandSourceStack source) {
		return source.getEntity() instanceof ServerPlayer player ? player : null;
	}

	private static NameAndId getSingleProfile(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		Collection<NameAndId> profiles = GameProfileArgument.getGameProfiles(context, "player");
		return profiles.iterator().next();
	}
}

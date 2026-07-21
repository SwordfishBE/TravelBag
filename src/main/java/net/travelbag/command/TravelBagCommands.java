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

	private record RestoreKey(String actorId, UUID ownerUuid, int backupIndex) {
	}

	private record PendingRestore(long expiresAtMillis, long backupModifiedAtMillis) {
	}

	private TravelBagCommands() {
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
			.then(Commands.literal("backup")
				.requires(source -> source.getEntity() == null ? mod.getPermissionService().canRunBackup(source) : source.getEntity() instanceof ServerPlayer player && mod.getPermissionService().canRunBackup(player))
				.executes(context -> backup(mod, context.getSource())))
			.then(Commands.literal("reload")
				.requires(source -> source.getEntity() == null ? mod.getPermissionService().canRunReload(source) : source.getEntity() instanceof ServerPlayer player && mod.getPermissionService().canRunReload(player))
				.executes(context -> reload(mod, context.getSource())))
			.then(Commands.literal("restore")
				.requires(source -> source.getEntity() == null ? mod.getPermissionService().canRunRestore(source) : source.getEntity() instanceof ServerPlayer player && mod.getPermissionService().canRunRestore(player))
				.then(Commands.argument("player", GameProfileArgument.gameProfile())
					.then(createRestoreChoice("latest", 1, mod))
					.then(createRestoreChoice("previous", 2, mod))))
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
		if (player == null ? mod.getPermissionService().canRunRestore(source) : mod.getPermissionService().canRunRestore(player)) {
			source.sendSuccess(() -> Component.literal("/travelbag restore <player> <latest|previous> - Preview and restore a locked TravelBag backup"), false);
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
			source.sendFailure(Component.literal("Your TravelBag is locked and cannot be cleaned."));
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
			source.sendFailure(Component.literal("That TravelBag is locked and cannot be cleaned."));
			return 0;
		}
		source.sendSuccess(() -> Component.literal("Cleaned TravelBag of " + profile.name() + "."), false);
		return Command.SINGLE_SUCCESS;
	}

	private static int backup(TravelBagMod mod, CommandSourceStack source) {
		mod.backupNow();
		source.sendSuccess(() -> Component.literal("TravelBag backups created."), false);
		return Command.SINGLE_SUCCESS;
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

	private static boolean canRestoreTarget(TravelBagMod mod, CommandSourceStack source, NameAndId profile) {
		if (source.getServer().getPlayerList().getPlayer(profile.id()) != null) {
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

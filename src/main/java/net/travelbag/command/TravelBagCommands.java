package net.travelbag.command;

import java.io.IOException;
import java.util.Collection;

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

public final class TravelBagCommands {
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
		return Command.SINGLE_SUCCESS;
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
		mod.cleanBag(player.getUUID());
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
		mod.cleanBag(profile.id());
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

	private static ServerPlayer getPlayer(CommandSourceStack source) {
		return source.getEntity() instanceof ServerPlayer player ? player : null;
	}

	private static NameAndId getSingleProfile(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		Collection<NameAndId> profiles = GameProfileArgument.getGameProfiles(context, "player");
		return profiles.iterator().next();
	}
}

package net.travelbag.permission;

import java.util.Locale;
import java.util.UUID;

import net.fabricmc.fabric.api.permission.v1.PermissionContext;
import net.fabricmc.fabric.api.permission.v1.PermissionContextOwner;
import net.minecraft.resources.Identifier;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.travelbag.TravelBagMod;

public final class PermissionService {
	private final TravelBagMod mod;

	public PermissionService(TravelBagMod mod) {
		this.mod = mod;
	}

	public boolean canUse(ServerPlayer player) {
		if (!this.mod.getConfig().isLuckPerms()) {
			return true;
		}
		return check(player, "travelbag.use", false);
	}

	public boolean canUse(CommandSourceStack source) {
		if (!this.mod.getConfig().isLuckPerms()) {
			return true;
		}
		return check(source, "travelbag.use", false);
	}

	public int getRowsFor(ServerPlayer player) {
		if (!this.mod.getConfig().isLuckPerms()) {
			return this.mod.getConfig().getSize();
		}

		if (!this.canUse(player)) {
			return 0;
		}

		for (int rows = 6; rows >= 1; rows--) {
			if (check(player, "travelbag.size." + rows, false)) {
				return rows;
			}
		}

		return 1;
	}

	public int getRowsFor(UUID playerUuid) {
		if (!this.mod.getConfig().isLuckPerms()) {
			return this.mod.getConfig().getSize();
		}

		var server = this.mod.getServer();
		if (server == null) {
			return 0;
		}
		var online = server.getPlayerList().getPlayer(playerUuid);
		if (online != null) {
			return this.getRowsFor(online);
		}
		var context = PermissionContext.offlinePlayer(playerUuid, server).join();
		if (!check(context, "travelbag.use", false)) {
			return 0;
		}
		for (int rows = 6; rows >= 1; rows--) {
			if (check(context, "travelbag.size." + rows, false)) {
				return rows;
			}
		}
		return 1;
	}

	public boolean canAutoPickup(ServerPlayer player) {
		if (!this.mod.getConfig().isLuckPerms()) {
			return this.mod.getConfig().isCollectItems();
		}
		return check(player, "travelbag.fullpickup", false);
	}

	public boolean canCleanOwn(ServerPlayer player) {
		if (!this.mod.getConfig().isLuckPerms()) {
			return this.mod.getConfig().isClearCommand();
		}
		return check(player, "travelbag.clean", false);
	}

	public boolean canSortOwn(ServerPlayer player) {
		if (!this.mod.getConfig().isLuckPerms()) {
			return true;
		}
		return this.hasAdmin(player) || check(player, "travelbag.sort", false);
	}

	public boolean canCleanOther(ServerPlayer player) {
		return this.hasAdmin(player) || checkAdminNode(player, "travelbag.clean.other");
	}

	public boolean canOpenOthers(ServerPlayer player) {
		return this.hasAdmin(player) || checkAdminNode(player, "travelbag.others");
	}

	public boolean canEditOthers(ServerPlayer player) {
		return this.hasAdmin(player) || checkAdminNode(player, "travelbag.others.edit");
	}

	public boolean keepOnDeath(ServerPlayer player) {
		if (!this.mod.getConfig().isLuckPerms()) {
			return false;
		}
		return check(player, "travelbag.keepOnDeath", false);
	}

	public boolean canIgnoreCooldown(ServerPlayer player) {
		if (!this.mod.getConfig().isLuckPerms()) {
			return false;
		}
		return this.hasAdmin(player) || check(player, "travelbag.noCooldown", false);
	}

	public boolean canIgnoreBlacklist(UUID playerUuid) {
		if (!this.mod.getConfig().isLuckPerms()) {
			return false;
		}
		var server = this.mod.getServer();
		if (server == null) {
			return false;
		}
		var online = server.getPlayerList().getPlayer(playerUuid);
		return check(online != null ? online : PermissionContext.offlinePlayer(playerUuid, server).join(),
			"travelbag.ignoreBlacklist", false);
	}

	public boolean canIgnoreBlacklist(ServerPlayer player) {
		if (!this.mod.getConfig().isLuckPerms()) {
			return false;
		}
		return this.hasAdmin(player) || check(player, "travelbag.ignoreBlacklist", false);
	}

	public boolean canRunBackup(ServerPlayer player) {
		return this.hasAdmin(player) || checkAdminNode(player, "travelbag.backup");
	}

	public boolean canRunReload(ServerPlayer player) {
		return this.hasAdmin(player) || checkAdminNode(player, "travelbag.reload");
	}

	public boolean canRunRestore(ServerPlayer player) {
		return this.hasAdmin(player) || checkAdminNode(player, "travelbag.restore");
	}

	public boolean canRunBackup(CommandSourceStack source) {
		return this.hasAdmin(source) || checkAdminNode(source, "travelbag.backup");
	}

	public boolean canRunReload(CommandSourceStack source) {
		return this.hasAdmin(source) || checkAdminNode(source, "travelbag.reload");
	}

	public boolean canRunRestore(CommandSourceStack source) {
		return this.hasAdmin(source) || checkAdminNode(source, "travelbag.restore");
	}

	public boolean canBypassGameMode(ServerPlayer player) {
		return this.hasAdmin(player) || checkAdminNode(player, "travelbag.bypass.gamemode");
	}

	public boolean hasAdmin(ServerPlayer player) {
		return checkAdminNode(player, "travelbag.admin");
	}

	public boolean hasAdmin(CommandSourceStack source) {
		return checkAdminNode(source, "travelbag.admin");
	}

	private boolean checkAdminNode(ServerPlayer player, String node) {
		return check(player, node, this.getAdminFallback(player.createCommandSourceStack()));
	}

	private boolean checkAdminNode(CommandSourceStack source, String node) {
		return check(source, node, this.getAdminFallback(source));
	}

	private static boolean check(PermissionContextOwner owner, String node, boolean fallback) {
		// LuckPerms translates travelbag:path to travelbag.path and normalizes case.
		return owner.checkPermission(Identifier.fromNamespaceAndPath("travelbag",
			node.substring("travelbag.".length()).toLowerCase(Locale.ROOT)), fallback);
	}

	private boolean getAdminFallback(CommandSourceStack source) {
		if (this.mod.getConfig().isLuckPerms()) {
			return false;
		}
		return source.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER);
	}
}

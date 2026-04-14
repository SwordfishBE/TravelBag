package net.travelbag.permission;

import java.util.UUID;

import me.lucko.fabric.api.permissions.v0.Permissions;

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
		return Permissions.check(player, "travelbag.use", false);
	}

	public boolean canUse(CommandSourceStack source) {
		if (!this.mod.getConfig().isLuckPerms()) {
			return true;
		}
		return Permissions.check(source, "travelbag.use", false);
	}

	public int getRowsFor(ServerPlayer player) {
		if (!this.mod.getConfig().isLuckPerms()) {
			return this.mod.getConfig().getSize();
		}

		if (!this.canUse(player)) {
			return 0;
		}

		for (int rows = 6; rows >= 1; rows--) {
			if (Permissions.check(player, "travelbag.size." + rows, false)) {
				return rows;
			}
		}

		return 1;
	}

	public int getRowsFor(UUID playerUuid) {
		if (!this.mod.getConfig().isLuckPerms()) {
			return this.mod.getConfig().getSize();
		}

		boolean canUse = Permissions.check(playerUuid, "travelbag.use").join();
		if (!canUse) {
			return 0;
		}

		for (int rows = 6; rows >= 1; rows--) {
			if (Permissions.check(playerUuid, "travelbag.size." + rows).join()) {
				return rows;
			}
		}

		return 1;
	}

	public boolean canAutoPickup(ServerPlayer player) {
		if (!this.mod.getConfig().isLuckPerms()) {
			return this.mod.getConfig().isCollectItems();
		}
		return Permissions.check(player, "travelbag.fullpickup", false);
	}

	public boolean canCleanOwn(ServerPlayer player) {
		if (!this.mod.getConfig().isLuckPerms()) {
			return this.mod.getConfig().isClearCommand();
		}
		return Permissions.check(player, "travelbag.clean", false);
	}

	public boolean canSortOwn(ServerPlayer player) {
		if (!this.mod.getConfig().isLuckPerms()) {
			return true;
		}
		return this.hasAdmin(player) || Permissions.check(player, "travelbag.sort", false);
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
		return Permissions.check(player, "travelbag.keepOnDeath", false);
	}

	public boolean canIgnoreCooldown(ServerPlayer player) {
		if (!this.mod.getConfig().isLuckPerms()) {
			return false;
		}
		return this.hasAdmin(player) || Permissions.check(player, "travelbag.noCooldown", false);
	}

	public boolean canIgnoreBlacklist(UUID playerUuid) {
		if (!this.mod.getConfig().isLuckPerms()) {
			return false;
		}
		return Permissions.check(playerUuid, "travelbag.ignoreBlacklist").join();
	}

	public boolean canIgnoreBlacklist(ServerPlayer player) {
		if (!this.mod.getConfig().isLuckPerms()) {
			return false;
		}
		return this.hasAdmin(player) || Permissions.check(player, "travelbag.ignoreBlacklist", false);
	}

	public boolean canRunBackup(ServerPlayer player) {
		return this.hasAdmin(player) || checkAdminNode(player, "travelbag.backup");
	}

	public boolean canRunReload(ServerPlayer player) {
		return this.hasAdmin(player) || checkAdminNode(player, "travelbag.reload");
	}

	public boolean canRunBackup(CommandSourceStack source) {
		return this.hasAdmin(source) || checkAdminNode(source, "travelbag.backup");
	}

	public boolean canRunReload(CommandSourceStack source) {
		return this.hasAdmin(source) || checkAdminNode(source, "travelbag.reload");
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
		return Permissions.check(player, node, this.getAdminFallback(player.createCommandSourceStack()));
	}

	private boolean checkAdminNode(CommandSourceStack source, String node) {
		return Permissions.check(source, node, this.getAdminFallback(source));
	}

	private boolean getAdminFallback(CommandSourceStack source) {
		if (this.mod.getConfig().isLuckPerms()) {
			return false;
		}
		return source.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER);
	}
}

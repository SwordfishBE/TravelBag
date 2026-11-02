package net.travelbag.command;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.players.NameAndId;
import net.travelbag.TravelBagMod;

final class TravelBagUpgradeCommands {
    private record Key(String actor, java.util.UUID owner, int index, boolean legacy) {
    }

    private record Pending(long expires, String fingerprint) {
    }

    private static final Map<Key, Pending> PENDING = new ConcurrentHashMap<>();

    private TravelBagUpgradeCommands() {
    }

    static void clear() {
        PENDING.clear();
    }

    static void expire() {
        PENDING.entrySet().removeIf(entry -> entry.getValue().expires() < System.currentTimeMillis());
    }

    static LiteralArgumentBuilder<CommandSourceStack> create(TravelBagMod mod) {
        return Commands.literal("upgrade").requires(source -> TravelBagCommands.canRunRecovery(mod, source))
            .then(Commands.argument("player", GameProfileArgument.gameProfile())
                .then(mode(mod, "legacy", true)).then(mode(mod, "current", false)));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> mode(TravelBagMod mod, String name, boolean legacy) {
        return choice(mod, name, legacy, 0)
            .then(choice(mod, "latest", legacy, 1))
            .then(choice(mod, "previous", legacy, 2));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> choice(TravelBagMod mod, String name, boolean legacy, int index) {
        return Commands.literal(name).executes(context -> execute(mod, context, legacy, index, false))
            .then(Commands.literal("confirm").executes(context -> execute(mod, context, legacy, index, true)));
    }

    private static int execute(TravelBagMod mod, CommandContext<CommandSourceStack> context,
                               boolean legacy, int index, boolean confirm) throws CommandSyntaxException {
        var source = context.getSource();
        NameAndId profile = GameProfileArgument.getGameProfiles(context, "player").iterator().next();
        if (!TravelBagCommands.canRestoreTarget(mod, source, profile)) return 0;
        String actor = source.getEntity() == null ? "console" : source.getEntity().getUUID().toString();
        Key key = new Key(actor, profile.id(), index, legacy);
        try {
            if (!confirm) {
                var preview = mod.getStorage().inspectLegacyUpgrade(profile.id(), index, legacy);
                PENDING.put(key, new Pending(System.currentTimeMillis() + 60_000L, preview.fingerprint()));
                String command = context.getInput() + " confirm";
                source.sendSuccess(() -> Component.literal("Valid file: " + preview.itemCount() + " item(s). "
                    + (legacy ? "Legacy uses pre-26.4 defaults for omitted sounds." : "Current preserves omitted sounds as intentional silence.")
                    + " An exact recovery snapshot will be preserved. Confirm within 60 seconds: /" + command), false);
                return Command.SINGLE_SUCCESS;
            }
            Pending pending = PENDING.remove(key);
            if (pending == null || pending.expires() < System.currentTimeMillis()) {
                source.sendFailure(Component.literal("Upgrade preview is missing or expired. Preview the same choice again."));
                return 0;
            }
            var recovery = mod.getStorage().upgradeLegacyFile(profile.id(), index, legacy, pending.fingerprint());
            TravelBagMod.LOGGER.info("[TravelBag] {} selected {} item data for {} (backup index {}). Recovery: {}",
                actor, legacy ? "legacy" : "current", profile.id(), index, recovery);
            source.sendSuccess(() -> Component.literal("TravelBag data version recorded and verified. Recovery snapshot: "
                + recovery.getFileName() + (index == 0 ? ". The active bag is now available." : ". You may now preview the backup restore.")), false);
            return Command.SINGLE_SUCCESS;
        } catch (IOException exception) {
            PENDING.remove(key);
            source.sendFailure(Component.literal("TravelBag upgrade refused: " + exception.getMessage()));
            return 0;
        }
    }
}

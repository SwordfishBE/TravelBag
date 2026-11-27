package net.travelbag;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;

final class TravelBagCommandTestSupport {
    private TravelBagCommandTestSupport() {
    }

    static int execute(MinecraftServer server, String command, CommandSourceStack source) {
        int[] result = {0};
        server.getCommands().performPrefixedCommand(source.withCallback((success, value) -> {
            if (success) result[0] = value;
        }), command);
        return result[0];
    }
}

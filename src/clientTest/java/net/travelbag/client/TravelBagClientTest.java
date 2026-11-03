package net.travelbag.client;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.AlertScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.travelbag.TravelBagMod;
import net.travelbag.bag.TravelBagScreenHandler;

public final class TravelBagClientTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        context.runOnClient(client -> {
            var parent = new TitleScreen();
            var notice = new TravelBagModMenuCompat().getModConfigScreenFactory().create(parent);
            if (!(notice instanceof AlertScreen)) {
                throw new AssertionError("Missing or 26.3 Cloth Config must show a safe notice");
            }
            client.setScreenAndShow(notice);
        });
        context.waitTicks(5);
        context.takeScreenshot(FabricLoader.getInstance().isModLoaded("cloth-config")
            ? "travelbag-old-cloth-notice" : "travelbag-missing-cloth-notice");
        context.setScreen(TitleScreen::new);
        try (var world = context.worldBuilder().create()) {
            world.getServer().runOnServer(server -> {
                var player = server.getPlayerList().getPlayers().getFirst();
                player.setGameMode(GameType.SURVIVAL);
                var mod = TravelBagMod.getInstance();
                var data = mod.getStorage().getOrLoad(player.getUUID());
                data.setStack(0, new ItemStack(Items.DIAMOND, 7));
                data.markDirty();
                if (!mod.getStorage().saveAll()) throw new AssertionError("Cannot persist initial bag");
                player.setItemInHand(InteractionHand.MAIN_HAND, mod.getShortcutItemManager().createShortcutItem(player));
                var held = player.getMainHandItem().copy();
                var pos = player.blockPosition().below();
                var result = UseBlockCallback.EVENT.invoker().interact(player, player.level(), InteractionHand.MAIN_HAND,
                    new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false));
                if (result != InteractionResult.SUCCESS || !(player.containerMenu instanceof TravelBagScreenHandler)) {
                    throw new AssertionError("Shortcut block use must open the bag and consume the interaction");
                }
                if (!ItemStack.matches(held, player.getMainHandItem())) {
                    throw new AssertionError("Opening the bag must not consume the shortcut head");
                }
            });
            context.waitTicks(10);
            context.runOnClient(client -> {
                if (!(client.gui.screen() instanceof ContainerScreen) || !(client.player.containerMenu instanceof ChestMenu menu)) {
                    throw new AssertionError("Server bag must synchronize and render as a vanilla chest menu");
                }
                if (!menu.getSlot(0).getItem().is(Items.DIAMOND) || menu.getSlot(0).getItem().getCount() != 7) {
                    throw new AssertionError("Bag contents did not synchronize to the client");
                }
            });
            context.takeScreenshot("travelbag-snapshot-7-open");
            world.getServer().runOnServer(server -> {
                var player = server.getPlayerList().getPlayers().getFirst();
                player.closeContainer();
                var data = TravelBagMod.getInstance().getStorage().getOrLoad(player.getUUID());
                if (!data.getStack(0).is(Items.DIAMOND) || data.getStack(0).getCount() != 7) {
                    throw new AssertionError("Closing the bag lost items");
                }
            });
        }
        context.setScreen(TitleScreen::new);
    }
}

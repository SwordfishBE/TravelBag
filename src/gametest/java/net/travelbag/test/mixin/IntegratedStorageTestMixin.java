package net.travelbag.test.mixin;

import net.minecraft.gametest.framework.GameTestServer;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(GameTestServer.class)
abstract class IntegratedStorageTestMixin {
    // Fabric's test server otherwise identifies as dedicated, unlike vanilla's test server.
    @WrapMethod(method = "isDedicatedServer")
    private boolean travelbag$exerciseIntegratedStorage(Operation<Boolean> original) {
        return !Boolean.getBoolean("travelbag.test.integrated") && original.call();
    }
}

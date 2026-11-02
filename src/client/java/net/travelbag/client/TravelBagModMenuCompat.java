package net.travelbag.client;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.SemanticVersion;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.VersionParsingException;
import net.fabricmc.loader.api.metadata.version.VersionPredicate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.AlertScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class TravelBagModMenuCompat implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		var cloth = FabricLoader.getInstance().getModContainer("cloth-config");
		if (cloth.isEmpty()) {
			return parent -> notice(parent, "Install Cloth Config for Minecraft 26.4 to edit the configuration here. "
					+ "You can also edit config/travelbag/travelbag.properties.");
		}
		if (!hasCompatibleClothConfig(cloth.get().getMetadata().getVersion())) {
			return parent -> notice(parent, "This menu requires Cloth Config for Minecraft 26.4. "
					+ "The installed version cannot render this screen safely. "
					+ "Until it is updated, edit config/travelbag/travelbag.properties and use /travelbag reload.");
		}
		return net.travelbag.client.config.TravelBagClothConfigScreenFactory::create;
	}

	static boolean hasCompatibleClothConfig(Version version) {
		// Older Cloth widgets are incompatible with Minecraft 26.4's client UI.
		if (!(version instanceof SemanticVersion)) {
			return false;
		}
		try {
			return VersionPredicate.parse(">=26.4-").test(version);
		} catch (VersionParsingException exception) {
			return false;
		}
	}

	private static Screen notice(Screen parent, String message) {
		return new AlertScreen(() -> Minecraft.getInstance().setScreenAndShow(parent),
				Component.literal("TravelBag"), Component.literal(message));
	}
}

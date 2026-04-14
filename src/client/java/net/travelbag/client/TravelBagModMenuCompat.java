package net.travelbag.client;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

import net.fabricmc.loader.api.FabricLoader;

public final class TravelBagModMenuCompat implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		if (!FabricLoader.getInstance().isModLoaded("cloth-config")) {
			return parent -> null;
		}
		return parent -> net.travelbag.client.config.TravelBagClothConfigScreenFactory.create(parent);
	}
}

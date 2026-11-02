package net.travelbag.client;

import net.fabricmc.loader.api.Version;

public final class ClothVersionProbe {
    public static void main(String[] args) throws Exception {
        check(null, false);
        check("26.3.159", false);
        check("26.4.0-alpha.1", true);
        check("26.4.0", true);
        check("26.4.159", true);
        check("unknown", false);
        System.out.println("All 6 Cloth Config version checks passed.");
    }

    private static void check(String version, boolean expected) throws Exception {
        boolean actual = TravelBagModMenuCompat.hasCompatibleClothConfig(
                version == null ? null : Version.parse(version));
        if (actual != expected) {
            throw new AssertionError("Unexpected Cloth Config compatibility: " + version);
        }
    }
}

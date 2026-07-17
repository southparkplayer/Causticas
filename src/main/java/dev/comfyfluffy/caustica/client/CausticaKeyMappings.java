package dev.comfyfluffy.caustica.client;

import dev.comfyfluffy.caustica.CausticaMod;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

/** Every player-facing Caustica key mapping, grouped together in Minecraft's Controls screen. */
public final class CausticaKeyMappings {
    public static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(
            Identifier.fromNamespaceAndPath(CausticaMod.MOD_ID, "controls"));

    public static final KeyMapping OPEN_MENU = new KeyMapping(
            "key.caustica.open_menu", GLFW.GLFW_KEY_GRAVE_ACCENT, CATEGORY);

    private CausticaKeyMappings() {
    }

    public static KeyMapping[] all() {
        return new KeyMapping[] {OPEN_MENU, UltraScreenshot.KEY, OfflineGroundTruth.KEY};
    }
}

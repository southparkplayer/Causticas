package dev.comfyfluffy.caustica.client;

import java.util.Locale;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Transparent, non-interactive offline-render status HUD. */
public final class OfflineRendererHud {
    private static final int WHITE = 0xFFFFFFFF;
    private static final int MUTED = 0xFFB8C0C8;
    private static final int ACTIVE = 0xFF7FE7A7;
    private static final double HELP_SECONDS = 5.0;

    private OfflineRendererHud() {
    }

    public static void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        OfflineGroundTruth renderer = OfflineGroundTruth.INSTANCE;
        if (!renderer.engaged()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        Font font = minecraft.font;
        int right = graphics.guiWidth() - 8;
        int y = 8;

        String title = "OFFLINE  " + renderer.phaseLabel();
        drawRight(graphics, font, title, right, y, ACTIVE);

        String progress = renderer.active()
                ? String.format(Locale.ROOT, "up to %,d spp   %.1f max spp/s   %.1fs",
                        renderer.submittedSamples(), renderer.samplesPerSecond(), renderer.elapsedSeconds())
                : "Freezing scene snapshot";
        drawRight(graphics, font, progress, right, y + font.lineHeight + 2, WHITE);
        if (renderer.elapsedSeconds() < HELP_SECONDS) {
            drawRight(graphics, font, "F2 screenshot   F7 stop", right,
                    y + (font.lineHeight + 2) * 2, MUTED);
        }
    }

    private static void drawRight(GuiGraphicsExtractor graphics, Font font, String text,
                                  int right, int y, int color) {
        graphics.text(font, text, right - font.width(text), y, color, true);
    }
}

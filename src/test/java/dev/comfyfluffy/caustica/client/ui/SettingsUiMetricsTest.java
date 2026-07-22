package dev.comfyfluffy.caustica.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class SettingsUiMetricsTest {
    @Test
    void forcedEvenAutoScaleMinimaRemainUsableAndBounded() {
        for (int[] viewport : new int[][] {{400, 225}, {384, 216}, {534, 300}, {427, 240}}) {
            SettingsUiMetrics metrics = SettingsUiMetrics.calculate(viewport[0], viewport[1], 220);

            assertEquals(SettingsUiMetrics.Mode.COMPACT, metrics.mode());
            assertEquals(0, metrics.railWidth());
            assertEquals(1, metrics.principalColumns());
            assertEquals(24, metrics.controlHeight());
            assertTrue(metrics.workspaceLeft() >= 0);
            assertTrue(metrics.workspaceRight() <= viewport[0]);
            assertTrue(metrics.paneRight() <= viewport[0] - metrics.margin());
            assertTrue(metrics.bodyHeight() >= 40);
            assertEquals(metrics.paneWidth(), metrics.contentWidth() + SettingsUiMetrics.SCROLLBAR_RESERVE);
        }
    }

    @Test
    void standardModeUsesFixedRailAndOneBoundedSectionLane() {
        SettingsUiMetrics metrics = SettingsUiMetrics.calculate(800, 450, 900);

        assertEquals(SettingsUiMetrics.Mode.STANDARD, metrics.mode());
        assertEquals(160, metrics.railWidth());
        assertEquals(1, metrics.principalColumns());
        assertTrue(metrics.contentWidth() <= 520);
        assertEquals(metrics.workspaceLeft(), 800 - metrics.workspaceRight());
        assertEquals(28, metrics.controlHeight());
    }

    @Test
    void wideModeCapsWorkspaceAndProducesTwoBoundedLanes() {
        SettingsUiMetrics metrics = SettingsUiMetrics.calculate(1920, 1080, 1);

        assertEquals(SettingsUiMetrics.Mode.WIDE, metrics.mode());
        assertEquals(1088, metrics.workspaceWidth());
        assertEquals(180, metrics.railWidth());
        assertEquals(2, metrics.principalColumns());
        assertEquals(440, metrics.targetCellWidth());
        assertEquals(metrics.workspaceLeft(), 1920 - metrics.workspaceRight());
        assertEquals(metrics.workspaceRight(), metrics.paneRight());
    }

    @Test
    void largerLogicalViewportsAddMarginsInsteadOfStretchingControls() {
        SettingsUiMetrics wide = SettingsUiMetrics.calculate(3840, 2160, 210);
        SettingsUiMetrics eightK = SettingsUiMetrics.calculate(7680, 4320, 210);

        assertEquals(wide.workspaceWidth(), eightK.workspaceWidth());
        assertEquals(wide.contentWidth(), eightK.contentWidth());
        assertEquals(wide.targetCellWidth(), eightK.targetCellWidth());
        assertEquals(eightK.workspaceLeft(), 7680 - eightK.workspaceRight());
    }
}

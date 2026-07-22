package dev.comfyfluffy.caustica.client.ui;

/** Responsive geometry for the settings workstation in Minecraft logical GUI coordinates. */
public record SettingsUiMetrics(
        int margin,
        int workspaceLeft,
        int workspaceWidth,
        int railWidth,
        int paneLeft,
        int contentWidth,
        int headerHeight,
        int footerHeight,
        int bodyTop,
        int bodyHeight,
        int controlHeight,
        int gridGap,
        int categoryGap,
        int principalColumns,
        int targetCellWidth,
        int actionWidth) {

    private static final int MAX_WORKSPACE_WIDTH = 1240;
    private static final int MAX_RAIL_WIDTH = 220;
    private static final int MIN_RAIL_WIDTH = 104;
    private static final int PANE_GAP = 12;
    private static final int TWO_COLUMN_MIN_CONTENT = 720;

    public static SettingsUiMetrics calculate(int viewportWidth, int viewportHeight, int preferredRailWidth) {
        int margin = viewportWidth < 700 ? 8 : 12;
        int availableWidth = Math.max(1, viewportWidth - margin * 2);
        int workspaceWidth = Math.min(MAX_WORKSPACE_WIDTH, availableWidth);
        int workspaceLeft = Math.max(0, (viewportWidth - workspaceWidth) / 2);

        int maximumRail = Math.max(MIN_RAIL_WIDTH, workspaceWidth - PANE_GAP - 180);
        int railWidth = Math.clamp(preferredRailWidth, MIN_RAIL_WIDTH,
                Math.min(MAX_RAIL_WIDTH, maximumRail));
        int paneLeft = workspaceLeft + railWidth + PANE_GAP;
        int contentWidth = Math.max(1, workspaceWidth - railWidth - PANE_GAP);

        int headerHeight = 40;
        int footerHeight = 48;
        int bodyTop = margin + headerHeight + 8;
        int bodyHeight = Math.max(40, viewportHeight - bodyTop - footerHeight - margin);
        int columns = contentWidth >= TWO_COLUMN_MIN_CONTENT ? 2 : 1;
        int targetCellWidth = columns == 2 ? (contentWidth - 12) / 2 : contentWidth;

        return new SettingsUiMetrics(margin, workspaceLeft, workspaceWidth, railWidth, paneLeft,
                contentWidth, headerHeight, footerHeight, bodyTop, bodyHeight, 44, 12, 8,
                columns, targetCellWidth, Math.min(220, contentWidth));
    }

    public int workspaceRight() {
        return workspaceLeft + workspaceWidth;
    }

    public int paneRight() {
        return paneLeft + contentWidth;
    }

    public int footerTop(int viewportHeight) {
        return viewportHeight - footerHeight - margin;
    }
}

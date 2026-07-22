package dev.comfyfluffy.caustica.client.ui;

/** Responsive geometry in Minecraft logical GUI coordinates. */
public record SettingsUiMetrics(
        Mode mode,
        int margin,
        int workspaceLeft,
        int workspaceWidth,
        int railWidth,
        int paneLeft,
        int paneWidth,
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

    public enum Mode {
        WIDE,
        STANDARD,
        COMPACT
    }

    public static final int SCROLLBAR_WIDTH = 6;
    public static final int SCROLLBAR_SPACING = 6;
    public static final int SCROLLBAR_RESERVE = SCROLLBAR_WIDTH + SCROLLBAR_SPACING;

    private static final int WIDE_MIN_WIDTH = 1080;
    private static final int WIDE_MIN_HEIGHT = 520;
    private static final int STANDARD_MIN_WIDTH = 760;
    private static final int STANDARD_MIN_HEIGHT = 400;
    private static final int MAX_WIDE_WORKSPACE = 1088;
    private static final int MAX_STANDARD_WORKSPACE = 700;
    private static final int MAX_COMPACT_WORKSPACE = 540;
    private static final int WIDE_RAIL = 180;
    private static final int STANDARD_RAIL = 160;
    private static final int PANE_GAP = 12;

    public static SettingsUiMetrics calculate(int viewportWidth, int viewportHeight, int preferredRailWidth) {
        Mode mode = viewportWidth >= WIDE_MIN_WIDTH && viewportHeight >= WIDE_MIN_HEIGHT
                ? Mode.WIDE
                : viewportWidth >= STANDARD_MIN_WIDTH && viewportHeight >= STANDARD_MIN_HEIGHT
                        ? Mode.STANDARD : Mode.COMPACT;
        int margin = mode == Mode.COMPACT ? 6 : 8;
        int availableWidth = Math.max(1, viewportWidth - margin * 2);
        int workspaceLimit = switch (mode) {
            case WIDE -> MAX_WIDE_WORKSPACE;
            case STANDARD -> MAX_STANDARD_WORKSPACE;
            case COMPACT -> MAX_COMPACT_WORKSPACE;
        };
        int workspaceWidth = Math.min(workspaceLimit, availableWidth);
        int workspaceLeft = Math.max(0, (viewportWidth - workspaceWidth) / 2);

        int railWidth = switch (mode) {
            case WIDE -> WIDE_RAIL;
            case STANDARD -> STANDARD_RAIL;
            case COMPACT -> 0;
        };
        int paneGap = mode == Mode.COMPACT ? 0 : PANE_GAP;
        int paneLeft = workspaceLeft + railWidth + paneGap;
        int paneWidth = Math.max(SCROLLBAR_RESERVE + 1, workspaceWidth - railWidth - paneGap);
        int contentWidth = Math.max(1, paneWidth - SCROLLBAR_RESERVE);

        int headerHeight = mode == Mode.COMPACT ? 32 : 36;
        int footerHeight = mode == Mode.COMPACT ? 30 : 34;
        int bodyTop = margin + headerHeight + 4;
        int bodyHeight = Math.max(40, viewportHeight - bodyTop - footerHeight - margin);
        int controlHeight = mode == Mode.COMPACT ? 24 : 28;
        int gridGap = mode == Mode.COMPACT ? 2 : 4;
        int categoryGap = mode == Mode.COMPACT ? 6 : 10;
        int columns = mode == Mode.WIDE ? 2 : 1;
        int targetCellWidth = columns == 2
                ? Math.max(1, (contentWidth - gridGap) / 2) : contentWidth;

        return new SettingsUiMetrics(mode, margin, workspaceLeft, workspaceWidth, railWidth, paneLeft,
                paneWidth, contentWidth, headerHeight, footerHeight, bodyTop, bodyHeight,
                controlHeight, gridGap, categoryGap, columns, targetCellWidth,
                Math.min(132, contentWidth));
    }

    public boolean compact() {
        return mode == Mode.COMPACT;
    }

    public int workspaceRight() {
        return workspaceLeft + workspaceWidth;
    }

    public int paneRight() {
        return paneLeft + paneWidth;
    }

    public int footerTop(int viewportHeight) {
        return viewportHeight - footerHeight - margin;
    }
}

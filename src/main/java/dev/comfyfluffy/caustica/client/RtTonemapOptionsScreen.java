package dev.comfyfluffy.caustica.client;

import dev.comfyfluffy.caustica.CausticaConfig;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Options;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ResettableOptionWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/** A wide tone-mapping workstation that only shows controls used by the active output paths. */
public final class RtTonemapOptionsScreen extends Screen {
    private static final int SIDE_MARGIN = 16;
    private static final int CONTENT_TOP = 28;
    private static final int FOOTER_TOP_MARGIN = 8;
    private static final int PANEL_GAP = 12;
    private static final int SECTION_GAP = 10;
    private static final int HEADER_HEIGHT = 13;
    private static final int BUTTON_HEIGHT = 20;
    private static final int ROW_GAP = 4;
    private static final int MIN_PANEL_WIDTH = 420;
    private static final int MIN_CONTROL_WIDTH = 360;
    private static final int MAX_WORKSPACE_WIDTH = 1100;
    private static final int BUTTON_GAP = 6;
    private static final int FOOTER_BUTTON_WIDTH = 150;

    private final Screen lastScreen;
    private final Options options;
    private final List<SectionTitle> sectionTitles = new ArrayList<>();
    private final List<WidgetPlacement> pageWidgets = new ArrayList<>();
    private final Map<AbstractWidget, RtVideoOptions.TonemapControl> resetControls =
            new IdentityHashMap<>();
    private String visibleState;
    private int scrollOffset;
    private int maxScroll;

    public RtTonemapOptionsScreen(Screen lastScreen, Options options) {
        super(Component.translatable("caustica.options.rt.tonemapping.title"));
        this.lastScreen = lastScreen;
        this.options = options;
    }

    @Override
    protected void init() {
        sectionTitles.clear();
        pageWidgets.clear();
        resetControls.clear();

        layoutSections();

        int footerY = height - 28;
        addRenderableWidget(Button.builder(
                        Component.translatable("caustica.options.rt.tonemapping.resetVisible"),
                        button -> resetPage())
                .bounds(width / 2 - FOOTER_BUTTON_WIDTH - BUTTON_GAP / 2, footerY,
                        FOOTER_BUTTON_WIDTH, BUTTON_HEIGHT)
                .build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> onClose())
                .bounds(width / 2 + BUTTON_GAP / 2, footerY, FOOTER_BUTTON_WIDTH, BUTTON_HEIGHT)
                .build());

        visibleState = currentVisibleState();
        updatePagePositions();
    }

    private void layoutSections() {
        List<Section> sections = visibleSections();
        int availableWidth = Math.max(MIN_CONTROL_WIDTH,
                Math.min(MAX_WORKSPACE_WIDTH, width - SIDE_MARGIN * 2));
        int workspaceX = (width - availableWidth) / 2;
        int columns = availableWidth >= MIN_PANEL_WIDTH * 2 + PANEL_GAP ? 2 : 1;
        int panelWidth = (availableWidth - PANEL_GAP * (columns - 1)) / columns;
        int y = CONTENT_TOP;

        for (int sectionIndex = 0; sectionIndex < sections.size();) {
            Section first = sections.get(sectionIndex);
            if (columns == 1 || first.spanWorkspace()) {
                y += layoutSection(first, workspaceX, y, availableWidth) + SECTION_GAP;
                sectionIndex++;
                continue;
            }

            int rowHeight = layoutSection(first, workspaceX, y, panelWidth);
            sectionIndex++;
            if (sectionIndex < sections.size() && !sections.get(sectionIndex).spanWorkspace()) {
                Section second = sections.get(sectionIndex);
                rowHeight = Math.max(rowHeight,
                        layoutSection(second, workspaceX + panelWidth + PANEL_GAP, y, panelWidth));
                sectionIndex++;
            }
            y += rowHeight + SECTION_GAP;
        }

        int viewportHeight = contentBottom() - CONTENT_TOP;
        int contentHeight = Math.max(0, y - CONTENT_TOP - SECTION_GAP);
        maxScroll = Math.max(0, contentHeight - viewportHeight);
        scrollOffset = Math.clamp(scrollOffset, 0, maxScroll);
        if (maxScroll == 0) {
            centerShortContent((viewportHeight - contentHeight) / 2);
        }
    }

    private void centerShortContent(int offset) {
        if (offset <= 0) {
            return;
        }
        sectionTitles.replaceAll(title ->
                new SectionTitle(title.title(), title.x(), title.baseY() + offset));
        pageWidgets.replaceAll(placement ->
                new WidgetPlacement(placement.widget(), placement.x(), placement.baseY() + offset));
    }

    private int layoutSection(Section section, int x, int y, int width) {
        sectionTitles.add(new SectionTitle(section.title(), x, y));
        int controlTop = y + HEADER_HEIGHT;
        int controlsPerRow = width >= MIN_CONTROL_WIDTH * 2 + ROW_GAP ? 2 : 1;
        int controlWidth = (width - ROW_GAP * (controlsPerRow - 1)) / controlsPerRow;

        RtVideoOptions.TonemapControl[] controls = section.controls();
        for (int i = 0; i < controls.length; i++) {
            int row = i / controlsPerRow;
            int column = i % controlsPerRow;
            int controlX = x + column * (controlWidth + ROW_GAP);
            int controlY = controlTop + row * (BUTTON_HEIGHT + ROW_GAP);
            AbstractWidget widget = controls[i].option().createButton(
                    options, controlX, controlY, controlWidth);
            widget.setHeight(BUTTON_HEIGHT);
            addRenderableWidget(widget);
            pageWidgets.add(new WidgetPlacement(widget, controlX, controlY));
            resetControls.put(widget, controls[i]);
        }

        int rows = (controls.length + controlsPerRow - 1) / controlsPerRow;
        return HEADER_HEIGHT + rows * BUTTON_HEIGHT + Math.max(0, rows - 1) * ROW_GAP;
    }

    @Override
    public void tick() {
        super.tick();
        String nextState = currentVisibleState();
        if (!nextState.equals(visibleState)) {
            scrollOffset = 0;
            rebuildWidgets();
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (mouseY >= CONTENT_TOP && mouseY < contentBottom() && maxScroll > 0) {
            int nextOffset = Math.clamp(scrollOffset - (int) Math.round(verticalAmount * 24.0), 0, maxScroll);
            if (nextOffset != scrollOffset) {
                scrollOffset = nextOffset;
                updatePagePositions();
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    private void updatePagePositions() {
        int bottom = contentBottom();
        for (WidgetPlacement placement : pageWidgets) {
            AbstractWidget widget = placement.widget();
            int y = placement.baseY() - scrollOffset;
            widget.setPosition(placement.x(), y);
            widget.visible = y >= CONTENT_TOP && y + widget.getHeight() <= bottom;
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == 0 && event.hasShiftDown()) {
            for (Map.Entry<AbstractWidget, RtVideoOptions.TonemapControl> entry : resetControls.entrySet()) {
                AbstractWidget widget = entry.getKey();
                if (widget.visible && widget.active && widget.isMouseOver(event.x(), event.y())) {
                    resetControl(entry.getValue(), widget);
                    saveSettings();
                    return true;
                }
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.centeredText(font, title, width / 2, 8, 0xFFFFFFFF);
        int bottom = contentBottom();
        for (SectionTitle sectionTitle : sectionTitles) {
            int y = sectionTitle.baseY() - scrollOffset;
            if (y >= CONTENT_TOP && y + HEADER_HEIGHT <= bottom) {
                graphics.text(font, sectionTitle.title(), sectionTitle.x(), y, 0xFFFFFFFF);
            }
        }
        if (maxScroll > 0) {
            int trackHeight = bottom - CONTENT_TOP;
            int thumbHeight = Math.max(24, trackHeight * trackHeight / (trackHeight + maxScroll));
            int thumbTravel = trackHeight - thumbHeight;
            int thumbY = CONTENT_TOP + (maxScroll == 0 ? 0 : thumbTravel * scrollOffset / maxScroll);
            graphics.fill(width - 6, CONTENT_TOP, width - 3, bottom, 0x55000000);
            graphics.fill(width - 6, thumbY, width - 3, thumbY + thumbHeight, 0xFFAAAAAA);
        }
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    private void resetPage() {
        for (Map.Entry<AbstractWidget, RtVideoOptions.TonemapControl> entry : resetControls.entrySet()) {
            resetControl(entry.getValue(), entry.getKey());
        }
        saveSettings();
    }

    private static void resetControl(
            RtVideoOptions.TonemapControl control, AbstractWidget widget) {
        control.resetToDefault();
        if (widget instanceof ResettableOptionWidget resettable) {
            resettable.resetValue();
        }
    }

    private void saveSettings() {
        options.save();
        CausticaConfig.save();
    }

    private int contentBottom() {
        return height - 28 - FOOTER_TOP_MARGIN;
    }

    @Override
    public void removed() {
        saveSettings();
    }

    @Override
    public void onClose() {
        minecraft.setScreenAndShow(lastScreen);
    }

    private static List<Section> visibleSections() {
        List<Section> sections = new ArrayList<>();
        RtVideoOptions.TonemapControl[] output = RtVideoOptions.tonemapOutputOptions();
        sections.add(section("output", controls(output[0], output[1])));

        boolean hdrEnabled = CausticaConfig.Rt.Hdr.ENABLED.get();
        if (hdrEnabled) {
            sections.add(section("hdrOutput", controls(output[2], output[3], output[4])));
        }

        String sdrMode = CausticaConfig.Rt.Sdr.TONEMAP_MODE.get();
        switch (sdrMode) {
            case CausticaConfig.Rt.Sdr.TONEMAP_AGX ->
                    sections.add(section("sdrAgx", RtVideoOptions.sdrAgxOptions()));
            case CausticaConfig.Rt.Sdr.TONEMAP_PBR_NEUTRAL ->
                    sections.add(section("sdrPbrNeutral", RtVideoOptions.sdrPbrNeutralOptions()));
            case CausticaConfig.Rt.Sdr.TONEMAP_REINHARD ->
                    sections.add(section("sdrReinhard", RtVideoOptions.sdrReinhardOptions()));
            case CausticaConfig.Rt.Sdr.TONEMAP_ACES ->
                    sections.add(section("sdrAces", RtVideoOptions.sdrAcesOptions()));
            case CausticaConfig.Rt.Sdr.TONEMAP_LOTTES ->
                    sections.add(section("sdrLottes", RtVideoOptions.sdrLottesOptions()));
            case CausticaConfig.Rt.Sdr.TONEMAP_FROSTBITE ->
                    sections.add(section("sdrFrostbite", RtVideoOptions.sdrFrostbiteOptions()));
            case CausticaConfig.Rt.Sdr.TONEMAP_UNCHARTED2 ->
                    sections.add(section("sdrUncharted2", RtVideoOptions.sdrUncharted2Options()));
            case CausticaConfig.Rt.Sdr.TONEMAP_GT ->
                    sections.add(section("sdrGt", RtVideoOptions.sdrGtOptions()));
            case CausticaConfig.Rt.Sdr.TONEMAP_PSYCHOV ->
                    sections.add(section("sdrPsycho", RtVideoOptions.sdrPsychoOptions()));
            case CausticaConfig.Rt.Sdr.TONEMAP_PSYCHOV23 ->
                    sections.add(section("sdrPsycho23", RtVideoOptions.sdrPsychoV23Options()));
            default -> {
            }
        }

        String hdrMode = hdrEnabled ? CausticaConfig.Rt.Hdr.TONEMAP_MODE.get() : "";
        boolean psychoActive = isPsycho(sdrMode) || isPsycho(hdrMode);
        if (psychoActive) {
            sections.add(wideSection("psychoShared", RtVideoOptions.psychoOptions()));
        }
        if (hdrEnabled && CausticaConfig.Rt.Hdr.TONEMAP_PSYCHOV.equals(hdrMode)) {
            sections.add(section("hdrPsycho", RtVideoOptions.hdrPsychoOptions()));
        } else if (hdrEnabled && CausticaConfig.Rt.Hdr.TONEMAP_PSYCHOV23.equals(hdrMode)
                && !CausticaConfig.Rt.Sdr.TONEMAP_PSYCHOV23.equals(sdrMode)) {
            RtVideoOptions.TonemapControl[] psychoV23 = RtVideoOptions.sdrPsychoV23Options();
            sections.add(section("hdrPsycho23", controls(psychoV23[1], psychoV23[2])));
        }
        return sections;
    }

    private static boolean isPsycho(String mode) {
        return CausticaConfig.Rt.Sdr.TONEMAP_PSYCHOV.equals(mode)
                || CausticaConfig.Rt.Sdr.TONEMAP_PSYCHOV23.equals(mode);
    }

    private static String currentVisibleState() {
        boolean hdrEnabled = CausticaConfig.Rt.Hdr.ENABLED.get();
        return CausticaConfig.Rt.Sdr.TONEMAP_MODE.get() + '|'
                + (hdrEnabled ? CausticaConfig.Rt.Hdr.TONEMAP_MODE.get() : "off");
    }

    private static Section section(String keySuffix, RtVideoOptions.TonemapControl[] controls) {
        return new Section(
                Component.translatable("caustica.options.rt.tonemapping.section." + keySuffix), controls, false);
    }

    private static Section wideSection(String keySuffix, RtVideoOptions.TonemapControl[] controls) {
        return new Section(
                Component.translatable("caustica.options.rt.tonemapping.section." + keySuffix), controls, true);
    }

    private static RtVideoOptions.TonemapControl[] controls(RtVideoOptions.TonemapControl... controls) {
        return controls;
    }

    private record Section(Component title, RtVideoOptions.TonemapControl[] controls, boolean spanWorkspace) {
    }

    private record SectionTitle(Component title, int x, int baseY) {
    }

    private record WidgetPlacement(AbstractWidget widget, int x, int baseY) {
    }
}

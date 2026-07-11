package dev.comfyfluffy.caustica.client;

import dev.comfyfluffy.caustica.CausticaConfig;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/** Tone-mapping controls arranged as a responsive, non-scrolling workstation. */
public final class RtTonemapOptionsScreen extends Screen {
    private static final int SIDE_MARGIN = 16;
    private static final int TOP = 42;
    private static final int COLUMN_GAP = 10;
    private static final int ROW_GAP = 3;
    private static final int SECTION_GAP = 8;
    private static final int HEADER_HEIGHT = 13;
    private static final int BUTTON_HEIGHT = 20;
    private static final int MIN_COLUMN_WIDTH = 150;
    private static final int MAX_COLUMNS = 6;

    private final Screen lastScreen;
    private final Options options;
    private final List<SectionTitle> sectionTitles = new ArrayList<>();

    public RtTonemapOptionsScreen(Screen lastScreen, Options options) {
        super(Component.translatable("caustica.options.rt.tonemapping.title"));
        this.lastScreen = lastScreen;
        this.options = options;
    }

    @Override
    protected void init() {
        sectionTitles.clear();

        List<Section> sections = sections();
        int availableWidth = Math.max(MIN_COLUMN_WIDTH, width - SIDE_MARGIN * 2);
        int widthLimitedColumns = Math.max(1,
                (availableWidth + COLUMN_GAP) / (MIN_COLUMN_WIDTH + COLUMN_GAP));
        int preferredColumns = Math.clamp(width / 240, 2, MAX_COLUMNS);
        int columnCount = Math.min(sections.size(), Math.min(widthLimitedColumns, preferredColumns));
        columnCount = Math.max(1, columnCount);

        int columnWidth = (availableWidth - COLUMN_GAP * (columnCount - 1)) / columnCount;
        int controlsPerRow = columnWidth >= 300 ? 2 : 1;
        int[] columnHeights = new int[columnCount];

        for (Section section : sections) {
            int column = shortestColumn(columnHeights);
            int x = SIDE_MARGIN + column * (columnWidth + COLUMN_GAP);
            int y = TOP + columnHeights[column];
            sectionTitles.add(new SectionTitle(section.title(), x, y));
            y += HEADER_HEIGHT;

            int controlGap = controlsPerRow == 2 ? ROW_GAP : 0;
            int controlWidth = (columnWidth - controlGap * (controlsPerRow - 1)) / controlsPerRow;
            OptionInstance<?>[] controls = section.controls();
            for (int i = 0; i < controls.length; i++) {
                int row = i / controlsPerRow;
                int slot = i % controlsPerRow;
                int controlX = x + slot * (controlWidth + controlGap);
                int controlY = y + row * (BUTTON_HEIGHT + ROW_GAP);
                AbstractWidget widget = controls[i].createButton(options, controlX, controlY, controlWidth);
                widget.setHeight(BUTTON_HEIGHT);
                addRenderableWidget(widget);
            }

            int rows = (controls.length + controlsPerRow - 1) / controlsPerRow;
            columnHeights[column] += HEADER_HEIGHT + rows * (BUTTON_HEIGHT + ROW_GAP) + SECTION_GAP;
        }

        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> onClose())
                .bounds(width / 2 - 100, height - 27, 200, BUTTON_HEIGHT)
                .build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.centeredText(font, title, width / 2, 15, 0xFFFFFFFF);
        for (SectionTitle sectionTitle : sectionTitles) {
            graphics.text(font, sectionTitle.title(), sectionTitle.x(), sectionTitle.y(), 0xFFFFFFFF);
        }
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void removed() {
        options.save();
        CausticaConfig.save();
    }

    @Override
    public void onClose() {
        minecraft.setScreenAndShow(lastScreen);
    }

    private static int shortestColumn(int[] heights) {
        int shortest = 0;
        for (int i = 1; i < heights.length; i++) {
            if (heights[i] < heights[shortest]) {
                shortest = i;
            }
        }
        return shortest;
    }

    private static List<Section> sections() {
        return List.of(
                new Section(Component.translatable("caustica.options.rt.tonemapping.section.output"),
                        RtVideoOptions.tonemapOutputOptions()),
                new Section(Component.translatable("caustica.options.rt.tonemapping.section.sdrAgx"),
                        RtVideoOptions.sdrAgxOptions()),
                new Section(Component.translatable("caustica.options.rt.tonemapping.section.sdrPbrNeutral"),
                        RtVideoOptions.sdrPbrNeutralOptions()),
                new Section(Component.translatable("caustica.options.rt.tonemapping.section.sdrReinhard"),
                        RtVideoOptions.sdrReinhardOptions()),
                new Section(Component.translatable("caustica.options.rt.tonemapping.section.sdrAces"),
                        RtVideoOptions.sdrAcesOptions()),
                new Section(Component.translatable("caustica.options.rt.tonemapping.section.sdrLottes"),
                        RtVideoOptions.sdrLottesOptions()),
                new Section(Component.translatable("caustica.options.rt.tonemapping.section.sdrFrostbite"),
                        RtVideoOptions.sdrFrostbiteOptions()),
                new Section(Component.translatable("caustica.options.rt.tonemapping.section.sdrUncharted2"),
                        RtVideoOptions.sdrUncharted2Options()),
                new Section(Component.translatable("caustica.options.rt.tonemapping.section.sdrGt"),
                        RtVideoOptions.sdrGtOptions()),
                new Section(Component.translatable("caustica.options.rt.tonemapping.section.sdrPsycho"),
                        RtVideoOptions.sdrPsychoOptions()),
                new Section(Component.translatable("caustica.options.rt.tonemapping.section.psychoShared"),
                        RtVideoOptions.psychoOptions()));
    }

    private record Section(Component title, OptionInstance<?>[] controls) {
    }

    private record SectionTitle(Component title, int x, int y) {
    }
}

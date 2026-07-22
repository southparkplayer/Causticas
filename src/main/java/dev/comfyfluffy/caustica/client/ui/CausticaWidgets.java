package dev.comfyfluffy.caustica.client.ui;

import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;
import java.util.function.DoubleSupplier;
import java.util.function.DoubleUnaryOperator;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** Focusable, narrated controls with a neutral, low-chrome visual language. */
public final class CausticaWidgets {
    public static final int ACCENT = 0xFFF0F0F0;
    public static final int ACCENT_DIM = 0xFF777B80;
    public static final int PANEL = 0x50000000;
    public static final int PANEL_2 = 0x30000000;
    public static final int PANEL_HOVER = 0x30FFFFFF;
    public static final int TEXT = 0xFFF0F0F0;
    public static final int MUTED = 0xFFAAAAAA;
    public static final int DISABLED = 0xFF707070;
    public static final int WARNING = 0xFFFFC46B;

    private CausticaWidgets() {
    }

    /** Stable human-facing label used by menu search and local quick-access history. */
    public interface LabeledControl {
        Component causticaLabel();
    }

    public static Component onOff(boolean value) {
        return Component.translatable(value ? "options.on" : "options.off");
    }

    public static String clipped(Component message, int width) {
        Font font = Minecraft.getInstance().font;
        String value = message.getString();
        if (font.width(value) <= width) {
            return value;
        }
        return font.plainSubstrByWidth(value, Math.max(0, width - font.width("..."))) + "...";
    }

    private static int centeredTextY(AbstractWidget widget, Font font) {
        return widget.getY() + (widget.getHeight() - font.lineHeight) / 2;
    }

    private static void focusOutline(GuiGraphicsExtractor g, AbstractWidget widget) {
        if (widget.isFocused()) {
            g.outline(widget.getX(), widget.getY(), widget.getWidth(), widget.getHeight(), 0xFFFFFFFF);
            if (widget.getWidth() > 4 && widget.getHeight() > 4) {
                g.outline(widget.getX() + 2, widget.getY() + 2,
                        widget.getWidth() - 4, widget.getHeight() - 4, 0xB0FFFFFF);
            }
        } else if (widget.isHovered()) {
            g.outline(widget.getX(), widget.getY(), widget.getWidth(), widget.getHeight(), 0x90FFFFFF);
        }
    }

    private static Tooltip controlTooltip(Component tooltip, Component disabledReason, boolean active,
                                            boolean resettable) {
        Component result = tooltip;
        if (!active && disabledReason != null) {
            result = result == null ? disabledReason : result.copy().append("\n").append(disabledReason);
        }
        if (resettable) {
            Component resetHint = Component.translatable("caustica.options.widget.resetHint");
            result = result == null ? resetHint : result.copy().append("\n").append(resetHint);
        }
        return result == null ? null : Tooltip.create(result);
    }

    public static final class SectionHeader extends AbstractWidget {
        private final Component subtitle;

        public SectionHeader(int width, Component title, Component subtitle) {
            super(0, 0, width, 38, title);
            this.subtitle = subtitle;
            this.active = false;
        }

        @Override
        public void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            int x = getX();
            int y = getY();
            g.fill(x, getBottom() - 1, getRight(), getBottom(), 0x70FFFFFF);
            Font font = Minecraft.getInstance().font;
            int textWidth = Math.max(0, getWidth() - 4);
            boolean titleClipped = font.width(getMessage()) > textWidth;
            g.text(font, Component.literal(clipped(getMessage(), textWidth)), x + 2, y + 4, TEXT);
            List<net.minecraft.util.FormattedCharSequence> lines = font.split(this.subtitle, getWidth() - 8);
            for (int index = 0; index < Math.min(1, lines.size()); index++) {
                g.text(font, lines.get(index), x + 2, y + 19, MUTED);
            }
            if (lines.size() > 1) {
                g.text(font, Component.literal("..."), getRight() - font.width("...") - 2, y + 19, MUTED);
                setTooltip(Tooltip.create(titleClipped
                        ? getMessage().copy().append("\n").append(this.subtitle) : this.subtitle));
            } else if (titleClipped) {
                setTooltip(Tooltip.create(getMessage()));
            } else {
                setTooltip(null);
            }
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            output.add(NarratedElementType.TITLE, getMessage());
            output.add(NarratedElementType.HINT, this.subtitle);
        }
    }

    /** Compact visual boundary for a related control bundle inside a category. */
    public static final class BundleHeader extends AbstractWidget {
        public BundleHeader(int width, Component title) {
            super(0, 0, width, 24, title);
            this.active = false;
        }

        @Override
        public void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            int y = getY();
            g.fill(getX(), y + 2, getX() + 3, getBottom() - 2, 0xC0B9D9FF);
            g.text(Minecraft.getInstance().font, getMessage(), getX() + 9,
                    y + (getHeight() - Minecraft.getInstance().font.lineHeight) / 2, TEXT);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            output.add(NarratedElementType.TITLE, getMessage());
        }
    }

    public static final class InfoStrip extends AbstractWidget {
        private final Supplier<Component> text;

        public InfoStrip(int width, Supplier<Component> text) {
            super(0, 0, width, 38, Component.empty());
            this.text = Objects.requireNonNull(text);
            this.active = false;
        }

        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            Component message = this.text.get();
            setMessage(message);
            g.fill(getX(), getY(), getRight(), getBottom(), 0x28000000);
            Font font = Minecraft.getInstance().font;
            List<net.minecraft.util.FormattedCharSequence> lines = font.split(message, getWidth() - 16);
            for (int index = 0; index < Math.min(2, lines.size()); index++) {
                g.text(font, lines.get(index), getX() + 8, getY() + 7 + index * 11, MUTED);
            }
            if (lines.size() > 2) {
                int ellipsisX = getRight() - font.width("...") - 8;
                g.text(font, Component.literal("..."), ellipsisX, getY() + 18, MUTED);
                setTooltip(Tooltip.create(message));
            } else {
                setTooltip(null);
            }
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            output.add(NarratedElementType.TITLE, this.text.get());
        }
    }

    public static final class ActionButton extends AbstractButton {
        private final Supplier<Component> label;
        private final Runnable action;
        private final boolean accent;

        public ActionButton(int width, Supplier<Component> label, Runnable action, boolean accent) {
            super(0, 0, width, 22, label.get());
            this.label = Objects.requireNonNull(label);
            this.action = Objects.requireNonNull(action);
            this.accent = accent;
        }

        public ActionButton tooltip(Component tooltip) {
            setTooltip(Tooltip.create(tooltip));
            return this;
        }

        @Override
        public void onPress(InputWithModifiers input) {
            this.action.run();
        }

        @Override
        protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            setMessage(this.label.get());
            int color = !this.active ? 0x18000000 : this.accent ? 0x34FFFFFF
                    : isHoveredOrFocused() ? PANEL_HOVER : 0;
            if (color != 0) g.fill(getX(), getY(), getRight(), getBottom(), color);
            g.fill(getX(), getBottom() - 1, getRight(), getBottom(), 0x28777777);
            focusOutline(g, this);
            int textColor = this.active ? TEXT : DISABLED;
            Font font = Minecraft.getInstance().font;
            g.centeredText(Minecraft.getInstance().font,
                    Component.literal(clipped(getMessage(), getWidth() - 16)),
                    getX() + getWidth() / 2, centeredTextY(this, font), textColor);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }

    public static final class Toggle extends AbstractButton implements LabeledControl {
        private final Component label;
        private final BooleanSupplier getter;
        private final Consumer<Boolean> setter;
        private BooleanSupplier activeWhen = () -> true;
        private Supplier<Component> disabledReason;
        private Component tooltip;
        private Runnable resetAction;

        public Toggle(int width, Component label, BooleanSupplier getter, Consumer<Boolean> setter) {
            super(0, 0, width, 22, label);
            this.label = Objects.requireNonNull(label);
            this.getter = Objects.requireNonNull(getter);
            this.setter = Objects.requireNonNull(setter);
            refreshMessage();
        }

        public Toggle activeWhen(BooleanSupplier activeWhen) {
            this.activeWhen = Objects.requireNonNull(activeWhen);
            return this;
        }

        public Toggle tooltip(Component tooltip) {
            this.tooltip = Objects.requireNonNull(tooltip);
            return this;
        }

        public Toggle disabledReason(Supplier<Component> disabledReason) {
            this.disabledReason = Objects.requireNonNull(disabledReason);
            return this;
        }

        public Toggle resetOnShift(Runnable resetAction) {
            this.resetAction = Objects.requireNonNull(resetAction);
            return this;
        }

        private void refreshMessage() {
            setMessage(this.label.copy().append(": ").append(onOff(this.getter.getAsBoolean())));
        }

        @Override
        public Component causticaLabel() {
            return this.label;
        }

        @Override
        public void onPress(InputWithModifiers input) {
            if (input.hasShiftDown() && this.resetAction != null) {
                this.resetAction.run();
            } else {
                this.setter.accept(!this.getter.getAsBoolean());
            }
            refreshMessage();
        }

        @Override
        protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            this.active = this.activeWhen.getAsBoolean();
            Component reason = !this.active && this.disabledReason != null ? this.disabledReason.get() : null;
            setTooltip(controlTooltip(this.tooltip, reason, this.active, this.resetAction != null));
            refreshMessage();
            boolean enabled = this.getter.getAsBoolean();
            int color = !this.active ? 0x18000000 : isHoveredOrFocused() ? PANEL_HOVER : 0;
            if (color != 0) g.fill(getX(), getY(), getRight(), getBottom(), color);
            g.fill(getX(), getBottom() - 1, getRight(), getBottom(), 0x28777777);
            Font font = Minecraft.getInstance().font;
            Component state = onOff(this.getter.getAsBoolean());
            int stateWidth = font.width(state);
            int switchX = getRight() - 40;
            int switchY = getY() + (getHeight() - 14) / 2;
            g.fill(switchX, switchY, switchX + 30, switchY + 14, enabled ? 0xFFB8B8B8 : 0xFF555555);
            int knobX = enabled ? switchX + 18 : switchX + 2;
            g.fill(knobX, switchY + 2, knobX + 10, switchY + 12,
                    enabled ? 0xFFFFFFFF : 0xFF999999);
            focusOutline(g, this);
            g.text(font, state, switchX - stateWidth - 7, centeredTextY(this, font),
                    this.active ? MUTED : DISABLED);
            int available = Math.max(0, switchX - stateWidth - getX() - 18);
            g.text(font,
                    Component.literal(clipped(this.label, available)), getX() + 9, centeredTextY(this, font),
                    this.active ? TEXT : DISABLED);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
            if (!this.active && this.disabledReason != null) {
                output.add(NarratedElementType.HINT, this.disabledReason.get());
            }
            if (this.resetAction != null) {
                output.add(NarratedElementType.HINT, Component.translatable("caustica.options.widget.resetHint"));
            }
        }
    }

    public static final class Dropdown<T> extends AbstractButton implements LabeledControl {
        private static final int ITEM_HEIGHT = 28;
        private static final int MAX_VISIBLE_ITEMS = 8;

        private final Component label;
        private final List<T> values;
        private final Supplier<T> getter;
        private final Consumer<T> setter;
        private final Function<T, Component> valueLabel;
        private final Runnable changed;
        private BooleanSupplier activeWhen = () -> true;
        private Supplier<Component> disabledReason;
        private Component tooltip;
        private Runnable resetAction;
        private boolean open;
        private int scrollOffset;
        private int highlightedIndex;

        public Dropdown(int width, Component label, List<T> values, Supplier<T> getter, Consumer<T> setter,
                        Function<T, Component> valueLabel, Runnable changed) {
            super(0, 0, width, 22, label);
            if (values.isEmpty()) {
                throw new IllegalArgumentException("Dropdown requires at least one value");
            }
            this.label = Objects.requireNonNull(label);
            this.values = List.copyOf(values);
            this.getter = Objects.requireNonNull(getter);
            this.setter = Objects.requireNonNull(setter);
            this.valueLabel = Objects.requireNonNull(valueLabel);
            this.changed = changed == null ? () -> { } : changed;
            refreshMessage();
        }

        public Dropdown<T> activeWhen(BooleanSupplier activeWhen) {
            this.activeWhen = Objects.requireNonNull(activeWhen);
            return this;
        }

        public Dropdown<T> tooltip(Component tooltip) {
            this.tooltip = Objects.requireNonNull(tooltip);
            return this;
        }

        public Dropdown<T> disabledReason(Supplier<Component> disabledReason) {
            this.disabledReason = Objects.requireNonNull(disabledReason);
            return this;
        }

        public Dropdown<T> resetOnShift(Runnable resetAction) {
            this.resetAction = Objects.requireNonNull(resetAction);
            return this;
        }

        private void refreshMessage() {
            setMessage(this.label.copy().append(": ").append(this.valueLabel.apply(this.getter.get())));
        }

        @Override
        public Component causticaLabel() {
            return this.label;
        }

        @Override
        public void onPress(InputWithModifiers input) {
            if (input.hasShiftDown() && this.resetAction != null) {
                this.resetAction.run();
                refreshMessage();
                this.changed.run();
            } else {
                this.open = !this.open;
                if (this.open) {
                    int selected = Math.max(0, this.values.indexOf(this.getter.get()));
                    this.highlightedIndex = selected;
                    this.scrollOffset = Math.max(0, selected - MAX_VISIBLE_ITEMS / 2);
                }
            }
        }

        public boolean isOpen() {
            return open;
        }

        public void close() {
            open = false;
        }

        public boolean keyPressed(int key) {
            if (!open) return false;
            if (key == GLFW.GLFW_KEY_ESCAPE) {
                close();
                return true;
            }
            if (key == GLFW.GLFW_KEY_HOME) highlightedIndex = 0;
            else if (key == GLFW.GLFW_KEY_END) highlightedIndex = values.size() - 1;
            else if (key == GLFW.GLFW_KEY_UP) highlightedIndex = Math.max(0, highlightedIndex - 1);
            else if (key == GLFW.GLFW_KEY_DOWN) highlightedIndex = Math.min(values.size() - 1, highlightedIndex + 1);
            else if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER
                    || key == GLFW.GLFW_KEY_SPACE) {
                setter.accept(values.get(highlightedIndex));
                refreshMessage();
                changed.run();
                close();
                return true;
            } else return false;
            scrollOffset = Math.clamp(highlightedIndex - MAX_VISIBLE_ITEMS / 2, 0,
                    Math.max(0, values.size() - MAX_VISIBLE_ITEMS));
            return true;
        }

        public boolean clickOverlay(double mouseX, double mouseY, int screenHeight) {
            if (!open) return false;
            OverlayGeometry geometry = overlayGeometry(screenHeight);
            if (mouseX < valueLeft() || mouseX >= getRight()
                    || mouseY < geometry.top || mouseY >= geometry.top + geometry.height) {
                return false;
            }
            int row = (int)(mouseY - geometry.top - 1) / ITEM_HEIGHT;
            if (row < 0 || row >= geometry.visibleItems) return true;
            int index = scrollOffset + row;
            if (index < values.size()) {
                setter.accept(values.get(index));
                refreshMessage();
                changed.run();
            }
            close();
            return true;
        }

        public boolean scrollOverlay(double mouseX, double mouseY, double vertical, int screenHeight) {
            if (!open || vertical == 0.0) return false;
            OverlayGeometry geometry = overlayGeometry(screenHeight);
            if (mouseX < valueLeft() || mouseX >= getRight()
                    || mouseY < geometry.top || mouseY >= geometry.top + geometry.height) return false;
            int maximum = Math.max(0, values.size() - geometry.visibleItems);
            scrollOffset = Math.clamp(scrollOffset + (vertical > 0.0 ? -1 : 1), 0, maximum);
            return true;
        }

        public void extractOverlay(GuiGraphicsExtractor g, int mouseX, int mouseY, int screenHeight) {
            if (!open || !active) return;
            OverlayGeometry geometry = overlayGeometry(screenHeight);
            int left = valueLeft();
            int right = getRight();
            int bottom = geometry.top + geometry.height;
            g.fill(left + 2, geometry.top + 3, right + 3, bottom + 3, 0x90000000);
            g.fill(left, geometry.top, right, bottom, 0xF0181818);
            g.outline(left, geometry.top, right - left, geometry.height, 0xD0FFFFFF);
            T selected = getter.get();
            for (int row = 0; row < geometry.visibleItems; row++) {
                int index = scrollOffset + row;
                if (index >= values.size()) break;
                int rowTop = geometry.top + 1 + row * ITEM_HEIGHT;
                T candidate = values.get(index);
                boolean hovered = mouseX >= left && mouseX < right
                        && mouseY >= rowTop && mouseY < rowTop + ITEM_HEIGHT;
                boolean current = Objects.equals(candidate, selected);
                boolean highlighted = index == highlightedIndex;
                if (current || hovered || highlighted) {
                    g.fill(left + 1, rowTop, right - 1, rowTop + ITEM_HEIGHT,
                            hovered || highlighted ? 0xFF4A4A4A : 0xFF303030);
                }
                if (current) g.fill(left + 1, rowTop, left + 3, rowTop + ITEM_HEIGHT, 0xFFFFFFFF);
                Component value = valueLabel.apply(candidate);
                Font font = Minecraft.getInstance().font;
                g.text(font, Component.literal(clipped(value, right - left - 18)), left + 8,
                        rowTop + (ITEM_HEIGHT - font.lineHeight) / 2,
                        current ? 0xFFFFFFFF : 0xFFD0D0D0);
            }
            if (scrollOffset > 0) {
                g.centeredText(Minecraft.getInstance().font, Component.literal("\u25b2"),
                        right - 8, geometry.top + 5, 0xFFB0B0B0);
            }
            if (scrollOffset + geometry.visibleItems < values.size()) {
                g.centeredText(Minecraft.getInstance().font, Component.literal("\u25bc"),
                        right - 8, bottom - 10, 0xFFB0B0B0);
            }
        }

        private OverlayGeometry overlayGeometry(int screenHeight) {
            int below = Math.max(0, screenHeight - getBottom() - 8);
            int above = Math.max(0, getY() - 8);
            boolean openDown = below >= above;
            int available = Math.max(openDown ? below : above, ITEM_HEIGHT + 2);
            int visible = Math.clamp((available - 2) / ITEM_HEIGHT, 1,
                    Math.min(MAX_VISIBLE_ITEMS, values.size()));
            int height = visible * ITEM_HEIGHT + 2;
            int top = openDown ? getBottom() + 2 : getY() - height - 2;
            top = Math.clamp(top, 4, Math.max(4, screenHeight - height - 4));
            scrollOffset = Math.clamp(scrollOffset, 0, Math.max(0, values.size() - visible));
            return new OverlayGeometry(top, height, visible);
        }

        private int valueLeft() {
            int minimum = Math.min(120, getWidth());
            int maximum = Math.min(190, getWidth());
            return getRight() - Math.clamp(getWidth() / 2, minimum, maximum);
        }

        private record OverlayGeometry(int top, int height, int visibleItems) {
        }

        @Override
        protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            this.active = this.activeWhen.getAsBoolean();
            Component reason = !this.active && this.disabledReason != null ? this.disabledReason.get() : null;
            setTooltip(controlTooltip(this.tooltip, reason, this.active, this.resetAction != null));
            refreshMessage();
            Component value = this.valueLabel.apply(this.getter.get());
            int color = !this.active ? 0x18000000 : isHoveredOrFocused() ? PANEL_HOVER : 0;
            if (color != 0) g.fill(getX(), getY(), getRight(), getBottom(), color);
            g.fill(getX(), getBottom() - 1, getRight(), getBottom(), 0x28777777);
            focusOutline(g, this);
            Font font = Minecraft.getInstance().font;
            int valueLeft = valueLeft();
            g.fill(valueLeft, getY() + 2, getRight(), getBottom() - 2,
                    this.active ? 0x24000000 : 0x14000000);
            int contentWidth = Math.max(0, getRight() - valueLeft - 30);
            int valueWidth = Math.min(font.width(value), contentWidth);
            int labelWidth = Math.max(0, valueLeft - getX() - 14);
            Component clippedValue = Component.literal(clipped(value, valueWidth));
            int renderedValueWidth = font.width(clippedValue);
            int textY = centeredTextY(this, font);
            g.text(font, Component.literal(clipped(this.label, labelWidth)),
                    getX() + 9, textY, this.active ? TEXT : DISABLED);
            g.text(font, clippedValue, getRight() - renderedValueWidth - 20, textY,
                    this.active ? ACCENT : DISABLED);
            g.text(font, Component.literal(this.open ? "\u25b2" : "\u25bc"),
                    getRight() - 12, textY, this.active ? MUTED : DISABLED);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
            if (!this.active && this.disabledReason != null) {
                output.add(NarratedElementType.HINT, this.disabledReason.get());
            } else if (this.open) {
                output.add(NarratedElementType.HINT, Component.translatable(
                        "caustica.options.widget.dropdown.open",
                        this.valueLabel.apply(this.values.get(this.highlightedIndex))));
            } else {
                output.add(NarratedElementType.HINT,
                        Component.translatable("caustica.options.widget.dropdown.closed"));
            }
            if (this.resetAction != null) {
                output.add(NarratedElementType.HINT, Component.translatable("caustica.options.widget.resetHint"));
            }
        }
    }

    /**
     * Slider values are always the displayed/control values. The unit mapping only converts that
     * value to the normalized thumb position; persistence-specific conversions belong in the
     * getter/setter boundary at the call site, so manual entry follows the same contract.
     */
    public static final class Slider extends AbstractSliderButton implements LabeledControl {
        private final Component label;
        private final DoubleSupplier getter;
        private final DoubleConsumer setter;
        private final DoubleUnaryOperator fromUnit;
        private final DoubleUnaryOperator toUnit;
        private final DoubleFunction<String> formatter;
        private BooleanSupplier activeWhen = () -> true;
        private Supplier<Component> disabledReason;
        private Component tooltip;
        private Runnable resetAction;
        private Runnable releaseAction;
        private java.util.List<Integer> snapPoints;
        private int snapThreshold;

        public Slider(int width, Component label, DoubleSupplier getter, DoubleConsumer setter,
                      DoubleUnaryOperator fromUnit, DoubleUnaryOperator toUnit,
                      DoubleFunction<String> formatter) {
            super(0, 0, width, 22, Component.empty(),
                    Math.clamp(toUnit.applyAsDouble(getter.getAsDouble()), 0.0, 1.0));
            this.label = Objects.requireNonNull(label);
            this.getter = Objects.requireNonNull(getter);
            this.setter = Objects.requireNonNull(setter);
            this.fromUnit = Objects.requireNonNull(fromUnit);
            this.toUnit = Objects.requireNonNull(toUnit);
            this.formatter = Objects.requireNonNull(formatter);
            updateMessage();
        }

        public Slider activeWhen(BooleanSupplier activeWhen) {
            this.activeWhen = Objects.requireNonNull(activeWhen);
            return this;
        }

        @Override
        public Component causticaLabel() {
            return this.label;
        }

        public Slider tooltip(Component tooltip) {
            this.tooltip = Objects.requireNonNull(tooltip);
            return this;
        }

        public Slider disabledReason(Supplier<Component> disabledReason) {
            this.disabledReason = Objects.requireNonNull(disabledReason);
            return this;
        }

        public Slider resetOnShift(Runnable resetAction) {
            this.resetAction = Objects.requireNonNull(resetAction);
            return this;
        }

        public Slider onRelease(Runnable releaseAction) {
            this.releaseAction = Objects.requireNonNull(releaseAction);
            return this;
        }

        public Slider snapTo(java.util.List<Integer> points, int threshold) {
            this.snapPoints = points;
            this.snapThreshold = threshold;
            return this;
        }

        public double currentValue() {
            return this.fromUnit.applyAsDouble(this.value);
        }

        @Override
        public void onClick(MouseButtonEvent input, boolean doubleClick) {
            if (input.hasShiftDown() && this.resetAction != null) {
                this.resetAction.run();
                syncFromModel();
                if (this.releaseAction != null) this.releaseAction.run();
                return;
            }
            super.onClick(input, doubleClick);
        }

        @Override
        public boolean mouseClicked(MouseButtonEvent input, boolean doubleClick) {
            if (this.active && this.visible && isMouseOver(input.x(), input.y())
                    && (input.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT
                    || (input.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT && input.hasControlDown()))) {
                openNumericEntry();
                return true;
            }
            return super.mouseClicked(input, doubleClick);
        }

        private void openNumericEntry() {
            var minecraft = Minecraft.getInstance();
            var parent = minecraft.gui.screen();
            if (parent == null) return;
            minecraft.setScreenAndShow(new NumericValueScreen(parent, this.label, this.getter.getAsDouble(), entered -> {
                double unit = Math.clamp(this.toUnit.applyAsDouble(entered), 0.0, 1.0);
                this.setter.accept(this.fromUnit.applyAsDouble(unit));
                syncFromModel();
                if (this.releaseAction != null) this.releaseAction.run();
            }));
        }


        @Override
        public void onRelease(MouseButtonEvent input) {
            super.onRelease(input);
            if (this.releaseAction != null) this.releaseAction.run();
        }

        public void syncFromModel() {
            this.value = Math.clamp(this.toUnit.applyAsDouble(this.getter.getAsDouble()), 0.0, 1.0);
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            double current = this.fromUnit == null ? 0.0 : this.fromUnit.applyAsDouble(this.value);
            String formatted = this.formatter == null ? "" : this.formatter.apply(current);
            setMessage(this.label == null ? Component.literal(formatted)
                    : this.label.copy().append(": ").append(formatted));
        }

        @Override
        protected void applyValue() {
            double domain = this.fromUnit.applyAsDouble(this.value);
            if (snapPoints != null) {
                int rounded = (int) Math.round(domain);
                for (int point : snapPoints) {
                    if (Math.abs(rounded - point) <= snapThreshold) {
                        domain = point;
                        this.value = this.toUnit.applyAsDouble(point);
                        break;
                    }
                }
            }
            this.setter.accept(domain);
            updateMessage();
        }

        @Override
        public void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            this.active = this.activeWhen.getAsBoolean();
            Component reason = !this.active && this.disabledReason != null ? this.disabledReason.get() : null;
            setTooltip(controlTooltip(this.tooltip, reason, this.active, this.resetAction != null));
            if (!isHoveredOrFocused()) {
                double modelValue = Math.clamp(this.toUnit.applyAsDouble(this.getter.getAsDouble()), 0.0, 1.0);
                if (Math.abs(modelValue - this.value) > 1.0e-6) {
                    this.value = modelValue;
                    updateMessage();
                }
            }
            int color = !this.active ? 0x18000000 : isHoveredOrFocused() ? PANEL_HOVER : 0;
            if (color != 0) g.fill(getX(), getY(), getRight(), getBottom(), color);
            g.fill(getX(), getBottom() - 1, getRight(), getBottom(), 0x28777777);
            Font font = Minecraft.getInstance().font;
            double current = this.fromUnit.applyAsDouble(this.value);
            Component formatted = Component.literal(this.formatter.apply(current));
            int valueWidth = Math.clamp(font.width(formatted) + 4, 48, 72);
            Component displayedValue = Component.literal(clipped(formatted, valueWidth));
            int trackWidth = Math.clamp(getWidth() / 3, 80, 168);
            int trackRight = getRight() - valueWidth - 8;
            int trackLeft = Math.max(getX() + 72, trackRight - trackWidth);
            int trackY = getY() + getHeight() / 2 - 2;
            g.fill(trackLeft, trackY, trackRight, trackY + 4, 0xFF555555);
            int handle = trackLeft + (int)Math.round((trackRight - trackLeft) * this.value);
            int progressColor = this.active ? ACCENT : DISABLED;
            g.fill(trackLeft, trackY, handle, trackY + 4, progressColor);
            g.fill(handle - 3, trackY - 4, handle + 4, trackY + 8,
                    this.active && isHoveredOrFocused() ? 0xFFFFFFFF : progressColor);
            focusOutline(g, this);
            int textY = centeredTextY(this, font);
            int labelWidth = Math.max(0, trackLeft - getX() - 14);
            g.text(font, Component.literal(clipped(this.label, labelWidth)),
                    getX() + 8, textY, this.active ? TEXT : DISABLED);
            g.text(font, displayedValue, getRight() - valueWidth, textY,
                    this.active ? ACCENT : DISABLED);
        }

        @Override
        public void updateWidgetNarration(NarrationElementOutput output) {
            super.updateWidgetNarration(output);
            if (!this.active && this.disabledReason != null) {
                output.add(NarratedElementType.HINT, this.disabledReason.get());
            }
            if (this.resetAction != null) {
                output.add(NarratedElementType.HINT, Component.translatable("caustica.options.widget.resetHint"));
            }
        }
    }
}

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

/** Focusable, narrated controls with a neutral, low-chrome visual language. */
public final class CausticaWidgets {
    public static final int ACCENT = 0xFFF0F0F0;
    public static final int ACCENT_DIM = 0xFF777B80;
    public static final int PANEL = 0x00000000;
    public static final int PANEL_2 = 0x24000000;
    public static final int PANEL_HOVER = 0x48FFFFFF;
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

    public static final class SectionHeader extends AbstractWidget {
        private final Component subtitle;

        public SectionHeader(int width, Component title, Component subtitle) {
            super(0, 0, width, 31, title);
            this.subtitle = subtitle;
            this.active = false;
        }

        @Override
        public void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            int x = getX();
            int y = getY();
            g.fill(x, getBottom() - 1, getRight(), getBottom(), 0x70FFFFFF);
            g.text(Minecraft.getInstance().font, getMessage(), x + 2, y + 6, TEXT);
            g.text(Minecraft.getInstance().font, Component.literal(clipped(this.subtitle, getWidth() - 24)),
                    x + 2, y + 18, MUTED);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            output.add(NarratedElementType.TITLE, getMessage());
            output.add(NarratedElementType.HINT, this.subtitle);
        }
    }

    public static final class InfoStrip extends AbstractWidget {
        private final Supplier<Component> text;

        public InfoStrip(int width, Supplier<Component> text) {
            super(0, 0, width, 22, Component.empty());
            this.text = Objects.requireNonNull(text);
            this.active = false;
        }

        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            Component message = this.text.get();
            setMessage(message);
            g.fill(getX(), getBottom() - 1, getRight(), getBottom(), 0x50777777);
            g.text(Minecraft.getInstance().font, Component.literal(clipped(message, getWidth() - 16)),
                    getX() + 2, getY() + 7, MUTED);
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
                    : isHoveredOrFocused() ? PANEL_HOVER : PANEL_2;
            g.fill(getX(), getY(), getRight(), getBottom(), color);
            if (isHoveredOrFocused()) {
                g.outline(getX(), getY(), getWidth(), getHeight(), 0x90FFFFFF);
            }
            int textColor = this.active ? TEXT : DISABLED;
            g.centeredText(Minecraft.getInstance().font,
                    Component.literal(clipped(getMessage(), getWidth() - 16)),
                    getX() + getWidth() / 2, getY() + 7, textColor);
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
            setTooltip(Tooltip.create(tooltip));
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
            refreshMessage();
            boolean enabled = this.getter.getAsBoolean();
            int color = !this.active ? 0x18000000 : isHoveredOrFocused() ? PANEL_HOVER : PANEL_2;
            g.fill(getX(), getY(), getRight(), getBottom(), color);
            int switchX = getRight() - 30;
            int switchY = getY() + 7;
            g.fill(switchX, switchY, switchX + 22, switchY + 8, enabled ? 0xFFB8B8B8 : 0xFF555555);
            int knobX = enabled ? switchX + 14 : switchX + 2;
            g.fill(knobX, switchY - 2, knobX + 6, switchY + 10, enabled ? 0xFFFFFFFF : 0xFF999999);
            if (isHoveredOrFocused()) {
                g.outline(getX(), getY(), getWidth(), getHeight(), 0x90FFFFFF);
            }
            int available = Math.max(0, getWidth() - 48);
            g.text(Minecraft.getInstance().font,
                    Component.literal(clipped(this.label, available)), getX() + 9, getY() + 7,
                    this.active ? TEXT : DISABLED);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }

    public static final class Dropdown<T> extends AbstractButton implements LabeledControl {
        private static final int ITEM_HEIGHT = 20;
        private static final int MAX_VISIBLE_ITEMS = 8;

        private final Component label;
        private final List<T> values;
        private final Supplier<T> getter;
        private final Consumer<T> setter;
        private final Function<T, Component> valueLabel;
        private final Runnable changed;
        private BooleanSupplier activeWhen = () -> true;
        private Runnable resetAction;
        private boolean open;
        private int scrollOffset;

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
            setTooltip(Tooltip.create(tooltip));
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

        public boolean clickOverlay(double mouseX, double mouseY, int screenHeight) {
            if (!open) return false;
            OverlayGeometry geometry = overlayGeometry(screenHeight);
            if (mouseX < getX() || mouseX >= getRight()
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
            if (mouseX < getX() || mouseX >= getRight()
                    || mouseY < geometry.top || mouseY >= geometry.top + geometry.height) return false;
            int maximum = Math.max(0, values.size() - geometry.visibleItems);
            scrollOffset = Math.clamp(scrollOffset + (vertical > 0.0 ? -1 : 1), 0, maximum);
            return true;
        }

        public void extractOverlay(GuiGraphicsExtractor g, int mouseX, int mouseY, int screenHeight) {
            if (!open || !active) return;
            OverlayGeometry geometry = overlayGeometry(screenHeight);
            int left = getX();
            int right = getRight();
            int bottom = geometry.top + geometry.height;
            g.fill(left + 2, geometry.top + 3, right + 3, bottom + 3, 0x90000000);
            g.fill(left, geometry.top, right, bottom, 0xF0181818);
            g.outline(left, geometry.top, getWidth(), geometry.height, 0xD0FFFFFF);
            T selected = getter.get();
            for (int row = 0; row < geometry.visibleItems; row++) {
                int index = scrollOffset + row;
                if (index >= values.size()) break;
                int rowTop = geometry.top + 1 + row * ITEM_HEIGHT;
                T candidate = values.get(index);
                boolean hovered = mouseX >= left && mouseX < right
                        && mouseY >= rowTop && mouseY < rowTop + ITEM_HEIGHT;
                boolean current = Objects.equals(candidate, selected);
                if (current || hovered) {
                    g.fill(left + 1, rowTop, right - 1, rowTop + ITEM_HEIGHT,
                            hovered ? 0xFF4A4A4A : 0xFF303030);
                }
                if (current) g.fill(left + 1, rowTop, left + 3, rowTop + ITEM_HEIGHT, 0xFFFFFFFF);
                Component value = valueLabel.apply(candidate);
                g.text(Minecraft.getInstance().font,
                        Component.literal(clipped(value, getWidth() - 18)), left + 8, rowTop + 6,
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

        private record OverlayGeometry(int top, int height, int visibleItems) {
        }

        @Override
        protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            this.active = this.activeWhen.getAsBoolean();
            refreshMessage();
            Component value = this.valueLabel.apply(this.getter.get());
            int color = !this.active ? 0x18000000 : isHoveredOrFocused() ? PANEL_HOVER : PANEL_2;
            g.fill(getX(), getY(), getRight(), getBottom(), color);
            if (isHoveredOrFocused()) {
                g.outline(getX(), getY(), getWidth(), getHeight(), 0x90FFFFFF);
            }
            int valueWidth = Minecraft.getInstance().font.width(value);
            int labelWidth = Math.max(0, getWidth() - valueWidth - 38);
            g.text(Minecraft.getInstance().font, Component.literal(clipped(this.label, labelWidth)),
                    getX() + 9, getY() + 7, this.active ? TEXT : DISABLED);
            g.text(Minecraft.getInstance().font, value, getRight() - valueWidth - 20, getY() + 7,
                    this.active ? ACCENT : DISABLED);
            g.text(Minecraft.getInstance().font, Component.literal(this.open ? "\u25b2" : "\u25bc"),
                    getRight() - 12, getY() + 7, this.active ? MUTED : DISABLED);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }

    public static final class Slider extends AbstractSliderButton implements LabeledControl {
        private final Component label;
        private final DoubleSupplier getter;
        private final DoubleConsumer setter;
        private final DoubleUnaryOperator fromUnit;
        private final DoubleUnaryOperator toUnit;
        private final DoubleFunction<String> formatter;
        private BooleanSupplier activeWhen = () -> true;
        private Runnable resetAction;

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
            setTooltip(Tooltip.create(tooltip));
            return this;
        }

        public Slider resetOnShift(Runnable resetAction) {
            this.resetAction = Objects.requireNonNull(resetAction);
            return this;
        }

        @Override
        public void onClick(MouseButtonEvent input, boolean doubleClick) {
            if (input.hasShiftDown() && this.resetAction != null) {
                this.resetAction.run();
                syncFromModel();
                return;
            }
            super.onClick(input, doubleClick);
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
            this.setter.accept(this.fromUnit.applyAsDouble(this.value));
            updateMessage();
        }

        @Override
        public void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            this.active = this.activeWhen.getAsBoolean();
            if (!isHoveredOrFocused()) {
                double modelValue = Math.clamp(this.toUnit.applyAsDouble(this.getter.getAsDouble()), 0.0, 1.0);
                if (Math.abs(modelValue - this.value) > 1.0e-6) {
                    this.value = modelValue;
                    updateMessage();
                }
            }
            int color = !this.active ? 0x18000000 : isHoveredOrFocused() ? PANEL_HOVER : PANEL_2;
            g.fill(getX(), getY(), getRight(), getBottom(), color);
            int trackY = getBottom() - 5;
            g.fill(getX() + 8, trackY, getRight() - 8, trackY + 2, 0xFF555555);
            int handle = getX() + 8 + (int)Math.round((getWidth() - 16) * this.value);
            int progressColor = this.active ? ACCENT : DISABLED;
            g.fill(getX() + 8, trackY, handle, trackY + 2, progressColor);
            g.fill(handle - 2, trackY - 3, handle + 3, trackY + 5,
                    this.active && isHoveredOrFocused() ? 0xFFFFFFFF : progressColor);
            if (isHoveredOrFocused()) {
                g.outline(getX(), getY(), getWidth(), getHeight(), 0x90FFFFFF);
            }
            g.text(Minecraft.getInstance().font,
                    Component.literal(clipped(getMessage(), getWidth() - 16)),
                    getX() + 8, getY() + 5, this.active ? TEXT : DISABLED);
        }
    }
}

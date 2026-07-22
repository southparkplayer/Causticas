package dev.comfyfluffy.caustica.client.ui;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.layouts.Layout;
import net.minecraft.client.gui.layouts.LayoutElement;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

/** A tree node whose header controls the visibility of its child layout. */
public final class CollapsibleLayout implements Layout {
    private static final int CONTENT_GAP = 4;
    private final TreeHeader header;
    private final BooleanSupplier collapsed;
    private final Map<AbstractWidget, WidgetState> hiddenWidgetStates = new IdentityHashMap<>();
    private LayoutElement content;
    private int x;
    private int y;

    public CollapsibleLayout(int width, Component title, BooleanSupplier collapsed, Runnable toggle) {
        this.collapsed = Objects.requireNonNull(collapsed);
        this.header = new TreeHeader(width, title, collapsed, Objects.requireNonNull(toggle));
    }

    public void setContent(LayoutElement content) {
        if (this.content != null) throw new IllegalStateException("Collapsible tree content already set");
        this.content = Objects.requireNonNull(content);
    }

    public TreeHeader header() {
        return header;
    }

    public void setResetAction(Runnable reset) {
        header.setResetAction(reset);
    }

    @Override
    public void arrangeElements() {
        header.setX(x);
        header.setY(y);
        if (content == null) return;
        content.setX(x);
        content.setY(y + header.getHeight() + CONTENT_GAP);
        boolean isCollapsed = collapsed.getAsBoolean();
        if (!isCollapsed) restoreHidden(content);
        if (content instanceof Layout layout) layout.arrangeElements();
        if (isCollapsed) setHidden(content);
    }

    @Override
    public void visitChildren(Consumer<LayoutElement> visitor) {
        visitor.accept(header);
        if (content != null) visitor.accept(content);
    }

    @Override
    public void removeChildren() {
        content = null;
        hiddenWidgetStates.clear();
    }

    @Override
    public void setX(int x) {
        int delta = x - this.x;
        visitChildren(child -> child.setX(child.getX() + delta));
        this.x = x;
    }

    @Override
    public void setY(int y) {
        int delta = y - this.y;
        visitChildren(child -> child.setY(child.getY() + delta));
        this.y = y;
    }

    @Override
    public int getX() {
        return x;
    }

    @Override
    public int getY() {
        return y;
    }

    @Override
    public int getWidth() {
        return header.getWidth();
    }

    @Override
    public int getHeight() {
        int contentHeight = collapsed.getAsBoolean() || content == null ? 0 : content.getHeight();
        return header.getHeight() + (contentHeight == 0 ? 0 : CONTENT_GAP + contentHeight);
    }

    private void setHidden(LayoutElement element) {
        if (element instanceof AbstractWidget widget) {
            hiddenWidgetStates.putIfAbsent(widget, new WidgetState(widget.visible, widget.active));
            widget.visible = false;
            widget.active = false;
        }
        if (element instanceof Layout layout) layout.visitChildren(this::setHidden);
    }

    private void restoreHidden(LayoutElement element) {
        if (element instanceof AbstractWidget widget) {
            WidgetState state = hiddenWidgetStates.remove(widget);
            if (state != null) {
                widget.visible = state.visible();
                widget.active = state.active();
            }
        }
        if (element instanceof Layout layout) layout.visitChildren(this::restoreHidden);
    }

    private record WidgetState(boolean visible, boolean active) { }

    /** Clickable tree header with a compact disclosure marker. */
    public static final class TreeHeader extends AbstractButton {
        private final BooleanSupplier collapsed;
        private final Runnable toggle;
        private Runnable reset;

        public TreeHeader(int width, Component title, BooleanSupplier collapsed, Runnable toggle) {
            super(0, 0, width, 24, title);
            this.collapsed = collapsed;
            this.toggle = toggle;
        }

        @Override
        public void onPress(InputWithModifiers input) {
            if (input.hasShiftDown() && reset != null) reset.run();
            else toggle.run();
        }

        private void setResetAction(Runnable reset) {
            this.reset = reset;
        }

        @Override
        protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            int y = getY();
            int textY = y + (getHeight() - Minecraft.getInstance().font.lineHeight) / 2;
            g.fill(getX(), y + 2, getX() + 3, getBottom() - 2, 0xC0B9D9FF);
            g.fill(getX() + 3, y + 2, getRight(), getBottom() - 2,
                    isHoveredOrFocused() ? 0x48FFFFFF : 0x26000000);
            Component marker = Component.literal(collapsed.getAsBoolean() ? "> " : "v ");
            g.text(Minecraft.getInstance().font, marker, getX() + 9, textY, CausticaWidgets.TEXT);
            g.text(Minecraft.getInstance().font, getMessage(), getX() + 22, textY,
                    isHoveredOrFocused() ? CausticaWidgets.ACCENT : CausticaWidgets.TEXT);
            if (reset != null) {
                g.text(Minecraft.getInstance().font, Component.literal("R"), getRight() - 12, textY,
                        CausticaWidgets.MUTED);
            }
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            output.add(NarratedElementType.TITLE, getMessage());
            output.add(NarratedElementType.HINT,
                    Component.literal(collapsed.getAsBoolean() ? "Collapsed" : "Expanded"));
            if (reset != null) {
                output.add(NarratedElementType.HINT,
                        Component.translatable("caustica.options.widget.resetHint"));
            }
        }
    }
}

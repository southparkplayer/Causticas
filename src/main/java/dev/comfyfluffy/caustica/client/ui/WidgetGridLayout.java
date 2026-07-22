package dev.comfyfluffy.caustica.client.ui;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.layouts.Layout;
import net.minecraft.client.gui.layouts.LayoutElement;

/** A compact responsive grid whose active child set may change without rebuilding the owning screen. */
public final class WidgetGridLayout implements Layout {
    private final int width;
    private final int columns;
    private final int columnGap;
    private final int rowGap;
    private final int rowHeight;
    private final List<? extends AbstractWidget> allChildren;
    private final Supplier<List<? extends AbstractWidget>> visibleChildren;
    private final Map<AbstractWidget, Boolean> defaultActive = new IdentityHashMap<>();
    private int x;
    private int y;

    public WidgetGridLayout(int width, int columns, int columnGap, int rowGap, int rowHeight,
                            List<? extends AbstractWidget> children) {
        this(width, columns, columnGap, rowGap, rowHeight, children, () -> children);
    }

    public WidgetGridLayout(int width, int columns, int columnGap, int rowGap, int rowHeight,
                            List<? extends AbstractWidget> allChildren,
                            Supplier<List<? extends AbstractWidget>> visibleChildren) {
        this.width = width;
        this.columns = Math.max(1, columns);
        this.columnGap = Math.max(0, columnGap);
        this.rowGap = Math.max(0, rowGap);
        this.rowHeight = Math.max(1, rowHeight);
        this.allChildren = List.copyOf(allChildren);
        this.visibleChildren = Objects.requireNonNull(visibleChildren);
        this.allChildren.forEach(widget -> this.defaultActive.put(widget, widget.active));
    }

    private List<? extends AbstractWidget> currentChildren() {
        List<? extends AbstractWidget> result = this.visibleChildren.get();
        return result == null ? List.of() : result;
    }

    @Override
    public void arrangeElements() {
        List<? extends AbstractWidget> widgets = currentChildren();
        java.util.Set<AbstractWidget> selected = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        selected.addAll(widgets);
        this.allChildren.forEach(widget -> {
            if (!selected.contains(widget)) {
                widget.visible = false;
                widget.active = false;
            }
        });
        int cellWidth = Math.max(1, (this.width - (this.columns - 1) * this.columnGap) / this.columns);
        for (int i = 0; i < widgets.size(); i++) {
            int column = i % this.columns;
            int row = i / this.columns;
            int childX = this.x + column * (cellWidth + this.columnGap);
            int childY = this.y + row * (this.rowHeight + this.rowGap);
            int childWidth = column == this.columns - 1 ? this.x + this.width - childX : cellWidth;
            AbstractWidget widget = widgets.get(i);
            widget.visible = true;
            widget.active = this.defaultActive.getOrDefault(widget, true);
            widget.setRectangle(childWidth, this.rowHeight, childX, childY);
        }
    }

    @Override
    public void visitChildren(Consumer<LayoutElement> visitor) {
        this.allChildren.forEach(visitor);
    }

    @Override
    public void removeChildren() {
        // Child ownership stays with the screen model so dynamic groups can be shown again.
    }

    @Override
    public void setX(int x) {
        int delta = x - this.x;
        this.allChildren.forEach(child -> child.setX(child.getX() + delta));
        this.x = x;
    }

    @Override
    public void setY(int y) {
        int delta = y - this.y;
        this.allChildren.forEach(child -> child.setY(child.getY() + delta));
        this.y = y;
    }

    @Override
    public int getX() {
        return this.x;
    }

    @Override
    public int getY() {
        return this.y;
    }

    @Override
    public int getWidth() {
        return this.width;
    }

    @Override
    public int getHeight() {
        int count = currentChildren().size();
        int rows = count == 0 ? 0 : (count + this.columns - 1) / this.columns;
        return rows == 0 ? 0 : rows * this.rowHeight + (rows - 1) * this.rowGap;
    }
}

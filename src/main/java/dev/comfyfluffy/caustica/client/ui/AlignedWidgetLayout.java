package dev.comfyfluffy.caustica.client.ui;

import java.util.function.Consumer;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.layouts.Layout;
import net.minecraft.client.gui.layouts.LayoutElement;

/** A full-width layout row containing one bounded widget aligned to the right edge. */
public final class AlignedWidgetLayout implements Layout {
    private final int width;
    private final int widgetWidth;
    private final int height;
    private final AbstractWidget widget;
    private int x;
    private int y;

    public AlignedWidgetLayout(int width, int widgetWidth, int height, AbstractWidget widget) {
        this.width = Math.max(1, width);
        this.widgetWidth = Math.clamp(widgetWidth, 1, this.width);
        this.height = Math.max(1, height);
        this.widget = widget;
    }

    @Override
    public void arrangeElements() {
        widget.setRectangle(widgetWidth, height, x + width - widgetWidth, y);
    }

    @Override
    public void visitChildren(Consumer<LayoutElement> visitor) {
        visitor.accept(widget);
    }

    @Override public void removeChildren() { }
    @Override public void setX(int x) {
        widget.setX(widget.getX() + x - this.x);
        this.x = x;
    }
    @Override public void setY(int y) {
        widget.setY(widget.getY() + y - this.y);
        this.y = y;
    }
    @Override public int getX() { return x; }
    @Override public int getY() { return y; }
    @Override public int getWidth() { return width; }
    @Override public int getHeight() { return height; }
}

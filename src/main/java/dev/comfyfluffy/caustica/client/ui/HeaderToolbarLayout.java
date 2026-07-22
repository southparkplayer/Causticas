package dev.comfyfluffy.caustica.client.ui;

import java.util.function.Consumer;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.layouts.Layout;
import net.minecraft.client.gui.layouts.LayoutElement;

/** Page heading with one bounded trailing action in the same vertical band. */
public final class HeaderToolbarLayout implements Layout {
    private final int width;
    private final int gap;
    private final AbstractWidget header;
    private final AbstractWidget action;
    private int x;
    private int y;

    public HeaderToolbarLayout(int width, int gap, AbstractWidget header, AbstractWidget action) {
        this.width = Math.max(1, width);
        this.gap = Math.max(0, gap);
        this.header = header;
        this.action = action;
    }

    @Override
    public void arrangeElements() {
        int actionWidth = Math.min(action.getWidth(), width);
        int headerWidth = Math.max(1, width - actionWidth - gap);
        header.setRectangle(headerWidth, header.getHeight(), x, y);
        action.setRectangle(actionWidth, Math.min(action.getHeight(), getHeight()),
                x + width - actionWidth, y + (getHeight() - action.getHeight()) / 2);
    }

    @Override
    public void visitChildren(Consumer<LayoutElement> visitor) {
        visitor.accept(header);
        visitor.accept(action);
    }

    @Override public void removeChildren() { }

    @Override
    public void setX(int x) {
        int delta = x - this.x;
        header.setX(header.getX() + delta);
        action.setX(action.getX() + delta);
        this.x = x;
    }

    @Override
    public void setY(int y) {
        int delta = y - this.y;
        header.setY(header.getY() + delta);
        action.setY(action.getY() + delta);
        this.y = y;
    }

    @Override public int getX() { return x; }
    @Override public int getY() { return y; }
    @Override public int getWidth() { return width; }
    @Override public int getHeight() { return Math.max(header.getHeight(), action.getHeight()); }
}

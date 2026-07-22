package dev.comfyfluffy.caustica.client.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.gui.layouts.Layout;
import net.minecraft.client.gui.layouts.LayoutElement;

/** Deterministic vertical layout for section headers, responsive grids, and status strips. */
public final class CategoryLayout implements Layout {
    private final int width;
    private final int spacing;
    private final List<LayoutElement> children = new ArrayList<>();
    private int x;
    private int y;

    public CategoryLayout(int width, int spacing) {
        this.width = Math.max(1, width);
        this.spacing = Math.max(0, spacing);
    }

    public <T extends LayoutElement> T addChild(T child) {
        children.add(child);
        return child;
    }

    @Override
    public void arrangeElements() {
        int cursor = y;
        for (LayoutElement child : children) {
            child.setX(x);
            child.setY(cursor);
            if (child instanceof Layout layout) {
                layout.arrangeElements();
            }
            cursor += child.getHeight() + spacing;
        }
    }

    @Override
    public void visitChildren(Consumer<LayoutElement> visitor) {
        children.forEach(visitor);
    }

    @Override
    public void removeChildren() {
        children.clear();
    }

    @Override
    public void setX(int x) {
        int delta = x - this.x;
        children.forEach(child -> child.setX(child.getX() + delta));
        this.x = x;
    }

    @Override
    public void setY(int y) {
        int delta = y - this.y;
        children.forEach(child -> child.setY(child.getY() + delta));
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
        return width;
    }

    @Override
    public int getHeight() {
        if (children.isEmpty()) return 0;
        int height = children.stream().mapToInt(LayoutElement::getHeight).sum();
        return height + spacing * (children.size() - 1);
    }
}

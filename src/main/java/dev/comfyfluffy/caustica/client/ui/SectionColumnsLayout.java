package dev.comfyfluffy.caustica.client.ui;

import java.util.function.Consumer;
import net.minecraft.client.gui.layouts.Layout;
import net.minecraft.client.gui.layouts.LayoutElement;

/** Two stable section lanes: a canonical prefix on the left and suffix on the right. */
public final class SectionColumnsLayout implements Layout {
    private final int width;
    private final int gap;
    private final int splitIndex;
    private final CategoryLayout left;
    private final CategoryLayout right;
    private int sectionCount;
    private int x;
    private int y;

    public SectionColumnsLayout(int width, int gap, int sectionGap, int splitIndex) {
        this.width = Math.max(1, width);
        this.gap = Math.max(0, gap);
        this.splitIndex = Math.max(1, splitIndex);
        int laneWidth = Math.max(1, (this.width - this.gap) / 2);
        this.left = new CategoryLayout(laneWidth, sectionGap);
        this.right = new CategoryLayout(laneWidth, sectionGap);
    }

    public <T extends LayoutElement> T addSection(T section) {
        if (sectionCount++ < splitIndex) left.addChild(section);
        else right.addChild(section);
        return section;
    }

    public int laneWidth() {
        return left.getWidth();
    }

    @Override
    public void arrangeElements() {
        left.setX(x);
        left.setY(y);
        left.arrangeElements();
        right.setX(x + left.getWidth() + gap);
        right.setY(y);
        right.arrangeElements();
    }

    @Override
    public void visitChildren(Consumer<LayoutElement> visitor) {
        visitor.accept(left);
        visitor.accept(right);
    }

    @Override
    public void removeChildren() {
        left.removeChildren();
        right.removeChildren();
        sectionCount = 0;
    }

    @Override
    public void setX(int x) {
        int delta = x - this.x;
        left.setX(left.getX() + delta);
        right.setX(right.getX() + delta);
        this.x = x;
    }

    @Override
    public void setY(int y) {
        int delta = y - this.y;
        left.setY(left.getY() + delta);
        right.setY(right.getY() + delta);
        this.y = y;
    }

    @Override public int getX() { return x; }
    @Override public int getY() { return y; }
    @Override public int getWidth() { return width; }
    @Override public int getHeight() { return Math.max(left.getHeight(), right.getHeight()); }
}

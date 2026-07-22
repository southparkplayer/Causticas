package dev.comfyfluffy.caustica.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.layouts.Layout;
import net.minecraft.client.gui.layouts.LayoutElement;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

final class LayoutMovementTest {
    @Test
    void nestedLayoutsTranslateEveryDescendantByExactDeltaImmediately() {
        Button first = button();
        Button second = button();
        Button action = button();
        WidgetGridLayout grid = new WidgetGridLayout(180, 2, 4, 3, 20, List.of(first, second));
        AlignedWidgetLayout aligned = new AlignedWidgetLayout(180, 60, 20, action);
        CategoryLayout section = new CategoryLayout(180, 5);
        section.addChild(grid);
        section.addChild(aligned);

        CollapsibleLayout collapsible = new CollapsibleLayout(
                180, Component.literal("Section"), () -> false, () -> { });
        collapsible.setContent(section);
        CategoryLayout root = new CategoryLayout(180, 8);
        root.addChild(collapsible);
        root.setX(12);
        root.setY(30);
        root.arrangeElements();

        Map<LayoutElement, Position> before = descendantPositions(root);
        assertEquals(8, before.size());

        root.setX(-7);
        before.forEach((element, position) -> {
            assertEquals(position.x() - 19, element.getX());
            assertEquals(position.y(), element.getY());
        });

        root.setY(81);
        before.forEach((element, position) -> {
            assertEquals(position.x() - 19, element.getX());
            assertEquals(position.y() + 51, element.getY());
        });
    }

    @Test
    void widgetGridTranslatesChildrenOutsideTheCurrentVisibleSet() {
        Button shown = button();
        Button filtered = Button.builder(Component.empty(), ignored -> { }).bounds(70, 80, 10, 10).build();
        WidgetGridLayout grid = new WidgetGridLayout(
                100, 1, 0, 0, 20, List.of(shown, filtered), () -> List.of(shown));
        grid.arrangeElements();

        assertFalse(filtered.visible);
        grid.setX(25);
        grid.setY(-10);

        assertEquals(25, shown.getX());
        assertEquals(-10, shown.getY());
        assertEquals(95, filtered.getX());
        assertEquals(70, filtered.getY());
    }

    @Test
    void expandingRestoresEachDescendantsVisibilityAndActivity() {
        AtomicBoolean collapsed = new AtomicBoolean();
        Button enabled = button();
        Button disabled = button();
        disabled.active = false;
        Button alreadyHidden = button();
        alreadyHidden.visible = false;

        CategoryLayout content = new CategoryLayout(120, 2);
        content.addChild(new AlignedWidgetLayout(120, 60, 20, enabled));
        content.addChild(new AlignedWidgetLayout(120, 60, 20, disabled));
        content.addChild(new AlignedWidgetLayout(120, 60, 20, alreadyHidden));
        CollapsibleLayout layout = new CollapsibleLayout(
                120, Component.literal("Section"), collapsed::get, () -> { });
        layout.setContent(content);
        layout.arrangeElements();

        collapsed.set(true);
        layout.arrangeElements();
        layout.arrangeElements();
        assertHidden(enabled);
        assertHidden(disabled);
        assertHidden(alreadyHidden);

        collapsed.set(false);
        layout.arrangeElements();
        assertTrue(enabled.visible);
        assertTrue(enabled.active);
        assertTrue(disabled.visible);
        assertFalse(disabled.active);
        assertFalse(alreadyHidden.visible);
        assertTrue(alreadyHidden.active);
    }

    @Test
    void sectionColumnsKeepCanonicalPrefixAndSuffixInStableLanes() {
        Button first = button();
        Button second = button();
        Button third = button();
        SectionColumnsLayout columns = new SectionColumnsLayout(204, 4, 6, 2);
        columns.addSection(new AlignedWidgetLayout(100, 100, 20, first));
        columns.addSection(new AlignedWidgetLayout(100, 100, 20, second));
        columns.addSection(new AlignedWidgetLayout(100, 100, 20, third));
        columns.setX(10);
        columns.setY(30);
        columns.arrangeElements();

        assertEquals(10, first.getX());
        assertEquals(30, first.getY());
        assertEquals(10, second.getX());
        assertEquals(56, second.getY());
        assertEquals(114, third.getX());
        assertEquals(30, third.getY());
        assertEquals(46, columns.getHeight());
    }

    @Test
    void headerToolbarReservesASeparateRegionForItsAction() {
        Button header = button();
        header.setWidth(500);
        header.setHeight(38);
        Button action = button();
        action.setWidth(132);
        action.setHeight(28);
        HeaderToolbarLayout toolbar = new HeaderToolbarLayout(516, 4, header, action);
        toolbar.setX(20);
        toolbar.setY(30);
        toolbar.arrangeElements();

        assertEquals(380, header.getWidth());
        assertEquals(404, action.getX());
        assertTrue(header.getRight() + 4 <= action.getX());
    }

    private static Button button() {
        return Button.builder(Component.empty(), ignored -> { }).bounds(0, 0, 10, 10).build();
    }

    private static void assertHidden(AbstractWidget widget) {
        assertFalse(widget.visible);
        assertFalse(widget.active);
    }

    private static Map<LayoutElement, Position> descendantPositions(Layout root) {
        Map<LayoutElement, Position> result = new IdentityHashMap<>();
        collectDescendantPositions(root, result);
        return result;
    }

    private static void collectDescendantPositions(Layout layout, Map<LayoutElement, Position> result) {
        layout.visitChildren(child -> {
            result.put(child, new Position(child.getX(), child.getY()));
            if (child instanceof Layout nested) collectDescendantPositions(nested, result);
        });
    }

    private record Position(int x, int y) { }
}

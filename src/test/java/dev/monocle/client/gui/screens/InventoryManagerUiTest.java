package dev.monocle.client.gui.screens;

import dev.monocle.client.gui.screens.InventoryManagerScreen.ToolbarLayout;

/** Run with assertions enabled. Pure layout checks do not create a Minecraft screen or GPU device. */
public final class InventoryManagerUiTest {
    public static void main(String[] args) {
        boolean assertionsEnabled = false;
        assert assertionsEnabled = true;
        if (!assertionsEnabled) throw new IllegalStateException("Run with assertions enabled (-ea).");

        assert ToolbarLayout.calculate(640, 480, 232, 157, 176, 166, 4) != null;
        ToolbarLayout shortChest = ToolbarLayout.calculate(320, 200, 72, -11, 176, 222, 4);
        assert shortChest != null && shortChest.vertical() : "A tall chest uses the side margin instead of covering slots.";
        assert ToolbarLayout.calculate(176, 120, 0, -51, 176, 222, 4) == null : "Hide controls if no safe space exists.";

        int checked = 0;
        for (int width : new int[] { 176, 240, 320, 427, 640, 1280 }) {
            for (int height : new int[] { 100, 120, 180, 200, 240, 360, 720 }) {
                for (int imageHeight : new int[] { 166, 222 }) {
                    for (int count : new int[] { 3, 4, 6 }) {
                        int left = (width - 176) / 2, top = (height - imageHeight) / 2;
                        ToolbarLayout layout = ToolbarLayout.calculate(width, height, left, top, 176, imageHeight, count);
                        if (layout == null) continue;
                        for (int i = 0; i < count; i++) {
                            int x = layout.x() + (layout.vertical() ? 0 : i * (layout.buttonWidth() + 2));
                            int y = layout.y() + (layout.vertical() ? i * 22 : 0);
                            assert x >= 0 && y >= 0 && x + layout.buttonWidth() <= width && y + 20 <= height;
                            assert outside(x, y, layout.buttonWidth(), 20, left, top, 176, imageHeight) : "Toolbar must not cover slots.";
                        }
                        assert layout.x() >= 0 && layout.x() + layout.statusWidth() <= width;
                        assert layout.statusY() >= 1 && layout.statusY() + 10 <= height;
                        assert outside(layout.x(), layout.statusY() - 1, layout.statusWidth(), 11, left, top, 176, imageHeight);
                        checked++;
                    }
                }
            }
        }
        System.out.println("Inventory Manager UI checks passed: " + checked + " safe toolbar layouts; tiny viewports hide controls instead of covering slots.");
    }

    private static boolean outside(int x, int y, int w, int h, int left, int top, int imageWidth, int imageHeight) {
        return x + w <= left || x >= left + imageWidth || y + h <= top || y >= top + imageHeight;
    }
}

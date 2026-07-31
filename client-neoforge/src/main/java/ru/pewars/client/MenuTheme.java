package ru.pewars.client;

import net.minecraft.client.gui.GuiGraphics;

/**
 * Общая палитра и примитивы отрисовки современного плоского стиля меню.
 * Все элементы рисуются обычными fill/fillGradient со «скруглением» углов
 * в 1 пиксель, поэтому не требуют текстур и ресурспаков.
 * Визуально совпадает с MenuTheme из pe_townymenu и politempire_quests.
 */
final class MenuTheme {

    /** Золотой акцент бренда меню. */
    static final int ACCENT = 0xFFE8B94E;
    /** Приглушённая рамка нейтральных элементов. */
    static final int BORDER_MUTED = 0xFF39424F;

    private MenuTheme() {
    }

    /**
     * Заливка прямоугольника со срезанными угловыми пикселями (радиус 1).
     * Три fill не перекрываются, поэтому полупрозрачные цвета не «двоятся».
     */
    static void fillRounded(GuiGraphics graphics, int x1, int y1, int x2, int y2, int color) {
        graphics.fill(x1 + 1, y1, x2 - 1, y2, color);
        graphics.fill(x1, y1 + 1, x1 + 1, y2 - 1, color);
        graphics.fill(x2 - 1, y1 + 1, x2, y2 - 1, color);
    }

    /** Вертикальная градиентная заливка со срезанными углами (радиус 1). */
    static void gradientRounded(GuiGraphics graphics, int x1, int y1, int x2, int y2, int top, int bottom) {
        graphics.fillGradient(x1, y1 + 1, x2, y2 - 1, top, bottom);
        graphics.fill(x1 + 1, y1, x2 - 1, y1 + 1, top);
        graphics.fill(x1 + 1, y2 - 1, x2 - 1, y2, bottom);
    }

    /** Рамка толщиной 1px со срезанными углами (радиус 1). */
    static void strokeRounded(GuiGraphics graphics, int x1, int y1, int x2, int y2, int color) {
        graphics.fill(x1 + 1, y1, x2 - 1, y1 + 1, color);
        graphics.fill(x1 + 1, y2 - 1, x2 - 1, y2, color);
        graphics.fill(x1, y1 + 1, x1 + 1, y2 - 1, color);
        graphics.fill(x2 - 1, y1 + 1, x2, y2 - 1, color);
    }
}

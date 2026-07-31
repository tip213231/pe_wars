package ru.pewars.client;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

/**
 * Стилизованная кнопка в едином стиле townymenu (NORMAL/ON/OFF).
 * Полная копия стиля pe_townymenu — для визуальной консистентности меню.
 */
public class StyledButton extends AbstractButton {
    public enum Style {
        NORMAL,
        ON,
        OFF
    }

    private final Button.OnPress onPress;
    private final Style style;

    public StyledButton(int x, int y, int width, int height, Component message, Button.OnPress onPress, Style style) {
        super(x, y, width, height, message);
        this.onPress = onPress;
        this.style = style;
    }

    @Override
    public void onPress() {
        onPress.onPress(null);
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int x = getX();
        int y = getY();
        int right = x + getWidth();
        int bottom = y + getHeight();
        boolean hover = active && isHoveredOrFocused();

        // Современный плоский стиль: мягкий градиент, тонкая рамка, скруглённые углы.
        int border = switch (style) {
            case ON -> hover ? 0xFF6FE79B : 0xFF2FA866;
            case OFF -> hover ? 0xFFFF8B8B : 0xFFCC4B4B;
            case NORMAL -> hover ? 0xFFAEB9C7 : MenuTheme.BORDER_MUTED;
        };
        int fillTop = hover ? 0xF22B3542 : 0xE6212933;
        int fillBottom = hover ? 0xF2202834 : 0xE6191F28;
        if (!active) {
            border = 0xFF2A313B;
            fillTop = 0xB3161B22;
            fillBottom = 0xB312161C;
        }

        // Мягкая тень под кнопкой
        graphics.fill(x + 1, bottom, right + 1, bottom + 1, 0x33000000);
        MenuTheme.gradientRounded(graphics, x, y, right, bottom, fillTop, fillBottom);
        MenuTheme.strokeRounded(graphics, x, y, right, bottom, border);
        // Тонкий верхний блик
        graphics.fill(x + 2, y + 1, right - 2, y + 2, hover ? 0x22FFFFFF : 0x10FFFFFF);

        Font font = Minecraft.getInstance().font;
        int textColor = active ? 0xFFF2F4F7 : 0xFF6B7684;
        Component text = getMessage();
        if (style == Style.ON) {
            text = getMessage().copy().withStyle(ChatFormatting.GREEN);
        } else if (style == Style.OFF) {
            text = getMessage().copy().withStyle(ChatFormatting.RED);
        }
        graphics.drawCenteredString(font, text, x + getWidth() / 2, y + (getHeight() - 8) / 2, textColor);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
        defaultButtonNarrationText(narrationElementOutput);
    }
}

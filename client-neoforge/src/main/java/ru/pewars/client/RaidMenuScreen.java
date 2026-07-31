package ru.pewars.client;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Меню рейдов в стиле townymenu (панель 330×270, золотая рамка).
 * Страницы: main / declare / active / history.
 *
 * Открывается командой /t raid или кнопкой из меню войн. Данные через [PETM_RAID].
 */
public class RaidMenuScreen extends Screen {
    private RaidState state = RaidState.loading();
    private String page = "main";
    private String inputAction = "";
    private String inputTitle = "";
    private EditBox input;
    private String pendingTarget = "";
    private int targetPage = 0;

    public RaidMenuScreen() {
        super(Component.literal("PE Raids"));
    }

    /** ФИКС: init() вызывается и при каждом ресайзе окна — не спамим сервер повторными запросами. */
    private boolean requestedOnce;

    @Override
    protected void init() {
        rebuild();
        if (!requestedOnce) {
            requestedOnce = true;
            WarsClientApi.requestRaid();
        }
    }

    public void acceptState(RaidState state) {
        this.state = state;
        rebuild();
    }

    private void rebuild() {
        // ФИКС: сохраняем набранный текст, если состояние обновилось во время ввода
        // (раньше acceptState() пересоздавал EditBox и стирал ввод игрока).
        if (input != null && !inputAction.isEmpty()) {
            pendingTarget = input.getValue();
        }
        clearWidgets();
        input = null;
        int panelX = width / 2 - 150;
        int y = height / 2 - 55;

        if (!inputAction.isEmpty()) {
            input = new EditBox(font, panelX + 25, y + 45, 250, 20, Component.literal(inputTitle));
            input.setMaxLength(32);
            if (!pendingTarget.isEmpty()) input.setValue(pendingTarget);
            addRenderableWidget(input);
            addButton(panelX + 25, y + 75, 120, 20, "Подтвердить", b -> {
                String value = input.getValue().trim();
                if (!value.isEmpty()) {
                    WarsClientApi.raidAction(inputAction, value);
                    inputAction = "";
                    inputTitle = "";
                    pendingTarget = "";
                    page = "main";
                    delayedRefresh();
                }
            });
            addButton(panelX + 155, y + 75, 120, 20, "Отмена", b -> {
                inputAction = "";
                inputTitle = "";
                pendingTarget = "";
                rebuild();
            });
            return;
        }

        if (!state.loaded) {
            addButton(panelX + 75, y + 118, 150, 20, "Закрыть", b -> onClose());
            return;
        }

        switch (page) {
            case "declare" -> buildDeclarePage(panelX, y);
            case "active" -> buildActivePage(panelX, y);
            case "history" -> buildHistoryPage(panelX, y);
            default -> buildMainPage(panelX, y);
        }
    }

    private void buildMainPage(int panelX, int y) {
        boolean canDeclare = state.isMayor || (Minecraft.getInstance().player != null
                && Minecraft.getInstance().player.hasPermissions(2));

        // Ряд 1 (y+86): широкая высокая кнопка во всю ширину контента (250×28).
        if (state.activeRaid != null) {
            addButton(panelX + 25, y + 86, 250, 28, "Активный рейд", b -> switchPage("active"));
        } else {
            addButton(panelX + 25, y + 86, 250, 28, "Объявить рейд", b -> switchPage("declare"), canDeclare);
        }
        // Ряд 2 (y+118): Войны + История (или + Объявить при активном рейде).
        addButton(panelX + 25, y + 118, 120, 20, "Войны", b -> {
            onClose();
            Minecraft.getInstance().setScreen(new WarMenuScreen());
        });
        if (state.activeRaid != null) {
            addButton(panelX + 155, y + 118, 120, 20, "+ Объявить", b -> switchPage("declare"), canDeclare);
        } else {
            addButton(panelX + 155, y + 118, 120, 20, "История", b -> switchPage("history"));
        }
        // Ряд 3 (y+142): История/Обновить + Закрыть.
        if (state.activeRaid != null) {
            addButton(panelX + 25, y + 142, 120, 20, "История", b -> switchPage("history"));
        } else {
            addButton(panelX + 25, y + 142, 120, 20, "Обновить", b -> delayedRefresh());
        }
        addButton(panelX + 155, y + 142, 120, 20, "Закрыть", b -> onClose());
    }

    private void buildDeclarePage(int panelX, int y) {
        int maxPages = (int) Math.ceil((double) state.targets.size() / 6);
        if (targetPage >= maxPages && maxPages > 0) targetPage = maxPages - 1;
        if (targetPage < 0) targetPage = 0;

        int start = targetPage * 6;
        int end = Math.min(start + 6, state.targets.size());

        for (int i = start; i < end; i++) {
            String target = state.targets.get(i);
            int idx = i - start;
            int col = idx % 2;
            int row = idx / 2;
            int bx = panelX + 25 + col * 130;
            int by = y + 86 + row * 24;
            final String t = target;
            addButton(bx, by, 120, 20, t, b -> {
                pendingTarget = t;
                openInput("raid_declare", "Объявить рейд городу:");
            });
        }
        
        if (targetPage > 0) {
            addButton(panelX + 25, y + 150, 40, 20, "<", b -> { targetPage--; rebuild(); });
        }
        addButton(panelX + 95, y + 150, 120, 20, "Назад", b -> switchPage("main"));
        if (targetPage < maxPages - 1) {
            addButton(panelX + 245, y + 150, 40, 20, ">", b -> { targetPage++; rebuild(); });
        }
    }

    private void buildActivePage(int panelX, int y) {
        if (state.activeRaid == null) {
            addButton(panelX + 95, y + 90, 120, 20, "Назад", b -> switchPage("main"));
            return;
        }
        addButton(panelX + 25, y + 150, 120, 20, "Обновить", b -> delayedRefresh());
        addButton(panelX + 155, y + 150, 120, 20, "Назад", b -> switchPage("main"));
    }

    private void buildHistoryPage(int panelX, int y) {
        addButton(panelX + 95, y + 150, 120, 20, "Назад", b -> switchPage("main"));
    }

    private void addButton(int x, int y, int w, int h, String text, Button.OnPress press) {
        addRenderableWidget(new StyledButton(x, y, w, h, Component.literal(text), press, StyledButton.Style.NORMAL));
    }

    private void addButton(int x, int y, int w, int h, String text, Button.OnPress press, boolean enabled) {
        StyledButton btn = new StyledButton(x, y, w, h, Component.literal(text), press, StyledButton.Style.NORMAL);
        btn.active = enabled;
        addRenderableWidget(btn);
    }

    private void switchPage(String page) {
        this.page = page;
        this.targetPage = 0;
        rebuild();
    }

    private void openInput(String action, String title) {
        this.inputAction = action;
        this.inputTitle = title;
        rebuild();
        if (input != null) setInitialFocus(input);
    }

    private void delayedRefresh() {
        Minecraft.getInstance().execute(WarsClientApi::requestRaid);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int panelX = width / 2 - 165;
        int panelY = height / 2 - 135;
        int panelW = 330;
        int panelH = 270;

        WarMenuScreen.drawPanel(graphics, panelX, panelY, panelW, panelH, "Рейды");
        drawInfo(graphics, panelX + 18, panelY + 38);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void drawInfo(GuiGraphics graphics, int x, int y) {
        List<Component> lines = new ArrayList<>();
        if (!inputAction.isEmpty()) {
            lines.add(Component.literal(inputTitle).withStyle(ChatFormatting.YELLOW));
            lines.add(Component.literal("Имя города из списка или введи вручную.").withStyle(ChatFormatting.GRAY));
        } else if (!state.loaded) {
            lines.add(Component.literal(state.message).withStyle(ChatFormatting.YELLOW));
            lines.add(Component.literal("Проверьте серверный плагин pe_wars и Towny.").withStyle(ChatFormatting.GRAY));
        } else if ("declare".equals(page)) {
            lines.add(Component.literal("Объявление рейда").withStyle(ChatFormatting.GOLD));
            lines.add(Component.literal("Цена: " + state.raidCost + " " + state.currency + " с банка города").withStyle(ChatFormatting.YELLOW));
            lines.add(Component.literal("Подготовка " + state.raidPrepMin + " мин → активная " + state.raidActiveMin + " мин.").withStyle(ChatFormatting.GRAY));
            if (state.targets.isEmpty()) {
                lines.add(Component.literal("Нет онлайн-городов для рейда.").withStyle(ChatFormatting.RED));
            } else {
                lines.add(Component.literal("Выбери цель кнопками ниже.").withStyle(ChatFormatting.GRAY));
            }
        } else if ("active".equals(page) && state.activeRaid != null) {
            RaidState.ActiveRaid r = state.activeRaid;
            lines.add(Component.literal("Активный рейд").withStyle(ChatFormatting.GOLD));
            lines.add(Component.literal(r.attacker + " → " + r.defender).withStyle(ChatFormatting.WHITE));
            lines.add(Component.literal("Фаза: " + WarMenuScreen.phaseRu(r.phase)).withStyle(WarMenuScreen.phaseColor(r.phase)));
            lines.add(Component.literal("Осталось: " + r.timeLeft).withStyle(ChatFormatting.YELLOW));
            lines.add(Component.literal("Твоя роль: " + WarMenuScreen.roleRu(r.role)).withStyle(WarMenuScreen.roleColor(r.role)));
        } else if ("history".equals(page)) {
            lines.add(Component.literal("История рейдов").withStyle(ChatFormatting.GOLD));
            if (state.history.isEmpty()) {
                lines.add(Component.literal("Рейдов пока не было.").withStyle(ChatFormatting.GRAY));
            } else {
                int idx = 1;
                for (RaidState.SummaryRaid r : state.history) {
                    if (idx > 5) break;
                    lines.add(Component.literal(idx + ". " + r.attacker + " → " + r.defender)
                            .withStyle(ChatFormatting.GRAY));
                    idx++;
                }
            }
        } else {
            // main
            if (state.activeRaid != null) {
                lines.add(Component.literal("Идёт рейд!").withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
                lines.add(Component.literal(state.activeRaid.attacker + " → " + state.activeRaid.defender).withStyle(ChatFormatting.WHITE));
                lines.add(Component.literal("Фаза: " + WarMenuScreen.phaseRu(state.activeRaid.phase)
                        + " | Осталось: " + state.activeRaid.timeLeft).withStyle(ChatFormatting.YELLOW));
            } else {
                lines.add(Component.literal("Рейдов сейчас нет.").withStyle(ChatFormatting.GRAY));
            }
            lines.add(Component.literal(""));
            lines.add(Component.literal("Город: " + (state.hasTown ? state.townName : "нет")).withStyle(ChatFormatting.GOLD));
            lines.add(Component.literal("Баланс города: " + state.townBalance + " " + state.currency));
            lines.add(Component.literal("Цена рейда: " + state.raidCost + " " + state.currency));
            lines.add(Component.literal("Рейд: подготовка " + state.raidPrepMin + " мин → активная " + state.raidActiveMin + " мин").withStyle(ChatFormatting.DARK_GRAY));
        }

        int lineY = y;
        for (Component line : lines) {
            graphics.drawString(Minecraft.getInstance().font, line, x, lineY, 0xEDEDED, false);
            lineY += 12;
        }
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    }

    @Override
    protected void renderBlurredBackground(float partialTick) {
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}

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
 * Главное меню войн в стиле townymenu (панель 330×270, золотая рамка, тёмный фон).
 * Страницы: main / active / declare / history.
 *
 * Открывается клавишей H или командой /t war. Данные приходят через [PETM_WAR].
 */
public class WarMenuScreen extends Screen {
    private static final int PAGE_BTN_STEP = 28;

    private WarState state = WarState.loading();
    private String page = "main";
    private String inputAction = "";
    private String inputTitle = "";
    private EditBox input;
    private String pendingTarget = "";
    private int targetPage = 0;

    public WarMenuScreen() {
        super(Component.literal("PE Wars"));
    }

    /** ФИКС: init() вызывается и при каждом ресайзе окна — не спамим сервер повторными запросами. */
    private boolean requestedOnce;

    @Override
    protected void init() {
        rebuild();
        if (!requestedOnce) {
            requestedOnce = true;
            WarsClientApi.requestWar();
        }
    }

    public void acceptState(WarState state) {
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
                    String action = inputAction;
                    WarsClientApi.warAction(action, value);
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

        if ("declare".equals(page)) {
            buildDeclarePage(panelX, y);
            return;
        }
        if ("active".equals(page)) {
            buildActivePage(panelX, y);
            return;
        }
        if ("history".equals(page)) {
            buildHistoryPage(panelX, y);
            return;
        }
        if ("decision".equals(page)) {
            buildDecisionPage(panelX, y);
            return;
        }

        // main
        buildMainPage(panelX, y);
    }

    private void buildMainPage(int panelX, int y) {
        boolean canDeclare = state.isMayor || (Minecraft.getInstance().player != null
                && Minecraft.getInstance().player.hasPermissions(2));

        // Ряд 1 (y+86): широкая высокая кнопка во всю ширину контента (250×28).
        if (state.decision != null) {
            addButton(panelX + 25, y + 86, 250, 28, "⚑ Выбрать исход войны", b -> switchPage("decision"));
        } else if (state.activeWar != null) {
            addButton(panelX + 25, y + 86, 250, 28, "Активная война", b -> switchPage("active"));
        } else {
            addButton(panelX + 25, y + 86, 250, 28, "Объявить войну", b -> switchPage("declare"), canDeclare);
        }
        // Ряд 2 (y+118): Рейды + История (или + Объявить при активной войне).
        addButton(panelX + 25, y + 118, 120, 20, "Рейды", b -> {
            onClose();
            Minecraft.getInstance().setScreen(new RaidMenuScreen());
        });
        if (state.activeWar != null) {
            addButton(panelX + 155, y + 118, 120, 20, "+ Объявить", b -> switchPage("declare"), canDeclare);
        } else {
            addButton(panelX + 155, y + 118, 120, 20, "История", b -> switchPage("history"));
        }
        // Ряд 3 (y+142): История/Обновить + Закрыть.
        if (state.activeWar != null) {
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
                openInput("war_declare", "Объявить войну городу:");
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
        if (state.activeWar == null) {
            addButton(panelX + 95, y + 90, 120, 20, "Назад", b -> switchPage("main"));
            return;
        }
        
        if (state.isMayor && state.activeWar.pendingHelpRequestTime <= System.currentTimeMillis()) {
            addButton(panelX + 25, y + 150, 250, 20, "Запросить помощь нации", b -> {
                openInput("war_request_help", "Сообщение к запросу:");
            });
            addButton(panelX + 25, y + 174, 120, 20, "Обновить", b -> delayedRefresh());
            addButton(panelX + 155, y + 174, 120, 20, "Назад", b -> switchPage("main"));
        } else {
            addButton(panelX + 25, y + 150, 120, 20, "Обновить", b -> delayedRefresh());
            addButton(panelX + 155, y + 150, 120, 20, "Назад", b -> switchPage("main"));
        }
    }

    private void buildHistoryPage(int panelX, int y) {
        addButton(panelX + 95, y + 150, 120, 20, "Назад", b -> switchPage("main"));
    }

    /** Страница выбора исхода войны после победы (3 варианта по ТЗ). */
    private void buildDecisionPage(int panelX, int y) {
        boolean can = state.decision != null && (state.decision.chooser
                || (Minecraft.getInstance().player != null && Minecraft.getInstance().player.hasPermissions(2)));
        addButton(panelX + 25, y + 74, 250, 20, "Забрать казну города", b -> {
            WarsClientApi.warAction("war_decide_treasury");
            switchPage("main");
            delayedRefresh();
        }, can);
        addButton(panelX + 25, y + 98, 250, 20, "Захватить город (все чанки)", b -> {
            WarsClientApi.warAction("war_decide_capture");
            switchPage("main");
            delayedRefresh();
        }, can);
        addButton(panelX + 25, y + 122, 250, 20, "Назначить своего мэра...", b ->
                openInput("war_decide_mayor", "Ник игрока из вашего города:"), can);
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
        Minecraft.getInstance().execute(WarsClientApi::requestWar);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int panelX = width / 2 - 165;
        int panelY = height / 2 - 135;
        int panelW = 330;
        int panelH = 270;

        drawPanel(graphics, panelX, panelY, panelW, panelH, "Войны");
        drawInfo(graphics, panelX + 18, panelY + 38);
        drawActiveContent(graphics, panelX, panelY);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    /** Общая отрисовка панели и шапки в стиле townymenu. */
    static void drawPanel(GuiGraphics graphics, int panelX, int panelY, int panelW, int panelH, String title) {
        // Мягкая тень под панелью.
        MenuTheme.fillRounded(graphics, panelX + 1, panelY + 1, panelX + panelW + 7, panelY + panelH + 7, 0x66000000);
        // Корпус панели: тёмный градиент со скруглёнными углами.
        MenuTheme.gradientRounded(graphics, panelX - 3, panelY - 3, panelX + panelW + 3, panelY + panelH + 3, 0xFA141B27, 0xFA0B0F16);
        // Шапка, плавно растворяющаяся в фоне.
        graphics.fillGradient(panelX - 2, panelY - 2, panelX + panelW + 2, panelY + 30, 0xFF1D2634, 0x00141B26);
        // Тонкая золотая рамка.
        MenuTheme.strokeRounded(graphics, panelX - 3, panelY - 3, panelX + panelW + 3, panelY + panelH + 3, 0xD9D9AC4F);
        // Разделитель шапки с ярким центром.
        graphics.fill(panelX + 10, panelY + 32, panelX + panelW - 10, panelY + 33, 0x2EE8B94E);
        graphics.fill(panelX + panelW / 2 - 36, panelY + 32, panelX + panelW / 2 + 36, panelY + 33, 0x99E8B94E);
        graphics.drawCenteredString(Minecraft.getInstance().font,
                Component.literal(title).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD),
                panelX + panelW / 2 + 1, panelY + 11, 0x66100000);
        graphics.drawCenteredString(Minecraft.getInstance().font,
                Component.literal(title).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD),
                panelX + panelW / 2, panelY + 10, 0xFFFFC84D);
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
            lines.add(Component.literal("Объявление войны").withStyle(ChatFormatting.GOLD));
            lines.add(Component.literal("Цена: " + state.warCost + " " + state.currency + " с банка города").withStyle(ChatFormatting.YELLOW));
            lines.add(Component.literal("Нужно " + state.warMinOnline + " онлайн в городе-цели.").withStyle(ChatFormatting.GRAY));
            if (state.targets.isEmpty()) {
                lines.add(Component.literal("Нет онлайн-городов для атаки.").withStyle(ChatFormatting.RED));
            } else {
                lines.add(Component.literal("Выбери цель кнопками ниже.").withStyle(ChatFormatting.GRAY));
            }
        } else if ("active".equals(page) && state.activeWar != null) {
            WarState.ActiveWar w = state.activeWar;
            lines.add(Component.literal("Активная война").withStyle(ChatFormatting.GOLD));
            lines.add(Component.literal(w.attacker + " → " + w.defender).withStyle(ChatFormatting.WHITE));
            String phaseRu = phaseRu(w.phase);
            lines.add(Component.literal("Фаза: " + phaseRu).withStyle(phaseColor(w.phase)));
            lines.add(Component.literal("Осталось: " + w.timeLeft).withStyle(ChatFormatting.YELLOW));
            lines.add(Component.literal("Чанков захвачено: " + w.capturedChunks + " / " + w.totalChunks).withStyle(ChatFormatting.GRAY));
            lines.add(Component.literal("Центр. чанк: " + (w.centralCaptured ? "Захвачен" : "Под контролем защиты"))
                    .withStyle(w.centralCaptured ? ChatFormatting.RED : ChatFormatting.GREEN));
            lines.add(Component.literal("Ваша роль: " + roleRu(w.role)).withStyle(roleColor(w.role)));
            if (w.pendingHelpRequestTime > System.currentTimeMillis()) {
                long left = (w.pendingHelpRequestTime - System.currentTimeMillis()) / 1000;
                lines.add(Component.literal("Запрос помощи доставляется: " + left + " сек").withStyle(ChatFormatting.AQUA));
            }
        } else if ("decision".equals(page)) {
            lines.add(Component.literal("Исход войны").withStyle(ChatFormatting.GOLD));
            String defeated = state.decision != null ? state.decision.defender : "";
            lines.add(Component.literal("Побеждён город: " + defeated).withStyle(ChatFormatting.WHITE));
            lines.add(Component.literal("Казна — все деньги, чанки вернутся городу.").withStyle(ChatFormatting.GRAY));
            lines.add(Component.literal("Захват — вся территория + аванпост в центре.").withStyle(ChatFormatting.GRAY));
            lines.add(Component.literal("Мэр — свой правитель, город остаётся.").withStyle(ChatFormatting.GRAY));
            if (state.decision != null && !state.decision.chooser) {
                lines.add(Component.literal("Выбор делает мэр города-победителя.").withStyle(ChatFormatting.RED));
            }
        } else if ("history".equals(page)) {
            lines.add(Component.literal("История войн").withStyle(ChatFormatting.GOLD));
            if (state.history.isEmpty()) {
                lines.add(Component.literal("Войн пока не было.").withStyle(ChatFormatting.GRAY));
            } else {
                int idx = 1;
                for (WarState.SummaryWar w : state.history) {
                    if (idx > 5) break;
                    String win = winnerRu(w.winner);
                    lines.add(Component.literal(idx + ". " + w.attacker + " → " + w.defender + " (" + win + ")")
                            .withStyle(ChatFormatting.GRAY));
                    idx++;
                }
            }
        } else {
            // main
            if (state.activeWar != null) {
                lines.add(Component.literal("Идёт война!").withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
                lines.add(Component.literal(state.activeWar.attacker + " → " + state.activeWar.defender).withStyle(ChatFormatting.WHITE));
                lines.add(Component.literal("Фаза: " + phaseRu(state.activeWar.phase) + " | Осталось: " + state.activeWar.timeLeft)
                        .withStyle(ChatFormatting.YELLOW));
            } else if (state.decision != null) {
                lines.add(Component.literal("Победа!").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
                lines.add(Component.literal("Выберите исход войны против " + state.decision.defender + ".").withStyle(ChatFormatting.YELLOW));
            } else {
                lines.add(Component.literal("Войн сейчас нет.").withStyle(ChatFormatting.GRAY));
            }
            lines.add(Component.literal(""));
            lines.add(Component.literal("Город: " + (state.hasTown ? state.townName : "нет")).withStyle(ChatFormatting.GOLD));
            lines.add(Component.literal("Баланс города: " + state.townBalance + " " + state.currency));
            lines.add(Component.literal("Цена войны: " + state.warCost + " " + state.currency));
            lines.add(Component.literal("Война: подготовка " + state.warPrepMin + " мин → активная " + state.warActiveMin + " мин").withStyle(ChatFormatting.DARK_GRAY));
        }

        int lineY = y;
        for (Component line : lines) {
            graphics.drawString(Minecraft.getInstance().font, line, x, lineY, 0xEDEDED, false);
            lineY += 12;
        }
    }

    private void drawActiveContent(GuiGraphics graphics, int panelX, int panelY) {
        // Прогресс-бар захвата чанков для активной войны — под инфо-блоком, над кнопками.
        if (!"active".equals(page) || state.activeWar == null) return;
        WarState.ActiveWar w = state.activeWar;
        try {
            int total = Integer.parseInt(w.totalChunks);
            int captured = Integer.parseInt(w.capturedChunks);
            if (total <= 0) return;
            int barX = panelX + 24;
            int barY = panelY + 130;
            int barW = 282;
            int barH = 14;
            MenuTheme.fillRounded(graphics, barX - 2, barY - 2, barX + barW + 2, barY + barH + 2, 0xFF232A33);
            int filled = (int) ((double) captured / total * barW);
            if (filled > 2) {
                MenuTheme.fillRounded(graphics, barX, barY, barX + filled, barY + barH, 0xFFCC4B4B);
            }
            MenuTheme.strokeRounded(graphics, barX - 2, barY - 2, barX + barW + 2, barY + barH + 2, 0x552F3540);
            graphics.drawCenteredString(Minecraft.getInstance().font,
                    Component.literal("Захват чанков: " + captured + "/" + total),
                    panelX + 165, barY + 3, 0xFFFFFFFF);
        } catch (NumberFormatException ignored) {
        }
    }

    static String phaseRu(String phase) {
        return switch (phase) {
            case "PREPARATION" -> "Подготовка";
            case "ACTIVE" -> "Активная";
            case "ENDED" -> "Завершена";
            default -> phase;
        };
    }

    static ChatFormatting phaseColor(String phase) {
        return switch (phase) {
            case "PREPARATION" -> ChatFormatting.YELLOW;
            case "ACTIVE" -> ChatFormatting.RED;
            default -> ChatFormatting.GRAY;
        };
    }

    static String roleRu(String role) {
        return switch (role) {
            case "attacker" -> "Атакующий";
            case "defender" -> "Защитник";
            default -> "Наблюдатель";
        };
    }

    static ChatFormatting roleColor(String role) {
        return switch (role) {
            case "attacker" -> ChatFormatting.RED;
            case "defender" -> ChatFormatting.AQUA;
            default -> ChatFormatting.GRAY;
        };
    }

    static String winnerRu(String winner) {
        return switch (winner) {
            case "attacker" -> "победа атакующих";
            case "defender" -> "защита удержана";
            default -> winner;
        };
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Отключаем стандартный фон и блюр мира.
    }

    @Override
    protected void renderBlurredBackground(float partialTick) {
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}

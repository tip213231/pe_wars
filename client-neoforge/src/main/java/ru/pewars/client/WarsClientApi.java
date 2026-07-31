package ru.pewars.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Мост между клиентским модом и серверным плагином pe_wars.
 * Запросы идут через sendCommand("t war"/"t raid"/"pewar action ..."),
 * ответы приходят как чат-сообщения с маркерами [PETM_WAR]/[PETM_RAID].
 *
 * Аргументы действий кодируются base64-url без padding (как в pe_townymenu).
 */
public final class WarsClientApi {
    public static final String WAR_MARKER = "[PETM_WAR]";
    public static final String RAID_MARKER = "[PETM_RAID]";

    private static WarState lastWarState = WarState.loading();
    private static RaidState lastRaidState = RaidState.loading();

    private WarsClientApi() {
    }

    public static WarState lastWarState() {
        return lastWarState;
    }

    public static RaidState lastRaidState() {
        return lastRaidState;
    }

    public static void requestWar() {
        sendCommand("t war");
    }

    public static void requestRaid() {
        sendCommand("t raid");
    }

    public static void warAction(String action) {
        sendCommand("pewar action " + action);
    }

    public static void warAction(String action, String argument) {
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(argument.getBytes(StandardCharsets.UTF_8));
        sendCommand("pewar action " + action + " " + encoded);
    }

    public static void raidAction(String action) {
        sendCommand("peraid action " + action);
    }

    public static void raidAction(String action, String argument) {
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(argument.getBytes(StandardCharsets.UTF_8));
        sendCommand("peraid action " + action + " " + encoded);
    }

    public static void handleWarMessage(String raw) {
        try {
            String jsonText = raw.substring(WAR_MARKER.length()).trim();
            JsonObject json = JsonParser.parseString(jsonText).getAsJsonObject();
            lastWarState = WarState.fromJson(json);
            Minecraft.getInstance().execute(() -> {
                Minecraft mc = Minecraft.getInstance();
                if (mc.screen instanceof WarMenuScreen screen) {
                    screen.acceptState(lastWarState);
                } else if (mc.screen == null || mc.screen instanceof net.minecraft.client.gui.screens.ChatScreen) {
                    // ФИКС: открываем меню только если игрок не занят другим экраном.
                    // Раньше любой входящий маркер принудительно перекрывал текущий
                    // экран (инвентарь, сундук и т.п.) меню войн.
                    WarMenuScreen screen = new WarMenuScreen();
                    mc.setScreen(screen);
                    screen.acceptState(lastWarState);
                }
                // Иначе состояние просто сохранено в lastWarState.
            });
        } catch (Exception exception) {
            // ФИКС NPE: player может быть null (смена мира/выход с сервера).
            var player = Minecraft.getInstance().player;
            if (player != null) {
                player.displayClientMessage(
                        Component.literal("Не удалось прочитать данные войн pe_wars: " + exception.getMessage()), false);
            }
        }
    }

    public static void handleRaidMessage(String raw) {
        try {
            String jsonText = raw.substring(RAID_MARKER.length()).trim();
            JsonObject json = JsonParser.parseString(jsonText).getAsJsonObject();
            lastRaidState = RaidState.fromJson(json);
            Minecraft.getInstance().execute(() -> {
                Minecraft mc = Minecraft.getInstance();
                if (mc.screen instanceof RaidMenuScreen screen) {
                    screen.acceptState(lastRaidState);
                } else if (mc.screen == null || mc.screen instanceof net.minecraft.client.gui.screens.ChatScreen) {
                    // ФИКС: открываем меню только если игрок не занят другим экраном
                    // (аналогично handleWarMessage).
                    RaidMenuScreen screen = new RaidMenuScreen();
                    mc.setScreen(screen);
                    screen.acceptState(lastRaidState);
                }
                // Иначе состояние просто сохранено в lastRaidState.
            });
        } catch (Exception exception) {
            // ФИКС NPE: player может быть null (смена мира/выход с сервера).
            var player = Minecraft.getInstance().player;
            if (player != null) {
                player.displayClientMessage(
                        Component.literal("Не удалось прочитать данные рейдов pe_wars: " + exception.getMessage()), false);
            }
        }
    }

    private static void sendCommand(String command) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.getConnection() == null) {
            return;
        }
        minecraft.getConnection().sendCommand(command);
    }
}

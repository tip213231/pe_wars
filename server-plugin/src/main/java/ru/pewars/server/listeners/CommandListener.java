package ru.pewars.server.listeners;

import net.md_5.bungee.api.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import ru.pewars.server.Config;
import ru.pewars.server.StateSerializer;
import ru.pewars.server.towny.TownyBridge;
import ru.pewars.server.raid.Raid;
import ru.pewars.server.raid.RaidManager;
import ru.pewars.server.raid.RaidPhase;
import ru.pewars.server.war.War;
import ru.pewars.server.war.WarManager;
import ru.pewars.server.war.WarPhase;

/**
 * Перехватывает команды, связанные с меню войн/рейдов, и блокирует
 * критические команды во время АКТИВНОЙ фазы войны ИЛИ рейда (п.14 ТЗ):
 *   /t delete, /t withdraw, /t toggle * (ВСЕ тумблеры) + список из конфига.
 *
 * Блокировка всех /t toggle * также гарантирует, что PvP нельзя выключить
 * командой во время войны (второй рубеж — принудительное восстановление
 * флага в WarManager.tick()).
 *
 * /t war, /town war, /t raid, /town raid — открывают клиентское меню
 * (плагин шлёт [PETM_WAR]/[PETM_RAID] маркер, клиент pe_wars ловит).
 */
public final class CommandListener implements Listener {
    private final Config config;
    private final TownyBridge towny;
    private final WarManager wars;
    private final RaidManager raids;
    private final StateSerializer serializer;

    public CommandListener(Config config, TownyBridge towny, WarManager wars, RaidManager raids,
                           StateSerializer serializer) {
        this.config = config;
        this.towny = towny;
        this.wars = wars;
        this.raids = raids;
        this.serializer = serializer;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        String message = event.getMessage().trim();
        if (message.isEmpty()) return;
        Player player = event.getPlayer();

        // Нормализуем ОДИН раз и переиспользуем для всех проверок.
        String normalized = Config.normalizeCommand(message);

        // Перехват открытия меню войн/рейдов.
        if (normalized.equals("t war") || normalized.equals("town war")
                || normalized.equals("t война") || normalized.equals("town война")) {
            event.setCancelled(true);
            player.sendMessage(StateSerializer.WAR_MARKER + serializer.buildWar(player));
            return;
        }
        if (normalized.equals("t raid") || normalized.equals("town raid")
                || normalized.equals("t рейд") || normalized.equals("town рейд")) {
            event.setCancelled(true);
            player.sendMessage(StateSerializer.RAID_MARKER + serializer.buildRaid(player));
            return;
        }

        // П.14 ТЗ: блокировка критических команд для участников АКТИВНОЙ фазы войны ИЛИ рейда.
        ActiveConflict conflict = getActiveConflictForPlayer(player);
        if (conflict == null) return;

        // ФИКС (п.27): раньше любая заблокированная команда, введённая на территории
        // конфликта, отвечала сообщением про телепорт на спавн. Теперь каждый
        // случай получает своё сообщение.
        if (isBlockedCommand(normalized)) {
            event.setCancelled(true);
            player.sendMessage(color(config.chat("cmd-blocked-war", "cmd", commandLabel(message))));
            return;
        }

        // Телепорты запрещены только НА территории конфликта (чтобы нельзя было
        // сбежать из боя). Проверку территории делаем лениво — только если команда
        // вообще похожа на телепорт, а не на каждую команду игрока.
        if (isBlockedSpawnTeleport(normalized) && isPlayerInConflictTerritory(player, conflict)) {
            event.setCancelled(true);
            player.sendMessage(color(config.chat("spawn-blocked-war")));
        }
    }

    /** Имя команды без ведущего слеша и аргументов — для подстановки в сообщение. */
    private String commandLabel(String message) {
        String raw = message.startsWith("/") ? message.substring(1) : message;
        int space = raw.indexOf(' ');
        return space >= 0 ? raw.substring(0, space) : raw;
    }

    /** Активный конфликт игрока: война или рейд в АКТИВНОЙ фазе. */
    private record ActiveConflict(String attackerTownName, String defenderTownName) {}

    private ActiveConflict getActiveConflictForPlayer(Player player) {
        Object town = towny.getTown(player);
        if (town == null) return null;
        String townName = towny.townName(town);
        if (townName == null || townName.isBlank()) return null;

        War war = wars.getWarInvolving(townName);
        if (war != null && war.phase == WarPhase.ACTIVE) {
            return new ActiveConflict(war.attackerTownName, war.defenderTownName);
        }
        Raid raid = raids.getRaidInvolving(townName);
        if (raid != null && raid.phase == RaidPhase.ACTIVE) {
            return new ActiveConflict(raid.attackerTownName, raid.defenderTownName);
        }
        return null;
    }

    /** Команда из зашитого в код блок-листа или из конфига war.blocked-commands-during-war? */
    private boolean isBlockedCommand(String normalized) {
        if (isHardcodedBlockedCommand(normalized)) {
            return true;
        }
        for (String blocked : config.blockedCommandsDuringWar) {
            String normBlocked = Config.normalizeCommand(blocked);
            if (!normBlocked.isEmpty()) {
                if (normalized.equals(normBlocked) || normalized.startsWith(normBlocked + " ")) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Зашитый в код блок-лист критических команд во время войны/рейда (п.14 ТЗ).
     * Нельзя обходить через алиасы, пробелы или спец-синтаксис (towny:t …).
     */
    private boolean isHardcodedBlockedCommand(String cmd) {
        if (cmd == null || cmd.isBlank()) return false;
        String[] parts = cmd.split(" ");
        if (parts.length == 0) return false;
        String base = parts[0];

        // 1. Команды города: /t, /town
        if (base.equals("t") || base.equals("town")) {
            if (parts.length >= 2) {
                String sub = parts[1];
                // П.14 ТЗ: /t delete, /t withdraw, /t toggle * (ВСЕ тумблеры без исключений)
                if (sub.equals("delete") || sub.equals("withdraw") || sub.equals("toggle")) return true;
                // Банк и управление жителями
                if (sub.equals("deposit")) return true;
                if (sub.equals("invite") || sub.equals("add") || sub.equals("kick") || sub.equals("leave")) return true;
                // Клеймы & аванпосты
                if (sub.equals("claim") || sub.equals("unclaim") || sub.equals("reclaim")) return true;
                // Установка ключевых свойств
                if (sub.equals("set")) {
                    if (parts.length >= 3) {
                        String prop = parts[2];
                        if (prop.equals("spawn") || prop.equals("homeblock") || prop.equals("pvp")
                                || prop.equals("mayor") || prop.equals("name") || prop.equals("board")) return true;
                    }
                }
                // Пермишены Towny (могут влиять на PvP/защиту)
                if (sub.equals("perm") || sub.equals("permissions")) return true;
            }
        }

        // 2. Команды нации: /n, /nation
        if (base.equals("n") || base.equals("nation")) {
            if (parts.length >= 2) {
                String sub = parts[1];
                if (sub.equals("deposit") || sub.equals("withdraw") || sub.equals("add")
                        || sub.equals("kick") || sub.equals("leave") || sub.equals("delete")
                        || sub.equals("toggle")) return true;
            }
        }

        // 3. Команды плотов: /plot (тумблеры и pvp-свойства плотов)
        if (base.equals("plot")) {
            if (parts.length >= 2) {
                String sub = parts[1];
                if (sub.equals("toggle")) return true;
                if (sub.equals("set") && parts.length >= 3 && parts[2].equals("pvp")) return true;
            }
        }

        return false;
    }

    /** Телепорт на спавн города/резиденции или команды побега, запрещённые при конфликте? */
    private boolean isBlockedSpawnTeleport(String normalized) {
        if (!config.blockSpawnTeleports) return false;
        return matches(normalized, "t spawn")
                || matches(normalized, "town spawn")
                || matches(normalized, "res spawn")
                || matches(normalized, "tpa")
                || matches(normalized, "tpaccept")
                || matches(normalized, "tpdeny")
                || matches(normalized, "rtp")
                || matches(normalized, "wild")
                || matches(normalized, "home")
                || matches(normalized, "spawn")
                || matches(normalized, "warp")
                || matches(normalized, "back");
    }

    /** Точное совпадение команды или команда с аргументами. */
    private boolean matches(String normalized, String command) {
        return normalized.equals(command) || normalized.startsWith(command + " ");
    }

    /** Находится ли игрок на территории одного из городов конфликта (включая аванпосты). */
    private boolean isPlayerInConflictTerritory(Player player, ActiveConflict conflict) {
        Object townAt = towny.getTownAt(player.getLocation());
        if (townAt == null) return false;
        String name = towny.townName(townAt);
        if (name == null) return false;
        return name.equalsIgnoreCase(conflict.defenderTownName())
                || name.equalsIgnoreCase(conflict.attackerTownName());
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text);
    }
}

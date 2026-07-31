package ru.pewars.server.listeners;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Level;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.java.JavaPlugin;
import ru.pewars.server.towny.TownyBridge;
import ru.pewars.server.raid.Raid;
import ru.pewars.server.raid.RaidManager;
import ru.pewars.server.raid.RaidPhase;
import ru.pewars.server.war.War;
import ru.pewars.server.war.WarManager;
import ru.pewars.server.war.WarPhase;

/**
 * Подавление спама сообщений Towny («PvP выключено на этой территории»,
 * «Чужакам нельзя строить/ломать» и т.п.) во время активной войны/рейда.
 *
 * ПРОБЛЕМА: Towny отменяет Bukkit-событие и СРАЗУ отправляет игроку
 * сообщение об отказе. Наши слушатели (HIGHEST) переоткрывали событие —
 * действие происходило (бить/ломать можно), но сообщение Towny уже улетало
 * в чат — отсюда постоянный спам.
 *
 * РЕШЕНИЕ: Towny ПЕРЕД отказом кидает свои события (TownyBuildEvent,
 * TownyDestroyEvent, TownySwitchEvent, TownyItemuseEvent,
 * TownyPlayerDamagePlayerEvent). Если на них снять отмену — Towny считает
 * действие разрешённым: НЕ отменяет Bukkit-событие И НЕ ШЛЁТ сообщение.
 *
 * Тонкие правила (какие блоки можно ломать на рейде, PvP только между
 * участниками и т.д.) по-прежнему применяют BlockListener и CombatListener
 * на Bukkit-событиях — они отменят недопустимое действие уже с НАШИМ
 * понятным сообщением (без дублей от Towny).
 *
 * Классы Towny недоступны на компиляции (вся интеграция через рефлексию),
 * поэтому слушатели регистрируются динамически: Class.forName + registerEvent.
 * Если версия Towny не содержит какое-то событие — оно просто пропускается.
 *
 * ПРОИЗВОДИТЕЛЬНОСТЬ: эти обработчики вызываются на каждое действие с блоком
 * НА ВСЁМ СЕРВЕРЕ, поэтому результаты поиска методов через рефлексию
 * кешируются (включая отрицательный результат).
 */
public final class TownyActionListener implements Listener {
    /** Маркер «метод точно отсутствует» — ConcurrentHashMap не умеет хранить null. */
    private static final Method NO_METHOD;

    static {
        try {
            NO_METHOD = Object.class.getMethod("hashCode");
        } catch (NoSuchMethodException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final JavaPlugin plugin;
    private final TownyBridge towny;
    private final WarManager wars;
    private final RaidManager raids;

    /** Кеш разрешённых методов: "<класс события>#<имя1,имя2>" -> Method или NO_METHOD. */
    private final Map<String, Method> methodCache = new ConcurrentHashMap<>();

    public TownyActionListener(JavaPlugin plugin, TownyBridge towny, WarManager wars, RaidManager raids) {
        this.plugin = plugin;
        this.towny = towny;
        this.wars = wars;
        this.raids = raids;
    }

    public void register() {
        // Действия с блоками: строительство, разрушение, рычаги/двери/контейнеры, предметы.
        registerTownyEvent("com.palmergames.bukkit.towny.event.actions.TownyBuildEvent", this::handleAction);
        registerTownyEvent("com.palmergames.bukkit.towny.event.actions.TownyDestroyEvent", this::handleAction);
        registerTownyEvent("com.palmergames.bukkit.towny.event.actions.TownySwitchEvent", this::handleAction);
        registerTownyEvent("com.palmergames.bukkit.towny.event.actions.TownyItemuseEvent", this::handleAction);
        // PvP: именно отсюда берётся спам «PvP выключено на этой территории».
        registerTownyEvent("com.palmergames.bukkit.towny.event.damage.TownyPlayerDamagePlayerEvent", this::handleDamage);
    }

    private void registerTownyEvent(String className, Consumer<Event> handler) {
        try {
            Class<?> raw = Class.forName(className);
            if (!Event.class.isAssignableFrom(raw)) return;
            @SuppressWarnings("unchecked")
            Class<? extends Event> cls = (Class<? extends Event>) raw;
            EventExecutor executor = (listener, event) -> {
                if (cls.isInstance(event)) {
                    try {
                        handler.accept(event);
                    } catch (Throwable e) {
                        // Никогда не ломаем обработку событий Towny из-за рефлексии,
                        // но и не глотаем ошибку молча.
                        plugin.getLogger().log(Level.FINE,
                                "Ошибка в обработчике события Towny " + className, e);
                    }
                }
            };
            plugin.getServer().getPluginManager().registerEvent(cls, this, EventPriority.HIGHEST, executor, plugin, false);
        } catch (ClassNotFoundException | NoClassDefFoundError ignored) {
            // Старая/другая версия Towny без этого события — пропускаем.
        }
    }

    // ===================== Build / Destroy / Switch / Itemuse =====================

    private void handleAction(Event event) {
        if (!(event instanceof Cancellable cancellable) || !cancellable.isCancelled()) return; // Towny и так разрешил.

        Player player = asPlayer(call(event, "getPlayer"));
        Location loc = asLocation(call(event, "getLocation"));
        if (player == null || loc == null) return;

        // Война: участникам ОБЕИХ сторон разрешаем действия во всей зоне войны
        // (оба города, включая аванпосты) — Towny не шлёт своё сообщение.
        War war = warAt(loc);
        if (war != null && war.phase == WarPhase.ACTIVE
                && (isInTown(player, war.attackerTownName)
                    || isInTown(player, war.defenderTownName)
                    || player.hasPermission("pewars.admin"))) {
            cancellable.setCancelled(false);
            return;
        }

        // Рейд: атакующим разрешаем действия в городе-защитнике.
        Raid raid = raidAt(loc);
        if (raid != null && raid.phase == RaidPhase.ACTIVE
                && (isInTown(player, raid.attackerTownName) || player.hasPermission("pewars.admin"))) {
            cancellable.setCancelled(false);
        }
    }

    // ===================== PvP («PvP выключено на этой территории») =====================

    private void handleDamage(Event event) {
        if (!(event instanceof Cancellable cancellable) || !cancellable.isCancelled()) return;

        Player attacker = asPlayer(call(event, "getAttackingPlayer", "getAttacker"));
        Player victim = asPlayer(call(event, "getVictimPlayer", "getPlayer", "getVictim"));
        if (attacker == null || victim == null || attacker.equals(victim)) return;

        Location loc = asLocation(call(event, "getLocation"));
        if (loc == null) loc = victim.getLocation();

        // Война: PvP между противоположными сторонами в зоне войны — разрешаем
        // на уровне Towny, чтобы он не слал «PvP выключено» каждый удар.
        War war = warAt(loc);
        if (war == null) war = warAt(attacker.getLocation());
        if (war != null && war.phase == WarPhase.ACTIVE) {
            boolean cross = (isInTown(attacker, war.attackerTownName) && isInTown(victim, war.defenderTownName))
                    || (isInTown(victim, war.attackerTownName) && isInTown(attacker, war.defenderTownName));
            if (cross) {
                cancellable.setCancelled(false);
            }
            return;
        }

        // Рейд: PvP между сторонами в городе-защитнике.
        Raid raid = raidAt(loc);
        if (raid != null && raid.phase == RaidPhase.ACTIVE) {
            boolean cross = (isInTown(attacker, raid.attackerTownName) && isInTown(victim, raid.defenderTownName))
                    || (isInTown(victim, raid.attackerTownName) && isInTown(attacker, raid.defenderTownName));
            if (cross) {
                cancellable.setCancelled(false);
            }
        }
    }

    // ===================== Helpers =====================

    /**
     * Вызывает первый существующий метод без аргументов из списка имён.
     * Результат поиска кешируется: класс события у конкретной версии Towny не меняется
     * в рантайме, а без кеша мы делали getMethod (и ловили исключения) на каждое
     * событие с блоком на всём сервере.
     */
    private Object call(Object target, String... methodNames) {
        Class<?> targetClass = target.getClass();
        String cacheKey = targetClass.getName() + "#" + String.join(",", methodNames);

        Method resolved = methodCache.computeIfAbsent(cacheKey, key -> {
            for (String name : methodNames) {
                try {
                    return targetClass.getMethod(name);
                } catch (NoSuchMethodException ignored) {
                    // Пробуем следующее имя — разные версии Towny.
                }
            }
            return NO_METHOD;
        });

        if (resolved == NO_METHOD) return null;

        try {
            return resolved.invoke(target);
        } catch (Throwable e) {
            plugin.getLogger().log(Level.FINE, "Не удалось вызвать " + resolved.getName()
                    + " на " + targetClass.getName(), e);
            return null;
        }
    }

    private Player asPlayer(Object o) {
        return o instanceof Player p ? p : null;
    }

    private Location asLocation(Object o) {
        return o instanceof Location l ? l : null;
    }

    /** Зона войны: ВЕСЬ город-защитник и ВЕСЬ город-атакующий (включая аванпосты). */
    private War warAt(Location loc) {
        Object town = towny.getTownAt(loc);
        if (town == null) return null;
        String townName = towny.townName(town);
        War war = wars.getWarInvolving(townName);
        if (war == null) return null;
        if (townName.equalsIgnoreCase(war.defenderTownName)
                || townName.equalsIgnoreCase(war.attackerTownName)) {
            return war;
        }
        return null;
    }

    /**
     * Зона рейда — это ТОЛЬКО территория города-защитника.
     * RaidManager индексирует рейд по обоим городам, поэтому обязательно
     * проверяем, что найденный город действительно защищается, иначе город
     * самих атакующих тоже становится зоной рейда.
     */
    private Raid raidAt(Location loc) {
        Object town = towny.getTownAt(loc);
        if (town == null) return null;
        String townName = towny.townName(town);
        Raid raid = raids.getRaidByDefender(townName);
        if (raid == null) return null;
        return raid.defenderTownName.equalsIgnoreCase(townName) ? raid : null;
    }

    private boolean isInTown(Player player, String townName) {
        Object town = towny.getTown(player);
        return town != null && townName != null && townName.equalsIgnoreCase(towny.townName(town));
    }
}

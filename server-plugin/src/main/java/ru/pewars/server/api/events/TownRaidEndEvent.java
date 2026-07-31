package ru.pewars.server.api.events;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Вызывается при завершении рейда (п.17 ТЗ). */
public class TownRaidEndEvent extends Event {

    public enum Result {
        /** Рейд успешен: атакующие удержались до конца. */
        ATTACKER_WIN,
        /** Рейд отбит защитниками. */
        DEFENDED,
        /** Рейд отменён. */
        CANCELLED
    }

    private static final HandlerList HANDLERS = new HandlerList();

    private final String attackerTown;
    private final String defenderTown;
    private final Result result;
    private final double loot;

    public TownRaidEndEvent(String attackerTown, String defenderTown, Result result, double loot) {
        this.attackerTown = attackerTown;
        this.defenderTown = defenderTown;
        this.result = result;
        this.loot = loot;
    }

    public String getAttackerTown() {
        return attackerTown;
    }

    public String getDefenderTown() {
        return defenderTown;
    }

    public Result getResult() {
        return result;
    }

    /** Сумма, переданная атакующим из казны защитника (0 при поражении). */
    public double getLoot() {
        return loot;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}

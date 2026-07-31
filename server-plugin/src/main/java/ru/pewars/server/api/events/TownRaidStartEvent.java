package ru.pewars.server.api.events;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Вызывается при старте активной фазы рейда (п.17 ТЗ). */
public class TownRaidStartEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final String attackerTown;
    private final String defenderTown;

    public TownRaidStartEvent(String attackerTown, String defenderTown) {
        this.attackerTown = attackerTown;
        this.defenderTown = defenderTown;
    }

    public String getAttackerTown() {
        return attackerTown;
    }

    public String getDefenderTown() {
        return defenderTown;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}

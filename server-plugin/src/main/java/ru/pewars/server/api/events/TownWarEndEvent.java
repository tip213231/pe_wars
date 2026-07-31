package ru.pewars.server.api.events;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Вызывается при завершении войны любым исходом (п.17 ТЗ). */
public class TownWarEndEvent extends Event {

    public enum Result {
        /** Победа атакующих (75% чанков, центральный чанк или капитуляция защитника). */
        ATTACKER_WIN,
        /** Победа защитников (таймаут, конец фазы, капитуляция атакующего). */
        DEFENDER_WIN,
        /** Подписан мирный договор. */
        PEACE_TREATY,
        /** Война отменена. */
        CANCELLED
    }

    private static final HandlerList HANDLERS = new HandlerList();

    private final String attackerTown;
    private final String defenderTown;
    private final Result result;
    private final String surrenderedBy;
    private final int capturedChunks;

    public TownWarEndEvent(String attackerTown, String defenderTown, Result result,
                           String surrenderedBy, int capturedChunks) {
        this.attackerTown = attackerTown;
        this.defenderTown = defenderTown;
        this.result = result;
        this.surrenderedBy = surrenderedBy;
        this.capturedChunks = capturedChunks;
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

    /** Город, подписавший капитуляцию, либо null. */
    public String getSurrenderedBy() {
        return surrenderedBy;
    }

    public int getCapturedChunks() {
        return capturedChunks;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}

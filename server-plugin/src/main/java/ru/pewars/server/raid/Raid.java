package ru.pewars.server.raid;

import java.util.UUID;

/**
 * Данные одного рейда.
 * Все Towny-объекты (town) держим как Object.
 */
public final class Raid {
    public final UUID id;
    public RaidPhase phase;

    public final String attackerTownName;
    public final String defenderTownName;

    public transient Object attackerTown;
    public transient Object defenderTown;

    public final long preparationEnd;
    public final long activeEnd;

    /** Время последнего присутствия атакующего в зоне защитника. */
    public long lastAttackerSeen;

    public Raid(UUID id, String attackerTownName, String defenderTownName,
                long preparationEnd, long activeEnd) {
        this.id = id;
        this.phase = RaidPhase.PREPARATION;
        this.attackerTownName = attackerTownName;
        this.defenderTownName = defenderTownName;
        this.preparationEnd = preparationEnd;
        this.activeEnd = activeEnd;
        this.lastAttackerSeen = System.currentTimeMillis();
    }

    public boolean isActive() {
        return phase != RaidPhase.ENDED;
    }
}

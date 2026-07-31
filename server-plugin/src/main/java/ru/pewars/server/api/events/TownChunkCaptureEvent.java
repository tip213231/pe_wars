package ru.pewars.server.api.events;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Вызывается при захвате чанка флагом или возврате чанка защитниками (п.17 ТЗ). */
public class TownChunkCaptureEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final String attackerTown;
    private final String defenderTown;
    private final String world;
    private final int chunkX;
    private final int chunkZ;
    private final boolean central;
    private final boolean recaptured;

    public TownChunkCaptureEvent(String attackerTown, String defenderTown, String world,
                                 int chunkX, int chunkZ, boolean central, boolean recaptured) {
        this.attackerTown = attackerTown;
        this.defenderTown = defenderTown;
        this.world = world;
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.central = central;
        this.recaptured = recaptured;
    }

    public String getAttackerTown() {
        return attackerTown;
    }

    public String getDefenderTown() {
        return defenderTown;
    }

    public String getWorld() {
        return world;
    }

    public int getChunkX() {
        return chunkX;
    }

    public int getChunkZ() {
        return chunkZ;
    }

    /** true — событие относится к центральному чанку. */
    public boolean isCentral() {
        return central;
    }

    /** true — чанк возвращён защитниками, false — захвачен атакующими. */
    public boolean isRecaptured() {
        return recaptured;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}

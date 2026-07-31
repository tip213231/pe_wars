package ru.pewars.server.war;

import ru.pewars.server.towny.TownyBridge.ChunkCoord;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Данные одной войны (town-based: атакующий — город, не нация).
 *
 * Захват чанков происходит через установку флага захвата (см. CaptureFlagManager).
 * Центральный чанк (homeblock защитника) требует N успешных флагов (конфиг).
 * Война может завершиться: 75% чанков, центральный чанк, капитуляция, мирный договор,
 * таймаут отсутствия атакующих, истечение активной фазы, отмена.
 * Все Towny-объекты (town) держим как Object, чтобы не тащить зависимость.
 */
public final class War {
    public final UUID id;
    public WarPhase phase;

    public final String attackerTownName;
    public final String defenderTownName;

    /** Ссылки на Towny-объекты (могут устареть, обновляются лениво через TownyBridge). */
    public transient Object attackerTown;
    public transient Object defenderTown;

    public final long preparationEnd;
    public final long activeEnd;

    /** Время, когда последний раз атакующий был в зоне города. */
    public long lastAttackerSeen;

    /** Координаты чанков города-защитника (для подсчёта общего числа и захваченных). */
    public final Map<ChunkCoord, Boolean> chunkStatus = new HashMap<>();

    /** Координаты центрального чанка (homeblock защитника). */
    public ChunkCoord centralChunk;

    /** Сколько флагов уже успешно захвачено в центральном чанке (нужно N по конфигу, п.7 ТЗ). */
    public int centralFlagsCaptured;

    /** Победа атакующих (75% чанков / центральный чанк / капитуляция защитника). */
    public boolean attackerWon;
    /** Завершено из-за отсутствия атакующих. */
    public boolean endedByTimeout;
    /** Город, подписавший капитуляцию (или null) (п.8 ТЗ). */
    public String surrenderedBy;
    /** Война завершена мирным договором (п.9 ТЗ). */
    public boolean peaceTreaty;
    /** Город, предложивший мирный договор (ожидает подписи другой стороной). */
    public String peaceOfferedBy;

    /** Нации, помогающие атакующему городу. */
    public final java.util.Set<String> attackerNations = new java.util.HashSet<>();
    /** Нации, помогающие защищающемуся городу. */
    public final java.util.Set<String> defenderNations = new java.util.HashSet<>();
    /** Нации, отклонившие запрос о помощи (запрос больше не отправить). */
    public final java.util.Set<String> rejectedNations = new java.util.HashSet<>();
    public static class WarHelpRequest {
        public final String townName;
        public final String message;
        public WarHelpRequest(String townName, String message) {
            this.townName = townName;
            this.message = message;
        }
    }

    /** Ожидающие запросы (доставка): Имя Нации -> Время старта (мс) */
    public final java.util.Map<String, Long> pendingHelpRequestsTime = new java.util.HashMap<>();
    /** Ожидающие запросы (доставка): Имя Нации -> Запрос */
    public final java.util.Map<String, java.util.List<WarHelpRequest>> pendingHelpRequests = new java.util.HashMap<>();
    /** Доставленные запросы (ожидают ответа короля): Имя Нации -> Список запросов */
    public final java.util.Map<String, java.util.List<WarHelpRequest>> deliveredHelpRequests = new java.util.HashMap<>();


    /**
     * Оригинальные значения флагов PvP, взрывов и прав чужаков ОБОИХ городов.
     * Сохраняем перед включением войны, чтобы восстановить после её окончания.
     */
    public Boolean defenderOriginalPvp;
    public Boolean defenderOriginalExplosion;
    public Boolean attackerOriginalPvp;
    public Boolean attackerOriginalExplosion;
    public ru.pewars.server.towny.TownyBridge.TownyOutsiderPerms defenderOriginalOutsiderPerms;
    public ru.pewars.server.towny.TownyBridge.TownyOutsiderPerms attackerOriginalOutsiderPerms;

    /** Прогресс возврата чанков защитниками (секунды удержания). */
    public final Map<ChunkCoord, Integer> recaptureProgress = new HashMap<>();

    public War(UUID id, String attackerTownName, String defenderTownName,
               long preparationEnd, long activeEnd) {
        this.id = id;
        this.phase = WarPhase.PREPARATION;
        this.attackerTownName = attackerTownName;
        this.defenderTownName = defenderTownName;
        this.preparationEnd = preparationEnd;
        this.activeEnd = activeEnd;
        this.lastAttackerSeen = System.currentTimeMillis();
    }

    public boolean isActive() {
        return phase != WarPhase.ENDED;
    }

    public boolean isCentralChunk(ChunkCoord coord) {
        return centralChunk != null && centralChunk.equals(coord);
    }

    /** Отметить чанк как захваченный (через флаг). Возвращает true, если это был новый захват. */
    public boolean markCaptured(ChunkCoord coord) {
        if (coord == null) return false;
        recaptureProgress.remove(coord);
        Boolean prev = chunkStatus.put(coord, true);
        return prev == null || !prev;
    }

    /** Отметить чанк как возвращённый защитниками. */
    public boolean markRecaptured(ChunkCoord coord) {
        if (coord == null) return false;
        recaptureProgress.remove(coord);
        Boolean prev = chunkStatus.put(coord, false);
        return Boolean.TRUE.equals(prev);
    }

    public int capturedCount() {
        int count = 0;
        for (Boolean b : chunkStatus.values()) {
            if (Boolean.TRUE.equals(b)) count++;
        }
        return count;
    }

    public int totalChunks() {
        return chunkStatus.size();
    }

    public boolean isCaptured(ChunkCoord coord) {
        return Boolean.TRUE.equals(chunkStatus.get(coord));
    }

    public double capturedPercent() {
        int total = totalChunks();
        if (total == 0) return 0.0;
        return (capturedCount() * 100.0) / total;
    }

    /** Захвачен ли требуемый процент чанков (percent — из конфига, п.2/п.8 ТЗ). */
    public boolean requiredChunksCaptured(int percent) {
        int total = totalChunks();
        if (total == 0) return false;
        return capturedCount() * 100 >= total * percent;
    }

    /** Захвачен ли центральный чанк (нужно flagsRequired флагов, п.7 ТЗ). */
    public boolean centralCaptured(int flagsRequired) {
        return centralFlagsCaptured >= Math.max(1, flagsRequired);
    }
}

package ru.pewars.server.war;

/** Фазы войны по ТЗ: подготовка (1 час) → активная (2 часа) → завершение. */
public enum WarPhase {
    PREPARATION,
    ACTIVE,
    /** Победа атакующих: мэр победителей выбирает исход войны (казна/захват/мэр). */
    DECISION,
    ENDED
}

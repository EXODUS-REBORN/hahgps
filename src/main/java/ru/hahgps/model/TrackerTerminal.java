package ru.hahgps.model;

import java.util.UUID;

/**
 * Терминал iTrak — блок, по нажатию на который игрок получает координаты
 * трекеров рядом и подсветку. Привязка идёт к координатам блока: сам блок в
 * мире обычный (лодестоун), отличает его реестр в БД.
 */
public record TrackerTerminal(int id, String world, int x, int y, int z,
                              UUID ownerUuid, String ownerName, long placedAt) {

    public String key() {
        return key(world, x, y, z);
    }

    public static String key(String world, int x, int y, int z) {
        return (world == null ? "?" : world) + ":" + x + ":" + y + ":" + z;
    }

    public String getDisplayName() {
        return "iTrak";
    }
}

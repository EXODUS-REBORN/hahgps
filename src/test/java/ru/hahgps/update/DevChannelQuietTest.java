package ru.hahgps.update;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Плановая проверка канала пререлизов не должна засорять консоль строкой «Пререлизов новее нет».
 *
 * <p>Раньше «новее нет» печаталось каждые две минуты по таймеру; теперь этот итог в консоль не
 * пишется никогда, а остальные итоги — только при смене (появился пререлиз, ошибка, ключ и т.п.).</p>
 */
class DevChannelQuietTest {

    @Test
    void latestIsNeverLogged() {
        assertFalse(DevChannel.logsQuiet("latest", ""));
        assertFalse(DevChannel.logsQuiet("latest", "available"));
        assertFalse(DevChannel.logsQuiet("latest", "failed"));
    }

    @Test
    void changedOutcomeIsLoggedOnce() {
        assertTrue(DevChannel.logsQuiet("available", "latest"));
        assertTrue(DevChannel.logsQuiet("failed", "latest"));
        assertTrue(DevChannel.logsQuiet("available", ""));
    }

    @Test
    void repeatedOutcomeIsSilent() {
        assertFalse(DevChannel.logsQuiet("available", "available"));
        assertFalse(DevChannel.logsQuiet("failed", "failed"));
    }
}

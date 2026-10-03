package ru.hahgps.update;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Список групп оповещения: значения по умолчанию (41, 42, 103, 555),
 * чистка при чтении конфига и откат к значениям по умолчанию на пустом списке.
 */
class UpdateAnnouncerTest {

    @Test
    void defaultGroupsAre41_42_103_555() {
        UpdateAnnouncer announcer = new UpdateAnnouncer(null);
        assertEquals(List.of("41", "42", "103", "555"), announcer.groups());
    }

    @Test
    void groupsFromConfigAreTrimmed() {
        UpdateAnnouncer announcer = new UpdateAnnouncer(null);
        announcer.setGroups(List.of(" 7 ", "", "  42  "));
        assertEquals(List.of("7", "42"), announcer.groups());
    }

    @Test
    void emptyGroupsFallBackToDefaults() {
        UpdateAnnouncer announcer = new UpdateAnnouncer(null);
        announcer.setGroups(List.of());
        assertEquals(List.of("41", "42", "103", "555"), announcer.groups());
        announcer.setGroups(null);
        assertEquals(List.of("41", "42", "103", "555"), announcer.groups());
    }

    @Test
    void groupsReturnsCopy() {
        UpdateAnnouncer announcer = new UpdateAnnouncer(null);
        announcer.groups().add("hacked");
        assertEquals(List.of("41", "42", "103", "555"), announcer.groups());
    }
}

package ru.hahgps.update;

import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;


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

    @Test
    void groupNamesAreComparedIgnoringCaseAndSpaces() {
        assertEquals(Set.of("41", "42"), UpdateAnnouncer.normalize(List.of(" 41 ", "42")));
        assertEquals(Set.of("103", "555"), UpdateAnnouncer.normalize(List.of("103", "555", "")));
    }

    @Test
    void playerOfPriorityGroupGetsUpdateMessages() {
        UpdateAnnouncer announcer = new UpdateAnnouncer(null);
        assertTrue(announcer.isPrivileged(fakePlayer(false, "group.555", true)));
    }

    @Test
    void operatorWithoutPriorityGroupDoesNotGetUpdateMessages() {
        UpdateAnnouncer announcer = new UpdateAnnouncer(null);
        assertFalse(announcer.isPrivileged(fakePlayer(true, "*", true)));
    }

    @Test
    void operatorWithAdminPermissionDoesNotGetUpdateMessages() {
        UpdateAnnouncer announcer = new UpdateAnnouncer(null);
        assertFalse(announcer.isPrivileged(
                fakePlayer(true, "gps.admin", true, "gps.reload", true)));
    }

    @Test
    void revokedGroupPermissionIsNotEnough() {
        UpdateAnnouncer announcer = new UpdateAnnouncer(null);
        assertFalse(announcer.isPrivileged(fakePlayer(false, "group.41", false)));
    }

    @Test
    void playerWithoutAnyPriorityGroupIsSkipped() {
        UpdateAnnouncer announcer = new UpdateAnnouncer(null);
        assertFalse(announcer.isPrivileged(
                fakePlayer(false, "gps.use", true, "group.7", true)));
    }

    @Test
    void operatorFromPriorityGroupStillGetsUpdateMessages() {
        UpdateAnnouncer announcer = new UpdateAnnouncer(null);
        assertTrue(announcer.isPrivileged(fakePlayer(true, "group.42", true)));
    }

    @Test
    void nullPlayerIsNeverPrivileged() {
        UpdateAnnouncer announcer = new UpdateAnnouncer(null);
        assertFalse(announcer.isPrivileged(null));
    }

    @Test
    void defaultPlayersContainHakerOne() {
        UpdateAnnouncer announcer = new UpdateAnnouncer(null);
        assertEquals(List.of("haker_one"), announcer.players());
    }

    @Test
    void namedPlayerGetsMessagesWithoutAnyGroup() {
        UpdateAnnouncer announcer = new UpdateAnnouncer(null);
        assertTrue(announcer.isPrivileged(fakePlayer("haker_one", false)));
        assertTrue(announcer.isPrivileged(fakePlayer("HAKER_ONE", false)));
    }

    @Test
    void playerListIsTrimmedAndFallsBackToDefault() {
        UpdateAnnouncer announcer = new UpdateAnnouncer(null);
        announcer.setPlayers(List.of(" ursik ", ""));
        assertEquals(List.of("ursik"), announcer.players());
        announcer.setPlayers(List.of());
        assertEquals(List.of("haker_one"), announcer.players());
    }

    @Test
    void playerListReturnsCopy() {
        UpdateAnnouncer announcer = new UpdateAnnouncer(null);
        announcer.players().add("hacked");
        assertEquals(List.of("haker_one"), announcer.players());
    }

    /** Игрок-заглушка: оператор проверяется по правам, а не по звёздочке. */
    private static Player fakePlayer(boolean op, Object... pairs) {
        return fakePlayer("TestPlayer", op, pairs);
    }

    private static Player fakePlayer(String name, boolean op, Object... pairs) {
        UUID uuid = UUID.nameUUIDFromBytes(("gps-test-" + name + "-" + op).getBytes());
        Set<PermissionAttachmentInfo> permissions = new LinkedHashSet<>();

        Player player = (Player) Proxy.newProxyInstance(
                UpdateAnnouncerTest.class.getClassLoader(),
                new Class<?>[]{Player.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "isOp":
                            return op;
                        case "getUniqueId":
                            return uuid;
                        case "getName":
                            return name;
                        case "getEffectivePermissions":
                            return permissions;
                        case "hasPermission":
                            if (args != null && args.length > 1 && Boolean.TRUE.equals(args[1])) {
                                return op;
                            }
                            String node = args == null || args.length == 0 ? null : String.valueOf(args[0]);
                            for (PermissionAttachmentInfo info : permissions) {
                                if (info.getPermission().equals(node)) return info.getValue();
                            }
                            return false;
                        case "toString":
                            return "FakePlayer";
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "equals":
                            return proxy == args[0];
                        default:
                            return null;
                    }
                });

        for (int i = 0; i + 1 < pairs.length; i += 2) {
            permissions.add(new PermissionAttachmentInfo(player, String.valueOf(pairs[i]), null,
                    Boolean.parseBoolean(String.valueOf(pairs[i + 1]))));
        }
        return player;
    }
}
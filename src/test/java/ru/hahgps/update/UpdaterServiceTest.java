package ru.hahgps.update;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;


class UpdaterServiceTest {

    @Test
    void releaseDateIsoBecomesDdMmYyyy() {
        assertEquals("12.05.2025", UpdaterService.formatReleaseDate("2025-05-12T10:30:00Z"));
        assertEquals("01.01.2026", UpdaterService.formatReleaseDate("2026-01-01T00:00:00Z"));
    }

    @Test
    void releaseDateUnparseablePassesThrough() {
        assertEquals("12.05.2025", UpdaterService.formatReleaseDate("12.05.2025"));

        assertEquals("2025-05-12 10:30", UpdaterService.formatReleaseDate("2025-05-12 10:30"));
        assertEquals("", UpdaterService.formatReleaseDate(""));
        assertEquals("", UpdaterService.formatReleaseDate(null));
    }

    @Test
    void notesAreTruncatedWithEllipsis() {
        String out = UpdaterService.shortNotes("a".repeat(600));
        assertEquals(501, out.length());
        assertTrue(out.endsWith("…"));
    }

    @Test
    void shortNotesPassThroughAsIs() {
        assertEquals("hello", UpdaterService.shortNotes("  hello  "));
        assertEquals("", UpdaterService.shortNotes("   "));
        assertEquals("", UpdaterService.shortNotes(null));
    }

    @Test
    void versionComparisonIgnoresLeadingV() {
        assertTrue(UpdaterService.isNewer("1.0.10", "1.0.9"));
        assertFalse(UpdaterService.isNewer("1.0.9", "1.0.10"));
        assertFalse(UpdaterService.isNewer("1.0.4", "1.0.4"));
        assertTrue(UpdaterService.isNewer("v1.0.5", "1.0.4"));
        assertEquals("1.0.5", UpdaterService.normalizeVersion("v1.0.5"));
    }

    @Test
    void tokenFileCannotEscapePluginFolder() {
        java.io.File base = new java.io.File(System.getProperty("java.io.tmpdir"), "hahgps-test");

        assertEquals("github-token.txt",
                UpdaterService.tokenPath(base, "github-token.txt").getFileName().toString());
        assertEquals("key.txt",
                UpdaterService.tokenPath(base, "keys/key.txt").getFileName().toString(),
                "вложенный путь внутри папки плагина разрешён");
        assertEquals(null, UpdaterService.tokenPath(base, "../../server.properties"),
                "выход из папки плагина запрещён");
        assertEquals(null, UpdaterService.tokenPath(base, "../secrets/token.txt"),
                "выход из папки плагина запрещён");
        assertEquals(null, UpdaterService.tokenPath(base, "   "));
        assertEquals(null, UpdaterService.tokenPath(null, "github-token.txt"));
    }
    @Test
    void screenshotsAndLinksAreRemovedFromChatNotes() {
        String body = "Что нового: каталог и 3D-трекеры.\n\n### Скриншоты\n\n"
                + "![Терминал](https://github.com/x/y/releases/download/v1.0/z.png)\n"
                + "[Трекеры](https://github.com/x/y/releases/download/v1.0/t.png)";
        String out = UpdaterService.shortNotes(body);
        assertEquals("Что нового: каталог и 3D-трекеры.", out);
    }

    @Test
    void markdownImagesBeforeHeadingAreRemoved() {
        String body = "Релиз ![скрин](https://example.com/a.png) готов.";
        assertEquals("Релиз готов.", UpdaterService.shortNotes(body));
    }

    @Test
    void htmlScreenshotSectionIsNotSentToChat() {
        String body = "Коротко о релизе.\n\n<h3>Скриншоты</h3>\n"
                + "<p><img src=\"https://example.com/a.png\" width=\"720\"></p>";
        assertEquals("Коротко о релизе.", UpdaterService.shortNotes(body));
    }

}

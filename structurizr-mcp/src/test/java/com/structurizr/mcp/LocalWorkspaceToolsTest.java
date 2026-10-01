package com.structurizr.mcp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class LocalWorkspaceToolsTest {
    @TempDir Path root;
    private static final String DSL = "workspace \"Example\" {\n model {\n user = person \"User\"\n app = softwareSystem \"App\"\n user -> app \"Uses\"\n }\n}";

    @Test void createReadUpdateAndBackup() throws Exception {
        var tools = new LocalWorkspaceTools(root);
        var created = tools.createLocalWorkspace(1, DSL);
        tools.createLocalWorkspace(2, DSL);
        assertEquals(java.util.List.of(1L, 2L), tools.listLocalWorkspaces());
        assertEquals(DSL, tools.readLocalWorkspace(1).get("dsl"));
        var updated = tools.updateLocalWorkspace(1, DSL.replace("Example", "Changed"), (String) created.get("sha256"));
        assertNotEquals(created.get("sha256"), updated.get("sha256"));
        try (var files = Files.list(root.resolve("1/.backups"))) {
            assertEquals(DSL, Files.readString(files.findFirst().orElseThrow()));
        }
        assertEquals(DSL, tools.readLocalWorkspace(2).get("dsl"));
    }

    @Test void invalidOrStaleEditsDoNotOverwrite() throws Exception {
        var tools = new LocalWorkspaceTools(root);
        var created = tools.createLocalWorkspace(1, DSL);
        assertThrows(Exception.class, () -> tools.createLocalWorkspace(1, DSL));
        assertThrows(Exception.class, () -> tools.createLocalWorkspace(2, "not valid DSL"));
        assertThrows(Exception.class, () -> tools.createLocalWorkspace(2, ""));
        assertFalse(Files.exists(root.resolve("2")));
        assertThrows(Exception.class, () -> tools.updateLocalWorkspace(1, "not valid DSL", (String)created.get("sha256")));
        assertThrows(IllegalStateException.class, () -> tools.updateLocalWorkspace(1, DSL, "stale"));
        assertEquals(DSL, tools.readLocalWorkspace(1).get("dsl"));
        assertThrows(IllegalArgumentException.class, () -> tools.readLocalWorkspace(-1));
    }

    @Test void remoteIncludesAndScriptsAreRejected() throws Exception {
        var tools = new LocalWorkspaceTools(root);
        assertThrows(Exception.class, () -> tools.createLocalWorkspace(1, "workspace {\n!include https://example.com/a.dsl\n}"));
        assertThrows(Exception.class, () -> tools.createLocalWorkspace(1, "workspace {\n!script groovy {\nprintln 'test'\n}\n}"));
        assertFalse(Files.exists(root.resolve("1")));
    }
}

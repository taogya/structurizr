package com.structurizr.mcp;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Local DSL authoring. No remote API, cloud account, or LLM dependency. */
@Service
@ConditionalOnProperty(name = "structurizr.launcher.workspaces")
public class LocalWorkspaceTools extends AbstractTools {
    private final Path root;

    public LocalWorkspaceTools() throws IOException {
        this(Path.of(System.getProperty("structurizr.launcher.workspaces")));
    }

    LocalWorkspaceTools(Path root) throws IOException {
        Files.createDirectories(root);
        this.root = root.toRealPath();
    }

    private Path directory(long id) throws IOException {
        if (id < 1) throw new IllegalArgumentException("workspaceId must be positive");
        Path dir = root.resolve(Long.toString(id));
        if (Files.isSymbolicLink(dir) || (Files.exists(dir) && !dir.toRealPath().startsWith(root))) {
            throw new IllegalArgumentException("Symbolic links are not supported");
        }
        return dir;
    }

    private Path file(long id) throws IOException {
        Path file = directory(id).resolve("workspace.dsl");
        if (Files.isSymbolicLink(file)) throw new IllegalArgumentException("Symbolic links are not supported");
        return file;
    }

    private String hash(String dsl) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(dsl.getBytes(StandardCharsets.UTF_8)));
    }

    private Map<String, Object> result(long id, String dsl) throws Exception {
        return Map.of("workspaceId", id, "dsl", dsl, "sha256", hash(dsl), "path", file(id).toString());
    }

    @McpTool(name = "listLocalWorkspaces", description = "List local numeric workspace IDs. Use readLocalWorkspace to read DSL before editing.")
    public List<Long> listLocalWorkspaces() throws IOException {
        try (var paths = Files.list(root)) {
            return paths.filter(p -> !Files.isSymbolicLink(p) && Files.isDirectory(p))
                    .map(p -> p.getFileName().toString()).filter(n -> n.matches("[1-9][0-9]{0,18}"))
                    .flatMap(n -> {
                        try { return java.util.stream.Stream.of(Long.parseLong(n)); }
                        catch (NumberFormatException e) { return java.util.stream.Stream.<Long>empty(); }
                    }).sorted().toList();
        }
    }

    @McpTool(name = "readLocalWorkspace", description = "Read local DSL and its SHA-256 revision. Pass that revision when updating.")
    public synchronized Map<String, Object> readLocalWorkspace(
            @McpToolParam(description = "Positive numeric workspace ID", required = true) long workspaceId) throws Exception {
        return result(workspaceId, Files.readString(file(workspaceId), StandardCharsets.UTF_8));
    }

    @McpTool(name = "createLocalWorkspace", description = "Validate and save a NEW self-contained local DSL workspace. Existing IDs are never overwritten. Refresh the local UI to see the new workspace.")
    public synchronized Map<String, Object> createLocalWorkspace(
            @McpToolParam(description = "New positive numeric workspace ID", required = true) long workspaceId,
            @McpToolParam(description = "Complete Structurizr DSL, with inline styles and no external resources", required = true) String dsl) throws Exception {
        validateForSave(dsl);
        Path dir = directory(workspaceId);
        Files.createDirectory(dir); // atomic collision detection; never reuse an existing workspace
        try {
            write(file(workspaceId), dsl);
        } catch (Exception e) {
            Files.deleteIfExists(dir);
            throw e;
        }
        return result(workspaceId, dsl);
    }

    @McpTool(name = "updateLocalWorkspace", description = "Validate and update local DSL using the SHA-256 revision from readLocalWorkspace. Backs up the previous DSL; preserves workspace.json layout. On conflict, re-read and merge.")
    public synchronized Map<String, Object> updateLocalWorkspace(
            @McpToolParam(description = "Positive numeric workspace ID", required = true) long workspaceId,
            @McpToolParam(description = "Complete replacement DSL", required = true) String dsl,
            @McpToolParam(description = "SHA-256 from readLocalWorkspace", required = true) String expectedSha256) throws Exception {
        validateForSave(dsl);
        Path file = file(workspaceId);
        String previous = Files.readString(file, StandardCharsets.UTF_8);
        if (!hash(previous).equals(expectedSha256)) throw new IllegalStateException("Revision conflict: re-read workspace before updating");
        Path backups = directory(workspaceId).resolve(".backups");
        if (Files.isSymbolicLink(backups)) throw new IllegalArgumentException("Symbolic links are not supported");
        Files.createDirectories(backups);
        Files.writeString(backups.resolve(UUID.randomUUID() + ".dsl"), previous, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        write(file, dsl);
        return result(workspaceId, dsl);
    }

    private void validateForSave(String dsl) throws Exception {
        if (dsl == null || dsl.length() > 5_000_000) throw new IllegalArgumentException("DSL is required and limited to 5 MB of characters");
        var parser = createStructurizrDslParser();
        parser.parse(dsl);
        if (parser.getWorkspace() == null) throw new IllegalArgumentException("DSL must define a workspace");
    }

    private void write(Path target, String dsl) throws IOException {
        Path temp = Files.createTempFile(target.getParent(), ".workspace-", ".tmp");
        try {
            Files.writeString(temp, dsl, StandardCharsets.UTF_8);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}

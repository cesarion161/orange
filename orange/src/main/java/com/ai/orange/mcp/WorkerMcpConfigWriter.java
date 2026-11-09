package com.ai.orange.mcp;

import com.ai.orange.json.Json;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.springframework.stereotype.Component;

/**
 * Writes the {@code --mcp-config} file for one run: a single HTTP server named {@code orange},
 * authenticated with the run's token. The file holds the raw token, so it is owner-only and the
 * runner deletes it when the run ends.
 */
@Component
public class WorkerMcpConfigWriter {

    public static final String SERVER_NAME = "orange";

    private final WorkerMcpUrl url;

    public WorkerMcpConfigWriter(WorkerMcpUrl url) {
        this.url = url;
    }

    public Path write(Path runDir, String token) throws IOException {
        Files.createDirectories(runDir);
        ObjectNode root = Json.mapper().createObjectNode();
        ObjectNode server = root.putObject("mcpServers").putObject(SERVER_NAME);
        server.put("type", "http");
        server.put("url", url.url());
        server.putObject("headers").put("Authorization", "Bearer " + token);

        Path file = runDir.resolve("mcp.json");
        Files.deleteIfExists(file);
        try {
            Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } catch (UnsupportedOperationException e) {
            Files.createFile(file);
        }
        Files.writeString(file, root.toString());
        return file;
    }
}

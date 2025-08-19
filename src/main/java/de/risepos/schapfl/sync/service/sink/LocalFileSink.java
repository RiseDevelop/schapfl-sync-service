package de.risepos.schapfl.sync.service.sink;

import jakarta.enterprise.context.ApplicationScoped;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.OutputStream;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;

@Slf4j
@ApplicationScoped
@LocalSink
public class LocalFileSink implements FileSink {
    @ConfigProperty(name = "local.outputDir")
    String outputDir;

    @Override
    public void writeAtomic(String filename, byte[] content) throws Exception {
        if (outputDir == null || outputDir.isBlank()) {
            throw new IllegalStateException("local.outputDir is not configured");
        }
        Path dir = Paths.get(outputDir);
        Files.createDirectories(dir); // ensure exists

        Path finalPath = dir.resolve(filename);
        Path tmpPath = dir.resolve(filename + ".tmp");

        // Write temp file
        try (OutputStream out = Files.newOutputStream(tmpPath,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            out.write(content);
        }

        // Try atomic move; fallback if FS doesn't support it
        try {
            Files.move(tmpPath, finalPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(tmpPath, finalPath, StandardCopyOption.REPLACE_EXISTING);
        }

        // Optional: set POSIX perms if supported (Linux host)
        try {
            Files.setPosixFilePermissions(finalPath,
                    PosixFilePermissions.fromString("rw-rw-r--"));
        } catch (UnsupportedOperationException ignore) {}

        log.info("📄 Local write: {}", finalPath);
    }
}

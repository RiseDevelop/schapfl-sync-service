package de.risepos.schapfl.sync.service.sales;

import jakarta.enterprise.context.ApplicationScoped;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

@ApplicationScoped
@Slf4j
public class SalesFileProcessor {
    private static final String SALES_PREFIX = "GES";
    private static final String SALES_SUFFIX = ".schapfl";
    private static final String PROCESSING_DIR = ".processing";

    public int processAvailableFiles(Path inputDir,
                                     Path processedDir,
                                     long stabilityMillis,
                                     FilePublisher publisher) throws Exception {
        if (inputDir == null) throw new IllegalArgumentException("inputDir must not be null");
        if (processedDir == null) throw new IllegalArgumentException("processedDir must not be null");
        if (publisher == null) throw new IllegalArgumentException("publisher must not be null");
        if (!Files.isDirectory(inputDir)) return 0;

        List<Path> files;
        try (Stream<Path> stream = Files.list(inputDir)) {
            files = stream
                    .filter(Files::isRegularFile)
                    .filter(this::isEligibleSalesFile)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        }

        int processed = 0;
        for (Path file : files) {
            if (!isStable(file, stabilityMillis)) {
                continue;
            }

            String originalName = file.getFileName().toString();
            Path claimedFile = claimForPublishing(inputDir, file);

            try {
                byte[] payload = Files.readAllBytes(claimedFile);
                publisher.publish(originalName, payload);
                finalizeProcessedFile(claimedFile, processedDir.resolve(originalName));
                processed++;
            } catch (Exception e) {
                if (Files.exists(claimedFile)) {
                    log.warn("Sales file {} was claimed into {} and will not be auto-republished. Manual recovery may be required. Cause: {}",
                            originalName, claimedFile, e.toString());
                    continue;
                }
                throw e;
            }
        }

        return processed;
    }

    private boolean isEligibleSalesFile(Path path) {
        String name = path.getFileName().toString();
        return !name.startsWith(".")
                && name.startsWith(SALES_PREFIX)
                && name.toLowerCase(Locale.ROOT).endsWith(SALES_SUFFIX);
    }

    private boolean isStable(Path file, long stabilityMillis) throws IOException {
        if (stabilityMillis <= 0) {
            return true;
        }
        long ageMillis = System.currentTimeMillis() - Files.getLastModifiedTime(file).toMillis();
        return ageMillis >= stabilityMillis;
    }

    private Path claimForPublishing(Path inputDir, Path source) throws IOException {
        Path processingDir = inputDir.resolve(PROCESSING_DIR);
        Files.createDirectories(processingDir);
        Path claimedTarget = uniqueTarget(processingDir.resolve(source.getFileName()));
        moveFile(source, claimedTarget);
        return claimedTarget;
    }

    private void finalizeProcessedFile(Path claimedFile, Path desiredTarget) throws IOException {
        Files.createDirectories(desiredTarget.getParent());
        moveFile(claimedFile, uniqueTarget(desiredTarget));
    }

    void moveFile(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private Path uniqueTarget(Path desiredTarget) {
        if (!Files.exists(desiredTarget)) {
            return desiredTarget;
        }

        String filename = desiredTarget.getFileName().toString();
        int dot = filename.lastIndexOf('.');
        String base = dot >= 0 ? filename.substring(0, dot) : filename;
        String ext = dot >= 0 ? filename.substring(dot) : "";

        int counter = 1;
        while (true) {
            Path candidate = desiredTarget.resolveSibling(base + "-" + counter + ext);
            if (!Files.exists(candidate)) {
                return candidate;
            }
            counter++;
        }
    }

    @FunctionalInterface
    public interface FilePublisher {
        void publish(String filename, byte[] payload) throws Exception;
    }
}

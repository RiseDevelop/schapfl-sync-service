package de.risepos.schapfl.sync.service.sales;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SalesFileProcessorTest {
    private final SalesFileProcessor processor = new SalesFileProcessor();

    @TempDir
    Path tempDir;

    @Test
    void publishesStableFilesAndMovesThemToProcessedDir() throws Exception {
        Path inputDir = Files.createDirectories(tempDir.resolve("input"));
        Path processedDir = tempDir.resolve("processed");
        Path salesFile = inputDir.resolve("GES_1_2126695_20260314105137.schapfl");
        Files.writeString(salesFile, "{\"amount\":12.34}", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(salesFile, FileTime.from(Instant.now().minusSeconds(10)));

        List<String> publishedNames = new ArrayList<>();
        List<String> payloads = new ArrayList<>();

        int processed = processor.processAvailableFiles(inputDir, processedDir, 1000, (filename, payload) -> {
            publishedNames.add(filename);
            payloads.add(new String(payload, StandardCharsets.UTF_8));
        });

        assertEquals(1, processed);
        assertEquals(List.of("GES_1_2126695_20260314105137.schapfl"), publishedNames);
        assertEquals(List.of("{\"amount\":12.34}"), payloads);
        assertFalse(Files.exists(salesFile));
        assertTrue(Files.exists(processedDir.resolve("GES_1_2126695_20260314105137.schapfl")));
    }

    @Test
    void ignoresFilesOutsideGesSchapflPattern() throws Exception {
        Path inputDir = Files.createDirectories(tempDir.resolve("input"));
        Path processedDir = tempDir.resolve("processed");
        Path wrongPrefix = inputDir.resolve("ART_1_2126695_20260314105137.schapfl");
        Path wrongSuffix = inputDir.resolve("GES_1_2126695_20260314105137.txt");
        Files.writeString(wrongPrefix, "ignored", StandardCharsets.UTF_8);
        Files.writeString(wrongSuffix, "ignored", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(wrongPrefix, FileTime.from(Instant.now().minusSeconds(10)));
        Files.setLastModifiedTime(wrongSuffix, FileTime.from(Instant.now().minusSeconds(10)));

        List<String> publishedNames = new ArrayList<>();
        int processed = processor.processAvailableFiles(inputDir, processedDir, 0, (filename, payload) -> publishedNames.add(filename));

        assertEquals(0, processed);
        assertTrue(publishedNames.isEmpty());
        assertTrue(Files.exists(wrongPrefix));
        assertTrue(Files.exists(wrongSuffix));
        assertFalse(Files.exists(processedDir));
    }

    @Test
    void leavesFileClaimedAndDoesNotRepublishWhenArchiveMoveFails() throws Exception {
        Path inputDir = Files.createDirectories(tempDir.resolve("input"));
        Path processedDir = tempDir.resolve("processed");
        Path salesFile = inputDir.resolve("GES_1_2126695_20260314105137.schapfl");
        Files.writeString(salesFile, "payload", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(salesFile, FileTime.from(Instant.now().minusSeconds(10)));

        AtomicInteger publishCount = new AtomicInteger();
        SalesFileProcessor archiveFailingProcessor = new SalesFileProcessor() {
            @Override
            void moveFile(Path source, Path target) throws java.io.IOException {
                if (target.normalize().startsWith(processedDir.normalize())) {
                    throw new AccessDeniedException(target.toString());
                }
                super.moveFile(source, target);
            }
        };

        int firstRun = archiveFailingProcessor.processAvailableFiles(inputDir, processedDir, 0, (filename, payload) -> publishCount.incrementAndGet());
        int secondRun = archiveFailingProcessor.processAvailableFiles(inputDir, processedDir, 0, (filename, payload) -> publishCount.incrementAndGet());

        assertEquals(0, firstRun);
        assertEquals(0, secondRun);
        assertEquals(1, publishCount.get());
        assertFalse(Files.exists(salesFile));
        assertTrue(Files.exists(inputDir.resolve(".processing").resolve("GES_1_2126695_20260314105137.schapfl")));
    }

    @Test
    void leavesFileClaimedAndDoesNotRepublishWhenPublishFails() throws Exception {
        Path inputDir = Files.createDirectories(tempDir.resolve("input"));
        Path processedDir = tempDir.resolve("processed");
        Path salesFile = inputDir.resolve("GES_1_2126695_20260314105137.schapfl");
        Files.writeString(salesFile, "boom", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(salesFile, FileTime.from(Instant.now().minusSeconds(10)));

        AtomicInteger publishAttempts = new AtomicInteger();

        int firstRun = processor.processAvailableFiles(inputDir, processedDir, 0, (filename, payload) -> {
            publishAttempts.incrementAndGet();
            throw new RuntimeException("publish failed");
        });
        int secondRun = processor.processAvailableFiles(inputDir, processedDir, 0, (filename, payload) -> publishAttempts.incrementAndGet());

        assertEquals(0, firstRun);
        assertEquals(0, secondRun);
        assertEquals(1, publishAttempts.get());
        assertFalse(Files.exists(salesFile));
        assertTrue(Files.exists(inputDir.resolve(".processing").resolve("GES_1_2126695_20260314105137.schapfl")));
        assertFalse(Files.exists(processedDir));
    }

    @Test
    void skipsFilesThatAreNotStableYet() throws Exception {
        Path inputDir = Files.createDirectories(tempDir.resolve("input"));
        Path processedDir = tempDir.resolve("processed");
        Path salesFile = inputDir.resolve("GES_1_2126695_20260314105137.schapfl");
        Files.writeString(salesFile, "fresh", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(salesFile, FileTime.from(Instant.now()));

        List<String> publishedNames = new ArrayList<>();
        int processed = processor.processAvailableFiles(inputDir, processedDir, 60_000, (filename, payload) -> publishedNames.add(filename));

        assertEquals(0, processed);
        assertTrue(publishedNames.isEmpty());
        assertTrue(Files.exists(salesFile));
    }
}

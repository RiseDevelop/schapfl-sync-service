package de.risepos.schapfl.sync.service.sales;

import de.risepos.schapfl.sync.service.mqtt.MqttClientService;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Startup
@ApplicationScoped
@Slf4j
public class LocalSalesPublisher {
    @Inject MqttClientService mqttClientService;
    @Inject SalesFileProcessor salesFileProcessor;

    @ConfigProperty(name = "local.inputDir")
    Optional<String> inputDir;

    @ConfigProperty(name = "local.outputDir")
    Optional<String> outputDir;

    @ConfigProperty(name = "mqtt.sales.topic")
    Optional<String> salesTopic;

    @ConfigProperty(name = "sales.poll.interval.millis", defaultValue = "5000")
    long pollIntervalMillis;

    @ConfigProperty(name = "sales.file.stability.millis", defaultValue = "2000")
    long stabilityMillis;

    @ConfigProperty(name = "sales.processedDir")
    Optional<String> processedDir;

    private ScheduledExecutorService executor;
    private Path resolvedInputDir;
    private Path resolvedProcessedDir;
    private String resolvedSalesTopic;

    @PostConstruct
    public void start() {
        String inputDirValue = inputDir.filter(s -> !s.isBlank()).map(String::trim).orElse(null);
        String salesTopicValue = salesTopic.filter(s -> !s.isBlank()).map(String::trim).orElse(null);
        if (inputDirValue == null || salesTopicValue == null) {
            log.info("Sales publisher disabled (local.inputDir or mqtt.sales.topic not configured)");
            return;
        }
        if (pollIntervalMillis <= 0) {
            log.error("Sales publisher disabled: sales.poll.interval.millis must be > 0");
            return;
        }
        if (stabilityMillis < 0) {
            log.error("Sales publisher disabled: sales.file.stability.millis must be >= 0");
            return;
        }

        resolvedInputDir = Paths.get(inputDirValue).normalize();
        Path resolvedOutputDir = outputDir.filter(s -> !s.isBlank())
                .map(String::trim)
                .map(Paths::get)
                .map(Path::normalize)
                .orElse(null);

        if (resolvedInputDir.equals(resolvedOutputDir)) {
            log.error("Sales publisher disabled: local.inputDir must differ from local.outputDir to avoid publish loops");
            return;
        }

        resolvedProcessedDir = processedDir.filter(s -> !s.isBlank())
                .map(String::trim)
                .map(Paths::get)
                .map(Path::normalize)
                .orElse(resolvedInputDir.resolve("processed").normalize());

        if (resolvedProcessedDir.equals(resolvedInputDir)) {
            log.error("Sales publisher disabled: sales.processedDir must differ from local.inputDir");
            return;
        }

        try {
            Files.createDirectories(resolvedInputDir);
            Files.createDirectories(resolvedProcessedDir);
        } catch (Exception e) {
            log.error("❌ Sales publisher disabled: failed to initialize directories", e);
            return;
        }

        resolvedSalesTopic = salesTopicValue;
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "local-sales-publisher");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleWithFixedDelay(this::pollSafely, pollIntervalMillis, pollIntervalMillis, TimeUnit.MILLISECONDS);

        log.info("📤 Sales publisher watching {} -> {} (processedDir={}, poll={}ms, stability={}ms)",
                resolvedInputDir, resolvedSalesTopic, resolvedProcessedDir, pollIntervalMillis, stabilityMillis);
    }

    @PreDestroy
    public void stop() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    private void pollSafely() {
        try {
            int processed = salesFileProcessor.processAvailableFiles(
                    resolvedInputDir,
                    resolvedProcessedDir,
                    stabilityMillis,
                    (filename, payload) -> {
                        mqttClientService.publish(resolvedSalesTopic, payload);
                        log.info("📨 Published sales file {} to topic {}", filename, resolvedSalesTopic);
                    }
            );

            if (processed > 0) {
                log.info("✅ Sales publish cycle completed: {} file(s) processed", processed);
            }
        } catch (Exception e) {
            log.error("❌ Sales publish cycle failed", e);
        }
    }
}


package de.risepos.schapfl.sync.service.mqtt;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import de.risepos.schapfl.sync.service.dto.ArticlePosDto;
import de.risepos.schapfl.sync.service.schapfl.SchapflBuilder;
import de.risepos.schapfl.sync.service.sink.FileSink;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.paho.mqttv5.common.MqttMessage;

import java.nio.charset.StandardCharsets;
import java.util.List;

@Startup
@ApplicationScoped
@Slf4j
public class Consumer {
    @Inject SchapflBuilder schapfl;
    @Inject FileSink fileSink;
    @Inject MqttClientService mqttClientService;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @ConfigProperty(name = "mqtt.topic")
    String topic;

    @PostConstruct
    public void subscribe() {
        mqttClientService.subscribe(topic, this::handleMessage);
    }

    private void handleMessage(String topic, MqttMessage message) {
        String payload = new String(message.getPayload(), StandardCharsets.UTF_8);
        log.info("📩 MQTT message on [{}] ({} bytes, qos={}): {}", topic, message.getPayload().length, message.getQos(), truncateForLog(payload));

        try {
            List<ArticlePosDto> list;
            if (payload.trim().startsWith("[")) {
                list = OBJECT_MAPPER.readValue(payload, new TypeReference<List<ArticlePosDto>>() {});
            } else {
                list = List.of(OBJECT_MAPPER.readValue(payload, ArticlePosDto.class));
            }

            var now = java.time.LocalDateTime.now();
            var batch = schapfl.buildBatch(list, now);
            fileSink.writeAtomic(batch.artName(), batch.artContent());
            batch.mehName().ifPresent(name -> {
                try { fileSink.writeAtomic(name, batch.mehContent().orElseThrow()); }
                catch (Exception e) { log.error("❌ MEH write failed", e); }
            });
        } catch (Exception e) {
            log.error("❌ Payload handing failed", e);
        }
    }

    private static String truncateForLog(String s) {
        if (s == null) return "null";
        return (s.length() > 300) ? (s.substring(0, 300) + " …") : s;
    }
}

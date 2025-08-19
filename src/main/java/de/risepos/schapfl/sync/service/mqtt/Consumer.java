package de.risepos.schapfl.sync.service.mqtt;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import de.risepos.schapfl.sync.service.dto.ArticlePosDto;
import de.risepos.schapfl.sync.service.schapfl.SchapflBuilder;
import de.risepos.schapfl.sync.service.sftp.SftpServiceJsch;
import de.risepos.schapfl.sync.service.sink.FileSink;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.paho.mqttv5.client.*;
import org.eclipse.paho.mqttv5.client.persist.MemoryPersistence;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.packet.MqttProperties;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@Startup
@ApplicationScoped
@Slf4j
public class Consumer {
    @Inject
    SchapflBuilder schapfl;
    @Inject
    FileSink fileSink;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @ConfigProperty(name = "mqtt.broker")
    String broker;

    @ConfigProperty(name = "mqtt.client")
    String clientIdCfg;

    @ConfigProperty(name = "mqtt.username")
    String username;

    @ConfigProperty(name = "mqtt.password")
    String password;

    @ConfigProperty(name = "mqtt.topic")
    String topic;

    @ConfigProperty(name = "mqtt.qos")
    int qos;

    private MqttAsyncClient client;
    private final AtomicBoolean connected = new AtomicBoolean(false);

    @PostConstruct
    public void connect() {
        try {
            // Use in-memory persistence instead of file persistence
            client = new MqttAsyncClient(broker, clientIdCfg, new MemoryPersistence());

            MqttConnectionOptions options = new MqttConnectionOptions();
            options.setUserName(username);
            options.setPassword(password.getBytes());
            options.setKeepAliveInterval(60);
            options.setAutomaticReconnect(true);
            options.setCleanStart(false);
            options.setSessionExpiryInterval(604800L);
            options.setReceiveMaximum(1000);

            client.setCallback(new MqttCallback() {
                @Override
                public void disconnected(MqttDisconnectResponse disconnectResponse) {
                    connected.set(false);
                    log.warn("❌ Disconnected: {}", disconnectResponse.getReasonString());
                }

                @Override
                public void mqttErrorOccurred(MqttException exception) {
                    log.error("❌ MQTT error", exception);
                }

                @Override
                public void messageArrived(String topic, MqttMessage message) {
                    String payload = new String(message.getPayload(), StandardCharsets.UTF_8);
                    log.info("📩 MQTT message on [{}] ({} bytes, qos={}): {}", topic, message.getPayload().length, message.getQos(), truncateForLog(payload));

                    try {
                        List<ArticlePosDto> list;
                        if (payload.trim().startsWith("[")) {
                            list = OBJECT_MAPPER.readValue(payload, new TypeReference<List<ArticlePosDto>>() {});
                        } else {
                            list = List.of(OBJECT_MAPPER.readValue(payload, ArticlePosDto.class));
                        }

                        // one timestamp per batch or per item—your choice
                        var now = java.time.LocalDateTime.now();
                        // build ONE ART and ONE MEH for the whole list
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

                @Override
                public void deliveryComplete(IMqttToken token) {
                }

                @Override
                public void connectComplete(boolean reconnect, String serverURI) {
                    connected.set(true);
                    log.info("🔗 MQTT connected: {}", serverURI);
                    try {
                        client.subscribe(topic, qos).waitForCompletion();
                        log.info("✅ Subscribed to {} with client {}", topic, clientIdCfg);
                    } catch (MqttException e) {
                        log.error("❌ Failed to subscribe on reconnect", e);
                    }
                }

                @Override
                public void authPacketArrived(int reasonCode, MqttProperties properties) {
                }
            });

            client.connect(options).waitForCompletion();
            connected.set(true);
            log.info("✅ Subscribed to {} with client {}", topic, clientIdCfg);

        } catch (Exception e) {
            log.error("❌ MQTT startup failed", e);
        }
    }

    @PreDestroy
    public void disconnect() {
        try {
            if (client != null && client.isConnected()) {
                client.disconnect().waitForCompletion();
                log.info("🔌 Disconnected client: {}", client.getClientId());
            }
        } catch (MqttException e) {
            log.warn("❌ Failed to disconnect: {}", clientIdCfg, e);
        }
    }

    private static String truncateForLog(String s) {
        if (s == null) return "null";
        return (s.length() > 300) ? (s.substring(0, 300) + " …") : s;
    }
}

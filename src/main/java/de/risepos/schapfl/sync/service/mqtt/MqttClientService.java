package de.risepos.schapfl.sync.service.mqtt;

import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.paho.mqttv5.client.IMqttMessageListener;
import org.eclipse.paho.mqttv5.client.MqttAsyncClient;
import org.eclipse.paho.mqttv5.client.MqttCallback;
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions;
import org.eclipse.paho.mqttv5.client.persist.MemoryPersistence;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.packet.MqttProperties;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Startup
@ApplicationScoped
@Slf4j
public class MqttClientService {
    @ConfigProperty(name = "mqtt.broker")
    String broker;

    @ConfigProperty(name = "mqtt.client")
    String clientIdCfg;

    @ConfigProperty(name = "mqtt.username")
    String username;

    @ConfigProperty(name = "mqtt.password")
    String password;

    @ConfigProperty(name = "mqtt.qos", defaultValue = "1")
    int qos;

    private final Map<String, IMqttMessageListener> subscriptions = new ConcurrentHashMap<>();
    private final AtomicBoolean connected = new AtomicBoolean(false);

    private MqttAsyncClient client;

    @PostConstruct
    public void connect() {
        try {
            connectIfNeeded();
            resubscribeAll();
        } catch (Exception e) {
            log.error("❌ MQTT startup failed", e);
        }
    }

    public synchronized void subscribe(String topic, IMqttMessageListener listener) {
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException("MQTT topic must not be blank");
        }
        subscriptions.put(topic, listener);
        try {
            connectIfNeeded();
            client.subscribe(topic, qos).waitForCompletion();
            log.info("✅ Subscribed to {} with client {}", topic, clientIdCfg);
        } catch (Exception e) {
            log.error("❌ Failed to subscribe to {}", topic, e);
        }
    }

    public synchronized void publish(String topic, byte[] payload) throws Exception {
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException("MQTT topic must not be blank");
        }
        connectIfNeeded();
        MqttMessage message = new MqttMessage(payload == null ? new byte[0] : payload);
        message.setQos(qos);
        client.publish(topic, message).waitForCompletion();
    }

    public boolean isConnected() {
        return connected.get();
    }

    @PreDestroy
    public synchronized void disconnect() {
        try {
            if (client != null && client.isConnected()) {
                client.disconnect().waitForCompletion();
                log.info("🔌 Disconnected client: {}", client.getClientId());
            }
        } catch (MqttException e) {
            log.warn("❌ Failed to disconnect: {}", clientIdCfg, e);
        }
    }

    private synchronized void connectIfNeeded() throws Exception {
        if (client == null) {
            client = new MqttAsyncClient(broker, clientIdCfg, new MemoryPersistence());
            client.setCallback(new MqttCallback() {
                @Override
                public void disconnected(org.eclipse.paho.mqttv5.client.MqttDisconnectResponse disconnectResponse) {
                    connected.set(false);
                    log.warn("❌ Disconnected: {}", disconnectResponse.getReasonString());
                }

                @Override
                public void mqttErrorOccurred(MqttException exception) {
                    log.error("❌ MQTT error", exception);
                }

                @Override
                public void messageArrived(String topic, MqttMessage message) throws Exception {
                    IMqttMessageListener listener = subscriptions.get(topic);
                    if (listener != null) {
                        listener.messageArrived(topic, message);
                        return;
                    }
                    log.debug("MQTT message arrived on [{}] ({} bytes): {}", topic, message.getPayload().length,
                            truncateForLog(new String(message.getPayload(), StandardCharsets.UTF_8)));
                }

                @Override
                public void deliveryComplete(org.eclipse.paho.mqttv5.client.IMqttToken token) {
                }

                @Override
                public void connectComplete(boolean reconnect, String serverURI) {
                    connected.set(true);
                    log.info("🔗 MQTT connected: {}", serverURI);
                    try {
                        resubscribeAll();
                    } catch (Exception e) {
                        log.error("❌ Failed to restore subscriptions after reconnect", e);
                    }
                }

                @Override
                public void authPacketArrived(int reasonCode, MqttProperties properties) {
                }
            });
        }

        if (client.isConnected()) {
            connected.set(true);
            return;
        }

        client.connect(buildOptions()).waitForCompletion();
        connected.set(true);
        log.info("🔗 MQTT connected: {}", broker);
    }

    private MqttConnectionOptions buildOptions() {
        MqttConnectionOptions options = new MqttConnectionOptions();
        options.setUserName(username);
        options.setPassword(password.getBytes(StandardCharsets.UTF_8));
        options.setKeepAliveInterval(60);
        options.setAutomaticReconnect(true);
        options.setCleanStart(false);
        options.setSessionExpiryInterval(604800L);
        options.setReceiveMaximum(1000);
        return options;
    }

    private synchronized void resubscribeAll() throws Exception {
        if (client == null || !client.isConnected()) {
            return;
        }
        for (Map.Entry<String, IMqttMessageListener> entry : subscriptions.entrySet()) {
            client.subscribe(entry.getKey(), qos).waitForCompletion();
            log.info("✅ Subscribed to {} with client {}", entry.getKey(), clientIdCfg);
        }
    }

    private static String truncateForLog(String s) {
        if (s == null) return "null";
        return (s.length() > 300) ? (s.substring(0, 300) + " …") : s;
    }
}

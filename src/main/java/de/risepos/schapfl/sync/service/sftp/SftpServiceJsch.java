package de.risepos.schapfl.sync.service.sftp;

import com.jcraft.jsch.*;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.ByteArrayInputStream;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;

@Slf4j
@ApplicationScoped
public class SftpServiceJsch {
    @ConfigProperty(name = "sftp.host") String host;
    @ConfigProperty(name = "sftp.port", defaultValue = "22") int port;
    @ConfigProperty(name = "sftp.username")
    String username;
    @ConfigProperty(name = "sftp.password")
    Optional<String> password;           // optional
    @ConfigProperty(name = "sftp.privateKey")
    Optional<String> privateKey;       // optional
    @ConfigProperty(name = "sftp.privateKeyPassphrase")
    Optional<String> privateKeyPass; // optional
    @ConfigProperty(name = "sftp.knownHosts")
    Optional<String> knownHosts;       // optional
    @ConfigProperty(name = "sftp.strictHostKeyChecking", defaultValue = "yes") String strict;
    @ConfigProperty(name = "sftp.remoteDir") String remoteDir;

    // Retry config (optional)
    @ConfigProperty(name = "sftp.retry.maxAttempts", defaultValue = "1") int maxAttempts;
    @ConfigProperty(name = "sftp.retry.delayMillis", defaultValue = "0") long delayMillis;

    private final JSch jsch = new JSch();
    private Session session;
    private ChannelSftp sftp;

    private void validateConfig() {
        if (isBlank(host)) throw new IllegalStateException("sftp.host is required");
        if (port <= 0) throw new IllegalStateException("sftp.port must be > 0");
        if (isBlank(username)) throw new IllegalStateException("sftp.username is required");
        if (isBlank(remoteDir)) throw new IllegalStateException("sftp.remoteDir is required");

        boolean hasKey = privateKey.filter(s -> !s.isBlank()).isPresent();
        boolean hasPwd = password.filter(s -> !s.isBlank()).isPresent();

        if (!hasKey && !hasPwd) {
            throw new IllegalStateException("Either sftp.privateKey or sftp.password must be provided");
        }
        if ("yes".equalsIgnoreCase(strict)) {
            if (knownHosts.isEmpty() || knownHosts.get().isBlank()) {
                throw new IllegalStateException("StrictHostKeyChecking=yes but sftp.knownHosts is empty");
            }
        }
    }

    public synchronized void ensureConnected() throws Exception {
        validateConfig();

        // known_hosts (only if strict=yes)
        if ("yes".equalsIgnoreCase(strict)) {
            jsch.setKnownHosts(knownHosts.get());
        }

        if (privateKey.filter(s -> !s.isBlank()).isPresent()) {
            var pk = privateKey.get();
            if (privateKeyPass.filter(s -> !s.isBlank()).isPresent()) {
                jsch.addIdentity(pk, privateKeyPass.get());
            } else {
                jsch.addIdentity(pk);
            }
            log.info("SFTP auth mode: key ({})", pk);
        } else {
            log.info("SFTP auth mode: password (user={})", username);
        }

        session = jsch.getSession(username, host, port);
        var cfg = new Properties();
        cfg.put("StrictHostKeyChecking", strict); // yes/no
        session.setConfig(cfg);
        password.ifPresent(session::setPassword);
        session.connect(15_000);

        var channel = session.openChannel("sftp");
        channel.connect(10_000);
        sftp = (ChannelSftp) channel;
        mkdirs(remoteDir);
        log.info("SFTP connected {}@{}:{} → {}", username, host, port, remoteDir);
    }

    private void mkdirs(String path) throws SftpException {
        if (path == null || path.isBlank()) return;
        String[] parts = path.split("/");
        String cur = path.startsWith("/") ? "/" : "";
        for (String p : parts) {
            if (p == null || p.isBlank()) continue;
            cur = (cur.endsWith("/") ? cur : cur + "/") + p;
            try {
                sftp.cd(cur);
            } catch (SftpException e) {
                sftp.mkdir(cur);
            }
        }
    }

    /** Atomic upload: put to .tmp then rename in-place */
    public void uploadAtomic(String fileName, byte[] content) throws Exception {
        runWithRetry(() -> {
            ensureConnected();
            String tmp = remoteDir + "/" + fileName + ".tmp";
            String fin = remoteDir + "/" + fileName;
            try (var in = new ByteArrayInputStream(content)) {
                sftp.put(in, tmp, ChannelSftp.OVERWRITE);
            }
            sftp.rename(tmp, fin);
            log.info("⬆️  Uploaded {}", fin);
            return null;
        });
    }

    private <T> T runWithRetry(CallableX<T> op) throws Exception {
        int attempts = 0;
        Exception last = null;
        int max = Math.max(1, maxAttempts);
        while (++attempts <= max) {
            try {
                return op.call();
            } catch (JSchException | SftpException e) {
                last = e;
                log.warn("SFTP attempt {}/{} failed: {}", attempts, max, e.getMessage());
                closeQuietly();
                if (attempts < max && delayMillis > 0) Thread.sleep(delayMillis);
            }
        }
        throw last != null ? last : new RuntimeException("SFTP operation failed");
    }

    @PreDestroy
    public synchronized void closeQuietly() {
        try { if (sftp != null) sftp.disconnect(); } catch (Exception ignored) {}
        try { if (session != null) session.disconnect(); } catch (Exception ignored) {}
        sftp = null;
        session = null;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    @FunctionalInterface
    private interface CallableX<T> { T call() throws Exception; }
}

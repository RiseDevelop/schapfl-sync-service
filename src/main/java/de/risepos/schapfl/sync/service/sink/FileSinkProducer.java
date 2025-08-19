package de.risepos.schapfl.sync.service.sink;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class FileSinkProducer {
    @ConfigProperty(name = "sink.mode", defaultValue = "sftp") String mode;
    @Inject @LocalSink LocalFileSink local;
    @Inject @SftpSink  SftpFileSink  sftp;

    @Produces
    public FileSink produce() {
        return "local".equalsIgnoreCase(mode) ? local : sftp;
    }
}

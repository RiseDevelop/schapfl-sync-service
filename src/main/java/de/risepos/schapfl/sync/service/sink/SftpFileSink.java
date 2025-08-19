package de.risepos.schapfl.sync.service.sink;

import de.risepos.schapfl.sync.service.sftp.SftpServiceJsch;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
@SftpSink
public class SftpFileSink implements FileSink {

    @Inject SftpServiceJsch sftp;

    @Override
    public void writeAtomic(String filename, byte[] content) throws Exception {
        sftp.uploadAtomic(filename, content);
    }
}

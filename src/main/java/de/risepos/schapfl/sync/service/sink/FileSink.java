package de.risepos.schapfl.sync.service.sink;

public interface FileSink {
    void writeAtomic(String filename, byte[] content) throws Exception;
}

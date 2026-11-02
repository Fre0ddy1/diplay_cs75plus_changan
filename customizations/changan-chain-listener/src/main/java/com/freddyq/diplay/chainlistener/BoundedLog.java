package com.freddyq.diplay.chainlistener;

import java.io.*;
import java.nio.charset.StandardCharsets;

/** Four 4MiB slots maximum, streaming with an 8KiB buffer, no growing queue. */
final class BoundedLog implements Closeable {
    private final File directory;
    private final long limit;
    private final int slots;
    private OutputStream stream;
    private long size, flushedAt;
    private int index;
    BoundedLog(File directory) throws IOException { this(directory, 4 * 1024 * 1024L, 4); }
    BoundedLog(File directory, long limit, int slots) throws IOException {
        this.directory=directory; this.limit=limit; this.slots=slots;
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create private log directory");
        for (int i=0;i<slots;i++) {
            File previous=new File(directory,"chain-"+i+".log");
            if (previous.exists() && !previous.delete()) throw new IOException("Cannot rotate own previous log slot");
        }
        stream=open(0);
    }
    private OutputStream open(int slot) throws IOException { return new BufferedOutputStream(new FileOutputStream(new File(directory,"chain-"+slot+".log")),8192); }
    void line(String value) throws IOException {
        byte[] bytes=(value+"\n").getBytes(StandardCharsets.UTF_8);
        if (bytes.length>limit) throw new IOException("Unexpected oversized log record");
        if (size+bytes.length>limit) { stream.close(); index=(index+1)%slots; stream=open(index); size=0; }
        stream.write(bytes); size+=bytes.length;
        long now=System.currentTimeMillis();
        if (now-flushedAt>=1000) { stream.flush(); flushedAt=now; }
    }
    @Override public void close() throws IOException { stream.close(); }
}

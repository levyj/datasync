package com.socrata.datasync.deltaimporter2;

import org.tukaani.xz.LZMA2Options;
import org.tukaani.xz.XZOutputStream;

import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;

public class XZCompressInputStream extends InputStream {
    private final InputStream underlying;
    private final Worker worker;

    public XZCompressInputStream(InputStream underlying, int pipeBufferSize) {
        this.underlying = underlying;
        this.worker = new Worker(underlying, pipeBufferSize);
        worker.start();
    }

    @Override
    public int read() throws IOException {
        return worker.read();
    }

    @Override
    public int read(byte[] bytes, int off, int len) throws IOException {
        int count;
        int initialLen = len;
        while(len > 0 && (count = worker.read(bytes, off, len)) != -1) {
            off += count;
            len -= count;
        }
        if(len == initialLen) return -1;
        else return initialLen - len;
    }

    @Override
    public void close() throws IOException {
        worker.shutdown();
        underlying.close();
    }

    private static class Worker extends Thread {
        private final InputStream in;
        private final PipedInputStream source;
        private final PipedOutputStream sink;
        private volatile IOException pendingException;

        public Worker(InputStream in, int pipeBufferSize) {
            setDaemon(true);
            setName("Compression thread");

            this.in = in;
            this.source = new PipedInputStream(pipeBufferSize);
            this.sink = new PipedOutputStream();

            try {
                sink.connect(source);
            } catch(IOException e) {
                // this can only happen if the sink is already connected.  Since we just
                // created it, we know it's not.
            }
        }

        @Override
        public void run() {
            try {
                try(XZOutputStream out = new XZOutputStream(sink, new LZMA2Options())) {
                    byte[] buffer = new byte[4096];
                    int count;
                    while((count = in.read(buffer)) != -1) {
                        try {
                            out.write(buffer, 0, count);
                        } catch (IOException e) {
                            // ok we're done here
                        }
                    }
                }
            } catch(IOException e) {
                pendingException = e;
                try {
                    sink.close();
                } catch(IOException e2) {
                    // sink.close can't actually throw
                }
            }
        }

        public void shutdown() throws IOException {
            source.close(); // this can't actually throw
            in.close();
        }

        public int read() throws IOException {
            int res = source.read();
            if(res == -1 && pendingException != null) throw new IOException("Pending exception", pendingException);
            return res;
        }

        public int read(byte[] bs, int off, int len) throws IOException {
            int res = source.read(bs, off, len);
            if(res == -1 && pendingException != null) throw new IOException("Pending exception", pendingException);
            return res;
        }
    }
}

package de.roni.alearena;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/** Verteilt einen MP3-Stream an beliebig viele Browser-Zuhörer (Fallback, wenn jemand kein Voice-Chat-Mod hat). */
public final class AudioHub {

    private static final byte[] EOF = new byte[0];
    private final Set<BlockingQueue<byte[]>> clients = ConcurrentHashMap.newKeySet();

    public void pump(InputStream in) throws IOException {
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) {
            byte[] chunk = Arrays.copyOf(buf, n);
            for (BlockingQueue<byte[]> q : clients) {
                if (!q.offer(chunk)) { // Client zu langsam -> rauswerfen
                    clients.remove(q);
                    q.clear();
                    q.offer(EOF);
                }
            }
        }
        closeAll();
    }

    public void serve(OutputStream out) throws IOException, InterruptedException {
        BlockingQueue<byte[]> q = new ArrayBlockingQueue<>(512);
        clients.add(q);
        try {
            while (true) {
                byte[] c = q.poll(30, TimeUnit.SECONDS);
                if (c == null) {
                    continue;
                }
                if (c == EOF) {
                    break;
                }
                out.write(c);
                out.flush();
            }
        } finally {
            clients.remove(q);
        }
    }

    public void closeAll() {
        for (BlockingQueue<byte[]> q : clients) {
            q.clear();
            q.offer(EOF);
        }
        clients.clear();
    }

    public int listeners() {
        return clients.size();
    }
}

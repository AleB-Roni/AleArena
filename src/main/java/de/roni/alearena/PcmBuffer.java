package de.roni.alearena;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ArrayBlockingQueue;

/**
 * Puffer für 48 kHz / Mono / 16-Bit-PCM in 20-ms-Blöcken (960 Samples) –
 * genau das Format, das Simple Voice Chat pro Paket erwartet.
 */
public final class PcmBuffer {

    public static final int FRAME = 960;
    private static final short[] SILENCE = new short[FRAME];
    /** Mehr als ~0,5 s Rückstand wird verworfen, damit der Ton nicht hinterherläuft. */
    private static final int MAX_LAG_FRAMES = 25;

    private final ArrayBlockingQueue<short[]> queue = new ArrayBlockingQueue<>(250);

    public void pump(InputStream in) throws IOException {
        byte[] buf = new byte[FRAME * 2];
        while (readFully(in, buf)) {
            short[] s = new short[FRAME];
            for (int i = 0; i < FRAME; i++) {
                s[i] = (short) ((buf[2 * i] & 0xFF) | (buf[2 * i + 1] << 8));
            }
            if (!queue.offer(s)) {
                queue.poll();
                queue.offer(s);
            }
        }
    }

    /** Wird von Simple Voice Chat alle 20 ms aufgerufen. Gibt nie null zurück (sonst stoppt der Player). */
    public short[] next() {
        while (queue.size() > MAX_LAG_FRAMES) {
            queue.poll();
        }
        short[] s = queue.poll();
        return s != null ? s : SILENCE;
    }

    public void clear() {
        queue.clear();
    }

    static boolean readFully(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) {
                return false;
            }
            off += n;
        }
        return true;
    }
}

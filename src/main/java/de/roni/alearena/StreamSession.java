package de.roni.alearena;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Startet ffmpeg, liest Rohbilder (RGB24) von stdout, wandelt sie in Kartenfarben,
 * zerschneidet sie in 128x128-Kacheln und liefert Ton als PCM (Voice Chat) und/oder MP3 (Browser).
 * Bewusst ohne Bukkit-Abhängigkeit, damit es auch außerhalb des Servers testbar ist.
 */
public final class StreamSession {

    public record Frame(byte[][] tiles, long createdAt) {}

    public static final class Spec {
        public final List<List<String>> inputs = new ArrayList<>();
        public final Map<String, String> env = new HashMap<>();
        public int audioInput = -1;
        public int cols = 1;
        public int rows = 1;
        public int fps = 10;
        public String ffmpeg = "ffmpeg";
        public String label = "stream";
    }

    private final Spec spec;
    private final PaletteConverter palette;
    private final PcmBuffer pcm;
    private final AudioHub mp3;
    private final Consumer<Frame> sink;
    private final Consumer<String> onEnd;
    private final Logger log;
    private final List<ServerSocket> sockets = new CopyOnWriteArrayList<>();

    private volatile boolean stopped;
    private volatile Process process;
    private volatile String lastError = "";
    private volatile long frames;

    public StreamSession(Spec spec, PaletteConverter palette, PcmBuffer pcm, AudioHub mp3,
                         Consumer<Frame> sink, Consumer<String> onEnd, Logger log) {
        this.spec = spec;
        this.palette = palette;
        this.pcm = pcm;
        this.mp3 = mp3;
        this.sink = sink;
        this.onEnd = onEnd;
        this.log = log;
    }

    public long frames() {
        return frames;
    }

    public List<String> buildCommand() throws IOException {
        int w = spec.cols * 128;
        int h = spec.rows * 128;
        List<String> cmd = new ArrayList<>(List.of(spec.ffmpeg, "-hide_banner", "-loglevel", "error", "-nostdin"));
        for (List<String> in : spec.inputs) {
            cmd.addAll(in);
        }
        String vf = "fps=" + spec.fps
                + ",scale=" + w + ":" + h + ":force_original_aspect_ratio=decrease:flags=bilinear"
                + ",pad=" + w + ":" + h + ":(ow-iw)/2:(oh-ih)/2:color=black";
        cmd.addAll(List.of("-map", "0:v:0", "-vf", vf, "-pix_fmt", "rgb24", "-f", "rawvideo", "pipe:1"));

        if (spec.audioInput >= 0) {
            String map = spec.audioInput + ":a:0";
            if (pcm != null) {
                int port = listen(pcm::pump, "PCM");
                cmd.addAll(List.of("-map", map, "-c:a", "pcm_s16le", "-ar", "48000", "-ac", "1",
                        "-f", "s16le", "tcp://127.0.0.1:" + port));
            }
            if (mp3 != null) {
                int port = listen(mp3::pump, "MP3");
                cmd.addAll(List.of("-map", map, "-c:a", "libmp3lame", "-b:a", "128k", "-ar", "44100", "-ac", "2",
                        "-f", "mp3", "tcp://127.0.0.1:" + port));
            }
        }
        return cmd;
    }

    public void start() throws IOException {
        List<String> cmd = buildCommand();
        log.fine("[AleArena] ffmpeg: " + String.join(" ", cmd));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().putAll(spec.env);
        process = pb.start();

        daemon("ffmpeg-log", () -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    lastError = line;
                    if (!stopped) {
                        log.warning("[AleArena/" + spec.label + "] ffmpeg: " + line);
                    }
                }
            } catch (IOException ignored) {
            }
        });

        daemon("video", this::readVideo);
    }

    private void readVideo() {
        int w = spec.cols * 128;
        int h = spec.rows * 128;
        byte[] rgb = new byte[w * h * 3];
        byte[] pal = new byte[w * h];
        try (InputStream in = new BufferedInputStream(process.getInputStream(), 1 << 20)) {
            while (!stopped && PcmBuffer.readFully(in, rgb)) {
                palette.convert(rgb, pal, w, h);
                sink.accept(new Frame(split(pal, spec.cols, spec.rows), System.currentTimeMillis()));
                frames++;
            }
        } catch (IOException e) {
            if (!stopped) {
                lastError = e.getMessage();
            }
        } catch (RuntimeException e) {
            log.log(Level.WARNING, "[AleArena] Fehler im Videothread", e);
            lastError = e.toString();
        }
        if (!stopped) {
            stop();
            onEnd.accept(lastError);
        }
    }

    public static byte[][] split(byte[] pal, int cols, int rows) {
        int w = cols * 128;
        byte[][] tiles = new byte[cols * rows][];
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                byte[] t = new byte[128 * 128];
                for (int y = 0; y < 128; y++) {
                    System.arraycopy(pal, (r * 128 + y) * w + c * 128, t, y * 128, 128);
                }
                tiles[r * cols + c] = t;
            }
        }
        return tiles;
    }

    public void stop() {
        stopped = true;
        for (ServerSocket s : sockets) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
        Process p = process;
        if (p != null && p.isAlive()) {
            p.destroy();
            try {
                if (!p.waitFor(2, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                }
            } catch (InterruptedException e) {
                p.destroyForcibly();
                Thread.currentThread().interrupt();
            }
        }
    }

    @FunctionalInterface
    private interface StreamConsumer {
        void accept(InputStream in) throws IOException;
    }

    private int listen(StreamConsumer consumer, String what) throws IOException {
        ServerSocket ss = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        sockets.add(ss);
        daemon("audio-" + what, () -> {
            try (Socket s = ss.accept(); InputStream in = s.getInputStream()) {
                consumer.accept(in);
            } catch (IOException e) {
                if (!stopped) {
                    log.fine("[AleArena] Audio " + what + " beendet: " + e.getMessage());
                }
            }
        });
        return ss.getLocalPort();
    }

    private void daemon(String name, Runnable r) {
        Thread t = new Thread(r, "AleArena-" + spec.label + "-" + name);
        t.setDaemon(true);
        t.start();
    }
}

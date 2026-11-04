package de.roni.alearena;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Unsichtbarer Browser auf dem Server: Xvfb (virtueller Bildschirm) + Google Chrome im Kiosk-Modus.
 * Ton läuft in eine eigene PulseAudio-Senke, die ffmpeg wieder abgreift.
 * Steuerung (Klicks, Tippen) über das Chrome-DevTools-Protokoll.
 */
final class BrowserHost {

    static final class Config {
        String chrome = "google-chrome";
        String xvfb = "Xvfb";
        String pulseaudio = "pulseaudio";
        String pactl = "pactl";
        boolean audio = true;
        int width = 1280;
        int height = 720;
        int display = 90;
        Path dataDir;
    }

    private final Config cfg;
    private final String name;
    private final Logger log;
    private final Map<String, String> env = new HashMap<>();

    private Process xvfb;
    private Process chrome;
    private String sinkModule;
    private boolean audioReady;
    private volatile CdpClient cdp;

    BrowserHost(Config cfg, String name, Logger log) {
        this.cfg = cfg;
        this.name = name;
        this.log = log;
    }

    String sinkName() {
        return "alearena_" + name;
    }

    Map<String, String> env() {
        return env;
    }

    boolean audioReady() {
        return audioReady;
    }

    int width() {
        return cfg.width;
    }

    int height() {
        return cfg.height;
    }

    void start(String url) throws IOException, InterruptedException {
        Path runtime = cfg.dataDir.resolve("runtime");
        Path profile = cfg.dataDir.resolve("profile-" + name);
        Files.createDirectories(runtime);
        Files.createDirectories(profile);
        runtime.toFile().setReadable(false, false);
        runtime.toFile().setReadable(true, true);
        runtime.toFile().setWritable(true, true);
        runtime.toFile().setExecutable(true, true);

        env.put("DISPLAY", ":" + cfg.display);
        env.put("XDG_RUNTIME_DIR", runtime.toAbsolutePath().toString());
        env.put("PULSE_SINK", sinkName());

        // 1) Virtueller Bildschirm
        Files.deleteIfExists(Path.of("/tmp/.X11-unix/X" + cfg.display));
        Files.deleteIfExists(Path.of("/tmp/.X" + cfg.display + "-lock"));
        xvfb = new ProcessBuilder(cfg.xvfb, ":" + cfg.display, "-screen", "0",
                cfg.width + "x" + cfg.height + "x24", "-nolisten", "tcp")
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        Path socket = Path.of("/tmp/.X11-unix/X" + cfg.display);
        for (int i = 0; i < 50 && !Files.exists(socket); i++) {
            if (!xvfb.isAlive()) {
                throw new IOException("Xvfb ist sofort beendet – ist es installiert? (" + cfg.xvfb + ")");
            }
            Thread.sleep(100);
        }
        if (!Files.exists(socket)) {
            throw new IOException("Xvfb startet nicht (Display :" + cfg.display + ")");
        }

        // 2) Ton-Senke
        if (cfg.audio) {
            try {
                ensurePulse();
                sinkModule = run(cfg.pactl, "load-module", "module-null-sink",
                        "sink_name=" + sinkName(), "sink_properties=device.description=AleArena_" + name).trim();
                audioReady = true;
            } catch (IOException e) {
                log.warning("[AleArena] PulseAudio nicht verfügbar – Browser läuft ohne Ton: " + e.getMessage());
            }
        }

        // 3) Chrome
        int cdpPort;
        try (ServerSocket s = new ServerSocket(0)) {
            cdpPort = s.getLocalPort();
        }
        List<String> cmd = new ArrayList<>(List.of(cfg.chrome,
                "--no-sandbox", "--no-first-run", "--no-default-browser-check",
                "--disable-dev-shm-usage", "--disable-infobars", "--disable-session-crashed-bubble",
                "--disable-features=Translate,MediaRouter,DialMediaRouteProvider",
                "--autoplay-policy=no-user-gesture-required",
                "--password-store=basic", "--lang=de-DE",
                "--window-position=0,0", "--window-size=" + cfg.width + "," + cfg.height,
                "--kiosk", "--user-data-dir=" + profile.toAbsolutePath(),
                "--remote-debugging-address=127.0.0.1", "--remote-debugging-port=" + cdpPort,
                "--remote-allow-origins=*", url));
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD);
        pb.environment().putAll(env);
        chrome = pb.start();
        cdp = CdpClient.connect(cdpPort, 20_000);
    }

    private void ensurePulse() throws IOException, InterruptedException {
        ProcessBuilder check = new ProcessBuilder(cfg.pulseaudio, "--check");
        check.environment().putAll(env);
        Process p = check.start();
        if (p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0) {
            return;
        }
        run(cfg.pulseaudio, "-D", "--exit-idle-time=-1", "--disallow-exit");
        for (int i = 0; i < 30; i++) {
            Process c = check.start();
            if (c.waitFor(5, TimeUnit.SECONDS) && c.exitValue() == 0) {
                return;
            }
            Thread.sleep(200);
        }
        throw new IOException("PulseAudio-Daemon startet nicht");
    }

    private String run(String... cmd) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        pb.environment().putAll(env);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!p.waitFor(15, TimeUnit.SECONDS) || p.exitValue() != 0) {
            throw new IOException(cmd[0] + " " + (cmd.length > 1 ? cmd[1] : "") + ": " + out.trim());
        }
        return out;
    }

    /** ffmpeg-Eingabe für das Bild. */
    List<String> videoInput(int fps) {
        return List.of("-f", "x11grab", "-draw_mouse", "0", "-framerate", String.valueOf(fps),
                "-video_size", cfg.width + "x" + cfg.height, "-i", ":" + cfg.display + ".0");
    }

    /** ffmpeg-Eingabe für den Ton. */
    List<String> audioInput() {
        return List.of("-f", "pulse", "-i", sinkName() + ".monitor");
    }

    CdpClient cdp() {
        return cdp;
    }

    void stop() {
        CdpClient c = cdp;
        cdp = null;
        if (c != null) {
            c.close();
        }
        kill(chrome);
        kill(xvfb);
        if (sinkModule != null && !sinkModule.isEmpty()) {
            try {
                run(cfg.pactl, "unload-module", sinkModule);
            } catch (Exception ignored) {
            }
        }
    }

    private static void kill(Process p) {
        if (p == null) {
            return;
        }
        p.descendants().forEach(ProcessHandle::destroy);
        p.destroy();
        try {
            if (!p.waitFor(3, TimeUnit.SECONDS)) {
                p.descendants().forEach(ProcessHandle::destroyForcibly);
                p.destroyForcibly();
            }
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }
}

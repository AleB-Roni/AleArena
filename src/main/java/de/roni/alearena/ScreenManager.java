package de.roni.alearena;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.GlowItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

final class ScreenManager {

    private static final Pattern NAME = Pattern.compile("[a-zA-Z0-9_-]{1,24}");
    private static final Pattern DIRECT = Pattern.compile(
            "(?i)^(rtmp|rtmps|rtsp|srt|udp|tcp)://.*|.*\\.(m3u8|mpd|mp4|mkv|webm|ts|flv|mov|mp3|aac)(\\?.*)?$");

    private final AleArenaPlugin plugin;
    private final Map<String, Screen> screens = new LinkedHashMap<>();
    private final File file;
    private int nextDisplay;

    ScreenManager(AleArenaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "screens.yml");
        this.nextDisplay = plugin.getConfig().getInt("browser.display-start", 90);
    }

    Collection<Screen> all() {
        return screens.values();
    }

    Screen get(String name) {
        return name == null ? null : screens.get(name.toLowerCase(Locale.ROOT));
    }

    // ------------------------------------------------------------------ Laden / Speichern

    @SuppressWarnings("deprecation")
    void load() {
        if (!file.exists()) {
            return;
        }
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection sec = y.getConfigurationSection("screens");
        if (sec == null) {
            return;
        }
        for (String key : sec.getKeys(false)) {
            ConfigurationSection s = sec.getConfigurationSection(key);
            if (s == null) {
                continue;
            }
            try {
                List<Integer> ids = s.getIntegerList("maps");
                Screen sc = new Screen(key, UUID.fromString(s.getString("world")),
                        s.getInt("x"), s.getInt("y"), s.getInt("z"),
                        BlockFace.valueOf(s.getString("face")), s.getInt("cols"), s.getInt("rows"),
                        ids.stream().mapToInt(Integer::intValue).toArray());
                for (String f : s.getStringList("frames")) {
                    sc.frameIds.add(UUID.fromString(f));
                }
                byte black = plugin.palette().black();
                for (int i = 0; i < sc.mapIds.length; i++) {
                    MapView v = Bukkit.getMap(sc.mapIds[i]);
                    if (v == null) {
                        plugin.getLogger().warning("Karte " + sc.mapIds[i] + " von Leinwand " + key + " fehlt.");
                        continue;
                    }
                    attachRenderer(sc, i, v, black);
                }
                screens.put(key, sc);
            } catch (RuntimeException e) {
                plugin.getLogger().warning("Leinwand " + key + " konnte nicht geladen werden: " + e.getMessage());
            }
        }
        plugin.getLogger().info(screens.size() + " Leinwand/Leinwände geladen.");
    }

    void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Screen sc : screens.values()) {
            String p = "screens." + sc.name + ".";
            y.set(p + "world", sc.worldId.toString());
            y.set(p + "x", sc.tx);
            y.set(p + "y", sc.ty);
            y.set(p + "z", sc.tz);
            y.set(p + "face", sc.face.name());
            y.set(p + "cols", sc.cols);
            y.set(p + "rows", sc.rows);
            y.set(p + "maps", Arrays.stream(sc.mapIds).boxed().toList());
            y.set(p + "frames", sc.frameIds.stream().map(UUID::toString).toList());
        }
        try {
            y.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("screens.yml konnte nicht gespeichert werden: " + e.getMessage());
        }
    }

    private void attachRenderer(Screen sc, int i, MapView v, byte black) {
        for (MapRenderer r : new ArrayList<>(v.getRenderers())) {
            v.removeRenderer(r);
        }
        v.setTrackingPosition(false);
        v.setUnlimitedTracking(false);
        v.setLocked(true);
        byte[] init = new byte[128 * 128];
        Arrays.fill(init, black);
        TileRenderer tr = new TileRenderer(init);
        v.addRenderer(tr);
        sc.views[i] = v;
        sc.renderers[i] = tr;
        sc.lastSent[i] = init;
    }

    // ------------------------------------------------------------------ Erstellen / Entfernen

    @SuppressWarnings("deprecation")
    String create(Player p, String rawName, int cols, int rows) {
        if (!NAME.matcher(rawName).matches()) {
            return "Name nur aus Buchstaben, Zahlen, _ und - (max. 24 Zeichen).";
        }
        String name = rawName.toLowerCase(Locale.ROOT);
        if (screens.containsKey(name)) {
            return "Eine Leinwand namens '" + name + "' gibt es schon.";
        }
        int maxC = plugin.getConfig().getInt("max-width", 12);
        int maxR = plugin.getConfig().getInt("max-height", 8);
        if (cols < 1 || rows < 1 || cols > maxC || rows > maxR) {
            return "Größe muss zwischen 1x1 und " + maxC + "x" + maxR + " liegen.";
        }
        Block target = p.getTargetBlockExact(8);
        BlockFace face = p.getTargetBlockFace(8);
        if (target == null || face == null) {
            return "Schau auf den Block, der UNTEN LINKS die Leinwand bilden soll (max. 8 Blöcke weg).";
        }
        if (face != BlockFace.NORTH && face != BlockFace.SOUTH && face != BlockFace.EAST && face != BlockFace.WEST) {
            return "Schau auf die Seite einer Wand, nicht auf Boden oder Decke.";
        }
        int rx = face.getModZ();
        int rz = -face.getModX();
        World w = target.getWorld();
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                Block wall = target.getRelative(rx * col, rows - 1 - row, rz * col);
                Block front = wall.getRelative(face);
                if (!wall.getType().isSolid()) {
                    return "Die Wand ist nicht durchgehend fest (Lücke bei " + xyz(wall) + ").";
                }
                if (!front.getType().isAir()) {
                    return "Vor der Wand ist etwas im Weg (" + front.getType().name().toLowerCase() + " bei " + xyz(front) + ").";
                }
            }
        }
        Screen sc = new Screen(name, w.getUID(), target.getX(), target.getY(), target.getZ(), face, cols, rows, new int[cols * rows]);
        byte black = plugin.palette().black();
        List<Entity> spawned = new ArrayList<>();
        try {
            for (int row = 0; row < rows; row++) {
                for (int col = 0; col < cols; col++) {
                    int i = row * cols + col;
                    Block wall = target.getRelative(rx * col, rows - 1 - row, rz * col);
                    Block front = wall.getRelative(face);
                    MapView v = Bukkit.createMap(w);
                    v.setScale(MapView.Scale.CLOSEST);
                    attachRenderer(sc, i, v, black);
                    sc.mapIds[i] = v.getId();
                    ItemStack item = new ItemStack(Material.FILLED_MAP);
                    MapMeta meta = (MapMeta) item.getItemMeta();
                    meta.setMapView(v);
                    item.setItemMeta(meta);
                    GlowItemFrame frame = w.spawn(front.getLocation(), GlowItemFrame.class, f -> {
                        f.setFacingDirection(face, true);
                        f.setItem(item, false);
                        f.setVisible(false);
                        f.setFixed(true);
                        f.setInvulnerable(true);
                        f.setPersistent(true);
                        f.getPersistentDataContainer().set(plugin.key(), PersistentDataType.STRING, name);
                    });
                    spawned.add(frame);
                    sc.frameIds.add(frame.getUniqueId());
                }
            }
        } catch (RuntimeException e) {
            spawned.forEach(Entity::remove);
            return "Item-Frames konnten nicht gesetzt werden: " + e.getMessage();
        }
        screens.put(name, sc);
        save();
        return null;
    }

    boolean remove(String name) {
        Screen sc = get(name);
        if (sc == null) {
            return false;
        }
        stop(sc, false);
        World w = Bukkit.getWorld(sc.worldId);
        if (w != null) {
            Vector c = sc.center();
            Location loc = new Location(w, c.getX(), c.getY(), c.getZ());
            w.getChunkAt(loc).load();
            double rad = Math.max(sc.cols, sc.rows) + 2;
            for (Entity e : w.getNearbyEntities(loc, rad, rad, rad)) {
                String tag = e.getPersistentDataContainer().get(plugin.key(), PersistentDataType.STRING);
                if (sc.name.equals(tag)) {
                    e.remove();
                }
            }
        }
        for (UUID id : sc.frameIds) {
            Entity e = Bukkit.getEntity(id);
            if (e != null) {
                e.remove();
            }
        }
        screens.remove(sc.name);
        save();
        return true;
    }

    private static String xyz(Block b) {
        return b.getX() + " " + b.getY() + " " + b.getZ();
    }

    // ------------------------------------------------------------------ Abspielen

    enum Mode { WEB, URL, OBS }

    void play(CommandSender sender, Screen sc, Mode mode, String url) {
        if (sc.playing()) {
            stop(sc, false);
        }
        if (mode == Mode.OBS) {
            for (Screen o : screens.values()) {
                if (o != sc && o.obs && o.playing()) {
                    sender.sendMessage(err("OBS-Empfang läuft schon auf Leinwand '" + o.name + "'."));
                    return;
                }
            }
        }
        sc.starting = true;
        sc.obs = mode == Mode.OBS;
        sc.source = mode == Mode.OBS ? "OBS" : url;
        FileConfiguration cfg = plugin.getConfig();
        sender.sendMessage(info(switch (mode) {
            case WEB -> "Starte Browser auf '" + sc.name + "' …";
            case URL -> "Hole Stream für '" + sc.name + "' …";
            case OBS -> "Warte auf OBS: Server rtmp://<server-ip>:" + cfg.getInt("rtmp.port", 1935)
                    + "/live  –  Stream-Key: " + cfg.getString("rtmp.key", "alearena");
        }));

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            BrowserHost browser = null;
            try {
                int fps = Math.max(1, Math.min(20, cfg.getInt("fps", 10)));
                StreamSession.Spec spec = new StreamSession.Spec();
                spec.cols = sc.cols;
                spec.rows = sc.rows;
                spec.fps = fps;
                spec.ffmpeg = cfg.getString("ffmpeg-path", "ffmpeg");
                spec.label = sc.name;
                boolean wantAudio = true;
                switch (mode) {
                    case WEB -> {
                        browser = new BrowserHost(browserConfig(sc), sc.name, plugin.getLogger());
                        browser.start(url);
                        spec.inputs.add(browser.videoInput(fps));
                        spec.env.putAll(browser.env());
                        if (browser.audioReady()) {
                            spec.inputs.add(browser.audioInput());
                            spec.audioInput = 1;
                        } else {
                            wantAudio = false;
                        }
                    }
                    case URL -> {
                        List<String> urls = resolve(url);
                        for (String u : urls) {
                            List<String> in = new ArrayList<>();
                            if (u.startsWith("http")) {
                                in.addAll(List.of("-reconnect", "1", "-reconnect_streamed", "1", "-reconnect_delay_max", "5"));
                            }
                            in.addAll(List.of("-re", "-i", u));
                            spec.inputs.add(in);
                        }
                        spec.audioInput = urls.size() - 1;
                    }
                    case OBS -> {
                        spec.inputs.add(List.of("-listen", "1", "-f", "flv", "-i",
                                "rtmp://0.0.0.0:" + cfg.getInt("rtmp.port", 1935) + "/live/" + cfg.getString("rtmp.key", "alearena")));
                        spec.audioInput = 0;
                    }
                }
                AudioOut voice = plugin.voice();
                boolean useVoice = wantAudio && voice != null && voice.ready() && cfg.getBoolean("voicechat.enabled", true);
                boolean useWeb = wantAudio && plugin.web() != null;
                if (!useVoice && !useWeb) {
                    spec.audioInput = -1;
                }
                PcmBuffer pcm = useVoice ? new PcmBuffer() : null;
                AudioHub mp3 = useWeb ? new AudioHub() : null;

                final BrowserHost fb = browser;
                StreamSession[] holder = new StreamSession[1];
                StreamSession session = new StreamSession(spec, plugin.palette(), pcm, mp3,
                        frame -> enqueue(sc, frame),
                        error -> Bukkit.getScheduler().runTask(plugin, () -> onEnded(sc, holder[0], error)),
                        plugin.getLogger());
                holder[0] = session;
                session.start();

                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!sc.starting) { // zwischendurch gestoppt
                        session.stop();
                        if (fb != null) {
                            fb.stop();
                        }
                        return;
                    }
                    sc.session = session;
                    sc.browser = fb;
                    sc.pcm = pcm;
                    sc.mp3 = mp3;
                    sc.starting = false;
                    if (pcm != null) {
                        World w = Bukkit.getWorld(sc.worldId);
                        Vector c = sc.center();
                        if (w != null) {
                            sc.voiceStop = voice.start(w, c.getX(), c.getY(), c.getZ(),
                                    (float) cfg.getDouble("voicechat.distance", 48), pcm);
                        }
                    }
                    announce(sc, mode);
                });
            } catch (Exception e) {
                if (browser != null) {
                    browser.stop();
                }
                String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                Bukkit.getScheduler().runTask(plugin, () -> {
                    sc.starting = false;
                    sc.source = null;
                    sender.sendMessage(err("Start fehlgeschlagen: " + msg));
                });
            }
        });
    }

    private BrowserHost.Config browserConfig(Screen sc) {
        FileConfiguration cfg = plugin.getConfig();
        BrowserHost.Config c = new BrowserHost.Config();
        c.chrome = cfg.getString("browser.chrome-path", "google-chrome");
        c.xvfb = cfg.getString("browser.xvfb-path", "Xvfb");
        c.pulseaudio = cfg.getString("browser.pulseaudio-path", "pulseaudio");
        c.pactl = cfg.getString("browser.pactl-path", "pactl");
        c.audio = cfg.getBoolean("browser.audio", true);
        c.height = cfg.getInt("browser.height", 720);
        // Seitenverhältnis an die Leinwand anpassen -> keine schwarzen Ränder
        c.width = (int) Math.round(c.height * (double) sc.cols / sc.rows / 2.0) * 2;
        c.display = nextDisplay++;
        if (nextDisplay > cfg.getInt("browser.display-start", 90) + 50) {
            nextDisplay = cfg.getInt("browser.display-start", 90);
        }
        c.dataDir = new File(plugin.getDataFolder(), "browser").toPath();
        return c;
    }

    private List<String> resolve(String url) throws IOException, InterruptedException {
        if (DIRECT.matcher(url).matches()) {
            return List.of(url);
        }
        FileConfiguration cfg = plugin.getConfig();
        String ytdlp = cfg.getString("ytdlp-path", "yt-dlp");
        Process p;
        try {
            p = new ProcessBuilder(ytdlp, "-g", "--no-playlist", "--no-warnings",
                    "-f", cfg.getString("ytdlp-format", "best[height<=720]/bv*[height<=720]+ba/best"), url)
                    .redirectErrorStream(false).start();
        } catch (IOException e) {
            // yt-dlp fehlt – dann direkt versuchen
            return List.of(url);
        }
        List<String> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("http")) {
                    out.add(line.trim());
                }
            }
        }
        String errText = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        if (!p.waitFor(60, TimeUnit.SECONDS) || p.exitValue() != 0 || out.isEmpty()) {
            throw new IOException("yt-dlp konnte den Stream nicht finden. Tipp: /arena web nutzen. "
                    + (errText.isEmpty() ? "" : errText.lines().reduce((a, b) -> b).orElse("")));
        }
        return out.size() > 2 ? out.subList(0, 2) : out;
    }

    private void enqueue(Screen sc, StreamSession.Frame f) {
        int max = Math.max(20, plugin.getConfig().getInt("fps", 10) * 12);
        synchronized (sc.queue) {
            sc.queue.addLast(f);
            while (sc.queue.size() > max) {
                sc.queue.pollFirst();
            }
        }
    }

    private void onEnded(Screen sc, StreamSession session, String error) {
        if (session == null || sc.session != session) {
            return;
        }
        String src = sc.source;
        stop(sc, true);
        Component msg = err("Stream auf '" + sc.name + "' beendet" + (error == null || error.isBlank() ? "." : ": " + error));
        notifyNearby(sc, msg);
        plugin.getLogger().info("Stream " + src + " auf " + sc.name + " beendet. " + (error == null ? "" : error));
    }

    void stop(Screen sc, boolean blank) {
        sc.starting = false;
        StreamSession s = sc.session;
        sc.session = null;
        BrowserHost b = sc.browser;
        sc.browser = null;
        Runnable vs = sc.voiceStop;
        sc.voiceStop = null;
        AudioHub hub = sc.mp3;
        sc.mp3 = null;
        sc.pcm = null;
        sc.source = null;
        sc.obs = false;
        synchronized (sc.queue) {
            sc.queue.clear();
        }
        if (vs != null) {
            try {
                vs.run();
            } catch (Throwable ignored) {
            }
        }
        if (hub != null) {
            hub.closeAll();
        }
        if (s != null || b != null) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                if (s != null) {
                    s.stop();
                }
                if (b != null) {
                    b.stop();
                }
            });
        }
        // Leinwand schwarz machen
        byte[] black = new byte[128 * 128];
        Arrays.fill(black, plugin.palette().black());
        List<Player> viewers = viewers(sc);
        for (int i = 0; i < sc.renderers.length; i++) {
            if (sc.renderers[i] == null) {
                continue;
            }
            sc.renderers[i].setData(black);
            sc.lastSent[i] = black;
            for (Player p : viewers) {
                p.sendMap(sc.views[i]);
            }
        }
    }

    void stopAll() {
        for (Screen sc : screens.values()) {
            StreamSession s = sc.session;
            BrowserHost b = sc.browser;
            Runnable vs = sc.voiceStop;
            sc.session = null;
            sc.browser = null;
            if (vs != null) {
                try {
                    vs.run();
                } catch (Throwable ignored) {
                }
            }
            if (s != null) {
                s.stop();
            }
            if (b != null) {
                b.stop();
            }
        }
    }

    // ------------------------------------------------------------------ Jeder Tick

    void tick() {
        if (screens.isEmpty()) {
            return;
        }
        long due = System.currentTimeMillis() - plugin.getConfig().getLong("video-delay-ms", 0);
        for (Screen sc : screens.values()) {
            if (sc.session == null) {
                continue;
            }
            StreamSession.Frame f = null;
            synchronized (sc.queue) {
                while (!sc.queue.isEmpty() && sc.queue.peekFirst().createdAt() <= due) {
                    f = sc.queue.pollFirst();
                }
            }
            if (f == null) {
                continue;
            }
            List<Player> viewers = viewers(sc);
            for (int i = 0; i < f.tiles().length; i++) {
                if (sc.views[i] == null || Arrays.equals(f.tiles()[i], sc.lastSent[i])) {
                    continue;
                }
                sc.renderers[i].setData(f.tiles()[i]);
                sc.lastSent[i] = f.tiles()[i];
                for (Player p : viewers) {
                    p.sendMap(sc.views[i]);
                }
            }
        }
    }

    List<Player> viewers(Screen sc) {
        World w = Bukkit.getWorld(sc.worldId);
        if (w == null) {
            return List.of();
        }
        Vector c = sc.center();
        double d = plugin.getConfig().getDouble("view-distance", 96);
        double d2 = d * d;
        List<Player> out = new ArrayList<>();
        for (Player p : w.getPlayers()) {
            Location l = p.getLocation();
            double dx = l.getX() - c.getX(), dy = l.getY() - c.getY(), dz = l.getZ() - c.getZ();
            if (dx * dx + dy * dy + dz * dz <= d2) {
                out.add(p);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ Browser-Steuerung

    /** Klick eines Spielers auf die Leinwand an die passende Stelle im Browser weiterreichen. */
    void click(Player p, Screen sc, boolean scrollUp, boolean scrollDown) {
        BrowserHost b = sc.browser;
        if (b == null) {
            return;
        }
        Location eye = p.getEyeLocation();
        double[] uv = sc.hit(eye.toVector(), eye.getDirection());
        if (uv == null) {
            return;
        }
        int x = (int) Math.round(uv[0] * b.width());
        int y = (int) Math.round(uv[1] * b.height());
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            CdpClient c = b.cdp();
            if (c == null) {
                return;
            }
            if (scrollUp) {
                c.scroll(x, y, -400);
            } else if (scrollDown) {
                c.scroll(x, y, 400);
            } else {
                c.click(x, y);
            }
        });
    }

    // ------------------------------------------------------------------ Nachrichten

    private void announce(Screen sc, Mode mode) {
        Component msg = info("▶ Auf '" + sc.name + "' läuft jetzt: " + (mode == Mode.OBS ? "OBS" : sc.source));
        notifyNearby(sc, msg);
        if (sc.voiceStop == null && plugin.web() != null && sc.mp3 != null) {
            String url = plugin.getConfig().getString("web-audio.public-url", "") + "/#" + sc.name;
            notifyNearby(sc, Component.text("🔊 Ton im Browser: ", NamedTextColor.GRAY)
                    .append(Component.text("[hier klicken]", NamedTextColor.AQUA).clickEvent(ClickEvent.openUrl(url))));
        } else if (sc.voiceStop != null) {
            notifyNearby(sc, Component.text("🔊 Ton kommt über Simple Voice Chat aus der Leinwand.", NamedTextColor.GRAY));
        }
        if (mode == Mode.WEB) {
            notifyNearby(sc, Component.text("Klick auf die Leinwand = Mausklick · Schleichen + Rechts/Links = Scrollen", NamedTextColor.DARK_GRAY));
        }
    }

    private void notifyNearby(Screen sc, Component msg) {
        for (Player p : viewers(sc)) {
            p.sendMessage(msg);
        }
    }

    static Component info(String s) {
        return Component.text("[AleArena] ", NamedTextColor.GREEN).append(Component.text(s, NamedTextColor.WHITE));
    }

    static Component err(String s) {
        return Component.text("[AleArena] ", NamedTextColor.RED).append(Component.text(s, NamedTextColor.GRAY));
    }
}

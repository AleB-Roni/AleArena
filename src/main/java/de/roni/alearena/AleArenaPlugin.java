package de.roni.alearena;

import org.bukkit.NamespacedKey;
import org.bukkit.command.PluginCommand;
import org.bukkit.map.MapPalette;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.util.StringJoiner;

public final class AleArenaPlugin extends JavaPlugin {

    private ScreenManager screens;
    private PaletteConverter palette;
    private NamespacedKey key;
    private AudioOut voice;
    private WebServer web;

    @Override
    @SuppressWarnings({"deprecation", "removal"})
    public void onEnable() {
        saveDefaultConfig();
        key = new NamespacedKey(this, "screen");
        palette = new PaletteConverter(MapPalette::matchColor, getConfig().getBoolean("dithering", true));

        if (getConfig().getBoolean("voicechat.enabled", true) && getServer().getPluginManager().getPlugin("voicechat") != null) {
            try {
                voice = (AudioOut) Class.forName("de.roni.alearena.VoiceBridge")
                        .getMethod("register", AleArenaPlugin.class).invoke(null, this);
            } catch (Throwable t) {
                getLogger().warning("Simple Voice Chat gefunden, aber Anbindung fehlgeschlagen: " + t);
            }
        } else {
            getLogger().info("Simple Voice Chat nicht installiert – Ton ingame nicht verfügbar.");
        }

        if (getConfig().getBoolean("web-audio.enabled", false)) {
            web = new WebServer(name -> {
                Screen sc = screens.get(name);
                return sc == null ? null : sc.mp3;
            }, this::screensJson);
            try {
                web.start(getConfig().getInt("web-audio.port", 8765));
                getLogger().info("Browser-Ton auf Port " + getConfig().getInt("web-audio.port", 8765));
            } catch (IOException e) {
                getLogger().warning("Webserver konnte nicht starten: " + e.getMessage());
                web = null;
            }
        }

        screens = new ScreenManager(this);
        screens.load();

        ArenaCommand cmd = new ArenaCommand(this);
        PluginCommand pc = getCommand("arena");
        if (pc != null) {
            pc.setExecutor(cmd);
            pc.setTabCompleter(cmd);
        }
        getServer().getPluginManager().registerEvents(new ScreenListener(this), this);
        getServer().getScheduler().runTaskTimer(this, screens::tick, 1L, 1L);
    }

    @Override
    public void onDisable() {
        if (screens != null) {
            screens.stopAll();
        }
        if (web != null) {
            web.stop();
        }
    }

    private String screensJson() {
        StringJoiner j = new StringJoiner(",", "[", "]");
        for (Screen sc : screens.all()) {
            j.add("{\"name\":\"" + sc.name + "\",\"audio\":" + (sc.mp3 != null)
                    + ",\"source\":" + CdpClient.json(sc.source == null ? "" : sc.source) + "}");
        }
        return j.toString();
    }

    ScreenManager screens() {
        return screens;
    }

    PaletteConverter palette() {
        return palette;
    }

    NamespacedKey key() {
        return key;
    }

    AudioOut voice() {
        return voice;
    }

    WebServer web() {
        return web;
    }
}

package de.roni.alearena;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

final class ArenaCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBS = List.of("create", "remove", "web", "play", "obs", "stop", "list",
            "go", "type", "key", "scroll", "back", "reload", "link", "help");
    private static final List<String> KEYS = List.of("enter", "esc", "tab", "backspace", "space", "up", "down",
            "left", "right", "f", "k", "m", "f11");

    private final AleArenaPlugin plugin;

    ArenaCommand(AleArenaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (a.length == 0 || a[0].equalsIgnoreCase("help")) {
            help(s);
            return true;
        }
        String sub = a[0].toLowerCase(Locale.ROOT);
        ScreenManager sm = plugin.screens();
        switch (sub) {
            case "create" -> {
                if (!admin(s)) return true;
                if (!(s instanceof Player p)) {
                    s.sendMessage(ScreenManager.err("Nur ingame."));
                    return true;
                }
                if (a.length < 4) {
                    s.sendMessage(ScreenManager.err("/arena create <name> <breite> <höhe>   (z. B. 8 5)"));
                    return true;
                }
                try {
                    String e = sm.create(p, a[1], Integer.parseInt(a[2]), Integer.parseInt(a[3]));
                    s.sendMessage(e == null
                            ? ScreenManager.info("Leinwand '" + a[1].toLowerCase(Locale.ROOT) + "' gebaut. Jetzt: /arena web " + a[1].toLowerCase(Locale.ROOT) + " <url>")
                            : ScreenManager.err(e));
                } catch (NumberFormatException ex) {
                    s.sendMessage(ScreenManager.err("Breite und Höhe müssen Zahlen sein."));
                }
            }
            case "remove" -> {
                if (!admin(s) || a.length < 2) return usage(s, "/arena remove <name>");
                s.sendMessage(sm.remove(a[1]) ? ScreenManager.info("Leinwand entfernt.") : ScreenManager.err("Unbekannte Leinwand."));
            }
            case "web", "play" -> {
                if (!admin(s)) return true;
                if (a.length < 3) return usage(s, "/arena " + sub + " <name> <url>");
                Screen sc = screen(s, a[1]);
                if (sc == null) return true;
                String url = a[2];
                if (sub.equals("web") && !url.matches("(?i)^(https?|file)://.*")) {
                    url = "https://" + url;
                }
                sm.play(s, sc, sub.equals("web") ? ScreenManager.Mode.WEB : ScreenManager.Mode.URL, url);
            }
            case "obs" -> {
                if (!admin(s)) return true;
                if (a.length < 2) return usage(s, "/arena obs <name>");
                Screen sc = screen(s, a[1]);
                if (sc != null) sm.play(s, sc, ScreenManager.Mode.OBS, null);
            }
            case "stop" -> {
                if (!admin(s)) return true;
                if (a.length < 2) return usage(s, "/arena stop <name>");
                Screen sc = screen(s, a[1]);
                if (sc != null) {
                    sm.stop(sc, true);
                    s.sendMessage(ScreenManager.info("Gestoppt."));
                }
            }
            case "list" -> {
                if (sm.all().isEmpty()) {
                    s.sendMessage(ScreenManager.info("Noch keine Leinwand. /arena create <name> <breite> <höhe>"));
                }
                for (Screen sc : sm.all()) {
                    String state = sc.starting ? "startet …" : sc.session != null ? "▶ " + sc.source : "aus";
                    s.sendMessage(Component.text(" • " + sc.name, NamedTextColor.AQUA)
                            .append(Component.text(" " + sc.cols + "x" + sc.rows + " – " + state, NamedTextColor.GRAY)));
                }
            }
            case "go" -> browser(s, a, 3, "/arena go <name> <url>", c -> {
                String url = a[2].matches("(?i)^(https?|file)://.*") ? a[2] : "https://" + a[2];
                c.navigate(url);
            });
            case "type" -> browser(s, a, 3, "/arena type <name> <text>",
                    c -> c.type(String.join(" ", Arrays.copyOfRange(a, 2, a.length))));
            case "key" -> browser(s, a, 3, "/arena key <name> <taste>  (" + String.join(", ", KEYS) + ")", c -> {
                if (!c.key(a[2])) {
                    s.sendMessage(ScreenManager.err("Unbekannte Taste. Möglich: " + String.join(", ", KEYS)));
                }
            });
            case "scroll" -> browser(s, a, 3, "/arena scroll <name> <up|down>",
                    c -> c.scroll(640, 360, a[2].equalsIgnoreCase("up") || a[2].equalsIgnoreCase("hoch") ? -500 : 500));
            case "back" -> browser(s, a, 2, "/arena back <name>", CdpClient::back);
            case "reload" -> browser(s, a, 2, "/arena reload <name>", CdpClient::reload);
            case "link" -> {
                if (plugin.web() == null) {
                    s.sendMessage(ScreenManager.err("Browser-Ton ist aus (web-audio.enabled in der config.yml)."));
                } else {
                    s.sendMessage(ScreenManager.info("Ton im Browser: " + plugin.getConfig().getString("web-audio.public-url")));
                }
            }
            default -> help(s);
        }
        return true;
    }

    private void browser(CommandSender s, String[] a, int min, String usage, Consumer<CdpClient> action) {
        if (!s.hasPermission("alearena.control")) {
            s.sendMessage(ScreenManager.err("Keine Berechtigung."));
            return;
        }
        if (a.length < min) {
            usage(s, usage);
            return;
        }
        Screen sc = screen(s, a[1]);
        if (sc == null) return;
        BrowserHost b = sc.browser;
        if (b == null || b.cdp() == null) {
            s.sendMessage(ScreenManager.err("Auf dieser Leinwand läuft kein Browser (/arena web …)."));
            return;
        }
        CdpClient c = b.cdp();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> action.accept(c));
    }

    private Screen screen(CommandSender s, String name) {
        Screen sc = plugin.screens().get(name);
        if (sc == null) {
            s.sendMessage(ScreenManager.err("Unbekannte Leinwand '" + name + "'. /arena list"));
        }
        return sc;
    }

    private boolean admin(CommandSender s) {
        if (s.hasPermission("alearena.admin")) {
            return true;
        }
        s.sendMessage(ScreenManager.err("Keine Berechtigung."));
        return false;
    }

    private boolean usage(CommandSender s, String u) {
        s.sendMessage(ScreenManager.err(u));
        return true;
    }

    private void help(CommandSender s) {
        String[] lines = {
                "/arena create <name> <breite> <höhe> – Leinwand bauen (auf Block unten links schauen)",
                "/arena web <name> <url> – Webseite im Server-Browser anzeigen",
                "/arena play <name> <url> – Stream-URL (m3u8, YouTube, Twitch …) direkt",
                "/arena obs <name> – Bild/Ton per OBS vom PC empfangen",
                "/arena stop <name> · /arena list · /arena remove <name>",
                "/arena go|type|key|scroll|back|reload <name> … – Browser steuern",
                "Klick auf die Leinwand = Mausklick, Schleichen + Klick = Scrollen"
        };
        s.sendMessage(Component.text("⚽ AleArena", NamedTextColor.GREEN));
        for (String l : lines) {
            s.sendMessage(Component.text(" " + l, NamedTextColor.GRAY));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) {
            filter(SUBS, a[0], out);
        } else if (a.length == 2 && !a[0].equalsIgnoreCase("create")) {
            List<String> names = new ArrayList<>();
            plugin.screens().all().forEach(sc -> names.add(sc.name));
            filter(names, a[1], out);
        } else if (a.length == 3 && a[0].equalsIgnoreCase("key")) {
            filter(KEYS, a[2], out);
        } else if (a.length == 3 && a[0].equalsIgnoreCase("scroll")) {
            filter(List.of("up", "down"), a[2], out);
        } else if (a.length == 3 && a[0].equalsIgnoreCase("create")) {
            out.add("8");
        } else if (a.length == 4 && a[0].equalsIgnoreCase("create")) {
            out.add("5");
        }
        return out;
    }

    private static void filter(List<String> src, String prefix, List<String> out) {
        String p = prefix.toLowerCase(Locale.ROOT);
        for (String x : src) {
            if (x.toLowerCase(Locale.ROOT).startsWith(p)) {
                out.add(x);
            }
        }
    }
}

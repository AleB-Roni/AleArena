package de.roni.alearena;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.function.Supplier;

/** Optionaler Browser-Ton für Freunde ohne Simple-Voice-Chat-Mod. */
final class WebServer {

    private final Function<String, AudioHub> hubs;
    private final Supplier<String> screensJson;
    private HttpServer server;
    private ExecutorService pool;

    WebServer(Function<String, AudioHub> hubs, Supplier<String> screensJson) {
        this.hubs = hubs;
        this.screensJson = screensJson;
    }

    void start(int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        pool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "AleArena-Web");
            t.setDaemon(true);
            return t;
        });
        server.setExecutor(pool);
        server.createContext("/", this::index);
        server.createContext("/api/screens", ex -> send(ex, 200, "application/json", screensJson.get().getBytes(StandardCharsets.UTF_8)));
        server.createContext("/audio/", this::audio);
        server.start();
    }

    private void index(HttpExchange ex) throws IOException {
        try (InputStream in = WebServer.class.getResourceAsStream("/web/index.html")) {
            byte[] body = in == null ? "AleArena".getBytes(StandardCharsets.UTF_8) : in.readAllBytes();
            send(ex, 200, "text/html; charset=utf-8", body);
        }
    }

    private void audio(HttpExchange ex) throws IOException {
        String name = ex.getRequestURI().getPath().substring("/audio/".length()).replace(".mp3", "");
        AudioHub hub = hubs.apply(name);
        if (hub == null) {
            send(ex, 404, "text/plain", "Kein Ton".getBytes(StandardCharsets.UTF_8));
            return;
        }
        ex.getResponseHeaders().set("Content-Type", "audio/mpeg");
        ex.getResponseHeaders().set("Cache-Control", "no-cache, no-store");
        ex.sendResponseHeaders(200, 0);
        try (OutputStream out = ex.getResponseBody()) {
            hub.serve(out);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException ignored) {
            // Browser hat geschlossen
        }
    }

    private static void send(HttpExchange ex, int code, String type, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(code, body.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
        }
    }

    void stop() {
        if (server != null) {
            server.stop(0);
        }
        if (pool != null) {
            pool.shutdownNow();
        }
    }
}

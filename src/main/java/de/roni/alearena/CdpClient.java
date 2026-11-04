package de.roni.alearena;

import java.io.IOException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Minimaler Chrome-DevTools-Client: Klicken, Scrollen, Tippen, Tasten, Navigieren. */
final class CdpClient implements WebSocket.Listener {

    private static final Pattern PAGE_WS = Pattern.compile(
            "\"type\"\\s*:\\s*\"page\"[^}]*?\"webSocketDebuggerUrl\"\\s*:\\s*\"(ws://[^\"]+)\"", Pattern.DOTALL);
    private static final Pattern ANY_WS = Pattern.compile("\"webSocketDebuggerUrl\"\\s*:\\s*\"(ws://[^\"]+/page/[^\"]+)\"");

    private final AtomicInteger ids = new AtomicInteger();
    private WebSocket ws;

    static CdpClient connect(int port, long timeoutMs) throws IOException, InterruptedException {
        HttpClient http = HttpClient.newBuilder()
                .proxy(ProxySelector.of(null))
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        long until = System.currentTimeMillis() + timeoutMs;
        String wsUrl = null;
        while (System.currentTimeMillis() < until && wsUrl == null) {
            try {
                String body = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/json/list")).build(),
                        HttpResponse.BodyHandlers.ofString()).body();
                Matcher m = PAGE_WS.matcher(body);
                if (m.find()) {
                    wsUrl = m.group(1);
                } else {
                    Matcher m2 = ANY_WS.matcher(body);
                    if (m2.find()) {
                        wsUrl = m2.group(1);
                    }
                }
            } catch (IOException ignored) {
            }
            if (wsUrl == null) {
                Thread.sleep(300);
            }
        }
        if (wsUrl == null) {
            throw new IOException("Chrome-DevTools nicht erreichbar (Port " + port + ")");
        }
        CdpClient c = new CdpClient();
        try {
            c.ws = http.newWebSocketBuilder().buildAsync(URI.create(wsUrl), c).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IOException("DevTools-Verbindung fehlgeschlagen: " + e.getMessage(), e);
        }
        return c;
    }

    synchronized void send(String method, String paramsJson) {
        if (ws == null) {
            return;
        }
        String msg = "{\"id\":" + ids.incrementAndGet() + ",\"method\":\"" + method + "\",\"params\":" + paramsJson + "}";
        try {
            ws.sendText(msg, true).get(5, TimeUnit.SECONDS);
        } catch (Exception ignored) {
        }
    }

    void click(int x, int y) {
        send("Input.dispatchMouseEvent", "{\"type\":\"mouseMoved\",\"x\":" + x + ",\"y\":" + y + "}");
        send("Input.dispatchMouseEvent", "{\"type\":\"mousePressed\",\"x\":" + x + ",\"y\":" + y + ",\"button\":\"left\",\"clickCount\":1}");
        send("Input.dispatchMouseEvent", "{\"type\":\"mouseReleased\",\"x\":" + x + ",\"y\":" + y + ",\"button\":\"left\",\"clickCount\":1}");
    }

    void scroll(int x, int y, int deltaY) {
        send("Input.dispatchMouseEvent", "{\"type\":\"mouseWheel\",\"x\":" + x + ",\"y\":" + y + ",\"deltaX\":0,\"deltaY\":" + deltaY + "}");
    }

    void type(String text) {
        send("Input.insertText", "{\"text\":" + json(text) + "}");
    }

    /** Unterstützte Tasten: enter, esc, tab, backspace, space, up, down, left, right, f, k, m, f11. */
    boolean key(String name) {
        String key;
        String code;
        int vk;
        switch (name.toLowerCase()) {
            case "enter" -> { key = "Enter"; code = "Enter"; vk = 13; }
            case "esc", "escape" -> { key = "Escape"; code = "Escape"; vk = 27; }
            case "tab" -> { key = "Tab"; code = "Tab"; vk = 9; }
            case "backspace" -> { key = "Backspace"; code = "Backspace"; vk = 8; }
            case "space", "leertaste" -> { key = " "; code = "Space"; vk = 32; }
            case "up", "hoch" -> { key = "ArrowUp"; code = "ArrowUp"; vk = 38; }
            case "down", "runter" -> { key = "ArrowDown"; code = "ArrowDown"; vk = 40; }
            case "left", "links" -> { key = "ArrowLeft"; code = "ArrowLeft"; vk = 37; }
            case "right", "rechts" -> { key = "ArrowRight"; code = "ArrowRight"; vk = 39; }
            case "f" -> { key = "f"; code = "KeyF"; vk = 70; }
            case "k" -> { key = "k"; code = "KeyK"; vk = 75; }
            case "m" -> { key = "m"; code = "KeyM"; vk = 77; }
            case "f11" -> { key = "F11"; code = "F11"; vk = 122; }
            default -> { return false; }
        }
        String text = key.length() == 1 ? ",\"text\":" + json(key) : "";
        send("Input.dispatchKeyEvent", "{\"type\":\"keyDown\",\"key\":" + json(key) + ",\"code\":\"" + code
                + "\",\"windowsVirtualKeyCode\":" + vk + text + "}");
        send("Input.dispatchKeyEvent", "{\"type\":\"keyUp\",\"key\":" + json(key) + ",\"code\":\"" + code
                + "\",\"windowsVirtualKeyCode\":" + vk + "}");
        return true;
    }

    void navigate(String url) {
        send("Page.navigate", "{\"url\":" + json(url) + "}");
    }

    void back() {
        send("Runtime.evaluate", "{\"expression\":\"history.back()\"}");
    }

    void reload() {
        send("Page.reload", "{}");
    }

    void close() {
        WebSocket w = ws;
        ws = null;
        if (w != null) {
            w.abort();
        }
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        webSocket.request(1); // Antworten interessieren uns nicht
        return null;
    }

    static String json(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        return b.append('"').toString();
    }
}

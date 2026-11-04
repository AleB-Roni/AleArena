package de.roni.alearena;

import org.bukkit.entity.Player;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;

/** Zeichnet eine 128x128-Kachel der Leinwand auf eine Karte. */
final class TileRenderer extends MapRenderer {

    private volatile byte[] data;

    TileRenderer(byte[] initial) {
        super(false);
        this.data = initial;
    }

    void setData(byte[] data) {
        this.data = data;
    }

    @Override
    @SuppressWarnings({"deprecation", "removal"})
    public void render(MapView map, MapCanvas canvas, Player player) {
        byte[] d = data;
        for (int y = 0; y < 128; y++) {
            int o = y * 128;
            for (int x = 0; x < 128; x++) {
                canvas.setPixel(x, y, d[o + x]);
            }
        }
    }
}

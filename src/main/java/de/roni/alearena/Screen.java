package de.roni.alearena;

import org.bukkit.block.BlockFace;
import org.bukkit.map.MapView;
import org.bukkit.util.Vector;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Eine Leinwand aus cols x rows Karten an einer Wand. */
final class Screen {

    final String name;
    final UUID worldId;
    final int tx;
    final int ty;
    final int tz;
    final BlockFace face;
    final int cols;
    final int rows;
    final int[] mapIds;
    final List<UUID> frameIds = new ArrayList<>();

    // Laufzeit
    final MapView[] views;
    final TileRenderer[] renderers;
    final byte[][] lastSent;
    final ArrayDeque<StreamSession.Frame> queue = new ArrayDeque<>();
    volatile StreamSession session;
    volatile BrowserHost browser;
    volatile String source;
    volatile Runnable voiceStop;
    volatile PcmBuffer pcm;
    volatile AudioHub mp3;
    volatile boolean starting;
    volatile boolean obs;

    Screen(String name, UUID worldId, int tx, int ty, int tz, BlockFace face, int cols, int rows, int[] mapIds) {
        this.name = name;
        this.worldId = worldId;
        this.tx = tx;
        this.ty = ty;
        this.tz = tz;
        this.face = face;
        this.cols = cols;
        this.rows = rows;
        this.mapIds = mapIds;
        this.views = new MapView[cols * rows];
        this.renderers = new TileRenderer[cols * rows];
        this.lastSent = new byte[cols * rows][];
    }

    Vector normal() {
        return new Vector(face.getModX(), 0, face.getModZ());
    }

    /** "Rechts" aus Sicht eines Spielers, der auf die Wand schaut. */
    Vector right() {
        return new Vector(face.getModZ(), 0, -face.getModX());
    }

    /** Unten-links-Ecke der Bildfläche (Weltkoordinaten). */
    Vector corner() {
        Vector r = right();
        return new Vector(
                tx + (r.getX() < 0 ? 1 : 0) + (face.getModX() > 0 ? 1 : 0),
                ty,
                tz + (r.getZ() < 0 ? 1 : 0) + (face.getModZ() > 0 ? 1 : 0));
    }

    Vector center() {
        return corner().add(right().multiply(cols / 2.0)).add(new Vector(0, rows / 2.0, 0)).add(normal().multiply(0.3));
    }

    /**
     * Wo trifft der Blick die Leinwand? Rückgabe {u, v} in 0..1 (v=0 oben) oder null.
     */
    double[] hit(Vector eye, Vector dir) {
        Vector n = normal();
        Vector c = corner();
        double denom = n.dot(dir);
        if (denom >= -1e-6) {
            return null; // schaut nicht auf die Bildseite
        }
        double t = n.dot(c.clone().subtract(eye)) / denom;
        if (t < 0 || t > 64) {
            return null;
        }
        Vector p = eye.clone().add(dir.clone().multiply(t));
        double u = right().dot(p.clone().subtract(c)) / cols;
        double v = 1.0 - (p.getY() - c.getY()) / rows;
        if (u < 0 || u > 1 || v < 0 || v > 1) {
            return null;
        }
        return new double[]{u, v};
    }

    boolean playing() {
        return session != null || starting;
    }
}

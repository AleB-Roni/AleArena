package de.roni.alearena;

/**
 * Wandelt RGB24-Frames in Minecraft-Kartenfarben um.
 * Nutzt eine 15-Bit-Lookup-Tabelle (32768 Einträge) plus optionales 4x4-Bayer-Dithering,
 * damit Verläufe (Rasen, Himmel, Trikots) nicht so stark "streifen".
 */
public final class PaletteConverter {

    @FunctionalInterface
    public interface ColorMatcher {
        byte match(int r, int g, int b);
    }

    private static final int[][] BAYER = {
            {0, 8, 2, 10},
            {12, 4, 14, 6},
            {3, 11, 1, 9},
            {15, 7, 13, 5}
    };

    private final byte[] lut = new byte[32768];
    private final int[] ditherOffset = new int[16];
    private final boolean dithering;
    private final byte black;

    public PaletteConverter(ColorMatcher matcher, boolean dithering) {
        this.dithering = dithering;
        for (int i = 0; i < 32768; i++) {
            int r = ((i >> 10) & 31) << 3 | 4;
            int g = ((i >> 5) & 31) << 3 | 4;
            int b = (i & 31) << 3 | 4;
            lut[i] = matcher.match(r, g, b);
        }
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) {
                // Bereich ca. -12..+11 – passt zu den groben Stufen der Kartenpalette
                ditherOffset[y * 4 + x] = (int) Math.round((BAYER[y][x] - 7.5) * 1.5);
            }
        }
        this.black = matcher.match(0, 0, 0);
    }

    public byte black() {
        return black;
    }

    public void convert(byte[] rgb, byte[] out, int width, int height) {
        int p = 0;
        for (int y = 0; y < height; y++) {
            int row = (y & 3) * 4;
            for (int x = 0; x < width; x++, p++) {
                int i = p * 3;
                int r = rgb[i] & 0xFF;
                int g = rgb[i + 1] & 0xFF;
                int b = rgb[i + 2] & 0xFF;
                if (dithering) {
                    int d = ditherOffset[row + (x & 3)];
                    r = clamp(r + d);
                    g = clamp(g + d);
                    b = clamp(b + d);
                }
                out[p] = lut[((r >> 3) << 10) | ((g >> 3) << 5) | (b >> 3)];
            }
        }
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }
}

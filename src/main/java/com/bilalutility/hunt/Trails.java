package com.bilalutility.hunt;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Long rows of old chunks. Another player flying leaves a band of old chunks behind; following the
 * band to its end shows where they were going. An end is "open" when the chunks beyond it are not
 * known yet (go there), and "closed" when new chunks were seen right after it (the row really stops).
 */
public final class Trails {
    private Trails() {}

    private static final String[] AXES = {"E-W", "SE-NW", "N-S", "NE-SW"};

    public static final class Trail {
        public int n;
        public double len;
        public int ax, az, bx, bz;           // chunk coordinates of the two ends
        public double ux, uz;                // direction from end A to end B
        public boolean openA, openB;
        public double mx, mz;                // middle in blocks

        public String axis() {
            double deg = Math.toDegrees(Math.atan2(uz, ux));
            if (deg < 0) deg += 180;
            if (deg >= 180) deg -= 180;
            return AXES[(int) Math.round(deg / 45.0) % 4];
        }

        public int blockAX() { return ax * 16 + 8; }
        public int blockAZ() { return az * 16 + 8; }
        public int blockBX() { return bx * 16 + 8; }
        public int blockBZ() { return bz * 16 + 8; }

        public double distanceTo(double px, double pz) {
            return Math.min(Math.hypot(blockAX() - px, blockAZ() - pz), Math.hypot(blockBX() - px, blockBZ() - pz));
        }

        /** Short text for the HUD and commands. */
        public String describe(double px, double pz) {
            boolean aCloser = Math.hypot(blockAX() - px, blockAZ() - pz) <= Math.hypot(blockBX() - px, blockBZ() - pz);
            String a = "end1 " + blockAX() + "," + blockAZ() + (openA ? " open" : " closed");
            String b = "end2 " + blockBX() + "," + blockBZ() + (openB ? " open" : " closed");
            return axis() + " row, " + Math.round(len) + " chunks long, " + (aCloser ? a + ", " + b : b + ", " + a);
        }
    }

    public static List<Trail> find(String dim, List<HotSpots.Cluster> clusters) {
        Map<Long, Byte> m = ChunkStore.map(dim);
        List<Trail> out = new ArrayList<>();
        for (HotSpots.Cluster c : clusters) {
            if (!c.isTrail() || c.nearSpawn()) continue;
            Trail t = new Trail();
            t.n = c.n;
            t.len = c.len;
            t.ax = c.xs[c.ia]; t.az = c.zs[c.ia];
            t.bx = c.xs[c.ib]; t.bz = c.zs[c.ib];
            t.ux = c.ux; t.uz = c.uz;
            t.mx = c.mx * 16 + 8; t.mz = c.mz * 16 + 8;
            t.openA = !endsInNew(m, t.ax, t.az, -c.ux, -c.uz);
            t.openB = !endsInNew(m, t.bx, t.bz, c.ux, c.uz);
            out.add(t);
        }
        out.sort((p, q) -> Double.compare(q.len, p.len));
        return out;
    }

    private static boolean endsInNew(Map<Long, Byte> m, int x, int z, double dx, double dz) {
        for (int k = 1; k <= 3; k++) {
            Byte s = m.get(ChunkStore.key((int) Math.round(x + dx * k), (int) Math.round(z + dz * k)));
            if (s != null && s == ChunkStore.NEW) return true;
        }
        return false;
    }

    public static Trail nearest(List<Trail> trails, double px, double pz) {
        Trail best = null;
        double bd = Double.MAX_VALUE;
        for (Trail t : trails) {
            double d = t.distanceTo(px, pz);
            if (d < bd) { bd = d; best = t; }
        }
        return best;
    }
}

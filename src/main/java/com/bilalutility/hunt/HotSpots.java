package com.bilalutility.hunt;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Groups old chunks that touch each other and tells compact patches (bases) from long rows (trails). */
public final class HotSpots {
    private HotSpots() {}

    /** A group of old chunks that touch each other, with its shape measured. */
    public static final class Cluster {
        public final int[] xs, zs;
        public final int n;
        public double mx, mz;      // middle, in chunks
        public double ux = 1, uz;  // long axis, unit vector
        public double len = 1;     // length along the long axis, in chunks
        public double elong = 1;   // long axis / short axis
        public int ia, ib;         // indices of the two ends along the axis

        Cluster(int[] xs, int[] zs) {
            this.xs = xs; this.zs = zs; this.n = xs.length;
            double sx = 0, sz = 0;
            for (int i = 0; i < n; i++) { sx += xs[i]; sz += zs[i]; }
            mx = sx / n; mz = sz / n;
            if (n < 2) return;
            double sxx = 0, szz = 0, sxz = 0;
            for (int i = 0; i < n; i++) {
                double dx = xs[i] - mx, dz = zs[i] - mz;
                sxx += dx * dx; szz += dz * dz; sxz += dx * dz;
            }
            sxx /= n; szz /= n; sxz /= n;
            double theta = 0.5 * Math.atan2(2 * sxz, sxx - szz);
            ux = Math.cos(theta); uz = Math.sin(theta);
            double common = Math.sqrt(((sxx - szz) / 2) * ((sxx - szz) / 2) + sxz * sxz);
            double l1 = (sxx + szz) / 2 + common, l2 = (sxx + szz) / 2 - common;
            elong = Math.sqrt(Math.max(l1, 0)) / Math.max(0.6, Math.sqrt(Math.max(l2, 0)));
            double pmin = Double.MAX_VALUE, pmax = -Double.MAX_VALUE;
            for (int i = 0; i < n; i++) {
                double p = (xs[i] - mx) * ux + (zs[i] - mz) * uz;
                if (p < pmin) { pmin = p; ia = i; }
                if (p > pmax) { pmax = p; ib = i; }
            }
            len = pmax - pmin + 1;
        }

        public boolean nearSpawn() {
            return Math.hypot(mx * 16 + 8, mz * 16 + 8) < HuntConfig.d.ignoreSpawn;
        }

        /** Near the X axis, the Z axis or a diagonal: the roads players fly out on. */
        public boolean onHighway() {
            if (!HuntConfig.d.ignoreHighways) return false;
            double x = mx * 16 + 8, z = mz * 16 + 8;
            double w = HuntConfig.d.highwayWidth;
            double diag = Math.min(Math.abs(x - z), Math.abs(x + z)) / Math.sqrt(2);
            return Math.abs(z) < w || Math.abs(x) < w || diag < w;
        }

        public boolean ignored() {
            return nearSpawn() || onHighway();
        }

        public boolean isTrail() {
            return len >= HuntConfig.d.minTrail && elong >= HuntConfig.d.trailShape / 10.0;
        }
    }

    public static List<Cluster> clusters(String dim) {
        Map<Long, Byte> m = ChunkStore.map(dim);
        Set<Long> old = new HashSet<>();
        for (Map.Entry<Long, Byte> e : m.entrySet()) if (e.getValue() == ChunkStore.OLD) old.add(e.getKey());

        List<Cluster> out = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (long start : old) {
            if (!seen.add(start)) continue;
            ArrayDeque<Long> queue = new ArrayDeque<>();
            List<Long> members = new ArrayList<>();
            queue.add(start);
            while (!queue.isEmpty()) {
                long k = queue.poll();
                members.add(k);
                int cx = ChunkStore.keyX(k), cz = ChunkStore.keyZ(k);
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dz == 0) continue;
                        long nk = ChunkStore.key(cx + dx, cz + dz);
                        if (old.contains(nk) && seen.add(nk)) queue.add(nk);
                    }
                }
            }
            int[] xs = new int[members.size()], zs = new int[members.size()];
            for (int i = 0; i < xs.length; i++) { xs[i] = ChunkStore.keyX(members.get(i)); zs[i] = ChunkStore.keyZ(members.get(i)); }
            out.add(new Cluster(xs, zs));
        }
        return out;
    }

    /** A compact patch of old chunks: the kind of shape a base leaves. */
    public static final class Spot {
        public final int x, z;   // block coordinates of the middle
        public final int size;   // old chunks in the patch
        Spot(int x, int z, int size) { this.x = x; this.z = z; this.size = size; }
    }

    public static List<Spot> spots(List<Cluster> clusters) {
        List<Spot> out = new ArrayList<>();
        for (Cluster c : clusters) {
            if (c.n < HuntConfig.d.minCluster || c.isTrail() || c.ignored()) continue;
            out.add(new Spot((int) Math.round(c.mx) * 16 + 8, (int) Math.round(c.mz) * 16 + 8, c.n));
        }
        out.sort((a, b) -> Integer.compare(b.size, a.size));
        return out;
    }
}

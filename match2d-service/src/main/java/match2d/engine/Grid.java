package match2d.engine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Dijkstra pe o grila de costuri, pentru miscare care ocoleste adversarii.
 *
 * <p>Grila are celule de 2 m. Fiecare adversar "stampileaza" un cost in jurul lui; drumul cel mai
 * ieftin il ocoleste. Doua lucruri il tin rapid si natural: daca linia dreapta e libera, nu mai
 * cautam deloc; dupa cautare, drumul e netezit (taiem colturile cat timp linia ramane libera).
 * Cautarea se opreste cand ajunge la tinta si foloseste distanta in linie dreapta ca estimare
 * (Dijkstra cu euristica admisibila = A*: acelasi drum optim, mai putine noduri).
 */
public final class Grid {
    static final double CELL = 2.0;
    static final int NX = (int) Math.ceil(Geo.L / CELL);
    static final int NY = (int) Math.ceil(Geo.W / CELL);
    private static final double DIAG = CELL * 1.41421356;
    private static final int[] NDI = {1, -1, 0, 0, 1, 1, -1, -1};
    private static final int[] NDJ = {0, 0, 1, -1, 1, -1, 1, -1};
    private static final double[] NSTEP = {CELL, CELL, CELL, CELL, DIAG, DIAG, DIAG, DIAG};

    final double[] c = new double[NX * NY];

    void clear() {
        Arrays.fill(c, 0.0);
    }

    void stamp(double x, double y, double radius, double weight) {
        int i0 = (int) ((x - radius) / CELL);
        int i1 = (int) ((x + radius) / CELL);
        int j0 = (int) ((y - radius) / CELL);
        int j1 = (int) ((y + radius) / CELL);
        if (i0 < 0) {
            i0 = 0;
        }
        if (j0 < 0) {
            j0 = 0;
        }
        if (i1 >= NX) {
            i1 = NX - 1;
        }
        if (j1 >= NY) {
            j1 = NY - 1;
        }
        double r2 = radius * radius;
        for (int j = j0; j <= j1; j++) {
            double dy = (j + 0.5) * CELL - y;
            int base = j * NX;
            double dy2 = dy * dy;
            for (int i = i0; i <= i1; i++) {
                double dx = (i + 0.5) * CELL - x;
                double d2 = dx * dx + dy2;
                if (d2 < r2) {      // colturile patratului sunt in afara cercului
                    double d = Math.sqrt(d2);
                    if (d < radius) {
                        c[base + i] += weight * (1.0 - d / radius);
                    }
                }
            }
        }
    }

    /** Costul maxim intalnit pe segmentul AB (esantionat din 1.4 in 1.4 m). */
    double lineMax(double ax, double ay, double bx, double by) {
        double dx = bx - ax;
        double dy = by - ay;
        double d = Math.sqrt(dx * dx + dy * dy);
        int n = (int) (d / 1.4) + 1;
        double m = 0.0;
        for (int k = 0; k <= n; k++) {
            double u = (double) k / n;
            int i = (int) ((ax + dx * u) / CELL);
            int j = (int) ((ay + dy * u) / CELL);
            if (i < 0) {
                i = 0;
            } else if (i >= NX) {
                i = NX - 1;
            }
            if (j < 0) {
                j = 0;
            } else if (j >= NY) {
                j = NY - 1;
            }
            double v = c[j * NX + i];
            if (v > m) {
                m = v;
            }
        }
        return m;
    }

    /** Un nod din coada cautarii: (estimarea totala, costul pana aici, celula), comparate in aceasta ordine. */
    private record Node(double f, double g, int cell) implements Comparable<Node> {
        @Override
        public int compareTo(Node o) {
            int a = Double.compare(f, o.f);
            if (a != 0) {
                return a;
            }
            int b = Double.compare(g, o.g);
            return b != 0 ? b : Integer.compare(cell, o.cell);
        }
    }

    /** Drumul de la (sx,sy) la (gx,gy) ca lista de puncte {x, y} (fara start, cu tinta la final). */
    List<double[]> path(double sx, double sy, double gx, double gy, double weight, double free) {
        final int margin = 6;
        List<double[]> out = new ArrayList<>();
        if (lineMax(sx, sy, gx, gy) <= free) {
            out.add(new double[] {gx, gy});
            return out;
        }
        int si = Math.min(NX - 1, Math.max(0, (int) (sx / CELL)));
        int sj = Math.min(NY - 1, Math.max(0, (int) (sy / CELL)));
        int gi = Math.min(NX - 1, Math.max(0, (int) (gx / CELL)));
        int gj = Math.min(NY - 1, Math.max(0, (int) (gy / CELL)));
        if (si == gi && sj == gj) {
            out.add(new double[] {gx, gy});
            return out;
        }
        int loI = Math.max(0, Math.min(si, gi) - margin);
        int hiI = Math.min(NX - 1, Math.max(si, gi) + margin);
        int loJ = Math.max(0, Math.min(sj, gj) - margin);
        int hiJ = Math.min(NY - 1, Math.max(sj, gj) + margin);
        int start = sj * NX + si;
        int goal = gj * NX + gi;
        double[] dist = new double[NX * NY];
        Arrays.fill(dist, 1e18);
        int[] prev = new int[NX * NY];
        dist[start] = 0.0;
        PriorityQueue<Node> heap = new PriorityQueue<>();
        heap.add(new Node(0.0, 0.0, start));
        boolean found = false;
        while (!heap.isEmpty()) {
            Node top = heap.poll();
            double g = top.g;
            int u = top.cell;
            if (u == goal) {
                found = true;
                break;
            }
            if (g > dist[u]) {
                continue;
            }
            int ui = u % NX;
            int uj = u / NX;
            for (int k = 0; k < 8; k++) {
                int vi = ui + NDI[k];
                int vj = uj + NDJ[k];
                if (vi < loI || vi > hiI || vj < loJ || vj > hiJ) {
                    continue;
                }
                int v = vj * NX + vi;
                double ng = g + NSTEP[k] * (1.0 + weight * c[v]);
                if (ng < dist[v]) {
                    dist[v] = ng;
                    prev[v] = u;
                    double hx = (vi - gi) * CELL;
                    double hy = (vj - gj) * CELL;
                    heap.add(new Node(ng + Math.sqrt(hx * hx + hy * hy), ng, v));
                }
            }
        }
        if (!found) {
            out.add(new double[] {gx, gy});
            return out;
        }
        List<Integer> cells = new ArrayList<>();
        int u = goal;
        double worst = 0.0;
        while (u != start) {
            cells.add(u);
            if (c[u] > worst) {
                worst = c[u];
            }
            u = prev[u];
        }
        int n = cells.size();
        double[][] pts = new double[n][];
        for (int k = 0; k < n; k++) {
            int cell = cells.get(n - 1 - k);
            pts[k] = new double[] {(cell % NX + 0.5) * CELL, (cell / NX + 0.5) * CELL};
        }
        pts[n - 1] = new double[] {gx, gy};
        // netezire: din punctul curent sarim la cel mai indepartat punct vizibil "ieftin"
        double limit = Math.max(free, 0.55 * worst);
        double ax = sx;
        double ay = sy;
        int i = 0;
        while (i < n) {
            int j = n - 1;
            while (j > i && lineMax(ax, ay, pts[j][0], pts[j][1]) > limit) {
                j--;
            }
            out.add(pts[j]);
            ax = pts[j][0];
            ay = pts[j][1];
            i = j + 1;
        }
        return out;
    }
}

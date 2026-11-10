package com.footballmanagergamesimulator.algorithms.geometry;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Codewars 1 kyu "Tiptoe through the circles".
 *
 * <p>Shortest route from A to B in the plane that never enters the open interior of any
 * forbidden circle. Touching a boundary (tangency) is legal. Returns -1.0 when no route exists.
 *
 * <p>Method: the optimal route is a chain of straight tangent segments and arcs on circle
 * boundaries. We therefore build a geometric graph whose nodes are A, B and every tangent
 * point, whose straight edges are tangent segments that no circle blocks, and whose curved
 * edges are arcs between angularly adjacent tangent points on the same circle that no other
 * circle covers. Dijkstra on that graph gives the answer.
 */
public final class TiptoeThroughCircles {

    /** Immutable 2D point, mirrors the structure the kata provides. */
    public record Point(double x, double y) {
    }

    /** Forbidden zone: center + radius, mirrors the structure the kata provides. */
    public record Circle(Point ctr, double r) {
    }

    /** Geometric tolerance: "strictly inside" means deeper than EPS below the boundary. */
    private static final double EPS = 1e-9;

    private TiptoeThroughCircles() {
    }

    /** A graph node: a point that lies either free (A, B) or on circle {@code circleIdx} at {@code angle}. */
    private static final class Node {
        final Point p;
        final int circleIdx;   // -1 for A and B
        final double angle;    // polar angle on the owning circle, unused for A and B
        final List<double[]> edges = new ArrayList<>(); // {neighbour index, length}

        Node(Point p, int circleIdx, double angle) {
            this.p = p;
            this.circleIdx = circleIdx;
            this.angle = angle;
        }
    }

    public static double shortestPath(Point a, Point b, List<Circle> circles) {
        // A route that starts or ends strictly inside a forbidden zone cannot exist.
        for (Circle c : circles) {
            if (dist(a, c.ctr()) < c.r() - EPS || dist(b, c.ctr()) < c.r() - EPS) {
                return -1.0;
            }
        }

        List<Node> nodes = new ArrayList<>();
        Node start = new Node(a, -1, 0);
        Node goal = new Node(b, -1, 0);
        nodes.add(start);
        nodes.add(goal);

        // 1) Straight candidate: A -> B directly.
        tryAddSegment(nodes, 0, 1, circles);

        // 2) Tangent points from A and from B to every circle.
        for (int i = 0; i < circles.size(); i++) {
            for (Node t : pointTangents(a, i, circles.get(i))) {
                nodes.add(t);
                tryAddSegment(nodes, 0, nodes.size() - 1, circles);
            }
            for (Node t : pointTangents(b, i, circles.get(i))) {
                nodes.add(t);
                tryAddSegment(nodes, 1, nodes.size() - 1, circles);
            }
        }

        // 3) Common tangents (external + internal) for every pair of circles.
        for (int i = 0; i < circles.size(); i++) {
            for (int j = i + 1; j < circles.size(); j++) {
                for (Node[] pair : pairTangents(i, circles.get(i), j, circles.get(j))) {
                    nodes.add(pair[0]);
                    nodes.add(pair[1]);
                    tryAddSegment(nodes, nodes.size() - 2, nodes.size() - 1, circles);
                }
            }
        }

        // 4) Arcs: on each circle, sort its nodes by angle and connect angular neighbours.
        addArcEdges(nodes, circles);

        return dijkstra(nodes);
    }

    /** Tangent points from an outside point to one circle: at base +/- acos(r/d) seen from the center. */
    private static List<Node> pointTangents(Point p, int ci, Circle c) {
        double d = dist(p, c.ctr());
        if (d < c.r() - EPS) {
            return List.of(); // point strictly inside: no tangent exists
        }
        double base = Math.atan2(p.y() - c.ctr().y(), p.x() - c.ctr().x());
        double offset = Math.acos(clamp(c.r() / Math.max(d, c.r())));
        return List.of(onCircle(c, ci, base + offset), onCircle(c, ci, base - offset));
    }

    /**
     * Common tangent segments between two circles.
     * External pair exists when neither circle is strictly inside the other (d >= |r1-r2|):
     * both touch points sit at the same polar angle base +/- acos((r1-r2)/d).
     * Internal pair exists only when the circles are disjoint (d >= r1+r2):
     * touch points sit at opposite angles, base +/- acos((r1+r2)/d) and that plus pi.
     */
    private static List<Node[]> pairTangents(int i, Circle c1, int j, Circle c2) {
        double d = dist(c1.ctr(), c2.ctr());
        if (d < EPS) {
            return List.of(); // concentric circles have no common tangent line
        }
        double base = Math.atan2(c2.ctr().y() - c1.ctr().y(), c2.ctr().x() - c1.ctr().x());
        List<Node[]> out = new ArrayList<>(4);
        if (d >= Math.abs(c1.r() - c2.r()) - EPS) {
            double phi = Math.acos(clamp((c1.r() - c2.r()) / d));
            for (int sign : new int[] {1, -1}) {
                out.add(new Node[] {onCircle(c1, i, base + sign * phi), onCircle(c2, j, base + sign * phi)});
            }
        }
        if (d >= c1.r() + c2.r() - EPS) {
            double phi = Math.acos(clamp((c1.r() + c2.r()) / d));
            for (int sign : new int[] {1, -1}) {
                out.add(new Node[] {onCircle(c1, i, base + sign * phi),
                        onCircle(c2, j, base + sign * phi + Math.PI)});
            }
        }
        return out;
    }

    /** Adds the straight edge u-v unless some circle's interior cuts the segment. */
    private static void tryAddSegment(List<Node> nodes, int u, int v, List<Circle> circles) {
        Point p = nodes.get(u).p;
        Point q = nodes.get(v).p;
        for (Circle c : circles) {
            if (distPointSegment(c.ctr(), p, q) < c.r() - EPS) {
                return; // segment dips strictly inside this forbidden zone
            }
        }
        double len = dist(p, q);
        nodes.get(u).edges.add(new double[] {v, len});
        nodes.get(v).edges.add(new double[] {u, len});
    }

    /**
     * For each circle: compute the forbidden angular intervals carved by every other circle,
     * then link angular neighbours with an arc edge when their gap avoids all intervals.
     * Chained gap-arcs compose into any longer arc, so neighbours are enough.
     */
    private static void addArcEdges(List<Node> nodes, List<Circle> circles) {
        int n = circles.size();
        List<List<Integer>> byCircle = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            byCircle.add(new ArrayList<>());
        }
        for (int k = 0; k < nodes.size(); k++) {
            if (nodes.get(k).circleIdx >= 0) {
                byCircle.get(nodes.get(k).circleIdx).add(k);
            }
        }
        for (int i = 0; i < n; i++) {
            List<Integer> ids = byCircle.get(i);
            if (ids.size() < 2) {
                continue;
            }
            List<double[]> forbidden = forbiddenIntervals(i, circles);
            if (forbidden == null) {
                continue; // the whole circumference lies inside another circle: no arc survives
            }
            List<Node> ref = nodes;
            ids.sort((x, y) -> Double.compare(norm(ref.get(x).angle), norm(ref.get(y).angle)));
            for (int k = 0; k < ids.size(); k++) {
                int u = ids.get(k);
                int v = ids.get((k + 1) % ids.size());
                double from = norm(nodes.get(u).angle);
                double span = norm(nodes.get(v).angle) - from;
                if (k == ids.size() - 1) {
                    span += 2 * Math.PI; // wrap-around gap closes the ring
                }
                if (arcFree(from, from + span, forbidden)) {
                    double len = circles.get(i).r() * span;
                    nodes.get(u).edges.add(new double[] {v, len});
                    nodes.get(v).edges.add(new double[] {u, len});
                }
            }
        }
    }

    /**
     * Angular intervals of circle i whose points lie strictly inside some other circle.
     * Law of cosines: a boundary point at angle t is inside circle j iff
     * cos(t - base) > (d^2 + ri^2 - rj^2) / (2 * d * ri).
     * Returns null when another circle swallows the whole circumference.
     */
    private static List<double[]> forbiddenIntervals(int i, List<Circle> circles) {
        Circle ci = circles.get(i);
        List<double[]> out = new ArrayList<>();
        for (int j = 0; j < circles.size(); j++) {
            if (j == i) {
                continue;
            }
            Circle cj = circles.get(j);
            double d = dist(ci.ctr(), cj.ctr());
            if (d >= ci.r() + cj.r() - EPS) {
                continue; // too far: no overlap with the circumference
            }
            if (d + ci.r() <= cj.r() + EPS) {
                return null; // circle i's boundary lies entirely inside circle j
            }
            if (d + cj.r() <= ci.r() + EPS) {
                continue; // circle j floats strictly inside circle i, never reaching the boundary
            }
            double base = Math.atan2(cj.ctr().y() - ci.ctr().y(), cj.ctr().x() - ci.ctr().x());
            double half = Math.acos(clamp((d * d + ci.r() * ci.r() - cj.r() * cj.r()) / (2 * d * ci.r())));
            out.add(new double[] {base - half, base + half});
        }
        return out;
    }

    /** An arc [from, to] survives when its overlap with every forbidden interval is negligible. */
    private static boolean arcFree(double from, double to, List<double[]> forbidden) {
        for (double[] f : forbidden) {
            double lo = norm(f[0]);
            double hi = lo + (f[1] - f[0]);
            for (int shift = -1; shift <= 1; shift++) {
                double overlap = Math.min(to, hi + shift * 2 * Math.PI) - Math.max(from, lo + shift * 2 * Math.PI);
                if (overlap > EPS) {
                    return false;
                }
            }
        }
        return true;
    }

    private static double dijkstra(List<Node> nodes) {
        double[] best = new double[nodes.size()];
        Arrays.fill(best, Double.POSITIVE_INFINITY);
        best[0] = 0;
        PriorityQueue<double[]> queue = new PriorityQueue<>((x, y) -> Double.compare(x[1], y[1]));
        queue.add(new double[] {0, 0});
        while (!queue.isEmpty()) {
            double[] cur = queue.poll();
            int u = (int) cur[0];
            if (cur[1] > best[u] + EPS) {
                continue; // stale queue entry, a shorter route already settled u
            }
            if (u == 1) {
                return best[1]; // goal settled: Dijkstra guarantees this is minimal
            }
            for (double[] e : nodes.get(u).edges) {
                int v = (int) e[0];
                if (best[u] + e[1] < best[v] - EPS) {
                    best[v] = best[u] + e[1];
                    queue.add(new double[] {v, best[v]});
                }
            }
        }
        return -1.0;
    }

    private static Node onCircle(Circle c, int idx, double angle) {
        return new Node(new Point(c.ctr().x() + c.r() * Math.cos(angle),
                c.ctr().y() + c.r() * Math.sin(angle)), idx, angle);
    }

    private static double dist(Point p, Point q) {
        return Math.hypot(p.x() - q.x(), p.y() - q.y());
    }

    /** Distance from point c to segment pq: clamp the projection to [0,1], then measure. */
    private static double distPointSegment(Point c, Point p, Point q) {
        double vx = q.x() - p.x();
        double vy = q.y() - p.y();
        double len2 = vx * vx + vy * vy;
        double t = len2 < EPS ? 0 : clamp01(((c.x() - p.x()) * vx + (c.y() - p.y()) * vy) / len2);
        return Math.hypot(c.x() - (p.x() + t * vx), c.y() - (p.y() + t * vy));
    }

    /** Maps any angle to [0, 2*pi). */
    private static double norm(double angle) {
        double a = angle % (2 * Math.PI);
        return a < 0 ? a + 2 * Math.PI : a;
    }

    private static double clamp(double v) {
        return Math.max(-1.0, Math.min(1.0, v));
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}

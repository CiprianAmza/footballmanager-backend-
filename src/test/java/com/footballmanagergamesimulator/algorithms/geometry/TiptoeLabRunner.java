package com.footballmanagergamesimulator.algorithms.geometry;

import java.util.List;

import com.footballmanagergamesimulator.algorithms.geometry.TiptoeThroughCircles.Circle;
import com.footballmanagergamesimulator.algorithms.geometry.TiptoeThroughCircles.Point;

/**
 * Hand-checkable scenarios for {@link TiptoeThroughCircles}: every expected value below
 * is derived analytically (tangent length sqrt(d^2-r^2), arc length r*angle), so a failure
 * points at the solver, not at the fixture.
 */
public final class TiptoeLabRunner {

    private static final double TOL = 1e-6;

    private TiptoeLabRunner() {
    }

    public static void main(String[] args) {
        // F1: empty plane -> the segment AB itself.
        check("open plane", run(p(0, 0), p(10, 0)), 10.0);

        // F1: a circle far from the segment changes nothing.
        check("circle off route", run(p(0, 0), p(10, 0), c(5, 5, 1)), 10.0);

        // F2: one unit circle centered on the midpoint of AB, d(A,center)=3.
        // Route = tangent sqrt(9-1) + arc (pi - 2*acos(1/3)) * 1 + tangent sqrt(9-1).
        check("one blocking circle", run(p(-3, 0), p(3, 0), c(0, 0, 1)),
                2 * Math.sqrt(8) + Math.PI - 2 * Math.acos(1.0 / 3));

        // F3: two unit circles at (+-2,0), A=(-6,0), B=(6,0). Optimal route climbs over both:
        // tangent sqrt(16-1), arc down to the top, straight y=1 bridge of length 4, mirror image.
        check("two blocking circles", run(p(-6, 0), p(6, 0), c(-2, 0, 1), c(2, 0, 1)),
                2 * Math.sqrt(15) + 2 * (Math.PI / 2 - Math.acos(0.25)) + 4);

        // F4: four overlapping unit circles at distance 1.2 from A seal it in -> no route.
        check("sealed ring", run(p(0, 0), p(10, 10),
                c(1.2, 0, 1), c(0, 1.2, 1), c(-1.2, 0, 1), c(0, -1.2, 1)), -1.0);

        // T8: A strictly inside a forbidden zone -> no route can even start.
        check("start inside a circle", run(p(0, 0), p(5, 5), c(0, 0, 2)), -1.0);

        // T8: A == B and the point is legal -> zero-length route.
        check("degenerate A equals B", run(p(1, 1), p(1, 1), c(4, 4, 1)), 0.0);

        System.out.println("all tiptoe checks passed");
    }

    private static double run(Point a, Point b, Circle... circles) {
        return TiptoeThroughCircles.shortestPath(a, b, List.of(circles));
    }

    private static void check(String name, double actual, double expected) {
        if (Math.abs(actual - expected) > TOL) {
            throw new AssertionError(name + ": expected " + expected + " but got " + actual);
        }
        System.out.printf("%-24s %.9f ok%n", name, actual);
    }

    private static Point p(double x, double y) {
        return new Point(x, y);
    }

    private static Circle c(double x, double y, double r) {
        return new Circle(new Point(x, y), r);
    }
}

package match2d.engine;

/** Tipareste valori din generator, ca sa fie comparate cu cele din Python (vezi tools/check_mt.py). */
public final class MtCheck {
    public static void main(String[] args) {
        long seed = Long.parseLong(args[0]);
        int count = Integer.parseInt(args[1]);
        Mt rng = new Mt(seed);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < count; i++) {
            out.append(Double.toHexString(rng.random())).append('\n');
        }
        for (int i = 0; i < count; i++) {
            out.append(Double.toHexString(rng.gauss(0.0, 1.0))).append('\n');
        }
        // amestecate, cum le cere motorul
        for (int i = 0; i < count; i++) {
            out.append(Double.toHexString(i % 3 == 0 ? rng.random() : rng.gauss(1.0, 0.3))).append('\n');
        }
        System.out.print(out);
    }
}

package match2d.engine;

/** Tipareste valorile functiilor portabile pe intrari fixe, ca sa fie comparate bit cu bit cu Python. */
public final class PMathCheck {
    public static void main(String[] args) {
        int count = Integer.parseInt(args[0]);
        Mt rng = new Mt(99);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < count; i++) {
            double a = (rng.random() - 0.5) * 24.0;
            double b = (rng.random() - 0.5) * 80.0;
            double c = (rng.random() - 0.5) * 1400.0;
            double u = rng.random();
            double y = (rng.random() - 0.5) * 160.0;
            double x = (rng.random() - 0.5) * 220.0;
            double wide = PMath.exp((rng.random() - 0.5) * 40.0);
            out.append(Double.toHexString(PMath.sin(a))).append(' ')
                    .append(Double.toHexString(PMath.cos(a))).append(' ')
                    .append(Double.toHexString(PMath.exp(b))).append(' ')
                    .append(Double.toHexString(PMath.exp(c))).append(' ')
                    .append(Double.toHexString(PMath.log(1.0 - u))).append(' ')
                    .append(Double.toHexString(PMath.log(wide))).append(' ')
                    .append(Double.toHexString(PMath.atan2(y, x))).append('\n');
        }
        System.out.print(out);
    }
}

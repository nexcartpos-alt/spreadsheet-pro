/**
 * Goal Seek: finds the number to type into one cell so that a formula in another cell gives a wanted result.
 * Uses the secant method and falls back to bracketing + bisection when that does not converge.
 */
public final class GoalSeek {

    public static final class Result {
        public boolean found;
        public double x;          // the value now in the changing cell
        public double value;      // what the target formula gives with it
        public int iterations;
        public String message = "";
        public String original = ""; // what the changing cell held before (to undo)
    }

    private GoalSeek() {
    }

    private static double eval(SpreadsheetModel m, int tr, int tc, int cr, int cc, double x) {
        m.setInput(cr, cc, java.math.BigDecimal.valueOf(x).stripTrailingZeros().toPlainString());
        Object v = m.getComputedValue(tr, tc);
        return v instanceof Double ? (Double) v : Double.NaN;
    }

    /** Solves and leaves the best value in the changing cell. Call {@link #restore} to undo. */
    public static Result solve(SpreadsheetModel m, int tr, int tc, double goal, int cr, int cc) {
        Result res = new Result();
        res.original = m.getInput(cr, cc);
        if (m.isProtected()) {
            res.message = "The sheet is protected.";
            return res;
        }
        if (!m.getInput(tr, tc).startsWith("=")) {
            res.message = "The cell to set must contain a formula.";
            return res;
        }
        if (res.original.startsWith("=")) {
            res.message = "The cell to change must contain a number, not a formula.";
            return res;
        }
        if (tr == cr && tc == cc) {
            res.message = "The cell to set and the cell to change must be different.";
            return res;
        }
        double x0 = 0;
        if (!res.original.isEmpty()) {
            try {
                x0 = Double.parseDouble(res.original.trim());
            } catch (NumberFormatException e) {
                res.message = "The cell to change must contain a number.";
                return res;
            }
        }
        double tol = 1e-9 * Math.max(1, Math.abs(goal));
        m.beginBatch();
        try {
            double f0 = eval(m, tr, tc, cr, cc, x0) - goal;
            res.iterations = 1;
            double bestX = x0;
            double bestAbs = Double.isNaN(f0) ? Double.MAX_VALUE : Math.abs(f0);
            boolean done = !Double.isNaN(f0) && Math.abs(f0) <= tol;

            // 1) secant method
            double xa = x0;
            double fa = f0;
            double xb = x0 + (Math.abs(x0) < 1e-6 ? 1 : Math.abs(x0) * 0.01);
            double fb = eval(m, tr, tc, cr, cc, xb) - goal;
            res.iterations++;
            for (int i = 0; i < 100 && !done; i++) {
                if (Double.isNaN(fb) || Double.isInfinite(fb)) break;
                if (Math.abs(fb) < bestAbs) {
                    bestAbs = Math.abs(fb);
                    bestX = xb;
                }
                if (Math.abs(fb) <= tol) {
                    done = true;
                    break;
                }
                if (fb == fa || Double.isNaN(fa)) break;
                double xc = xb - fb * (xb - xa) / (fb - fa);
                if (Double.isNaN(xc) || Double.isInfinite(xc) || Math.abs(xc) > 1e15) break;
                xa = xb;
                fa = fb;
                xb = xc;
                fb = eval(m, tr, tc, cr, cc, xb) - goal;
                res.iterations++;
            }
            if (!done && !Double.isNaN(fb) && Math.abs(fb) <= tol) {
                done = true;
                bestX = xb;
                bestAbs = Math.abs(fb);
            }

            // 2) look for a sign change around the start value, then bisect
            if (!done) {
                double step = Math.max(1, Math.abs(x0));
                double lo = Double.NaN;
                double hi = Double.NaN;
                double flo = 0;
                double fhi = 0;
                double prevX = x0;
                double prevF = f0;
                for (int k = 0; k < 60 && Double.isNaN(lo); k++) {
                    for (int dir = -1; dir <= 1; dir += 2) {
                        double x = x0 + dir * step;
                        double f = eval(m, tr, tc, cr, cc, x) - goal;
                        res.iterations++;
                        if (Double.isNaN(f)) continue;
                        if (Math.abs(f) < bestAbs) {
                            bestAbs = Math.abs(f);
                            bestX = x;
                        }
                        if (!Double.isNaN(prevF) && Math.signum(f) != Math.signum(prevF) && prevF != 0) {
                            lo = Math.min(x, prevX);
                            hi = Math.max(x, prevX);
                            flo = lo == x ? f : prevF;
                            fhi = hi == x ? f : prevF;
                            break;
                        }
                        if (Math.abs(f) <= tol) {
                            done = true;
                            break;
                        }
                    }
                    if (done) break;
                    step *= 2;
                }
                for (int i = 0; i < 200 && !done && !Double.isNaN(lo); i++) {
                    double mid = (lo + hi) / 2;
                    double f = eval(m, tr, tc, cr, cc, mid) - goal;
                    res.iterations++;
                    if (Double.isNaN(f)) break;
                    if (Math.abs(f) < bestAbs) {
                        bestAbs = Math.abs(f);
                        bestX = mid;
                    }
                    if (Math.abs(f) <= tol) {
                        done = true;
                        break;
                    }
                    if (Math.signum(f) == Math.signum(flo)) {
                        lo = mid;
                        flo = f;
                    } else {
                        hi = mid;
                        fhi = f;
                    }
                }
            }

            double x = bestX;
            double value = eval(m, tr, tc, cr, cc, x);
            if (done) {
                // prefer a tidy number (3 instead of 3.0000000001) when it is just as good
                double rounded = new java.math.BigDecimal(x).setScale(9, java.math.RoundingMode.HALF_UP).doubleValue();
                double rv = eval(m, tr, tc, cr, cc, rounded);
                if (!Double.isNaN(rv) && Math.abs(rv - goal) <= 1e-7 * Math.max(1, Math.abs(goal))) {
                    x = rounded;
                    value = rv;
                } else {
                    value = eval(m, tr, tc, cr, cc, x);
                }
            }
            res.found = done;
            res.x = x;
            res.value = value;
            res.message = done ? "Found a solution." : "Goal Seek could not find a solution. The formula may not reach "
                    + FormulaEngine.formatNumber(goal) + " (or it needs a different starting value).";
        } finally {
            m.endBatch();
        }
        return res;
    }

    /** Puts the changing cell back as it was. */
    public static void restore(SpreadsheetModel m, int cr, int cc, Result r) {
        m.setInput(cr, cc, r.original);
    }
}

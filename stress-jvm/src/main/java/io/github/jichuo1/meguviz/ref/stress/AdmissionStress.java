package io.github.jichuo1.meguviz.ref.stress;

import static org.openjdk.jcstress.annotations.Expect.ACCEPTABLE;
import static org.openjdk.jcstress.annotations.Expect.FORBIDDEN;

import org.openjdk.jcstress.annotations.*;
import org.openjdk.jcstress.infra.results.III_Result;
import org.openjdk.jcstress.infra.results.IIII_Result;

/**
 * CodeScope admission/close protocol. r1/r2 are callers (1 admitted, 0 rejected), r3 is the number of provider closes
 * and r4 the number of violations (provider closed with a target in flight or by a rejected caller, a target entered
 * after provider close, or a different rejection message).
 */
public final class AdmissionStress {
    private AdmissionStress() { }

    @JCStressTest
    @Outcome(id = {"0, 0, 1, 0", "0, 1, 1, 0", "1, 0, 1, 0", "1, 1, 1, 0"}, expect = ACCEPTABLE, desc = "Each caller is admitted before close or rejected after it; providers close once, after the drain.")
    @Outcome(expect = FORBIDDEN, desc = "Provider closed zero or several times, early, or by a rejected caller.")
    @State
    public static class CloseWhileCallingBase {
        final Fixture f = new Fixture(false);
        @Actor public void caller1(IIII_Result r) { r.r1 = f.call(); }
        @Actor public void caller2(IIII_Result r) { r.r2 = f.call(); }
        @Actor public void closer() { f.close(); }
        @Arbiter public void arbiter(IIII_Result r) { r.r3 = f.closes.get(); r.r4 = f.violations.get(); }
    }

    @JCStressTest
    @Outcome(id = {"0, 0, 1, 0", "0, 1, 1, 0", "1, 0, 1, 0", "1, 1, 1, 0"}, expect = ACCEPTABLE, desc = "Each caller is admitted before close or rejected after it; providers close once, after the drain.")
    @Outcome(expect = FORBIDDEN, desc = "Provider closed zero or several times, early, or by a rejected caller.")
    @State
    public static class CloseWhileCallingCells {
        final Fixture f = new Fixture(true);
        @Actor public void caller1(IIII_Result r) { r.r1 = f.call(); }
        @Actor public void caller2(IIII_Result r) { r.r2 = f.call(); }
        @Actor public void closer() { f.close(); }
        @Arbiter public void arbiter(IIII_Result r) { r.r3 = f.closes.get(); r.r4 = f.violations.get(); }
    }

    /**
     * Two closers race one caller on an inflated scope: r1 caller, r2 provider closes, r3 violations.
     */
    @JCStressTest
    @Outcome(id = {"0, 1, 0", "1, 1, 0"}, expect = ACCEPTABLE, desc = "Exactly one provider close after the drain.")
    @Outcome(expect = FORBIDDEN, desc = "Provider closed zero or several times, early, or by a rejected caller.")
    @State
    public static class ConcurrentClosers {
        final Fixture f = new Fixture(true);
        @Actor public void caller(III_Result r) { r.r1 = f.call(); }
        @Actor public void closer1() { f.close(); }
        @Actor public void closer2() { f.close(); }
        @Arbiter public void arbiter(III_Result r) { r.r2 = f.closes.get(); r.r3 = f.violations.get(); }
    }

    /**
     * Linearizability of rejection on an inflated scope: caller1 publishes its rejection, caller2 reads it before its
     * own call. r1 caller1, r2 = caller2 saw caller1's rejection, r3 caller2. Seeing a rejection and then being
     * admitted (x, 1, 1) would order an admission after close.
     */
    @JCStressTest
    @Outcome(id = {"0, 0, 0", "0, 0, 1", "1, 0, 0", "1, 0, 1", "0, 1, 0"}, expect = ACCEPTABLE, desc = "No admission follows an observed rejection.")
    @Outcome(id = "0, 1, 1", expect = FORBIDDEN, desc = "Admitted after another caller was already rejected.")
    @Outcome(expect = FORBIDDEN, desc = "Impossible combination.")
    @State
    public static class NoAdmissionAfterRejection {
        final Fixture f = new Fixture(true);
        volatile int rejected;
        @Actor public void closer() { f.close(); }
        @Actor public void caller1(III_Result r) { int admitted = f.call(); r.r1 = admitted; if (admitted == 0) rejected = 1; }
        @Actor public void caller2(III_Result r) { r.r2 = rejected; r.r3 = f.call(); }
    }
}

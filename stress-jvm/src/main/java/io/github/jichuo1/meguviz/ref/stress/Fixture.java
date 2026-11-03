package io.github.jichuo1.meguviz.ref.stress;

import io.github.jichuo1.meguviz.ref.api.descriptor.MemberDescriptor;
import io.github.jichuo1.meguviz.ref.api.evidence.CodeIdentity;
import io.github.jichuo1.meguviz.ref.api.query.QuerySpec;
import io.github.jichuo1.meguviz.ref.runtime.invoke.MethodPlan;
import io.github.jichuo1.meguviz.ref.runtime.invoke.StalePlanException;
import io.github.jichuo1.meguviz.ref.scope.CodeScope;
import io.github.jichuo1.meguviz.ref.spi.*;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One scope with an installed provider and a plan bound to {@link Probe#run}. The provider's close records protocol
 * violations: closing while a target is in flight, or being closed by a caller that was not admitted.
 */
public final class Fixture {
    static final ThreadLocal<Boolean> ADMITTED = ThreadLocal.withInitial(() -> false);
    static final ThreadLocal<Boolean> CLOSER = ThreadLocal.withInitial(() -> false);
    private static final CodeIdentity IDENTITY = CodeIdentity.strong(Collections.singletonMap("stress", "b".repeat(64)), "stress-fixture", QuerySpec.RULE_VERSION);
    private static final Method INFLATE;
    static {
        try { INFLATE = CodeScope.class.getDeclaredMethod("inflate"); INFLATE.setAccessible(true); }
        catch (ReflectiveOperationException e) { throw new ExceptionInInitializerError(e); }
    }
    final AtomicInteger closes = new AtomicInteger(), violations = new AtomicInteger();
    final Probe probe = new Probe(violations);
    final CodeScope scope = CodeScope.open(Fixture.class.getClassLoader());
    final MethodPlan plan;

    Fixture(boolean inflated) {
        scope.installProviders(ProviderRegistry.create(Collections.singletonList(new Provider())), IDENTITY);
        try {
            plan = scope.bindMethod(scope.resolve(QuerySpec.exact(MemberDescriptor.method(Probe.class.getName(), "run", "I"))).requireSingle());
            // White-box: the same inflation a failed base CAS triggers, so every iteration exercises the cell path.
            if (inflated) INFLATE.invoke(scope);
        } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }

    /** 1 when admitted, 0 when rejected with the scope's stale message. */
    int call() {
        ADMITTED.set(false);
        try { plan.invoke(probe, new Object[0]); return 1; }
        catch (StalePlanException rejected) {
            if (!"scope identity or epoch is stale".equals(rejected.getMessage())) violations.incrementAndGet();
            return 0;
        } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }

    void close() {
        CLOSER.set(true);
        try { scope.close(); } finally { CLOSER.set(false); }
    }

    /** Public so that the plan's Method.invoke passes the ordinary access check. */
    public static final class Probe {
        final AtomicInteger inFlight = new AtomicInteger(), violations;
        volatile boolean providerClosed;
        Probe(AtomicInteger violations) { this.violations = violations; }
        public int run() {
            ADMITTED.set(true); inFlight.incrementAndGet();
            try { if (providerClosed) violations.incrementAndGet(); return 1; }
            finally { inFlight.decrementAndGet(); }
        }
    }

    private final class Provider implements CandidateProvider {
        public ProviderDescriptor descriptor() { return new ProviderDescriptor("stress", "1", 1, 0, "", Collections.emptyList()); }
        public CandidateBatch discover(CandidateRequest request) { return new CandidateBatch(Collections.emptyList(), IDENTITY.fingerprint(), "none", true); }
        public void close() {
            closes.incrementAndGet();
            if (probe.inFlight.get() != 0) violations.incrementAndGet();
            if (!CLOSER.get() && !ADMITTED.get()) violations.incrementAndGet();
            probe.providerClosed = true;
        }
    }
}

package io.github.jichuo1.meguviz.ref.stress;

import static org.openjdk.jcstress.annotations.Expect.ACCEPTABLE;
import static org.openjdk.jcstress.annotations.Expect.FORBIDDEN;

import io.github.jichuo1.meguviz.ref.api.descriptor.MemberDescriptor;
import io.github.jichuo1.meguviz.ref.api.query.AccessPolicy;
import io.github.jichuo1.meguviz.ref.api.query.QuerySpec;
import io.github.jichuo1.meguviz.ref.api.result.ResolutionResult;
import io.github.jichuo1.meguviz.ref.api.result.ResolutionStatus;
import io.github.jichuo1.meguviz.ref.scope.CodeScope;
import java.lang.reflect.Field;
import java.util.Map;
import org.openjdk.jcstress.annotations.*;
import org.openjdk.jcstress.infra.results.II_Result;
import org.openjdk.jcstress.infra.results.I_Result;

/** The lock-free most-recent cache-hit memo of CodeScope.resolve (MeguvizRef 1.1.0 round 1). */
public final class MemoStress {
    private MemoStress() { }
    static final ClassLoader LOADER = MemoStress.class.getClassLoader();
    static QuerySpec query(String name) { return QuerySpec.exact(MemberDescriptor.method(Target.class.getName(), name, "I")); }

    public static final class Target {
        public int a() { return 1; }
        public int b() { return 2; }
        public int c() { return 3; }
    }

    /** r1 = reader saw close return, r2 = 1 memo/cache hit, 0 STALE scope_closed, 2 anything else. */
    @JCStressTest
    @Outcome(id = {"0, 0", "0, 1", "1, 0"}, expect = ACCEPTABLE, desc = "A hit is ordered before close; after close returns only STALE.")
    @Outcome(id = "1, 1", expect = FORBIDDEN, desc = "Cached result served after close returned.")
    @Outcome(expect = FORBIDDEN, desc = "Unexpected result.")
    @State
    public static class HitVersusClose {
        final CodeScope scope = CodeScope.open(LOADER);
        final QuerySpec query = query("a");
        final ResolutionResult cached;
        volatile int closed;
        public HitVersusClose() { cached = scope.resolve(query); if (scope.resolve(query) != cached) throw new IllegalStateException("memo"); }
        @Actor public void closer() { scope.close(); closed = 1; }
        @Actor public void reader(II_Result r) {
            r.r1 = closed;
            ResolutionResult result = scope.resolve(query);
            r.r2 = result == cached ? 1 : result.status() == ResolutionStatus.STALE && "scope_closed".equals(result.receipt().reason()) ? 0 : 2;
        }
    }

    /** r1 = reader saw the epoch advance return, r2 = epoch of the returned token (or -1 if not resolved). */
    @JCStressTest
    @Outcome(id = {"0, 0", "0, 1", "1, 1"}, expect = ACCEPTABLE, desc = "Old-epoch hit ordered before the advance, or a new-epoch result.")
    @Outcome(id = "1, 0", expect = FORBIDDEN, desc = "Old-epoch token served after advanceCodeEpoch returned.")
    @Outcome(expect = FORBIDDEN, desc = "Unexpected result.")
    @State
    public static class HitVersusEpoch {
        final CodeScope scope = CodeScope.open(LOADER);
        final QuerySpec query = query("a");
        volatile int advanced;
        public HitVersusEpoch() { ResolutionResult cached = scope.resolve(query); if (scope.resolve(query) != cached) throw new IllegalStateException("memo"); }
        @Actor public void advancer() { scope.advanceCodeEpoch("stress"); advanced = 1; }
        @Actor public void reader(II_Result r) {
            r.r1 = advanced;
            ResolutionResult result = scope.resolve(query);
            r.r2 = result.status() == ResolutionStatus.RESOLVED ? (int) result.members().get(0).codeEpoch() : -1;
        }
        @Arbiter public void arbiter() { scope.close(); }
    }

    /**
     * Capacity 2 with a and b cached; one actor hits a and b, another misses c, a different query for a's member (so
     * it needs no new binding and is cached: put + eviction). Afterwards the memo, when set, must still be the LRU
     * tail: r1 = 1 when that holds.
     */
    @JCStressTest
    @Outcome(id = "1", expect = ACCEPTABLE, desc = "The memo is empty or the most recently used cache entry.")
    @Outcome(expect = FORBIDDEN, desc = "The memo points at an entry that is not the LRU tail.")
    @State
    public static class MemoStaysLruTail {
        static final Field RECENT, RECENT_RESULT, CACHE;
        static {
            try {
                RECENT = CodeScope.class.getDeclaredField("recent"); RECENT.setAccessible(true);
                RECENT_RESULT = RECENT.getType().getDeclaredField("result"); RECENT_RESULT.setAccessible(true);
                CACHE = CodeScope.class.getDeclaredField("cache"); CACHE.setAccessible(true);
            } catch (ReflectiveOperationException e) { throw new ExceptionInInitializerError(e); }
        }
        final CodeScope scope = CodeScope.open(LOADER, AccessPolicy.PRESERVE_JAVA_ACCESS, 2);
        final QuerySpec a = query("a"), b = query("b"), c = QuerySpec.method(Target.class, "a");
        public MemoStaysLruTail() { scope.resolve(a); scope.resolve(b); scope.resolve(b); }
        @Actor public void hitter() { scope.resolve(a); scope.resolve(b); }
        @Actor public void misser() { scope.resolve(c); }
        @Arbiter public void arbiter(I_Result r) {
            try {
                Object recent = RECENT.get(scope);
                Object tail = null;
                for (Object value : ((Map<?, ?>) CACHE.get(scope)).values()) tail = value;
                r.r1 = recent == null || RECENT_RESULT.get(recent) == tail ? 1 : 0;
            } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
            scope.close();
        }
    }
}

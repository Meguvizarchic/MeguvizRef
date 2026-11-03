package io.github.jichuo1.meguviz.ref.bench;

import io.github.jichuo1.meguviz.ref.api.descriptor.MemberKind;
import io.github.jichuo1.meguviz.ref.api.query.QuerySpec;
import io.github.jichuo1.meguviz.ref.api.query.Selection;
import io.github.jichuo1.meguviz.ref.scope.CodeScope;
import java.lang.reflect.Member;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

/** Ref 1.1 resolveRaw counterparts of ResolveBenchmark's bridge-shaped export benchmarks (no binding slots). */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class RawResolveBenchmark {
    static final ClassLoader LOADER = WideTarget.class.getClassLoader();
    final QuerySpec bridgeAll = QuerySpec.builder(WideTarget.class, MemberKind.METHOD).name("pick").selection(Selection.ALL)
            .resultLimit(4096).bridge(false).synthetic(false).parameterCount(1).build();
    final QuerySpec[] session = new QuerySpec[8];

    @Setup
    public void setUp() {
        for (int i = 0; i < session.length; i++) {
            int index = 10 + i * 17;
            session[i] = QuerySpec.builder(WideTarget.class, MemberKind.METHOD).name(String.format("m%03d", index))
                    .selection(Selection.ALL).parameterCount(index % 4).bridge(false).synthetic(false).build();
        }
    }

    @Benchmark
    public int bridgeAllRawFreshScope() throws Exception {
        int total = 0;
        try (CodeScope scope = CodeScope.open(LOADER)) { for (Member member : scope.resolveRaw(bridgeAll).members()) total += member.getModifiers(); }
        return total;
    }

    @Benchmark
    public int sessionEightQueriesRaw() throws Exception {
        int total = 0;
        try (CodeScope scope = CodeScope.open(LOADER)) {
            for (QuerySpec query : session) for (Member member : scope.resolveRaw(query).members()) total += member.getModifiers();
        }
        return total;
    }
}

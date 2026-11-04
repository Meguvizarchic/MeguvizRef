package io.github.jichuo1.meguviz.ref.bench;

import io.github.jichuo1.meguviz.ref.api.descriptor.MemberDescriptor;
import io.github.jichuo1.meguviz.ref.api.descriptor.MemberKind;
import io.github.jichuo1.meguviz.ref.api.query.Hierarchy;
import io.github.jichuo1.meguviz.ref.api.query.QuerySpec;
import io.github.jichuo1.meguviz.ref.api.query.Selection;
import io.github.jichuo1.meguviz.ref.api.result.ResolutionResult;
import io.github.jichuo1.meguviz.ref.api.result.ResolvedMember;
import io.github.jichuo1.meguviz.ref.scope.CodeScope;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

/**
 * Resolution and scope lifecycle costs on the host JVM. "FreshScope" variants open, resolve and close one scope per
 * operation (the bridge's default resolver shape); subtract scopeOpenClose for the resolve-only part.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class ResolveBenchmark {
    static final ClassLoader LOADER = WideTarget.class.getClassLoader();
    final QuerySpec exact = QuerySpec.exact(MemberDescriptor.method(WideTarget.class.getName(), "sum", "I", "I", "I"));
    /** Exact descriptor with reference parameter and return types (each resolved through Class.forName on a miss). */
    final QuerySpec exactReference = QuerySpec.exact(MemberDescriptor.method(WideTarget.class.getName(), "pick", "Ljava/lang/String;", "Ljava/lang/String;"));
    final QuerySpec structural = QuerySpec.builder(WideTarget.class, MemberKind.METHOD).name("m077").parameters(int.class).build();
    /** Same shape as the Hook bridge default query: name + arity, ALL, no bridge/synthetic. */
    final QuerySpec bridgeAll = QuerySpec.builder(WideTarget.class, MemberKind.METHOD).name("pick").selection(Selection.ALL)
            .resultLimit(4096).bridge(false).synthetic(false).parameterCount(1).build();
    final QuerySpec inherited = QuerySpec.builder(WideChild.class, MemberKind.METHOD).name("hashCode").parameters()
            .hierarchy(Hierarchy.SUPERCLASSES).build();
    final QuerySpec[] session = new QuerySpec[8];
    CodeScope shared;

    @Setup
    public void setUp() {
        for (int i = 0; i < session.length; i++) {
            int index = 10 + i * 17;
            session[i] = QuerySpec.builder(WideTarget.class, MemberKind.METHOD).name(String.format("m%03d", index))
                    .selection(Selection.ALL).parameterCount(index % 4).bridge(false).synthetic(false).build();
        }
        shared = CodeScope.open(LOADER);
        shared.resolve(exact).requireSingle();
        shared.resolve(structural).requireSingle();
    }

    @TearDown
    public void tearDown() { shared.close(); }

    @Benchmark
    public CodeScope scopeOpenClose() {
        CodeScope scope = CodeScope.open(LOADER);
        scope.close();
        return scope;
    }

    @Benchmark
    public ResolutionResult exactHit() { return shared.resolve(exact); }

    @Benchmark
    @Threads(2)
    public ResolutionResult exactHitThreads2() { return shared.resolve(exact); }

    @Benchmark
    @Threads(4)
    public ResolutionResult exactHitThreads4() { return shared.resolve(exact); }

    @Benchmark
    @Threads(8)
    public ResolutionResult exactHitThreads8() { return shared.resolve(exact); }

    @Benchmark
    public ResolutionResult structuralHit() { return shared.resolve(structural); }

    @Benchmark
    public ResolutionResult exactMissFreshScope() {
        try (CodeScope scope = CodeScope.open(LOADER)) { return scope.resolve(exact); }
    }

    @Benchmark
    public ResolutionResult exactReferenceMissFreshScope() {
        try (CodeScope scope = CodeScope.open(LOADER)) { return scope.resolve(exactReference); }
    }

    @Benchmark
    public ResolutionResult structuralMissFreshScope() {
        try (CodeScope scope = CodeScope.open(LOADER)) { return scope.resolve(structural); }
    }

    @Benchmark
    public ResolutionResult bridgeAllMissFreshScope() {
        try (CodeScope scope = CodeScope.open(LOADER)) { return scope.resolve(bridgeAll); }
    }

    @Benchmark
    public ResolutionResult inheritedMissFreshScope() {
        try (CodeScope scope = CodeScope.open(LOADER)) { return scope.resolve(inherited); }
    }

    /** Bridge default resolver shape on Ref 1.0.0 API: fresh scope, ALL query, exportRaw for every token. */
    @Benchmark
    public int bridgeAllExportFreshScope() throws Exception {
        int total = 0;
        try (CodeScope scope = CodeScope.open(LOADER)) {
            for (ResolvedMember token : scope.resolve(bridgeAll).members()) total += scope.exportRaw(token).getModifiers();
        }
        return total;
    }

    /** Bridge session shape on Ref 1.0.0 API: one scope, eight different ALL queries, exportRaw for every token. */
    @Benchmark
    public int sessionEightQueriesExport() throws Exception {
        int total = 0;
        try (CodeScope scope = CodeScope.open(LOADER)) {
            for (QuerySpec query : session) for (ResolvedMember token : scope.resolve(query).members()) total += scope.exportRaw(token).getModifiers();
        }
        return total;
    }

    /** Eight different structural queries on the same 155-method owner inside one scope (session-style misses). */
    @Benchmark
    public int structuralEightQueriesOneScope() {
        int total = 0;
        try (CodeScope scope = CodeScope.open(LOADER)) {
            for (QuerySpec query : session) total += scope.resolve(query).members().size();
        }
        return total;
    }
}

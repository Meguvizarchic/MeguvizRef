package io.github.jichuo1.meguviz.ref.bench;

import io.github.jichuo1.meguviz.ref.api.descriptor.MemberDescriptor;
import io.github.jichuo1.meguviz.ref.api.descriptor.MemberKind;
import io.github.jichuo1.meguviz.ref.api.query.Hierarchy;
import io.github.jichuo1.meguviz.ref.api.query.QuerySpec;
import io.github.jichuo1.meguviz.ref.api.query.Selection;
import io.github.jichuo1.meguviz.ref.api.result.ResolvedMember;
import io.github.jichuo1.meguviz.ref.scope.CodeScope;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

/**
 * Install-batch shape of the MeguvizHook bridge: {@link #hooks} descriptor targets with distinct, non-overloaded public
 * names on java.lang.String (the same selection as MeguvizHook's InstallBatchBenchmark). Each target becomes the
 * bridge's default structural query (name + arity, ALL, DECLARED, no bridge/synthetic), built per operation as the
 * bridge does. "OneScope" variants open a single scope for the whole batch; "ScopePerQuery" is the current default
 * resolver (one scope per descriptor). The exact variants use QuerySpec.exact with the full descriptor instead.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class BatchResolveBenchmark {
    /** An application loader, as in the bridge; java.lang.String itself is defined by the bootstrap loader. */
    static final ClassLoader LOADER = ClassLoader.getSystemClassLoader();

    @Param({"1", "20"})
    public int hooks;

    String[] names;
    int[] arities;
    MemberDescriptor[] descriptors;

    @Setup
    public void setUp() {
        Map<String, Method> unique = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Method method : String.class.getDeclaredMethods()) {
            if (method.isSynthetic() || method.isBridge() || !Modifier.isPublic(method.getModifiers())) continue;
            counts.merge(method.getName(), 1, Integer::sum);
            unique.put(method.getName(), method);
        }
        List<Method> selected = new ArrayList<>();
        for (Map.Entry<String, Method> entry : unique.entrySet()) {
            if (counts.get(entry.getKey()) == 1) selected.add(entry.getValue());
            if (selected.size() == hooks) break;
        }
        if (selected.size() != hooks) throw new IllegalStateException("only " + selected.size() + " unique targets");
        names = new String[hooks]; arities = new int[hooks]; descriptors = new MemberDescriptor[hooks];
        for (int i = 0; i < hooks; i++) {
            Method method = selected.get(i);
            names[i] = method.getName(); arities[i] = method.getParameterCount(); descriptors[i] = MemberDescriptor.from(method);
        }
    }

    QuerySpec query(int i) {
        return QuerySpec.builder(String.class, MemberKind.METHOD).name(names[i]).selection(Selection.ALL).resultLimit(4096)
                .hierarchy(Hierarchy.DECLARED).bridge(false).synthetic(false).parameterCount(arities[i]).build();
    }

    /** resolve + exportRaw per token in one scope (bridge session on the 1.0.0 API). */
    @Benchmark
    public int structuralOneScopeExport() throws Exception {
        int total = 0;
        try (CodeScope scope = CodeScope.open(LOADER)) {
            for (int i = 0; i < hooks; i++) for (ResolvedMember token : scope.resolve(query(i)).members()) total += scope.exportRaw(token).getModifiers();
        }
        return total;
    }

    /** resolveRaw in one scope (bridge install batch on the 1.1.0 API). */
    @Benchmark
    public int structuralOneScopeRaw() throws Exception {
        int total = 0;
        try (CodeScope scope = CodeScope.open(LOADER)) {
            for (int i = 0; i < hooks; i++) for (Member member : scope.resolveRaw(query(i)).members()) total += member.getModifiers();
        }
        return total;
    }

    /** The bridge's current default resolver: one scope per descriptor target. */
    @Benchmark
    public int structuralScopePerQueryExport() throws Exception {
        int total = 0;
        for (int i = 0; i < hooks; i++) {
            try (CodeScope scope = CodeScope.open(LOADER)) {
                for (ResolvedMember token : scope.resolve(query(i)).members()) total += scope.exportRaw(token).getModifiers();
            }
        }
        return total;
    }

    /** Exact descriptors (reference parameter/return types resolved through Class.forName) in one scope. */
    @Benchmark
    public int exactOneScopeRaw() throws Exception {
        int total = 0;
        try (CodeScope scope = CodeScope.open(LOADER)) {
            for (int i = 0; i < hooks; i++) for (Member member : scope.resolveRaw(QuerySpec.exact(descriptors[i])).members()) total += member.getModifiers();
        }
        return total;
    }
}

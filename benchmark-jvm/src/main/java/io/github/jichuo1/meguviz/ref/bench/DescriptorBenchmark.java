package io.github.jichuo1.meguviz.ref.bench;

import io.github.jichuo1.meguviz.ref.api.descriptor.MemberDescriptor;
import io.github.jichuo1.meguviz.ref.api.descriptor.MemberKind;
import io.github.jichuo1.meguviz.ref.api.descriptor.TypeDescriptor;
import io.github.jichuo1.meguviz.ref.api.query.QuerySpec;
import io.github.jichuo1.meguviz.ref.api.query.Selection;
import io.github.jichuo1.meguviz.ref.runtime.resolve.MethodSchema;
import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

/** Per-miss metadata work: schema snapshots, portable descriptors, query summaries and hashes. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class DescriptorBenchmark {
    Method threeInts, oneString;
    MemberDescriptor descriptor;
    QuerySpec query;
    final TypeDescriptor stringType = TypeDescriptor.of("Ljava/lang/String;");
    final ClassLoader appLoader = WideTarget.class.getClassLoader();

    @Setup
    public void setUp() throws Exception {
        threeInts = WideTarget.class.getMethod("m003", int.class, int.class, int.class);
        oneString = WideTarget.class.getMethod("pick", String.class);
        descriptor = MemberDescriptor.from(oneString);
        query = QuerySpec.builder(WideTarget.class, MemberKind.METHOD).name("pick").selection(Selection.ALL)
                .resultLimit(4096).bridge(false).synthetic(false).parameterCount(1).build();
    }

    @Benchmark
    public MethodSchema methodSchemaThreeInts() { return new MethodSchema(threeInts); }

    @Benchmark
    public MethodSchema methodSchemaOneString() { return new MethodSchema(oneString); }

    @Benchmark
    public MemberDescriptor descriptorFromOneString() { return MemberDescriptor.from(oneString); }

    @Benchmark
    public int descriptorHashCode() { return descriptor.hashCode(); }

    @Benchmark
    public String querySummary() { return query.summary(); }

    /** One reference type of an exact descriptor, resolved through the owner's (application) loader on every miss. */
    @Benchmark
    public Class<?> typeResolveReference() throws Exception { return stringType.resolve(appLoader); }

    /** The Hook bridge builds one query like this per resolve. */
    @Benchmark
    public QuerySpec bridgeQueryBuild() {
        return QuerySpec.builder(WideTarget.class, MemberKind.METHOD).name("pick").selection(Selection.ALL)
                .resultLimit(4096).bridge(false).synthetic(false).parameterCount(1).build();
    }
}

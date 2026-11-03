package io.github.jichuo1.meguviz.ref.bench;

import io.github.jichuo1.meguviz.ref.api.descriptor.MemberDescriptor;
import io.github.jichuo1.meguviz.ref.api.query.QuerySpec;
import io.github.jichuo1.meguviz.ref.runtime.invoke.ConstructorPlan;
import io.github.jichuo1.meguviz.ref.runtime.invoke.FieldPlan;
import io.github.jichuo1.meguviz.ref.runtime.invoke.MethodPlan;
import io.github.jichuo1.meguviz.ref.scope.CodeScope;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

/**
 * Plan invocation overhead versus direct core reflection, single-threaded and with 2/4/8 threads sharing one plan (one
 * scope admission state). {@code planInvokeThreads4OwnScope} gives every thread its own scope as the no-sharing
 * reference, and {@code planInvokeAfterContention} measures one thread on a scope that has already seen contention.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class InvokeBenchmark {
    static final ClassLoader LOADER = WideTarget.class.getClassLoader();
    final WideTarget target = new WideTarget(1);
    final Object[] arguments = {1, 2};
    final Object[] constructorArguments = {7};
    Method method;
    Field field;
    Constructor<?> constructor;
    CodeScope scope, wideScope, contendedScope;
    MethodPlan plan, highSlotPlan, contendedPlan;
    FieldPlan fieldPlan;
    ConstructorPlan constructorPlan;

    @Setup
    public void setUp() throws Exception {
        method = WideTarget.class.getMethod("sum", int.class, int.class);
        field = WideTarget.class.getField("value");
        constructor = WideTarget.class.getConstructor(int.class);
        String owner = WideTarget.class.getName();
        MemberDescriptor sum = MemberDescriptor.method(owner, "sum", "I", "I", "I");
        scope = CodeScope.open(LOADER);
        plan = scope.bindMethod(scope.resolve(QuerySpec.exact(sum)).requireSingle());
        fieldPlan = scope.bindField(scope.resolve(QuerySpec.exact(MemberDescriptor.field(owner, "value", "I"))).requireSingle());
        constructorPlan = scope.bindConstructor(scope.resolve(QuerySpec.exact(MemberDescriptor.constructor(owner, "I"))).requireSingle());
        // A long-lived scope whose plan sits beyond the small Long cache (slot > 127).
        wideScope = CodeScope.open(LOADER);
        for (int i = 0; i < 150; i++) {
            String[] parameters = new String[i % 4];
            java.util.Arrays.fill(parameters, "I");
            wideScope.resolve(QuerySpec.exact(MemberDescriptor.method(owner, String.format("m%03d", i), "I", parameters))).requireSingle();
        }
        highSlotPlan = wideScope.bindMethod(wideScope.resolve(QuerySpec.exact(sum)).requireSingle());
        if (!Integer.valueOf(4).equals(plan.invoke(target, arguments)) || !Integer.valueOf(4).equals(highSlotPlan.invoke(target, arguments))) {
            throw new IllegalStateException("fixture");
        }
        contendedScope = CodeScope.open(LOADER);
        contendedPlan = contendedScope.bindMethod(contendedScope.resolve(QuerySpec.exact(sum)).requireSingle());
        contend(contendedPlan);
    }

    /** Four threads call one plan for 300 ms, so the scope has seen contention before the single-threaded measurement. */
    private void contend(MethodPlan shared) throws Exception {
        Thread[] threads = new Thread[4];
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(300);
        for (int t = 0; t < threads.length; t++) {
            threads[t] = new Thread(() -> {
                try { while (System.nanoTime() < deadline) for (int i = 0; i < 1000; i++) shared.invoke(target, arguments); }
                catch (Exception e) { throw new IllegalStateException(e); }
            });
            threads[t].start();
        }
        for (Thread thread : threads) thread.join();
        // Since 1.1.0 round 2 contention inflates per-thread admission cells; check that the setup reached that state.
        try {
            java.lang.reflect.Field cells = CodeScope.class.getDeclaredField("cells");
            cells.setAccessible(true);
            if (cells.get(contendedScope) == null) throw new IllegalStateException("contention did not inflate admission cells");
        } catch (NoSuchFieldException before) { /* single admission word (round 1) */ }
    }

    @TearDown
    public void tearDown() { scope.close(); wideScope.close(); contendedScope.close(); }

    /** Each benchmark thread gets its own scope and plan: no shared admission state. */
    @State(Scope.Thread)
    public static class OwnScope {
        final WideTarget target = new WideTarget(1);
        final Object[] arguments = {1, 2};
        CodeScope scope;
        MethodPlan plan;

        @Setup
        public void setUp() throws Exception {
            scope = CodeScope.open(LOADER);
            plan = scope.bindMethod(scope.resolve(QuerySpec.exact(MemberDescriptor.method(WideTarget.class.getName(), "sum", "I", "I", "I"))).requireSingle());
        }

        @TearDown
        public void tearDown() { scope.close(); }
    }

    @Benchmark
    public int directCall() { return target.sum(1, 2); }

    @Benchmark
    public Object directInvoke() throws Exception { return method.invoke(target, arguments); }

    @Benchmark
    public Object planInvoke() throws Exception { return plan.invoke(target, arguments); }

    @Benchmark
    public Object planInvokeHighSlot() throws Exception { return highSlotPlan.invoke(target, arguments); }

    @Benchmark
    public Object planInvokeAfterContention() throws Exception { return contendedPlan.invoke(target, arguments); }

    @Benchmark
    @Threads(2)
    public Object directInvokeThreads2() throws Exception { return method.invoke(target, arguments); }

    @Benchmark
    @Threads(4)
    public Object directInvokeThreads4() throws Exception { return method.invoke(target, arguments); }

    @Benchmark
    @Threads(8)
    public Object directInvokeThreads8() throws Exception { return method.invoke(target, arguments); }

    @Benchmark
    @Threads(2)
    public Object planInvokeThreads2() throws Exception { return plan.invoke(target, arguments); }

    @Benchmark
    @Threads(4)
    public Object planInvokeThreads4() throws Exception { return plan.invoke(target, arguments); }

    @Benchmark
    @Threads(8)
    public Object planInvokeThreads8() throws Exception { return plan.invoke(target, arguments); }

    @Benchmark
    @Threads(4)
    public Object planInvokeThreads4OwnScope(OwnScope own) throws Exception { return own.plan.invoke(own.target, own.arguments); }

    @Benchmark
    public Object directFieldGet() throws Exception { return field.get(target); }

    @Benchmark
    public Object planFieldGet() throws Exception { return fieldPlan.get(target); }

    @Benchmark
    public Object directNewInstance() throws Exception { return constructor.newInstance(constructorArguments); }

    @Benchmark
    public Object planNewInstance() throws Exception { return constructorPlan.newInstance(constructorArguments); }
}

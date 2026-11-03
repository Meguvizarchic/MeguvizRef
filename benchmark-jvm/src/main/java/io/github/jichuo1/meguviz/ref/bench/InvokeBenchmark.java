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

/** Plan invocation overhead versus direct core reflection, single-threaded and with four threads sharing one scope. */
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
    CodeScope scope, wideScope;
    MethodPlan plan, highSlotPlan;
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
    }

    @TearDown
    public void tearDown() { scope.close(); wideScope.close(); }

    @Benchmark
    public int directCall() { return target.sum(1, 2); }

    @Benchmark
    public Object directInvoke() throws Exception { return method.invoke(target, arguments); }

    @Benchmark
    public Object planInvoke() throws Exception { return plan.invoke(target, arguments); }

    @Benchmark
    public Object planInvokeHighSlot() throws Exception { return highSlotPlan.invoke(target, arguments); }

    @Benchmark
    @Threads(4)
    public Object directInvokeThreads4() throws Exception { return method.invoke(target, arguments); }

    @Benchmark
    @Threads(4)
    public Object planInvokeThreads4() throws Exception { return plan.invoke(target, arguments); }

    @Benchmark
    public Object directFieldGet() throws Exception { return field.get(target); }

    @Benchmark
    public Object planFieldGet() throws Exception { return fieldPlan.get(target); }

    @Benchmark
    public Object directNewInstance() throws Exception { return constructor.newInstance(constructorArguments); }

    @Benchmark
    public Object planNewInstance() throws Exception { return constructorPlan.newInstance(constructorArguments); }
}

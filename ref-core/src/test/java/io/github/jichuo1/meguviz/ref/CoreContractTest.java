package io.github.jichuo1.meguviz.ref;

import io.github.jichuo1.meguviz.ref.api.descriptor.*;
import io.github.jichuo1.meguviz.ref.api.evidence.*;
import io.github.jichuo1.meguviz.ref.api.query.*;
import io.github.jichuo1.meguviz.ref.api.result.*;
import io.github.jichuo1.meguviz.ref.runtime.invoke.*;
import io.github.jichuo1.meguviz.ref.runtime.resolve.*;
import io.github.jichuo1.meguviz.ref.scope.CodeScope;
import io.github.jichuo1.meguviz.ref.spi.*;
import java.io.*;
import java.lang.ref.WeakReference;
import java.lang.reflect.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.jar.*;
import javax.tools.ToolProvider;

/** Dependency-free, executable contracts with handwritten expected descriptors and standard-reflection oracles. */
public final class CoreContractTest {
    private static final List<String> xml = new ArrayList<>();
    private static int count, failures;
    private static Path fixtures, missing;
    private static final ClassLoader LOADER = CoreContractTest.class.getClassLoader();
    private static final CodeIdentity IDENTITY = CodeIdentity.strong(Collections.singletonMap("fixture", "a".repeat(64)), "parent-first-fixture", QuerySpec.RULE_VERSION);
    public static void main(String[] args) throws Exception {
        prepareFixtures();
        descriptorContracts();
        exactAndInvocationContracts();
        structuralContracts();
        lifecycleAndLoaderContracts();
        providerContracts();
        providerArityContracts();
        concurrencyContracts();
        scopeFastPathContracts();
        memberTableContracts();
        if (args.length > 0) {
            Path output = Path.of(args[0]); Files.createDirectories(output.toAbsolutePath().getParent());
            Files.writeString(output, "<?xml version=\"1.0\" encoding=\"UTF-8\"?><testsuite name=\"MeguvizRef.CoreContracts\" tests=\"" + count + "\" failures=\"" + failures + "\">" + String.join("", xml) + "</testsuite>");
        }
        System.out.println("TESTS=" + count + " FAILURES=" + failures);
        if (count == 0 || failures != 0) throw new AssertionError("contract failures=" + failures);
    }
    private static void descriptorContracts() {
        for (String invalid : new String[]{"", "[V", "VV", "L;", "Lfoo.bar;", "L/a;", "La//b;", "(I)V", "["}) {
            test("invalid-type-" + invalid, () -> expect(IllegalArgumentException.class, () -> TypeDescriptor.of(invalid)));
        }
        test("array-bound", () -> expect(IllegalArgumentException.class, () -> TypeDescriptor.of("[".repeat(256) + "I")));
        test("descriptor-limit", () -> expect(IllegalArgumentException.class, () -> TypeDescriptor.of("L" + "a".repeat(4096) + ";")));
        test("void-parameter", () -> expect(IllegalArgumentException.class, () -> MemberDescriptor.method("X", "f", "V", "V")));
        test("void-field", () -> expect(IllegalArgumentException.class, () -> MemberDescriptor.field("X", "f", "V")));
        test("constructor-name", () -> expect(IllegalArgumentException.class, () -> MemberDescriptor.create(MemberKind.CONSTRUCTOR, "X", "wrong", "V")));
        test("method-special-name", () -> expect(IllegalArgumentException.class, () -> MemberDescriptor.method("X", "<init>", "V")));
        test("method-special-name-after-newline", () -> expect(IllegalArgumentException.class, () -> MemberDescriptor.method("X", "before\n<invalid>", "V")));
        test("portable-complete-signature", () -> eq("java.lang.String#substring(II)Ljava/lang/String;", MemberDescriptor.method("java.lang.String", "substring", "Ljava/lang/String;", "I", "I").signature()));
        test("return-in-identity", () -> check(!MemberDescriptor.method("X", "f", "I").equals(MemberDescriptor.method("X", "f", "J")), "return included"));
        test("descriptor-list-immutable", () -> expect(UnsupportedOperationException.class, () -> MemberDescriptor.method("X", "f", "V", "I").parameters().clear()));
        test("type-bind-arrays", () -> eq(String[][].class, TypeDescriptor.of("[[Ljava/lang/String;").resolve(null)));
        test("runtime-query-not-portable", () -> check(!QuerySpec.builder(Target.class, MemberKind.METHOD).build().portable(), "runtime Class"));
        test("diagnostic-delimiter-collision-is-not-cache-identity", () -> {
            QuerySpec a = QuerySpec.builder("a:b",MemberKind.METHOD).name("c").build();
            QuerySpec b = QuerySpec.builder("a",MemberKind.METHOD).name("b:c").build();
            eq(a.summary(),b.summary()); check(!a.equals(b),"structural identity");
            Map<QuerySpec,String> cache=new HashMap<>();cache.put(a,"first");cache.put(b,"second");eq(2,cache.size());eq("first",cache.get(a));eq("second",cache.get(b));
        });
        test("exact-query-portable", () -> check(QuerySpec.exact(descriptor("four", "I", "I", "I", "I", "I")).portable(), "literal"));
        test("query-snapshot-arrays", () -> {
            Class<?>[] values = {int.class}; QuerySpec query = QuerySpec.builder(Target.class, MemberKind.METHOD).parameters(values).build();
            values[0] = String.class; Class<?>[] read = query.parameterClasses(); read[0] = long.class; eq(int.class, query.parameterClasses()[0]);
        });
        test("constructors-not-inherited", () -> expect(IllegalArgumentException.class, () -> QuerySpec.builder(Target.class, MemberKind.CONSTRUCTOR).hierarchy(Hierarchy.SUPERCLASSES).build()));
        test("incomplete-exact-rejected", () -> expect(IllegalArgumentException.class, () -> QuerySpec.builder(Target.class, MemberKind.METHOD).selection(Selection.EXACT).build()));
    }
    private static void exactAndInvocationContracts() {
        test("vertical-slice-lookup-invoke-close", () -> {
            CodeScope scope = CodeScope.open(LOADER);
            ResolutionResult result = scope.resolve(QuerySpec.exact(descriptor("four", "I", "I", "I", "I", "I")));
            eq(ResolutionStatus.RESOLVED, result.status()); check(result.receipt().coverage().complete(), "exact coverage");
            MethodPlan plan = scope.bindMethod(result.requireSingle()); eq(10, plan.invoke(new Target(), new Object[]{1, 2, 3, 4}));
            scope.close(); scope.close(); eq(CodeScope.State.CLOSED, scope.state()); expect(StalePlanException.class, () -> plan.invoke(new Target(), new Object[]{1, 2, 3, 4}));
            eq(ResolutionStatus.STALE, scope.resolve(QuerySpec.exact(descriptor("four", "I", "I", "I", "I", "I"))).status());
        });
        test("exact-no-full-schema", () -> {
            try (CodeScope scope = CodeScope.open(LOADER)) { scope.resolve(QuerySpec.exact(descriptor("four", "I", "I", "I", "I", "I"))).requireSingle(); eq(0L, scope.diagnostics().get("schemas")); }
        });
        test("exact-missing-unavailable-not-physical-proof", () -> {
            try (CodeScope scope = CodeScope.open(LOADER)) { ResolutionResult result = scope.resolve(QuerySpec.exact(descriptor("missing", "I"))); eq(ResolutionStatus.UNAVAILABLE, result.status()); eq("not_found_or_filtered", result.receipt().reason()); }
        });
        test("exact-wrong-return", () -> { try (CodeScope scope = CodeScope.open(LOADER)) { eq(ResolutionStatus.UNAVAILABLE, scope.resolve(QuerySpec.exact(descriptor("four", "J", "I", "I", "I", "I"))).status()); } });
        test("exact-covariant-bridge-return", () -> {
            try (CodeScope scope = CodeScope.open(LOADER)) {
                Method bridge = (Method) scope.exportRaw(scope.resolve(QuerySpec.exact(MemberDescriptor.method(Child.class.getName(), "covariant", "Ljava/lang/Object;"))).requireSingle());
                check(bridge.isBridge() && bridge.isSynthetic(), "compiler bridge");
                Method actual = (Method) scope.exportRaw(scope.resolve(QuerySpec.exact(MemberDescriptor.method(Child.class.getName(), "covariant", "Ljava/lang/String;"))).requireSingle());
                check(!actual.isBridge(), "actual return");
            }
        });
        test("field-read-write", () -> {
            try (CodeScope scope = CodeScope.open(LOADER)) {
                Target target = new Target(); FieldPlan plan = scope.bindField(scope.resolve(QuerySpec.exact(MemberDescriptor.field(Target.class.getName(), "number", "I"))).requireSingle());
                plan.set(target, (byte) 7); eq(7, plan.get(target)); expect(IllegalArgumentException.class, () -> plan.set(target, 9L));
            }
        });
        test("constructor-call", () -> { try (CodeScope scope = CodeScope.open(LOADER)) { Target target = (Target) scope.bindConstructor(scope.resolve(QuerySpec.exact(MemberDescriptor.constructor(Target.class.getName(), "I"))).requireSingle()).newInstance(new Object[]{23}); eq(23, target.number); } });
        test("constructor-throws-wrapper", () -> { try (CodeScope scope = CodeScope.open(LOADER)) { InvocationTargetException failure = expect(InvocationTargetException.class, () -> scope.bindConstructor(scope.resolve(QuerySpec.exact(MemberDescriptor.constructor(Target.class.getName(), "I"))).requireSingle()).newInstance(new Object[]{-1})); check(failure.getCause() instanceof IllegalArgumentException, "cause"); } });
        test("target-exception-no-retry", () -> {
            try (CodeScope scope = CodeScope.open(LOADER)) {
                Target target = new Target(); MethodPlan plan = scope.bindMethod(scope.resolve(QuerySpec.exact(descriptor("throwOnce", "V"))).requireSingle());
                InvocationTargetException error = expect(InvocationTargetException.class, () -> plan.invoke(target, new Object[0]));
                check(error.getCause() instanceof IllegalStateException, "original cause"); eq(1, target.calls);
            }
        });
        test("target-null-distinct-from-lookup-failure", () -> { try (CodeScope scope = CodeScope.open(LOADER)) { eq(null, scope.bindMethod(scope.resolve(QuerySpec.exact(descriptor("nullable", "Ljava/lang/Object;"))).requireSingle()).invoke(new Target(), new Object[0])); } });
        test("wrong-receiver-arity-type", () -> {
            try (CodeScope scope = CodeScope.open(LOADER)) {
                MethodPlan plan = scope.bindMethod(scope.resolve(QuerySpec.exact(descriptor("four", "I", "I", "I", "I", "I"))).requireSingle());
                expect(IllegalArgumentException.class, () -> plan.invoke("wrong", new Object[]{1, 2, 3, 4}));
                expect(IllegalArgumentException.class, () -> plan.invoke(new Target(), new Object[]{1}));
                expect(IllegalArgumentException.class, () -> plan.invoke(new Target(), new Object[]{"1", 2, 3, 4}));
            }
        });
        test("reflection-widening-executes", () -> { try (CodeScope scope = CodeScope.open(LOADER)) { eq(3L, scope.bindMethod(scope.resolve(QuerySpec.exact(descriptor("wide", "J", "J"))).requireSingle()).invoke(new Target(), new Object[]{3})); } });
        test("preserve-access-default", () -> { try (CodeScope scope = CodeScope.open(LOADER)) { MethodPlan plan = scope.bindMethod(scope.resolve(QuerySpec.exact(descriptor("privateValue", "I"))).requireSingle()); expect(IllegalAccessException.class, () -> plan.invoke(new Target(), new Object[0])); } });
        test("explicit-suppress-access", () -> { try (CodeScope scope = CodeScope.open(LOADER, AccessPolicy.TRY_SUPPRESS_JAVA_CHECKS, 128)) { eq(42, scope.bindMethod(scope.resolve(QuerySpec.exact(descriptor("privateValue", "I"))).requireSingle()).invoke(new Target(), new Object[0])); } });
        test("access-policy-scopes-isolated", () -> {
            try (CodeScope open = CodeScope.open(LOADER, AccessPolicy.TRY_SUPPRESS_JAVA_CHECKS, 128); CodeScope preserve = CodeScope.open(LOADER)) {
                QuerySpec query = QuerySpec.exact(descriptor("privateValue", "I")); open.bindMethod(open.resolve(query).requireSingle());
                expect(IllegalAccessException.class, () -> preserve.bindMethod(preserve.resolve(query).requireSingle()).invoke(new Target(), new Object[0]));
            }
        });
        test("virtual-dispatch", () -> { try (CodeScope scope = CodeScope.open(LOADER)) { eq("child", scope.bindMethod(scope.resolve(QuerySpec.exact(MemberDescriptor.method(Parent.class.getName(), "covariant", "Ljava/lang/Object;"))).requireSingle()).invoke(new Child(), new Object[0])); } });
        test("public-java-lookup", () -> { try (CodeScope scope = CodeScope.open(LOADER)) { ResolutionResult result = scope.resolve(QuerySpec.builder(Child.class, MemberKind.METHOD).name("toString").parameters().hierarchy(Hierarchy.JAVA_PUBLIC).build()); eq(Object.class, scope.exportRaw(result.requireSingle()).getDeclaringClass()); } });
    }
    private static void structuralContracts() {
        test("unique-null-overloads-ambiguous", () -> { try (CodeScope scope = CodeScope.open(LOADER)) { eq(ResolutionStatus.AMBIGUOUS, scope.resolve(QuerySpec.builder(Target.class, MemberKind.METHOD).name("pick").arguments(CompatibilityPolicy.LEGACY_BIL, new Object[]{null}).build()).status()); } });
        test("first-preserves-reflection-order", () -> {
            Method expected = Arrays.stream(Target.class.getDeclaredMethods()).filter(m -> m.getName().equals("pick")).findFirst().orElseThrow();
            try (CodeScope scope = CodeScope.open(LOADER)) { ResolutionResult result = scope.resolve(QuerySpec.builder(Target.class, MemberKind.METHOD).name("pick").selection(Selection.FIRST).build()); eq(expected, scope.exportRaw(result.requireSingle())); eq(Selection.FIRST, result.receipt().selection()); check(!result.receipt().coverage().complete(), "first not unique proof"); }
        });
        test("all-order-and-count", () -> {
            List<Method> expected = new ArrayList<>(); for (Method m : Target.class.getDeclaredMethods()) if (m.getName().equals("pick")) expected.add(m);
            try (CodeScope scope = CodeScope.open(LOADER)) {
                ResolutionResult result = scope.resolve(QuerySpec.builder(Target.class, MemberKind.METHOD).name("pick").selection(Selection.ALL).build()); eq(ResolutionStatus.ALL_MATCHES, result.status()); eq(2, result.members().size());
                for (int i = 0; i < expected.size(); i++) eq(expected.get(i), scope.exportRaw(result.members().get(i)));
            }
        });
        test("all-limit-partial", () -> { try (CodeScope scope = CodeScope.open(LOADER)) { eq(ResolutionStatus.PARTIAL, scope.resolve(QuerySpec.builder(Target.class, MemberKind.METHOD).name("pick").selection(Selection.ALL).resultLimit(1).build()).status()); } });
        test("budget-not-negative-proof", () -> { try (CodeScope scope = CodeScope.open(LOADER)) { ResolutionResult result = scope.resolve(QuerySpec.builder(Target.class, MemberKind.METHOD).name("noSuchMethod").candidateBudget(1).build()); eq(ResolutionStatus.PARTIAL, result.status()); eq(1, result.receipt().coverage().inspected()); } });
        test("complete-visible-not-found", () -> { try (CodeScope scope = CodeScope.open(LOADER)) { ResolutionResult result = scope.resolve(QuerySpec.builder(Target.class, MemberKind.METHOD).name("noSuchMethod").build()); eq(ResolutionStatus.NOT_FOUND_IN_VISIBLE_UNIVERSE, result.status()); check(result.receipt().coverage().complete(), "visible complete"); } });
        test("field-shadowing-unique-ambiguous", () -> { try (CodeScope scope = CodeScope.open(LOADER)) { eq(ResolutionStatus.AMBIGUOUS, scope.resolve(QuerySpec.builder(Child.class, MemberKind.FIELD).name("shadow").hierarchy(Hierarchy.SUPERCLASSES).build()).status()); } });
        test("field-nearest-explicit-first", () -> { try (CodeScope scope = CodeScope.open(LOADER)) { eq(Child.class, scope.exportRaw(scope.resolve(QuerySpec.builder(Child.class, MemberKind.FIELD).name("shadow").hierarchy(Hierarchy.SUPERCLASSES).selection(Selection.FIRST).build()).requireSingle()).getDeclaringClass()); } });
        test("hierarchy-includes-object", () -> { try (CodeScope scope = CodeScope.open(LOADER)) { eq(Object.class, scope.exportRaw(scope.resolve(QuerySpec.builder(Child.class, MemberKind.METHOD).name("hashCode").parameters().hierarchy(Hierarchy.SUPERCLASSES).build()).requireSingle()).getDeclaringClass()); } });
        test("bounded-hierarchy-partial", () -> { try (CodeScope scope = CodeScope.open(LOADER)) { eq(ResolutionStatus.PARTIAL, scope.resolve(QuerySpec.builder(Child.class, MemberKind.METHOD).name("hashCode").hierarchy(Hierarchy.SUPERCLASSES).depthLimit(1).build()).status()); } });
        test("bridge-filter", () -> { try (CodeScope scope = CodeScope.open(LOADER)) { Method method = (Method) scope.exportRaw(scope.resolve(QuerySpec.builder(Child.class, MemberKind.METHOD).name("covariant").bridge(false).build()).requireSingle()); eq(String.class, method.getReturnType()); } });
        test("legacy-no-widening", () -> { try (CodeScope scope = CodeScope.open(LOADER)) { eq(ResolutionStatus.NOT_FOUND_IN_VISIBLE_UNIVERSE, scope.resolve(QuerySpec.builder(Target.class, MemberKind.METHOD).name("wide").arguments(CompatibilityPolicy.LEGACY_BIL, 1).build()).status()); } });
        test("java-invocation-widening-query", () -> { try (CodeScope scope = CodeScope.open(LOADER)) { scope.resolve(QuerySpec.builder(Target.class, MemberKind.METHOD).name("wide").arguments(CompatibilityPolicy.JAVA_INVOCATION, 1).build()).requireSingle(); } });
        Class<?>[] primitives = {boolean.class, byte.class, short.class, char.class, int.class, long.class, float.class, double.class};
        Class<?>[] boxes = {Boolean.class, Byte.class, Short.class, Character.class, Integer.class, Long.class, Float.class, Double.class};
        boolean[][] widening = {{true,false,false,false,false,false,false,false},{false,true,false,false,false,false,false,false},{false,true,true,false,false,false,false,false},{false,false,false,true,false,false,false,false},{false,true,true,true,true,false,false,false},{false,true,true,true,true,true,false,false},{false,true,true,true,true,true,true,false},{false,true,true,true,true,true,true,true}};
        test("primitive-conversion-matrix-64", () -> { for (int p = 0; p < 8; p++) for (int a = 0; a < 8; a++) { eq(widening[p][a], CompatibilityPolicy.JAVA_INVOCATION.accepts(primitives[p], boxes[a])); eq(p == a, CompatibilityPolicy.LEGACY_BIL.accepts(primitives[p], boxes[a])); } });
        test("null-primitive-reference", () -> { check(!CompatibilityPolicy.LEGACY_BIL.accepts(int.class, null), "primitive rejects null"); check(CompatibilityPolicy.LEGACY_BIL.accepts(String.class, null), "reference accepts null"); });
        test("varargs-no-implicit-pack", () -> {
            try (CodeScope scope = CodeScope.open(LOADER)) {
                eq(ResolutionStatus.NOT_FOUND_IN_VISIBLE_UNIVERSE, scope.resolve(QuerySpec.builder(Target.class, MemberKind.METHOD).name("varargs").arguments(CompatibilityPolicy.JAVA_INVOCATION, "a", "b").build()).status());
                scope.resolve(QuerySpec.builder(Target.class, MemberKind.METHOD).name("varargs").arguments(CompatibilityPolicy.JAVA_INVOCATION, (Object) new String[]{"a", "b"}).build()).requireSingle();
            }
        });
        for (String name : new String[]{"zero", "one", "four", "eight"}) test("single-parameter-snapshot-" + name, () -> {
            try (CodeScope scope = CodeScope.open(LOADER)) {
                QuerySpec query = QuerySpec.builder(Target.class, MemberKind.METHOD).name(name).selection(Selection.FIRST).build();
                scope.resolve(query).requireSingle(); eq(1L, scope.diagnostics().get("parameterReads"));
                scope.resolve(query).requireSingle(); eq(1L, scope.diagnostics().get("parameterReads"));
            }
        });
        test("name-static-arity-early-exit-no-parameter-read", () -> {
            try (CodeScope scope = CodeScope.open(LOADER)) {
                scope.resolve(QuerySpec.builder(Target.class, MemberKind.METHOD).name("nonexistent").build());
                scope.resolve(QuerySpec.builder(Target.class, MemberKind.METHOD).name("four").staticOnly(true).build());
                scope.resolve(QuerySpec.builder(Target.class, MemberKind.METHOD).name("four").parameterCount(3).build());
                eq(0L, scope.diagnostics().get("parameterReads"));
            }
        });
        test("legacy-fixed-oracle-differential", () -> {
            Object[][] cases = {{1,2,3,4},{null,2,3,4},{1L,2,3,4},{1,"2",3,4}};
            for (Object[] arguments : cases) {
                Method expected = oldBilMatch(Target.class, "four", arguments);
                try (CodeScope scope = CodeScope.open(LOADER)) {
                    ResolutionResult result = scope.resolve(QuerySpec.builder(Target.class, MemberKind.METHOD).name("four").arguments(CompatibilityPolicy.LEGACY_BIL, arguments).selection(Selection.FIRST).build());
                    if (expected == null) eq(ResolutionStatus.NOT_FOUND_IN_VISIBLE_UNIVERSE, result.status()); else eq(expected, scope.exportRaw(result.requireSingle()));
                }
            }
        });
    }
    private static void lifecycleAndLoaderContracts() {
        test("code-epoch-invalidates-plans", () -> { try (CodeScope scope = CodeScope.open(LOADER)) { MethodPlan plan = scope.bindMethod(scope.resolve(QuerySpec.exact(descriptor("zero", "I"))).requireSingle()); scope.advanceCodeEpoch("test code replacement"); expect(StalePlanException.class, () -> plan.invoke(new Target(), new Object[0])); eq(1L, scope.codeEpoch()); eq(0L, scope.diagnostics().get("bindings")); } });
        test("epoch-overflow-new-identity", () -> {
            try (CodeScope scope = CodeScope.open(LOADER)) {
                Field epoch = CodeScope.class.getDeclaredField("codeEpoch"); epoch.setAccessible(true); epoch.setLong(scope, Long.MAX_VALUE);
                String before = scope.identity(); scope.advanceCodeEpoch("overflow fixture"); check(!before.equals(scope.identity()), "new identity"); eq(0L, scope.codeEpoch());
            }
        });
        test("different-scopes-reject-token", () -> { try (CodeScope one = CodeScope.open(LOADER); CodeScope two = CodeScope.open(LOADER)) { ResolvedMember member = one.resolve(QuerySpec.exact(descriptor("zero", "I"))).requireSingle(); expect(StalePlanException.class, () -> two.bindMethod(member)); } });
        test("raw-member-outlives-close", () -> { CodeScope scope = CodeScope.open(LOADER); Method raw = (Method) scope.exportRaw(scope.resolve(QuerySpec.exact(descriptor("zero", "I"))).requireSingle()); scope.close(); eq(0, raw.invoke(new Target())); });
        test("cache-only-complete-success", () -> { try (CodeScope scope = CodeScope.open(LOADER)) { QuerySpec success = QuerySpec.exact(descriptor("zero", "I")); scope.resolve(success); scope.resolve(success); eq(1L, scope.diagnostics().get("cacheHits")); QuerySpec partial = QuerySpec.builder(Target.class, MemberKind.METHOD).name("missing").candidateBudget(1).build(); scope.resolve(partial); scope.resolve(partial); eq(1L, scope.diagnostics().get("cachedQueries")); } });
        test("bounded-live-bindings-explicit-partial", () -> { try (CodeScope scope = CodeScope.open(LOADER, AccessPolicy.PRESERVE_JAVA_ACCESS, 1)) { MethodPlan plan = scope.bindMethod(scope.resolve(QuerySpec.exact(descriptor("zero", "I"))).requireSingle()); eq(ResolutionStatus.PARTIAL, scope.resolve(QuerySpec.exact(descriptor("one", "I", "I"))).status()); eq(0, plan.invoke(new Target(), new Object[0])); eq(1L, scope.diagnostics().get("bindings")); } });
        test("mixed-existing-and-new-bindings-capacity", () -> {
            MemberDescriptor stringPick = descriptor("pick", "Ljava/lang/String;", "Ljava/lang/String;");
            MemberDescriptor numberPick = descriptor("pick", "Ljava/lang/String;", "Ljava/lang/Number;");
            QuerySpec both = QuerySpec.builder(Target.class, MemberKind.METHOD).name("pick").selection(Selection.ALL).build();
            try (CodeScope scope = CodeScope.open(LOADER, AccessPolicy.PRESERVE_JAVA_ACCESS, 2)) {
                ResolvedMember prior = scope.resolve(QuerySpec.exact(stringPick)).requireSingle();
                MethodPlan existing = scope.bindMethod(prior); ResolutionResult full = scope.resolve(both);
                eq(ResolutionStatus.ALL_MATCHES, full.status()); check(full.receipt().coverage().complete(), "complete mixed result");
                eq(new HashSet<>(Arrays.asList(stringPick, numberPick)), new HashSet<>(full.receipt().descriptors()));
                eq(2, full.members().size()); eq(2L, scope.diagnostics().get("bindings"));
                ResolvedMember number = null;
                for (ResolvedMember token : full.members()) {
                    if (token.descriptor().equals(stringPick)) eq(prior.slot(), token.slot());
                    if (token.descriptor().equals(numberPick)) number = token;
                }
                check(number != null, "new member bound"); eq("string", existing.invoke(new Target(), new Object[]{"x"}));
                eq("number", scope.bindMethod(number).invoke(new Target(), new Object[]{1}));
            }
            try (CodeScope scope = CodeScope.open(LOADER, AccessPolicy.PRESERVE_JAVA_ACCESS, 2)) {
                MethodPlan existing = scope.bindMethod(scope.resolve(QuerySpec.exact(stringPick)).requireSingle());
                MethodPlan other = scope.bindMethod(scope.resolve(QuerySpec.exact(descriptor("zero", "I"))).requireSingle());
                ResolutionResult overflow = scope.resolve(both);
                eq(ResolutionStatus.PARTIAL, overflow.status()); eq("binding_capacity", overflow.receipt().reason());
                check(overflow.members().isEmpty() && overflow.receipt().descriptors().isEmpty(), "over-capacity result has no new bindings");
                eq(2L, scope.diagnostics().get("bindings")); eq("string", existing.invoke(new Target(), new Object[]{"x"}));
                eq(0, other.invoke(new Target(), new Object[0]));
            }
        });
        test("bounded-query-cache-eviction", () -> { try (CodeScope scope = CodeScope.open(LOADER, AccessPolicy.PRESERVE_JAVA_ACCESS, 1)) { scope.resolve(QuerySpec.exact(descriptor("zero", "I"))); scope.resolve(QuerySpec.builder(Target.class, MemberKind.METHOD).name("zero").parameters().build()); eq(1L, scope.diagnostics().get("cachedQueries")); } });
        test("query-lru-access-order-baseline", () -> {
            CodeScope scope = CodeScope.open(LOADER, AccessPolicy.PRESERVE_JAVA_ACCESS, 2);
            try {
                QuerySpec a = QuerySpec.builder(Target.class, MemberKind.METHOD).name("zero").selection(Selection.ALL).candidateBudget(127).build();
                QuerySpec b = QuerySpec.builder(Target.class, MemberKind.METHOD).name("zero").selection(Selection.ALL).candidateBudget(128).build();
                QuerySpec c = QuerySpec.builder(Target.class, MemberKind.METHOD).name("zero").selection(Selection.ALL).candidateBudget(256).build();
                ResolutionResult first = scope.resolve(a); eq(ResolutionStatus.ALL_MATCHES, first.status()); eq(1L, scope.diagnostics().get("lookups"));
                eq(ResolutionStatus.ALL_MATCHES, scope.resolve(b).status()); eq(2L, scope.diagnostics().get("lookups"));
                eq(first, scope.resolve(a)); eq(1L, scope.diagnostics().get("cacheHits")); eq(2L, scope.diagnostics().get("lookups"));
                eq(ResolutionStatus.ALL_MATCHES, scope.resolve(c).status()); eq(3L, scope.diagnostics().get("lookups"));
                eq(ResolutionStatus.ALL_MATCHES, scope.resolve(b).status()); eq(4L, scope.diagnostics().get("lookups"));
                eq(1L, scope.diagnostics().get("cacheHits")); eq(2L, scope.diagnostics().get("cachedQueries"));
                eq(1L, scope.diagnostics().get("bindings")); MethodPlan plan = scope.bindMethod(first.members().get(0)); eq(0, plan.invoke(new Target(), new Object[0]));
                scope.advanceCodeEpoch("lru baseline"); eq(0L, scope.diagnostics().get("cachedQueries")); eq(0L, scope.diagnostics().get("schemas")); eq(0L, scope.diagnostics().get("bindings"));
                expect(StalePlanException.class, () -> plan.invoke(new Target(), new Object[0]));
            } finally { scope.close(); }
            eq(0L, scope.diagnostics().get("cachedQueries")); eq(0L, scope.diagnostics().get("schemas")); eq(0L, scope.diagnostics().get("bindings"));
        });
        test("schema-lru-access-order-baseline", () -> {
            try (CodeScope scope = CodeScope.open(LOADER, AccessPolicy.PRESERVE_JAVA_ACCESS, 2)) {
                QuerySpec a = QuerySpec.builder(Target.class, MemberKind.METHOD).name("zero").returns(String.class).selection(Selection.ALL).build();
                QuerySpec b = QuerySpec.builder(Target.class, MemberKind.METHOD).name("one").returns(String.class).selection(Selection.ALL).build();
                QuerySpec c = QuerySpec.builder(Target.class, MemberKind.METHOD).name("four").returns(String.class).selection(Selection.ALL).build();
                eq(ResolutionStatus.NOT_FOUND_IN_VISIBLE_UNIVERSE, scope.resolve(a).status()); eq(1L, scope.diagnostics().get("parameterReads"));
                eq(ResolutionStatus.NOT_FOUND_IN_VISIBLE_UNIVERSE, scope.resolve(b).status()); eq(2L, scope.diagnostics().get("parameterReads"));
                eq(ResolutionStatus.NOT_FOUND_IN_VISIBLE_UNIVERSE, scope.resolve(a).status()); eq(2L, scope.diagnostics().get("parameterReads"));
                eq(ResolutionStatus.NOT_FOUND_IN_VISIBLE_UNIVERSE, scope.resolve(c).status()); eq(3L, scope.diagnostics().get("parameterReads"));
                eq(ResolutionStatus.NOT_FOUND_IN_VISIBLE_UNIVERSE, scope.resolve(b).status()); eq(4L, scope.diagnostics().get("parameterReads"));
                eq(2L, scope.diagnostics().get("schemas")); eq(0L, scope.diagnostics().get("cachedQueries")); eq(0L, scope.diagnostics().get("bindings"));
                scope.advanceCodeEpoch("schema lru baseline"); eq(0L, scope.diagnostics().get("schemas")); eq(0L, scope.diagnostics().get("parameterReads"));
            }
        });
        test("close-releases-owned-graphs", () -> { CodeScope scope = CodeScope.open(LOADER); scope.resolve(QuerySpec.exact(descriptor("zero", "I"))); scope.close(); eq(0L, scope.diagnostics().get("bindings")); eq(0L, scope.diagnostics().get("schemas")); eq(0L, scope.diagnostics().get("cachedQueries")); check(!scope.usesLoader(LOADER), "closed identity"); Field loader = CodeScope.class.getDeclaredField("loader"); loader.setAccessible(true); eq(null, loader.get(scope)); });
        test("MVX-L03-two-loader-identity", () -> {
            try (URLClassLoader a = isolated(fixtures); URLClassLoader b = isolated(fixtures); CodeScope one = CodeScope.open(a); CodeScope two = CodeScope.open(b)) {
                Class<?> ac = Class.forName("fixture.LoaderFixture", false, a), bc = Class.forName("fixture.LoaderFixture", false, b); check(ac != bc, "distinct Class");
                QuerySpec portable = QuerySpec.exact(MemberDescriptor.method("fixture.LoaderFixture", "echo", "Lfixture/LoaderFixture;", "Lfixture/LoaderFixture;"));
                eq(ac, one.exportRaw(one.resolve(portable).requireSingle()).getDeclaringClass()); eq(bc, two.exportRaw(two.resolve(portable).requireSingle()).getDeclaringClass());
                eq(ResolutionStatus.NOT_FOUND_IN_VISIBLE_UNIVERSE, one.resolve(QuerySpec.builder(ac, MemberKind.METHOD).name("echo").parameters(bc).build()).status());
            }
        });
        test("same-name-members-from-two-loaders-use-distinct-binding-slots", () -> {
            try (URLClassLoader a = isolated(fixtures); URLClassLoader b = isolated(fixtures);
                 CodeScope scope = CodeScope.open(a, AccessPolicy.PRESERVE_JAVA_ACCESS, 2)) {
                Class<?> first = Class.forName("fixture.LoaderFixture", false, a), second = Class.forName("fixture.LoaderFixture", false, b);
                check(first != second && first.getName().equals(second.getName()), "distinct loader identities");
                ResolutionResult ar = scope.resolve(QuerySpec.builder(first, MemberKind.METHOD).name("value").selection(Selection.ALL).build());
                ResolutionResult br = scope.resolve(QuerySpec.builder(second, MemberKind.METHOD).name("value").selection(Selection.ALL).build());
                eq(ResolutionStatus.ALL_MATCHES, ar.status()); eq(ResolutionStatus.ALL_MATCHES, br.status());
                ResolvedMember at = ar.members().get(0), bt = br.members().get(0);
                eq(at.descriptor(), bt.descriptor()); check(at.slot() != bt.slot(), "same descriptor does not merge Class identities");
                eq(first, scope.exportRaw(at).getDeclaringClass()); eq(second, scope.exportRaw(bt).getDeclaringClass());
                eq(2L, scope.diagnostics().get("bindings"));
                ResolutionResult overflow = scope.resolve(QuerySpec.builder(first, MemberKind.METHOD).name("echo").selection(Selection.ALL).build());
                eq(ResolutionStatus.PARTIAL, overflow.status()); eq("binding_capacity", overflow.receipt().reason());
                eq(first, scope.exportRaw(at).getDeclaringClass()); eq(second, scope.exportRaw(bt).getDeclaringClass());
                eq(2L, scope.diagnostics().get("bindings"));
            }
        });
        test("loader-shadow-exact-provider-and-structural-use-owner-signature-identity", () -> {
            try (URLClassLoader parent = isolated(fixtures);
                 ShadowLoader parentOwner = new ShadowLoader(fixtures.toUri().toURL(), parent, false);
                 ShadowLoader childOwner = new ShadowLoader(fixtures.toUri().toURL(), parent, true)) {
                Class<?> parentType = Class.forName("fixture.ShadowParam", false, parent);
                Class<?> parentDefinedOwner = Class.forName("fixture.ShadowOwner", false, parent);
                Class<?> parentShadow = Class.forName("fixture.ShadowParam", false, parentOwner);
                Class<?> childType = Class.forName("fixture.ShadowParam", false, childOwner);
                Class<?> childDefinedOwner = Class.forName("fixture.ShadowOwner", false, childOwner);
                check(parentType != parentShadow && parentType != childType && parentShadow != childType, "same binary name has distinct class identities");
                check(Class.forName("fixture.ShadowOwner", false, parentOwner) == parentDefinedOwner, "owner delegated to parent");
                check(childDefinedOwner.getClassLoader() == childOwner, "owner defined by child");
                verifyShadowMembers(parentOwner, parentDefinedOwner, parentType);
                verifyShadowMembers(childOwner, childDefinedOwner, childType);
            }
        });
        test("lookup-does-not-initialize", () -> {
            System.clearProperty("meguviz.fixture.initialized");
            try (URLClassLoader loader = isolated(fixtures); CodeScope scope = CodeScope.open(loader)) {
                MethodPlan plan = scope.bindMethod(scope.resolve(QuerySpec.exact(MemberDescriptor.method("fixture.LoaderFixture", "value", "Ljava/lang/String;"))).requireSingle());
                eq(null, System.getProperty("meguviz.fixture.initialized")); eq("fixture", plan.invoke(null, new Object[0])); eq("yes", System.getProperty("meguviz.fixture.initialized"));
            } finally { System.clearProperty("meguviz.fixture.initialized"); }
        });
        test("missing-signature-no-false-unique", () -> {
            try (URLClassLoader loader = isolated(missing); CodeScope scope = CodeScope.open(loader)) {
                ResolutionResult structural = scope.resolve(QuerySpec.builder("fixture.MissingSignatureFixture", MemberKind.METHOD).name("available").build());
                eq(ResolutionStatus.PARTIAL, structural.status()); check(!structural.receipt().coverage().complete(), "unknown signature");
                ResolutionResult exact = scope.resolve(QuerySpec.exact(MemberDescriptor.method("fixture.MissingSignatureFixture", "available", "Ljava/lang/String;")));
                check(exact.status() == ResolutionStatus.PARTIAL || exact.status() == ResolutionStatus.RESOLVED, "VM-dependent exact reflection surface");
            }
        });
        test("retained-plan-does-not-retain-loader-after-close", () -> {
            Object[] retained = closedPlan(); WeakReference<?> weak = (WeakReference<?>) retained[0];
            for (int i = 0; i < 30 && weak.get() != null; i++) { System.gc(); Thread.sleep(10); }
            System.out.println("GC_OBSERVATION loaderCollected=" + (weak.get() == null) + " (observation, no immediate-GC guarantee)");
            expect(StalePlanException.class, () -> ((MethodPlan) retained[1]).invoke(null, new Object[0]));
        });
    }
    private static void providerContracts() {
        test("MVX-X01-conflicting-provider", () -> expect(IllegalArgumentException.class, () -> ProviderRegistry.create(Arrays.asList(new Provider("a", "same"), new Provider("b", "same")))));
        test("duplicate-provider-id", () -> expect(IllegalArgumentException.class, () -> ProviderRegistry.create(Arrays.asList(new Provider("a", ""), new Provider("a", "")))));
        test("MVX-V01-unknown-spi-major", () -> { Provider provider = new Provider("new", ""); provider.major = 2; expect(IllegalArgumentException.class, () -> ProviderRegistry.create(Collections.singletonList(provider))); });
        test("provider-dependency-cycle", () -> { Provider a = new Provider("a", ""), b = new Provider("b", ""); a.dependencies = Collections.singletonList("b"); b.dependencies = Collections.singletonList("a"); expect(IllegalArgumentException.class, () -> ProviderRegistry.create(Arrays.asList(a,b))); });
        test("provider-deterministic-install-order", () -> { Provider a = new Provider("a", ""), b = new Provider("b", ""); b.dependencies = Collections.singletonList("a"); try (ProviderRegistry registry = ProviderRegistry.create(Arrays.asList(b,a))) { eq(Arrays.asList("a","b"), registry.installationOrder()); } });
        test("provider-explicit-conflict-owner", () -> { try (ProviderRegistry registry = ProviderRegistry.create(Arrays.asList(new Provider("a","cap"),new Provider("b","cap")), Collections.singletonMap("cap","b"))) { eq(Collections.singletonList("b"), registry.installationOrder()); } });
        test("provider-registry-checked-close-continues-reverse-order", () -> {
            IOException original = new IOException("checked cleanup"); List<String> order = new ArrayList<>();
            Provider a = new Provider("a", ""), b = new Provider("b", ""); a.closeOrder = order; b.closeOrder = order; b.closeFailure = original;
            ProviderRegistry registry = ProviderRegistry.create(Arrays.asList(a, b));
            IOException caught = expect(IOException.class, registry::close);
            check(caught == original, "checked cleanup throwable preserved"); eq(Arrays.asList("b", "a"), order); eq(1, a.closes.get()); eq(1, b.closes.get());
            registry.close(); eq(1, a.closes.get()); eq(1, b.closes.get());
        });
        test("scope-explicit-close-propagates-original-checked-failure", () -> {
            IOException original = new IOException("checked cleanup"); Provider provider = new Provider("p", ""); provider.closeFailure = original;
            CodeScope scope = providerScope(provider); IOException caught = expect(IOException.class, scope::close);
            check(caught == original, "explicit close preserves checked throwable"); eq(IOException.class.getName(), scope.closeFailureType());
            eq(CodeScope.State.CLOSED, scope.state()); eq(1, provider.closes.get()); scope.close();
        });
        test("scope-explicit-close-interruption-preserves-flag", () -> {
            InterruptedException original = new InterruptedException("checked cleanup"); Provider provider = new Provider("p", ""); provider.closeFailure = original;
            try {
                CodeScope scope = providerScope(provider); InterruptedException caught = expect(InterruptedException.class, scope::close);
                check(caught == original, "explicit close preserves interrupted throwable"); check(Thread.currentThread().isInterrupted(), "close interruption preserved");
                eq(InterruptedException.class.getName(), scope.closeFailureType()); eq(CodeScope.State.CLOSED, scope.state());
            } finally { Thread.interrupted(); }
        });
        test("provider-close-later-provider-cannot-clear-interrupted-failure", () -> {
            InterruptedException original = new InterruptedException("first cleanup"); List<String> order = new ArrayList<>();
            Provider a = new Provider("a", ""), b = new Provider("b", ""); a.closeOrder = order; b.closeOrder = order;
            a.clearInterruptOnClose = true; b.closeFailure = original;
            try {
                ProviderRegistry registry = ProviderRegistry.create(Arrays.asList(a, b));
                InterruptedException caught = expect(InterruptedException.class, registry::close);
                check(caught == original, "checked cleanup throwable preserved"); eq(Arrays.asList("b", "a"), order);
                check(a.closeSawInterrupt.get(), "later provider saw and cleared interrupt");
                check(Thread.currentThread().isInterrupted(), "later provider cannot erase observed interruption");
            } finally { Thread.interrupted(); }
        });
        test("provider-close-fatal-path-restores-observed-interrupt", () -> {
            InterruptedException interrupted = new InterruptedException("first cleanup"); SyntheticFatal fatal = new SyntheticFatal();
            List<String> order = new ArrayList<>(); Provider a = new Provider("a", ""), b = new Provider("b", "");
            a.closeOrder = order; b.closeOrder = order; a.clearInterruptOnClose = true; a.closeFailure = fatal; b.closeFailure = interrupted;
            try {
                ProviderRegistry registry = ProviderRegistry.create(Arrays.asList(a, b));
                check(expect(SyntheticFatal.class, registry::close) == fatal, "fatal cleanup propagates");
                eq(Arrays.asList("b", "a"), order); check(a.closeSawInterrupt.get(), "fatal provider cleared observed interruption");
                check(Thread.currentThread().isInterrupted(), "fatal path preserves observed interruption");
            } finally { Thread.interrupted(); }
        });
        test("scope-explicit-close-does-not-swallow-fatal", () -> {
            SyntheticFatal original = new SyntheticFatal(); Provider provider = new Provider("p", ""); provider.closeFailure = original;
            CodeScope scope = providerScope(provider); SyntheticFatal caught = expect(SyntheticFatal.class, scope::close);
            check(caught == original, "fatal cleanup propagates"); eq(CodeScope.State.CLOSED, scope.state());
        });
        test("provider-partial-unique-stays-partial", () -> { Provider provider = new Provider("p", ""); provider.complete = false; try (CodeScope scope = providerScope(provider)) { eq(ResolutionStatus.PARTIAL, scope.resolve(providerQuery()).status()); } });
        test("provider-wrong-identity-stale", () -> { Provider provider = new Provider("p", ""); provider.fingerprint = "wrong"; try (CodeScope scope = providerScope(provider)) { eq(ResolutionStatus.STALE, scope.resolve(providerQuery()).status()); } });
        test("provider-runtime-filtered-partial", () -> { Provider provider = new Provider("p", ""); provider.candidates = Arrays.asList(descriptor("zero","I"), descriptor("missing","I")); try (CodeScope scope = providerScope(provider)) { eq(ResolutionStatus.PARTIAL, scope.resolve(providerQuery()).status()); } });
        test("provider-duplicate-runtime-dedup", () -> {
            Provider provider = new Provider("p", ""); provider.candidates = Arrays.asList(descriptor("zero","I"),descriptor("zero","I"));
            try (CodeScope scope = CodeScope.open(LOADER, AccessPolicy.PRESERVE_JAVA_ACCESS, 1)) {
                scope.installProviders(ProviderRegistry.create(Collections.singletonList(provider)), IDENTITY);
                ResolvedMember unique = scope.resolve(providerQuery()).requireSingle();
                ResolutionResult all = scope.resolve(QuerySpec.builder(Target.class, MemberKind.METHOD).name("zero").selection(Selection.ALL).provider("p").build());
                eq(ResolutionStatus.ALL_MATCHES, all.status()); check(all.receipt().coverage().complete(), "duplicate provider candidates independently verified");
                eq(1, all.members().size()); eq(Collections.singletonList(descriptor("zero", "I")), all.receipt().descriptors());
                eq(unique.slot(), all.members().get(0).slot()); eq(1L, scope.diagnostics().get("bindings"));
            }
        });
        test("provider-partial-dedup-preserves-existing-and-new-bindings", () -> {
            Provider provider = new Provider("p", ""); provider.complete = false;
            provider.candidates = Arrays.asList(descriptor("zero", "I"), descriptor("zero", "I"), descriptor("one", "I", "I"), descriptor("one", "I", "I"));
            try (CodeScope scope = CodeScope.open(LOADER, AccessPolicy.PRESERVE_JAVA_ACCESS, 2)) {
                scope.installProviders(ProviderRegistry.create(Collections.singletonList(provider)), IDENTITY);
                ResolvedMember prior = scope.resolve(QuerySpec.exact(descriptor("zero", "I"))).requireSingle();
                MethodPlan existing = scope.bindMethod(prior);
                QuerySpec query = QuerySpec.builder(Target.class, MemberKind.METHOD).selection(Selection.ALL).provider("p").build();
                ResolutionResult partial = scope.resolve(query);
                eq(ResolutionStatus.PARTIAL, partial.status()); check(!partial.receipt().coverage().complete(), "provider partial remains incomplete");
                eq(Arrays.asList(descriptor("zero", "I"), descriptor("one", "I", "I")), partial.receipt().descriptors());
                eq(2, partial.members().size()); eq(prior.slot(), partial.members().get(0).slot()); eq(2L, scope.diagnostics().get("bindings"));
                eq(0, existing.invoke(new Target(), new Object[0])); eq(3, scope.bindMethod(partial.members().get(1)).invoke(new Target(), new Object[]{3}));
                ResolutionResult repeated = scope.resolve(query); eq(ResolutionStatus.PARTIAL, repeated.status()); eq(2, provider.discoveries.get());
                eq(2L, scope.diagnostics().get("bindings")); eq(1L, scope.diagnostics().get("cachedQueries"));
            }
        });
        test("provider-candidate-outside-domain", () -> { Provider provider = new Provider("p", ""); provider.candidates = Collections.singletonList(MemberDescriptor.method(String.class.getName(),"length","I")); try (CodeScope scope = providerScope(provider)) { eq(ResolutionStatus.PARTIAL, scope.resolve(providerQuery()).status()); } });
        test("provider-weak-identity-rejected", () -> { try (CodeScope scope = CodeScope.open(LOADER); ProviderRegistry registry = ProviderRegistry.create(Collections.singletonList(new Provider("p","")))) { expect(IllegalArgumentException.class, () -> scope.installProviders(registry, CodeIdentity.unverified("fixture", QuerySpec.RULE_VERSION))); } });
        test("provider-snapshot-owned-and-frozen", () -> { Provider provider = new Provider("p", ""); CodeScope scope = providerScope(provider); scope.resolve(providerQuery()); try (ProviderRegistry registry = ProviderRegistry.create(Collections.singletonList(new Provider("other","")))) { expect(IllegalStateException.class, () -> scope.installProviders(registry, IDENTITY)); } scope.close(); scope.close(); eq(1, provider.closes.get()); });
        test("provider-epoch-requires-new-content-identity", () -> { Provider provider = new Provider("p", ""); try (CodeScope scope = providerScope(provider)) { scope.resolve(providerQuery()).requireSingle(); scope.advanceCodeEpoch("changed code"); eq(ResolutionStatus.UNAVAILABLE, scope.resolve(providerQuery()).status()); } });
        test("MVX-X02-bound-10000-no-candidate-checks", () -> {
            Provider provider = new Provider("p", ""); try (CodeScope scope = providerScope(provider)) {
                MethodPlan plan = scope.bindMethod(scope.resolve(providerQuery()).requireSingle()); long before = scope.diagnostics().get("candidateChecks");
                Target target = new Target(); for (int i = 0; i < 10000; i++) eq(0, plan.invoke(target, new Object[0]));
                eq(before, scope.diagnostics().get("candidateChecks")); eq(1, provider.discoveries.get());
            }
        });
        test("provider-independent-finite-domain-replay", () -> { Provider provider = new Provider("p", ""); try (CodeScope scope = providerScope(provider)) { ResolutionResult result = scope.resolve(providerQuery()); Method expected = Target.class.getDeclaredMethod("zero"); eq(expected, scope.exportRaw(result.requireSingle())); eq(Target.class.getName()+"#zero()I", result.receipt().descriptors().get(0).signature()); } });
        test("provider-complete-claim-does-not-prove-unique", () -> {
            Provider provider = new Provider("p", ""); provider.candidates = Collections.singletonList(descriptor("pick","Ljava/lang/String;","Ljava/lang/String;"));
            try (CodeScope scope = providerScope(provider)) {
                ResolutionResult result = scope.resolve(QuerySpec.builder(Target.class,MemberKind.METHOD).name("pick").provider("p").build());
                eq(ResolutionStatus.PARTIAL,result.status()); eq("provider_coverage_not_proven",result.receipt().reason());
            }
        });
        test("provider-complete-claim-does-not-prove-absence", () -> { Provider provider = new Provider("p", ""); provider.candidates = Collections.emptyList(); try (CodeScope scope = providerScope(provider)) { eq(ResolutionStatus.PARTIAL,scope.resolve(providerQuery()).status()); } });
        test("provider-first-preserves-declared-and-java-public-order", () -> {
            for (Hierarchy hierarchy : new Hierarchy[]{Hierarchy.DECLARED, Hierarchy.JAVA_PUBLIC}) {
                Method[] domain = hierarchy == Hierarchy.JAVA_PUBLIC ? Target.class.getMethods() : Target.class.getDeclaredMethods();
                List<MemberDescriptor> reversed = new ArrayList<>();
                for (Method method : domain) if (method.getName().equals("pick")) reversed.add(MemberDescriptor.from(method));
                eq(2, reversed.size()); Collections.reverse(reversed);
                Provider provider = new Provider("p", ""); provider.candidates = reversed;
                try (CodeScope scope = providerScope(provider)) {
                    QuerySpec direct = QuerySpec.builder(Target.class, MemberKind.METHOD).name("pick").hierarchy(hierarchy).selection(Selection.FIRST).build();
                    QuerySpec supplied = QuerySpec.builder(Target.class, MemberKind.METHOD).name("pick").hierarchy(hierarchy).selection(Selection.FIRST).provider("p").build();
                    ResolutionResult expected = scope.resolve(direct), actual = scope.resolve(supplied);
                    eq(ResolutionStatus.RESOLVED, expected.status()); eq(ResolutionStatus.RESOLVED, actual.status());
                    Method first = (Method) scope.exportRaw(expected.requireSingle()), provided = (Method) scope.exportRaw(actual.requireSingle());
                    Method other = Arrays.stream(domain).filter(method -> MemberDescriptor.from(method).equals(reversed.get(0))).findFirst().orElseThrow();
                    check(!Objects.equals(first.invoke(new Target(), new Object[]{null}), other.invoke(new Target(), new Object[]{null})), "fixture candidates produce different values");
                    check(!reversed.get(0).equals(MemberDescriptor.from(first)), "fixture reverses actual first");
                    eq(first, provided); eq(first.invoke(new Target(), new Object[]{null}), provided.invoke(new Target(), new Object[]{null}));
                }
            }
        });
        test("provider-first-superclasses-preserves-shadowing-order", () -> {
            Field child = Child.class.getDeclaredField("shadow"), parent = Parent.class.getDeclaredField("shadow");
            Provider provider = new Provider("p", ""); provider.candidates = Arrays.asList(MemberDescriptor.from(parent), MemberDescriptor.from(child));
            try (CodeScope scope = providerScope(provider)) {
                QuerySpec direct = QuerySpec.builder(Child.class, MemberKind.FIELD).name("shadow").hierarchy(Hierarchy.SUPERCLASSES).selection(Selection.FIRST).build();
                QuerySpec supplied = QuerySpec.builder(Child.class, MemberKind.FIELD).name("shadow").hierarchy(Hierarchy.SUPERCLASSES).selection(Selection.FIRST).provider("p").build();
                ResolutionResult expected = scope.resolve(direct), actual = scope.resolve(supplied);
                eq(ResolutionStatus.RESOLVED, expected.status()); eq(ResolutionStatus.RESOLVED, actual.status());
                Field first = (Field) scope.exportRaw(expected.requireSingle()), provided = (Field) scope.exportRaw(actual.requireSingle());
                Child target = new Child(); target.shadow = "child"; ((Parent) target).shadow = 17;
                check(!Objects.equals(child.get(target), parent.get(target)), "hidden fields have different values");
                eq(child, first); eq(first, provided); eq(first.get(target), provided.get(target));
            }
        });
        test("provider-first-incomplete-does-not-claim-success", () -> {
            List<MemberDescriptor> reversed = new ArrayList<>();
            for (Method method : Target.class.getDeclaredMethods()) if (method.getName().equals("pick")) reversed.add(MemberDescriptor.from(method));
            eq(2, reversed.size()); Collections.reverse(reversed);
            for (boolean providerClaimsComplete : new boolean[]{false, true}) {
                Provider provider = new Provider("p", ""); provider.candidates = Collections.singletonList(reversed.get(0)); provider.complete = providerClaimsComplete;
                try (CodeScope scope = providerScope(provider)) {
                    ResolutionResult result = scope.resolve(QuerySpec.builder(Target.class, MemberKind.METHOD).name("pick").selection(Selection.FIRST).provider("p").build());
                    eq(ResolutionStatus.PARTIAL, result.status()); check(!result.receipt().coverage().complete(), "incomplete set cannot prove first");
                }
            }
        });
        test("provider-java-public-rejects-private", () -> {
            Provider provider = new Provider("p", ""); provider.candidates = Collections.singletonList(descriptor("privateValue","I"));
            try (CodeScope scope = providerScope(provider)) { eq(ResolutionStatus.PARTIAL,scope.resolve(QuerySpec.builder(Target.class,MemberKind.METHOD).name("privateValue").hierarchy(Hierarchy.JAVA_PUBLIC).provider("p").build()).status()); }
        });
        test("provider-explicit-cancellation-is-distinct", () -> { Provider provider = new Provider("p", ""); provider.cancel=true; try (CodeScope scope=providerScope(provider)) { eq(ResolutionStatus.CANCELLED,scope.resolve(providerQuery()).status()); eq(0L,scope.diagnostics().get("cachedQueries")); } });
        test("provider-empty-result-interrupted-is-not-complete-absence", () -> {
            eq(0, EmptyTarget.class.getDeclaredMethods().length);
            for (Class<?> owner : new Class<?>[]{EmptyTarget.class, Target.class}) {
                Provider provider = new Provider("p", ""); provider.candidates = Collections.emptyList(); provider.interruptBeforeReturn = true;
                try (CodeScope scope = providerScope(provider)) {
                    QuerySpec query = QuerySpec.builder(owner, MemberKind.METHOD).name("missing").selection(Selection.ALL).provider("p").build();
                    ResolutionResult result = scope.resolve(query);
                    eq(ResolutionStatus.CANCELLED, result.status()); check(!result.receipt().coverage().complete(), "interrupted result is incomplete");
                    check(Thread.currentThread().isInterrupted(), "provider interrupt preserved"); eq(0L, scope.diagnostics().get("cachedQueries"));
                    eq(1, provider.discoveries.get());
                } finally { Thread.interrupted(); }
            }
        });
        test("provider-empty-result-without-interruption-is-complete-absence", () -> {
            eq(0, EmptyTarget.class.getDeclaredMethods().length);
            for (Class<?> owner : new Class<?>[]{EmptyTarget.class, Target.class}) {
                Provider provider = new Provider("p", ""); provider.candidates = Collections.emptyList();
                try (CodeScope scope = providerScope(provider)) {
                    QuerySpec query = QuerySpec.builder(owner, MemberKind.METHOD).name("missing").selection(Selection.ALL).provider("p").build();
                    ResolutionResult result = scope.resolve(query);
                    eq(ResolutionStatus.NOT_FOUND_IN_VISIBLE_UNIVERSE, result.status()); check(result.receipt().coverage().complete(), "complete empty domain");
                    check(!Thread.currentThread().isInterrupted(), "thread not interrupted"); eq(0L, scope.diagnostics().get("cachedQueries"));
                }
            }
        });
        test("provider-interruption-with-full-binding-capacity-stays-cancelled", () -> {
            Provider provider = new Provider("p", ""); provider.interruptFlagDescriptorAfterDiscovery = true;
            try (CodeScope scope = CodeScope.open(LOADER, AccessPolicy.PRESERVE_JAVA_ACCESS, 1)) {
                scope.installProviders(ProviderRegistry.create(Collections.singletonList(provider)), IDENTITY);
                MethodPlan existing = scope.bindMethod(scope.resolve(QuerySpec.exact(descriptor("one", "I", "I"))).requireSingle());
                eq(1L, scope.diagnostics().get("bindings"));
                ResolutionResult result = scope.resolve(QuerySpec.builder(Target.class, MemberKind.METHOD).name("zero").selection(Selection.ALL).provider("p").build());
                eq(ResolutionStatus.CANCELLED, result.status()); eq("interrupted", result.receipt().reason());
                eq(ResolutionReceipt.InterruptionOrigin.UNKNOWN, result.receipt().interruptionOrigin());
                check(!result.receipt().coverage().complete(), "cancelled coverage incomplete"); check(Thread.currentThread().isInterrupted(), "interrupt preserved");
                check(result.members().isEmpty() && result.receipt().descriptors().isEmpty(), "capacity failure exposes no unbound members");
                eq(1L, scope.diagnostics().get("bindings")); eq(3, existing.invoke(new Target(), new Object[]{3}));
                check(Thread.currentThread().isInterrupted(), "existing plan preserves interrupt");
            } finally { Thread.interrupted(); }
        });
        test("provider-interruption-with-available-capacity-retains-partial-members", () -> {
            Provider provider = new Provider("p", ""); provider.interruptFlagDescriptorAfterDiscovery = true;
            try (CodeScope scope = CodeScope.open(LOADER, AccessPolicy.PRESERVE_JAVA_ACCESS, 2)) {
                scope.installProviders(ProviderRegistry.create(Collections.singletonList(provider)), IDENTITY);
                MethodPlan existing = scope.bindMethod(scope.resolve(QuerySpec.exact(descriptor("one", "I", "I"))).requireSingle());
                ResolutionResult result = scope.resolve(QuerySpec.builder(Target.class, MemberKind.METHOD).name("zero").selection(Selection.ALL).provider("p").build());
                eq(ResolutionStatus.CANCELLED, result.status()); eq("interrupted", result.receipt().reason());
                eq(ResolutionReceipt.InterruptionOrigin.UNKNOWN, result.receipt().interruptionOrigin());
                check(!result.receipt().coverage().complete(), "cancelled coverage incomplete"); check(Thread.currentThread().isInterrupted(), "interrupt preserved");
                eq(1, result.members().size()); eq(descriptor("zero", "I"), result.members().get(0).descriptor());
                eq(Collections.singletonList(descriptor("zero", "I")), result.receipt().descriptors());
                eq(Target.class.getDeclaredMethod("zero"), scope.exportRaw(result.members().get(0)));
                eq(2L, scope.diagnostics().get("bindings")); eq(3, existing.invoke(new Target(), new Object[]{3}));
            } finally { Thread.interrupted(); }
        });
        test("provider-noninterrupted-binding-capacity-remains-partial", () -> {
            Provider provider = new Provider("p", "");
            try (CodeScope scope = CodeScope.open(LOADER, AccessPolicy.PRESERVE_JAVA_ACCESS, 1)) {
                scope.installProviders(ProviderRegistry.create(Collections.singletonList(provider)), IDENTITY);
                MethodPlan existing = scope.bindMethod(scope.resolve(QuerySpec.exact(descriptor("one", "I", "I"))).requireSingle());
                ResolutionResult result = scope.resolve(QuerySpec.builder(Target.class, MemberKind.METHOD).name("zero").selection(Selection.ALL).provider("p").build());
                eq(ResolutionStatus.PARTIAL, result.status()); eq("binding_capacity", result.receipt().reason());
                eq(1L, scope.diagnostics().get("bindings")); eq(3, existing.invoke(new Target(), new Object[]{3}));
                check(!Thread.currentThread().isInterrupted(), "noninterrupted query");
            }
        });
    }
    private static void providerArityContracts() {
        String owner = ArityFixture.class.getName();
        MemberDescriptor methodInt = MemberDescriptor.method(owner, "choose", "V", "I");
        MemberDescriptor methodString = MemberDescriptor.method(owner, "choose", "V", "Ljava/lang/String;");
        List<MemberDescriptor> methods = Arrays.asList(MemberDescriptor.method(owner, "choose", "V"), methodInt,
                methodString, MemberDescriptor.method(owner, "choose", "V", "I", "I"));
        MemberDescriptor constructorInt = MemberDescriptor.constructor(owner, "I");
        MemberDescriptor constructorString = MemberDescriptor.constructor(owner, "Ljava/lang/String;");
        List<MemberDescriptor> constructors = Arrays.asList(MemberDescriptor.constructor(owner), constructorInt,
                constructorString, MemberDescriptor.constructor(owner, "I", "I"));
        List<MemberDescriptor> fields = Arrays.asList(MemberDescriptor.field(owner, "first", "I"),
                MemberDescriptor.field(owner, "second", "Ljava/lang/String;"));
        test("provider-coverage-mixed-arity-methods", () -> {
            Provider provider = new Provider("p", ""); provider.candidates = methods;
            try (CodeScope scope = providerScope(provider)) {
                ResolutionResult result = scope.resolve(QuerySpec.builder(ArityFixture.class, MemberKind.METHOD)
                        .name("choose").parameterCount(1).selection(Selection.ALL).provider("p").build());
                eq(ResolutionStatus.ALL_MATCHES, result.status());
                check(result.receipt().coverage().complete(), "method domain independently verified");
                eq(new HashSet<>(Arrays.asList(methodInt, methodString)), new HashSet<>(result.receipt().descriptors()));
                eq(4, result.receipt().coverage().inspected());
            }
        });
        test("provider-coverage-mixed-arity-constructors", () -> {
            Provider provider = new Provider("p", ""); provider.candidates = constructors;
            try (CodeScope scope = providerScope(provider)) {
                ResolutionResult result = scope.resolve(QuerySpec.builder(ArityFixture.class, MemberKind.CONSTRUCTOR)
                        .parameterCount(1).selection(Selection.ALL).provider("p").build());
                eq(ResolutionStatus.ALL_MATCHES, result.status());
                check(result.receipt().coverage().complete(), "constructor domain independently verified");
                eq(new HashSet<>(Arrays.asList(constructorInt, constructorString)), new HashSet<>(result.receipt().descriptors()));
                eq(4, result.receipt().coverage().inspected());
            }
        });
        test("provider-coverage-field-arity-zero-and-nonzero", () -> {
            Provider provider = new Provider("p", ""); provider.candidates = fields;
            try (CodeScope scope = providerScope(provider)) {
                ResolutionResult zero = scope.resolve(QuerySpec.builder(ArityFixture.class, MemberKind.FIELD)
                        .parameterCount(0).selection(Selection.ALL).provider("p").build());
                eq(ResolutionStatus.ALL_MATCHES, zero.status()); check(zero.receipt().coverage().complete(), "zero-arity fields complete");
                eq(new HashSet<>(fields), new HashSet<>(zero.receipt().descriptors()));
                ResolutionResult one = scope.resolve(QuerySpec.builder(ArityFixture.class, MemberKind.FIELD)
                        .parameterCount(1).selection(Selection.ALL).provider("p").build());
                eq(ResolutionStatus.NOT_FOUND_IN_VISIBLE_UNIVERSE, one.status());
                check(one.receipt().coverage().complete(), "nonzero-arity fields empty and complete");
                check(one.members().isEmpty(), "no field has parameters");
            }
        });
        test("provider-coverage-arity-missing-candidate-is-partial", () -> {
            Provider provider = new Provider("p", ""); provider.candidates = Collections.singletonList(methodInt);
            try (CodeScope scope = providerScope(provider)) {
                ResolutionResult result = scope.resolve(QuerySpec.builder(ArityFixture.class, MemberKind.METHOD)
                        .name("choose").parameterCount(1).selection(Selection.ALL).provider("p").build());
                eq(ResolutionStatus.PARTIAL, result.status());
                check(!result.receipt().coverage().complete(), "missing same-arity overload cannot prove completeness");
                eq("provider_coverage_not_proven", result.receipt().reason());
            }
        });
        test("provider-coverage-arity-verifier-budget-is-partial", () -> {
            Provider provider = new Provider("p", ""); provider.candidates = Arrays.asList(methodInt, methodString);
            try (CodeScope scope = providerScope(provider)) {
                ResolutionResult result = scope.resolve(QuerySpec.builder(ArityFixture.class, MemberKind.METHOD)
                        .name("choose").parameterCount(1).selection(Selection.ALL).candidateBudget(2).provider("p").build());
                eq(ResolutionStatus.PARTIAL, result.status());
                check(!result.receipt().coverage().complete(), "verifier budget cannot prove completeness");
                eq("provider_coverage_not_proven", result.receipt().reason());
            }
        });
        test("provider-coverage-arity-provider-partial-stays-partial", () -> {
            Provider provider = new Provider("p", ""); provider.candidates = methods; provider.complete = false;
            try (CodeScope scope = providerScope(provider)) {
                ResolutionResult result = scope.resolve(QuerySpec.builder(ArityFixture.class, MemberKind.METHOD)
                        .name("choose").parameterCount(1).selection(Selection.ALL).provider("p").build());
                eq(ResolutionStatus.PARTIAL, result.status());
                check(!result.receipt().coverage().complete(), "provider partial is never upgraded");
            }
        });
    }
    private static void concurrencyContracts() {
        test("close-in-flight-target-nonblocking", () -> {
            CodeScope scope = CodeScope.open(LOADER); Blocking target = new Blocking(); MethodPlan plan = scope.bindMethod(scope.resolve(QuerySpec.exact(MemberDescriptor.method(Blocking.class.getName(),"run","I"))).requireSingle());
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<Object> result = executor.submit(() -> plan.invoke(target, new Object[0])); await(target.entered);
                scope.close(); eq(CodeScope.State.RETIRING, scope.state()); expect(StalePlanException.class, () -> plan.invoke(target, new Object[0]));
                target.exit.countDown(); eq(1, result.get(3, TimeUnit.SECONDS)); eq(CodeScope.State.CLOSED, scope.state());
            } finally { target.exit.countDown(); scope.close(); executor.shutdownNow(); }
        });
        test("interrupted-query-preserves-flag", () -> { try (CodeScope scope = CodeScope.open(LOADER)) { Thread.currentThread().interrupt(); eq(ResolutionStatus.CANCELLED, scope.resolve(QuerySpec.exact(descriptor("zero","I"))).status()); check(Thread.currentThread().isInterrupted(), "interrupt preserved"); } finally { Thread.interrupted(); } });
        test("receipt-legacy-constructor-interruption-origin-unknown", () -> {
            ResolutionReceipt receipt = new ResolutionReceipt("fixture", Selection.UNIQUE, ResolutionStatus.CANCELLED,
                    new Coverage("fixture", false, 0, "wait_interrupted"), Collections.emptyList(), "wait_interrupted", null);
            eq(ResolutionReceipt.InterruptionOrigin.UNKNOWN, receipt.interruptionOrigin());
        });
        test("owner-descriptor-sneaky-interrupt-is-not-waiter", () -> {
            Provider provider = new Provider("p", ""); provider.interruptDescriptorAfterDiscovery = true;
            try (CodeScope scope = providerScope(provider)) {
                ResolutionResult result = scope.resolve(providerQuery());
                eq(1, provider.discoveries.get()); eq(ResolutionStatus.CANCELLED, result.status());
                eq("wait_interrupted", result.receipt().reason()); eq(null, result.receipt().causeType());
                eq(ResolutionReceipt.InterruptionOrigin.UNKNOWN, result.receipt().interruptionOrigin());
                check(Thread.currentThread().isInterrupted(), "owner interrupt preserved");
            } finally { Thread.interrupted(); }
        });
        test("scope-waiter-interrupt-is-isolated-and-classified", () -> {
            Provider provider = new Provider("p", ""); provider.entered = new CountDownLatch(1); provider.exit = new CountDownLatch(1);
            ExecutorService executor = Executors.newSingleThreadExecutor(); AtomicReference<ResolutionResult> waiterResult = new AtomicReference<>();
            AtomicBoolean waiterFlag = new AtomicBoolean(); AtomicReference<Throwable> waiterFailure = new AtomicReference<>();
            try (CodeScope scope = providerScope(provider)) {
                Future<ResolutionResult> owner = executor.submit(() -> scope.resolve(providerQuery())); await(provider.entered);
                Thread waiter = new Thread(() -> { try { waiterResult.set(scope.resolve(providerQuery())); waiterFlag.set(Thread.currentThread().isInterrupted()); } catch (Throwable failure) { waiterFailure.set(failure); } });
                waiter.start(); awaitScopeWaiter(scope, waiter); waiter.interrupt(); waiter.join(2000);
                check(!waiter.isAlive(), "waiter finished"); eq(null, waiterFailure.get());
                ResolutionResult result = waiterResult.get(); eq(ResolutionStatus.CANCELLED, result.status()); eq("wait_interrupted", result.receipt().reason());
                eq(ResolutionReceipt.InterruptionOrigin.SINGLE_FLIGHT_WAITER, result.receipt().interruptionOrigin()); check(waiterFlag.get(), "waiter interrupt preserved");
                check(!owner.isDone(), "waiter did not cancel owner"); provider.exit.countDown(); owner.get(3, TimeUnit.SECONDS).requireSingle(); eq(1, provider.discoveries.get());
            } finally { provider.exit.countDown(); executor.shutdownNow(); }
        });
        test("shared-owner-descriptor-sneaky-interrupt-preserves-waiter-failure", () -> {
            Provider provider = new Provider("p", ""); provider.entered = new CountDownLatch(1); provider.exit = new CountDownLatch(1); provider.interruptDescriptorAfterDiscovery = true;
            ExecutorService executor = Executors.newSingleThreadExecutor(); AtomicBoolean ownerFlag = new AtomicBoolean(), waiterFlag = new AtomicBoolean();
            AtomicReference<Throwable> waiterFailure = new AtomicReference<>();
            try (CodeScope scope = providerScope(provider)) {
                Future<ResolutionResult> owner = executor.submit(() -> { ResolutionResult result = scope.resolve(providerQuery()); ownerFlag.set(Thread.currentThread().isInterrupted()); return result; }); await(provider.entered);
                Thread waiter = new Thread(() -> { try { scope.resolve(providerQuery()); waiterFailure.set(new AssertionError("expected owner failure")); } catch (Throwable failure) { waiterFailure.set(failure); } finally { waiterFlag.set(Thread.currentThread().isInterrupted()); } });
                waiter.start(); awaitScopeWaiter(scope, waiter); provider.exit.countDown();
                ResolutionResult result = owner.get(3, TimeUnit.SECONDS); waiter.join(2000); check(!waiter.isAlive(), "waiter finished");
                eq(ResolutionStatus.CANCELLED, result.status()); eq("wait_interrupted", result.receipt().reason());
                eq(ResolutionReceipt.InterruptionOrigin.UNKNOWN, result.receipt().interruptionOrigin()); check(ownerFlag.get(), "owner interrupt preserved");
                check(waiterFailure.get() instanceof IllegalStateException, "waiter sees owner failure");
                check(waiterFailure.get().getCause() instanceof InterruptedException, "waiter sees original owner failure as cause");
                check(!waiterFlag.get(), "waiter was not interrupted"); eq(1, provider.discoveries.get());
            } finally { provider.exit.countDown(); executor.shutdownNow(); }
        });
        test("shared-provider-interruption-is-not-waiter-interruption", () -> {
            Provider provider = new Provider("p", ""); provider.entered = new CountDownLatch(1); provider.exit = new CountDownLatch(1); provider.interruptAfterWait = true;
            ExecutorService executor = Executors.newSingleThreadExecutor(); AtomicBoolean ownerFlag = new AtomicBoolean(), waiterFlag = new AtomicBoolean();
            AtomicReference<ResolutionResult> waiterResult = new AtomicReference<>(); AtomicReference<Throwable> waiterFailure = new AtomicReference<>();
            try (CodeScope scope = providerScope(provider)) {
                Future<ResolutionResult> owner = executor.submit(() -> { ResolutionResult result = scope.resolve(providerQuery()); ownerFlag.set(Thread.currentThread().isInterrupted()); return result; }); await(provider.entered);
                Thread waiter = new Thread(() -> { try { waiterResult.set(scope.resolve(providerQuery())); waiterFlag.set(Thread.currentThread().isInterrupted()); } catch (Throwable failure) { waiterFailure.set(failure); } });
                waiter.start(); awaitScopeWaiter(scope, waiter); provider.exit.countDown();
                ResolutionResult ownerResult = owner.get(3, TimeUnit.SECONDS); waiter.join(2000); check(!waiter.isAlive(), "waiter finished"); eq(null, waiterFailure.get());
                ResolutionResult shared = waiterResult.get(); check(ownerResult == shared, "shared owner result");
                eq(ResolutionStatus.CANCELLED, shared.status()); eq("provider_interrupted", shared.receipt().reason()); eq(InterruptedException.class.getName(), shared.receipt().causeType());
                eq(ResolutionReceipt.InterruptionOrigin.UNKNOWN, shared.receipt().interruptionOrigin()); check(ownerFlag.get(), "owner interrupt preserved"); check(!waiterFlag.get(), "waiter was not interrupted"); eq(1, provider.discoveries.get());
            } finally { provider.exit.countDown(); executor.shutdownNow(); }
        });
        test("last-release-provider-close-preserves-query-interruption", () -> {
            Provider provider = new Provider("p", ""); provider.entered = new CountDownLatch(1); provider.exit = new CountDownLatch(1);
            provider.interruptAfterWait = true; provider.clearInterruptOnClose = true;
            CodeScope scope = providerScope(provider); AtomicReference<ResolutionResult> result = new AtomicReference<>();
            AtomicReference<Throwable> failure = new AtomicReference<>(); AtomicBoolean callerFlag = new AtomicBoolean();
            Thread owner = new Thread(() -> { try { result.set(scope.resolve(providerQuery())); callerFlag.set(Thread.currentThread().isInterrupted()); } catch (Throwable error) { failure.set(error); } });
            try {
                owner.start(); await(provider.entered);
                Field field = CodeScope.class.getDeclaredField("coordinator"); field.setAccessible(true); Object coordinator = field.get(scope);
                synchronized (coordinator) {
                    provider.exit.countDown(); long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2); boolean blocked = false;
                    while (System.nanoTime() < deadline) {
                        if (owner.getState() == Thread.State.BLOCKED) {
                            for (StackTraceElement frame : owner.getStackTrace()) if (frame.getClassName().equals(QueryCoordinator.class.getName()) && frame.getMethodName().equals("execute")) blocked = true;
                        }
                        if (blocked) break; Thread.yield();
                    }
                    check(blocked, "owner completed query and is blocked removing flight"); eq(0, provider.closes.get());
                    scope.close(); eq(CodeScope.State.RETIRING, scope.state());
                }
                owner.join(3000); check(!owner.isAlive(), "owner finished"); eq(null, failure.get());
                eq(ResolutionStatus.CANCELLED, result.get().status()); eq("provider_interrupted", result.get().receipt().reason());
                check(provider.closeSawInterrupt.get(), "provider observed and cleared interrupt");
                check(callerFlag.get(), "last release preserved caller interrupt"); eq(1, provider.closes.get()); eq(CodeScope.State.CLOSED, scope.state());
            } finally { provider.exit.countDown(); owner.interrupt(); owner.join(3000); scope.close(); }
        });
        test("single-flight-shared-miss", () -> {
            Provider provider = new Provider("p", ""); provider.entered = new CountDownLatch(1); provider.exit = new CountDownLatch(1);
            ExecutorService executor = Executors.newFixedThreadPool(4);
            try (CodeScope scope = providerScope(provider)) {
                List<Future<ResolutionResult>> futures = new ArrayList<>(); for (int i = 0; i < 4; i++) futures.add(executor.submit(() -> scope.resolve(providerQuery())));
                await(provider.entered); provider.exit.countDown(); for (Future<ResolutionResult> future : futures) future.get(3, TimeUnit.SECONDS).requireSingle(); eq(1, provider.discoveries.get());
            } finally { provider.exit.countDown(); executor.shutdownNow(); }
        });
        test("single-flight-reentrant-does-not-deadlock", () -> {
            QueryCoordinator<String,Integer> coordinator = new QueryCoordinator<>(4);
            expect(IllegalStateException.class, () -> coordinator.execute("q", () -> { try { return coordinator.execute("q", () -> 2, 100); } catch (InterruptedException | TimeoutException e) { throw new AssertionError(e); } }, 100));
            coordinator.close();
        });
        test("single-flight-waiter-timeout-owner-continues", () -> {
            QueryCoordinator<String,Integer> coordinator = new QueryCoordinator<>(4); CountDownLatch entered = new CountDownLatch(1), exit = new CountDownLatch(1); ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<Integer> owner = executor.submit(() -> coordinator.execute("q", () -> { entered.countDown(); blockingAwait(exit); return 7; }, 100)); await(entered);
                expect(TimeoutException.class, () -> coordinator.execute("q", () -> 8, 20)); exit.countDown(); eq(7, owner.get(3,TimeUnit.SECONDS));
            } finally { exit.countDown(); coordinator.close(); executor.shutdownNow(); }
        });
        test("close-completes-waiters-before-provider-returns", () -> {
            Provider provider = new Provider("p", ""); provider.entered = new CountDownLatch(1); provider.exit = new CountDownLatch(1); CodeScope scope = providerScope(provider); ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                Future<ResolutionResult> owner = executor.submit(() -> scope.resolve(providerQuery())); await(provider.entered);
                Future<ResolutionResult> waiter = executor.submit(() -> scope.resolve(providerQuery()));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2); while (scope.diagnostics().get("inFlight") < 2 && System.nanoTime() < deadline) Thread.yield();
                check(scope.diagnostics().get("inFlight") == 2, "waiter entered"); scope.close(); eq(ResolutionStatus.STALE, waiter.get(1,TimeUnit.SECONDS).status()); eq(0,provider.closes.get());
                provider.exit.countDown(); eq(ResolutionStatus.STALE,owner.get(3,TimeUnit.SECONDS).status()); eq(1,provider.closes.get());
            } finally { provider.exit.countDown(); scope.close(); executor.shutdownNow(); }
        });
        test("single-flight-different-keys-parallel", () -> {
            QueryCoordinator<String,Integer> coordinator = new QueryCoordinator<>(4); CountDownLatch both = new CountDownLatch(2), exit = new CountDownLatch(1); ExecutorService executor = Executors.newFixedThreadPool(2);
            try { Future<Integer> a = executor.submit(() -> coordinator.execute("a", () -> { both.countDown(); blockingAwait(exit); return 1; },100)); Future<Integer> b = executor.submit(() -> coordinator.execute("b", () -> { both.countDown(); blockingAwait(exit); return 2; },100)); await(both); exit.countDown(); eq(1,a.get(3,TimeUnit.SECONDS)); eq(2,b.get(3,TimeUnit.SECONDS)); }
            finally { exit.countDown(); coordinator.close(); executor.shutdownNow(); }
        });
        test("single-flight-cross-thread-cycle", () -> {
            QueryCoordinator<String,Integer> coordinator = new QueryCoordinator<>(4); CyclicBarrier barrier = new CyclicBarrier(2); ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                Future<Integer> a = executor.submit(() -> coordinator.execute("a", () -> cycle(coordinator, "b", barrier),1000));
                Future<Integer> b = executor.submit(() -> coordinator.execute("b", () -> cycle(coordinator, "a", barrier),1000));
                expect(ExecutionException.class, () -> a.get(3,TimeUnit.SECONDS)); expect(ExecutionException.class, () -> b.get(3,TimeUnit.SECONDS));
            } finally { coordinator.close(); executor.shutdownNow(); }
        });
        test("single-flight-one-waiter-interrupt-independent", () -> {
            QueryCoordinator<String,Integer> coordinator = new QueryCoordinator<>(4); CountDownLatch entered = new CountDownLatch(1), exit = new CountDownLatch(1), waiterStarted = new CountDownLatch(1); ExecutorService executor = Executors.newSingleThreadExecutor(); AtomicBoolean interrupted = new AtomicBoolean();
            try {
                Future<Integer> owner = executor.submit(() -> coordinator.execute("q", () -> { entered.countDown(); blockingAwait(exit); return 9; },1000)); await(entered);
                Thread waiter = new Thread(() -> { waiterStarted.countDown(); try { coordinator.execute("q", () -> 5,1000); } catch (InterruptedException e) { interrupted.set(true); } catch (TimeoutException e) { throw new AssertionError(e); } }); waiter.start(); await(waiterStarted); waiter.interrupt(); waiter.join(2000); check(interrupted.get(), "waiter interrupted"); exit.countDown(); eq(9,owner.get(3,TimeUnit.SECONDS));
            } finally { exit.countDown(); coordinator.close(); executor.shutdownNow(); }
        });
        test("provider-close-failure-preserves-target-exception", () -> {
            Provider provider = new Provider("p", ""); provider.failClose = true; CodeScope scope = providerScope(provider);
            ClosingTarget target = new ClosingTarget(scope);
            MethodPlan plan = scope.bindMethod(scope.resolve(QuerySpec.exact(MemberDescriptor.method(ClosingTarget.class.getName(),"run","V"))).requireSingle());
            InvocationTargetException failure = expect(InvocationTargetException.class, () -> plan.invoke(target,new Object[0]));
            eq("original target",failure.getCause().getMessage()); eq(IllegalStateException.class.getName(),scope.closeFailureType()); eq(CodeScope.State.CLOSED,scope.state());
        });
        test("provider-checked-close-failure-preserves-target-exception", () -> {
            IOException original = new IOException("checked cleanup"); List<String> order = new ArrayList<>();
            Provider a = new Provider("a", ""), b = new Provider("b", ""); a.closeOrder = order; b.closeOrder = order; b.closeFailure = original;
            CodeScope scope = CodeScope.open(LOADER); scope.installProviders(ProviderRegistry.create(Arrays.asList(a, b)), IDENTITY);
            ClosingTarget target = new ClosingTarget(scope);
            MethodPlan plan = scope.bindMethod(scope.resolve(QuerySpec.exact(MemberDescriptor.method(ClosingTarget.class.getName(),"run","V"))).requireSingle());
            InvocationTargetException failure = expect(InvocationTargetException.class, () -> plan.invoke(target, new Object[0]));
            check(failure.getCause() instanceof IllegalArgumentException, "original target cause"); eq("original target", failure.getCause().getMessage());
            eq(Arrays.asList("b", "a"), order); eq(IOException.class.getName(), scope.closeFailureType()); eq(CodeScope.State.CLOSED, scope.state());
        });
        test("provider-fatal-close-during-target-is-not-swallowed", () -> {
            SyntheticFatal original = new SyntheticFatal(); Provider provider = new Provider("p", ""); provider.closeFailure = original;
            CodeScope scope = providerScope(provider); ClosingTarget target = new ClosingTarget(scope);
            MethodPlan plan = scope.bindMethod(scope.resolve(QuerySpec.exact(MemberDescriptor.method(ClosingTarget.class.getName(),"run","V"))).requireSingle());
            SyntheticFatal caught = expect(SyntheticFatal.class, () -> plan.invoke(target, new Object[0]));
            check(caught == original, "fatal cleanup propagates during target invocation"); eq(1, provider.closes.get()); eq(CodeScope.State.CLOSED, scope.state());
        });
    }
    private static void scopeFastPathContracts() {
        test("lock-free-admission-close-drains-admitted-calls-before-provider-close", () -> {
            Provider provider = new Provider("p", ""); CodeScope scope = providerScope(provider); ExecutorService executor = Executors.newFixedThreadPool(3);
            Blocking[] targets = {new Blocking(), new Blocking(), new Blocking()};
            try {
                MethodPlan plan = scope.bindMethod(scope.resolve(QuerySpec.exact(MemberDescriptor.method(Blocking.class.getName(), "run", "I"))).requireSingle());
                List<Future<Object>> calls = new ArrayList<>();
                for (Blocking target : targets) { calls.add(executor.submit(() -> plan.invoke(target, new Object[0]))); await(target.entered); }
                eq(3L, scope.diagnostics().get("inFlight"));
                scope.close(); eq(CodeScope.State.RETIRING, scope.state()); eq(0, provider.closes.get());
                Blocking late = new Blocking(); late.exit.countDown();
                eq("scope identity or epoch is stale", expect(StalePlanException.class, () -> plan.invoke(late, new Object[0])).getMessage());
                eq(1L, late.entered.getCount()); eq(3L, scope.diagnostics().get("inFlight"));
                targets[0].exit.countDown(); eq(1, calls.get(0).get(3, TimeUnit.SECONDS));
                targets[1].exit.countDown(); eq(1, calls.get(1).get(3, TimeUnit.SECONDS));
                eq(0, provider.closes.get()); eq(CodeScope.State.RETIRING, scope.state()); eq(1L, scope.diagnostics().get("inFlight"));
                targets[2].exit.countDown(); eq(1, calls.get(2).get(3, TimeUnit.SECONDS));
                eq(1, provider.closes.get()); eq(CodeScope.State.CLOSED, scope.state()); eq(0L, scope.diagnostics().get("inFlight"));
                scope.close(); eq(1, provider.closes.get());
            } finally { for (Blocking target : targets) target.exit.countDown(); scope.close(); executor.shutdownNow(); }
        });
        test("lock-free-admission-close-race-stress", () -> {
            Thread main = Thread.currentThread();
            for (int round = 0; round < 150; round++) {
                Provider provider = new Provider("p", ""); CodeScope scope = providerScope(provider); Counting counting = new Counting();
                AtomicInteger violations = new AtomicInteger(); AtomicBoolean closeReturned = new AtomicBoolean();
                provider.onClose = () -> {
                    if (counting.inFlight.get() != 0) violations.incrementAndGet();
                    if (Thread.currentThread() != main && !Counting.ADMITTED.get()) violations.incrementAndGet();
                    counting.providerClosed.set(true);
                };
                MethodPlan plan = scope.bindMethod(scope.resolve(QuerySpec.exact(MemberDescriptor.method(Counting.class.getName(), "run", "I"))).requireSingle());
                CountDownLatch running = new CountDownLatch(4); List<Thread> workers = new ArrayList<>(); AtomicReference<Throwable> failure = new AtomicReference<>();
                for (int w = 0; w < 4; w++) {
                    Thread worker = new Thread(() -> {
                        boolean first = true;
                        try {
                            for (;;) {
                                boolean late = closeReturned.get(); Counting.ADMITTED.set(false);
                                try { plan.invoke(counting, new Object[0]); } catch (StalePlanException stale) { if (Counting.ADMITTED.get()) violations.incrementAndGet(); return; }
                                if (late) violations.incrementAndGet();
                                if (first) { first = false; running.countDown(); }
                            }
                        } catch (Throwable error) { failure.set(error); }
                        finally { if (first) running.countDown(); }
                    });
                    workers.add(worker); worker.start();
                }
                await(running); for (int spin = round % 7; spin > 0; spin--) Thread.yield();
                scope.close(); closeReturned.set(true);
                for (Thread worker : workers) { worker.join(3000); check(!worker.isAlive(), "worker stopped after close"); }
                eq(null, failure.get()); eq(0, violations.get()); eq(0, counting.enteredAfterProviderClose.get());
                eq(1, provider.closes.get()); eq(CodeScope.State.CLOSED, scope.state()); eq(0L, scope.diagnostics().get("inFlight"));
            }
        });
        test("lock-free-admission-epoch-race", () -> {
            for (int round = 0; round < 50; round++) {
                try (CodeScope scope = CodeScope.open(LOADER)) {
                    Counting counting = new Counting(); AtomicBoolean advanced = new AtomicBoolean(); AtomicInteger violations = new AtomicInteger();
                    MethodPlan plan = scope.bindMethod(scope.resolve(QuerySpec.exact(MemberDescriptor.method(Counting.class.getName(), "run", "I"))).requireSingle());
                    CountDownLatch running = new CountDownLatch(3); List<Thread> workers = new ArrayList<>(); AtomicReference<Throwable> failure = new AtomicReference<>();
                    for (int w = 0; w < 3; w++) {
                        Thread worker = new Thread(() -> {
                            boolean first = true;
                            try {
                                for (;;) {
                                    boolean late = advanced.get();
                                    try { plan.invoke(counting, new Object[0]); } catch (StalePlanException stale) { return; }
                                    if (late) violations.incrementAndGet();
                                    if (first) { first = false; running.countDown(); }
                                }
                            } catch (Throwable error) { failure.set(error); }
                            finally { if (first) running.countDown(); }
                        });
                        workers.add(worker); worker.start();
                    }
                    await(running); scope.advanceCodeEpoch("race"); advanced.set(true);
                    for (Thread worker : workers) { worker.join(3000); check(!worker.isAlive(), "worker stopped after epoch"); }
                    eq(null, failure.get()); eq(0, violations.get()); eq(0L, scope.diagnostics().get("inFlight")); eq(CodeScope.State.OPEN, scope.state());
                    eq(1, scope.bindMethod(scope.resolve(QuerySpec.exact(MemberDescriptor.method(Counting.class.getName(), "run", "I"))).requireSingle()).invoke(counting, new Object[0]));
                }
            }
        });
        test("slot-table-indexing-across-epochs-and-forged-tokens", () -> {
            CodeScope scope = CodeScope.open(LOADER);
            try {
                ResolutionResult all = scope.resolve(QuerySpec.builder(String.class, MemberKind.METHOD).selection(Selection.ALL).build());
                eq(ResolutionStatus.ALL_MATCHES, all.status()); int n = all.members().size(); check(n > 127, "beyond the small Long cache: " + n);
                for (int i = 0; i < n; i++) {
                    ResolvedMember token = all.members().get(i); eq((long) i + 1, token.slot());
                    eq(token.descriptor(), MemberDescriptor.from(scope.exportRaw(token)));
                }
                MethodPlan before = scope.bindMethod(scope.resolve(QuerySpec.exact(descriptor("zero", "I"))).requireSingle()); eq(0, before.invoke(new Target(), new Object[0]));
                scope.advanceCodeEpoch("slot table");
                ResolvedMember zero = scope.resolve(QuerySpec.exact(descriptor("zero", "I"))).requireSingle();
                eq((long) n + 2, zero.slot()); eq(1L, zero.codeEpoch()); eq(0, scope.bindMethod(zero).invoke(new Target(), new Object[0]));
                eq("scope identity or epoch is stale", expect(StalePlanException.class, () -> before.invoke(new Target(), new Object[0])).getMessage());
                String id = scope.identity();
                for (long slot : new long[]{n + 1, n + 3, 0, -1, Long.MAX_VALUE, Long.MIN_VALUE}) {
                    eq("binding no longer exists", expect(StalePlanException.class, () -> scope.exportRaw(new ResolvedMember(id, 1, slot, zero.descriptor()))).getMessage());
                }
                eq("binding descriptor mismatch", expect(StalePlanException.class, () -> scope.exportRaw(new ResolvedMember(id, 1, zero.slot(), descriptor("one", "I", "I")))).getMessage());
                check(zero.descriptor() != descriptor("zero", "I") && zero.descriptor().equals(descriptor("zero", "I")), "fixture");
                eq(Target.class.getDeclaredMethod("zero"), scope.exportRaw(new ResolvedMember(new String(id), 1, zero.slot(), descriptor("zero", "I"))));
                eq("scope identity or epoch is stale", expect(StalePlanException.class, () -> scope.exportRaw(new ResolvedMember(id, 0, zero.slot(), zero.descriptor()))).getMessage());
                scope.close();
                eq("scope identity or epoch is stale", expect(StalePlanException.class, () -> scope.exportRaw(zero)).getMessage());
                eq(0L, scope.diagnostics().get("bindings"));
            } finally { scope.close(); }
        });
        test("scope-identity-unique-uuid-text", () -> {
            Set<String> identities = new HashSet<>();
            for (int i = 0; i < 2000; i++) {
                CodeScope scope = CodeScope.open(LOADER); String id = scope.identity();
                eq(id, UUID.fromString(id).toString()); identities.add(id); scope.close(); eq(id, scope.identity());
            }
            eq(2000, identities.size());
        });
        test("lazy-resolver-diagnostics-shape", () -> {
            List<String> keys = Arrays.asList("cacheHits", "cachedQueries", "bindings", "schemas", "parameterReads", "candidateChecks", "lookups", "inFlight");
            CodeScope scope = CodeScope.open(LOADER);
            eq(keys, new ArrayList<>(scope.diagnostics().keySet())); for (long value : scope.diagnostics().values()) eq(0L, value);
            scope.resolve(QuerySpec.exact(descriptor("zero", "I"))).requireSingle(); eq(1L, scope.diagnostics().get("lookups")); eq(1L, scope.diagnostics().get("bindings"));
            scope.advanceCodeEpoch("lazy"); eq(keys, new ArrayList<>(scope.diagnostics().keySet())); for (long value : scope.diagnostics().values()) eq(0L, value);
            scope.close(); for (long value : scope.diagnostics().values()) eq(0L, value);
            CodeScope unused = CodeScope.open(LOADER); unused.advanceCodeEpoch("never resolved"); eq(1L, unused.codeEpoch()); unused.close(); eq(CodeScope.State.CLOSED, unused.state());
        });
    }
    private static void memberTableContracts() {
        test("member-table-cache-structural-results-identical-to-fresh-scopes", () -> {
            List<QuerySpec> queries = new ArrayList<>();
            for (Class<?> owner : new Class<?>[]{Target.class, Child.class, ArityFixture.class})
                for (MemberKind kind : MemberKind.values())
                    for (Hierarchy hierarchy : Hierarchy.values())
                        for (Selection selection : new Selection[]{Selection.UNIQUE, Selection.FIRST, Selection.ALL})
                            for (String name : new String[]{null, "pick", "shadow", "choose", "hashCode", "<init>", "first", "covariant"}) {
                                if (kind == MemberKind.CONSTRUCTOR && hierarchy != Hierarchy.DECLARED) continue;
                                QuerySpec.Builder builder = QuerySpec.builder(owner, kind).hierarchy(hierarchy).selection(selection);
                                if (name != null) builder.name(name);
                                queries.add(builder.build());
                                queries.add(builder.parameterCount(1).build());
                            }
            try (CodeScope shared = CodeScope.open(LOADER)) {
                for (int pass = 0; pass < 2; pass++) for (QuerySpec query : queries) {
                    try (CodeScope fresh = CodeScope.open(LOADER)) {
                        ResolutionResult expected = fresh.resolve(query), actual = shared.resolve(query);
                        String label = query.summary();
                        eq(expected.status(), actual.status()); eq(expected.receipt().reason(), actual.receipt().reason());
                        eq(expected.receipt().coverage().complete(), actual.receipt().coverage().complete());
                        eq(expected.receipt().coverage().inspected(), actual.receipt().coverage().inspected());
                        eq(expected.receipt().descriptors(), actual.receipt().descriptors());
                        eq(expected.members().size(), actual.members().size());
                        for (int i = 0; i < expected.members().size(); i++) check(fresh.exportRaw(expected.members().get(i)).equals(shared.exportRaw(actual.members().get(i))), "same member order: " + label);
                    }
                }
                check(memberTables(shared) > 0, "tables retained in shared scope");
            }
        });
        test("member-table-cache-first-keeps-reflection-order", () -> {
            List<Method> expected = new ArrayList<>(); for (Method m : Target.class.getDeclaredMethods()) if (m.getName().equals("pick")) expected.add(m);
            try (CodeScope scope = CodeScope.open(LOADER)) {
                scope.resolve(QuerySpec.builder(Target.class, MemberKind.METHOD).name("zero").build()).requireSingle();
                eq(Target.class.getDeclaredMethods().length, memberTables(scope));
                eq(expected.get(0), scope.exportRaw(scope.resolve(QuerySpec.builder(Target.class, MemberKind.METHOD).name("pick").selection(Selection.FIRST).build()).requireSingle()));
                ResolutionResult all = scope.resolve(QuerySpec.builder(Target.class, MemberKind.METHOD).name("pick").selection(Selection.ALL).build());
                for (int i = 0; i < expected.size(); i++) eq(expected.get(i), scope.exportRaw(all.members().get(i)));
                eq(Target.class.getDeclaredMethods().length, memberTables(scope));
            }
        });
        test("member-table-cache-bounded-by-capacity-lru", () -> {
            int target = Target.class.getDeclaredMethods().length, arity = ArityFixture.class.getDeclaredMethods().length, child = Child.class.getDeclaredMethods().length;
            try (CodeScope small = CodeScope.open(LOADER, AccessPolicy.PRESERVE_JAVA_ACCESS, target - 1)) {
                small.resolve(QuerySpec.builder(Target.class, MemberKind.METHOD).name("zero").build()).requireSingle(); eq(0, memberTables(small));
                small.resolve(QuerySpec.builder(Child.class, MemberKind.METHOD).name("covariant").bridge(false).build()).requireSingle(); eq(child, memberTables(small));
            }
            try (CodeScope scope = CodeScope.open(LOADER, AccessPolicy.PRESERVE_JAVA_ACCESS, target + arity)) {
                scope.resolve(QuerySpec.builder(Target.class, MemberKind.METHOD).name("zero").selection(Selection.ALL).build()); eq(target, memberTables(scope));
                scope.resolve(QuerySpec.builder(ArityFixture.class, MemberKind.METHOD).name("choose").parameterCount(0).build()).requireSingle(); eq(target + arity, memberTables(scope));
                scope.resolve(QuerySpec.builder(Target.class, MemberKind.METHOD).name("one").build()).requireSingle(); // touch: Target becomes most recent
                scope.resolve(QuerySpec.builder(Child.class, MemberKind.METHOD).name("covariant").bridge(false).build()).requireSingle();
                eq(target + child, memberTables(scope)); // eldest ArityFixture table evicted
                scope.resolve(QuerySpec.builder(EmptyTarget.class, MemberKind.FIELD).build());
                check(memberTables(scope) <= target + arity, "bounded");
            }
        });
        test("member-table-cache-cleared-by-epoch-and-close", () -> {
            CodeScope scope = CodeScope.open(LOADER);
            scope.resolve(QuerySpec.builder(Target.class, MemberKind.METHOD).name("zero").build()).requireSingle(); check(memberTables(scope) > 0, "retained");
            scope.advanceCodeEpoch("tables"); eq(0, memberTables(scope));
            scope.resolve(QuerySpec.builder(Target.class, MemberKind.FIELD).name("number").build()).requireSingle(); eq(Target.class.getDeclaredFields().length, memberTables(scope));
            scope.close(); eq(0, memberTables(scope));
        });
        test("member-table-cache-no-negative-caching-of-failed-enumeration", () -> {
            try (URLClassLoader loader = isolated(missing); CodeScope scope = CodeScope.open(loader)) {
                QuerySpec query = QuerySpec.builder("fixture.MissingSignatureFixture", MemberKind.METHOD).name("available").build();
                for (int i = 0; i < 3; i++) { ResolutionResult result = scope.resolve(query); eq(ResolutionStatus.PARTIAL, result.status()); check(!result.receipt().coverage().complete(), "unknown signature"); }
                eq(0, memberTables(scope));
            }
        });
        test("member-table-cache-concurrent-different-queries-same-owner", () -> {
            ExecutorService executor = Executors.newFixedThreadPool(4);
            try (CodeScope scope = CodeScope.open(LOADER)) {
                List<Callable<Method>> work = new ArrayList<>();
                String[] names = {"zero", "one", "four", "eight", "wide", "varargs", "nullable", "throwOnce"};
                for (int round = 0; round < 8; round++) for (String name : names) {
                    QuerySpec query = QuerySpec.builder(Target.class, MemberKind.METHOD).name(name).candidateBudget(1000 + round).build();
                    work.add(() -> (Method) scope.exportRaw(scope.resolve(query).requireSingle()));
                }
                List<Future<Method>> results = executor.invokeAll(work);
                for (int i = 0; i < results.size(); i++) eq(names[i % names.length], results.get(i).get(3, TimeUnit.SECONDS).getName());
                eq(Target.class.getDeclaredMethods().length, memberTables(scope));
                eq((long) names.length, scope.diagnostics().get("bindings"));
            } finally { executor.shutdownNow(); }
        });
    }
    private static int memberTables(CodeScope scope) throws ReflectiveOperationException {
        Field field = CodeScope.class.getDeclaredField("resolver"); field.setAccessible(true);
        Object resolver = field.get(scope); if (resolver == null) return 0;
        Field members = ReflectionResolver.class.getDeclaredField("tableMembers"); members.setAccessible(true);
        synchronized (resolver) { return members.getInt(resolver); }
    }
    private static int cycle(QueryCoordinator<String,Integer> coordinator, String dependency, CyclicBarrier barrier) {
        try { barrier.await(2,TimeUnit.SECONDS); return coordinator.execute(dependency, () -> 0,1000); } catch (InterruptedException | TimeoutException | BrokenBarrierException e) { throw new AssertionError(e); }
    }
    private static QuerySpec providerQuery() { return QuerySpec.builder(Target.class,MemberKind.METHOD).name("zero").provider("p").build(); }
    private static CodeScope providerScope(Provider provider) { CodeScope scope = CodeScope.open(LOADER); scope.installProviders(ProviderRegistry.create(Collections.singletonList(provider)), IDENTITY); return scope; }
    private static MemberDescriptor descriptor(String name, String returns, String... parameters) { return MemberDescriptor.method(Target.class.getName(), name, returns, parameters); }
    private static void verifyShadowMembers(ClassLoader loader, Class<?> owner, Class<?> signatureType) throws Exception {
        Class<?> arrayType = java.lang.reflect.Array.newInstance(signatureType, 0).getClass();
        List<Member> expected = Arrays.asList(owner.getDeclaredMethod("method", signatureType), owner.getDeclaredMethod("returns"),
                owner.getDeclaredMethod("arrayMethod", arrayType), owner.getDeclaredMethod("returnsArray"),
                owner.getDeclaredField("field"), owner.getDeclaredField("arrayField"),
                owner.getDeclaredConstructor(signatureType), owner.getDeclaredConstructor(arrayType));
        for (Member member : expected) {
            MemberDescriptor descriptor = MemberDescriptor.from(member); Provider provider = new Provider("p", ""); provider.candidates = Collections.singletonList(descriptor);
            try (CodeScope scope = CodeScope.open(loader)) {
                scope.installProviders(ProviderRegistry.create(Collections.singletonList(provider)), IDENTITY);
                ResolutionResult direct = scope.resolve(shadowQuery(owner, member, false));
                eq(ResolutionStatus.ALL_MATCHES, direct.status()); check(direct.receipt().coverage().complete(), "structural coverage");
                eq(1, direct.members().size()); eq(member, scope.exportRaw(direct.members().get(0)));
                ResolutionResult exact = scope.resolve(QuerySpec.exact(descriptor));
                eq(ResolutionStatus.RESOLVED, exact.status()); eq(member, scope.exportRaw(exact.requireSingle()));
                ResolutionResult supplied = scope.resolve(shadowQuery(owner, member, true));
                eq(ResolutionStatus.ALL_MATCHES, supplied.status()); check(supplied.receipt().coverage().complete(), "provider coverage");
                eq(1, supplied.members().size()); eq(member, scope.exportRaw(supplied.members().get(0)));
            }
        }
    }
    private static QuerySpec shadowQuery(Class<?> owner, Member member, boolean provider) {
        MemberKind kind = member instanceof Method ? MemberKind.METHOD : member instanceof Field ? MemberKind.FIELD : MemberKind.CONSTRUCTOR;
        QuerySpec.Builder builder = QuerySpec.builder(owner, kind).selection(Selection.ALL);
        if (member instanceof Method) {
            Method method = (Method) member; builder.name(method.getName()).parameters(method.getParameterTypes()).returns(method.getReturnType());
        } else if (member instanceof Field) {
            Field field = (Field) member; builder.name(field.getName()).returns(field.getType());
        } else builder.parameters(((Constructor<?>) member).getParameterTypes());
        if (provider) builder.provider("p");
        return builder.build();
    }
    private static void prepareFixtures() throws IOException {
        Path root = Path.of("").toAbsolutePath(); while (!Files.isDirectory(root.resolve("test-fixtures/isolated-src"))) { root = root.getParent(); if (root == null) throw new IOException("test-fixtures not found"); }
        fixtures = Files.createDirectories(root.resolve("ref-core/build/isolated-fixtures/all")); missing = Files.createDirectories(root.resolve("ref-core/build/isolated-fixtures/missing/fixture")).getParent();
        Path sources = root.resolve("test-fixtures/isolated-src/fixture");
        int result = ToolProvider.getSystemJavaCompiler().run(null,System.out,System.err,"--release","17","-d",fixtures.toString(),sources.resolve("LoaderFixture.java").toString(),sources.resolve("MissingType.java").toString(),sources.resolve("MissingSignatureFixture.java").toString(),sources.resolve("ShadowParam.java").toString(),sources.resolve("ShadowOwner.java").toString());
        if (result != 0) throw new IOException("fixture compilation failed: " + result);
        Files.copy(fixtures.resolve("fixture/MissingSignatureFixture.class"), missing.resolve("fixture/MissingSignatureFixture.class"), StandardCopyOption.REPLACE_EXISTING);
        Path completeJar = root.resolve("ref-core/build/isolated-fixtures/complete-fixture.jar");
        fixtureJar(completeJar, fixtures, "fixture/LoaderFixture.class", "fixture/MissingType.class", "fixture/MissingSignatureFixture.class", "fixture/ShadowParam.class", "fixture/ShadowOwner.class");
        Path missingJar = root.resolve("ref-core/build/isolated-fixtures/missing-signature-fixture.jar");
        fixtureJar(missingJar, missing, "fixture/MissingSignatureFixture.class");
        fixtures = completeJar; missing = missingJar;
    }
    private static void fixtureJar(Path output, Path directory, String... entries) throws IOException {
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(output))) {
            for (String entry : entries) { JarEntry item = new JarEntry(entry); item.setTime(0); jar.putNextEntry(item); Files.copy(directory.resolve(entry), jar); jar.closeEntry(); }
        }
    }
    private static URLClassLoader isolated(Path directory) throws MalformedURLException { return new URLClassLoader(new URL[]{directory.toUri().toURL()}, null); }
    private static final class ShadowLoader extends URLClassLoader {
        private final boolean childOwner;
        ShadowLoader(URL location, ClassLoader parent, boolean childOwner) { super(new URL[]{location}, parent); this.childOwner = childOwner; }
        @Override protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.equals("fixture.ShadowParam") || (childOwner && name.equals("fixture.ShadowOwner"))) {
                Class<?> type = findLoadedClass(name); if (type == null) type = findClass(name);
                if (resolve) resolveClass(type); return type;
            }
            return super.loadClass(name, resolve);
        }
    }
    private static Object[] closedPlan() throws Exception {
        URLClassLoader loader = isolated(fixtures); WeakReference<ClassLoader> weak = new WeakReference<>(loader); CodeScope scope = CodeScope.open(loader);
        MethodPlan plan = scope.bindMethod(scope.resolve(QuerySpec.exact(MemberDescriptor.method("fixture.LoaderFixture","value","Ljava/lang/String;"))).requireSingle()); scope.close(); loader.close(); return new Object[]{weak,plan};
    }
    // Fixed BIL baseline matcher: boxing + isInstance, no widening; deliberately independent of production policy.
    private static Method oldBilMatch(Class<?> owner, String name, Object[] args) {
        for (Method method : owner.getDeclaredMethods()) {
            if (!method.getName().equals(name) || method.getParameterTypes().length != args.length) continue;
            boolean accepted = true;
            for (int i = 0; i < args.length; i++) {
                Class<?> type = method.getParameterTypes()[i]; Object arg = args[i];
                if (arg == null ? type.isPrimitive() : !(type == int.class ? Integer.class : type).isInstance(arg)) { accepted = false; break; }
            }
            if (accepted) return method;
        }
        return null;
    }
    private static void test(String name, Checked test) {
        long start = System.nanoTime(); String error = null; count++;
        try { test.run(); System.out.println("PASS " + name); } catch (Throwable failure) { failures++; error = failure.toString(); System.err.println("FAIL " + name); failure.printStackTrace(); }
        xml.add("<testcase name=\"" + escape(name) + "\" classname=\"CoreContractTest\" time=\"" + (System.nanoTime()-start)/1_000_000_000.0 + "\">" + (error == null ? "" : "<failure message=\"" + escape(error) + "\"/>") + "</testcase>");
    }
    private static String escape(String text) { return text.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;"); }
    private static void check(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
    private static void eq(Object expected, Object actual) { if (!Objects.equals(expected, actual)) throw new AssertionError("expected="+expected+" actual="+actual); }
    private static <T extends Throwable> T expect(Class<T> type, Checked action) throws Exception { try { action.run(); } catch (Throwable failure) { if (type.isInstance(failure)) return type.cast(failure); throw new AssertionError("expected "+type+", got "+failure, failure); } throw new AssertionError("expected "+type.getName()); }
    private static void await(CountDownLatch latch) throws InterruptedException { check(latch.await(3,TimeUnit.SECONDS), "latch timeout"); }
    private static void awaitScopeWaiter(CodeScope scope, Thread waiter) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline && (scope.diagnostics().get("inFlight") < 2 || waiter.getState() != Thread.State.TIMED_WAITING)) Thread.yield();
        check(scope.diagnostics().get("inFlight") == 2 && waiter.getState() == Thread.State.TIMED_WAITING, "waiter entered single-flight wait");
    }
    @SuppressWarnings("unchecked") private static <T extends Throwable> void sneakyThrow(Throwable failure) throws T { throw (T) failure; }
    private static void blockingAwait(CountDownLatch latch) { try { await(latch); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); } }
    private interface Checked { void run() throws Exception; }
    public static class Target {
        public int number, calls;
        public Target() { }
        public Target(int number) { if (number < 0) throw new IllegalArgumentException("negative"); this.number = number; }
        public int zero() { return 0; }
        public int one(int a) { return a; }
        public int four(int a,int b,int c,int d) { return a+b+c+d; }
        public int eight(int a,int b,int c,int d,int e,int f,int g,int h) { return a+b+c+d+e+f+g+h; }
        public long wide(long value) { return value; }
        public String pick(String value) { return "string"; }
        public String pick(Number value) { return "number"; }
        public int varargs(String... values) { return values.length; }
        public Object nullable() { return null; }
        public void throwOnce() { calls++; throw new IllegalStateException("target"); }
        private int privateValue() { return 42; }
    }
    public static final class EmptyTarget { }
    public static class Parent { public int shadow; public Object covariant() { return "parent"; } }
    public static final class Child extends Parent { public String shadow; @Override public String covariant() { return "child"; } }
    /** Counts entered target calls; ADMITTED marks that the current thread's call reached the target. */
    public static final class Counting {
        static final ThreadLocal<Boolean> ADMITTED = ThreadLocal.withInitial(() -> false);
        final AtomicInteger inFlight = new AtomicInteger(), calls = new AtomicInteger(), enteredAfterProviderClose = new AtomicInteger();
        final AtomicBoolean providerClosed = new AtomicBoolean();
        public int run() {
            ADMITTED.set(true); inFlight.incrementAndGet(); calls.incrementAndGet();
            try { if (providerClosed.get()) enteredAfterProviderClose.incrementAndGet(); Thread.onSpinWait(); return 1; }
            finally { inFlight.decrementAndGet(); }
        }
    }
    public static final class Blocking { final CountDownLatch entered = new CountDownLatch(1), exit = new CountDownLatch(1); public int run() { entered.countDown(); blockingAwait(exit); return 1; } }
    public static final class ClosingTarget { private final CodeScope scope; ClosingTarget(CodeScope scope) { this.scope=scope; } public void run() { scope.close(); throw new IllegalArgumentException("original target"); } }
    private static final class SyntheticFatal extends VirtualMachineError { }
    public static final class ArityFixture {
        public int first;
        public String second;
        public ArityFixture() { }
        public ArityFixture(int value) { }
        public ArityFixture(String value) { }
        public ArityFixture(int first, int second) { }
        public void choose() { }
        public void choose(int value) { }
        public void choose(String value) { }
        public void choose(int first, int second) { }
    }
    private static final class Provider implements CandidateProvider {
        final String id, capability; int major=1; List<String> dependencies=Collections.emptyList(); List<MemberDescriptor> candidates=Collections.singletonList(CoreContractTest.descriptor("zero","I")); boolean complete=true; String fingerprint=IDENTITY.fingerprint();
        final AtomicInteger discoveries=new AtomicInteger(),closes=new AtomicInteger(); CountDownLatch entered,exit; boolean failClose, cancel, interruptAfterWait, interruptDescriptorAfterDiscovery, interruptBeforeReturn, interruptFlagDescriptorAfterDiscovery;
        Throwable closeFailure; List<String> closeOrder; boolean clearInterruptOnClose; final AtomicBoolean closeSawInterrupt = new AtomicBoolean();
        Provider(String id,String capability) { this.id=id; this.capability=capability; }
        public ProviderDescriptor descriptor() { if (interruptDescriptorAfterDiscovery && discoveries.get() > 0) CoreContractTest.<RuntimeException>sneakyThrow(new InterruptedException("descriptor")); if (interruptFlagDescriptorAfterDiscovery && discoveries.get() > 0) Thread.currentThread().interrupt(); return new ProviderDescriptor(id,"fixture-1",major,0,capability,dependencies); }
        public CandidateBatch discover(CandidateRequest request) throws InterruptedException { discoveries.incrementAndGet(); if (cancel) throw new CancellationException("fixture"); if (entered!=null) entered.countDown(); if (exit!=null) await(exit); if (interruptAfterWait) throw new InterruptedException("provider"); if (interruptBeforeReturn) Thread.currentThread().interrupt(); return new CandidateBatch(candidates,fingerprint,"finite-handwritten-fixture",complete); }
        Runnable onClose;
        public void close() { closes.incrementAndGet(); if (onClose != null) onClose.run(); if (closeOrder != null) closeOrder.add(id); if (clearInterruptOnClose) closeSawInterrupt.set(Thread.interrupted()); if (closeFailure != null) CoreContractTest.<RuntimeException>sneakyThrow(closeFailure); if (failClose) throw new IllegalStateException("provider close"); }
    }
}

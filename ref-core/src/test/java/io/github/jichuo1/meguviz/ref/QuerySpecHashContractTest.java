package io.github.jichuo1.meguviz.ref;

import io.github.jichuo1.meguviz.ref.api.descriptor.MemberDescriptor;
import io.github.jichuo1.meguviz.ref.api.descriptor.MemberKind;
import io.github.jichuo1.meguviz.ref.api.query.*;
import io.github.jichuo1.meguviz.ref.api.result.ResolutionResult;
import io.github.jichuo1.meguviz.ref.api.result.ResolutionStatus;
import io.github.jichuo1.meguviz.ref.scope.CodeScope;
import java.io.InputStream;
import java.lang.reflect.Member;
import java.util.*;

/** Exercises immutable query keys and the hash formula used by earlier releases. */
public final class QuerySpecHashContractTest {
    public static void main(String[] args) throws Exception {
        Class<?>[] input = {int.class, String.class};
        QuerySpec first = QuerySpec.builder(QuerySpecHashContractTest.class, MemberKind.METHOD)
                .name("example").parameters(input).returns(void.class).staticOnly(true)
                .bridge(false).synthetic(false).hierarchy(Hierarchy.SUPERCLASSES)
                .selection(Selection.ALL).candidateBudget(42).resultLimit(7).depthLimit(3).provider("example").build();
        QuerySpec equal = QuerySpec.builder(QuerySpecHashContractTest.class, MemberKind.METHOD)
                .name("example").parameters(int.class, String.class).returns(void.class).staticOnly(true)
                .bridge(false).synthetic(false).hierarchy(Hierarchy.SUPERCLASSES)
                .selection(Selection.ALL).candidateBudget(42).resultLimit(7).depthLimit(3).provider("example").build();
        Map<QuerySpec, String> cache = new HashMap<>();
        cache.put(first, "found");
        input[0] = long.class;
        first.parameterClasses()[1] = Object.class;
        check(first.equals(equal) && equal.equals(first), "snapshot equality");
        check(first.hashCode() == equal.hashCode(), "equal hashes");
        check("found".equals(cache.get(equal)), "lookup by equivalent query after array mutation");
        check(first.hashCode() == legacyHash(first), "runtime query hash compatibility");

        QuerySpec exact = QuerySpec.exact(MemberDescriptor.method("java.lang.String", "substring", "Ljava/lang/String;", "I"));
        check(exact.hashCode() == legacyHash(exact), "portable exact hash compatibility");
        QuerySpec arguments = QuerySpec.builder(String.class, MemberKind.METHOD)
                .arguments(CompatibilityPolicy.JAVA_INVOCATION, "value", null).build();
        check(arguments.hashCode() == legacyHash(arguments), "argument query hash compatibility");
        hashMatrixContracts();
        exactDescriptorHashContracts();
        argumentSnapshotContracts();
        ownerNormalizationContracts();
        System.out.println("QuerySpec hash contracts passed");
    }

    private static void ownerNormalizationContracts() throws Exception {
        for (String name : new String[]{null, "", ".", "/", "java.lang.String", "java/lang/String",
                "pkg.Outer$Inner", "pkg/Outer$Inner", "bad[", "bad;", "a//b", "a b", "x".repeat(4095)}) {
            String expected = ownerOutcome(() -> MemberDescriptor.field(name, "_", "I").owner());
            String actual = ownerOutcome(() -> QuerySpec.builder(name, MemberKind.METHOD).build().owner());
            check(expected.equals(actual), "owner name parity for " + name);
        }
        for (Class<?> owner : new Class<?>[]{String.class, LoaderFixture.class, int.class, void.class,
                String[].class, int[].class, String[][].class}) {
            String expected = ownerOutcome(() -> MemberDescriptor.field(owner.getName(), "_", "I").owner());
            String actual = ownerOutcome(() -> QuerySpec.builder(owner, MemberKind.METHOD).build().owner());
            check(expected.equals(actual), "owner Class parity for " + owner.getName());
        }
        check(ownerOutcome(() -> QuerySpec.builder((Class<?>) null, MemberKind.METHOD).build().owner()).startsWith("error:java.lang.NullPointerException:"),
                "null runtime Class rejected");

        String binaryName = LoaderFixture.class.getName();
        byte[] bytes;
        try (InputStream stream = LoaderFixture.class.getResourceAsStream("QuerySpecHashContractTest$LoaderFixture.class")) {
            if (stream == null) throw new AssertionError("fixture class bytes unavailable");
            bytes = stream.readAllBytes();
        }
        ClassLoader firstLoader = fixtureLoader(binaryName, bytes), secondLoader = fixtureLoader(binaryName, bytes);
        Class<?> first = Class.forName(binaryName, false, firstLoader), second = Class.forName(binaryName, false, secondLoader);
        check(first != second && first.getName().equals(second.getName()), "same name, distinct class identity");
        QuerySpec firstQuery = QuerySpec.builder(first, MemberKind.METHOD).name("value").selection(Selection.ALL).build();
        QuerySpec sameQuery = QuerySpec.builder(first, MemberKind.METHOD).name("value").selection(Selection.ALL).build();
        QuerySpec secondQuery = QuerySpec.builder(second, MemberKind.METHOD).name("value").selection(Selection.ALL).build();
        check(firstQuery.equals(sameQuery) && firstQuery.hashCode() == sameQuery.hashCode(), "same class equals");
        check(!firstQuery.equals(secondQuery), "different loader class queries are distinct");
        check(firstQuery.hashCode() == legacyHash(firstQuery) && secondQuery.hashCode() == legacyHash(secondQuery), "loader owner hash parity");
        QuerySpec firstTypes = QuerySpec.builder(QuerySpecHashContractTest.class, MemberKind.METHOD).parameters(first).returns(first).build();
        QuerySpec sameTypes = QuerySpec.builder(QuerySpecHashContractTest.class, MemberKind.METHOD).parameters(first).returns(first).build();
        QuerySpec secondTypes = QuerySpec.builder(QuerySpecHashContractTest.class, MemberKind.METHOD).parameters(second).returns(second).build();
        QuerySpec firstArguments = QuerySpec.builder(QuerySpecHashContractTest.class, MemberKind.METHOD)
                .arguments(CompatibilityPolicy.LEGACY_BIL, first.getDeclaredConstructor().newInstance()).build();
        QuerySpec sameArguments = QuerySpec.builder(QuerySpecHashContractTest.class, MemberKind.METHOD)
                .arguments(CompatibilityPolicy.LEGACY_BIL, first.getDeclaredConstructor().newInstance()).build();
        QuerySpec secondArguments = QuerySpec.builder(QuerySpecHashContractTest.class, MemberKind.METHOD)
                .arguments(CompatibilityPolicy.LEGACY_BIL, second.getDeclaredConstructor().newInstance()).build();
        Map<QuerySpec, String> keys = new HashMap<>();
        for (QuerySpec key : new QuerySpec[]{firstTypes, sameTypes, secondTypes, firstArguments, sameArguments, secondArguments})
            check(key.hashCode() == legacyHash(key), "parameter, return or argument class hash parity");
        keys.put(firstTypes, "first types"); keys.put(secondTypes, "second types");
        keys.put(firstArguments, "first arguments"); keys.put(secondArguments, "second arguments");
        check(keys.size() == 4 && "first types".equals(keys.get(sameTypes)) && "first arguments".equals(keys.get(sameArguments)), "equivalent loader-aware keys");
        check(!firstTypes.equals(secondTypes) && !firstArguments.equals(secondArguments), "signature class identities are distinct");
        try (CodeScope scope = CodeScope.open(firstLoader)) {
            ResolutionResult a = scope.resolve(firstQuery), b = scope.resolve(secondQuery), c = scope.resolve(sameQuery);
            check(a.status() == ResolutionStatus.ALL_MATCHES && b.status() == ResolutionStatus.ALL_MATCHES, "both loader queries resolve");
            check(a == c && a != b, "same-class hit and different-class cache isolation");
            Member am = scope.exportRaw(a.members().get(0)), bm = scope.exportRaw(b.members().get(0));
            check(am.getDeclaringClass() == first && bm.getDeclaringClass() == second, "cached members keep defining class identity");
        }
    }

    private static void hashMatrixContracts() {
        Boolean[] flags = {null, Boolean.FALSE, Boolean.TRUE};
        int[] budgets = {1, 127, 128, 256, 1000000}, depths = {1, 127, 128, 256, 1024};
        Map<QuerySpec, Integer> keys = new HashMap<>(); int count = 0; boolean overflow = false;
        for (MemberKind kind : MemberKind.values()) for (Hierarchy hierarchy : Hierarchy.values()) {
            if (kind == MemberKind.CONSTRUCTOR && hierarchy != Hierarchy.DECLARED) continue;
            for (Selection selection : new Selection[]{Selection.FIRST, Selection.UNIQUE, Selection.ALL})
                for (Boolean staticOnly : flags) for (Boolean bridge : flags) for (Boolean synthetic : flags)
                    for (int budgetIndex = 0; budgetIndex < budgets.length; budgetIndex++)
                        for (int mode = 0; mode < 5; mode++) for (boolean runtimeOwner : new boolean[]{false, true}) {
                            QuerySpec first = matrixQuery(kind, hierarchy, selection, staticOnly, bridge, synthetic,
                                    budgets[budgetIndex], budgets[(budgetIndex + 2) % budgets.length], depths[budgetIndex], mode, runtimeOwner);
                            QuerySpec equal = matrixQuery(kind, hierarchy, selection, staticOnly, bridge, synthetic,
                                    budgets[budgetIndex], budgets[(budgetIndex + 2) % budgets.length], depths[budgetIndex], mode, runtimeOwner);
                            check(first.hashCode() == legacyHash(first), "historical Objects.hash matrix value");
                            check(equal.hashCode() == legacyHash(equal), "equivalent historical hash value");
                            check(first.equals(equal) && equal.equals(first) && first.hashCode() == equal.hashCode(), "equivalent matrix keys");
                            keys.put(first, count); check(Objects.equals(count, keys.get(equal)), "HashMap equivalent key lookup"); keys.remove(first);
                            if (!overflow) overflow = legacyCalculationOverflows(first);
                            count++;
                        }
        }
        check(count > 20000, "matrix exercised"); check(overflow, "32-bit overflow exercised");
        System.out.println("QuerySpec legacy hash matrix cases=" + count + " overflow=true");
    }

    private static QuerySpec matrixQuery(MemberKind kind, Hierarchy hierarchy, Selection selection,
                                         Boolean staticOnly, Boolean bridge, Boolean synthetic,
                                         int candidateBudget, int resultLimit, int depthLimit, int mode, boolean runtimeOwner) {
        QuerySpec.Builder builder = runtimeOwner ? QuerySpec.builder(QuerySpecHashContractTest.class, kind)
                : QuerySpec.builder(QuerySpecHashContractTest.class.getName(), kind);
        builder.hierarchy(hierarchy).selection(selection).candidateBudget(candidateBudget).resultLimit(resultLimit).depthLimit(depthLimit);
        if (staticOnly != null) builder.staticOnly(staticOnly);
        if (bridge != null) builder.bridge(bridge);
        if (synthetic != null) builder.synthetic(synthetic);
        if ((mode & 1) != 0) builder.name("matrix").returns(String[].class).provider("fixture");
        if (mode == 0) builder.parameterCount(255);
        else if (mode == 1) builder.parameters();
        else if (mode == 2) builder.parameters(int.class, String[].class);
        else if (mode == 3) builder.arguments(CompatibilityPolicy.LEGACY_BIL, null, Integer.valueOf(1));
        else builder.arguments(CompatibilityPolicy.JAVA_INVOCATION, null, Integer.valueOf(1));
        return builder.build();
    }

    private static void exactDescriptorHashContracts() {
        MemberDescriptor[] descriptors = {
                MemberDescriptor.method("fixture.Owner", "method", "Ljava/lang/String;", "I", "[Ljava/lang/String;"),
                MemberDescriptor.field("fixture.Owner", "field", "[I"),
                MemberDescriptor.constructor("fixture.Owner", "Ljava/lang/String;")};
        for (MemberDescriptor descriptor : descriptors) {
            QuerySpec first = QuerySpec.exact(descriptor), equal = QuerySpec.exact(descriptor);
            check(first.selection() == Selection.EXACT && first.kind() == descriptor.kind(), "exact kind");
            check(first.hashCode() == legacyHash(first) && equal.hashCode() == legacyHash(equal), "exact descriptor historical hash");
            Map<QuerySpec, String> keys = new HashMap<>(); keys.put(first, "exact");
            check(first.equals(equal) && "exact".equals(keys.get(equal)), "equivalent exact HashMap lookup");
        }
    }

    private static void argumentSnapshotContracts() {
        Object[] values = {"value", null};
        QuerySpec first = QuerySpec.builder(String.class, MemberKind.METHOD).arguments(CompatibilityPolicy.JAVA_INVOCATION, values).build();
        QuerySpec equal = QuerySpec.builder(String.class, MemberKind.METHOD).arguments(CompatibilityPolicy.JAVA_INVOCATION, "different", null).build();
        values[0] = Integer.valueOf(1); first.argumentClasses()[0] = Object.class;
        Map<QuerySpec, String> keys = new HashMap<>(); keys.put(first, "argument snapshot");
        check(first.equals(equal) && first.hashCode() == legacyHash(first), "argument snapshot and historical hash");
        check("argument snapshot".equals(keys.get(equal)), "argument snapshot HashMap lookup");
    }

    private static boolean legacyCalculationOverflows(QuerySpec q) {
        Object[] values = {q.owner(), q.name(), q.kind(), q.exactDescriptor(), q.hierarchy(), q.selection(), q.compatibility(),
                q.staticOnly(), q.bridge(), q.synthetic(), q.parameterCount(), q.candidateBudget(), q.resultLimit(), q.depthLimit(),
                q.providerId(), q.runtimeOwner(), q.returnClass(), Arrays.hashCode(q.parameterClasses()), Arrays.hashCode(q.argumentClasses())};
        int hash = 1; boolean overflow = false;
        for (Object value : values) {
            long next = 31L * hash + Objects.hashCode(value);
            if (next < Integer.MIN_VALUE || next > Integer.MAX_VALUE) overflow = true;
            hash = (int) next;
        }
        return overflow;
    }

    private static ClassLoader fixtureLoader(String name, byte[] bytes) {
        return new ClassLoader(null) {
            @Override protected Class<?> findClass(String requested) throws ClassNotFoundException {
                if (!requested.equals(name)) throw new ClassNotFoundException(requested);
                return defineClass(requested, bytes, 0, bytes.length);
            }
        };
    }
    private static String ownerOutcome(OwnerOperation operation) {
        try { return "ok:" + operation.run(); }
        catch (RuntimeException error) { return "error:" + error.getClass().getName() + ":" + error.getMessage(); }
    }
    private interface OwnerOperation { String run(); }
    public static final class LoaderFixture { public void value() { } }

    private static int legacyHash(QuerySpec q) {
        return Objects.hash(q.owner(), q.name(), q.kind(), q.exactDescriptor(), q.hierarchy(), q.selection(), q.compatibility(),
                q.staticOnly(), q.bridge(), q.synthetic(), q.parameterCount(), q.candidateBudget(), q.resultLimit(), q.depthLimit(),
                q.providerId(), q.runtimeOwner(), q.returnClass(), Arrays.hashCode(q.parameterClasses()), Arrays.hashCode(q.argumentClasses()));
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}

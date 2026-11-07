package io.github.jichuo1.meguviz.ref;

import io.github.jichuo1.meguviz.ref.api.descriptor.MemberDescriptor;
import io.github.jichuo1.meguviz.ref.api.descriptor.MemberKind;
import io.github.jichuo1.meguviz.ref.api.evidence.CodeIdentity;
import io.github.jichuo1.meguviz.ref.api.query.Hierarchy;
import io.github.jichuo1.meguviz.ref.api.query.QuerySpec;
import io.github.jichuo1.meguviz.ref.api.query.Selection;
import io.github.jichuo1.meguviz.ref.api.result.ResolutionResult;
import io.github.jichuo1.meguviz.ref.api.result.ResolutionStatus;
import io.github.jichuo1.meguviz.ref.scope.CodeScope;
import io.github.jichuo1.meguviz.ref.spi.CandidateBatch;
import io.github.jichuo1.meguviz.ref.spi.CandidateProvider;
import io.github.jichuo1.meguviz.ref.spi.CandidateRequest;
import io.github.jichuo1.meguviz.ref.spi.ProviderDescriptor;
import io.github.jichuo1.meguviz.ref.spi.ProviderRegistry;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.tools.ToolProvider;

/** Provider membership is checked against the real reflection domain and runtime Class identities. */
public final class ProviderDomainContractTest {
    private static int tests, failures;
    public static class PublicParent { public int pick() { return 1; } }
    public static final class PublicChild extends PublicParent { @Override public int pick() { return 2; } }
    public static final class PublicOverloads {
        public int pick(int value) { return 1; }
        public int pick(long value) { return 2; }
        public int pick(String value) { return 3; }
    }

    private static final class Provider implements CandidateProvider {
        private final List<MemberDescriptor> descriptors;
        private final boolean complete;
        Provider(boolean complete, MemberDescriptor... descriptors) { this.complete = complete; this.descriptors = Arrays.asList(descriptors); }
        @Override public ProviderDescriptor descriptor() { return new ProviderDescriptor("p", "1", 1, 0, "", Collections.emptyList()); }
        @Override public CandidateBatch discover(CandidateRequest request) {
            return new CandidateBatch(descriptors, request.codeFingerprint(), "provider-domain-fixture", complete);
        }
        @Override public void close() { }
    }
    private static final class ScopeLoader extends URLClassLoader {
        private final boolean shadowBase;
        ScopeLoader(URL url, ClassLoader parent, boolean shadowBase) { super(new URL[]{url}, parent); this.shadowBase = shadowBase; }
        @Override protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (shadowBase && name.equals("fixture.Base")) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) loaded = findClass(name);
                if (resolve) resolveClass(loaded);
                return loaded;
            }
            return super.loadClass(name, resolve);
        }
    }
    private static final class TwinLoader extends URLClassLoader {
        TwinLoader(URL url, ClassLoader parent) { super(new URL[]{url}, parent); }
        @Override protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.equals("fixture.Identity") || name.equals("fixture.Twin")) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) loaded = findClass(name);
                if (resolve) resolveClass(loaded);
                return loaded;
            }
            return super.loadClass(name, resolve);
        }
    }
    private static CodeScope scope(ClassLoader loader, MemberDescriptor... descriptors) {
        return scope(loader, true, descriptors);
    }
    private static CodeScope scope(ClassLoader loader, boolean complete, MemberDescriptor... descriptors) {
        CodeScope scope = CodeScope.open(loader);
        scope.installProviders(ProviderRegistry.create(Collections.singletonList(new Provider(complete, descriptors))),
                CodeIdentity.strong(Collections.singletonMap("fixture", "a".repeat(64)), "provider-domain", QuerySpec.RULE_VERSION));
        return scope;
    }
    private static QuerySpec query(Class<?> owner, String name, Hierarchy hierarchy, Selection selection, boolean provider) {
        QuerySpec.Builder builder = QuerySpec.builder(owner, MemberKind.METHOD).name(name).parameters().hierarchy(hierarchy).selection(selection);
        return (provider ? builder.provider("p") : builder).build();
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static void inherited(ClassLoader loader, Class<?> derived, Class<?> parentBase, boolean shadow, Hierarchy hierarchy) throws Exception {
        check(Class.forName("fixture.Derived", false, loader) == derived, "scope loader delegates runtime owner");
        check(derived.getSuperclass() == parentBase, "actual superclass identity");
        check((Class.forName("fixture.Base", false, loader) != parentBase) == shadow, "expected Base loader arrangement");
        Method expected = parentBase.getDeclaredMethod("inherited");
        try (CodeScope scope = scope(loader, MemberDescriptor.from(expected))) {
            ResolutionResult direct = scope.resolve(query(derived, "inherited", hierarchy, Selection.ALL, false));
            check(direct.status() == ResolutionStatus.ALL_MATCHES && direct.receipt().coverage().complete(), "direct complete");
            check(direct.members().size() == 1 && scope.exportRaw(direct.members().get(0)).equals(expected), "direct superclass member");
            ResolutionResult provided = scope.resolve(query(derived, "inherited", hierarchy, Selection.ALL, true));
            check(provided.status() == ResolutionStatus.ALL_MATCHES && provided.receipt().coverage().complete(),
                    "provider complete, got " + provided.status() + "/" + provided.receipt().reason());
            check(provided.members().size() == 1 && scope.exportRaw(provided.members().get(0)).equals(expected), "provider superclass identity");
            check(provided.receipt().descriptors().equals(direct.receipt().descriptors()), "same descriptor collection");
        }
    }
    private static void overriddenPublicMember() throws Exception {
        Method parent = PublicParent.class.getDeclaredMethod("pick"), child = PublicChild.class.getDeclaredMethod("pick");
        long count = Arrays.stream(PublicChild.class.getMethods()).filter(m -> m.getName().equals("pick")).count();
        check(count == 1 && PublicChild.class.getMethod("pick").equals(child), "public domain contains only override");
        try (CodeScope scope = scope(ProviderDomainContractTest.class.getClassLoader(), MemberDescriptor.from(parent), MemberDescriptor.from(child))) {
            ResolutionResult direct = scope.resolve(query(PublicChild.class, "pick", Hierarchy.JAVA_PUBLIC, Selection.UNIQUE, false));
            check(direct.status() == ResolutionStatus.RESOLVED && scope.exportRaw(direct.requireSingle()).equals(child), "direct public unique");
            ResolutionResult provided = scope.resolve(query(PublicChild.class, "pick", Hierarchy.JAVA_PUBLIC, Selection.UNIQUE, true));
            check(provided.status() == ResolutionStatus.PARTIAL && !provided.receipt().coverage().complete(),
                    "hidden parent cannot prove ambiguity or complete coverage: " + provided.status() + "/" + provided.receipt().coverage().complete());
        }
    }
    private static void indexedShadowCandidates(ClassLoader loader, Class<?> derived, Class<?> parentBase,
                                                boolean duplicates, int budget, boolean expectComplete) throws Exception {
        check(Class.forName("fixture.Base", false, loader) != parentBase, "fixture requires the child-shadowed name");
        Method first = parentBase.getDeclaredMethod("indexed", int.class);
        Method second = parentBase.getDeclaredMethod("indexed", long.class);
        Method third = parentBase.getDeclaredMethod("indexed", String.class);
        MemberDescriptor one = MemberDescriptor.from(first), two = MemberDescriptor.from(second), three = MemberDescriptor.from(third);
        MemberDescriptor[] supplied = duplicates ? new MemberDescriptor[]{one, one, two, three} : new MemberDescriptor[]{one, two, three};
        try (CodeScope scope = scope(loader, supplied)) {
            QuerySpec directQuery = QuerySpec.builder(derived, MemberKind.METHOD).name("indexed")
                    .hierarchy(Hierarchy.JAVA_PUBLIC).selection(Selection.ALL).build();
            ResolutionResult direct = scope.resolve(directQuery);
            check(direct.status() == ResolutionStatus.ALL_MATCHES && direct.receipt().coverage().complete(), "direct indexed domain complete");
            Set<Method> expected = new HashSet<>(Arrays.asList(first, second, third));
            Set<Method> visible = new HashSet<>();
            for (var token : direct.members()) visible.add((Method) scope.exportRaw(token));
            check(visible.equals(expected), "direct indexed members match parent Class identities");
            QuerySpec providerQuery = QuerySpec.builder(derived, MemberKind.METHOD).name("indexed")
                    .hierarchy(Hierarchy.JAVA_PUBLIC).selection(Selection.ALL).candidateBudget(budget).provider("p").build();
            ResolutionResult provided = scope.resolve(providerQuery);
            if (!expectComplete) {
                check(provided.status() == ResolutionStatus.PARTIAL && !provided.receipt().coverage().complete(),
                        "insufficient budget must remain partial: " + provided.status() + "/" + provided.receipt().reason());
                return;
            }
            check(provided.status() == ResolutionStatus.ALL_MATCHES && provided.receipt().coverage().complete(), "indexed provider complete");
            check(provided.members().size() == expected.size(), "distinct candidates returned once");
            Set<Method> actual = new HashSet<>();
            for (var token : provided.members()) {
                Method method = (Method) scope.exportRaw(token);
                check(method.getDeclaringClass() == parentBase, "provider member bound to real parent identity");
                actual.add(method);
            }
            check(actual.equals(expected), "provider indexed collection matches direct reflection");
            check(provided.receipt().coverage().inspected() == supplied.length, "candidate accounting includes duplicates");
        }
    }
    private static void ambiguousLoaderIdentities(ClassLoader loader, Class<?> twin) throws Exception {
        Method[] reflected = Arrays.stream(twin.getMethods()).filter(m -> m.getName().equals("coincident")).toArray(Method[]::new);
        check(reflected.length == 2, "two public interface methods");
        check(reflected[0].getDeclaringClass() != reflected[1].getDeclaringClass()
                && reflected[0].getDeclaringClass().getName().equals(reflected[1].getDeclaringClass().getName()),
                "two same-name interface identities");
        MemberDescriptor first = MemberDescriptor.from(reflected[0]), second = MemberDescriptor.from(reflected[1]);
        check(first.equals(second), "portable descriptors cannot distinguish interface identities");
        try (CodeScope scope = scope(loader, first)) {
            ResolutionResult direct = scope.resolve(query(twin, "coincident", Hierarchy.JAVA_PUBLIC, Selection.ALL, false));
            check(direct.status() == ResolutionStatus.ALL_MATCHES && direct.receipt().coverage().complete()
                    && direct.members().size() == 2, "direct enumeration retains both runtime members");
            Method rawFirst = (Method) scope.exportRaw(direct.members().get(0)), rawSecond = (Method) scope.exportRaw(direct.members().get(1));
            check(rawFirst.getDeclaringClass() != rawSecond.getDeclaringClass(), "binding preserves both interface identities");
            ResolutionResult provided = scope.resolve(query(twin, "coincident", Hierarchy.JAVA_PUBLIC, Selection.ALL, true));
            check(provided.status() == ResolutionStatus.PARTIAL && !provided.receipt().coverage().complete(),
                    "one indistinguishable descriptor cannot prove both identities: " + provided.status());
        }
    }
    private static void ambiguousWithoutCoverageProof(boolean claimedComplete) throws Exception {
        Method first = PublicOverloads.class.getDeclaredMethod("pick", int.class);
        Method second = PublicOverloads.class.getDeclaredMethod("pick", long.class);
        try (CodeScope scope = scope(ProviderDomainContractTest.class.getClassLoader(), claimedComplete, MemberDescriptor.from(first), MemberDescriptor.from(second))) {
            QuerySpec query = QuerySpec.builder(PublicOverloads.class, MemberKind.METHOD).name("pick")
                    .hierarchy(Hierarchy.JAVA_PUBLIC).selection(Selection.UNIQUE).provider("p").build();
            ResolutionResult provided = scope.resolve(query);
            check(provided.status() == ResolutionStatus.AMBIGUOUS, "two verified matches prove ambiguity");
            check(!provided.receipt().coverage().complete(), "unverified provider claim cannot prove complete coverage: " + claimedComplete);
        }
    }
    private static Path repositoryRoot() {
        Path path = Path.of("").toAbsolutePath();
        while (path != null && !Files.isDirectory(path.resolve("test-fixtures/provider-domain-src"))) path = path.getParent();
        if (path == null) throw new IllegalStateException("provider domain fixture sources unavailable");
        return path;
    }
    private static void compile(Path root, Path output, Path classpath, String... sources) throws Exception {
        Files.createDirectories(output);
        int prefix = classpath == null ? 4 : 6;
        String[] args = new String[sources.length + prefix];
        args[0] = "--release"; args[1] = "17"; args[2] = "-d"; args[3] = output.toString();
        if (classpath != null) { args[4] = "-cp"; args[5] = classpath.toString(); }
        for (int i = 0; i < sources.length; i++) args[i + prefix] = root.resolve(sources[i]).toString();
        if (ToolProvider.getSystemJavaCompiler().run(null, System.out, System.err, args) != 0) throw new AssertionError("fixture compilation failed");
    }
    private static void test(String name, Checked body) {
        tests++;
        try { body.run(); System.out.println("PASS " + name); }
        catch (Throwable failure) { failures++; System.err.println("FAIL " + name); failure.printStackTrace(); }
    }
    @FunctionalInterface private interface Checked { void run() throws Exception; }
    public static void main(String[] args) throws Exception {
        Path root = repositoryRoot(), fixtures = root.resolve("test-fixtures/provider-domain-src");
        Path parentClasses = root.resolve("ref-core/build/provider-domain-fixtures/parent"), childClasses = root.resolve("ref-core/build/provider-domain-fixtures/child");
        compile(fixtures, parentClasses, null, "parent/fixture/Base.java", "parent/fixture/Derived.java",
                "parent/fixture/Identity.java", "parent/fixture/ParentImpl.java");
        compile(fixtures, childClasses, parentClasses, "child/fixture/Base.java", "child/fixture/Identity.java", "child/fixture/Twin.java");
        try (URLClassLoader parent = new URLClassLoader(new URL[]{parentClasses.toUri().toURL()}, null);
             ScopeLoader delegated = new ScopeLoader(childClasses.toUri().toURL(), parent, false);
             ScopeLoader shadowed = new ScopeLoader(childClasses.toUri().toURL(), parent, true);
             TwinLoader twins = new TwinLoader(childClasses.toUri().toURL(), parent)) {
            Class<?> base = Class.forName("fixture.Base", false, parent), derived = Class.forName("fixture.Derived", false, parent);
            for (Hierarchy hierarchy : new Hierarchy[]{Hierarchy.SUPERCLASSES, Hierarchy.JAVA_PUBLIC}) {
                test("provider-domain-" + hierarchy + "-delegated", () -> inherited(delegated, derived, base, false, hierarchy));
                test("provider-domain-" + hierarchy + "-shadowed", () -> inherited(shadowed, derived, base, true, hierarchy));
            }
            test("provider-public-override-not-ambiguous", ProviderDomainContractTest::overriddenPublicMember);
            test("provider-public-same-name-loader-identities-are-ambiguous", () -> ambiguousLoaderIdentities(twins, Class.forName("fixture.Twin", false, twins)));
            test("provider-public-shadow-index-multiple-candidates", () -> indexedShadowCandidates(shadowed, derived, base, false, 256, true));
            test("provider-public-shadow-index-duplicate-candidate", () -> indexedShadowCandidates(shadowed, derived, base, true, 256, true));
            test("provider-public-shadow-index-domain-budget", () -> indexedShadowCandidates(shadowed, derived, base, false, 3, false));
            test("provider-public-shadow-index-candidate-budget", () -> indexedShadowCandidates(shadowed, derived, base, true, 2, false));
            test("provider-unique-ambiguity-claimed-complete-without-proof", () -> ambiguousWithoutCoverageProof(true));
            test("provider-unique-ambiguity-provider-partial", () -> ambiguousWithoutCoverageProof(false));
        }
        System.out.println("PROVIDER_DOMAIN_TESTS=" + tests + " FAILURES=" + failures);
        if (failures != 0) throw new AssertionError("provider domain failures=" + failures);
    }
}

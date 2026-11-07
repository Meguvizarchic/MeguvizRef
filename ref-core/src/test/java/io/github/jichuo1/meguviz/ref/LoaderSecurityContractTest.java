package io.github.jichuo1.meguviz.ref;

import io.github.jichuo1.meguviz.ref.api.descriptor.MemberDescriptor;
import io.github.jichuo1.meguviz.ref.api.query.QuerySpec;
import io.github.jichuo1.meguviz.ref.api.result.ResolutionResult;
import io.github.jichuo1.meguviz.ref.api.result.ResolutionStatus;
import io.github.jichuo1.meguviz.ref.runtime.resolve.ReflectionResolver;
import io.github.jichuo1.meguviz.ref.scope.CodeScope;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Permission;
import java.util.Arrays;
import javax.tools.ToolProvider;

/** JDK 17-only legacy SecurityManager fixture; run in a dedicated JVM. */
@SuppressWarnings("removal")
public final class LoaderSecurityContractTest {
    private static int count, failures;

    public static void main(String[] args) throws Exception {
        check(Runtime.version().feature() == 17, "JDK 17 required");
        check(System.getSecurityManager() == null, "run in a dedicated JVM without an existing SecurityManager");
        Path root = Path.of("").toAbsolutePath();
        while (!Files.isDirectory(root.resolve("test-fixtures/isolated-src/fixture"))) {
            root = root.getParent();
            if (root == null) throw new IllegalStateException("fixture sources not found");
        }
        Path outputRoot = Files.createDirectories(root.resolve("ref-core/build"));
        Path fixtures = Files.createTempDirectory(outputRoot, "loader-security-contract-fixtures-");
        Path source = root.resolve("test-fixtures/isolated-src/fixture");
        int compiled = ToolProvider.getSystemJavaCompiler().run(null, System.out, System.err, "--release", "17", "-d", fixtures.toString(),
                source.resolve("ShadowParam.java").toString(), source.resolve("ShadowOwner.java").toString());
        check(compiled == 0, "fixture compilation failed");

        URL fixtureUrl = fixtures.toUri().toURL(); ClassLoader app = LoaderSecurityContractTest.class.getClassLoader();
        try (URLClassLoader independent = new URLClassLoader(new URL[]{fixtureUrl}, null);
             URLClassLoader parent = new URLClassLoader(new URL[]{fixtureUrl}, null);
             ShadowLoader shadow = new ShadowLoader(fixtureUrl, parent);
             CodeScope bootstrapScope = CodeScope.open(app);
             CodeScope independentScope = CodeScope.open(independent);
             CodeScope shadowScope = CodeScope.open(shadow)) {
            Class<?> independentOwner = Class.forName("fixture.ShadowOwner", false, independent);
            Class<?> independentParam = Class.forName("fixture.ShadowParam", false, independent);
            Class<?> parentOwner = Class.forName("fixture.ShadowOwner", false, parent);
            Class<?> parentParam = Class.forName("fixture.ShadowParam", false, parent);
            Class<?> shadowParam = Class.forName("fixture.ShadowParam", false, shadow);
            check(parentParam != shadowParam && Class.forName("fixture.ShadowOwner", false, shadow) == parentOwner, "shadow arrangement");
            Method bootstrap = String.class.getDeclaredMethod("substring", int.class);
            Method independentMethod = independentOwner.getDeclaredMethod("method", independentParam);
            Method shadowMethod = parentOwner.getDeclaredMethod("method", parentParam);
            Method shadowReturn = parentOwner.getDeclaredMethod("returns");
            Field shadowField = parentOwner.getDeclaredField("field");
            Guard guard = new Guard(); System.setSecurityManager(guard);
            try {
                test("bootstrap-fallback-preserves-result", () -> resolved(bootstrapScope, bootstrap, guard));
                test("independent-owner-fallback-preserves-class-identity", () -> resolved(independentScope, independentMethod, guard));
                test("shadow-fallback-never-binds-wrong-signature", () -> {
                    unavailable(shadowScope, MemberDescriptor.from(shadowMethod), guard);
                    unavailable(shadowScope, MemberDescriptor.from(shadowReturn), guard);
                    unavailable(shadowScope, MemberDescriptor.from(shadowField), guard);
                    check(shadowScope.diagnostics().get("bindings") == 0L, "shadow did not bind a member");
                });
                test("missing-member-remains-unavailable", () -> unavailable(bootstrapScope,
                        MemberDescriptor.method("java.lang.String", "notARealMember", "Ljava/lang/String;", "I"), guard));
            } finally { System.setSecurityManager(null); }
        }
        System.out.println("TESTS=" + count + " FAILURES=" + failures);
        if (count == 0 || failures != 0) throw new AssertionError("loader security contract failures=" + failures);
    }

    private static void resolved(CodeScope scope, Method expected, Guard guard) throws Exception {
        int deniedBefore = guard.denials;
        ResolutionResult result = scope.resolve(QuerySpec.exact(MemberDescriptor.from(expected)));
        check(guard.denials > deniedBefore, "getClassLoader permission denial was not exercised");
        check(result.status() == ResolutionStatus.RESOLVED, "exact result: " + result.status() + "/" + result.receipt().reason());
        Method actual = (Method) scope.exportRaw(result.requireSingle());
        check(actual.equals(expected), "actual member");
        check(actual.getReturnType() == expected.getReturnType() && Arrays.equals(actual.getParameterTypes(), expected.getParameterTypes()), "parameter and return identity");
    }

    private static void unavailable(CodeScope scope, MemberDescriptor descriptor, Guard guard) {
        int deniedBefore = guard.denials;
        ResolutionResult result = scope.resolve(QuerySpec.exact(descriptor));
        check(guard.denials > deniedBefore, "getClassLoader permission denial was not exercised");
        check(result.status() == ResolutionStatus.UNAVAILABLE, "unavailable result: " + result.status() + "/" + result.receipt().reason());
        check(result.members().isEmpty(), "no incorrect member");
    }

    private static void test(String name, Checked action) {
        count++;
        try { action.run(); System.out.println("PASS " + name); }
        catch (Throwable failure) { failures++; System.err.println("FAIL " + name); failure.printStackTrace(); }
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private interface Checked { void run() throws Exception; }

    private static final class Guard extends SecurityManager {
        int denials;
        @Override public void checkPermission(Permission permission) {
            if (permission instanceof RuntimePermission && permission.getName().equals("getClassLoader")) {
                for (Class<?> frame : getClassContext()) if (frame == ReflectionResolver.class) {
                    denials++; throw new SecurityException("fixture denied getClassLoader");
                }
            }
        }
    }
    private static final class ShadowLoader extends URLClassLoader {
        ShadowLoader(URL fixture, ClassLoader parent) { super(new URL[]{fixture}, parent); }
        @Override protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.equals("fixture.ShadowParam")) {
                Class<?> type = findLoadedClass(name); if (type == null) type = findClass(name);
                if (resolve) resolveClass(type); return type;
            }
            return super.loadClass(name, resolve);
        }
    }
}

package example;

import io.github.jichuo1.meguviz.ref.api.descriptor.MemberDescriptor;
import io.github.jichuo1.meguviz.ref.api.query.QuerySpec;
import io.github.jichuo1.meguviz.ref.api.result.ResolutionStatus;
import io.github.jichuo1.meguviz.ref.scope.CodeScope;
import java.nio.file.Files;
import java.nio.file.Path;

public final class Consumer {
    public static String greet(String name) { return "Hello " + name; }
    public static void main(String[] args) throws Exception {
        if (Runtime.version().feature() != 17) throw new AssertionError("Consumer must run on Java17");
        ClassLoader loader = Consumer.class.getClassLoader();
        // Portable exact descriptor path.
        CodeScope scope = CodeScope.open(loader);
        var descriptor = MemberDescriptor.method("example.Consumer", "greet", "Ljava/lang/String;", "Ljava/lang/String;");
        var member = scope.resolve(QuerySpec.exact(descriptor)).requireSingle();
        var plan = scope.bindMethod(member);
        if (!"Hello Meguviz".equals(plan.invoke(null, new Object[]{"Meguviz"}))) throw new AssertionError("Invocation failed");
        scope.close();
        boolean stale = false;
        try { plan.invoke(null, new Object[]{"late"}); }
        catch (IllegalStateException expected) { stale = true; }
        if (!stale) throw new AssertionError("Closed plan accepted invocation");
        // Shortest runtime path: Class-based query, bind in one call, varargs invocation.
        try (CodeScope quick = CodeScope.open(loader)) {
            var greet = quick.bindMethod(QuerySpec.method(Consumer.class, "greet", String.class));
            if (!"Hello Ref".equals(greet.call(null, "Ref"))) throw new AssertionError("Shortcut invocation failed");
            var raw = quick.resolveRaw(QuerySpec.method(Consumer.class, "greet", String.class));
            if (raw.status() != ResolutionStatus.RESOLVED || !raw.members().get(0).equals(Consumer.class.getMethod("greet", String.class))) throw new AssertionError("Raw resolution failed");
            if (!quick.isCurrent(loader, quick.identity(), quick.codeEpoch())) throw new AssertionError("Currency check failed");
        }
        Path out = Path.of(args[0]);
        Files.createDirectories(out.getParent());
        String origin = CodeScope.class.getProtectionDomain().getCodeSource().getLocation().toString();
        Files.writeString(out, "{\"tests\":6,\"java\":17,\"status\":\"passed\",\"origin\":\"" + origin + "\"}");
        System.out.println("Independent Maven consumer TESTS=6; origin=" + origin);
    }
}

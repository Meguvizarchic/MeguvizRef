package example

import io.github.jichuo1.meguviz.ref.api.query.QuerySpec
import io.github.jichuo1.meguviz.ref.scope.CodeScope
import java.nio.file.Files
import java.nio.file.Path

fun main() {
    check(Runtime.version().feature() == 17)
    CodeScope.open(String::class.java.classLoader).use { scope ->
        // Int::class.java is the primitive int class; call(...) is the varargs form of invoke(receiver, arrayOf(...)).
        val plan = scope.bindMethod(QuerySpec.method(String::class.java, "substring", Int::class.java))
        check(plan.call("Meguviz", 4) == "viz")
    }
    Files.createDirectories(Path.of("build"))
    Files.writeString(Path.of("build/kotlin-consumer.json"), "{\"tests\":2,\"java\":17,\"status\":\"passed\"}")
    println("Kotlin independent consumer TESTS=2")
}

// Opt-in jcstress tests for CodeScope's admission/close protocol and lock-free cache-hit memo (host JVM only).
// Not published and not part of verifyHost/CI. Run (see docs/performance.md):
//   bash gradlew -Pmeguviz.jcstress=true :stress-jvm:jcstress "-Pjcstress.args=-m quick"
plugins { java }
java { toolchain.languageVersion.set(JavaLanguageVersion.of(17)) }
tasks.withType<JavaCompile>().configureEach { options.release.set(17); options.encoding = "UTF-8" }
dependencies {
    implementation(project(":ref-core"))
    implementation("org.openjdk.jcstress:jcstress-core:0.16")
    annotationProcessor("org.openjdk.jcstress:jcstress-core:0.16")
}
dependencyLocking { lockAllConfigurations() }
val jcstressArgs = providers.gradleProperty("jcstress.args").orElse("-m quick")
val jcstressReports = layout.buildDirectory.dir("reports/jcstress")
tasks.register<JavaExec>("jcstress") {
    group = "verification"
    description = "Runs the jcstress tests on the host JDK 17 (not ART evidence)."
    dependsOn(tasks.named("classes"))
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("org.openjdk.jcstress.Main")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(17)) })
    val extra = jcstressArgs
    val reports = jcstressReports
    workingDir = layout.buildDirectory.dir("jcstress-work").get().asFile
    doFirst { workingDir.mkdirs() }
    argumentProviders.add(CommandLineArgumentProvider {
        listOf("-r", reports.get().asFile.absolutePath) + extra.get().split(' ').filter { it.isNotBlank() }
    })
}
// Classpath for tools/mutation_check.py --jcstress (mutated ref-core classes are prepended to it).
tasks.register("jcstressClasspath") {
    dependsOn(tasks.named("classes"))
    val classpath = sourceSets["main"].runtimeClasspath
    val output = layout.buildDirectory.file("jcstress-classpath.txt")
    outputs.file(output)
    doLast { output.get().asFile.writeText(classpath.files.joinToString(File.pathSeparator)) }
}

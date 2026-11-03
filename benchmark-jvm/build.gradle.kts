// Host JVM microbenchmarks only: not published, not part of verifyHost/apiDump, not ART or device evidence.
// Run: bash gradlew -Pmeguviz.benchmarks=true :benchmark-jvm:jmh -Pjmh.include=<regex> "-Pjmh.args=-f 1 -wi 3 -i 5 -w 1s -r 1s -prof gc"
plugins { java }
java { toolchain.languageVersion.set(JavaLanguageVersion.of(17)) }
tasks.withType<JavaCompile>().configureEach { options.release.set(17); options.encoding = "UTF-8" }
dependencies {
    implementation(project(":ref-core"))
    implementation("org.openjdk.jmh:jmh-core:1.37")
    annotationProcessor("org.openjdk.jmh:jmh-generator-annprocess:1.37")
}
dependencyLocking { lockAllConfigurations() }
val jmhInclude = providers.gradleProperty("jmh.include").orElse(".*")
val jmhArgs = providers.gradleProperty("jmh.args").orElse("-f 1 -wi 3 -i 5 -w 1s -r 1s -prof gc")
val jmhResults = layout.buildDirectory.file("reports/jmh/results.json")
tasks.register<JavaExec>("jmh") {
    group = "benchmark"
    description = "Runs JMH microbenchmarks on the host JDK 17 (not ART evidence)."
    dependsOn(tasks.named("classes"))
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("org.openjdk.jmh.Main")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(17)) })
    val include = jmhInclude
    val extra = jmhArgs
    val results = jmhResults
    argumentProviders.add(CommandLineArgumentProvider {
        val output = results.get().asFile
        output.parentFile.mkdirs()
        listOf(include.get()) + extra.get().split(' ').filter { it.isNotBlank() } + listOf("-rf", "json", "-rff", output.absolutePath)
    })
}

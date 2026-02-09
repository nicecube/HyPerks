/**
 * NOTE: This is entirely optional and basics can be done in `settings.gradle.kts`
 */

val hytaleServerJarPath = providers.gradleProperty("hytale.server.jar")
    .orElse("HystaleJar/HytaleServer.jar")
    .get()
val hytaleServerJar = layout.projectDirectory.file(hytaleServerJarPath).asFile
val bundledRuntime by configurations.creating

if (!hytaleServerJar.exists()) {
    throw GradleException(
        "Missing Hytale server jar at '$hytaleServerJarPath'. " +
            "Place it there or override with -Phytale.server.jar=<path>."
    )
}

configurations.configureEach {
    // The ScaffoldIt plugin adds com.hypixel.hytale:Server automatically.
    // We force local compilation against your provided jar instead.
    exclude(group = "com.hypixel.hytale", module = "Server")
}

repositories {
    // Any external repositories besides: MavenLocal, MavenCentral, HytaleMaven, and CurseMaven
}

dependencies {
    // Local Hytale server API jar (override with -Phytale.server.jar=...).
    compileOnly(files(hytaleServerJar))

    // Embedded runtime dependencies for SQL persistence backends.
    bundledRuntime("org.xerial:sqlite-jdbc:3.49.1.0")
    bundledRuntime("com.mysql:mysql-connector-j:9.3.0")
}

tasks.named<Jar>("jar") {
    // Bundle custom asset pack files into the plugin jar.
    from(layout.projectDirectory.dir("assets"))

    // Bundle SQL drivers so sqlite/mysql modes work without extra files.
    from(bundledRuntime.map { if (it.isDirectory) it else zipTree(it) }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

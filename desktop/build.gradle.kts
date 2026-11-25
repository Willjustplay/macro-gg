plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

val appVersionName = (findProperty("appVersion") as? String)
    ?.trim()
    ?.removePrefix("v")
    ?.takeIf { it.matches(Regex("\\d+\\.\\d+(\\.\\d+)?")) }
    ?: "2.15.0"

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

application {
    mainClass.set("com.example.desktop.DesktopMainKt")
    applicationDefaultJvmArgs = listOf(
        "-XX:+UseG1GC",
        "-XX:MaxGCPauseMillis=20",
        "-XX:+UseStringDeduplication",
        "-Xms48m",
        "-Xmx512m",
        "-Dfile.encoding=UTF-8",
        "-Dsun.java2d.d3d=false",
        "-Dsun.java2d.noddraw=true",
        "-Dsun.java2d.opengl=false",
        "-Dflatlaf.useWindowDecorations=false"
    )
}

dependencies {
    implementation(project(":core"))
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.flatlaf)
    implementation(libs.jna)
    implementation(libs.jna.platform)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.register<Jar>("fatJar") {
    group = "distribution"
    description = "Builds a single runnable cross-platform JAR for Wjust macro PC (Windows / Linux / macOS)."
    archiveBaseName.set("Wjust-macro-PC")
    archiveVersion.set(appVersionName)
    archiveClassifier.set("universal")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes(
            "Main-Class" to "com.example.desktop.DesktopMainKt",
            "Implementation-Title" to "Wjust macro PC",
            "Implementation-Version" to appVersionName
        )
    }
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get()
            .filter { it.name.endsWith(".jar") }
            .map { zipTree(it) }
    }) {
        exclude(
            "META-INF/*.SF",
            "META-INF/*.DSA",
            "META-INF/*.RSA",
            "META-INF/MANIFEST.MF",
            "module-info.class",
            "META-INF/versions/*/module-info.class",
            "lua.class",
            "luac.class",
            "luajc*.class"
        )
    }
}

tasks.register<Exec>("packagePortableWindows") {
    group = "distribution"
    description = "Packages a self-contained portable Windows x64 app-image (.exe + minimal bundled JRE via jlink/jpackage)."
    dependsOn("fatJar")
    val jpackageOut = layout.buildDirectory.dir("jpackage-win")
    val stagingDir = layout.buildDirectory.dir("jpackage-input")
    val fatJarTask = tasks.named<Jar>("fatJar")
    val iconFile = layout.projectDirectory.file("src/main/resources/wjust_logo.ico")

    doFirst {
        val stage = stagingDir.get().asFile
        stage.deleteRecursively()
        stage.mkdirs()
        fatJarTask.get().archiveFile.get().asFile.copyTo(File(stage, "WjustMacroPC.jar"), overwrite = true)
        jpackageOut.get().asFile.deleteRecursively()
    }

    val jpackageBin = File(System.getProperty("java.home"), "bin/jpackage" + if (System.getProperty("os.name").lowercase().contains("win")) ".exe" else "")
    commandLine(
        jpackageBin.absolutePath,
        "--type", "app-image",
        "--name", "WjustMacroPC",
        "--app-version", appVersionName,
        "--vendor", "Wjust macro",
        "--description", "Wjust macro PC — High-Speed Desktop & Emulator Macro Studio",
        "--input", stagingDir.get().asFile.absolutePath,
        "--main-jar", "WjustMacroPC.jar",
        "--main-class", "com.example.desktop.DesktopMainKt",
        "--dest", jpackageOut.get().asFile.absolutePath,
        "--icon", iconFile.asFile.absolutePath,
        "--java-options", "-XX:+UseG1GC",
        "--java-options", "-XX:MaxGCPauseMillis=20",
        "--java-options", "-XX:+UseStringDeduplication",
        "--java-options", "-Xms48m",
        "--java-options", "-Xmx512m",
        "--java-options", "-Dfile.encoding=UTF-8",
        "--java-options", "-Dsun.java2d.d3d=false",
        "--java-options", "-Dsun.java2d.noddraw=true",
        "--java-options", "-Dsun.java2d.opengl=false",
        "--java-options", "-Dflatlaf.useWindowDecorations=false",
        "--add-modules", "java.base,java.datatransfer,java.desktop,java.instrument,java.logging,java.management,java.prefs,java.scripting,java.xml,jdk.charsets,jdk.unsupported"
    )
}

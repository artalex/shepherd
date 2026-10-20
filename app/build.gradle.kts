import com.android.build.api.artifact.ScopedArtifact
import com.android.build.api.variant.ScopedArtifacts
import com.android.tools.r8.Diagnostic
import com.android.tools.r8.DiagnosticsHandler
import com.android.tools.r8.DiagnosticsLevel
import com.android.tools.r8.diagnostic.MissingDefinitionsDiagnostic
import com.android.tools.r8.tracereferences.TraceReferences
import com.android.tools.r8.tracereferences.TraceReferencesCommand
import com.android.tools.r8.tracereferences.TraceReferencesKeepRules
import java.io.File
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.artalex.herdr"
    compileSdk = 37
    defaultConfig {
        applicationId = "dev.artalex.herdr"
        minSdk = 29
        targetSdk = 37
        versionCode = 13
        versionName = "2.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // The release key lives outside the repository; without it the release APK comes out unsigned.
    val signing = releaseSigning()
    signingConfigs {
        if (signing != null) {
            create("release") {
                storeFile = file(signing.getProperty("storeFile"))
                storePassword = signing.getProperty("storePassword")
                keyAlias = signing.getProperty("keyAlias")
                keyPassword = signing.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }
    // scripts/e2e.sh --release runs the instrumented tests against the minified, signed release build.
    testBuildType = providers.gradleProperty("herdr.testBuildType").getOrElse("debug")
    // Without window animations a dialog is whole on screen as soon as it shows: the screenshots caught one
    // halfway through fading in.
    testOptions { animationsDisabled = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // English and Russian only, the libraries' strings too: on a phone in another language the whole interface is in
    // English, not English with the libraries' translations mixed in.
    androidResources {
      localeFilters += listOf("en", "ru")
    }
    buildFeatures {
      compose = true
      aidl = false
      buildConfig = false
      shaders = false
    }

    packaging {
      resources {
        excludes += "/META-INF/{AL2.0,LGPL2.1}"
      }
    }
}

kotlin {
    jvmToolchain(21)
}

// Instrumented tests run in the app's process and take shared libraries (the runner's tracing, Compose
// internals, coroutines) from the app, so against the minified build they need those kept. Only for that
// build: the androidTest component exists for release only with herdr.testBuildType=release.
androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        val androidTest = variant.androidTest ?: return@onVariants
        val trace = tasks.register<TraceTestReferences>("trace${variant.name.replaceFirstChar(Char::titlecase)}TestReferences") {
            bootClasspath.set(androidComponents.sdkComponents.bootClasspath)
            rules.set(layout.buildDirectory.file("intermediates/test_references/${variant.name}/keep.pro"))
        }
        variant.artifacts.forScope(ScopedArtifacts.Scope.ALL).use(trace)
            .toGet(ScopedArtifact.CLASSES, TraceTestReferences::appJars, TraceTestReferences::appDirs)
        androidTest.artifacts.forScope(ScopedArtifacts.Scope.ALL).use(trace)
            .toGet(ScopedArtifact.CLASSES, TraceTestReferences::testJars, TraceTestReferences::testDirs)
        variant.proguardFiles.add(trace.flatMap { it.rules })
    }
}

/**
 * Keep rules for whatever the instrumented tests reference in the app and its libraries, from R8's
 * TraceReferences. Test dependencies the app also has come from the app at run time: they count as the app's.
 * Names stay as they are (renamed members don't always reach the test APK through the mapping), but R8 may open
 * up their access as it does for the rest of the app: otherwise it moves, for example, unkept parts of Kotlin's
 * multifile classes out of the package of their kept package-private neighbours.
 */
abstract class TraceTestReferences : DefaultTask() {
    @get:Classpath abstract val appJars: ListProperty<RegularFile>
    @get:Classpath abstract val appDirs: ListProperty<Directory>
    @get:Classpath abstract val testJars: ListProperty<RegularFile>
    @get:Classpath abstract val testDirs: ListProperty<Directory>
    @get:Classpath abstract val bootClasspath: ListProperty<RegularFile>
    @get:OutputFile abstract val rules: RegularFileProperty

    @TaskAction
    fun trace() {
        val app = File(temporaryDir, "app.jar")
        val appClasses = packClasses(appJars.get() + appDirs.get(), app) { true }
        val tests = File(temporaryDir, "tests.jar")
        packClasses(testJars.get() + testDirs.get(), tests) { it !in appClasses }
        val diagnostics = object : DiagnosticsHandler {
            // Optional platform classes the test runner probes for (android.test.*) are nobody's to keep.
            override fun modifyDiagnosticsLevel(level: DiagnosticsLevel, diagnostic: Diagnostic): DiagnosticsLevel =
                if (diagnostic is MissingDefinitionsDiagnostic) DiagnosticsLevel.INFO else level
        }
        val traced = File(temporaryDir, "traced.pro")
        TraceReferences.run(
            TraceReferencesCommand.builder(diagnostics)
                .addLibraryFiles(bootClasspath.get().map { it.asFile.toPath() })
                .addTargetFiles(app.toPath())
                .addSourceFiles(tests.toPath())
                .setConsumer(TraceReferencesKeepRules.builder().setOutputPath(traced.toPath()).build())
                .build()
        )
        val pinned = Regex("(?m)^-keep (?=(class|interface|enum|@interface) )")
        rules.get().asFile.writeText(traced.readText().replace(pinned, "-keep,allowaccessmodification "))
    }

    /** Packs the class files of [inputs] into [jar], the first copy of each, and returns their paths. */
    private fun packClasses(inputs: List<FileSystemLocation>, jar: File, include: (String) -> Boolean): Set<String> {
        val names = mutableSetOf<String>()
        ZipOutputStream(jar.outputStream().buffered()).use { out ->
            fun add(name: String, bytes: () -> ByteArray) {
                if (!name.endsWith(".class") || name.startsWith("META-INF/") || name.endsWith("module-info.class")) return
                if (!include(name) || !names.add(name)) return
                out.putNextEntry(ZipEntry(name))
                out.write(bytes())
                out.closeEntry()
            }
            for (input in inputs) {
                val root = input.asFile
                if (root.isDirectory) {
                    root.walkTopDown().filter { it.isFile }.forEach { f -> add(f.relativeTo(root).invariantSeparatorsPath) { f.readBytes() } }
                } else {
                    ZipFile(root).use { zip -> zip.entries().asSequence().forEach { e -> add(e.name) { zip.getInputStream(e).use { it.readBytes() } } } }
                }
            }
        }
        return names
    }
}

/** Signing properties: `herdr.signing` or `~/.android-keys/herdr-release.properties` (see README). */
fun releaseSigning(): Properties? {
    val path = providers.gradleProperty("herdr.signing")
        .getOrElse("${System.getProperty("user.home")}/.android-keys/herdr-release.properties")
    val file = file(path)
    return if (file.isFile) Properties().apply { file.inputStream().use { load(it) } } else null
}

dependencies {
  implementation(project(":core"))
  // JSch picks its BouncyCastle-based Ed25519/X25519 classes on Android (D8 drops multi-release JCE ones).
  runtimeOnly(libs.bouncycastle.prov)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.androidx.lifecycle.process)

  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)

  // Core Android dependencies
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)

  // Arch Components
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)

  // Compose
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.termlib)
  // Tooling
  debugImplementation(libs.androidx.compose.ui.tooling)
  // Instrumented tests
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)

  // Local tests: jUnit, coroutines, Android runner
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)

  // Instrumented tests: jUnit rules and runners
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.espresso.core)
  androidTestImplementation(libs.androidx.test.rules)
  androidTestImplementation(libs.kotlinx.serialization.json)

  // Navigation
  implementation(libs.androidx.navigation3.ui)
  implementation(libs.androidx.navigation3.runtime)
  implementation(libs.androidx.lifecycle.viewmodel.navigation3)
}

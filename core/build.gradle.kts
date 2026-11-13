plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
  // Android lint for the core too, though without NewApi: lint checks API levels in Android modules only, so a call
  // that phones at the app's minSdk lack goes unnoticed in the core.
  alias(libs.plugins.android.lint)
}

kotlin {
  jvmToolchain(21)
  compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

java {
  sourceCompatibility = JavaVersion.VERSION_17
  targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.jsch)

  testImplementation(libs.kotlin.test)
  testImplementation(libs.kotlinx.coroutines.test)
}

testing {
  suites {
    withType<JvmTestSuite>().configureEach {
      useJUnitJupiter(libs.versions.junitJupiter)
      // A test JUnit won't run, such as `= runBlocking { … }` whose block ends in a value, fails the run: by default
      // JUnit only logs a warning and leaves it out, and the run stays green.
      targets.configureEach { testTask.configure { systemProperty("junit.platform.discovery.issue.severity.critical", "WARNING") } }
    }

    // Runs against a real, isolated herdr server (see HerdrTestServer); skipped where herdr is missing.
    register<JvmTestSuite>("integrationTest") {
      dependencies {
        implementation(project())
        implementation(libs.kotlin.test.junit5)
        implementation(libs.kotlinx.coroutines.core)
        implementation(libs.kotlinx.serialization.json)
      }
      targets.configureEach {
        testTask.configure {
          shouldRunAfter(tasks.named("test"))
          // What the tests check is the herdr and sshd of this machine, and Gradle doesn't see them: a result up to
          // date or from the build cache would stand for another herdr, or for a run with every test skipped.
          doNotTrackState("runs against the herdr and sshd installed on this machine")
        }
      }
    }
  }
}

kotlin.target.compilations.named("integrationTest") { associateWith(kotlin.target.compilations.getByName("main")) }

tasks.named("check") { dependsOn(testing.suites.named("integrationTest")) }

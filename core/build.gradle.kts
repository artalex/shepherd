plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
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
    withType<JvmTestSuite>().configureEach { useJUnitJupiter() }

    // Runs against a real, isolated herdr server (see HerdrTestServer); skipped where herdr is missing.
    register<JvmTestSuite>("integrationTest") {
      dependencies {
        implementation(project())
        implementation(libs.kotlin.test.junit5)
        implementation(libs.kotlinx.coroutines.core)
        implementation(libs.kotlinx.serialization.json)
      }
      targets.configureEach { testTask.configure { shouldRunAfter(tasks.named("test")) } }
    }
  }
}

kotlin.target.compilations.named("integrationTest") { associateWith(kotlin.target.compilations.getByName("main")) }

tasks.named("check") { dependsOn(testing.suites.named("integrationTest")) }

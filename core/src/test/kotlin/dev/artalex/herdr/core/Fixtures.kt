package dev.artalex.herdr.core

/** JSON captured from a real herdr 0.9.5 lab session (see CLAUDE.md). */
fun fixture(name: String): String =
  checkNotNull(Thread.currentThread().contextClassLoader.getResource("fixtures/$name")) { "missing fixture $name" }.readText()

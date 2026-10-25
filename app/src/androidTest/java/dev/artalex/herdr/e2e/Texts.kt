package dev.artalex.herdr.e2e

import androidx.annotation.StringRes
import androidx.test.platform.app.InstrumentationRegistry

/** A string of the app in the language it runs in: the emulator's, unless a test sets the app's own. */
fun text(@StringRes id: Int, vararg args: Any): String = InstrumentationRegistry.getInstrumentation().targetContext.getString(id, *args)

/** What a string says after its last argument, for a test that doesn't know the arguments; [args] is how many it takes. */
fun textAfterArg(@StringRes id: Int, args: Int = 1): String = text(id, *Array(args) { ARG }).substringAfterLast(ARG)

private const val ARG = "\u0001"

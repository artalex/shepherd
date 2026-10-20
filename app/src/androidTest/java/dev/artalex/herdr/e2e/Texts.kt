package dev.artalex.herdr.e2e

import androidx.annotation.StringRes
import androidx.test.platform.app.InstrumentationRegistry

/** A string of the app in the language it runs in: the emulator's, unless a test sets the app's own. */
fun text(@StringRes id: Int, vararg args: Any): String = InstrumentationRegistry.getInstrumentation().targetContext.getString(id, *args)

/** What a one-argument string says after its argument, for a test that doesn't know the argument. */
fun textAfterArg(@StringRes id: Int): String = text(id, ARG).substringAfter(ARG)

private const val ARG = "\u0001"

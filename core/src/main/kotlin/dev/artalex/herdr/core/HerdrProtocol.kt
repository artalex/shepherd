package dev.artalex.herdr.core

/** herdr socket API generation the client is written against (`ping` → `protocol`). */
const val SUPPORTED_PROTOCOL = 22

/** Oldest herdr release the client supports. */
const val MIN_HERDR_VERSION = "0.9.5"

/** herdr's own name for the session that runs without `--session`. */
const val DEFAULT_SESSION = "default"

/** Longest session name herdr takes, in bytes; each character it takes is one byte. */
const val MAX_SESSION_NAME_LENGTH = 64

/** Why herdr would refuse a session name: see [sessionNameProblem]. */
enum class SessionNameProblem { EMPTY, CHARACTERS, TOO_LONG, DOTS }

/**
 * What herdr would refuse in session [name], by its `validate_name`; null if herdr takes the name. A name with
 * characters herdr doesn't take is reported for them first, even if it is also too long.
 */
fun sessionNameProblem(name: String): SessionNameProblem? =
  when {
    name.isEmpty() -> SessionNameProblem.EMPTY
    name.any { !(it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "._-") } -> SessionNameProblem.CHARACTERS
    name.length > MAX_SESSION_NAME_LENGTH -> SessionNameProblem.TOO_LONG
    name == "." || name == ".." -> SessionNameProblem.DOTS
    else -> null
  }

package dev.artalex.herdr.core.rpc

import java.io.IOException

/** herdr answered with `{"error":{"code","message"}}`, e.g. `agent_blocked` or `pane_not_found`. */
class ApiError(val code: String, message: String) : Exception("$code: $message")

/** No complete answer within the deadline; the connection was closed. */
class HerdrTimeoutException(message: String) : IOException(message)

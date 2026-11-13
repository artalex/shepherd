package dev.artalex.herdr.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `ping` result. [protocol] is herdr's private protocol between CLI and server, not the API's: nothing goes by it. */
@Serializable
data class Pong(val version: String, val protocol: Int? = null)

/** `pane.read` payload; `text` rows are separated by `\r\n` in ANSI format. */
@Serializable
data class PaneRead(
  @SerialName("pane_id") val paneId: String,
  val source: String,
  val format: String,
  val text: String,
  val truncated: Boolean = false,
)

enum class ReadSource(val wire: String) {
  VISIBLE("visible"),
  DETECTION("detection"),
}

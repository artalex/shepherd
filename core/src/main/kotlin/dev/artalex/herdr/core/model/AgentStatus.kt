package dev.artalex.herdr.core.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** Agent lifecycle state as herdr reports it; values from newer servers decode as [UNKNOWN]. */
@Serializable(with = AgentStatusSerializer::class)
enum class AgentStatus(val wire: String) {
  IDLE("idle"),
  WORKING("working"),
  BLOCKED("blocked"),
  DONE("done"),
  UNKNOWN("unknown");

  companion object {
    fun fromWire(value: String): AgentStatus = entries.firstOrNull { it.wire == value } ?: UNKNOWN
  }
}

internal object AgentStatusSerializer : KSerializer<AgentStatus> {
  override val descriptor = PrimitiveSerialDescriptor("AgentStatus", PrimitiveKind.STRING)

  override fun serialize(encoder: Encoder, value: AgentStatus) = encoder.encodeString(value.wire)

  override fun deserialize(decoder: Decoder) = AgentStatus.fromWire(decoder.decodeString())
}

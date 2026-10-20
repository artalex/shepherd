package dev.artalex.herdr

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable data object Agents : NavKey

@Serializable data object Settings : NavKey

/** The form for server [serverId], or for a new server when it is null. */
@Serializable data class ServerEditor(val serverId: String?) : NavKey

@Serializable data class Agent(val serverId: String, val paneId: String) : NavKey

@Serializable data class Terminal(val serverId: String, val paneId: String) : NavKey

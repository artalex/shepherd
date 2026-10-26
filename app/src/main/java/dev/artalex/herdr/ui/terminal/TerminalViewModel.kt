package dev.artalex.herdr.ui.terminal

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import dev.artalex.herdr.Server
import dev.artalex.herdr.core.terminal.CloseReason
import org.connectbot.terminal.ModifierManager

sealed interface TerminalPhase {
  /** The terminal view hasn't measured itself yet; the stream opens with its size. */
  data object Measuring : TerminalPhase

  data object Connecting : TerminalPhase

  data object Live : TerminalPhase

  /** Another client controls the pane; taking over is the user's call. */
  data object NeedsTakeover : TerminalPhase

  data class Closed(val reason: CloseReason) : TerminalPhase

  data class Failed(val message: String) : TerminalPhase
}

/** Ctrl, Alt and Shift buttons that stay on until the next key, like on a hardware keyboard row. */
class StickyModifiers : ModifierManager {
  var ctrl by mutableStateOf(false)
  var alt by mutableStateOf(false)
  var shift by mutableStateOf(false)

  override fun isCtrlActive() = ctrl

  override fun isAltActive() = alt

  override fun isShiftActive() = shift

  override fun clearTransients() {
    ctrl = false
    alt = false
    shift = false
  }

  /** libvterm modifier mask for keys sent from the extra row. */
  fun mask(): Int = (if (shift) MOD_SHIFT else 0) or (if (alt) MOD_ALT else 0) or (if (ctrl) MOD_CTRL else 0)

  private companion object {
    const val MOD_SHIFT = 1
    const val MOD_ALT = 2
    const val MOD_CTRL = 4
  }
}

/** The terminal screen of one pane: its [stream], shared with the pane's agent screen, and the extra keys row. */
class TerminalViewModel(server: Server, paneId: String) : ViewModel() {
  val stream = server.streams.of(paneId)

  val modifiers = StickyModifiers()

  /**
   * Keys from the extra row go through the emulator, which encodes them like the soft keyboard does. The row waits for
   * a live stream, so the emulator is there.
   */
  fun sendKey(vtermKey: Int) {
    stream.emulator.value?.dispatchKey(modifiers.mask(), vtermKey)
    modifiers.clearTransients()
  }

  fun sendChar(char: Char) {
    stream.emulator.value?.dispatchCharacter(modifiers.mask(), char.code)
    modifiers.clearTransients()
  }

  fun paste(text: String) = stream.paste(text)
}

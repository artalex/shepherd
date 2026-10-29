package dev.artalex.herdr.ui.terminal

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.splineBasedDecay
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import kotlin.math.abs
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.connectbot.terminal.SelectionController

/**
 * Vertical swipes over the pane scroll it the way the desktop's mouse wheel does, through [stream]: a row of history
 * for each text row the finger travels, and a fling after it. termlib would scroll only its own history, and herdr's
 * redrawn frames never fill that.
 *
 * Taps, long-press selection and pinch zoom stay termlib's: a swipe is taken only once it is clearly vertical, with one
 * finger, before a long press. termlib gives up a gesture that an ancestor consumes, so the swipe is taken in the
 * initial pass, before termlib sees it.
 *
 * While text is selected in [selection] no swipe is taken. Dragging a handle down, or the finger after a double tap,
 * looks just like one; and a scroll would leave the selection over other text, since herdr redraws the screen.
 */
fun Modifier.wheelScroll(stream: PaneStream, selection: TerminalSelection, cellSize: Size): Modifier =
  pointerInput(stream, selection, cellSize) {
    val decay = splineBasedDecay<Float>(this)
    coroutineScope {
      var fling: Job? = null
      awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        fling?.cancel()
        val rows = stream.rows ?: return@awaitEachGesture
        val columns = stream.columns ?: return@awaitEachGesture
        if (rows <= 0 || columns <= 0 || size.width <= 0 || size.height <= 0) return@awaitEachGesture
        val viewport = size
        fun resized() = size != viewport || stream.rows != rows || stream.columns != columns
        val rowHeight = cellSize.height
        val columnWidth = cellSize.width
        val longPressAt = down.uptimeMillis + viewConfiguration.longPressTimeoutMillis
        var pan = Offset.Zero
        while (true) {
          val event = awaitPointerEvent(PointerEventPass.Initial)
          if (resized()) return@awaitEachGesture
          val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
          if (!change.pressed || event.changes.count { it.pressed } > 1 || change.uptimeMillis > longPressAt) return@awaitEachGesture
          pan += change.positionChange()
          if (pan.getDistance() > viewConfiguration.touchSlop) {
            // termlib has had the down by now: a double tap starts its selection there.
            if (abs(pan.y) <= abs(pan.x) || selection.active) return@awaitEachGesture
            change.consume()
            break
          }
        }

        // Pixels travelled but not yet scrolled; a finger moving down goes back in history.
        var pending = 0f
        // Fullscreen apps route the wheel by its position. Keep the last touch position for the fling too.
        var position = down.position
        fun travel(dy: Float) {
          pending += dy
          val lines = (pending / rowHeight).toInt()
          if (lines != 0) {
            val column = (position.x / columnWidth).toInt().coerceIn(0, columns - 1)
            val row = (position.y / rowHeight).toInt().coerceIn(0, rows - 1)
            stream.scroll(lines, column, row)
            pending -= lines * rowHeight
          }
        }
        val velocity = VelocityTracker()
        velocity.addPosition(down.uptimeMillis, down.position)
        while (true) {
          val event = awaitPointerEvent(PointerEventPass.Initial)
          if (resized()) return@awaitEachGesture
          val change = event.changes.firstOrNull { it.id == down.id } ?: break
          val dy = change.positionChange().y
          event.changes.forEach { it.consume() }
          // The lift counts too: a finger that stopped before it gives no fling.
          velocity.addPosition(change.uptimeMillis, change.position)
          position = change.position
          if (!change.pressed) break
          travel(dy)
        }
        val initialVelocity = velocity.calculateVelocity().y
        fling =
          launch {
            var last = 0f
            AnimationState(0f, initialVelocity).animateDecay(decay) {
              if (resized()) {
                cancelAnimation()
                return@animateDecay
              }
              travel(value - last)
              last = value
            }
          }
      }
    }
  }

/** Whether termlib has text selected in a pane's terminal; it hands over [controller] once the terminal is composed. */
class TerminalSelection {
  var controller: SelectionController? = null

  val active: Boolean
    get() = controller?.isSelectionActive == true
}

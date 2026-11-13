#!/usr/bin/env python3
"""A stand-in coding agent for the app's e2e tests (HostFixture.fakeClaude, fakeKittyAgent).

It takes its terminal as Claude Code and Codex do: raw, with no echo and no line editing or signals from the terminal,
and with bracketed paste on, so that herdr hands it a prompt as one paste and Enter after it. With --kitty it asks for
the kitty keyboard protocol too, with Claude Code's flags: herdr then sends keys with their modifiers, Ctrl+Enter as
ESC[13;5u.

It shows everything it gets as it comes, as `cat -vT` does: ESC as ^[, any other control character as ^X, a tab as ^I,
DEL as ^?, a C1 control as M-^X. Enter starts a new line; within a paste a line feed does, and a carriage return shows
as ^M. A prompt so shows as ^[[200~…^[[201~ on lines of its own, and a key after it on the next line.

Started as argv0 claude or codex (`exec -a claude python3 fake_agent.py`), it passes for that agent with herdr; the
test reports its status.
"""

import codecs
import os
import sys
import termios

PASTE_START = "\x1b[200~"
PASTE_END = "\x1b[201~"


def shown(ch, pasting):
    code = ord(ch)
    if ch == "\n" or (ch == "\r" and not pasting):
        return "\n"
    if code < 0x20:
        return "^" + chr(code + 0x40)
    if code == 0x7F:
        return "^?"
    if 0x80 <= code < 0xA0:
        return "M-^" + chr(code - 0x40)
    return ch


def main():
    kitty = "--kitty" in sys.argv[1:]
    fd = sys.stdin.fileno()
    saved = termios.tcgetattr(fd)
    mode = termios.tcgetattr(fd)
    # As cfmakeraw, but the output keeps its line ends: the screen reads as lines.
    mode[0] &= ~(termios.IGNBRK | termios.BRKINT | termios.PARMRK | termios.ISTRIP | termios.INLCR | termios.IGNCR | termios.ICRNL | termios.IXON)
    mode[3] &= ~(termios.ECHO | termios.ECHONL | termios.ICANON | termios.ISIG | termios.IEXTEN)
    mode[6][termios.VMIN] = 1
    mode[6][termios.VTIME] = 0
    termios.tcsetattr(fd, termios.TCSANOW, mode)
    os.write(1, ("\x1b[?2004h" + ("\x1b[>5u" if kitty else "")).encode())
    decoder = codecs.getincrementaldecoder("utf-8")(errors="replace")
    pasting = False
    recent = ""
    try:
        while data := os.read(fd, 4096):
            out = []
            for ch in decoder.decode(data):
                out.append(shown(ch, pasting))
                recent = (recent + ch)[-len(PASTE_START):]
                if recent == PASTE_START:
                    pasting = True
                elif recent == PASTE_END:
                    pasting = False
            os.write(1, "".join(out).encode())
    finally:
        os.write(1, ("\x1b[?2004l" + ("\x1b[<u" if kitty else "")).encode())
        termios.tcsetattr(fd, termios.TCSANOW, saved)


main()

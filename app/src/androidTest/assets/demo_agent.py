#!/usr/bin/env python3
"""A stand-in coding agent for the app's screenshots (DemoScreens, scripts/screenshots.sh).

Draws one scene of a coding agent's terminal at the terminal's width, and draws it again when the terminal is resized,
as it is when the app shows the pane at the phone's size. Keys are read and dropped. Started as argv0 claude, codex or
gemini (`exec -a claude python3 demo_agent.py claude-ask`), it passes for that agent with herdr; the test reports its
status.
"""

import os
import re
import select
import signal
import sys
import termios
import textwrap
import tty

RESET = "\x1b[0m"
BOLD = "\x1b[1m"
DIM = "\x1b[38;5;245m"
ACCENT = "\x1b[38;5;209m"
GREEN = "\x1b[38;5;114m"
BLUE = "\x1b[38;5;75m"
CYAN = "\x1b[38;5;80m"
ANSI = re.compile(r"\x1b\[[0-9;]*m")


def visible(s):
    return len(ANSI.sub("", s))


def wrap(text, width):
    # Paths stay whole: the files window finds them on the screen.
    return textwrap.wrap(text, max(10, width), break_long_words=False, break_on_hyphens=False) or [""]


def para(text, width, first, rest=None, style=""):
    """[text] after the bullet [first], the following lines under it after [rest] (by default blanks as wide)."""
    rest = " " * visible(first) if rest is None else rest
    lines = wrap(text, width - visible(first))
    return [first + style + lines[0] + RESET] + [rest + style + line + RESET for line in lines[1:]]


def tool(name, argument, result):
    return [GREEN + "● " + RESET + BOLD + name + RESET + "(" + argument + ")", "  " + DIM + "└ " + result + RESET, ""]


def box(rows, width, border=DIM):
    """A frame like the one agents draw around a question; rows are (prefix, text, style), wrapped under the prefix."""
    inner = width - 4
    out = [border + "╭" + "─" * (width - 2) + "╮" + RESET]
    for prefix, text, style in rows:
        lines = wrap(text, inner - len(prefix)) if text else [""]
        for i, line in enumerate(lines):
            lead = prefix if i == 0 else " " * len(prefix)
            pad = inner - len(lead) - len(line)
            out.append(border + "│ " + RESET + style + lead + line + RESET + " " * pad + border + " │" + RESET)
    out.append(border + "╰" + "─" * (width - 2) + "╯" + RESET)
    return out


def header(mark, name, folder):
    return [mark + " " + RESET + BOLD + name + RESET, DIM + "  " + folder + RESET, ""]


def claude_ask(w):
    lines = header(ACCENT + "✻", "Claude Code", "~/projects/shop-api")
    lines += para("Is the lint clean?", w, DIM + "> " + RESET) + [""]
    lines += tool("Bash", "npm run lint", "No problems found")
    lines += para("Yes, the lint is clean.", w, "● ") + [""]
    lines += para("Checkout fails when the cart has a gift card. Fix it and add a test.", w, DIM + "> " + RESET) + [""]
    lines += para("I'll start with where gift cards meet the total.", w, "● ") + [""]
    lines += tool("Search", 'pattern: "giftCard", path: "src"', "Found 6 files")
    lines += tool("Read", "src/checkout/total.ts", "Read 84 lines")
    lines += tool("Read", "src/checkout/giftCards.ts", "Read 41 lines")
    lines += para("The gift card is applied before tax, so the total can go below zero. I'll apply it after tax and stop at zero.", w, "● ") + [""]
    lines += tool("Update", "src/checkout/total.ts", "4 lines added, 2 removed")
    lines += tool("Write", "test/checkout.test.ts", "38 lines")
    lines += para("The new flow is drawn in docs/checkout-flow.png. Now the tests:", w, "● ") + [""]
    lines += box(
        [
            ("", "Bash command", BOLD),
            ("", "", ""),
            ("  ", "npm test -- checkout", ""),
            ("  ", "Run the checkout tests", DIM),
            ("", "", ""),
            ("", "Do you want to proceed?", ""),
            ("› 1. ", "Yes", ACCENT),
            ("  2. ", "Yes, and don't ask again for npm test commands", ""),
            ("  3. ", "No, and tell Claude what to do differently (esc)", ""),
        ],
        w,
        border=ACCENT,
    )
    return lines


def codex_work(w):
    lines = header(BOLD + ">_", "Codex", "~/projects/shop-api")
    lines += para("Add per-client rate limits to the public API.", w, CYAN + "▌ " + RESET) + [""]
    lines += para("I'll add a token bucket per API key in the middleware.", w, "• ") + [""]
    lines += ["• " + BOLD + "Explored" + RESET] + para("Read src/middleware/index.ts, src/routes/public.ts", w, "  " + DIM + "└ " + RESET, "    ", DIM) + [""]
    lines += para("Edited src/middleware/rateLimit.ts (+46 -0)", w, "• ") + [""]
    lines += ["• " + BOLD + "Ran" + RESET + " npm run typecheck", "  " + DIM + "└ no errors" + RESET, ""]
    lines += [DIM + "◦ " + RESET + BOLD + "Working" + RESET + DIM + " (1m 32s • esc to interrupt)" + RESET]
    return lines


def claude_done(w):
    lines = header(ACCENT + "✻", "Claude Code", "~/projects/mobile-app")
    lines += para("Update the dependencies and make sure the app still builds.", w, DIM + "> " + RESET) + [""]
    lines += tool("Bash", "npm outdated", "14 packages can be updated")
    lines += tool("Bash", "npm update && npm test", "142 passed")
    lines += para("Updated 14 packages. All 142 tests pass and the release build works. The notes are in docs/deps-2026-10.md.", w, "● ") + [""]
    lines += box([("> ", "", "")], w) + [DIM + "  ? for shortcuts" + RESET]
    return lines


def gemini_idle(w):
    lines = header(BLUE + "✦", "Gemini CLI", "~/projects/data-pipeline")
    lines += para("Why is the nightly import slow?", w, DIM + "> " + RESET) + [""]
    lines += para(
        "Most of the time goes to parsing the CSV row by row. Reading it in chunks with pyarrow cuts the import from 41 to 6 minutes on yesterday's file.",
        w,
        BLUE + "✦ " + RESET,
    ) + [""]
    lines += box([("> ", "Type your message", DIM)], w)
    return lines


SCENES = {"claude-ask": claude_ask, "codex-work": codex_work, "claude-done": claude_done, "gemini-idle": gemini_idle}


def draw(scene):
    size = os.get_terminal_size(sys.stdout.fileno())
    lines = scene(size.columns)
    # Taller than the screen: the end stays in sight, as in a terminal.
    lines = lines[-size.lines :]
    sys.stdout.write("\x1b[?25l\x1b[H\x1b[2J" + "\r\n".join(lines))
    sys.stdout.flush()


def main():
    scene = SCENES[sys.argv[1]]
    fd = sys.stdin.fileno()
    saved = termios.tcgetattr(fd)
    resized = [True]
    signal.signal(signal.SIGWINCH, lambda *_: resized.__setitem__(0, True))
    try:
        tty.setraw(fd)
        while True:
            if resized[0]:
                resized[0] = False
                draw(scene)
            if select.select([fd], [], [], 0.5)[0]:
                os.read(fd, 1024)
    finally:
        termios.tcsetattr(fd, termios.TCSADRAIN, saved)


if __name__ == "__main__":
    main()

package dev.artalex.herdr.core.model

/**
 * An interactive agent herdr 0.9.5 can start: the `agent.start` kind, the executable herdr types into the shell
 * (`interactive_agent_executable` in herdr's sources) and a name to show. Claude Code first, then roughly by use.
 */
enum class AgentKind(val kind: String, val executable: String, val title: String) {
  CLAUDE("claude", "claude", "Claude Code"),
  CODEX("codex", "codex", "Codex"),
  GEMINI("gemini", "gemini", "Gemini CLI"),
  OPENCODE("opencode", "opencode", "OpenCode"),
  COPILOT("copilot", "copilot", "GitHub Copilot"),
  CURSOR("cursor", "cursor-agent", "Cursor"),
  AMP("amp", "amp", "Amp"),
  DROID("droid", "droid", "Droid"),
  QWEN("qwen", "qwen", "Qwen Code"),
  KIMI("kimi", "kimi", "Kimi"),
  KIRO("kiro", "kiro-cli", "Kiro"),
  CLINE("cline", "cline", "Cline"),
  KILO("kilo", "kilo", "Kilo"),
  GROK("grok", "grok", "Grok"),
  DEVIN("devin", "devin", "Devin"),
  ANTIGRAVITY("agy", "agy", "Antigravity"),
  PI("pi", "pi", "Pi"),
  OMP("omp", "omp", "OMP"),
  MASTRACODE("mastracode", "mastracode", "Mastra Code"),
  HERMES("hermes", "hermes", "Hermes"),
  QODER("qodercli", "qodercli", "Qoder"),
  LETTA("letta", "letta", "Letta"),
  MAKI("maki", "maki", "Maki"),
  MUSE("muse", "muse", "Muse"),
}

package dev.artalex.herdr.core.terminal

/**
 * The web address to open for a link tapped in a terminal, or null for a link that isn't a web one. termlib finds
 * http and https links in the text as written, and bare hosts such as `example.com/x` or `192.168.0.10:8080`, which
 * get `http://`. A link a program sets with OSC 8 may have any scheme; only http and https open, so that a line on the
 * screen can't start some other app on the phone.
 */
fun webLink(link: String): String? {
  val text = link.trim()
  // Browsers match the scheme as written, and only in lower case.
  WEB.matchEntire(text)?.let { return it.groupValues[1].lowercase() + it.groupValues[2] }
  return if (BARE_HOST.matches(text)) "http://$text" else null
}

/** An http or https link: the scheme in any case, then the rest. */
private val WEB = Regex("(https?)(://\\S+)", RegexOption.IGNORE_CASE)

/** A host with a dot in its name, as termlib finds one without a scheme, then a port, a path, a query, a fragment. */
private val BARE_HOST = Regex("[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)+(:\\d{1,5})?([/?#]\\S*)?")

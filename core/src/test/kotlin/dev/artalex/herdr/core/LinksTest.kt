package dev.artalex.herdr.core

import dev.artalex.herdr.core.terminal.webLink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LinksTest {
  @Test
  fun webLinksOpenAsWritten() {
    assertEquals("https://example.com/a?b=1#c", webLink("https://example.com/a?b=1#c"))
    assertEquals("http://localhost:3000/", webLink("http://localhost:3000/"))
    assertEquals("https://example.com/x", webLink(" https://example.com/x "))
  }

  @Test
  fun schemeInCapitalsComesOutInLowerCase() {
    assertEquals("https://Example.com/Path", webLink("HTTPS://Example.com/Path"))
  }

  @Test
  fun bareHostsGetHttp() {
    // termlib finds hosts without a scheme: a domain with a known ending, or an address with a port.
    assertEquals("http://example.com/x", webLink("example.com/x"))
    assertEquals("http://files.example.ru", webLink("files.example.ru"))
    assertEquals("http://192.168.0.10:8080/status", webLink("192.168.0.10:8080/status"))
  }

  @Test
  fun otherSchemesDontOpen() {
    // A program sets these with OSC 8; on a phone they would start other apps or read local files.
    for (link in listOf("ftp://example.com/f", "file:///etc/hosts", "intent://scan/#Intent;scheme=zxing;end", "javascript:alert(1)", "mailto:a@example.com", "tel:123")) {
      assertNull(webLink(link), link)
    }
  }

  @Test
  fun notALinkDoesntOpen() {
    for (link in listOf("", "https://", "https://exa mple.com", "localhost", "a b.com")) assertNull(webLink(link), link)
  }
}

package dev.artalex.herdr.e2e

import android.app.LocaleManager
import android.os.LocaleList
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import dev.artalex.herdr.HerdrApp
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The interface speaks Russian on a phone set to Russian and English on any other. The app's own locale stands in for
 * the phone's here, as Android's per-app language setting does.
 */
@OptIn(ExperimentalTestApi::class)
class LocaleE2eTest {
  @get:Rule val localNetwork: GrantPermissionRule = GrantPermissionRule.grant("android.permission.ACCESS_LOCAL_NETWORK")
  @get:Rule val compose = createEmptyComposeRule()

  private lateinit var fixture: HostFixture
  private lateinit var agent: String
  private val run = System.currentTimeMillis().toString().takeLast(5)
  private val locales = InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(LocaleManager::class.java)

  @Before
  fun seed() {
    fixture = HostFixture(E2eArgs.require())
    // An agent with a question: its status shows in the screenshots in both languages.
    agent = fixture.workspace("e2e-locale-$run")
    fixture.fakeClaude(agent, "blocked")
    fixture.connectApp(ApplicationProvider.getApplicationContext<HerdrApp>().container)
  }

  @After
  fun followThePhone() {
    locales.applicationLocales = LocaleList.getEmptyLocaleList()
    if (::fixture.isInitialized) fixture.close()
  }

  @Test
  fun russianForRussianEnglishForTheRest() {
    for ((tag, title) in listOf("ru-RU" to "Агенты", "uk-UA" to "Agents", "de-DE" to "Agents", "en-US" to "Agents")) {
      locales.applicationLocales = LocaleList.forLanguageTags(tag)
      launchApp(compose, "russianForRussianEnglishForTheRest-$tag") {
        compose.waitUntilExactlyOneExists(hasText(title), 10_000)
        if (tag == "ru-RU" || tag == "en-US") {
          compose.scrollListTo(paneTag(agent))
          Screens.take(compose, "15-agents-$tag")
          compose.onNodeWithTag("openSettings").performClick()
          compose.waitUntilExactlyOneExists(hasTestTag("notifications"), 10_000)
          Screens.take(compose, "15-settings-$tag")
        }
      }
    }
  }
}

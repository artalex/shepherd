package dev.artalex.herdr.core

import dev.artalex.herdr.core.rpc.HerdrApi
import dev.artalex.herdr.core.store.SessionPoller
import dev.artalex.herdr.core.store.SessionState
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class SessionPollerTest {
  private val snapshotResult = fixture("session-snapshot.json").substringAfter("\"result\":").trim().removeSuffix("}")

  @Test
  fun pollsOnlyWhileCollectedAndRefreshesOnDemand() = runBlocking {
    val transport = FakeTransport { ok(it, snapshotResult) }
    val poller = SessionPoller(HerdrApi(transport), this, periodMs = 10_000)
    assertEquals(0, transport.requests.size)

    val collector = launch { poller.state.collect {} }
    withTimeout(2_000) { poller.state.filterIsInstance<SessionState.Ready>().first() }
    assertEquals(1, transport.requests.size)

    poller.refreshNow()
    withTimeout(2_000) { while (transport.requests.size < 2) delay(10) }

    collector.cancel()
    delay(100)
    val polls = transport.requests.size
    poller.refreshNow()
    delay(200)
    assertEquals(polls, transport.requests.size)
    coroutineContext.cancelChildren()
  }

  @Test
  fun failureKeepsLastSnapshot() = runBlocking {
    val failing = AtomicBoolean(false)
    val transport = FakeTransport {
      if (failing.get()) """{"id":"${it.id()}","error":{"code":"server_unavailable","message":"down"}}""" else ok(it, snapshotResult)
    }
    val poller = SessionPoller(HerdrApi(transport), this, periodMs = 50)
    val collector = launch { poller.state.collect {} }
    withTimeout(2_000) { poller.state.filterIsInstance<SessionState.Ready>().first() }
    failing.set(true)
    val failed = withTimeout(2_000) { poller.state.filterIsInstance<SessionState.Failed>().first() }
    assertIs<SessionState.Ready>(failed.last)
    assertTrue(failed.error.message!!.contains("server_unavailable"))
    collector.cancel()
    coroutineContext.cancelChildren()
  }
}

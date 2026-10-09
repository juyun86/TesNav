package com.garan.tesnav.export

import com.garan.tesnav.model.NavigationState
import com.garan.tesnav.model.NavigationMode
import com.garan.tesnav.model.NavigationManeuver
import com.garan.tesnav.model.OemLanePosition
import com.garan.tesnav.model.OemVehicleLaneState
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Executors
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.HttpUrl
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

class HttpNavAssistV2ExporterTest {
    @Test fun `HTTP reception evidence requires acceptance and matching session and sequence`() {
        val body = """{"sessionId":"current","sequence":12}"""
        assertTrue(confirmsNavigationSnapshot(body, """{"accepted":true,"sessionId":"current","sequence":12}"""))
        for (reply in listOf("", "{}", """{"accepted":false,"sessionId":"current","sequence":12}""",
            """{"accepted":true,"sessionId":"old","sequence":12}""",
            """{"accepted":true,"sessionId":"current","sequence":11}""")) {
            assertFalse(confirmsNavigationSnapshot(body, reply))
        }
    }
    @Test fun `speech receipt is sent only after exact completion and is cleared on stop`() {
        val sent = LinkedBlockingQueue<NavAssistV2Snapshot>()
        val session = NavAssistV2Session()
        val token = "a".repeat(32)
        val exporter = HttpNavAssistV2Exporter(
            config = NavAssistV2ExportConfig(baseUrl=""), navigationSession=session,
            stateProvider={ activeState() }, identity=AndroidKeystoreNavAssistIdentity.generatedForTest(),
            endpointDiscovery=NavAssistV2EndpointDiscovery { NavAssistV2DiscoveryResult.NotFound },
            pinnedDeviceProvider={ null }, useUnauthenticatedUdp=true, feedbackElapsedMs={ 100L },
            udpClient=NavAssistV3UdpClient { body, id, _ ->
                sent.put(Gson().fromJson(String(body, Charsets.UTF_8), NavAssistV2Snapshot::class.java))
                NavAssistV3UdpAck("192.168.8.101", null, LaneAnnouncement(token, id, "left", 100L))
            },
        )
        try {
            exporter.start()
            assertEquals(null, sent.poll(3, TimeUnit.SECONDS)!!.laneChangeSpeechCompletedId)
            assertEquals(null, sent.poll(3, TimeUnit.SECONDS)!!.laneChangeSpeechCompletedId)
            exporter.completeLaneAnnouncement("b".repeat(32), session.sessionId)
            exporter.completeLaneAnnouncement(token, "wrong-session")
            assertEquals(null, sent.poll(3, TimeUnit.SECONDS)!!.laneChangeSpeechCompletedId)
            exporter.completeLaneAnnouncement(token, session.sessionId)
            var found = false
            repeat(3) { if (sent.poll(3, TimeUnit.SECONDS)!!.laneChangeSpeechCompletedId == token) found=true }
            assertTrue(found)
        } finally { exporter.stop() }
        assertEquals(null, exporter.laneAnnouncement.value)
        exporter.completeLaneAnnouncement(token, session.sessionId)
        assertEquals(null, exporter.laneAnnouncement.value)
    }
    private val identity = AndroidKeystoreNavAssistIdentity.generatedForTest()
    private val deviceId = "d".repeat(32)

    @Test
    fun `owner confirmation waits beyond the receiver replay window even with immediate ACKs`() {
        val now = java.util.concurrent.atomic.AtomicLong(0L)
        val sent = LinkedBlockingQueue<NavAssistV2Snapshot>()
        val calls = AtomicInteger()
        val exporter = HttpNavAssistV2Exporter(
            config = NavAssistV2ExportConfig(baseUrl = ""), stateProvider = { activeState() }, identity = identity,
            endpointDiscovery = NavAssistV2EndpointDiscovery { NavAssistV2DiscoveryResult.NotFound },
            pinnedDeviceProvider = { null }, useUnauthenticatedUdp = true,
            requireOwnerHandshake = true, monotonicMs = now::get,
            udpClient = NavAssistV3UdpClient { body, _, _ ->
                sent.put(Gson().fromJson(String(body, Charsets.UTF_8), NavAssistV2Snapshot::class.java))
                now.set(if (calls.incrementAndGet() == 1) 3_000L else 3_001L)
                NavAssistV3UdpAck("192.168.53.232", null)
            },
        )
        try {
            exporter.start()
            val packets = (1..3).map { requireNotNull(sent.poll(2, TimeUnit.SECONDS)) }
            assertEquals(listOf(false, false, true), packets.map { it.routeActive })
            assertEquals(listOf(0L, 0L), packets.take(2).map { it.maneuverEventId })
        } finally { exporter.stop() }
    }

    @Test
    fun `owner handshake survives UDP ACK loss while HTTP failure resets it`() {
        for (udp in listOf(true, false)) {
            val now = java.util.concurrent.atomic.AtomicLong(0L)
            val sent = LinkedBlockingQueue<NavAssistV2Snapshot>()
            val calls = AtomicInteger()
            val exporter = HttpNavAssistV2Exporter(
                config = NavAssistV2ExportConfig(baseUrl = ""), stateProvider = { activeState() },
                identity = identity,
                endpointDiscovery = NavAssistV2EndpointDiscovery { NavAssistV2DiscoveryResult.Found("192.168.53.232", deviceId) },
                pinnedDeviceProvider = { null }, useUnauthenticatedUdp = udp, requireOwnerHandshake = true, ownerSettleMs = 0L,
                monotonicMs = now::get,
                udpClient = NavAssistV3UdpClient { body, _, _ ->
                    sent.put(Gson().fromJson(String(body, Charsets.UTF_8), NavAssistV2Snapshot::class.java))
                    if (calls.incrementAndGet() == 2) { now.set(1_200L); null } else NavAssistV3UdpAck("192.168.53.232", null)
                },
                httpClient = object : NavAssistV2HttpClient {
                    override fun post(endpoint: HttpUrl, body: String, appKeyId: String, signature: String) {
                        sent.put(Gson().fromJson(body, NavAssistV2Snapshot::class.java))
                        if (calls.incrementAndGet() == 2) error("connection changed")
                    }
                    override fun close() = Unit
                },
            )
            try {
                exporter.start()
                val packets = (1..4).map { requireNotNull(sent.poll(3, TimeUnit.SECONDS)) }
                val expected = if (udp) listOf(false, true, true, true) else listOf(false, true, false, true)
                assertEquals(expected, packets.map { it.routeActive })
                assertEquals(expected.map { if (it) packets[1].maneuverEventId else 0L }, packets.map { it.maneuverEventId })
                assertTrue(packets[1].maneuverEventId > 0)
                assertEquals(1, packets.map { it.sessionId }.toSet().size)
                assertEquals(listOf(1L, 2L, 3L, 4L), packets.map { it.sequence })
            } finally { exporter.stop() }
        }
    }

    @Test
    fun `confirmed UDP owner survives ACK loss and clock changes without changing the event`() {
        for (age in listOf(1L, 600L, 1_199L, 1_200L, -1L)) {
            val now = java.util.concurrent.atomic.AtomicLong(0L)
            val sent = LinkedBlockingQueue<NavAssistV2Snapshot>()
            val calls = AtomicInteger()
            val exporter = HttpNavAssistV2Exporter(
                config = NavAssistV2ExportConfig(baseUrl = ""), stateProvider = { activeState() }, identity = identity,
                endpointDiscovery = NavAssistV2EndpointDiscovery { NavAssistV2DiscoveryResult.NotFound },
                pinnedDeviceProvider = { null }, useUnauthenticatedUdp = true,
                requireOwnerHandshake = true, ownerSettleMs = 0L, monotonicMs = now::get,
                udpClient = NavAssistV3UdpClient { body, _, _ ->
                    sent.put(Gson().fromJson(String(body, Charsets.UTF_8), NavAssistV2Snapshot::class.java))
                    if (calls.incrementAndGet() == 2) { now.set(age); null }
                    else NavAssistV3UdpAck("192.168.53.232", null)
                },
            )
            try {
                exporter.start()
                val packets = (1..4).map { requireNotNull(sent.poll(3, TimeUnit.SECONDS)) }
                assertEquals(listOf(false, true, true, true), packets.map { it.routeActive })
                assertEquals(packets[1].maneuverEventId, packets[2].maneuverEventId)
                assertEquals(1, packets.map { it.sessionId }.toSet().size)
            } finally { exporter.stop() }
        }
    }

    @Test
    fun `publisher pause does not replace a confirmed UDP owner`() {
        val now = java.util.concurrent.atomic.AtomicLong(0L)
        val prepared = AtomicInteger()
        val sent = LinkedBlockingQueue<NavAssistV2Snapshot>()
        val exporter = HttpNavAssistV2Exporter(
            config = NavAssistV2ExportConfig(baseUrl = ""),
            stateProvider = { if (prepared.incrementAndGet() == 2) now.set(1_200L); activeState() }, identity = identity,
            endpointDiscovery = NavAssistV2EndpointDiscovery { NavAssistV2DiscoveryResult.NotFound },
            pinnedDeviceProvider = { null }, useUnauthenticatedUdp = true,
            requireOwnerHandshake = true, ownerSettleMs = 0L, monotonicMs = now::get,
            udpClient = NavAssistV3UdpClient { body, _, _ ->
                sent.put(Gson().fromJson(String(body, Charsets.UTF_8), NavAssistV2Snapshot::class.java))
                NavAssistV3UdpAck("192.168.53.232", null)
            },
        )
        try {
            exporter.start()
            assertEquals(listOf(false, true, true), (1..3).map { requireNotNull(sent.poll(3, TimeUnit.SECONDS)).routeActive })
        } finally { exporter.stop() }
    }

    @Test
    fun `intermittent UDP failures do not restart owner acquisition`() {
        val now = java.util.concurrent.atomic.AtomicLong(0L)
        val sent = LinkedBlockingQueue<NavAssistV2Snapshot>()
        val calls = AtomicInteger()
        val exporter = HttpNavAssistV2Exporter(
            config = NavAssistV2ExportConfig(baseUrl = ""), stateProvider = { activeState() }, identity = identity,
            endpointDiscovery = NavAssistV2EndpointDiscovery { NavAssistV2DiscoveryResult.NotFound },
            pinnedDeviceProvider = { null }, useUnauthenticatedUdp = true,
            requireOwnerHandshake = true, monotonicMs = now::get,
            udpClient = NavAssistV3UdpClient { body, _, _ ->
                sent.put(Gson().fromJson(String(body, Charsets.UTF_8), NavAssistV2Snapshot::class.java))
                when (val call = calls.incrementAndGet()) {
                    1 -> { now.set(3_001L); NavAssistV3UdpAck("192.168.53.232", null) }
                    2, 3, 4 -> { now.set(3_001L + (call - 1) * 400L); null }
                    5 -> { now.set(7_201L); NavAssistV3UdpAck("192.168.53.232", null) }
                    else -> { now.set(7_202L); NavAssistV3UdpAck("192.168.53.232", null) }
                }
            },
        )
        try {
            exporter.start()
            val packets = (1..7).map { requireNotNull(sent.poll(3, TimeUnit.SECONDS)) }
            assertEquals(listOf(false, true, true, true, true, true, true), packets.map { it.routeActive })
            assertEquals(packets[1].maneuverEventId, packets.last().maneuverEventId)
        } finally { exporter.stop() }
    }

    @Test
    fun `continuous UDP reply loss does not disable confirmed navigation`() {
        val now = java.util.concurrent.atomic.AtomicLong(0L)
        val prepared = AtomicInteger()
        val sent = LinkedBlockingQueue<NavAssistV2Snapshot>()
        val calls = AtomicInteger()
        val exporter = HttpNavAssistV2Exporter(
            config = NavAssistV2ExportConfig(baseUrl = ""),
            stateProvider = {
                when (prepared.incrementAndGet()) {
                    3 -> now.set(6_002L)
                    5 -> now.set(9_003L)
                }
                activeState()
            },
            identity = identity,
            endpointDiscovery = NavAssistV2EndpointDiscovery { NavAssistV2DiscoveryResult.NotFound },
            pinnedDeviceProvider = { null }, useUnauthenticatedUdp = true,
            requireOwnerHandshake = true, monotonicMs = now::get,
            udpClient = NavAssistV3UdpClient { body, _, _ ->
                sent.put(Gson().fromJson(String(body, Charsets.UTF_8), NavAssistV2Snapshot::class.java))
                when (calls.incrementAndGet()) {
                    1 -> { now.set(3_001L); NavAssistV3UdpAck("192.168.53.232", null) }
                    2, 3 -> null
                    else -> NavAssistV3UdpAck("192.168.53.232", null)
                }
            },
        )
        try {
            exporter.start()
            val packets = (1..6).map { requireNotNull(sent.poll(3, TimeUnit.SECONDS)) }
            assertEquals(listOf(false, true, true, true, true, true), packets.map { it.routeActive })
            assertEquals(packets[1].maneuverEventId, packets.last().maneuverEventId)
        } finally { exporter.stop() }
    }

    @Test
    fun `replacement UDP waits for stopped HTTP in flight then starts inactive`() {
        val session = NavAssistV2Session()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val replacementStarted = CountDownLatch(1)
        val sent = LinkedBlockingQueue<Pair<String, NavAssistV2Snapshot>>()
        val oldCalls = AtomicInteger()
        val newExecutor = Executors.newSingleThreadExecutor()
        val newDispatcher = object : kotlinx.coroutines.CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
                newExecutor.execute { replacementStarted.countDown(); block.run() }
            }
        }
        val old = HttpNavAssistV2Exporter(
            config = NavAssistV2ExportConfig(baseUrl = ""), navigationSession = session,
            stateProvider = { activeState() }, identity = identity,
            endpointDiscovery = NavAssistV2EndpointDiscovery { NavAssistV2DiscoveryResult.Found("192.168.53.232", deviceId) },
            pinnedDeviceProvider = { null }, requireOwnerHandshake = true, ownerSettleMs = 0L,
            httpClient = object : NavAssistV2HttpClient {
                override fun post(endpoint: HttpUrl, body: String, appKeyId: String, signature: String) {
                    sent.put("old" to Gson().fromJson(body, NavAssistV2Snapshot::class.java))
                    if (oldCalls.incrementAndGet() == 2) { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)) }
                }
                override fun close() = Unit
            },
        )
        val replacement = HttpNavAssistV2Exporter(
            config = NavAssistV2ExportConfig(baseUrl = ""), navigationSession = session,
            stateProvider = { activeState() }, identity = identity,
            endpointDiscovery = NavAssistV2EndpointDiscovery { NavAssistV2DiscoveryResult.NotFound },
            pinnedDeviceProvider = { null }, requireOwnerHandshake = true, ownerSettleMs = 0L, useUnauthenticatedUdp = true,
            publisherDispatcher = newDispatcher,
            udpClient = NavAssistV3UdpClient { body, _, _ ->
                sent.put("new" to Gson().fromJson(String(body, Charsets.UTF_8), NavAssistV2Snapshot::class.java))
                NavAssistV3UdpAck("192.168.53.232", null)
            },
        )
        try {
            old.start()
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val initial = listOf(requireNotNull(sent.poll()), requireNotNull(sent.poll()))
            old.stop()
            replacement.start()
            assertTrue(replacementStarted.await(2, TimeUnit.SECONDS))
            assertEquals(null, sent.poll(100, TimeUnit.MILLISECONDS))
            release.countDown()
            val packets = initial + (1..2).map { requireNotNull(sent.poll(2, TimeUnit.SECONDS)) }
            assertEquals(listOf("old", "old", "new", "new"), packets.map { it.first })
            assertEquals(listOf(false, true, false, true), packets.map { it.second.routeActive })
            assertEquals(listOf(1L, 2L, 3L, 4L), packets.map { it.second.sequence })
            assertEquals(packets[1].second.maneuverEventId, packets[3].second.maneuverEventId)
            assertTrue(packets[3].second.maneuverEventId > 0L)
        } finally {
            release.countDown(); old.stop(); replacement.stop(); newExecutor.shutdownNow()
        }
    }

    @Test
    fun `late UDP success timeout and failure cannot change stopped state`() {
        for (outcome in 0..2) {
            Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
                val entered = CountDownLatch(1)
                val release = CountDownLatch(1)
                val calls = AtomicInteger()
                val exporter = lifecycleExporter(dispatcher, useUdp = true, udpClient = NavAssistV3UdpClient { _, _, _ ->
                    calls.incrementAndGet()
                    entered.countDown()
                    check(release.await(2, TimeUnit.SECONDS))
                    when (outcome) {
                        0 -> NavAssistV3UdpAck("192.168.53.232", OemVehicleLaneState(permissionValid = true, leftAllowed = true))
                        1 -> null
                        else -> error("late UDP failure")
                    }
                })
                try {
                    exporter.start()
                    assertTrue(entered.await(2, TimeUnit.SECONDS))
                    exporter.stop()
                    val stopped = observableState(exporter)
                    release.countDown()
                    drain(dispatcher)
                    assertEquals("outcome=$outcome", stopped, observableState(exporter))
                    assertEquals(1, calls.get())
                } finally {
                    release.countDown()
                    exporter.stop()
                }
            }
        }
    }

    @Test
    fun `late HTTP success and failure cannot change stopped state`() {
        for (fail in listOf(false, true)) {
            Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
                val entered = CountDownLatch(1)
                val release = CountDownLatch(1)
                val exporter = lifecycleExporter(dispatcher, httpClient = object : NavAssistV2HttpClient {
                    override fun post(endpoint: HttpUrl, body: String, appKeyId: String, signature: String) {
                        entered.countDown()
                        check(release.await(2, TimeUnit.SECONDS))
                        if (fail) error("late HTTP failure")
                    }
                    override fun close() = Unit
                })
                try {
                    exporter.start()
                    assertTrue(entered.await(2, TimeUnit.SECONDS))
                    exporter.stop()
                    val stopped = observableState(exporter)
                    release.countDown()
                    drain(dispatcher)
                    assertEquals("fail=$fail", stopped, observableState(exporter))
                } finally {
                    release.countDown()
                    exporter.stop()
                }
            }
        }
    }

    @Test
    fun `late discovery outcomes cannot republish endpoint or errors`() {
        for (result in listOf(
            NavAssistV2DiscoveryResult.Found("192.168.53.232", deviceId),
            NavAssistV2DiscoveryResult.NotFound,
            NavAssistV2DiscoveryResult.MultipleAuthenticatedHosts,
            NavAssistV2DiscoveryResult.Failed("late discovery failure"),
        )) {
            Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
                val entered = CountDownLatch(1)
                val release = CountDownLatch(1)
                val exporter = lifecycleExporter(dispatcher, discovery = NavAssistV2EndpointDiscovery {
                    entered.countDown()
                    check(release.await(2, TimeUnit.SECONDS))
                    result
                })
                try {
                    exporter.start()
                    assertTrue(entered.await(2, TimeUnit.SECONDS))
                    exporter.stop()
                    val stopped = observableState(exporter)
                    release.countDown()
                    drain(dispatcher)
                    assertEquals(result.toString(), stopped, observableState(exporter))
                } finally {
                    release.countDown()
                    exporter.stop()
                }
            }
        }
    }

    @Test
    fun `stop during state acquisition prevents a subsequent HTTP or UDP send`() {
        for (udp in listOf(false, true)) {
            Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
                val entered = CountDownLatch(1)
                val release = CountDownLatch(1)
                val sends = AtomicInteger()
                val exporter = lifecycleExporter(
                    dispatcher, useUdp = udp,
                    stateProvider = {
                        entered.countDown()
                        check(release.await(2, TimeUnit.SECONDS))
                        activeState()
                    },
                    udpClient = NavAssistV3UdpClient { _, _, _ -> sends.incrementAndGet(); null },
                    httpClient = object : NavAssistV2HttpClient {
                        override fun post(endpoint: HttpUrl, body: String, appKeyId: String, signature: String) { sends.incrementAndGet() }
                        override fun close() = Unit
                    },
                )
                try {
                    exporter.start()
                    assertTrue(entered.await(2, TimeUnit.SECONDS))
                    exporter.stop()
                    release.countDown()
                    drain(dispatcher)
                    assertEquals("udp=$udp", 0, sends.get())
                    assertEquals(ExportConnectionState.STOPPED, exporter.connectionState.value)
                } finally {
                    release.countDown()
                    exporter.stop()
                }
            }
        }
    }

    @Test
    fun `stopped instance cannot restart and repeated stop closes client only once`() {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
            val closes = AtomicInteger()
            val exporter = lifecycleExporter(dispatcher, httpClient = object : NavAssistV2HttpClient {
                override fun post(endpoint: HttpUrl, body: String, appKeyId: String, signature: String) = error("must not send")
                override fun close() { closes.incrementAndGet() }
            })
            exporter.stop()
            val stopped = observableState(exporter)
            exporter.start()
            drain(dispatcher)
            assertEquals(stopped, observableState(exporter))
            exporter.stop()
            assertEquals(1, closes.get())
        }
    }

    private fun lifecycleExporter(
        dispatcher: ExecutorCoroutineDispatcher,
        useUdp: Boolean = false,
        stateProvider: () -> NavigationState? = { activeState() },
        discovery: NavAssistV2EndpointDiscovery = NavAssistV2EndpointDiscovery { NavAssistV2DiscoveryResult.Found("192.168.53.232", deviceId) },
        udpClient: NavAssistV3UdpClient = NavAssistV3UdpClient { _, _, _ -> error("unexpected UDP") },
        httpClient: NavAssistV2HttpClient = object : NavAssistV2HttpClient {
            override fun post(endpoint: HttpUrl, body: String, appKeyId: String, signature: String) = error("unexpected HTTP")
            override fun close() = Unit
        },
    ) = HttpNavAssistV2Exporter(
        config = NavAssistV2ExportConfig(baseUrl = ""),
        stateProvider = stateProvider,
        identity = identity,
        endpointDiscovery = discovery,
        pinnedDeviceProvider = { null },
        httpClient = httpClient,
        useUnauthenticatedUdp = useUdp,
        udpClient = udpClient,
        publisherDispatcher = dispatcher,
    )

    private fun observableState(exporter: HttpNavAssistV2Exporter) = listOf(
        exporter.connectionState.value, exporter.status.value, exporter.resolvedEndpoint.value,
        exporter.lastError.value, exporter.oemVehicleLaneState.value,
    )

    private fun drain(dispatcher: ExecutorCoroutineDispatcher) {
        val completed = CountDownLatch(1)
        dispatcher.executor.execute { completed.countDown() }
        assertTrue("publisher did not finish its current callback", completed.await(2, TimeUnit.SECONDS))
    }

    @Test
    fun `rebuilding exporter retains active event identity and continues sequence`() {
        val session = NavAssistV2Session(validForMs = 500L)
        val first = captureSnapshot(session)
        val rebuilt = captureSnapshot(session)

        assertTrue(first.routeActive)
        assertTrue(first.maneuverEventId > 0L)
        assertEquals(first.sessionId, rebuilt.sessionId)
        assertEquals(first.routeRevision, rebuilt.routeRevision)
        assertEquals(first.maneuverEventId, rebuilt.maneuverEventId)
        assertTrue(rebuilt.sequence > first.sequence)
        assertEquals(500L, rebuilt.validForMs)
    }

    @Test
    fun `HTTP and UDP exporters borrow the same session without restarting sequence`() {
        val session = NavAssistV2Session(validForMs = 500L)
        val udp = captureSnapshot(session)
        val http = captureSnapshot(session, useUdp = false)
        val udpAgain = captureSnapshot(session)

        for (snapshot in listOf(http, udpAgain)) {
            assertEquals(udp.sessionId, snapshot.sessionId)
            assertEquals(udp.maneuverEventId, snapshot.maneuverEventId)
            assertTrue(snapshot.routeActive)
        }
        assertTrue(http.sequence > udp.sequence)
        assertTrue(udpAgain.sequence > http.sequence)
    }

    @Test
    fun `failed UDP send consumes sequence without changing event on retry`() {
        val session = NavAssistV2Session(validForMs = 500L)
        val snapshots = LinkedBlockingQueue<NavAssistV2Snapshot>()
        val attempts = AtomicInteger()
        val exporter = sessionExporter(session, udpClient = NavAssistV3UdpClient { body, _, _ ->
            snapshots.put(Gson().fromJson(body.decodeToString(), NavAssistV2Snapshot::class.java))
            if (attempts.incrementAndGet() == 1) error("network unavailable")
            NavAssistV3UdpAck("192.168.53.233", null)
        })
        try {
            exporter.start()
            val failed = snapshots.poll(2, TimeUnit.SECONDS) ?: error("missing failed attempt")
            val retried = snapshots.poll(2, TimeUnit.SECONDS) ?: error("missing retry")
            await { exporter.status.value == NavAssistV2ConnectionStatus.ONLINE }
            assertEquals(failed.sessionId, retried.sessionId)
            assertEquals(failed.maneuverEventId, retried.maneuverEventId)
            assertTrue(retried.sequence > failed.sequence)
        } finally {
            exporter.stop()
        }
    }

    @Test
    fun `inactive snapshot keeps session while event remains zero across rebuild`() {
        val session = NavAssistV2Session(validForMs = 500L)
        val active = captureSnapshot(session)
        val inactive = captureSnapshot(session, state = activeState().copy(routeRecalculating = true))
        val resumed = captureSnapshot(session)

        assertEquals(active.sessionId, inactive.sessionId)
        assertFalse(inactive.routeActive)
        assertEquals(0L, inactive.maneuverEventId)
        assertEquals(active.maneuverEventId, resumed.maneuverEventId)
        assertTrue(active.sequence < inactive.sequence && inactive.sequence < resumed.sequence)
        // This checks App identity only; it cannot prove the C3 cancellation latch survives event=0.
    }

    @Test
    fun `new service session starts idle without inheriting the previous event`() {
        val old = captureSnapshot(NavAssistV2Session(validForMs = 500L))
        val fresh = captureSnapshot(NavAssistV2Session(validForMs = 500L), state = NavigationState())

        assertNotEquals(old.sessionId, fresh.sessionId)
        assertEquals(1L, fresh.sequence)
        assertFalse(fresh.routeActive)
        assertEquals(0L, fresh.maneuverEventId)
    }

    private fun captureSnapshot(
        session: NavAssistV2Session,
        state: NavigationState = activeState(),
        useUdp: Boolean = true,
    ): NavAssistV2Snapshot {
        val snapshots = LinkedBlockingQueue<NavAssistV2Snapshot>()
        fun record(body: String) {
            snapshots.put(Gson().fromJson(body, NavAssistV2Snapshot::class.java))
        }
        val exporter = sessionExporter(
            session, state, useUdp,
            udpClient = NavAssistV3UdpClient { body, _, _ ->
                check(useUdp)
                record(body.decodeToString())
                NavAssistV3UdpAck("192.168.53.232", null)
            },
            httpClient = object : NavAssistV2HttpClient {
                override fun post(endpoint: HttpUrl, body: String, appKeyId: String, signature: String) {
                    check(!useUdp)
                    record(body)
                }
                override fun close() = Unit
            },
        )
        return try {
            exporter.start()
            snapshots.poll(2, TimeUnit.SECONDS) ?: error("missing snapshot")
        } finally {
            exporter.stop()
        }
    }

    private fun sessionExporter(
        session: NavAssistV2Session,
        state: NavigationState = activeState(),
        useUdp: Boolean = true,
        udpClient: NavAssistV3UdpClient,
        httpClient: NavAssistV2HttpClient = object : NavAssistV2HttpClient {
            override fun post(endpoint: HttpUrl, body: String, appKeyId: String, signature: String) = error("unexpected HTTP")
            override fun close() = Unit
        },
    ) = HttpNavAssistV2Exporter(
        config = NavAssistV2ExportConfig(baseUrl = "", validForMs = 500L),
        stateProvider = { state },
        identity = identity,
        endpointDiscovery = NavAssistV2EndpointDiscovery { NavAssistV2DiscoveryResult.Found("192.168.53.232", deviceId) },
        pinnedDeviceProvider = { null },
        httpClient = httpClient,
        useUnauthenticatedUdp = useUdp,
        udpClient = udpClient,
        navigationSession = session,
    )

    private fun activeState() = NavigationState(
        navAssistControlAllowed = true,
        navigationMode = NavigationMode.REALTIME,
        routePlanned = true,
        routeMatched = true,
        latitude = 31.2304,
        longitude = 121.4737,
        accuracy = 2f,
        bearing = 90f,
        speedKph = 30f,
        locationObservedAtMs = 1_000L,
        guidanceObservedAtMs = 1_000L,
        guidanceStepIndex = 2,
        routeRevision = 7L,
        maneuver = NavigationManeuver.TURN_RIGHT,
    )

    @Test
    fun `runtime UDP mode broadcasts canonical snapshots without discovery or credentials`() {
        val sent = CountDownLatch(1)
        val discoveryCalls = AtomicInteger()
        val exporter = HttpNavAssistV2Exporter(
            config = NavAssistV2ExportConfig(baseUrl = ""),
            stateProvider = { NavigationState() },
            identity = identity,
            endpointDiscovery = NavAssistV2EndpointDiscovery {
                discoveryCalls.incrementAndGet()
                NavAssistV2DiscoveryResult.NotFound
            },
            pinnedDeviceProvider = { null },
            httpClient = object : NavAssistV2HttpClient {
                override fun post(endpoint: HttpUrl, body: String, appKeyId: String, signature: String) =
                    error("UDP mode must not POST")
                override fun close() = Unit
            },
            useUnauthenticatedUdp = true,
            udpClient = NavAssistV3UdpClient { body, sessionId, sequence ->
                assertTrue(body.decodeToString().contains("\"sessionId\":\"$sessionId\""))
                assertTrue(sequence > 0)
                sent.countDown()
                NavAssistV3UdpAck(
                    "192.168.102.187",
                    OemVehicleLaneState(
                        position = OemLanePosition.MIDDLE,
                        positionValid = true,
                        leftAllowed = true,
                        permissionValid = true,
                    ),
                )
            },
        )

        try {
            exporter.start()
            assertTrue(sent.await(2, TimeUnit.SECONDS))
            await { exporter.status.value == NavAssistV2ConnectionStatus.ONLINE }
            assertEquals("udp://192.168.102.187:4213", exporter.resolvedEndpoint.value)
            assertEquals(OemLanePosition.MIDDLE, exporter.oemVehicleLaneState.value.position)
            assertTrue(exporter.oemVehicleLaneState.value.leftAllowed)
            assertEquals(0, discoveryCalls.get())
        } finally {
            exporter.stop()
        }
    }

    @Test
    fun `discovered endpoint becomes online only after a successful POST`() {
        val postEntered = CountDownLatch(1)
        val allowPost = CountDownLatch(1)
        val client = object : NavAssistV2HttpClient {
            override fun post(endpoint: HttpUrl, body: String, appKeyId: String, signature: String) {
                assertEquals(identity.keyId, appKeyId)
                postEntered.countDown()
                assertTrue(allowPost.await(2, TimeUnit.SECONDS))
            }

            override fun close() = Unit
        }
        val exporter = exporter(
            discovery = NavAssistV2EndpointDiscovery { NavAssistV2DiscoveryResult.Found("192.168.53.232", deviceId) },
            client = client,
        )

        try {
            exporter.start()
            assertTrue(postEntered.await(2, TimeUnit.SECONDS))
            assertEquals(NavAssistV2ConnectionStatus.DISCOVERED, exporter.status.value)
            assertEquals(ExportConnectionState.STARTING, exporter.connectionState.value)
            allowPost.countDown()
            await { exporter.status.value == NavAssistV2ConnectionStatus.ONLINE }
            assertEquals(ExportConnectionState.CONNECTED, exporter.connectionState.value)
        } finally {
            allowPost.countDown()
            exporter.stop()
        }
    }

    @Test
    fun `one HTTP failure retries the authenticated endpoint before rediscovery`() {
        val discoveries = AtomicInteger()
        val posts = AtomicInteger()
        val online = CountDownLatch(1)
        val exporter = exporter(
            discovery = NavAssistV2EndpointDiscovery {
                discoveries.incrementAndGet()
                NavAssistV2DiscoveryResult.Found("192.168.53.232", deviceId)
            },
            client = object : NavAssistV2HttpClient {
                override fun post(endpoint: HttpUrl, body: String, appKeyId: String, signature: String) {
                    if (posts.incrementAndGet() == 1) error("first POST fails")
                    online.countDown()
                }

                override fun close() = Unit
            },
        )

        try {
            exporter.start()
            assertTrue(online.await(3, TimeUnit.SECONDS))
            await { exporter.status.value == NavAssistV2ConnectionStatus.ONLINE }
            assertEquals(1, discoveries.get())
            assertTrue(posts.get() >= 2)
            assertEquals("http://192.168.53.232:7766/v3/snapshot", exporter.resolvedEndpoint.value)
        } finally {
            exporter.stop()
        }
    }

    @Test
    fun `repeated HTTP failures rediscover after a network change`() {
        val discoveries = AtomicInteger()
        val posts = AtomicInteger()
        val online = CountDownLatch(1)
        val exporter = exporter(
            discovery = NavAssistV2EndpointDiscovery {
                val suffix = if (discoveries.incrementAndGet() == 1) 232 else 233
                NavAssistV2DiscoveryResult.Found("192.168.53.$suffix", deviceId)
            },
            client = object : NavAssistV2HttpClient {
                override fun post(endpoint: HttpUrl, body: String, appKeyId: String, signature: String) {
                    posts.incrementAndGet()
                    if (endpoint.host.endsWith(".232")) error("old network")
                    online.countDown()
                }

                override fun close() = Unit
            },
        )

        try {
            exporter.start()
            assertTrue(online.await(3, TimeUnit.SECONDS))
            await { exporter.status.value == NavAssistV2ConnectionStatus.ONLINE }
            assertTrue(discoveries.get() >= 2)
            assertTrue(posts.get() >= 3)
            assertEquals("http://192.168.53.233:7766/v3/snapshot", exporter.resolvedEndpoint.value)
        } finally {
            exporter.stop()
        }
    }

    @Test
    fun `navigation start can force rediscovery of a previously healthy endpoint`() {
        val discoveries = AtomicInteger()
        val posts = AtomicInteger()
        val firstOnline = CountDownLatch(1)
        val secondOnline = CountDownLatch(1)
        val exporter = exporter(
            discovery = NavAssistV2EndpointDiscovery {
                val count = discoveries.incrementAndGet()
                NavAssistV2DiscoveryResult.Found("192.168.53.${231 + count}", deviceId)
            },
            client = object : NavAssistV2HttpClient {
                override fun post(endpoint: HttpUrl, body: String, appKeyId: String, signature: String) {
                    when (posts.incrementAndGet()) {
                        1 -> firstOnline.countDown()
                        2 -> secondOnline.countDown()
                    }
                }

                override fun close() = Unit
            },
        )

        try {
            exporter.start()
            assertTrue(firstOnline.await(2, TimeUnit.SECONDS))
            exporter.requestRediscovery()
            assertTrue(secondOnline.await(2, TimeUnit.SECONDS))
            await { discoveries.get() >= 2 }
            assertEquals("http://192.168.53.233:7766/v3/snapshot", exporter.resolvedEndpoint.value)
        } finally {
            exporter.stop()
        }
    }

    @Test
    fun `multiple authenticated devices never select an endpoint`() {
        val discoveryCalled = CountDownLatch(1)
        val exporter = exporter(
            discovery = NavAssistV2EndpointDiscovery {
                discoveryCalled.countDown()
                NavAssistV2DiscoveryResult.MultipleAuthenticatedHosts
            },
            client = object : NavAssistV2HttpClient {
                override fun post(endpoint: HttpUrl, body: String, appKeyId: String, signature: String) = error("must not POST")
                override fun close() = Unit
            },
        )

        try {
            exporter.start()
            assertTrue(discoveryCalled.await(2, TimeUnit.SECONDS))
            await { exporter.status.value == NavAssistV2ConnectionStatus.MULTIPLE_DEVICES }
            assertEquals(null, exporter.resolvedEndpoint.value)
            assertEquals(ExportConnectionState.ERROR, exporter.connectionState.value)
        } finally {
            exporter.stop()
        }
    }

    @Test
    fun `stopping during discovery prevents a stale pairing tail POST`() {
        val discoveryEntered = CountDownLatch(1)
        val releaseDiscovery = CountDownLatch(1)
        val postCalled = CountDownLatch(1)
        val exporter = exporter(
            discovery = NavAssistV2EndpointDiscovery {
                discoveryEntered.countDown()
                assertTrue(releaseDiscovery.await(2, TimeUnit.SECONDS))
                NavAssistV2DiscoveryResult.Found("192.168.53.232", deviceId)
            },
            client = object : NavAssistV2HttpClient {
                override fun post(endpoint: HttpUrl, body: String, appKeyId: String, signature: String) {
                    postCalled.countDown()
                }

                override fun close() = Unit
            },
        )

        exporter.start()
        assertTrue(discoveryEntered.await(2, TimeUnit.SECONDS))
        exporter.stop()
        releaseDiscovery.countDown()

        assertTrue("stopped exporter must not POST", !postCalled.await(500, TimeUnit.MILLISECONDS))
    }

    private fun exporter(
        discovery: NavAssistV2EndpointDiscovery,
        client: NavAssistV2HttpClient,
    ) = HttpNavAssistV2Exporter(
        config = NavAssistV2ExportConfig(baseUrl = ""),
        stateProvider = { NavigationState() },
        identity = identity,
        endpointDiscovery = discovery,
        pinnedDeviceProvider = { null },
        httpClient = client,
        discoveryRetryMs = 1L,
    )

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (!condition()) {
            if (System.nanoTime() >= deadline) error("condition was not met")
            Thread.yield()
        }
    }
}

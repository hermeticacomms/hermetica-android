package com.hermeticacomms.sdk

import android.Manifest
import android.app.Application
import android.content.Intent
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class HermeticaTest {
    private lateinit var server: MockWebServer
    private val replies = ConcurrentLinkedQueue<MockResponse>()
    private val app: Application get() = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = replies.poll() ?: MockResponse().setBody("{}")
        }
        server.start()
        init()
    }

    @After
    fun tearDown() {
        Hermetica.shutdown()
        server.shutdown()
    }

    // Batches of 3, no backoff, and network work on the test thread so every call is synchronous.
    private fun init() = Hermetica.initialize(app, "pk_test", server.url("/").toString(), Config(batchSize = 3, maxRetries = 3, retryBaseMs = 0), Executor { it.run() })

    private fun next(): RecordedRequest = server.takeRequest(5, TimeUnit.SECONDS)!!
    private fun RecordedRequest.json() = JSONObject(body.readUtf8())
    private fun reply(code: Int, body: String = "{}") = replies.add(MockResponse().setResponseCode(code).setBody(body))
    private val outbox get() = Hermetica.core!!.outbox

    @Test
    fun tracksInBatches() {
        Hermetica.track("a")
        Hermetica.track("b", mapOf("x" to 1))
        assertEquals(0, server.requestCount)
        Hermetica.track("c", value = 1999, currency = "USD")
        val r = next()
        assertEquals("/ingest/v1/batch", r.path)
        assertEquals("Bearer pk_test", r.getHeader("Authorization"))
        assertNotNull(r.getHeader("Idempotency-Key"))
        val events = r.json().getJSONArray("events")
        assertEquals(listOf("a", "b", "c"), List(events.length()) { events.getJSONObject(it).getString("name") })
        assertEquals(1, events.getJSONObject(1).getJSONObject("properties").getInt("x"))
        assertTrue(events.getJSONObject(0).getJSONObject("properties").getString("hm_sdk").startsWith("android-"))
        val c = events.getJSONObject(2)
        assertEquals(Hermetica.anonymousId(), c.getString("anonymousId"))
        assertEquals(1999L, c.getLong("value"))
        assertEquals("USD", c.getString("currency"))
        assertNotNull(c.getString("dedupeKey"))
        assertEquals(0, outbox.size())
    }

    @Test
    fun identifySendsHeldAttributes() {
        Hermetica.setProfileAttributes(mapOf("plan" to "pro"))
        assertEquals(0, server.requestCount) // no identifier yet: held for identify
        Hermetica.identify("u1", email = "a@b.co", traits = mapOf("first_name" to "Ada"))
        val r = next()
        assertEquals("/ingest/v1/identify", r.path)
        assertNotNull(r.getHeader("Idempotency-Key"))
        val b = r.json()
        assertEquals(Hermetica.anonymousId(), b.getString("anonymousId"))
        assertEquals("u1", b.getJSONObject("identifiers").getString("externalId"))
        assertEquals("a@b.co", b.getJSONObject("identifiers").getString("email"))
        assertEquals("pro", b.getJSONObject("traits").getString("plan"))
        assertEquals("Ada", b.getJSONObject("traits").getString("first_name"))
    }

    @Test
    fun retriesWithTheSameIdempotencyKey() {
        reply(503); reply(429)
        repeat(3) { Hermetica.track("e$it") }
        val keys = List(3) { next().getHeader("Idempotency-Key") }
        assertEquals(1, keys.toSet().size)
        assertEquals(0, outbox.size())
    }

    @Test
    fun keepsTheQueueOfflineAndAcrossRestarts() {
        repeat(3) { reply(503) }
        repeat(3) { Hermetica.track("e$it") }
        assertEquals(3, server.requestCount)
        assertEquals(3, outbox.size())
        Hermetica.shutdown()
        init() // a new process: the persisted queue is sent at start
        assertEquals(4, server.requestCount)
        assertEquals(0, outbox.size())
    }

    @Test
    fun dropsWhatTheServerRejects() {
        reply(400)
        repeat(3) { Hermetica.track("e$it") }
        assertEquals(1, server.requestCount)
        assertEquals(0, outbox.size())
    }

    @Test
    fun honoursTrackingOptOut() {
        Hermetica.track("before1")
        Hermetica.track("before2")
        Hermetica.setTrackingOptOut(true)
        repeat(5) { Hermetica.track("x") }
        Hermetica.identify("u1")
        Hermetica.flush()
        var messages: List<InAppMessage>? = null
        Hermetica.fetchInAppMessages(InAppTrigger.AppOpen) { messages = it }
        assertEquals(emptyList<InAppMessage>(), messages)
        assertEquals(0, server.requestCount)

        Hermetica.shutdown()
        init()
        assertTrue(Hermetica.isTrackingOptedOut()) // persisted
        assertEquals(0, server.requestCount)

        Hermetica.setTrackingOptOut(false)
        listOf("c", "d", "e").forEach { Hermetica.track(it) }
        val events = next().json().getJSONArray("events")
        assertEquals(listOf("c", "d", "e"), List(events.length()) { events.getJSONObject(it).getString("name") }) // queued events were dropped
    }

    @Test
    fun pushTokenBody() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        Hermetica.identify(null, email = "a@b.co")
        next() // identify
        Hermetica.setPushToken("tok")
        val r = next()
        assertEquals("/client/push-tokens?company_id=pk_test", r.path)
        assertNotNull(r.getHeader("Idempotency-Key"))
        val data = r.json().getJSONObject("data")
        assertEquals("push-token", data.getString("type"))
        val a = data.getJSONObject("attributes")
        assertEquals("tok", a.getString("token"))
        assertEquals("android", a.getString("platform"))
        assertEquals("fcm", a.getString("vendor"))
        assertEquals("AUTHORIZED", a.getString("enablement_status"))
        assertEquals("AVAILABLE", a.getString("background"))
        val meta = a.getJSONObject("device_metadata")
        assertEquals(Hermetica.anonymousId(), meta.getString("device_id"))
        assertEquals("android", meta.getString("os_name"))
        assertEquals(app.packageName, meta.getString("app_id"))
        assertTrue(meta.getString("environment") in setOf("sandbox", "production"))
        val who = a.getJSONObject("profile").getJSONObject("data")
        assertEquals("profile", who.getString("type"))
        assertEquals("a@b.co", who.getJSONObject("attributes").getString("email"))
        assertEquals(Hermetica.anonymousId(), who.getJSONObject("attributes").getString("anonymous_id"))

        Hermetica.unregisterPushToken()
        val u = next()
        assertEquals("/client/push-token-unregister?company_id=pk_test", u.path)
        val ud = u.json().getJSONObject("data")
        assertEquals("push-token-unregister", ud.getString("type"))
        assertEquals("tok", ud.getJSONObject("attributes").getString("token"))
    }

    @Test
    fun deniedNotificationsAreReported() {
        assertEquals("DENIED", Hermetica.pushPermissionStatus()) // Android 14 without POST_NOTIFICATIONS
    }

    @Test
    fun pushOpenTracksOnceAndReturnsTheDeepLink() {
        val intent = Intent().putExtra("hermetica_message_id", "m1").putExtra("deep_link", "shop://cart")
        assertEquals("shop://cart", Hermetica.handlePushOpened(intent))
        Hermetica.handlePushOpened(intent) // same intent again (activity recreated)
        assertNull(Hermetica.handlePushOpened(Intent().putExtra("other", "1")))
        assertEquals(1, outbox.size())
        Hermetica.flush()
        val ev = next().json().getJSONArray("events").getJSONObject(0)
        assertEquals("\$opened_push", ev.getString("name"))
        assertEquals("m1", ev.getJSONObject("properties").getString("hermetica_message_id"))
    }

    @Test
    fun inAppFetchAndImpression() {
        reply(200, """{"items":[{"id":"0190a0a0-0000-7000-8000-000000000001","variant":"b","layout":"modal","queued":false,"priority":5,
            "content":{"blocks":[{"type":"heading","text":"Hi"},{"type":"buttons","buttons":[{"label":"Shop","action":"deep_link","value":"shop://sale"}]}]},
            "style":{"background":"#fff","textColor":"#111111","accentColor":"#0055ff","radius":12,"dismissible":true}}]}""")
        var messages: List<InAppMessage>? = null
        Hermetica.fetchInAppMessages(InAppTrigger.Screen("home")) { messages = it }
        val url = next().requestUrl!!
        assertEquals("/client/in-app-messages", url.encodedPath)
        assertEquals("pk_test", url.queryParameter("company_id"))
        assertEquals("android", url.queryParameter("platform"))
        assertEquals("screen_view", url.queryParameter("trigger"))
        assertEquals("home", url.queryParameter("name"))
        assertEquals(Hermetica.anonymousId(), url.queryParameter("anonymous_id"))
        val session = url.queryParameter("session_id")!!
        val m = messages!!.single()
        assertEquals("b", m.variant)
        assertEquals("Hi", m.blocks[0].text)
        assertEquals(InAppButton("Shop", "deep_link", "shop://sale", null), m.blocks[1].buttons.single())

        Hermetica.reportInAppEvent(m, "click", "Shop")
        val r = next()
        assertEquals("/client/in-app-events?company_id=pk_test", r.path)
        val b = r.json()
        val ev = b.getJSONArray("events").getJSONObject(0)
        assertEquals(m.id, ev.getString("messageId"))
        assertEquals("b", ev.getString("variant"))
        assertEquals("click", ev.getString("type"))
        assertEquals("Shop", ev.getString("button"))
        assertEquals("android", ev.getString("platform"))
        assertEquals(session, ev.getString("sessionId"))
        assertEquals(Hermetica.anonymousId(), b.getJSONObject("profile").getString("anonymousId"))
    }

    @Test
    fun geofencesAndTransitions() {
        reply(200, """{"data":[{"type":"geofence","id":"g1","attributes":{"name":"Store","latitude":1,"longitude":2,"radius":150}}]}""")
        var fences: List<Geofence>? = null
        Hermetica.fetchGeofences { fences = it }
        assertEquals("/client/geofences?company_id=pk_test", next().path)
        assertEquals(listOf(Geofence("g1", "Store", 1.0, 2.0, 150.0)), fences)

        var done = false
        Hermetica.onGeofenceTransitions(listOf("enter" to "g1", "exit" to "g1")) { done = true }
        assertTrue(done)
        val events = next().json().getJSONArray("events")
        assertEquals("\$geofence_enter", events.getJSONObject(0).getString("name"))
        assertEquals("g1", events.getJSONObject(0).getJSONObject("properties").getString("geofence_id"))
        assertEquals("\$geofence_exit", events.getJSONObject(1).getString("name"))
    }
}

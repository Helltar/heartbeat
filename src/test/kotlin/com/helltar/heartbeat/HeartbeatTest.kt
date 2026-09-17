package com.helltar.heartbeat

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.function.Function
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

class HeartbeatTest {

    @TempDir
    lateinit var directory: Path

    private val file get() = directory.resolve("health")
    private val time = TestTimeSource()

    private fun heartbeat(onStall: () -> Unit = {}) =
        Heartbeat(file = file, staleAfter = 10.seconds, timeSource = time, onStall = onStall)

    // the file's content is a wall-clock stamp, so a refresh is observed by whether the file comes back
    private fun Heartbeat.refreshes(): Boolean {
        Files.deleteIfExists(file)
        refreshOnce()
        return Files.exists(file)
    }

    @Test
    fun `file is absent until the first beat`() {
        val heartbeat = heartbeat()

        assertFalse(heartbeat.refreshes())
        assertFalse(heartbeat.awaitFirstBeat(1.milliseconds))
    }

    @Test
    fun `file is refreshed while beats keep coming`() {
        val heartbeat = heartbeat()

        heartbeat.beat()

        assertTrue(heartbeat.awaitFirstBeat(1.milliseconds))
        assertTrue(heartbeat.refreshes())

        time += 9.seconds

        assertTrue(heartbeat.refreshes())
    }

    @Test
    fun `file goes stale once beats stop and recovers when they resume`() {
        val heartbeat = heartbeat()

        heartbeat.beat()
        time += 10.seconds

        assertFalse(heartbeat.refreshes())

        heartbeat.beat()

        assertTrue(heartbeat.refreshes())
    }

    @Test
    fun `stall callback runs once per stall`() {
        var stalls = 0
        val heartbeat = heartbeat(onStall = { stalls++ })

        heartbeat.beat()
        time += 10.seconds
        heartbeat.refreshOnce()
        heartbeat.refreshOnce()

        assertEquals(1, stalls)

        heartbeat.beat()
        heartbeat.refreshOnce()
        time += 10.seconds
        heartbeat.refreshOnce()

        assertEquals(2, stalls)
    }

    @Test
    fun `failing stall callback does not escape`() {
        val heartbeat = heartbeat(onStall = { error("boom") })

        heartbeat.beat()
        time += 10.seconds

        heartbeat.refreshOnce()
    }

    @Test
    fun `wrapped function beats on every call`() {
        val heartbeat = heartbeat()
        val wrapped = heartbeat.beatOn(Function<Int, Int> { it + 1 })

        assertFalse(heartbeat.refreshes())
        assertEquals(42, wrapped.apply(41))
        assertTrue(heartbeat.refreshes())
    }

    @Test
    fun `start removes the file a previous run left behind`() {
        Files.writeString(file, "stale")

        Heartbeat(file = file, interval = 1.seconds).start().use {
            assertFalse(Files.exists(file))
        }
    }

    @Test
    fun `started heartbeat writes the file after a beat`() {
        Heartbeat(file = file, interval = 10.milliseconds).start().use { heartbeat ->
            heartbeat.beat()

            val deadline = System.nanoTime() + 5.seconds.inWholeNanoseconds
            while (!Files.exists(file) && System.nanoTime() < deadline) Thread.sleep(10)

            assertTrue(Files.exists(file))
        }
    }

    @Test
    fun `cannot be started twice`() {
        heartbeat().start().use { heartbeat ->
            assertFailsWith<IllegalStateException> { heartbeat.start() }
        }
    }

    @Test
    fun `rejects non-positive durations`() {
        assertFailsWith<IllegalArgumentException> { Heartbeat(file = file, staleAfter = Duration.ZERO) }
        assertFailsWith<IllegalArgumentException> { Heartbeat(file = file, interval = Duration.ZERO) }
    }
}

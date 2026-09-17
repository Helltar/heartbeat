package com.helltar.heartbeat

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Function
import kotlin.concurrent.thread
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Liveness signal for a container healthcheck, fed by the loop it watches.
 *
 * The heartbeat file's modification time is the only thing the healthcheck reads, and it is
 * refreshed only while [beat] keeps being called. That makes visible what nothing outside the
 * process can see on its own: a loop running on an executor can stop while the JVM stays up, and
 * the container then stays `Up` forever.
 *
 * Freshness follows the loop rather than the outcome of each cycle, so call [beat] where a cycle
 * begins, not where it succeeds. A brief upstream outage then does not mark the service unhealthy
 * over something a restart cannot fix, while a loop that has stopped — or backs off so hard that it
 * cycles in name only — still does.
 *
 * The matching `Dockerfile` line compares the file's age against a threshold a few [interval]s
 * long:
 *
 * ```
 * HEALTHCHECK --interval=30s --timeout=5s --start-period=120s --retries=3 \
 *     CMD test $(( $(date +%s) - $(stat -c %Y /tmp/health 2>/dev/null || echo 0) )) -lt 90
 * ```
 *
 * @param file the file whose modification time the healthcheck reads.
 * @param staleAfter how long the loop may go without a [beat] before it counts as stalled. The
 * default fits a Telegram long-polling session: `getUpdates` holds for 50 seconds under a
 * 100-second read timeout, so even a request that hangs until it times out beats inside the window.
 * @param interval how often the file is refreshed while the loop is alive.
 * @param timeSource what staleness is measured against. Monotonic by default, so a wall clock
 * stepping under the process neither fakes a stall nor hides one.
 * @param onStall called once each time the loop goes from alive to stalled, on the heartbeat
 * thread, after the stall has been logged. Does nothing by default: the stall is reported through
 * the log and the healthcheck, and what happens next is the operator's call. Docker alone never
 * restarts an unhealthy container, so a service that wants one can exit here, or send an alert.
 */
public class Heartbeat(
    private val file: Path = Path.of(DEFAULT_FILE),
    private val staleAfter: Duration = DEFAULT_STALE_AFTER,
    private val interval: Duration = DEFAULT_INTERVAL,
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val onStall: () -> Unit = {},
) : AutoCloseable {

    init {
        require(staleAfter.isPositive()) { "stale timeout must be positive" }
        require(interval.isPositive()) { "refresh interval must be positive" }
    }

    // set from the watched loop's thread, read from the heartbeat thread
    private val lastBeat = AtomicReference<TimeMark?>(null)
    private val firstBeat = CountDownLatch(1)

    private val worker = AtomicReference<Thread?>(null)

    // only the heartbeat thread touches these, so each transition is logged once
    private var stallReported = false
    private var writeFailureReported = false

    /** Records that the watched loop has started another cycle. */
    public fun beat() {
        lastBeat.set(timeSource.markNow())
        firstBeat.countDown()
    }

    /**
     * Wraps [function] so that every call to it is a [beat].
     *
     * Made for hooks a loop calls once per cycle, such as the `getUpdates` generator of a
     * `telegrambots` long-polling session. The update consumer would not do there: the session
     * skips it on an empty batch, so a bot nobody writes to would look dead within minutes.
     */
    public fun <T, R> beatOn(function: Function<T, R>): Function<T, R> =
        Function { argument ->
            beat()
            function.apply(argument)
        }

    /** Waits for the watched loop to reach its first cycle, and reports whether it ever did. */
    public fun awaitFirstBeat(timeout: Duration): Boolean =
        firstBeat.await(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)

    /**
     * Starts refreshing the file from a daemon thread, which needs no scope and never keeps the
     * JVM up on its own.
     *
     * A file left behind by a previous run is removed first: `/tmp` survives a container restart,
     * and a service whose loop has not cycled yet must not pass the healthcheck on its
     * predecessor's word.
     */
    public fun start(): Heartbeat {
        val thread = thread(start = false, isDaemon = true, name = "heartbeat") { run() }

        check(worker.compareAndSet(null, thread)) { "heartbeat is already started" }

        runCatching { Files.deleteIfExists(file) }
            .onFailure { log.warn("Failed to remove the previous heartbeat file=[{}]", file, it) }

        thread.start()

        return this
    }

    /** Stops refreshing the file, which then goes stale on its own. */
    override fun close() {
        worker.get()?.interrupt()
    }

    private fun run() {
        try {
            // there is nothing to refresh before the first cycle, and waiting for it here puts the
            // file in place the moment the loop starts instead of up to an interval later
            firstBeat.await()

            while (true) {
                refreshOnce()
                Thread.sleep(interval.inWholeMilliseconds.coerceAtLeast(1))
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    internal fun refreshOnce() {
        // nothing until the first cycle: a service whose loop never started must not pass the
        // healthcheck merely because its process is up
        val since = lastBeat.get() ?: return

        if (since.elapsedNow() >= staleAfter) {
            reportStall()
            return
        }

        if (stallReported) {
            stallReported = false
            log.info("The watched loop is cycling again")
        }

        runCatching { Files.writeString(file, Instant.now().toString()) }
            .onSuccess { writeFailureReported = false }
            .onFailure { reportWriteFailure(it) }
    }

    private fun reportStall() {
        if (stallReported) return

        stallReported = true

        log.error(
            "No cycle of the watched loop in {}. The heartbeat file=[{}] is now stale, " +
                    "so the container healthcheck will fail.",
            staleAfter, file
        )

        runCatching(onStall)
            .onFailure { log.warn("The stall callback failed", it) }
    }

    private fun reportWriteFailure(cause: Throwable) {
        // a write cut short by close() is a shutdown, not a failure
        if (Thread.currentThread().isInterrupted) return

        if (writeFailureReported) return

        writeFailureReported = true

        log.warn("Failed to write the heartbeat file=[{}], so the healthcheck cannot see this service", file, cause)
    }

    public companion object {
        /** Where the healthcheck looks unless told otherwise. */
        public const val DEFAULT_FILE: String = "/tmp/health"

        /** The default for how long the loop may go without a [beat]. */
        public val DEFAULT_STALE_AFTER: Duration = 180.seconds

        /** The default for how often the file is refreshed. */
        public val DEFAULT_INTERVAL: Duration = 30.seconds

        private val log = LoggerFactory.getLogger(Heartbeat::class.java)
    }
}

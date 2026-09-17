# heartbeat

A file-based liveness signal for a container healthcheck, fed by the loop it watches.

A JVM service can stop doing its job while its process stays alive: a task on a scheduled executor
dies on an unchecked exception, the JVM carries on, and the container says `Up` forever. `Heartbeat`
keeps a file fresh only while the loop keeps cycling, and the image's `HEALTHCHECK` reads nothing but
that file's age — no port, no HTTP server, no `curl` in the image.

## Install

```kotlin
dependencies {
    implementation("com.helltar:heartbeat:0.1.0")
}
```

Kotlin/JVM, Java 11 or newer. The only dependency is `slf4j-api`, so the stall messages land in
whatever logging the service already has.

## Use

```kotlin
val heartbeat = Heartbeat().start()

// wherever a cycle of the loop begins
heartbeat.beat()
```

```dockerfile
HEALTHCHECK --interval=30s --timeout=5s --start-period=120s --retries=3 \
    CMD test $(( $(date +%s) - $(stat -c %Y /tmp/health 2>/dev/null || echo 0) )) -lt 90
```

Call `beat()` where a cycle *begins*, not where it succeeds. Freshness then follows the loop rather
than the upstream it talks to, so a brief outage does not mark the service unhealthy over something a
restart cannot fix.

The check needs a shell with `date` and `stat -c` in the image — any Debian, Ubuntu or Alpine base
has them, a distroless one does not. On Kubernetes the same command works as an `exec` liveness
probe.

### Options

| Parameter | Default | |
|---|---|---|
| `file` | `/tmp/health` | the file the healthcheck reads |
| `staleAfter` | 180 s | how long the loop may go without a `beat()` before it counts as stalled |
| `interval` | 30 s | how often the file is refreshed while the loop is alive |
| `onStall` | nothing | called once each time the loop goes from alive to stalled |

Size `staleAfter` to the longest cycle the loop can legitimately take, retries and timeouts included.
The age threshold in the `HEALTHCHECK` line (90 s above) is a few `interval`s, so one late refresh
does not fail the check.

### Reacting to a stall

By default a stall is only reported: one `ERROR` in the log, and a healthcheck that starts failing.
Docker on its own never restarts an unhealthy container, so what happens next is up to whoever
watches that status.

A service that would rather act on its own passes `onStall`:

```kotlin
// hand the restart to the container's restart policy
Heartbeat(onStall = { exitProcess(1) })

// or tell someone
Heartbeat(onStall = { alerts.send("polling stalled") })
```

### Telegram long polling

The defaults (`staleAfter` 180 s, refresh every 30 s) fit a
[TelegramBots](https://github.com/rubenlagus/TelegramBots) long-polling session: `getUpdates` holds
for 50 s under a 100 s read timeout. The hook belongs on the session's `getUpdates` generator, which
is called once per cycle. The update consumer would not do — the session skips it on an empty batch,
so a bot nobody writes to would look dead within minutes.

```kotlin
longPolling.registerBot(
    botToken,
    { TelegramUrl.DEFAULT_URL },
    heartbeat.beatOn(DefaultGetUpdatesGenerator()),
    consumer
)

// registration can fail quietly in some runners, so wait for the loop to actually start
if (!heartbeat.awaitFirstBeat(60.seconds)) exitProcess(1)
```

`beatOn` wraps any `java.util.function.Function`, so the library itself does not depend on TelegramBots.

## Behaviour

- Nothing is written before the first `beat()`: a service whose loop never started does not pass the
  healthcheck merely because its process is up.
- `start()` removes a file left by a previous run — `/tmp` survives a container restart.
- A stall and a recovery are each logged once, at `ERROR` and `INFO`.
- Staleness is measured on a monotonic clock, so wall-clock steps neither fake a stall nor hide one.
- The refresher is a daemon thread: it needs no coroutine scope and never keeps the JVM up.
- One `Heartbeat` watches one loop. Several loops beating into the same instance, or several
  instances writing the same file, would let a live loop vouch for a dead one — give each loop its
  own file and have the healthcheck test them all.

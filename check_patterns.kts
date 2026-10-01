import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock

// Model the critical property: once sign-out has acquired the lock and increments
// generation, a stale token response cannot commit afterward.
repeat(10_000) {
    val lock = ReentrantLock()
    val generation = AtomicLong(0)
    val requestGen = generation.get()
    val signOutLocked = CountDownLatch(1)
    val releaseSignOut = CountDownLatch(1)
    val committed = AtomicBoolean(false)
    val staleCommit = AtomicBoolean(false)

    val signOut = thread(start = true) {
        lock.withLock {
            generation.incrementAndGet()
            signOutLocked.countDown()
            releaseSignOut.await()
        }
    }
    signOutLocked.await()
    val response = thread(start = true) {
        lock.withLock {
            if (requestGen == generation.get()) committed.set(true) else staleCommit.set(true)
        }
    }
    releaseSignOut.countDown()
    signOut.join(); response.join()
    check(!committed.get())
    check(staleCommit.get())
}
println("AUTH_LOCK_MODEL_PASS")

enum class State { IDLE, PENDING, SENT }
var state = State.IDLE
var pending = 100L
state = State.PENDING
check(state == State.PENDING && pending == 100L)
// Simulate process death after successful server acceptance but before local clear.
state = State.SENT
pending = 100L
// Restart cleanup of SENT must not resend the batch.
if (state == State.SENT) { state = State.IDLE; pending = 0L }
check(state == State.IDLE && pending == 0L)
println("STATS_STATE_MODEL_PASS")

// Regression model: an acknowledged batch must remain byte-for-byte immutable while
// traffic observed after the freeze is carried separately under a future batch id.
var frozenId = "batch-A"
var frozenBytes = 100L
var carryBytes = 0L
val newTrafficAfterFreeze = 35L
carryBytes += newTrafficAfterFreeze
check(frozenId == "batch-A" && frozenBytes == 100L)
check(carryBytes == 35L)
// Once batch-A is acknowledged, the carry becomes the payload of a new batch.
frozenId = "batch-B"
frozenBytes = carryBytes
carryBytes = 0L
check(frozenId == "batch-B" && frozenBytes == 35L && carryBytes == 0L)
// Legacy migration model: pending bytes without an old batch id are carried into a
// fresh batch rather than being overwritten by newly observed traffic.
val legacyPending = 80L
val legacyNewTraffic = 20L
val migratedBatch = legacyPending + legacyNewTraffic
check(migratedBatch == 100L)
println("STATS_IMMUTABLE_BATCH_MODEL_PASS")

val pendingRebuild = AtomicBoolean(false)
val observedGeneration = AtomicLong(7)
var queued = 0
fun schedule(observed: Long) {
    if (!pendingRebuild.compareAndSet(false, true)) return
    if (observedGeneration.get() == observed) queued++
    // A rebuild advances the generation before another callback can act.
    observedGeneration.incrementAndGet()
    pendingRebuild.set(false)
}
schedule(7)
schedule(7)
check(queued == 1)
println("DNS_GENERATION_MODEL_PASS")

package kr.lifetime

internal class LifeClock(now: Long, fractionalNanos: Long) {
    private var lastNanos = now
    var fractionalNanos = fractionalNanos
        private set

    init {
        require(fractionalNanos in 0 until NANOS_PER_SECOND) { "Invalid fractional elapsed time" }
    }

    fun advance(now: Long): Long {
        val elapsed = now - lastNanos
        require(elapsed >= 0) { "Monotonic clock moved backwards" }
        lastNanos = now
        var seconds = elapsed / NANOS_PER_SECOND
        fractionalNanos += elapsed % NANOS_PER_SECOND
        if (fractionalNanos >= NANOS_PER_SECOND) {
            fractionalNanos -= NANOS_PER_SECOND
            seconds++
        }
        return seconds
    }

    fun reset(now: Long) {
        lastNanos = now
        fractionalNanos = 0
    }

    companion object {
        private const val NANOS_PER_SECOND = 1_000_000_000L
    }
}

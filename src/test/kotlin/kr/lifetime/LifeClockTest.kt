package kr.lifetime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LifeClockTest {
    @Test
    fun `elapsed time includes lag and survives reconnect`() {
        val clock = LifeClock(0, 0)
        assertEquals(0, clock.advance(999_999_999))
        assertEquals(1, clock.advance(1_000_000_000))
        assertEquals(3, clock.advance(4_200_000_000))
        val reconnected = LifeClock(100, clock.fractionalNanos)
        assertEquals(0, reconnected.advance(800_000_099))
        assertEquals(1, reconnected.advance(800_000_100))
        assertEquals(0, reconnected.fractionalNanos)
    }

    @Test
    fun `reset and monotonic wrap preserve clock semantics`() {
        val clock = LifeClock(Long.MAX_VALUE - 100, 0)
        assertEquals(0, clock.advance(Long.MIN_VALUE + 99))
        assertEquals(200, clock.fractionalNanos)
        clock.reset(0)
        assertEquals(0, clock.fractionalNanos)
        assertEquals(1, clock.advance(1_000_000_000))
        assertFailsWith<IllegalArgumentException> { clock.advance(0) }
        assertFailsWith<IllegalArgumentException> { LifeClock(0, -1) }
        assertFailsWith<IllegalArgumentException> { LifeClock(0, 1_000_000_000) }
    }
}

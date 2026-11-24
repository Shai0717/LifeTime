package kr.lifetime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LifeTransferTest {
    @Test
    fun `transfer conserves life and can revive eliminated recipient`() {
        assertEquals(LifeTransfer.Success(1, 9), calculateTransfer(10, 0, 9))
        assertEquals(LifeTransfer.Success(5, 12), calculateTransfer(10, 7, 5))
    }

    @Test
    fun `transfer rejects exhaustion and overflow`() {
        assertEquals(LifeTransfer.InsufficientLife, calculateTransfer(10, 0, 10))
        assertEquals(LifeTransfer.InsufficientLife, calculateTransfer(0, 0, 1))
        assertEquals(LifeTransfer.RecipientOverflow, calculateTransfer(10, Long.MAX_VALUE, 1))
        assertEquals(LifeTransfer.Success(9, Long.MAX_VALUE), calculateTransfer(10, Long.MAX_VALUE - 1, 1))
        assertFailsWith<IllegalArgumentException> { calculateTransfer(10, 1, 0) }
    }
}

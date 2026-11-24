package kr.lifetime

internal sealed interface LifeTransfer {
    data class Success(val sender: Long, val recipient: Long) : LifeTransfer
    data object InsufficientLife : LifeTransfer
    data object RecipientOverflow : LifeTransfer
}

internal fun calculateTransfer(sender: Long, recipient: Long, amount: Long): LifeTransfer {
    require(sender >= 0 && recipient >= 0 && amount > 0)
    if (amount >= sender) return LifeTransfer.InsufficientLife
    if (amount > Long.MAX_VALUE - recipient) return LifeTransfer.RecipientOverflow
    return LifeTransfer.Success(sender - amount, recipient + amount)
}

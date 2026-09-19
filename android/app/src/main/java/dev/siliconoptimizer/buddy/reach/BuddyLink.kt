package dev.siliconoptimizer.buddy.reach

/**
 * Everything `siliconbuddy://` can mean.
 *
 * M1 had one: a pairing code. M2 adds two more, because a widget, a tile and a shortcut
 * all need a way to hand the app a half-written message. All three are requests rather
 * than instructions — anything on the phone can open a URL in this app, so a link may
 * fill the composer in and may never send it. The person presses send.
 */
sealed interface BuddyLink {
    /** `siliconbuddy://pair?host&port&code` — handled by `PairingInvite`. */
    object Pair : BuddyLink

    /**
     * `siliconbuddy://ask` — open the composer, optionally with something in it.
     *
     * `?offer=phone` is what the tile and the widget add when the Mac was unreachable and a
     * model is ready on the phone: the composer opens with "Answer on this phone" showing.
     * Showing — never answering: anything on the phone can fire this link, so it can put the
     * offer in front of the owner and nothing more.
     */
    data class Compose(val text: String?, val offerPhone: Boolean = false) : BuddyLink

    /** `siliconbuddy://conversation?id=…` — open a transcript. */
    data class Conversation(val id: String) : BuddyLink

    companion object {
        /**
         * The most a link may put in the composer.
         *
         * A URL can be opened by anything — a web page, a message, a QR on a wall — and
         * a megabyte of text arriving in the box would be a denial of service dressed
         * as a shortcut. Long enough for a paragraph somebody meant to send.
         */
        const val MAX_COMPOSED_CHARACTERS = 4_000

        /** `siliconbuddy://ask?offer=phone`: open with the phone's offer showing. */
        const val OFFER_PHONE = "phone"
        const val ASK_ON_PHONE_URI = "siliconbuddy://ask?offer=phone"

        /**
         * Reads a link, or returns null when it is not one of ours. Deliberately not
         * throwing: the caller already has `PairingInvite.parse` for the pairing case
         * and its refusals, and everything else here is "this is not for me".
         */
        fun parse(
            scheme: String?,
            action: String?,
            text: String?,
            id: String?,
            offer: String? = null,
        ): BuddyLink? {
            if (!scheme.equals("siliconbuddy", ignoreCase = true)) return null
            return when (action?.lowercase()) {
                "pair" -> Pair
                "ask", "compose" -> Compose(
                    text?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_COMPOSED_CHARACTERS),
                    offerPhone = offer.equals(OFFER_PHONE, ignoreCase = true),
                )
                "conversation" -> id?.trim()?.takeIf { it.isNotEmpty() }?.let { Conversation(it) }
                else -> null
            }
        }
    }
}

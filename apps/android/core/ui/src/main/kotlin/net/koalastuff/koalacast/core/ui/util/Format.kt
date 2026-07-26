package net.koalastuff.koalacast.core.ui.util

import android.content.Context
import android.text.format.DateUtils
import net.koalastuff.koalacast.core.ui.R
import java.util.concurrent.TimeUnit

/**
 * Time is always shown both ways where it matters, and always in the mono style:
 * uppercase, tracked, tabular. These helpers produce the *text*; the style is the
 * caller's job.
 */
object Format {

    /** `49 MIN`, `1 H 12 MIN`, or an em dash when the feed omits a duration. */
    fun duration(context: Context, durationMs: Long): String {
        if (durationMs <= 0L) return context.getString(R.string.format_duration_unknown)
        val totalMinutes = TimeUnit.MILLISECONDS.toMinutes(durationMs)
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return if (hours > 0) {
            context.getString(R.string.format_duration_hours_minutes, hours, minutes)
        } else {
            context.getString(R.string.format_duration_minutes, totalMinutes.coerceAtLeast(1))
        }
    }

    /**
     * A publication date the way a listener reads it: "2 days ago" near the present,
     * an absolute date further back. Feeds without a date say so rather than
     * pretending to be from the epoch.
     */
    fun publishedAt(context: Context, pubDateMs: Long, hasPubDate: Boolean, nowMs: Long): String {
        if (!hasPubDate || pubDateMs <= 0L) {
            return context.getString(R.string.format_no_date)
        }
        val age = nowMs - pubDateMs
        return if (age in 0 until DateUtils.WEEK_IN_MILLIS) {
            DateUtils.getRelativeTimeSpanString(
                pubDateMs,
                nowMs,
                DateUtils.MINUTE_IN_MILLIS,
                DateUtils.FORMAT_ABBREV_RELATIVE,
            ).toString()
        } else {
            DateUtils.formatDateTime(
                context,
                pubDateMs,
                DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_SHOW_YEAR,
            )
        }
    }

    /** Strips tags from feed-supplied HTML so it can sit on one line in a list row. */
    fun plainText(html: String): String =
        html
            .replace(SCRIPT_OR_STYLE, " ")
            .replace(TAG, " ")
            .replace(ENTITY_NBSP, " ")
            .replace(WHITESPACE, " ")
            .trim()

    private val SCRIPT_OR_STYLE = Regex("(?is)<(script|style)[^>]*>.*?</\\1>")
    private val TAG = Regex("(?s)<[^>]*>")
    private val ENTITY_NBSP = Regex("&nbsp;|&#160;")
    private val WHITESPACE = Regex("\\s+")
}

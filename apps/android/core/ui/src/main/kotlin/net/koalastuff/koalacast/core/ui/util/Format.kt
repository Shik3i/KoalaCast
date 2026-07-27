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

    /** `1.25×`, with a trailing zero dropped — the design writes it that way. */
    fun speed(speed: Float): String {
        val rounded = Math.round(speed * 100) / 100f
        val text = if (rounded % 1f == 0f) rounded.toInt().toString() else rounded.toString()
        return "$text×"
    }

    /** `18:42` / `1:04:12`, for the time codes beside a scrubber. */
    fun timeCode(positionMs: Long): String {
        val totalSeconds = positionMs.coerceAtLeast(0) / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            "%d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%d:%02d".format(minutes, seconds)
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

    /**
     * A position inside an episode: `12:34`, or `1:02:03` once it passes an hour.
     * Unlike [duration] this never says "min" — it is a clock reading, not a length.
     */
    fun timecode(positionMs: Long): String {
        val total = (positionMs / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    private val SCRIPT_OR_STYLE = Regex("(?is)<(script|style)[^>]*>.*?</\\1>")
    private val TAG = Regex("(?s)<[^>]*>")
    private val ENTITY_NBSP = Regex("&nbsp;|&#160;")
    private val WHITESPACE = Regex("\\s+")
}

package net.koalastuff.koalacast.navigation

/**
 * Which population the Profile tab's dashboard describes.
 *
 * The personal and community screens render the same sections in the same order —
 * range control, KPI grid, activity heatmap, weekday and hour bars, categories,
 * time saved, top podcasts — so they are one destination with two scopes rather
 * than two tabs. Settings is reachable from both.
 */
enum class StatsScope { YOU, COMMUNITY }

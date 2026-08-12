package net.koalastuff.koalacast.feature.settings

import android.annotation.SuppressLint
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import net.koalastuff.koalacast.core.ui.R as CoreR
import net.koalastuff.koalacast.core.ui.component.IconButtonSquare
import net.koalastuff.koalacast.core.ui.component.MonoText
import net.koalastuff.koalacast.core.ui.icon.PhosphorIcons
import net.koalastuff.koalacast.core.ui.theme.KoalaSpacing
import net.koalastuff.koalacast.core.ui.theme.KoalaTheme

/**
 * Legal text intentionally stays English, matching the canonical web policy.
 * Translating legal clauses without review would create a divergent policy.
 */
@Composable
@SuppressLint("HardcodedText")
fun PrivacyScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val colors = KoalaTheme.colors
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bgPanel)
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(horizontal = KoalaSpacing.screenH, vertical = KoalaSpacing.gap),
        verticalArrangement = Arrangement.spacedBy(KoalaSpacing.gapSection),
    ) {
        Row {
            IconButtonSquare(
                icon = PhosphorIcons.CaretLeft,
                contentDescription = stringResource(CoreR.string.action_back),
                onClick = onBack,
                bordered = false,
            )
        }
        Text("Privacy Policy", style = KoalaTheme.type.screenTitle, color = colors.inkStrong)
        MonoText(
            "Last updated: 26 July 2026",
            color = colors.ink4,
            style = KoalaTheme.type.monoSmall,
        )
        PRIVACY_SECTIONS.forEach { section ->
            Column(verticalArrangement = Arrangement.spacedBy(KoalaSpacing.gapSmall)) {
                Text(section.title, style = KoalaTheme.type.sectionTitle, color = colors.inkStrong)
                section.body.forEach {
                    Text(it, style = KoalaTheme.type.bodySmall, color = colors.ink2)
                }
                section.legalBasis?.let {
                    Text(
                        "Legal basis: $it",
                        style = KoalaTheme.type.bodySmall,
                        color = colors.ink4,
                    )
                }
            }
        }
    }
}

private data class PrivacySection(
    val title: String,
    val body: List<String>,
    val legalBasis: String? = null,
)

private val PRIVACY_SECTIONS = listOf(
    PrivacySection(
        "1. Operator and hosting",
        listOf("The operator named in the Legal Notice (Impressum) is responsible for this service. KoalaCast is hosted by Hetzner Online GmbH, Industriestr. 25, 91710 Gunzenhausen, Germany. The regular hosting location is within the EU/EEA."),
        "Art. 6(1)(f) GDPR, reliable service delivery, troubleshooting, and secure operation.",
    ),
    PrivacySection(
        "2. Connection and security data",
        listOf(
            "The web server keeps access logs for seven days. They may contain IP address, date and time, HTTP method and path, response status, referrer, and user agent. They are used only for operation, troubleshooting, security, and abuse prevention.",
            "Network addresses retained for session security are reduced to an IPv4 /24 network or at most the first three IPv6 groups. A shortened user agent may also be stored.",
        ),
        "Art. 6(1)(f) GDPR, reliable and secure operation and abuse prevention.",
    ),
    PrivacySection(
        "3. Local app mode",
        listOf("KoalaCast works without an account. Subscriptions, queue, playback progress, listening history, and preferences remain on this device unless you explicitly opt into cross-device sync."),
        "Art. 6(1)(b) GDPR, requested local application functionality.",
    ),
    PrivacySection(
        "4. Server proxying and external metadata",
        listOf(
            "Artwork, search, RSS feeds, chapters, and transcripts can be fetched through the KoalaCast backend so metadata providers do not receive this device's IP address.",
            "Audio is not proxied. It streams directly from the publisher's CDN, which receives your IP address and standard request headers.",
        ),
        "Art. 6(1)(f) GDPR, privacy-preserving proxying and compatibility.",
    ),
    PrivacySection(
        "5. Accounts and sessions",
        listOf("Accounts store a public username, status, role, timestamps, and an Argon2id password hash. No email is required. Device tokens are encrypted on this Android device; only hashes are kept by the server. Optional sync covers subscriptions, queue, progress, and listening statistics."),
        "Art. 6(1)(b) GDPR for account and sync services, and Art. 6(1)(f) GDPR for security.",
    ),
    PrivacySection(
        "6. Optional global statistics",
        listOf("Participation is off by default. Opting in includes synchronized sessions in public aggregates and your username in the listener leaderboard. Public data excludes episodes, raw sessions, timestamps, device IDs, and account IDs. Opting out removes the account immediately."),
        "Art. 6(1)(a) GDPR, explicit and freely revocable consent.",
    ),
    PrivacySection(
        "7. No advertising or client tracking",
        listOf("KoalaCast operates no advertising, tracking cookies, behavioral profiling, or user-level analytics. The Google Cast SDK sends encrypted, anonymous Cast interaction, device, SDK, and app metadata to Google for aggregate usage/performance analysis and defect detection. Google states that these logs contain no identifier traceable to a user and cannot be disabled or deleted by KoalaCast or the listener."),
    ),
    PrivacySection(
        "8. External links",
        listOf("GitHub, license, legal, and publisher links are ordinary external links. Opening one sends usual connection data to that provider under its own privacy policy."),
    ),
    PrivacySection(
        "9. Your rights and data control",
        listOf("Under the GDPR, you have rights to access, rectification, erasure, restriction, portability, objection, and complaint. You can import or export OPML, revoke sessions, and leave global statistics at any time."),
    ),
)

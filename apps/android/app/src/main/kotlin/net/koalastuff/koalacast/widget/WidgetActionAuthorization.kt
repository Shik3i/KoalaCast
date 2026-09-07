package net.koalastuff.koalacast.widget

internal fun isTrustedWidgetAction(receivedToken: String?, storedToken: String?): Boolean =
    !storedToken.isNullOrBlank() && receivedToken == storedToken

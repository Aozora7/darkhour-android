package one.aozora.darkhour.data

internal fun nextHealthConnectPageToken(
    returnedPageToken: String?,
    seenPageTokens: MutableSet<String>,
): String? {
    val pageToken = returnedPageToken?.takeUnless(String::isBlank) ?: return null
    check(seenPageTokens.add(pageToken)) {
        "Health Connect returned a repeated page token"
    }
    return pageToken
}

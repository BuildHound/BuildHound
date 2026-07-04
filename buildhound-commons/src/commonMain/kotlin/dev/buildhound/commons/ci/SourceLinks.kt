package dev.buildhound.commons.ci

import dev.buildhound.commons.payload.LinksInfo

/**
 * Git remote redaction + source/commit/PR link composition (plan 027, §4.5). KMP-pure string
 * parsing (no `java.net.URI`, which is JVM-only) so both the plugin and the golden tests run it.
 *
 * Two hard rules, both fail-closed:
 * - [redactRemoteUrl] strips userInfo for **every** scheme (fixing CCUD's http-only leak of
 *   `ssh://user:pass@host`); when it can't confidently parse the value, it returns null rather
 *   than risk leaking a credential.
 * - [compose] builds links only for github/gitlab hosts, always as `https://…`, so an
 *   env-controlled `javascript:` origin can never become a hyperlink.
 */
object SourceLinks {

    fun redactRemoteUrl(raw: String?): String? {
        val url = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        // A real remote URL never carries whitespace or control chars — reject fail-closed.
        if (url.any { it.isWhitespace() || it.code < 0x20 }) return null
        val schemeSep = url.indexOf("://")
        return if (schemeSep >= 0) redactSchemeUrl(url, schemeSep) else redactScpLike(url)
    }

    private fun redactSchemeUrl(url: String, schemeSep: Int): String? {
        val scheme = url.substring(0, schemeSep)
        if (scheme.isEmpty()) return null
        val afterScheme = url.substring(schemeSep + 3)
        val slash = afterScheme.indexOf('/').let { if (it < 0) afterScheme.length else it }
        val authority = afterScheme.substring(0, slash)
        val rest = afterScheme.substring(slash)
        // Split at the LAST '@': git accepts a raw '@' in the password, so the host is after the
        // final '@' and everything before it is userInfo — redact it whole (splitting at the first
        // '@' would leak the tail of an '@'-bearing credential; C1). Empty userInfo (`@host`) stays.
        val at = authority.lastIndexOf('@')
        // A '@' that falls AFTER the first '/' (so it never landed in `authority`) means the userInfo
        // carries a raw, unencoded '/' in the password — common in base64/URL-encoded tokens. Splitting
        // the authority at that '/' mis-locates the userInfo, so the credential would survive verbatim.
        // Fail closed: drop the whole value rather than leak it (§3.7; honours the fail-closed KDoc).
        if (at < 0 && afterScheme.indexOf('@') >= 0) return null
        val redactedAuthority = if (at > 0) "******" + authority.substring(at) else authority
        if (redactedAuthority.substringAfterLast('@').isEmpty()) return null // no host → drop
        return "$scheme://$redactedAuthority$rest"
    }

    private fun redactScpLike(url: String): String? {
        // scp-like: [user[:pass]@]host:path — the path colon is the first ':' AFTER the last '@'
        // (the userInfo, which may itself contain '@' or ':', is everything before that final '@').
        val at = url.lastIndexOf('@')
        val colon = url.indexOf(':', startIndex = if (at >= 0) at + 1 else 0)
        if (colon < 0 || colon == at + 1) return null // no host:path colon, or empty host → drop
        return if (at > 0) "******" + url.substring(at) else url
    }

    /**
     * Commit/PR web links from a (redacted) remote URL + sha + PR number. github/gitlab only,
     * always https; null when the host isn't supported, nothing composes, or the URL won't parse.
     */
    fun compose(remoteUrl: String?, sha: String?, prNumber: String?): LinksInfo? {
        val ref = parse(remoteUrl) ?: return null
        val github = ref.host.contains("github", ignoreCase = true)
        val gitlab = ref.host.contains("gitlab", ignoreCase = true)
        if (!github && !gitlab) return null // host-gated, fail-closed
        val base = "https://${ref.host}/${ref.path}"
        val commitUrl = sha?.takeIf { it.isNotEmpty() }?.let { if (github) "$base/commit/$it" else "$base/-/commit/$it" }
        val prUrl = prNumber?.takeIf { it.isNotEmpty() }?.let { if (github) "$base/pull/$it" else "$base/-/merge_requests/$it" }
        val links = LinksInfo(
            commitUrl = commitUrl?.takeIf { it.isHttpUrl() },
            pullRequestUrl = prUrl?.takeIf { it.isHttpUrl() },
        )
        return links.takeIf { it.commitUrl != null || it.pullRequestUrl != null }
    }

    private data class RepoRef(val host: String, val path: String)

    private fun parse(remoteUrl: String?): RepoRef? {
        val url = remoteUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val schemeSep = url.indexOf("://")
        val authority: String
        val pathPart: String
        if (schemeSep >= 0) {
            val afterScheme = url.substring(schemeSep + 3)
            val slash = afterScheme.indexOf('/')
            if (slash < 0) return null
            authority = afterScheme.substring(0, slash)
            pathPart = afterScheme.substring(slash + 1)
        } else {
            val colon = url.indexOf(':')
            if (colon <= 0) return null
            authority = url.substring(0, colon)
            pathPart = url.substring(colon + 1)
        }
        val host = authority.substringAfterLast('@').substringBefore(':') // drop userinfo + port
        if (host.isEmpty()) return null
        val path = pathPart.trim('/').removeSuffix(".git")
        if (path.isEmpty()) return null
        return RepoRef(host, path)
    }
}

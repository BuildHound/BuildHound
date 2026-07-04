package dev.buildhound.commons.ci

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SourceLinksTest {

    @Test
    fun redacts_user_info_for_every_scheme() {
        assertEquals("https://******@github.com/org/repo.git", SourceLinks.redactRemoteUrl("https://user:pass@github.com/org/repo.git"))
        assertEquals("ssh://******@gitlab.com/org/repo.git", SourceLinks.redactRemoteUrl("ssh://git:tok@gitlab.com/org/repo.git"))
        // scp-like — the CCUD http-only guard would have leaked this; here `git` userInfo is redacted.
        assertEquals("******@github.com:org/repo.git", SourceLinks.redactRemoteUrl("git@github.com:org/repo.git"))
    }

    @Test
    fun a_credential_never_survives_redaction() {
        for (raw in listOf(
            "https://user:s3cr3t@github.com/o/r.git",
            "ssh://deploy:AKIAABCDEFGHIJKLMNOP@gitlab.com/o/r",
            "https://token@github.com/o/r",
            // A raw '@' inside the userInfo must not leak the tail (host is after the LAST '@').
            "https://user:p@ssw0rd@github.com/o/r.git",
            "https://me@corp.com:tok3n@dev.azure.com/o/r",
            // scp-like with a ':' inside userInfo — the host colon is the one after the '@'.
            "svc:ghp_SECRETTOKEN@git.example.com:org/repo.git",
        )) {
            val redacted = SourceLinks.redactRemoteUrl(raw)!!
            for (secret in listOf("s3cr3t", "AKIA", "token", "deploy", "user", "ssw0rd", "tok3n", "ghp_SECRETTOKEN", "corp.com")) {
                assertTrue(!redacted.contains(secret), "leaked '$secret' in '$redacted' (from '$raw')")
            }
        }
    }

    @Test
    fun a_url_without_credentials_passes_through() {
        assertEquals("https://github.com/org/repo.git", SourceLinks.redactRemoteUrl("https://github.com/org/repo.git"))
        assertEquals("ssh://gitlab.com/org/repo.git", SourceLinks.redactRemoteUrl("ssh://gitlab.com/org/repo.git"))
    }

    @Test
    fun unparseable_or_blank_remote_is_dropped_fail_closed() {
        assertNull(SourceLinks.redactRemoteUrl(null))
        assertNull(SourceLinks.redactRemoteUrl("   "))
        assertNull(SourceLinks.redactRemoteUrl("has spaces://x")) // whitespace → fail closed
        assertNull(SourceLinks.redactRemoteUrl("://host/path")) // empty scheme
        assertNull(SourceLinks.redactRemoteUrl("nonsense")) // no scheme, no host:path colon
    }

    @Test
    fun composes_commit_and_pr_links_for_github_and_gitlab_only() {
        val gh = SourceLinks.compose("https://github.com/org/repo.git", "a".repeat(40), "42")!!
        assertEquals("https://github.com/org/repo/commit/${"a".repeat(40)}", gh.commitUrl)
        assertEquals("https://github.com/org/repo/pull/42", gh.pullRequestUrl)

        val gl = SourceLinks.compose("git@gitlab.com:org/repo.git", "b".repeat(40), "7")!!
        assertEquals("https://gitlab.com/org/repo/-/commit/${"b".repeat(40)}", gl.commitUrl)
        assertEquals("https://gitlab.com/org/repo/-/merge_requests/7", gl.pullRequestUrl)

        // A redacted remote still composes (host survives redaction).
        val redacted = SourceLinks.compose("https://******@github.com/org/repo.git", "c".repeat(40), null)!!
        assertEquals("https://github.com/org/repo/commit/${"c".repeat(40)}", redacted.commitUrl)
        assertNull(redacted.pullRequestUrl)
    }

    @Test
    fun composes_nothing_for_unsupported_hosts_or_missing_inputs() {
        assertNull(SourceLinks.compose("https://bitbucket.org/org/repo.git", "a".repeat(40), "1"), "host-gated")
        assertNull(SourceLinks.compose("https://github.com/org/repo.git", null, null), "no sha or pr → nothing")
        assertNull(SourceLinks.compose(null, "a".repeat(40), "1"))
    }

    @Test
    fun a_javascript_origin_can_never_become_a_javascript_hyperlink() {
        // compose ignores the scheme entirely and always builds an `https://` base, so an
        // env-controlled `javascript:` origin can never surface as a javascript hyperlink.
        val nonHost = SourceLinks.compose("javascript:alert(1)", "a".repeat(40), null)
        assertNull(nonHost, "no host:path colon and no supported host → nothing composes")
        val links = SourceLinks.compose("javascript://github/x", "a".repeat(40), null)
        assertTrue(links == null || links.commitUrl?.startsWith("https://") == true, "any composed link is https, never javascript:")
    }
}

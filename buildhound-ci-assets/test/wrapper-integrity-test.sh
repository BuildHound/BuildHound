#!/usr/bin/env sh
# Harness for the `validateWrapper` preventive check (plan 066, research F16) in
# azure-pipelines/buildhound-gradle-steps.yml. The Azure step body is inline YAML (no external
# script it can delegate to for consumers who only reference the template, not a checkout of this
# repo — see the template's own comment), so `check_wrapper` below is a line-for-line mirror of
# that step's shell logic with the `${{ parameters.validateWrapper }}` template expression replaced
# by the `$mode` shell variable. Keep the two in sync on any future edit.
#
# Run: sh buildhound-ci-assets/test/wrapper-integrity-test.sh
set -eu

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

fail() { echo "FAIL: $1" >&2; exit 1; }

# Mirrors the YAML step's script block verbatim (mode is the templated validateWrapper value;
# expected is BUILDHOUND_EXPECTED_WRAPPER_JAR_SHA256).
check_wrapper() {
    mode="$1"
    props="$2"
    jar="$3"
    expected="$4"
    issues=0

    if [ ! -f "$props" ]; then
        echo "buildhound: $props not found; skipping wrapper-integrity check"
    elif grep -q '^distributionSha256Sum=' "$props"; then
        echo "buildhound: wrapper distributionSha256Sum is pinned"
    else
        echo "buildhound: wrapper is NOT pinned (no distributionSha256Sum= in $props)"
        issues=$((issues + 1))
    fi

    if [ -n "$expected" ]; then
        if [ ! -f "$jar" ]; then
            echo "buildhound: $jar not found; cannot verify its SHA-256"
            issues=$((issues + 1))
        else
            actual=$(sha256sum "$jar" | awk '{print $1}')
            if [ "$actual" = "$expected" ]; then
                echo "buildhound: gradle-wrapper.jar SHA-256 matches the expected value"
            else
                echo "buildhound: gradle-wrapper.jar SHA-256 mismatch (expected $expected, got $actual)"
                issues=$((issues + 1))
            fi
        fi
    fi

    if [ "$issues" -gt 0 ]; then
        if [ "$mode" = "fail" ]; then
            echo "##vso[task.logissue type=error]BuildHound wrapper-integrity check found $issues issue(s)"
            return 1
        fi
        echo "##vso[task.logissue type=warning]BuildHound wrapper-integrity check found $issues issue(s)"
    fi
    return 0
}

unpinned="$work/unpinned.properties"
printf 'distributionUrl=https\\://services.gradle.org/distributions/gradle-8.14-bin.zip\n' > "$unpinned"

pinned="$work/pinned.properties"
printf 'distributionUrl=https\\://services.gradle.org/distributions/gradle-8.14-bin.zip\ndistributionSha256Sum=abc123\n' > "$pinned"

no_jar="$work/no-such.jar"

# 1. Unpinned + mode=fail -> non-zero.
if check_wrapper "fail" "$unpinned" "$no_jar" "" >/dev/null; then
    fail "unpinned wrapper in fail mode must exit non-zero"
fi

# 2. Unpinned + mode=warn -> zero (warns, never fails).
check_wrapper "warn" "$unpinned" "$no_jar" "" >/dev/null || fail "unpinned wrapper in warn mode must exit zero"

# 3. Unpinned + mode=off -> zero (the YAML template's ${{ if ne(validateWrapper, 'off') }} skips the
#    step entirely; the function itself also never fails for a non-"fail" mode, so this stays zero
#    as a defense-in-depth parity check).
check_wrapper "off" "$unpinned" "$no_jar" "" >/dev/null || fail "unpinned wrapper in off mode must exit zero"

# 4. Pinned + mode=fail -> zero (nothing to flag).
check_wrapper "fail" "$pinned" "$no_jar" "" >/dev/null || fail "pinned wrapper in fail mode must exit zero"

# 5. A missing gradle-wrapper.properties file is skipped silently (not itself a pinning failure).
check_wrapper "fail" "$work/missing.properties" "$no_jar" "" >/dev/null \
    || fail "a missing properties file must not fail the fail-mode check"

# 6. expectedWrapperJarSha256 set + jar matches -> zero.
jar="$work/gradle-wrapper.jar"
printf 'jar bytes' > "$jar"
actual_sha="$(sha256sum "$jar" | awk '{print $1}')"
check_wrapper "fail" "$pinned" "$jar" "$actual_sha" >/dev/null || fail "matching jar sha256 must exit zero"

# 7. expectedWrapperJarSha256 set + jar mismatches, mode=fail -> non-zero.
if check_wrapper "fail" "$pinned" "$jar" "0000000000000000000000000000000000000000000000000000000000000000" >/dev/null; then
    fail "mismatched jar sha256 in fail mode must exit non-zero"
fi

# 8. expectedWrapperJarSha256 set + jar mismatches, mode=warn -> zero (warns only).
check_wrapper "warn" "$pinned" "$jar" "0000000000000000000000000000000000000000000000000000000000000000" >/dev/null \
    || fail "mismatched jar sha256 in warn mode must exit zero"

echo "wrapper-integrity-test OK"

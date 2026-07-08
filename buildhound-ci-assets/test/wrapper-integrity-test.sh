#!/usr/bin/env sh
# Harness for the `validateWrapper` preventive check (plan 066, research F16) in
# azure-pipelines/buildhound-gradle-steps.yml. The Azure step body is inline YAML (no external
# script it can delegate to for consumers who only reference the template, not a checkout of this
# repo — see the template's own comment), so `check_wrapper` below is a line-for-line mirror of
# that step's shell logic with the `${{ parameters.validateWrapper }}` template expression replaced
# by the `$mode` shell variable. Keep the two in sync on any future edit (no automated parity guard
# exists yet — see docs/plans/066-*.md Implementation notes).
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
    gradlew="$5"
    issues=0

    if [ ! -f "$props" ]; then
        if [ -f "$gradlew" ]; then
            echo "buildhound: $props not found but $gradlew exists — this repo uses the Gradle wrapper but its properties file is missing"
            issues=$((issues + 1))
        else
            echo "buildhound: $gradlew not found; this repo does not use the Gradle wrapper — skipping wrapper-integrity check"
        fi
    elif grep -q '^distributionSha256Sum=..*' "$props"; then
        echo "buildhound: wrapper distributionSha256Sum is pinned"
    else
        echo "buildhound: wrapper is NOT pinned (no non-empty distributionSha256Sum= in $props) — this is a pinning-PRESENCE check only, not a jar-integrity check; set expectedWrapperJarSha256 for that"
        issues=$((issues + 1))
    fi

    if [ -n "$expected" ]; then
        if [ ! -f "$jar" ]; then
            echo "buildhound: $jar not found; cannot verify its SHA-256"
            issues=$((issues + 1))
        else
            if command -v sha256sum >/dev/null 2>&1; then
                actual=$(sha256sum "$jar" | awk '{print $1}')
            elif command -v shasum >/dev/null 2>&1; then
                actual=$(shasum -a 256 "$jar" | awk '{print $1}')
            else
                actual=""
            fi
            if [ -z "$actual" ]; then
                echo "buildhound: neither sha256sum nor shasum is available on this agent; cannot verify gradle-wrapper.jar SHA-256"
                issues=$((issues + 1))
            else
                actual_lc=$(printf '%s' "$actual" | tr '[:upper:]' '[:lower:]')
                expected_lc=$(printf '%s' "$expected" | tr '[:upper:]' '[:lower:]')
                if [ "$actual_lc" = "$expected_lc" ]; then
                    echo "buildhound: gradle-wrapper.jar SHA-256 matches the expected value"
                else
                    echo "buildhound: gradle-wrapper.jar SHA-256 mismatch (expected $expected, got $actual)"
                    issues=$((issues + 1))
                fi
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

empty_pin="$work/empty-pin.properties"
printf 'distributionUrl=https\\://services.gradle.org/distributions/gradle-8.14-bin.zip\ndistributionSha256Sum=\n' > "$empty_pin"

pinned="$work/pinned.properties"
printf 'distributionUrl=https\\://services.gradle.org/distributions/gradle-8.14-bin.zip\ndistributionSha256Sum=abc123\n' > "$pinned"

no_jar="$work/no-such.jar"

# A stand-in gradlew present/absent to exercise the fail-open-on-missing-props logic.
gradlew_present="$work/gradlew"
printf '#!/usr/bin/env sh\n' > "$gradlew_present"
gradlew_absent="$work/no-such-gradlew"

# 1. Unpinned + mode=fail -> non-zero.
if check_wrapper "fail" "$unpinned" "$no_jar" "" "$gradlew_present" >/dev/null; then
    fail "unpinned wrapper in fail mode must exit non-zero"
fi

# 2. Unpinned + mode=warn -> zero (warns, never fails).
check_wrapper "warn" "$unpinned" "$no_jar" "" "$gradlew_present" >/dev/null || fail "unpinned wrapper in warn mode must exit zero"

# 3. Unpinned + mode=off -> zero (the YAML template's ${{ if ne(validateWrapper, 'off') }} skips the
#    step entirely; the function itself also never fails for a non-"fail" mode, so this stays zero
#    as a defense-in-depth parity check).
check_wrapper "off" "$unpinned" "$no_jar" "" "$gradlew_present" >/dev/null || fail "unpinned wrapper in off mode must exit zero"

# 4. Pinned + mode=fail -> zero (nothing to flag).
check_wrapper "fail" "$pinned" "$no_jar" "" "$gradlew_present" >/dev/null || fail "pinned wrapper in fail mode must exit zero"

# 5. An empty distributionSha256Sum= value does not count as pinned (presence check requires a
#    non-empty value) -> non-zero in fail mode.
if check_wrapper "fail" "$empty_pin" "$no_jar" "" "$gradlew_present" >/dev/null; then
    fail "empty distributionSha256Sum= value must not count as pinned"
fi

# 6. A missing gradle-wrapper.properties file WITH gradlew present is a real issue (repo uses the
#    wrapper but its properties file is missing) -> non-zero in fail mode.
if check_wrapper "fail" "$work/missing.properties" "$no_jar" "" "$gradlew_present" >/dev/null; then
    fail "missing properties file with gradlew present must fail the fail-mode check"
fi

# 7. A missing gradle-wrapper.properties file WITHOUT gradlew is an honest no-op (repo legitimately
#    has no wrapper) -> zero even in fail mode.
check_wrapper "fail" "$work/missing.properties" "$no_jar" "" "$gradlew_absent" >/dev/null \
    || fail "a repo with no gradlew at all must not fail the fail-mode check"

# 8. expectedWrapperJarSha256 set + real_jar matches -> zero.
real_jar="$work/gradle-wrapper.jar"
printf 'jar bytes' > "$real_jar"
actual_sha="$(sha256sum "$real_jar" 2>/dev/null | awk '{print $1}')"
if [ -z "$actual_sha" ]; then
    actual_sha="$(shasum -a 256 "$real_jar" | awk '{print $1}')"
fi
check_wrapper "fail" "$pinned" "$real_jar" "$actual_sha" "$gradlew_present" >/dev/null || fail "matching real_jar sha256 must exit zero"

# 9. Case-insensitive compare: an uppercase expected value must still match -> zero.
actual_sha_uc="$(printf '%s' "$actual_sha" | tr '[:lower:]' '[:upper:]')"
check_wrapper "fail" "$pinned" "$real_jar" "$actual_sha_uc" "$gradlew_present" >/dev/null \
    || fail "case-insensitive real_jar sha256 match must exit zero"

# 10. expectedWrapperJarSha256 set + real_jar mismatches, mode=fail -> non-zero.
if check_wrapper "fail" "$pinned" "$real_jar" "0000000000000000000000000000000000000000000000000000000000000000" "$gradlew_present" >/dev/null; then
    fail "mismatched real_jar sha256 in fail mode must exit non-zero"
fi

# 11. expectedWrapperJarSha256 set + real_jar mismatches, mode=warn -> zero (warns only).
check_wrapper "warn" "$pinned" "$real_jar" "0000000000000000000000000000000000000000000000000000000000000000" "$gradlew_present" >/dev/null \
    || fail "mismatched real_jar sha256 in warn mode must exit zero"

# 12. expectedWrapperJarSha256 set + real_jar MISSING, mode=fail -> non-zero.
if check_wrapper "fail" "$pinned" "$no_jar" "$actual_sha" "$gradlew_present" >/dev/null; then
    fail "expected sha256 set with a missing real_jar in fail mode must exit non-zero"
fi

# 13. expectedWrapperJarSha256 set + real_jar MISSING, mode=warn -> zero (warns only).
check_wrapper "warn" "$pinned" "$no_jar" "$actual_sha" "$gradlew_present" >/dev/null \
    || fail "expected sha256 set with a missing real_jar in warn mode must exit zero"

# 14. Tool-detection fallback: with only `shasum` on PATH (no `sha256sum`), the comparison still
#     works via the `shasum -a 256` fallback -> zero for a matching real_jar.
narrow_shasum="$work/bin-shasum-only"
mkdir -p "$narrow_shasum"
for tool in grep awk tr; do
    tool_path=$(command -v "$tool") || fail "$tool not found on this machine; cannot build the narrow-PATH fixture"
    ln -s "$tool_path" "$narrow_shasum/$tool"
done
shasum_path=$(command -v shasum) || fail "shasum not found on this machine; cannot exercise the fallback path"
ln -s "$shasum_path" "$narrow_shasum/shasum"
(PATH="$narrow_shasum" check_wrapper "fail" "$pinned" "$real_jar" "$actual_sha" "$gradlew_present" >/dev/null) \
    || fail "the shasum fallback (sha256sum absent from PATH) must exit zero for a matching real_jar"

# 15. Tool-detection: with NEITHER sha256sum nor shasum on PATH, cannot verify -> counted as an
#     issue -> non-zero in fail mode (never a silently-empty "" match).
narrow_none="$work/bin-no-hash-tool"
mkdir -p "$narrow_none"
for tool in grep awk tr; do
    tool_path=$(command -v "$tool")
    ln -s "$tool_path" "$narrow_none/$tool"
done
if (PATH="$narrow_none" check_wrapper "fail" "$pinned" "$real_jar" "$actual_sha" "$gradlew_present" >/dev/null); then
    fail "no available hash tool must be treated as an issue and fail the fail-mode check"
fi
(PATH="$narrow_none" check_wrapper "warn" "$pinned" "$real_jar" "$actual_sha" "$gradlew_present" >/dev/null) \
    || fail "no available hash tool in warn mode must exit zero"

echo "wrapper-integrity-test OK"

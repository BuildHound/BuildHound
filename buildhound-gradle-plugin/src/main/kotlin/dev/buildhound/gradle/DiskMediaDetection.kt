package dev.buildhound.gradle

import dev.buildhound.commons.payload.DiskMedia

/**
 * Classifies the storage media behind the build root's filesystem (plan 104) — pure over its inputs,
 * with sysfs reads injected, so every branch is unit-testable without a real disk.
 *
 * **No subprocess, ever.** The only host reads are `/sys/block/…/queue/rotational`, a one-byte
 * pseudo-file. macOS would need `diskutil` and Windows would need WMI/PowerShell; both cost more
 * than the datum is worth on the always-on path, so they answer [DiskMedia.UNKNOWN] by construction.
 *
 * **Conservative by design.** Only an actual reading produces a media class. Every layout whose
 * block device does not resolve — LVM, dm-crypt, btrfs subvolumes, container overlayfs (i.e. most
 * CI) — falls through to [DiskMedia.UNKNOWN] rather than a plausible-looking guess.
 *
 * **Privacy (spec §3.7):** [deviceName] and [fileStoreType] are classifier *input only*. This object
 * returns an enum and never a string, so no device path or filesystem name can reach the payload —
 * the same "nothing to scrub because nothing textual escapes" discipline as the plan-065 jinfo
 * allowlist reads.
 */
internal object DiskMediaDetection {

    /** Filesystem types that are a network mount whatever the far-side media is. */
    private val NETWORK_FILESYSTEMS = setOf(
        "nfs", "nfs4", "cifs", "smbfs", "smb3", "afs", "9p", "fuse.sshfs", "fuse.s3fs", "afpfs",
    )

    private const val DEV_PREFIX = "/dev/"
    private const val NVME_PREFIX = "nvme"

    /**
     * The shape a kernel block-device name actually takes; anything else is not asked about. The
     * leading-alphanumeric anchor is load-bearing: `.` is a legal *interior* character, so a class
     * of `[A-Za-z0-9._-]+` alone would happily accept `..` — the one traversal token that matters.
     */
    private val DEVICE_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
    private const val ROTATIONAL_TRUE = "1"
    private const val ROTATIONAL_FALSE = "0"

    /**
     * @param osName `os.name`; only a Linux host has the sysfs queue attributes this reads.
     * @param deviceName the `FileStore.name()`, e.g. `/dev/nvme0n1p2`, `/dev/mapper/vg-root`, `overlay`.
     * @param fileStoreType the `FileStore.type()`, e.g. `ext4`, `overlay`, `nfs4`, `apfs`.
     * @param readSysfs reads a sysfs path's contents; null when it does not exist or is unreadable.
     */
    fun classify(
        osName: String?,
        deviceName: String?,
        fileStoreType: String?,
        readSysfs: (String) -> String?,
    ): DiskMedia {
        // A network mount is a network mount on every OS — checked before the Linux gate, since it
        // is the one class knowable without sysfs.
        if (fileStoreType != null && fileStoreType.lowercase() in NETWORK_FILESYSTEMS) return DiskMedia.NETWORK
        if (osName == null || !osName.startsWith("Linux", ignoreCase = true)) return DiskMedia.UNKNOWN

        val device = deviceName?.removePrefix(DEV_PREFIX)?.trim().orEmpty()
        return when {
            // An allowlist, not a "contains no slash" blocklist: this string is concatenated into a
            // filesystem path, so `..`, control bytes and NUL must be rejected by shape rather than
            // by enumerating what to exclude. `mapper/vg-root` and `overlay` fall out here too — no
            // block device to ask about.
            !DEVICE_NAME.matches(device) -> DiskMedia.UNKNOWN
            // NVMe is decided by the device namespace name, not by rotational: an NVMe SSD reports
            // rotational=0 like a SATA SSD, so the finer class would be lost if this ran second.
            device.startsWith(NVME_PREFIX) -> DiskMedia.NVME
            else -> rotationalMedia(device, readSysfs)
        }
    }

    /**
     * Reads `queue/rotational` for the device, then for its digit-trimmed parent: a partition
     * (`sda1`) has no `queue/` of its own on some kernels, while its parent disk always does.
     * Anything absent, unreadable or unrecognized falls through to [DiskMedia.UNKNOWN].
     */
    private fun rotationalMedia(device: String, readSysfs: (String) -> String?): DiskMedia {
        for (candidate in candidates(device)) {
            when (readSysfs("/sys/block/$candidate/queue/rotational")?.trim()) {
                ROTATIONAL_FALSE -> return DiskMedia.SSD
                ROTATIONAL_TRUE -> return DiskMedia.ROTATIONAL
                else -> Unit // absent/unreadable/garbage → try the next candidate
            }
        }
        return DiskMedia.UNKNOWN
    }

    /** `sda1` → [`sda1`, `sda`]; `sda` → [`sda`]. Never trims a name down to nothing. */
    private fun candidates(device: String): List<String> {
        val base = device.trimEnd { it.isDigit() }
        return if (base.isEmpty() || base == device) listOf(device) else listOf(device, base)
    }
}

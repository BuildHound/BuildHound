package dev.buildhound.gradle

import dev.buildhound.commons.payload.DiskMedia
import kotlin.test.Test
import kotlin.test.assertEquals

/** Every branch of the plan-104 media classifier, with sysfs injected — no real disk touched. */
class DiskMediaDetectionTest {

    private fun classify(
        os: String? = "Linux",
        device: String? = "/dev/sda1",
        type: String? = "ext4",
        sysfs: Map<String, String> = emptyMap(),
    ): DiskMedia = DiskMediaDetection.classify(os, device, type, sysfs::get)

    @Test
    fun `a rotational flag of 0 is an SSD and 1 is a spinning disk`() {
        assertEquals(DiskMedia.SSD, classify(sysfs = mapOf("/sys/block/sda/queue/rotational" to "0\n")))
        assertEquals(DiskMedia.ROTATIONAL, classify(sysfs = mapOf("/sys/block/sda/queue/rotational" to "1\n")))
    }

    @Test
    fun `the exact device name wins over the digit-trimmed base`() {
        // Some kernels expose queue/ on the partition too; prefer it, and never let the parent's
        // (stale, or differing) answer override a partition-level reading.
        val sysfs = mapOf(
            "/sys/block/sda1/queue/rotational" to "0",
            "/sys/block/sda/queue/rotational" to "1",
        )
        assertEquals(DiskMedia.SSD, classify(sysfs = sysfs))
    }

    @Test
    fun `nvme is decided by the device name, not by rotational`() {
        // An NVMe drive also reports rotational=0, so a rotational-first order would silently
        // downgrade every NVMe to a plain SSD.
        assertEquals(
            DiskMedia.NVME,
            classify(device = "/dev/nvme0n1p2", sysfs = mapOf("/sys/block/nvme0n1p2/queue/rotational" to "0")),
        )
    }

    @Test
    fun `a network filesystem is classified on every OS, before the Linux gate`() {
        assertEquals(DiskMedia.NETWORK, classify(os = "Mac OS X", device = "server:/export", type = "nfs"))
        assertEquals(DiskMedia.NETWORK, classify(os = "Windows 11", device = """\\srv\share""", type = "CIFS"))
        assertEquals(DiskMedia.NETWORK, classify(type = "nfs4"))
    }

    @Test
    fun `non-Linux hosts report unknown rather than a guess`() {
        // macOS would need a diskutil subprocess and Windows WMI — both out of scope by design, so
        // the honest answer is UNKNOWN even though "modern Mac means NVMe" would usually be right.
        assertEquals(DiskMedia.UNKNOWN, classify(os = "Mac OS X", device = "/dev/disk3s1s1", type = "apfs"))
        assertEquals(DiskMedia.UNKNOWN, classify(os = "Windows 11", device = "C:\\", type = "NTFS"))
        assertEquals(DiskMedia.UNKNOWN, classify(os = null))
    }

    @Test
    fun `unresolvable devices report unknown`() {
        // Container overlayfs, LVM and dm-crypt are the common CI/desktop layouts with no direct
        // block device — each must fall through rather than produce a plausible-looking answer.
        assertEquals(DiskMedia.UNKNOWN, classify(device = "overlay", type = "overlay"))
        assertEquals(DiskMedia.UNKNOWN, classify(device = "/dev/mapper/vg--root"))
        assertEquals(DiskMedia.UNKNOWN, classify(device = ""))
        assertEquals(DiskMedia.UNKNOWN, classify(device = null))
    }

    @Test
    fun `an absent or unreadable rotational file reports unknown`() {
        assertEquals(DiskMedia.UNKNOWN, classify(sysfs = emptyMap()))
        assertEquals(DiskMedia.UNKNOWN, classify(sysfs = mapOf("/sys/block/sda/queue/rotational" to "garbage")))
    }

    @Test
    fun `a device that is all digits is never trimmed away to an empty lookup`() {
        assertEquals(DiskMedia.SSD, classify(device = "/dev/123", sysfs = mapOf("/sys/block/123/queue/rotational" to "0")))
    }
}

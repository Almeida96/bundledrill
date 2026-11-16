package io.github.almeida96.bundledrill

import com.fasterxml.jackson.databind.node.*
import java.nio.file.*
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RecoveryTest {
    private lateinit var root: Path
    private lateinit var demo: Path
    @BeforeAll fun fixture() {
        root = Files.createTempDirectory("bundledrill-test-")
        demo = root.resolve("demo"); demo(demo)
    }
    @AfterAll fun cleanup() = clean(root)
    private fun read(name: String) = readJson(demo.resolve(name), 1024 * 1024)
    private fun modified(name: String, edit: (ObjectNode) -> Unit): Path {
        val n = read("complete.json") as ObjectNode
        edit(n)
        return demo.resolve("$name.json").also { writeJson(it, n) }
    }

    @Test fun missingBaseLeavesIndependentBranchRecoverable() {
        val r = read("broken/report.json")
        assertEquals("FAIL", r["status"].asText())
        assertEquals(listOf("MISSING", "RECOVERED"), r["points"].map { it["state"].asText() })
        val archive = r["bundles"][0]
        assertEquals(archive["prerequisites"], archive["missing"])
        assertEquals(1, archive["missing"].size())
    }
    @Test fun reordersIncrementAndRestoresExactFile() {
        val out = root.resolve("kept")
        val r = drill(demo.resolve("complete.json"), out, true)
        assertEquals("PASS", r.status)
        assertEquals(listOf(3, 1, 2), r.bundles.map { it.appliedOrder })
        val g = Git(out.resolve("recovered.git"))
        assertEquals("sku,stock\nbolts,8", g.run("show", "refs/heads/main:inventory.csv"))
        assertEquals(r.points[0].commit, g.run("rev-parse", "refs/heads/main"))
        assertEquals("refs/heads/main", g.run("symbolic-ref", "HEAD"))
    }
    @Test fun strictPolicyAndDefaultHaveDifferentExitCodesForRedundantBrokenArchive() {
        fun code(key: String, vararg flags: String) = execute(arrayOf("drill", "--plan", demo.resolve("degraded.json").toString(),
            "--out", root.resolve(key).toString(), *flags))
        assertEquals(0, code("normal")); assertEquals(1, code("strict", "--strict"))
        assertEquals("DEGRADED", read("degraded/report.json")["status"].asText())
        assertFalse(Files.exists(root.resolve("normal/recovered.git")))
    }
    @Test fun historicCommitCanBePinnedUnderDifferentRecoveryName() {
        val base = read("complete/report.json")["bundles"][0]["prerequisites"][0].asText()
        val p = modified("historic") { ((it["points"] as ArrayNode)[0] as ObjectNode).put("ref", "refs/heads/yesterday").put("commit", base) }
        val r = drill(p, root.resolve("historic-out"), true)
        assertEquals("RECOVERED", r.points[0].state)
        assertEquals("sku,stock\nbolts,12", Git(root.resolve("historic-out/recovered.git")).run("show", "yesterday:inventory.csv"))
    }
    @Test fun checksumMismatchDoesNotLeavePartialReport() {
        val p = modified("mismatch") { (it["bundles"][1] as ObjectNode).put("sha256", "a".repeat(64)) }
        val out = root.resolve("bad-out")
        assertThrows(IllegalArgumentException::class.java) { drill(p, out, true) }
        assertFalse(Files.exists(out))
    }
    @Test fun corruptPackRejectedEvenWithMatchingFileDigest() {
        val bytes = Files.readAllBytes(demo.resolve("base.bundle"))
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 31).toByte()
        val bad = Files.write(demo.resolve("damaged.bundle"), bytes)
        val p = modified("damaged-plan") { (it["bundles"][2] as ObjectNode).put("path", bad.fileName.toString()).put("sha256", digest(bad).hex) }
        val out = root.resolve("damaged-out")
        assertThrows(IllegalStateException::class.java) { drill(p, out, false) }
        assertFalse(Files.exists(out))
    }
    @Test fun emptyBackupSetFailsEveryPoint() {
        val p = modified("empty") { it.putArray("bundles") }
        val r = drill(p, root.resolve("no-archives"), false)
        assertEquals("FAIL", r.status); assertTrue(r.points.all { it.state == "MISSING" })
    }
    @Test fun missingNamedFileYieldsEvidenceRatherThanAborting() {
        val p = modified("removed") { (it["bundles"][2] as ObjectNode).put("path", "removed/base.bundle") }
        val r = drill(p, root.resolve("missing-archive"), false)
        assertEquals("FAIL", r.status)
        assertEquals(listOf("BLOCKED", "APPLIED", "ABSENT"), r.bundles.map { it.state })
        assertTrue(r.bundles.last().bytes == null)
    }
    @Test fun existingEvidenceCannotBeOverwritten() {
        val out = demo.resolve("complete")
        val before = Files.readString(out.resolve("report.json"))
        assertThrows(IllegalArgumentException::class.java) { drill(demo.resolve("broken.json"), out, true) }
        assertEquals(before, Files.readString(out.resolve("report.json")))
    }
    @Test fun rejectsConflictingBranchPathsAndUnknownFormat() {
        val p = modified("conflict") { (it["points"][1] as ObjectNode).put("ref","refs/heads/main/x") }
        assertThrows(IllegalArgumentException::class.java) { loadPlan(p) }
        val q = modified("format") { it.put("objectFormat","md5") }
        assertThrows(IllegalArgumentException::class.java) { loadPlan(q) }
    }
    @Test fun sha256NativeRoundTrip() {
        val d = root.resolve("sha256"); demo(d, "sha256")
        val r = readJson(d.resolve("complete/report.json"), 1024 * 1024)
        assertEquals("PASS", r["status"].asText())
        assertEquals(64, r["points"][0]["tree"].asText().length)
        assertTrue(r["bundles"].all { it["version"].asInt() == 3 })
    }
    @Test fun filteredBundleIsNotACompleteBackup() {
        val f = Files.writeString(root.resolve("filtered.bundle"), "# v3 git bundle\n@object-format=sha1\n@filter=blob:none\n")
        assertThrows(IllegalArgumentException::class.java) { header(f, "sha1") }
    }
}

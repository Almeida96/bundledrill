package io.github.almeida96.bundledrill

import java.nio.file.*

internal data class Source(val path: Path, val hash: String)
internal data class Point(val ref: String, val commit: String)
internal data class Plan(val format: String, val sources: List<Source>, val points: List<Point>)

private fun branch(ref: String, git: Git) {
    require(ref.startsWith("refs/heads/") && ref.length <= 512 && ref.none { it.code < 32 || it.code == 127 }) { "Expected a full local branch ref" }
    git.run("check-ref-format", ref)
}
internal fun loadPlan(path: Path): Plan {
    val p = path.toAbsolutePath().normalize()
    val n = readJson(p, 1024 * 1024)
    n.exact("version", "objectFormat", "bundles", "points")
    require(n["version"].isIntegralNumber && n["version"].asText() == "1") { "Unsupported plan version" }
    val fmt = n.str("objectFormat")
    require(fmt in setOf("sha1", "sha256")) { "Unsupported Git object format" }
    require(n["bundles"].isArray && n["bundles"].size() <= 64) { "Expected at most 64 bundles" }
    require(n["points"].isArray && n["points"].size() in 1..128) { "Expected 1 to 128 recovery points" }
    val sources = n["bundles"].map {
        it.exact("path", "sha256")
        val hash = it.str("sha256")
        require(oid(hash, "sha256")) { "Invalid bundle SHA-256" }
        Source(p.parent.resolve(it.str("path")).normalize(), hash)
    }
    val git = Git(p.parent)
    val points = n["points"].map {
        it.exact("ref", "commit")
        val ref = it.str("ref"); branch(ref, git)
        val commit = it.str("commit")
        require(oid(commit, fmt)) { "Invalid pinned commit for $ref" }
        Point(ref, commit)
    }
    val refs = points.map { it.ref }
    require(refs.distinct().size == refs.size && refs.none { r -> refs.any { it.startsWith("$r/") } }) { "Conflicting recovery point refs" }
    return Plan(fmt, sources, points)
}
internal fun makePlan(repo: Path, branches: List<String>, bundles: List<Path>, output: Path) {
    val git = Git(repo.toAbsolutePath())
    val fmt = git.run("rev-parse", "--show-object-format")
    require(branches.size in 1..128 && bundles.size <= 64 && branches.distinct().size == branches.size) { "Provide 1–128 unique branches and up to 64 bundles" }
    val points = branches.map {
        val ref = "refs/heads/$it"; branch(ref, git)
        val commit = git.run("show-ref", "--verify", "--hash", ref)
        require(git.run("cat-file", "-t", commit) == "commit") { "$ref is not a commit" }
        Point(ref, commit)
    }
    val out = output.toAbsolutePath().normalize()
    var budget = FILE_LIMIT * 4
    val rows = bundles.map { file ->
        val p = file.toAbsolutePath().normalize()
        val rel = try { out.parent.relativize(p).toString() } catch (_: IllegalArgumentException) { p.toString() }
        val d = digest(p, limit = minOf(budget, FILE_LIMIT)); budget -= d.bytes
        mapOf("path" to rel.replace(java.io.File.separatorChar, '/'), "sha256" to d.hex)
    }
    Files.createDirectories(out.parent)
    writeJson(out, mapOf("version" to 1, "objectFormat" to fmt, "bundles" to rows, "points" to points))
}

internal data class Tip(val ref: String, val oid: String)
internal data class Header(val version: Int, val prerequisites: List<String>, val tips: List<Tip>)
internal fun header(file: Path, expectedFormat: String): Header = Files.newInputStream(file).buffered().use { stream ->
    var count = 0
    fun line(): String {
        val buf = java.io.ByteArrayOutputStream()
        while (true) {
            val c = stream.read()
            require(c >= 0 && ++count <= 1048576 && buf.size() <= 65536) { "Malformed or oversized bundle header" }
            if (c == 10) break
            buf.write(c)
        }
        return buf.toString(Charsets.UTF_8)
    }
    val ver = when (line()) { "# v2 git bundle" -> 2; "# v3 git bundle" -> 3; else -> error("Expected Git bundle v2 or v3") }
    var fmt = "sha1"; var seen = false
    val prereqs = linkedSetOf<String>(); val tips = mutableListOf<Tip>()
    var entries = 0
    while (true) {
        val s = line(); if (s.isEmpty()) break
        if (s.startsWith("@")) {
            require(!seen && entries == 0 && ver == 3 && s in setOf("@object-format=sha1", "@object-format=sha256")) { "Unsupported bundle capability" }
            fmt = s.substringAfter('='); seen = true; continue
        }
        require(++entries <= 2048) { "Too many header entries" }
        val pre = s.startsWith("-")
        val hash = s.removePrefix("-").substringBefore(' ')
        require(oid(hash, fmt)) { "Invalid object ID in bundle header" }
        if (pre) prereqs.add(hash) else {
            val ref = s.substringAfter(' ', "")
            require(ref.isNotEmpty() && ref.length <= 1024 && ref.none { it.code < 32 || it.code == 127 }) { "Invalid advertised ref" }
            tips += Tip(ref, hash)
        }
    }
    require(fmt == expectedFormat && tips.isNotEmpty()) { "Bundle object format mismatch or empty tip list" }
    require(stream.readNBytes(4).contentEquals("PACK".toByteArray())) { "Missing pack signature" }
    Header(ver, prereqs.toList(), tips)
}

package io.github.almeida96.bundledrill

import java.nio.file.*

internal data class Archive(val index: Int, val name: String, val sha256: String, val bytes: Long?,
    val version: Int?, val tips: List<Tip>, val prerequisites: List<String>,
    var state: String = "BLOCKED", var appliedOrder: Int? = null, var missing: List<String> = emptyList())
internal data class ResultPoint(val ref: String, val commit: String, val state: String, val tree: String?)
internal data class Report(val formatVersion: Int, val status: String, val objectFormat: String, val gitVersion: String,
    val fsck: String, val points: List<ResultPoint>, val bundles: List<Archive>)
private fun types(git: Git, ids: List<String>): Map<String, String> {
    if (ids.isEmpty()) return emptyMap()
    val rows = git.run("cat-file", "--batch-check=%(objectname) %(objecttype)", input = ids.joinToString("\n", postfix = "\n"))
    return rows.lineSequence().associate { it.substringBefore(' ') to it.substringAfter(' ') }
}
internal fun drill(planPath: Path, out: Path, keep: Boolean): Report {
    val plan = loadPlan(planPath)
    return outputDirectory(out) { stage ->
        val scratch = Files.createTempDirectory("bundledrill-")
        try {
            val repo = (if (keep) stage else scratch).resolve("recovered.git")
            Files.createDirectory(repo)
            val git = Git(repo)
            git.run("init", "--bare", "--quiet", "--template=", "--initial-branch=main", "--object-format=${plan.format}", ".")
            var budget = FILE_LIMIT * 4
            var headerEntries = 0
            val archives = plan.sources.mapIndexed { i, source ->
                if (Files.notExists(source.path)) return@mapIndexed Archive(i + 1, source.path.fileName.toString(),
                    source.hash, null, null, emptyList(), emptyList(), state = "ABSENT")
                val file = scratch.resolve("$i.bundle")
                val sum = digest(source.path, file, minOf(budget, FILE_LIMIT)); budget -= sum.bytes
                require(sum.hex == source.hash) { "Checksum mismatch for bundle #${i + 1}" }
                val h = header(file, plan.format)
                headerEntries += h.tips.size + h.prerequisites.size
                require(headerEntries <= 8192) { "Too many total bundle header entries" }
                Archive(i + 1, source.path.fileName.toString(), sum.hex, sum.bytes, h.version, h.tips, h.prerequisites)
            }
            var order = 0
            do {
                var progress = false
                for (a in archives.filter { it.state == "BLOCKED" }) {
                    val available = types(git, a.prerequisites)
                    a.missing = a.prerequisites.filter { available[it] != "commit" }
                    if (a.missing.isNotEmpty()) continue
                    val path = scratch.resolve("${a.index - 1}.bundle").toString()
                    git.run("bundle", "verify", path)
                    git.run("bundle", "unbundle", path)
                    // Keep prior tips reachable when Git verifies the prerequisites of a later bundle.
                    git.run("update-ref", "--stdin", input = a.tips.mapIndexed { j, tip ->
                        "create refs/bundledrill/imports/${a.index}/$j ${tip.oid}\n"
                    }.joinToString(""))
                    a.state = "APPLIED"; a.appliedOrder = ++order; progress = true
                }
            } while (progress && archives.any { it.state == "BLOCKED" })
            val available = types(git, plan.points.map { it.commit })
            val points = plan.points.map { p ->
                if (available[p.commit] != "commit") ResultPoint(p.ref, p.commit, "MISSING", null)
                else {
                    git.run("update-ref", p.ref, p.commit)
                    ResultPoint(p.ref, p.commit, "RECOVERED", git.run("rev-parse", "${p.commit}^{tree}"))
                }
            }
            points.firstOrNull { it.state == "RECOVERED" }?.let { git.run("symbolic-ref", "HEAD", it.ref) }
            git.run("fsck", "--full", "--strict", "--no-reflogs", "--no-progress", "--no-dangling")
            val status = when {
                points.any { it.state == "MISSING" } -> "FAIL"
                archives.any { it.state != "APPLIED" } -> "DEGRADED"
                else -> "PASS"
            }
            val report = Report(1, status, plan.format, git.run("--version"), "PASS", points, archives)
            writeJson(stage.resolve("report.json"), report)
            Files.writeString(stage.resolve("report.html"), render(report))
            report
        } finally { clean(scratch) }
    }
}
private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
private fun render(r: Report): String {
    val headline = when (r.status) { "PASS" -> "Every pinned commit was recovered"; "FAIL" -> "A pinned commit could not be recovered"; else -> "Recovery succeeded with unused backup gaps" }
    val points = r.points.joinToString("") {
        "<tr><td><b>${esc(it.ref)}</b><br><code>${it.commit}</code></td><td>${it.state}</td><td><code>${it.tree ?: "—"}</code></td></tr>"
    }
    val archives = r.bundles.joinToString("") {
        val reason = if (it.missing.isEmpty()) "—" else it.missing.joinToString("<br>") { id -> "<code>$id</code>" }
        val detail = if (it.bytes == null) "File not found" else "${it.bytes} bytes · bundle v${it.version}"
        "<tr><td>${esc(it.name)}<br><small>$detail</small></td><td>${it.state}</td><td>${it.appliedOrder ?: "—"}</td><td>$reason</td></tr>"
    }
    return """<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>BundleDrill · ${r.status}</title>
<style>*{box-sizing:border-box}body{margin:0;background:#f5f4ed;color:#172e31;font:16px/1.55 system-ui,sans-serif}main{max-width:1090px;margin:auto;padding:clamp(20px,5vw,68px)}header{border-bottom:2px solid #25494e;display:flex;justify-content:space-between;padding-bottom:12px}h1{font-size:clamp(32px,5vw,54px);line-height:1.1;letter-spacing:-.035em;max-width:800px;margin:.45em 0}h2{margin:36px 0 8px}article{border-left:5px solid ${if (r.status=="PASS") "#177552" else "#b65224"};padding-left:20px;margin:36px 0}table{border-collapse:collapse;width:100%;background:#fff}th,td{text-align:left;padding:11px 13px;border-bottom:1px solid #d5ded9;vertical-align:top}th{font-size:13px;color:#355a57;background:#e4ede7}code{font-size:12px;overflow-wrap:anywhere}section{overflow-x:auto}footer{margin:24px 0;color:#395651;font-size:14px}</style>
<main><header><b>BundleDrill</b><span>Git backup recovery drill</span></header><article><b>${r.status}</b><h1>$headline</h1><p>${r.points.count { it.state == "RECOVERED" }} of ${r.points.size} recovery points available · ${r.bundles.count { it.state=="APPLIED" }} of ${r.bundles.size} bundles applied</p></article>
<h2>Recovery points</h2><section><table><tr><th>Branch and pinned commit</th><th>Result</th><th>Recovered tree</th></tr>$points</table></section>
<h2>Backup chain</h2><section><table><tr><th>Bundle</th><th>Result</th><th>Replay order</th><th>Missing prerequisite commits</th></tr>$archives</table></section>
<footer>Fresh bare repository · git fsck --full --strict: ${r.fsck} · ${esc(r.gitVersion)} · ${r.objectFormat}<br>Missing prerequisites were checked against the objects restored in this drill. Blocked packs were not imported.</footer></main></html>"""
}

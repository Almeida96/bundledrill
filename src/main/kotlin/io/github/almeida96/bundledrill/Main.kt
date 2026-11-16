package io.github.almeida96.bundledrill

import java.nio.file.*
import kotlin.system.exitProcess

internal fun demo(out: Path, format: String = "sha1") = outputDirectory(out) { stage ->
    val src = Files.createTempDirectory("bundledrill-warehouse-")
    try {
        val g = Git(src, demo = true)
        g.run("init", "--initial-branch=main", "--object-format=$format", "--template=", ".")
        fun commit(file: String, content: String, message: String) {
            Files.writeString(src.resolve(file), content); g.run("add", "--", file); g.run("commit", "--quiet", "-m", message)
        }
        val base = stage.resolve("base.bundle"); val delta = stage.resolve("inventory-day2.bundle"); val handbook = stage.resolve("handbook.bundle")
        commit("inventory.csv", "sku,stock\nbolts,12\n", "Record opening stock")
        val first = g.run("rev-parse", "HEAD")
        g.run("bundle", "create", base.toString(), "refs/heads/main")
        commit("inventory.csv", "sku,stock\nbolts,8\n", "Record four bolts shipped")
        g.run("bundle", "create", delta.toString(), "refs/heads/main", "^$first")
        g.run("switch", "--orphan", "handbook")
        commit("handbook.md", "# Receiving\nCount stock before signing the delivery note.\n", "Document receiving procedure")
        g.run("bundle", "create", handbook.toString(), "refs/heads/handbook")
        for (scenario in listOf("broken", "complete", "degraded")) {
            val input = stage.resolve("$scenario.json")
            val branches = if (scenario == "degraded") listOf("handbook") else listOf("main", "handbook")
            makePlan(src, branches, if (scenario == "complete") listOf(delta, handbook, base) else listOf(delta, handbook), input)
            drill(input, stage.resolve(scenario), false)
        }
    } finally { clean(src) }
}
private const val USAGE = """BundleDrill 0.1.0
  bundledrill plan --repo DIR --branch NAME [--branch NAME ...] [--bundle FILE ...] --out PLAN.json
  bundledrill drill --plan PLAN.json --out NEW_DIR [--keep-repo] [--strict]
  bundledrill demo --out NEW_DIR
  bundledrill mcp --report REPORT.json
Exit: 0 success, 1 unmet recovery policy, 2 invalid input or execution error."""
private class Args(raw: List<String>, allowed: Set<String>, flags: Set<String> = emptySet()) {
    val map = mutableMapOf<String, MutableList<String>>()
    init {
        var i = 0
        while (i < raw.size) {
            val key = raw[i++]
            require(key in allowed || key in flags) { "Unknown option: $key" }
            val value = if (key in flags) "" else {
                require(i < raw.size && !raw[i].startsWith("--")) { "Missing value for $key" }; raw[i++]
            }
            val bucket = map.getOrPut(key) { mutableListOf() }
            require(bucket.isEmpty() || key in setOf("--branch", "--bundle")) { "Duplicate option: $key" }
            bucket += value
        }
    }
    fun path(key: String) = Path.of(requireNotNull(map[key]?.singleOrNull()) { "Missing $key" }).toAbsolutePath().normalize()
}
internal fun execute(raw: Array<String>): Int {
    val rest = raw.drop(1)
    when (raw.firstOrNull()) {
        "--help", "-h", "help", null -> println(USAGE)
        "--version" -> println("BundleDrill 0.1.0")
        "plan" -> {
            val a = Args(rest, setOf("--repo", "--branch", "--bundle", "--out"))
            makePlan(a.path("--repo"), a.map["--branch"].orEmpty(), a.map["--bundle"].orEmpty().map { Path.of(it) }, a.path("--out"))
            println("Wrote recovery plan: ${a.path("--out")}")
        }
        "demo" -> {
            val out = Args(rest, setOf("--out")).path("--out"); demo(out)
            println("broken: FAIL; complete: PASS; degraded: DEGRADED\nOpen $out/complete/report.html")
        }
        "drill" -> {
            val a = Args(rest, setOf("--plan", "--out"), setOf("--keep-repo", "--strict"))
            val r = drill(a.path("--plan"), a.path("--out"), "--keep-repo" in a.map)
            println("${r.status}: ${r.points.count { it.state == "RECOVERED" }}/${r.points.size} recovery points; ${a.path("--out")}/report.json")
            return if (r.status == "FAIL" || r.status == "DEGRADED" && "--strict" in a.map) 1 else 0
        }
        "mcp" -> Mcp.serve(readJson(Args(rest, setOf("--report")).path("--report"), 64 * 1024 * 1024), System.`in`, System.out)
        else -> error("Unknown command. Use --help.")
    }
    return 0
}
fun main(args: Array<String>): Unit = exitProcess(try { execute(args) } catch (e: Exception) {
    System.err.println("bundledrill: ${e.message ?: e.javaClass.simpleName}"); 2
})

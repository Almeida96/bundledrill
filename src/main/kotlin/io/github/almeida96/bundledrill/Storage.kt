package io.github.almeida96.bundledrill

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import java.io.*
import java.nio.file.*
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.TimeUnit

internal val json = ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
internal fun readJson(p: Path, max: Int): JsonNode = Files.newInputStream(p).use {
    val bytes = it.readNBytes(max + 1)
    require(bytes.size <= max) { "JSON exceeds $max bytes" }
    requireNotNull(json.readTree(bytes)) { "Empty JSON" }
}
internal fun writeJson(p: Path, obj: Any) {
    Files.writeString(p, json.writerWithDefaultPrettyPrinter().writeValueAsString(obj) + "\n", StandardOpenOption.CREATE_NEW)
}
internal fun clean(p: Path) {
    if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)) return
    Files.walk(p).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { f ->
        try { Files.delete(f) } catch (e: AccessDeniedException) {
            if (Files.isSymbolicLink(f) || !f.toFile().setWritable(true)) throw e
            Files.delete(f)
        }
    } }
}
internal fun <T> outputDirectory(path: Path, body: (Path) -> T): T {
    val out = path.toAbsolutePath().normalize()
    require(!Files.exists(out, LinkOption.NOFOLLOW_LINKS)) { "Output already exists: $out" }
    Files.createDirectories(out.parent)
    val staging = Files.createTempDirectory(out.parent, ".bundledrill-")
    try {
        val result = body(staging)
        Files.move(staging, out)
        return result
    } finally { clean(staging) }
}
internal data class Digest(val hex: String, val bytes: Long)
internal const val FILE_LIMIT = 1024L * 1024 * 1024
internal fun digest(path: Path, to: Path? = null, limit: Long = FILE_LIMIT): Digest {
    require(Files.isRegularFile(path)) { "Expected a regular bundle: $path" }
    require(Files.size(path) <= limit) { "Bundle input limit exceeded" }
    val md = MessageDigest.getInstance("SHA-256")
    var size = 0L
    (if (to == null) OutputStream.nullOutputStream() else Files.newOutputStream(to, StandardOpenOption.CREATE_NEW)).use { out ->
        Files.newInputStream(path).buffered().use { src ->
            val buf = ByteArray(65536)
            while (true) {
                val n = src.read(buf)
                if (n == -1) break
                size += n
                require(size <= limit) { "Bundle input limit exceeded" }
                md.update(buf, 0, n)
                out.write(buf, 0, n)
            }
        }
    }
    return Digest(HexFormat.of().formatHex(md.digest()), size)
}
internal class Git(private val directory: Path, private val demo: Boolean = false) {
    fun run(vararg args: String, input: String = ""): String {
        val command = listOf("git", "--no-replace-objects", "-c", "core.hooksPath=/dev/null", "-c", "gc.auto=0",
            "-c", "core.autocrlf=false", "-c", "commit.gpgsign=false", "-C", directory.toString()) + args
        val pb = ProcessBuilder(command)
        pb.environment().apply {
            keys.removeIf { it.startsWith("GIT_", true) }
            put("GIT_CONFIG_NOSYSTEM", "1"); put("GIT_CONFIG_GLOBAL", if (File.separatorChar == '\\') "NUL" else "/dev/null")
            put("GIT_ATTR_NOSYSTEM", "1"); put("GIT_TERMINAL_PROMPT", "0"); put("GIT_ALLOW_PROTOCOL", "")
            put("LC_ALL", "C")
            if (demo) for (role in listOf("AUTHOR", "COMMITTER")) {
                put("GIT_${role}_NAME", "Warehouse fixture"); put("GIT_${role}_EMAIL", "fixture@example.invalid")
                put("GIT_${role}_DATE", "2025-01-01T12:00:00Z")
            }
        }
        val p = pb.start()
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        fun pump(src: InputStream, dest: ByteArrayOutputStream) = Thread.ofVirtual().start {
            src.use { stream -> val buf = ByteArray(8192)
                while (true) { val n = stream.read(buf); if (n < 0) break
                    dest.write(buf, 0, minOf(n, (1048577 - dest.size()).coerceAtLeast(0))) }
            }
        }
        val stdout = pump(p.inputStream, out); val stderr = pump(p.errorStream, err)
        val stdin = Thread.ofVirtual().start {
            try { p.outputStream.use { it.write(input.toByteArray()) } } catch (_: IOException) { }
        }
        try {
            check(p.waitFor(120, TimeUnit.SECONDS)) { "Git timed out during ${args.first()}" }
            stdout.join(); stderr.join(); stdin.join()
            check(p.exitValue() == 0) { "git ${args.first()} failed: ${err.toString(Charsets.UTF_8).take(2048).trim()}" }
            check(out.size() <= 1048576) { "Git output limit exceeded" }
            return out.toString(Charsets.UTF_8).trimEnd('\r', '\n')
        } finally { if (p.isAlive) { p.descendants().forEach { it.destroyForcibly() }; p.destroyForcibly() } }
    }
}
internal fun JsonNode.exact(vararg keys: String) {
    require(isObject && fieldNames().asSequence().toSet() == keys.toSet()) { "Expected fields: ${keys.joinToString()}" }
}
internal fun JsonNode.str(key: String): String {
    val v = get(key)
    require(v?.isTextual == true && v.asText().isNotBlank()) { "Expected string: $key" }
    return v.asText()
}
internal fun oid(s: String, fmt: String) = s.length == (if (fmt == "sha1") 40 else 64) && s.all { it in "0123456789abcdef" }

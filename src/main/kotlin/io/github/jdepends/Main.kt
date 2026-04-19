package io.github.jdepends

import kotlinx.serialization.json.*
import org.fusesource.jansi.Ansi.ansi
import org.fusesource.jansi.AnsiConsole
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

fun main(args: Array<String>) {
    if (System.console() != null) System.setProperty("jansi.force", "true")
    AnsiConsole.systemInstall()
    if (args.isEmpty()) {
        System.err.println("Usage: jdepends <groupId>:<artifactId>[:<version>]")
        System.err.println("  e.g. jdepends io.micronaut:micronaut-http-server-netty")
        System.err.println("  e.g. jdepends org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
        System.exit(1)
    }

    val input = args[0].trim()
    val parts = input.split(":")

    if (parts.size < 2) {
        System.err.println("Invalid input '$input'. Expected <groupId>:<artifactId>[:<version>]")
        System.exit(1)
    }

    val groupId = parts[0]
    val artifactId = parts[1]
    val suppliedVersion = if (parts.size >= 3) parts[2] else null

    val versions = fetchVersions(groupId, artifactId)

    if (versions.isEmpty()) {
        System.err.println("No versions found for $groupId:$artifactId")
        System.exit(1)
    }

    val sorted = versions.sortedWith(VersionComparator.reversed())
    val recent = sorted.take(8)

    val latestVersion = sorted.firstOrNull { VersionComparator.isStableRelease(it) }
        ?: recent.first()
    val latestFormatted = if (suppliedVersion != null && suppliedVersion == latestVersion)
        ansi().bold().fgBright(org.fusesource.jansi.Ansi.Color.GREEN).a(latestVersion).reset()
    else
        ansi().bold().fgBright(org.fusesource.jansi.Ansi.Color.WHITE).a(latestVersion).reset()
    val rest = if (recent.size > 1) ", " + recent.drop(1).joinToString(", ") {
        ansi().fgBright(org.fusesource.jansi.Ansi.Color.BLACK).a(it).reset().toString()
    } else ""
    val suppliedFormatted = if (suppliedVersion != null) {
        val color = if (suppliedVersion == latestVersion)
            org.fusesource.jansi.Ansi.Color.GREEN
        else
            org.fusesource.jansi.Ansi.Color.WHITE
        ":" + ansi().bold().fgBright(color).a(suppliedVersion).reset()
    } else ""
    println("$groupId:$artifactId$suppliedFormatted [$latestFormatted$rest]")
}

private fun fetchVersions(groupId: String, artifactId: String): List<String> {
    val query = URLEncoder.encode("""g:"$groupId" AND a:"$artifactId"""", Charsets.UTF_8)
    val url = "https://search.maven.org/solrsearch/select?q=$query&core=gav&rows=200&wt=json"

    val client = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    val request = HttpRequest.newBuilder()
        .uri(URI.create(url))
        .header("User-Agent", "jdepends/1.0")
        .GET()
        .build()

    val response = client.send(request, HttpResponse.BodyHandlers.ofString())

    if (response.statusCode() != 200) {
        error("Maven Central returned HTTP ${response.statusCode()}")
    }

    val json = Json.parseToJsonElement(response.body())
    val docs = json.jsonObject["response"]
        ?.jsonObject?.get("docs")
        ?.jsonArray
        ?: return emptyList()

    return docs.mapNotNull { it.jsonObject["v"]?.jsonPrimitive?.content }
}

/**
 * Compares version strings like "1.2.3", "2.0.0-RC1", "4.3.3.Final", etc.
 * Non-numeric suffixes (RC, alpha, beta, M) sort before their release counterpart.
 */
object VersionComparator : Comparator<String> {
    private val QUALIFIER_ORDER = mapOf(
        "alpha" to -4, "beta" to -3, "m" to -2, "rc" to -1,
        "cr" to -1, "snapshot" to -5, "final" to 0, "ga" to 0, "release" to 0
    )

    // Qualifiers that mark a version as a pre-release / non-stable build.
    // Checked as: full segment, leading letters before digits ("m4" → "m"), or
    // inline letters after leading digits ("2b3" → "b").
    private val UNSTABLE_QUALIFIERS = setOf(
        "alpha", "a", "beta", "b", "milestone", "m", "rc", "cr",
        "snapshot", "pre", "preview", "dev", "nightly"
    )

    /** Returns true when the version looks like a stable/final release. */
    fun isStableRelease(version: String): Boolean {
        val segments = version.lowercase().split(".", "-", "_").filter { it.isNotEmpty() }
        return segments.none { seg ->
            // Whole segment is a qualifier: "alpha", "snapshot", "rc", …
            seg in UNSTABLE_QUALIFIERS ||
            // Leading letters before the first digit: "m4" → "m", "rc1" → "rc"
            seg.takeWhile { !it.isDigit() }.let { it.isNotEmpty() && it in UNSTABLE_QUALIFIERS } ||
            // Letters after leading digits (inline qualifier): "2b3" → "b"
            seg.dropWhile { it.isDigit() }.takeWhile { !it.isDigit() }.let { it.isNotEmpty() && it in UNSTABLE_QUALIFIERS }
        }
    }

    override fun compare(a: String, b: String): Int {
        val pa = parse(a)
        val pb = parse(b)
        val len = maxOf(pa.size, pb.size)
        for (i in 0 until len) {
            val va = pa.getOrNull(i) ?: Pair(0, 0)
            val vb = pb.getOrNull(i) ?: Pair(0, 0)
            val cmp = compareBy<Pair<Int, Int>>({ it.first }, { it.second }).compare(va, vb)
            if (cmp != 0) return cmp
        }
        return 0
    }

    /** Returns list of (numericPart, qualifierWeight) pairs for each segment. */
    private fun parse(version: String): List<Pair<Int, Int>> {
        return version.split(".", "-", "_")
            .filter { it.isNotEmpty() }
            .map { segment ->
                val numeric = segment.takeWhile { it.isDigit() }
                val qualifier = segment.dropWhile { it.isDigit() }.lowercase()
                val num = numeric.toIntOrNull() ?: 0
                val weight = if (qualifier.isEmpty()) 0 else QUALIFIER_ORDER[qualifier] ?: 0
                Pair(num, weight)
            }
    }
}

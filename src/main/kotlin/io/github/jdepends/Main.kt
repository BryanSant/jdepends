package io.github.jdepends

import org.fusesource.jansi.Ansi.ansi
import org.fusesource.jansi.AnsiConsole
import org.w3c.dom.Element
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import javax.xml.parsers.DocumentBuilderFactory

fun main(args: Array<String>) {
    if (System.console() != null) System.setProperty("jansi.force", "true")
    AnsiConsole.systemInstall()

    val allFlag = "-a" in args || "--all" in args
    val positionalArgs = args.filter { it != "-a" && it != "--all" }

    if (positionalArgs.isEmpty()) {
        val plugins = detectGradlePlugins()
        val deps = if (allFlag) detectAndResolveDependencies() else detectDeclaredDependencies()
        if (plugins.isNotEmpty() || deps != null) {
            if (plugins.isEmpty() && (deps == null || deps.isEmpty())) {
                System.err.println("No dependencies found")
                return
            }
            plugins.forEach { (id, version) -> checkPlugin(id, version) }
            deps?.forEach { (groupId, artifactId, version) -> checkDependency(groupId, artifactId, version) }
            return
        }
        System.err.println("Usage: jdepends [-a|--all] <groupId>:<artifactId>[:<version>]")
        System.err.println("  -a, --all  Check all resolved/transitive dependencies (not just declared)")
        System.err.println("  e.g. jdepends io.micronaut:micronaut-http-server-netty")
        System.err.println("  e.g. jdepends org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
        System.err.println("  Or run in a directory with build.gradle[.kts] or pom.xml")
        System.exit(1)
    }

    val input = positionalArgs[0].trim()

    if (!input.contains(':')) {
        checkPlugin(input, null)
        return
    }

    val parts = input.split(":")
    checkDependency(parts[0], parts[1], if (parts.size >= 3) parts[2] else null)
}

private data class Dependency(val groupId: String, val artifactId: String, val version: String?)
private data class PluginDep(val id: String, val version: String?)

private fun detectAndResolveDependencies(): List<Dependency>? {
    val dir = File(".")
    return when {
        dir.resolve("build.gradle.kts").exists() || dir.resolve("build.gradle").exists() ->
            runGradle()
        dir.resolve("pom.xml").exists() ->
            runMaven()
        else -> null
    }
}

private fun detectDeclaredDependencies(): List<Dependency>? {
    val dir = File(".")
    val buildKts = dir.resolve("build.gradle.kts")
    val buildGroovy = dir.resolve("build.gradle")
    val pom = dir.resolve("pom.xml")

    val buildDeps: List<Dependency>? = when {
        buildKts.exists() -> parseGradleDeclaredDependencies(buildKts)
        buildGroovy.exists() -> parseGradleDeclaredDependencies(buildGroovy)
        pom.exists() -> parseMavenDeclaredDependencies(pom)
        else -> null
    }

    val catalog = findVersionCatalog(dir)
    if (catalog != null) {
        val (catalogDeps, _) = parseVersionCatalog(catalog)
        return ((buildDeps ?: emptyList()) + catalogDeps)
            .distinctBy { "${it.groupId}:${it.artifactId}" }
    }

    return buildDeps
}

private fun parseGradleDeclaredDependencies(file: File): List<Dependency> {
    val content = file.readText()

    // Find the dependencies block using balanced brace matching
    val startMatch = Regex("""(?m)^dependencies\s*\{""").find(content) ?: return emptyList()
    val blockStart = startMatch.range.last
    var depth = 1
    var i = blockStart + 1
    while (i < content.length && depth > 0) {
        when (content[i]) {
            '{' -> depth++
            '}' -> depth--
        }
        i++
    }
    val depsBlock = content.substring(blockStart + 1, i - 1)

    val result = mutableListOf<Dependency>()
    val plainVersion = Regex("""[A-Za-z0-9._\-+]+""")

    // Match coordinate strings: "group:artifact:version" or "group:artifact"
    val coordRe = Regex("""["']([A-Za-z0-9._-]+:[A-Za-z0-9._-]+)(?::([^"']+))?["']""")
    coordRe.findAll(depsBlock).forEach { m ->
        val parts = m.groupValues[1].split(":")
        if (parts.size == 2) {
            val rawVersion = m.groupValues[2].ifEmpty { null }
            // Skip version if it's a variable/expression (contains $, spaces, etc.)
            val version = if (rawVersion != null && plainVersion.matches(rawVersion)) rawVersion else null
            result += Dependency(parts[0], parts[1], version)
        }
    }

    return result.distinctBy { "${it.groupId}:${it.artifactId}" }
}

// ── Version catalog (gradle/libs.versions.toml) ──────────────────────────────

private fun findVersionCatalog(startDir: File = File(".")): File? {
    var dir = startDir.canonicalFile
    while (true) {
        val catalog = dir.resolve("gradle/libs.versions.toml")
        if (catalog.exists()) return catalog
        val parent = dir.parentFile ?: return null
        if (parent == dir) return null
        dir = parent
    }
}

private fun parseVersionCatalog(file: File): Pair<List<Dependency>, List<PluginDep>> {
    val versions = mutableMapOf<String, String>()
    val libraries = mutableListOf<Dependency>()
    val plugins = mutableListOf<PluginDep>()
    var section = ""

    for (rawLine in file.readLines()) {
        val line = rawLine.trim()
        if (line.startsWith("#") || line.isEmpty()) continue

        val sectionMatch = Regex("""^\[([^\]]+)]""").find(line)
        if (sectionMatch != null) {
            section = sectionMatch.groupValues[1].trim()
            continue
        }

        val eqIdx = line.indexOf('=')
        if (eqIdx < 0) continue
        val key = line.substring(0, eqIdx).trim()
        val value = line.substring(eqIdx + 1).trim()

        when (section) {
            "versions" -> versions[key] = value.trim('"', '\'')
            "libraries" -> parseCatalogLibrary(value, versions)?.let { libraries += it }
            "plugins" -> parseCatalogPlugin(value, versions)?.let { plugins += it }
        }
    }

    return libraries.distinctBy { "${it.groupId}:${it.artifactId}" } to
        plugins.distinctBy { it.id }
}

private fun parseCatalogLibrary(value: String, versions: Map<String, String>): Dependency? {
    if (value.startsWith("\"") || value.startsWith("'")) {
        // String notation: "group:artifact:version"
        val parts = value.trim('"', '\'').split(":")
        if (parts.size < 2) return null
        return Dependency(parts[0], parts[1], parts.getOrNull(2))
    }
    if (!value.startsWith("{")) return null
    val module = extractTomlField(value, "module")
    val group  = extractTomlField(value, "group")
    val name   = extractTomlField(value, "name")
    val (groupId, artifactId) = if (module != null) {
        val parts = module.split(":")
        if (parts.size < 2) return null
        parts[0] to parts[1]
    } else if (group != null && name != null) {
        group to name
    } else return null
    val version = extractTomlField(value, "version.ref")?.let { versions[it] }
        ?: extractTomlField(value, "version")
    return Dependency(groupId, artifactId, version)
}

private fun parseCatalogPlugin(value: String, versions: Map<String, String>): PluginDep? {
    if (!value.startsWith("{")) return null
    val id = extractTomlField(value, "id") ?: return null
    val version = extractTomlField(value, "version.ref")?.let { versions[it] }
        ?: extractTomlField(value, "version")
    return PluginDep(id, version)
}

/** Extracts a quoted string value for [fieldName] from a TOML inline table.
 *  When [fieldName] is "version", avoids matching "version.ref". */
private fun extractTomlField(inlineTable: String, fieldName: String): String? {
    val pattern = if (fieldName == "version")
        """version(?!\.ref)\s*=\s*["']([^"']+)["']"""
    else
        """${Regex.escape(fieldName)}\s*=\s*["']([^"']+)["']"""
    return Regex(pattern).find(inlineTable)?.groupValues?.get(1)
}

private fun parseMavenDeclaredDependencies(file: File): List<Dependency> {
    return try {
        val factory = DocumentBuilderFactory.newInstance().also { it.isNamespaceAware = false }
        val doc = factory.newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName("dependency")
        (0 until nodes.length).mapNotNull { i ->
            val dep = nodes.item(i) as Element
            if (dep.parentNode?.parentNode?.nodeName == "dependencyManagement") return@mapNotNull null
            val groupId = dep.getElementsByTagName("groupId").item(0)?.textContent ?: return@mapNotNull null
            val artifactId = dep.getElementsByTagName("artifactId").item(0)?.textContent ?: return@mapNotNull null
            val version = dep.getElementsByTagName("version").item(0)?.textContent
            // Skip property-reference versions like ${some.version}
            val resolvedVersion = if (version != null && !version.contains('$')) version else null
            Dependency(groupId, artifactId, resolvedVersion)
        }.distinctBy { "${it.groupId}:${it.artifactId}" }
    } catch (e: Exception) {
        System.err.println("Failed to parse pom.xml: ${e.message}")
        emptyList()
    }
}

// ── Gradle plugins ────────────────────────────────────────────────────────────

private fun detectGradlePlugins(): List<PluginDep> {
    val dir = File(".")
    val buildFile = dir.resolve("build.gradle.kts").takeIf { it.exists() }
        ?: dir.resolve("build.gradle").takeIf { it.exists() }

    val buildPlugins = buildFile?.let { parseGradlePlugins(it) } ?: emptyList()

    val catalog = findVersionCatalog(dir)
    if (catalog != null) {
        val (_, catalogPlugins) = parseVersionCatalog(catalog)
        return (buildPlugins + catalogPlugins).distinctBy { it.id }
    }

    return buildPlugins
}

private fun parseGradlePlugins(file: File): List<PluginDep> {
    val content = file.readText()
    val pluginsBlock = Regex("""plugins\s*\{([^}]*)\}""", RegexOption.DOT_MATCHES_ALL)
        .find(content)?.groupValues?.get(1) ?: return emptyList()

    val result = mutableListOf<PluginDep>()

    // id("pluginId") version "version"  (Kotlin DSL)
    Regex("""id\(["']([^"']+)["']\)\s+version\s+["']([^"']+)["']""")
        .findAll(pluginsBlock)
        .forEach { result += PluginDep(it.groupValues[1], it.groupValues[2]) }

    // kotlin("suffix") version "version"  →  org.jetbrains.kotlin.<suffix>
    Regex("""kotlin\(["']([^"']+)["']\)\s+version\s+["']([^"']+)["']""")
        .findAll(pluginsBlock)
        .forEach { result += PluginDep("org.jetbrains.kotlin.${it.groupValues[1]}", it.groupValues[2]) }

    // id 'pluginId' version 'version'  (Groovy DSL)
    if (!file.name.endsWith(".kts")) {
        Regex("""id\s+["']([^"']+)["']\s+version\s+["']([^"']+)["']""")
            .findAll(pluginsBlock)
            .forEach { m ->
                if (result.none { it.id == m.groupValues[1] })
                    result += PluginDep(m.groupValues[1], m.groupValues[2])
            }
    }

    return result.distinctBy { it.id }
}

// ── Gradle ────────────────────────────────────────────────────────────────────

private fun runGradle(): List<Dependency> {
    val cmd = if (File("gradlew").exists()) "./gradlew" else "gradle"
    // runtimeClasspath captures the actual resolved deps; fall back to all configs if unavailable
    for (args in listOf(
        listOf(cmd, "dependencies", "--configuration", "runtimeClasspath"),
        listOf(cmd, "dependencies", "--configuration", "compileClasspath"),
        listOf(cmd, "dependencies"),
    )) {
        val process = ProcessBuilder(args)
            .directory(File("."))
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        if (process.waitFor() == 0) return parseGradleOutput(output)
        if ("Configuration with name" !in output) {
            System.err.println("gradle dependencies failed:\n$output")
            return emptyList()
        }
        // configuration not found — try next fallback
    }
    return emptyList()
}

private fun parseGradleOutput(output: String): List<Dependency> {
    // Matches tree lines: "+--- group:artifact:version" and variants:
    //   - "group:artifact:version -> resolvedVersion"   (conflict-resolved)
    //   - "group:artifact:{strictly X} -> resolvedVersion"  (rich version)
    //   - trailing "(*)","(c)","(n)" markers
    val coordRe = Regex("""--- ([A-Za-z0-9._-]+:[A-Za-z0-9._-]+):([^\s(]+)(?:\s+->\s+([A-Za-z0-9._\-+]+))?""")
    val validVersion = Regex("""[A-Za-z0-9._\-+]+""")

    return output.lines()
        .filter { "--- " in it }
        .mapNotNull { line ->
            // (n) = unresolved declaration, (c) = BOM constraint — skip both
            if (line.trimEnd().endsWith("(n)") || line.trimEnd().endsWith("(c)")) return@mapNotNull null
            val m = coordRe.find(line) ?: return@mapNotNull null
            val coord = m.groupValues[1].split(":")
            val declared = m.groupValues[2]
            val resolved = m.groupValues[3].ifEmpty { null }
            // Prefer the resolved version; fall back to declared only if it's a plain version string
            val version = resolved ?: run {
                if (validVersion.matches(declared)) declared else return@mapNotNull null
            }
            Dependency(coord[0], coord[1], version)
        }
        .distinctBy { "${it.groupId}:${it.artifactId}" }
}

// ── Maven ─────────────────────────────────────────────────────────────────────

private fun runMaven(): List<Dependency> {
    val cmd = if (File("mvnw").exists()) "./mvnw" else "mvn"
    val process = ProcessBuilder(cmd, "help:effective-pom")
        .directory(File("."))
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().readText()
    if (process.waitFor() != 0) {
        System.err.println("mvn help:effective-pom failed:\n$output")
        return emptyList()
    }
    return parseMavenEffectivePom(output)
}

private fun parseMavenEffectivePom(output: String): List<Dependency> {
    // The command prints Maven log lines before/after the XML — extract just the XML.
    val xmlStart = output.indexOf("<?xml")
    if (xmlStart == -1) return emptyList()
    val xmlEnd = output.indexOf("</project>", xmlStart)
    if (xmlEnd == -1) return emptyList()
    val xml = output.substring(xmlStart, xmlEnd + "</project>".length)

    return try {
        val factory = DocumentBuilderFactory.newInstance().also { it.isNamespaceAware = false }
        val doc = factory.newDocumentBuilder().parse(xml.byteInputStream())
        val nodes = doc.getElementsByTagName("dependency")
        (0 until nodes.length).mapNotNull { i ->
            val dep = nodes.item(i) as Element
            // Skip entries inside <dependencyManagement> — those are version constraints, not real deps
            if (dep.parentNode?.parentNode?.nodeName == "dependencyManagement") return@mapNotNull null
            val groupId = dep.getElementsByTagName("groupId").item(0)?.textContent ?: return@mapNotNull null
            val artifactId = dep.getElementsByTagName("artifactId").item(0)?.textContent ?: return@mapNotNull null
            val version = dep.getElementsByTagName("version").item(0)?.textContent
            Dependency(groupId, artifactId, version)
        }.distinctBy { "${it.groupId}:${it.artifactId}" }
    } catch (e: Exception) {
        System.err.println("Failed to parse effective POM: ${e.message}")
        emptyList()
    }
}

// ── Plugin version check ──────────────────────────────────────────────────────

private fun fetchLatestPluginVersion(pluginId: String): String? {
    val url = "https://plugins.gradle.org/plugin/$pluginId"
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
        System.err.println("plugins.gradle.org returned HTTP ${response.statusCode()} for $pluginId")
        return null
    }
    return Regex("""<p[^>]*\bid=["']?plugin-id-version["']?[^>]*>([^<]+)</p>""")
        .find(response.body())?.groupValues?.get(1)?.trim()
}

private fun checkPlugin(pluginId: String, currentVersion: String?) {
    val latestVersion = fetchLatestPluginVersion(pluginId) ?: run {
        System.err.println("No version found for plugin $pluginId")
        return
    }
    val latestFormatted = if (currentVersion == latestVersion)
        ansi().bold().fgBright(org.fusesource.jansi.Ansi.Color.GREEN).a(latestVersion).reset()
    else
        ansi().bold().fgBright(org.fusesource.jansi.Ansi.Color.WHITE).a(latestVersion).reset()
    val suppliedFormatted = if (currentVersion != null) {
        val color = if (currentVersion == latestVersion)
            org.fusesource.jansi.Ansi.Color.GREEN
        else
            org.fusesource.jansi.Ansi.Color.WHITE
        ":" + ansi().bold().fgBright(color).a(currentVersion).reset()
    } else ""
    println("🔌 $pluginId$suppliedFormatted [$latestFormatted]")
}

// ── Version check ─────────────────────────────────────────────────────────────

private fun checkDependency(groupId: String, artifactId: String, suppliedVersion: String?) {
    val versions = fetchVersions(groupId, artifactId)
    if (versions.isEmpty()) {
        System.err.println("No versions found for $groupId:$artifactId")
        return
    }

    val sorted = versions.sortedWith(VersionComparator.reversed())
    val recent = sorted.take(8)

    val latestVersion = sorted.firstOrNull { VersionComparator.isStableRelease(it) } ?: recent.first()
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
    println("📦 $groupId:$artifactId$suppliedFormatted [$latestFormatted$rest]")
}

private fun fetchVersions(groupId: String, artifactId: String): List<String> {
    val groupPath = groupId.replace('.', '/')
    val url = "https://repo1.maven.org/maven2/$groupPath/$artifactId/maven-metadata.xml"

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
        System.err.println("Maven Central returned HTTP ${response.statusCode()} for $groupId:$artifactId")
        return emptyList()
    }

    return try {
        val factory = DocumentBuilderFactory.newInstance().also { it.isNamespaceAware = false }
        val doc = factory.newDocumentBuilder().parse(response.body().byteInputStream())
        val nodes = doc.getElementsByTagName("version")
        (0 until nodes.length).map { nodes.item(it).textContent }
    } catch (e: Exception) {
        System.err.println("Failed to parse metadata for $groupId:$artifactId: ${e.message}")
        emptyList()
    }
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

package com.nuvio.app.features.downloads

import java.io.File

/**
 * Fork: find a handed-off file by episode identity, not just by the name
 * Nuvio predicted. Download managers (Gopeed 1.9.x) name files from the
 * link or the host's disposition, so the stub matches on:
 * 1. the stored URI / predicted names (exact, via [resolveLocalFileUri]),
 * 2. a single unambiguous file whose normalized name carries the
 *    season+episode token plus title words.
 * Ambiguity (several candidates) resolves to null — Waiting, not wrong.
 */
internal fun findExternalFile(item: DownloadItem): String? {
    DownloadsPlatformDownloader.resolveLocalFileUri(item.localFileUri, item.fileName)?.let { return it }

    val roots = externalSearchRoots()
    if (roots.isEmpty()) return null
    val wanted = ExternalFileIdentity(item)
    if (!wanted.valid) return null

    // Exact predicted-name hit anywhere in the search roots.
    val exactNames = setOf(
        File(item.fileName).name,
        item.localFileUri?.substringAfterLast('/').orEmpty(),
    ).filter { it.isNotBlank() }
    for ((root, _) in roots) {
        for (name in exactNames) {
            File(root, name).takeIf { it.isFile }?.let { return it.toURI().toString() }
        }
    }

    // Fork: fuzzy scan is throttled + bounded — it must never jank composition.
    // Exact checks above stay live; fuzzy refreshes at most once a minute.
    val now = System.currentTimeMillis()
    synchronized(fuzzyCache) {
        val cached = fuzzyCache[item.fileName]
        if (cached != null && now - cached.checkedAt < FUZZY_TTL_MS) return cached.uriOrNull
    }
    val found = fuzzyScan(roots, item, wanted)
    synchronized(fuzzyCache) {
        if (fuzzyCache.size > 200) fuzzyCache.clear()
        fuzzyCache[item.fileName] = FuzzyEntry(now, found)
    }
    return found
}

private data class FuzzyEntry(val checkedAt: Long, val uriOrNull: String?)
private const val FUZZY_TTL_MS = 60_000L
private const val MAX_VISITED_FILES = 2000
private val fuzzyCache = mutableMapOf<String, FuzzyEntry>()

private fun fuzzyScan(
    roots: List<Pair<File, Int>>,
    item: DownloadItem,
    wanted: ExternalFileIdentity,
): String? {
    val counter = intArrayOf(0)
    val candidates = mutableListOf<File>()
    for ((root, depth) in roots) {
        collectVideoFiles(root, depth, counter, candidates)
    }
    val files = candidates
        .filter { it.isFile && it.length() > 0L }
        .distinctBy { it.absolutePath }
    if (files.isEmpty()) return null
    val matches = if (item.isEpisode) {
        files.filter { wanted.matchesEpisode(it.nameWithoutExtension) }
    } else {
        files.filter { wanted.matchesMovie(it.nameWithoutExtension) }
    }
    if (matches.size == 1) return matches.single().toURI().toString()
    return null
}

private fun externalSearchRoots(): List<Pair<File, Int>> {
    val externalRoot = android.os.Environment.getExternalStorageDirectory()
    val state = android.os.Environment.getExternalStorageState()
    if (state != android.os.Environment.MEDIA_MOUNTED &&
        state != android.os.Environment.MEDIA_MOUNTED_READ_ONLY) return emptyList()
    val ordered = linkedSetOf<Pair<File, Int>>()
    NuvioPublicDownloads.directory()?.let { ordered.add(it to 3) }
    ordered.add(File(externalRoot, "Download/GoPeed") to 1)
    ordered.add(File(externalRoot, "Download") to 1)
    // Fork: deliberately NOT the whole Movies tree — walking the user's
    // entire library on the UI thread caused ANRs. Manager files land in
    // the folders above; library files were never ours to claim.
    return ordered.filter { (root, _) -> root.isDirectory }.toList()
}

private val VIDEO_EXTENSIONS = setOf("mkv", "mp4", "webm", "avi", "mov", "m4v", "ts", "flv", "wmv", "mpg", "mpeg")

private fun listVideoFiles(root: File, maxDepth: Int): List<File> {
    if (!root.isDirectory || maxDepth < 0) return emptyList()
    val out = mutableListOf<File>()
    val entries = root.listFiles() ?: return emptyList()
    for (entry in entries) {
        if (entry.isFile && VIDEO_EXTENSIONS.contains(entry.extension.lowercase())) {
            out.add(entry)
        } else if (entry.isDirectory && maxDepth > 0) {
            out.addAll(listVideoFiles(entry, maxDepth - 1))
        }
    }
    return out
}

private fun collectVideoFiles(root: File, maxDepth: Int, visited: IntArray, out: MutableList<File>) {
    if (!root.isDirectory || maxDepth < 0 || visited[0] >= MAX_VISITED_FILES) return
    val entries = root.listFiles() ?: return
    for (entry in entries) {
        if (visited[0] >= MAX_VISITED_FILES) return
        visited[0]++
        if (entry.isFile && VIDEO_EXTENSIONS.contains(entry.extension.lowercase())) {
            out.add(entry)
        } else if (entry.isDirectory && maxDepth > 0) {
            collectVideoFiles(entry, maxDepth - 1, visited, out)
        }
    }
}

private val TITLE_STOPWORDS = setOf("the", "and", "of", "a", "an", "with", "in", "on", "vs")

private fun String.normalizedWords(): List<String> =
    lowercase()
        .replace(Regex("[^a-z0-9]+"), " ")
        .split(' ')
        .filter { it.length >= 3 && it !in TITLE_STOPWORDS }

private class ExternalFileIdentity(item: DownloadItem) {
    val season: Int? = item.seasonNumber
    val episode: Int? = item.episodeNumber
    val titleWords: List<String> = buildList {
        addAll(item.title.normalizedWords())
        item.episodeTitle?.let { addAll(it.normalizedWords()) }
    }.distinct().take(8)

    val valid: Boolean = if (item.isEpisode) {
        season != null && episode != null
    } else {
        titleWords.isNotEmpty()
    }

    private fun episodeTokens(): List<String> {
        val s = season ?: return emptyList()
        val e = episode ?: return emptyList()
        val ss = s.toString().padStart(2, '0')
        val ee = e.toString().padStart(2, '0')
        return listOf("s${ss}e$ee", "${s}x$ee", "s${s}e$e")
    }

    fun matchesEpisode(candidateName: String): Boolean {
        val normalized = candidateName.lowercase().replace(Regex("[^a-z0-9]+"), "")
        val compactTokens = episodeTokens().map { it.replace(Regex("[^a-z0-9]+"), "") }
        if (compactTokens.none { it in normalized }) return false
        if (titleWords.isEmpty()) return true
        val spaced = " ${candidateName.normalizedWords().joinToString(" ")} "
        val hits = titleWords.count { " $it " in spaced }
        return hits >= minOf(2, titleWords.size)
    }

    fun matchesMovie(candidateName: String): Boolean {
        if (titleWords.isEmpty()) return false
        val spaced = " ${candidateName.normalizedWords().joinToString(" ")} "
        val hits = titleWords.count { " $it " in spaced }
        return hits >= minOf(3, titleWords.size)
    }
}

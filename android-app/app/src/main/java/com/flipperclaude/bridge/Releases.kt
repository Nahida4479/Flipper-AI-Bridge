package com.flipperclaude.bridge

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class ReleaseAsset(val name: String, val url: String, val size: Long, val sha256: String)

data class ReleaseInfo(val tag: String, val prerelease: Boolean, val notes: String, val assets: List<ReleaseAsset>) {
    fun asset(name: String) = assets.firstOrNull { it.name == name }
}

class ChecksumException(message: String) : Exception(message)

object Releases {
    const val REPO = "Nahida4479/Flipper-Claude"
    const val APK_NAME = "flipper-claude-bridge.apk"
    const val FAP_NAME = "claude_bridge.fap"
    const val SUMS_NAME = "SHA256SUMS"
    private const val MAX_BYTES = 40L * 1024 * 1024

    private fun open(url: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 60000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "FlipperClaudeBridge")
        return conn
    }

    fun latest(includeBeta: Boolean): ReleaseInfo? {
        val conn = open("https://api.github.com/repos/$REPO/releases?per_page=15")
        try {
            if (conn.responseCode !in 200..299) throw ChecksumException("GitHub answered ${conn.responseCode}")
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val list = JSONArray(body)
            var best: ReleaseInfo? = null
            for (i in 0 until list.length()) {
                val r = list.getJSONObject(i)
                if (r.optBoolean("draft")) continue
                val pre = r.optBoolean("prerelease")
                if (pre && !includeBeta) continue
                val info = parseRelease(r)
                if (info.asset(APK_NAME) == null && info.asset(FAP_NAME) == null) continue
                if (best == null || Versions.compare(info.tag, best.tag) > 0) best = info
            }
            return best
        } finally {
            conn.disconnect()
        }
    }

    private fun parseRelease(r: JSONObject): ReleaseInfo {
        val assets = ArrayList<ReleaseAsset>()
        val arr = r.optJSONArray("assets") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val a = arr.getJSONObject(i)
            val digest = a.optString("digest")
            val sha = if (digest.startsWith("sha256:")) digest.substring(7).lowercase() else ""
            assets.add(
                ReleaseAsset(a.getString("name"), a.getString("browser_download_url"), a.optLong("size"), sha)
            )
        }
        return ReleaseInfo(r.getString("tag_name"), r.optBoolean("prerelease"), r.optString("body"), assets)
    }

    fun download(url: String, expectedSize: Long, onProgress: (Int) -> Unit): ByteArray {
        val conn = open(url)
        try {
            if (conn.responseCode !in 200..299) throw ChecksumException("Download failed (${conn.responseCode})")
            val total = if (expectedSize > 0) expectedSize else conn.contentLengthLong
            if (total > MAX_BYTES) throw ChecksumException("File too large")
            val out = ByteArrayOutputStream()
            val buf = ByteArray(16384)
            conn.inputStream.use { input ->
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > MAX_BYTES) throw ChecksumException("File too large")
                    if (total > 0) onProgress((out.size() * 100L / total).toInt())
                }
            }
            val data = out.toByteArray()
            if (expectedSize > 0 && data.size.toLong() != expectedSize) {
                throw ChecksumException("Download incomplete")
            }
            return data
        } finally {
            conn.disconnect()
        }
    }

    fun sha256(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    fun md5(data: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(data).joinToString("") { "%02x".format(it) }

    fun fetchAndVerify(release: ReleaseInfo, name: String, onProgress: (Int) -> Unit): ByteArray {
        val asset = release.asset(name) ?: throw ChecksumException("$name not in release")
        val expected = HashSet<String>()
        if (asset.sha256.isNotEmpty()) expected.add(asset.sha256)
        release.asset(SUMS_NAME)?.let { sums ->
            val text = String(download(sums.url, sums.size, {}), Charsets.UTF_8)
            for (line in text.lines()) {
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size >= 2 && parts[1].removePrefix("*") == name) expected.add(parts[0].lowercase())
            }
        }
        if (expected.isEmpty()) throw ChecksumException("No checksum published for $name")
        if (expected.size > 1) throw ChecksumException("Checksums disagree, refusing $name")
        val data = download(asset.url, asset.size, onProgress)
        val actual = sha256(data)
        if (actual != expected.first()) throw ChecksumException("Checksum mismatch for $name")
        return data
    }
}

object Versions {
    private class Parsed(val core: List<Int>, val pre: String)

    private fun parse(tag: String): Parsed {
        val clean = tag.trim().removePrefix("v").removePrefix("V")
        val dash = clean.indexOf('-')
        val corePart = if (dash >= 0) clean.substring(0, dash) else clean
        val pre = if (dash >= 0) clean.substring(dash + 1) else ""
        val core = corePart.split('.').map { it.toIntOrNull() ?: 0 }
        return Parsed(core, pre)
    }

    private fun preNumber(pre: String): Int = pre.filter { it.isDigit() }.toIntOrNull() ?: 0

    fun compare(a: String, b: String): Int {
        val x = parse(a)
        val y = parse(b)
        val size = maxOf(x.core.size, y.core.size)
        for (i in 0 until size) {
            val c = (x.core.getOrElse(i) { 0 }).compareTo(y.core.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        if (x.pre.isEmpty() && y.pre.isEmpty()) return 0
        if (x.pre.isEmpty()) return 1
        if (y.pre.isEmpty()) return -1
        return preNumber(x.pre).compareTo(preNumber(y.pre))
    }
}

package keiyoushi.utils

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import eu.kanade.tachiyomi.source.model.Page
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.security.MessageDigest
import java.util.LinkedHashMap

data class LeadingPageIdentity(
    val seriesKey: String,
    val chapterKey: String,
)

data class PageFingerprint(
    val sha256: String,
    val differenceHash: Long?,
    val width: Int,
    val height: Int,
) {
    fun isDuplicateOf(
        other: PageFingerprint,
        differenceHashThreshold: Int = DEFAULT_DIFFERENCE_HASH_THRESHOLD,
    ): Boolean {
        if (sha256 == other.sha256) {
            return true
        }

        if (
            width != other.width ||
            height != other.height ||
            differenceHash == null ||
            other.differenceHash == null
        ) {
            return false
        }

        return java.lang.Long.bitCount(differenceHash xor other.differenceHash) <= differenceHashThreshold
    }

    companion object {
        const val DEFAULT_DIFFERENCE_HASH_THRESHOLD = 4
    }
}

data class LeadingDuplicateScanResult(
    val inspectedIndices: Set<Int>,
    val duplicateIndices: Set<Int>,
)

fun <T> scanLeadingDuplicates(
    items: List<T>,
    initialWindow: Int = 4,
    isDuplicate: (index: Int, item: T) -> Boolean,
): LeadingDuplicateScanResult {
    require(initialWindow > 0)

    val inspected = linkedSetOf<Int>()
    val duplicates = linkedSetOf<Int>()
    val initialCount = minOf(initialWindow, items.size)

    repeat(initialCount) { index ->
        inspected += index
        if (isDuplicate(index, items[index])) {
            duplicates += index
        }
    }

    if (initialCount == initialWindow && duplicates.size == initialWindow) {
        for (index in initialWindow until items.size) {
            inspected += index
            if (isDuplicate(index, items[index])) {
                duplicates += index
            } else {
                break
            }
        }
    }

    return LeadingDuplicateScanResult(inspected, duplicates)
}

fun <T> scanTrailingDuplicates(
    items: List<T>,
    initialWindow: Int = 4,
    isDuplicate: (index: Int, item: T) -> Boolean,
): LeadingDuplicateScanResult {
    require(initialWindow > 0)

    val inspected = linkedSetOf<Int>()
    val duplicates = linkedSetOf<Int>()
    val indices = items.indices.reversed()
    val initialIndices = indices.take(initialWindow)

    initialIndices.forEach { index ->
        inspected += index
        if (isDuplicate(index, items[index])) {
            duplicates += index
        }
    }

    if (initialIndices.size == initialWindow && initialIndices.all(duplicates::contains)) {
        for (index in indices.drop(initialWindow)) {
            inspected += index
            if (isDuplicate(index, items[index])) {
                duplicates += index
            } else {
                break
            }
        }
    }

    return LeadingDuplicateScanResult(inspected, duplicates)
}

fun leadingPagePosition(index: Int): Int = index

fun trailingPagePosition(
    index: Int,
    pageCount: Int,
): Int = index - pageCount

fun pageIndexForFingerprintPosition(
    position: Int,
    pageCount: Int,
): Int? {
    val index = if (position >= 0) position else pageCount + position
    return index.takeIf { it in 0 until pageCount }
}

fun fingerprintPositionsShareEdge(
    first: Int,
    second: Int,
): Boolean = (first >= 0) == (second >= 0)

fun Map<Int, List<PageFingerprint>>.hasDuplicateFingerprintAtEdge(
    position: Int,
    candidate: PageFingerprint,
): Boolean =
    entries.any { (storedPosition, fingerprints) ->
        fingerprintPositionsShareEdge(position, storedPosition) &&
            fingerprints.any { reference ->
                candidate.sha256 == reference.sha256 ||
                    (position == storedPosition && candidate.isDuplicateOf(reference))
            }
    }

fun initialEdgeFingerprintPositions(
    pageCount: Int,
    initialWindow: Int = 4,
): List<Int> {
    require(initialWindow > 0)

    val leading = (0 until minOf(pageCount, initialWindow)).map(::leadingPagePosition)
    val trailing =
        (pageCount - 1 downTo maxOf(0, pageCount - initialWindow))
            .takeIf { pageCount > 0 }
            ?.map { trailingPagePosition(it, pageCount) }
            .orEmpty()
    return leading + trailing
}

fun createPageFingerprint(bytes: ByteArray): PageFingerprint {
    val sha256 = bytes.sha256()
    val bounds =
        BitmapFactory.Options().apply {
            inJustDecodeBounds = true
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, this)
        }

    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
        return PageFingerprint(sha256, null, 0, 0)
    }

    val decodeOptions =
        BitmapFactory.Options().apply {
            inSampleSize = calculateSampleSize(bounds.outWidth, bounds.outHeight)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
    val bitmap =
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
            ?: return PageFingerprint(sha256, null, bounds.outWidth, bounds.outHeight)

    val differenceHash =
        try {
            bitmap.differenceHash()
        } finally {
            bitmap.recycle()
        }

    return PageFingerprint(sha256, differenceHash, bounds.outWidth, bounds.outHeight)
}

class PageFingerprintHistory(
    private val preferences: SharedPreferences,
    private val namespace: String,
    private val maxChaptersPerSeries: Int = 8,
    private val maxFingerprintsPerChapter: Int = 64,
) {
    private val lock = Any()

    fun previousFingerprints(identity: LeadingPageIdentity): List<IndexedValue<PageFingerprint>> =
        synchronized(lock) {
            read(identity.seriesKey)
                .filterNot { it.chapterKeyHash == identity.chapterKey.sha256() }
                .map { IndexedValue(it.pageIndex, it.fingerprint) }
        }

    fun recordChapter(
        identity: LeadingPageIdentity,
        fingerprints: List<IndexedValue<PageFingerprint>>,
    ) {
        if (fingerprints.isEmpty()) {
            return
        }

        synchronized(lock) {
            val now = System.currentTimeMillis()
            val chapterKeyHash = identity.chapterKey.sha256()
            val retained =
                read(identity.seriesKey)
                    .filterNot { it.chapterKeyHash == chapterKeyHash }
                    .groupBy { it.chapterKeyHash }
                    .entries
                    .sortedByDescending { (_, records) -> records.maxOf { it.timestamp } }
                    .take(maxOf(0, maxChaptersPerSeries - 1))
                    .flatMap { it.value }
            val current =
                fingerprints
                    .take(maxFingerprintsPerChapter)
                    .map { indexed ->
                        StoredFingerprint(
                            timestamp = now,
                            chapterKeyHash = chapterKeyHash,
                            pageIndex = indexed.index,
                            fingerprint = indexed.value,
                        )
                    }

            preferences
                .edit()
                .putString(storageKey(identity.seriesKey), (retained + current).joinToString("\n") { it.encode() })
                .apply()
        }
    }

    private fun read(seriesKey: String): List<StoredFingerprint> =
        preferences
            .getString(storageKey(seriesKey), null)
            ?.lineSequence()
            ?.mapNotNull(StoredFingerprint::decode)
            ?.toList()
            .orEmpty()

    private fun storageKey(seriesKey: String): String = "$namespace.${seriesKey.sha256().take(24)}"

    private data class StoredFingerprint(
        val timestamp: Long,
        val chapterKeyHash: String,
        val pageIndex: Int,
        val fingerprint: PageFingerprint,
    ) {
        fun encode(): String =
            listOf(
                timestamp,
                chapterKeyHash,
                pageIndex,
                fingerprint.width,
                fingerprint.height,
                fingerprint.sha256,
                fingerprint.differenceHash?.let { java.lang.Long.toUnsignedString(it, 16) } ?: "-",
            ).joinToString("|")

        companion object {
            fun decode(value: String): StoredFingerprint? {
                val parts = value.split("|")
                if (parts.size != 7) {
                    return null
                }

                return runCatching {
                    StoredFingerprint(
                        timestamp = parts[0].toLong(),
                        chapterKeyHash = parts[1],
                        pageIndex = parts[2].toInt(),
                        fingerprint =
                            PageFingerprint(
                                width = parts[3].toInt(),
                                height = parts[4].toInt(),
                                sha256 = parts[5],
                                differenceHash =
                                    parts[6]
                                        .takeUnless { it == "-" }
                                        ?.let { java.lang.Long.parseUnsignedLong(it, 16) },
                            ),
                    )
                }.getOrNull()
            }
        }
    }
}

data class PrefetchedPageImage(
    val bytes: ByteArray,
    val mediaType: String?,
)

class PrefetchedPageImageStore(
    private val maxBytes: Int = 24 * 1024 * 1024,
) {
    private val entries = LinkedHashMap<String, PrefetchedPageImage>()
    private var byteCount = 0

    @Synchronized
    fun clear() {
        entries.clear()
        byteCount = 0
    }

    @Synchronized
    fun put(
        url: String,
        image: PrefetchedPageImage,
    ) {
        if (image.bytes.size > maxBytes) {
            return
        }

        entries.remove(url)?.let { byteCount -= it.bytes.size }
        while (byteCount + image.bytes.size > maxBytes && entries.isNotEmpty()) {
            val oldest = entries.entries.first()
            entries.remove(oldest.key)
            byteCount -= oldest.value.bytes.size
        }

        entries[url] = image
        byteCount += image.bytes.size
    }

    fun intercept(chain: Interceptor.Chain): Response {
        val cached =
            synchronized(this) {
                entries.remove(chain.request().url.toString())?.also {
                    byteCount -= it.bytes.size
                }
            } ?: return chain.proceed(chain.request())
        val mediaType = cached.mediaType?.toMediaTypeOrNull()

        return Response
            .Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(cached.bytes.toResponseBody(mediaType))
            .apply {
                mediaType?.let { header("Content-Type", it.toString()) }
            }.build()
    }
}

fun reindexPages(
    pages: List<Page>,
    excludedIndices: Set<Int>,
): List<Page> =
    pages
        .filterIndexed { index, _ -> index !in excludedIndices }
        .mapIndexed { index, page ->
            Page(index, page.url, page.imageUrl, page.uri)
        }

private fun ByteArray.sha256(): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(this)
        .joinToString("") { "%02x".format(it) }

private fun String.sha256(): String = toByteArray().sha256()

private fun calculateSampleSize(
    width: Int,
    height: Int,
): Int {
    var sampleSize = 1
    while (width / (sampleSize * 2) >= 9 && height / (sampleSize * 2) >= 8) {
        sampleSize *= 2
    }
    return sampleSize
}

private fun Bitmap.differenceHash(): Long {
    val scaled = Bitmap.createScaledBitmap(this, 9, 8, true)
    return try {
        var hash = 0L
        for (y in 0 until 8) {
            for (x in 0 until 8) {
                hash = hash shl 1
                if (scaled.getPixel(x, y).luminance() > scaled.getPixel(x + 1, y).luminance()) {
                    hash = hash or 1L
                }
            }
        }
        hash
    } finally {
        if (scaled !== this) {
            scaled.recycle()
        }
    }
}

private fun Int.luminance(): Int =
    (Color.red(this) * 299) +
        (Color.green(this) * 587) +
        (Color.blue(this) * 114)

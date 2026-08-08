package com.xlythe.tachiyomi.extension.en.weebcentral

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeebCentralSeamTest {
    @Test
    fun `moderate repeated seams are candidates while weak transitions are rejected`() {
        fun profile(boundaryDifference: Int): WeebCentralEdgeStripProfile {
            val luma = ByteArray(56 * 32)
            for (row in 0 until 56) {
                for (column in 0 until 32) {
                    val headerValue = if (column < 16) 50 else 100
                    luma[row * 32 + column] =
                        (if (row < 16) headerValue else headerValue + boundaryDifference).toByte()
                }
            }
            return WeebCentralEdgeStripProfile(
                originalWidth = 800,
                originalHeight = 1400,
                heightRows = 56,
                luma = luma,
            )
        }

        assertTrue(16 in profile(boundaryDifference = 12).strongEdgeBoundaryRows(fromTop = true))
        assertFalse(16 in profile(boundaryDifference = 11).strongEdgeBoundaryRows(fromTop = true))
    }

    @Test
    fun `weak seams require a close perceptual match`() {
        val candidateHash = ByteArray(256)
        val acceptedHash = candidateHash.copyOf().also { hash -> repeat(24) { hash[it] = 1 } }
        val rejectedHash = candidateHash.copyOf().also { hash -> repeat(25) { hash[it] = 1 } }
        val candidate =
            WeebCentralEdgeRegionSignature(
                edgeRows = 48,
                edgePixels = 1189,
                fromTop = true,
                hash = candidateHash,
                seamMeanDifference = 18,
            )

        assertTrue(candidate.isDuplicateOf(candidate.copy(hash = acceptedHash)))
        assertFalse(candidate.isDuplicateOf(candidate.copy(hash = rejectedHash)))
    }
}

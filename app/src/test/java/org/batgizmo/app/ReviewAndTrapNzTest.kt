package org.batgizmo.app

import org.batgizmo.app.review.FileReview
import org.batgizmo.app.review.GuanoScan
import org.batgizmo.app.review.ReviewFlag
import org.batgizmo.app.trapnz.CreatedObservation
import org.batgizmo.app.trapnz.partitionUploadables
import org.batgizmo.app.trapnz.snapToGrid
import org.batgizmo.app.trapnz.summariseLocations
import org.batgizmo.app.trapnz.trapNzNodeUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewAndTrapNzTest {

    @Test
    fun filenameStamp_parsesAudioMothStyle() {
        val ms = GuanoScan.parseRecordedAt("20240115_203000.wav")
        assertNotNull(ms)
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = ms!!
        assertEquals(2024, cal.get(java.util.Calendar.YEAR))
        assertEquals(java.util.Calendar.JANUARY, cal.get(java.util.Calendar.MONTH))
        assertEquals(15, cal.get(java.util.Calendar.DAY_OF_MONTH))
        assertEquals(20, cal.get(java.util.Calendar.HOUR_OF_DAY))
    }

    @Test
    fun filenameStamp_parsesPrefixedName() {
        assertNotNull(GuanoScan.parseRecordedAt("SITE3_20240115_203000.wav"))
    }

    @Test
    fun locPosition_isLatThenLon() {
        val loc = GuanoScan.parseLocPosition("-41.2865 174.7762")
        assertNotNull(loc)
        assertEquals(-41.2865, loc!!.lat, 0.0001)
        assertEquals(174.7762, loc.lon, 0.0001)
    }

    @Test
    fun locPosition_rejectsOutOfRange() {
        assertNull(GuanoScan.parseLocPosition("999 0"))
    }

    @Test
    fun naturalSort_ordersNumericNames() {
        val names = listOf("REC_10.wav", "REC_2.wav", "REC_1.wav")
        val sorted = names.sortedWith { a, b -> DocumentHelper.naturalCompare(a, b) }
        assertEquals(listOf("REC_1.wav", "REC_2.wav", "REC_10.wav"), sorted)
    }

    @Test
    fun trapNz_recordUrlUsesDrupalNode() {
        assertEquals("https://trap.nz/node/123456", trapNzNodeUrl(123456))
        val created = CreatedObservation("abc", 123456, "2 recordings")
        assertEquals("https://trap.nz/node/123456", created.webUrl)
    }

    @Test
    fun documentId_parentIsMediaStoreRelativePath() {
        assertEquals(
            "Documents/BatGizmo/2026-09-08/",
            DocumentHelper.relativePathFromDocumentId(
                "primary:Documents/BatGizmo/2026-09-08/20260908_210000.wav"
            )
        )
        assertEquals(
            "Documents/BatGizmo/2026-09-08/",
            DocumentHelper.relativePathFromDocumentId(
                "raw:/storage/emulated/0/Documents/BatGizmo/2026-09-08/20260908_210000.wav"
            )
        )
        assertNull(DocumentHelper.relativePathFromDocumentId("primary:file.wav"))
    }

    @Test
    fun partition_onlyBatsWithGpsAndDate() {
        val items = listOf(
            review("a", ReviewFlag.BATS, lat = -41.2, lon = 174.7, recorded = 1_000L),
            review("b", ReviewFlag.BATS, lat = null, lon = null, recorded = 1_000L),
            review("c", ReviewFlag.NO_BATS, lat = -41.2, lon = 174.7, recorded = 1_000L),
            review("d", ReviewFlag.BATS, lat = -41.2, lon = 174.7, recorded = null)
        )
        val (eligible, skipped) = partitionUploadables(items)
        assertEquals(1, eligible.size)
        assertEquals("a", eligible[0].item.fileName)
        assertEquals(2, skipped.size)
    }

    @Test
    fun summarise_groupsNearbyFiles() {
        val items = listOf(
            review("a", ReviewFlag.BATS, lat = -41.28650, lon = 174.77620, recorded = 1_000L),
            review("b", ReviewFlag.BATS, lat = -41.28651, lon = 174.77621, recorded = 2_000L)
        )
        val (eligible, _) = partitionUploadables(items)
        val summaries = summariseLocations(eligible)
        assertEquals(1, summaries.size)
        assertEquals(2, summaries[0].count)
        assertEquals(1_000L, summaries[0].startEpochMs)
        assertEquals(2_000L, summaries[0].finishEpochMs)
        val snapped = snapToGrid(-41.28650, 174.77620)
        assertTrue(snapped.first.isNotEmpty())
    }

    private fun review(
        name: String,
        flag: ReviewFlag,
        lat: Double?,
        lon: Double?,
        recorded: Long?
    ) = FileReview(
        key = name,
        uri = "content://$name",
        fileName = name,
        flag = flag,
        latitude = lat,
        longitude = lon,
        recordedAtEpochMs = recorded
    )
}

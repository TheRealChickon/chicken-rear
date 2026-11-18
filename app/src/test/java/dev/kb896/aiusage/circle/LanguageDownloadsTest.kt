package dev.kb896.aiusage.circle

import com.google.mlkit.nl.translate.TranslateLanguage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class LanguageDownloadsTest {
    @Test fun observesMlKitDownloadRowsByTheirEnglishPairArchiveNames() {
        assertEquals(setOf("af_en.zip", "en_fa.zip", "en_iw.zip", "en_zh.zip"),
            TranslationTransfer.modelFiles(setOf("en", "af", "fa", "he", "zh")))
    }

    @Test fun choiceDefaultsToLocalePacksAndRestoresExplicitEverything() {
        assertEquals(LanguageChoice.ONLY_MINE, LanguageChoice.saved(null))
        assertEquals(LanguageChoice.ONLY_MINE, LanguageChoice.saved("unknown"))
        for (choice in LanguageChoice.entries) assertEquals(choice, LanguageChoice.saved(choice.name))
        assertEquals(listOf("Only my languages", "Everything"), LanguageChoice.entries.map { it.label })
        assertEquals(OcrModel.FAST, OcrModel.saved(null))
    }

    @Test fun everythingIncludesTheSharedDetectorEveryFastRecognizerAndAllRemoteTranslationsExactlyOnce() {
        val plan = EverythingPlan
        assertEquals(OcrCatalog.assets.toSet() - OcrCatalog.accuratePacks.toSet(), plan.ocr.toSet())
        assertEquals(14, plan.ocr.size)
        assertEquals(14, plan.ocr.distinct().size)
        assertEquals(OcrCatalog.detector, plan.ocr.first())
        assertEquals(TranslateLanguage.getAllLanguages().toSet() - TranslateLanguage.ENGLISH, plan.translations.toSet())
        assertEquals(58, plan.translations.distinct().size)
        assertEquals(72, plan.packCount)
        assertEquals(132_792_320L, plan.ocrBytes)
        assertEquals(122_934_315L, plan.ocr.sumOf { it.modelBytes })
        assertEquals(1_872_792_320L, plan.estimatedTotal)
    }

    @Test fun storageCheckIncludesMarginAndScratchAndCreditsVerifiedPacksAndPartialBytes() {
        val plan = EverythingPlan
        val fresh = plan.remaining(emptySet(), emptySet())
        assertEquals(4_509_327_102L, fresh.requiredFreeBytes { 0 })
        val remaining = plan.remaining(setOf(OcrCatalog.detector), setOf("fr"))
        assertEquals(fresh.downloadBytes { 0 } - OcrCatalog.detector.bytes - 30_001_024,
            remaining.downloadBytes { if (it == plan.ocr[1]) 1024 else 0 })
        val threshold = remaining.requiredFreeBytes { 0 }
        assertEquals(4_464_483_582L, threshold)
        assertEquals(0L, plan.remaining(plan.ocr.toSet(), plan.translations.toSet()).requiredFreeBytes { 0 })
    }

    @Test fun stoppingSkipsQueuedPacksAndResumeOnlyTransfersMissingPacks() = runBlocking {
        val ocr = mutableSetOf<OcrAsset>()
        val translations = mutableSetOf<String>()
        var stop = false
        var paused = false
        try {
            EverythingPlan.transfer(EverythingPlan.remaining(ocr, translations), {
                if (stop) throw IOException("paused")
            }, { ocr.add(it) }, {
                translations.add(it)
                stop = true
            })
        } catch (_: IOException) {
            paused = true
        }
        assertTrue(paused)
        assertEquals(EverythingPlan.ocr.toSet(), ocr)
        assertEquals(setOf(EverythingPlan.translations.first()), translations)

        EverythingPlan.transfer(EverythingPlan.remaining(ocr, translations), {}, {
            error("Already verified")
        }, { assertTrue(translations.add(it)) })
        assertEquals(EverythingPlan.translations.toSet(), translations)
    }

    @Test fun oneProgressLineCountsRecognizersAndTranslationsAndReflectsCancellation() {
        val ocr = OcrModels.State(installed = setOf(OcrCatalog.detector, OcrCatalog.accurateDetector))
        val downloading = LanguageDownloads.State(setOf("fr"), LanguageDownloads.Phase.DOWNLOADING, "Translation · German")
        assertTrue(downloading.active)
        assertEquals("Downloading 2 of 72 packs · Translation · German", LanguageDownloadPrompt.statusLine(downloading, ocr))
        assertTrue(downloading.copy(phase = LanguageDownloads.Phase.STOPPING).active)
        val paused = downloading.copy(phase = LanguageDownloads.Phase.PAUSED, detail = "Download paused")
        assertFalse(paused.active)
        assertEquals("Download paused", LanguageDownloadPrompt.statusLine(paused, ocr))
        assertNull(LanguageDownloadPrompt.statusLine(paused.copy(phase = LanguageDownloads.Phase.IDLE), ocr))
    }
}

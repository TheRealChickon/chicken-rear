package dev.kb896.aiusage.circle

import android.graphics.Rect
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34])
class PaddleLayoutTest {
    @Test fun blankSeparatesRepeatedCharactersAndRightPaddingHasNoHitTarget() {
        val labels = listOf("", "设", "置", "A", " ")
        val ids = listOf(1,1,0,2,4,3,0,3,0,2)
        val scores = FloatArray(ids.size*labels.size)
        ids.forEachIndexed { i,k -> scores[i*labels.size+k] = .99f }
        val box = Rect(10,20,100,40)
        val reading = PaddleLayout.decode(scores,labels,10,box,90,100,false)
        assertEquals("设置 AA",reading.text)
        val result = PaddleLayout.result(listOf(TextRegion(box,reading)),2f,2f,10f,0f)
        assertEquals(listOf("设","置","AA"),result.words.map { it.text })
        assertTrue(result.words[0].box.right <= result.words[1].box.left)
        val aa = result.words.last()
        assertEquals(2,aa.characters.size)
        assertEquals(210,aa.box.right)
        val left = aa.selection(last = aa.glyphAt(aa.characters.first().box.exactCenterX()))
        assertEquals("A",left.text)
        assertEquals(aa.characters.first().box.right,left.r)
        assertTrue(aa.characters[0].box.right <= aa.characters[1].box.left)
    }

    @Test fun translationRegroupsATallFinalWordFromPaddle() {
        fun region(text: String, bounds: Rect): TextRegion {
            val glyphs = text.mapIndexed { i, char ->
                val left = bounds.left + i * bounds.width() / text.length
                val right = bounds.left + (i + 1) * bounds.width() / text.length
                Glyph(char.toString(), Rect(left, bounds.top, right, bounds.bottom))
            }
            return TextRegion(bounds, Reading(glyphs, .99f))
        }
        val lines = listOf(
            region("Am Sonntag laden wir zum gemeinsamen", Rect(59, 1933, 879, 1974)),
            region("Basteln ein. Das Material stellen wir zur", Rect(61, 1999, 835, 2032)),
            region("Verfügung.", Rect(71, 2053, 278, 2111)),
        )
        val reading = PaddleLayout.result(lines, 1f, 1f)
        val paragraphs = TrBlocks.split(reading.blocks)
        assertEquals(listOf(lines.joinToString(" ") { it.reading.text }), paragraphs.map { it.text })
        assertEquals("Verfügung.", paragraphs.single().lines.last().parts.single().text)
        assertTrue(reading.words.any { it.text == "Verfügung." })
    }

    @Test fun splitRegionsAndWrappedLinesTranslateAsOneParagraphPerColumn() {
        fun r(text: String,x: Int,y: Int,w: Int): TextRegion {
            val box = Rect(x,y,x+w,y+20)
            return TextRegion(box, Reading(listOf(Glyph(text,box)),.99f))
        }
        val page = PaddleLayout.result(listOf(r("文字。", 200,30,60), r("这是",0,0,40),
            r("右边的",200,0,60), r("一个",45,0,40), r("段落。",0,30,60)),1f,1f)
        val requests = mutableListOf<String>()
        val patches = mutableListOf<Rect>()
        Tr.screen(page.blocks,"en",true, { _,_ -> }, { block,_ -> patches.add(block.box) },
            translateParagraph = { text,src,tgt,answer ->
                assertEquals("zh",src); assertEquals("en",tgt)
                requests.add(text); answer("whole paragraph")
            })
        assertEquals(listOf("这是一个段落。","右边的文字。"),requests)
        assertEquals(listOf(Rect(0,0,85,50),Rect(200,0,260,50)),patches)
    }

    @Test fun aHeadingSpansTwoColumnsThatAreReadToTheirEnds() {
        fun region(text: String,x: Int,y: Int,w: Int): TextRegion {
            val box = Rect(x,y,x+w,y+20)
            return TextRegion(box,Reading(listOf(Glyph(text,box)),1f))
        }
        val page = PaddleLayout.result(listOf(region("Heading",0,0,360),
            region("First",0,50,120), region("Third",200,50,100),
            region("Second",0,110,110), region("Fourth",200,110,130)),1f,1f)
        assertEquals(listOf("Heading","First","Second","Third","Fourth"),page.blocks.map { it.text })
    }

    @Test fun thaiWordsKeepTheirCharacterBoundsAndCopyWithoutInsertedSpaces() {
        val text = "เลือกภาษาของคุณ"
        val glyphs = text.mapIndexed { i,c -> Glyph(c.toString(),Rect(i*8,0,(i+1)*8,20)) }
        val page = PaddleLayout.result(listOf(TextRegion(Rect(0,0,text.length*8,20),Reading(glyphs,1f))),1f,1f)
        assertTrue(page.words.size > 1)
        val words = page.words.map { it.selection() }
        assertEquals(text,SelLogic.text(words,0,words.lastIndex))
        assertEquals(text,SelLogic.query(words,0,words.lastIndex))
    }

    @Test fun namedOffersAreRememberedSeparatelyFromLanguageInstallation() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        assertFalse(OcrModels.offered(app,"th"))
        OcrModels.remember(app,"th")
        assertTrue(OcrModels.offered(app,"th"))
        assertFalse(OcrModels.offered(app,"arabic"))
        assertFalse(OcrModels.status.value.ready)
        assertEquals("th",Tr.script("เลือกภาษา"))
        assertEquals("el",Tr.script("Ρυθμίσεις"))
    }

    @Test fun localeScriptAndTheOfficialPackAreResolvedBeforeConsent() {
        fun pack(tag: String,script: String) = OcrCatalog.forLocale(Locale.forLanguageTag(tag),script)?.name
        assertEquals("kannada",pack("kn","Knda"))
        assertEquals("cjk",pack("ja","Jpan"))
        assertEquals("cjk",pack("zh-TW","Hant"))
        assertEquals("latin",pack("sr-Latn","Latn"))
        assertEquals("cyrillic",pack("sr-Cyrl","Cyrl"))
        assertEquals("arabic",pack("ur","Arab"))
        assertNull(pack("he","Hebr"))
        assertEquals(13,OcrCatalog.recognizers.size)
    }
}

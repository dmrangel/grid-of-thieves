package io.github.dmrangel.gridofthieves.scan

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Prints reais (os mesmos do chrollo), já reduzidos a 1000 px e em luminância por
 * `tools/make_fixtures.py`, nomeados `<número da base>-<aparelho>.png`.
 */
class BaseMatcherTest {
    private val matcher = BaseMatcher(BaseMatcher.parse(File("src/main/assets/bases.json").readText()))
    private val fixtures = File("src/test/resources/bases").listFiles { f -> f.extension == "png" }!!.sorted()

    private class Gray(val lum: ByteArray, val width: Int, val height: Int)

    private fun load(file: File): Gray {
        val image = ImageIO.read(file)
        val gray = if (image.type == BufferedImage.TYPE_BYTE_GRAY) image else
            BufferedImage(image.width, image.height, BufferedImage.TYPE_BYTE_GRAY).also { it.graphics.drawImage(image, 0, 0, null) }
        val lum = ByteArray(gray.width * gray.height)
        gray.raster.getDataElements(0, 0, gray.width, gray.height, lum)
        return Gray(lum, gray.width, gray.height)
    }

    @Test
    fun parsesAllBases() {
        val bases = BaseMatcher.parse(File("src/main/assets/bases.json").readText())
        assertEquals(124, bases.size)
        assertTrue(bases.all { it.walls.size == BaseMatcher.CELLS })
    }

    @Test
    fun identifiesEveryFixture() {
        assertTrue("sem fixtures", fixtures.isNotEmpty())
        for (file in fixtures) {
            val expected = file.name.substringBefore('-').toInt()
            val img = load(file)
            val result = matcher.identify(img.lum, img.width, img.height)
            assertTrue("${file.name}: $result", result is BaseMatcher.Result.Found)
            val (first, second) = (result as BaseMatcher.Result.Found).matches
            val f = result.frame
            println("${file.name}: ${result.matches} frame=$f " +
                "(x ${"%.4f".format(f.left / img.width.toFloat())}..${"%.4f".format(f.right / img.width.toFloat())}, " +
                "y ${"%.4f".format(f.top / img.height.toFloat())}..${"%.4f".format(f.bottom / img.height.toFloat())})")
            assertEquals(file.name, expected, first.number)
            assertTrue("${file.name}: margem pequena ${first.score} vs ${second.score}", first.score - second.score >= 0.05f)
        }
    }

    /** Com a grade calibrada no lugar certo, o candidato dela também tem que acertar. */
    @Test
    fun calibratedHintAgreesWithDetection() {
        for (file in fixtures) {
            val img = load(file)
            val detected = matcher.identify(img.lum, img.width, img.height) as BaseMatcher.Result.Found
            val withHint = matcher.identify(img.lum, img.width, img.height, hint = detected.frame) as BaseMatcher.Result.Found
            assertEquals(file.name, detected.matches.first().number, withHint.matches.first().number)
        }
    }

    @Test
    fun blankImageIsNotFound() {
        val result = matcher.identify(ByteArray(1000 * 450), 1000, 450)
        assertTrue(result.toString(), result is BaseMatcher.Result.NotFound)
    }
}

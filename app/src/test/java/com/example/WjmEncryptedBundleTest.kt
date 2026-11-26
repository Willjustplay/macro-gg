package com.example

import com.example.data.ClickAction
import com.example.data.ImageAction
import com.example.data.Macro
import com.example.data.MacroBundleCodec
import com.example.data.MacroSet
import com.example.data.RunMacroAction
import com.example.data.SingleUseImportException
import com.example.data.WjmCrypto
import com.example.data.WjmFormatException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class WjmEncryptedBundleTest {

    @Test
    fun encryptedMacroWjmRoundTripPreservesDataAndHidesPlaintext() {
        val subMacro = Macro(
            id = "sub_1",
            name = "SubRahasia",
            isPassive = true,
            actions = listOf(ClickAction(x = 321, y = 654))
        )
        val rootMacro = Macro(
            id = "root_1",
            name = "FarmBossSuperSecret",
            logoImage = "logo_boss.png",
            actions = listOf(
                ImageAction(
                    imageName = "tombol_serang.png",
                    thenActions = listOf(RunMacroAction(targetMacroId = subMacro.id))
                )
            )
        )
        val fakePng = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3, 4)
        val fakeLogo = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 9, 8, 7, 6)

        val out1 = ByteArrayOutputStream()
        val exportRes = MacroBundleCodec.writeEncryptedMacroWjm(
            root = rootMacro,
            lookupMacro = { if (it == subMacro.id) subMacro else null },
            loadImage = { if (it == "tombol_serang.png") it to fakePng else null },
            output = out1,
            loadMacroIcon = { if (it == "logo_boss.png") it to fakeLogo else null },
            singleUseImport = true
        )
        val wjmBytes1 = out1.toByteArray()

        // Second export of identical data must have completely different ciphertext (random salt + IV)
        val out2 = ByteArrayOutputStream()
        MacroBundleCodec.writeEncryptedMacroWjm(
            root = rootMacro,
            lookupMacro = { if (it == subMacro.id) subMacro else null },
            loadImage = { if (it == "tombol_serang.png") it to fakePng else null },
            output = out2,
            loadMacroIcon = { if (it == "logo_boss.png") it to fakeLogo else null },
            singleUseImport = true
        )
        val wjmBytes2 = out2.toByteArray()

        assertEquals(2, exportRes.macrosExported)
        assertEquals(1, exportRes.imagesExported)
        assertTrue(exportRes.singleUseImport)
        assertTrue("Must begin with WJM1 header", WjmCrypto.isWjmHeader(wjmBytes1))
        assertFalse("Must NOT begin with PK ZIP header", wjmBytes1[0] == 0x50.toByte() && wjmBytes1[1] == 0x4B.toByte())
        assertFalse("Plaintext macro name must not appear in .wjm", String(wjmBytes1, Charsets.ISO_8859_1).contains("FarmBossSuperSecret"))
        assertFalse("Plaintext asset name must not appear in .wjm", String(wjmBytes1, Charsets.ISO_8859_1).contains("tombol_serang.png"))
        assertNotEquals("Each .wjm export must use unique random salt/IV", wjmBytes1.contentToString(), wjmBytes2.contentToString())

        // Wjust Player decrypts cleanly
        val decoded = MacroBundleCodec.readEncryptedWjm(wjmBytes1.inputStream())
        assertEquals(2, decoded.macros.size)
        assertEquals("FarmBossSuperSecret", decoded.macros.first { !it.isPassive }.name)
        assertTrue(decoded.macros.any { it.isPassive && it.name == "SubRahasia" })
        assertArrayEquals(fakePng, decoded.images["tombol_serang.png"])
        assertArrayEquals(fakeLogo, decoded.macroIcons["logo_boss.png"])

        // Single-use token is preserved and enforced
        val tokens = MacroBundleCodec.validateAndExtractOneTimeTokens(decoded, emptySet())
        assertTrue(tokens.isNotEmpty())
        var secondImportBlocked = false
        try {
            MacroBundleCodec.validateAndExtractOneTimeTokens(decoded, tokens)
        } catch (_: SingleUseImportException) {
            secondImportBlocked = true
        }
        assertTrue("Re-importing 1x .wjm must throw SingleUseImportException", secondImportBlocked)
    }

    @Test
    fun encryptedMacroSetWjmRoundTripWorks() {
        val set = MacroSet(id = "set_1", name = "Paket Farming Pro", colorHex = "#10B981")
        val active = Macro(id = "m_active", name = "Utama", setId = set.id, actions = listOf(ClickAction(x = 10, y = 20)))
        val passive = Macro(id = "m_passive", name = "Auto Claim", setId = set.id, isPassive = true, passiveEnabled = true)

        val out = ByteArrayOutputStream()
        MacroBundleCodec.writeEncryptedMacroSetWjm(
            set = set,
            memberMacros = listOf(active, passive),
            lookupMacro = { null },
            loadImage = { null },
            output = out
        )

        val decoded = MacroBundleCodec.readEncryptedWjm(out.toByteArray().inputStream())
        assertEquals(1, decoded.macroSets.size)
        assertEquals("Paket Farming Pro", decoded.macroSets.first().name)
        assertEquals(2, decoded.macros.size)
        val decodedSetId = decoded.macroSets.first().id
        assertTrue(decoded.macros.all { it.setId == decodedSetId })
    }

    @Test
    fun tamperedWjmBytesAreRejected() {
        val macro = Macro(id = "m1", name = "TamperTest", actions = listOf(ClickAction(x = 50, y = 50)))
        val out = ByteArrayOutputStream()
        MacroBundleCodec.writeEncryptedMacroWjm(
            root = macro,
            lookupMacro = { null },
            loadImage = { null },
            output = out
        )
        val tampered = out.toByteArray()
        tampered[tampered.lastIndex] = (tampered[tampered.lastIndex].toInt() xor 0xFF).toByte()

        var failed = false
        try {
            MacroBundleCodec.readEncryptedWjm(tampered.inputStream())
        } catch (_: WjmFormatException) {
            failed = true
        }
        assertTrue("Tampered .wjm must fail GCM verification", failed)
    }

    @Test
    fun playerRejectsUnencryptedZipAndStudioRejectsEncryptedWjm() {
        val macro = Macro(id = "m2", name = "CrossGuard", actions = listOf(ClickAction(x = 10, y = 10)))

        // 1. Plain ZIP must be rejected by Wjust Player's readEncryptedWjm
        val zipOut = ByteArrayOutputStream()
        MacroBundleCodec.writeMacroZip(
            root = macro,
            lookupMacro = { null },
            loadImage = { null },
            output = zipOut
        )
        var playerRejectedZip = false
        try {
            MacroBundleCodec.readEncryptedWjm(zipOut.toByteArray().inputStream())
        } catch (_: WjmFormatException) {
            playerRejectedZip = true
        }
        assertTrue("Wjust Player must reject unencrypted .zip files", playerRejectedZip)

        // 2. Encrypted .wjm must be rejected by Studio's editable readBundle
        val wjmOut = ByteArrayOutputStream()
        MacroBundleCodec.writeEncryptedMacroWjm(
            root = macro,
            lookupMacro = { null },
            loadImage = { null },
            output = wjmOut
        )
        var studioRejectedWjm = false
        try {
            MacroBundleCodec.readBundle(wjmOut.toByteArray().inputStream())
        } catch (_: WjmFormatException) {
            studioRejectedWjm = true
        }
        assertTrue("Wjust macro Studio must reject importing encrypted .wjm for editing", studioRejectedWjm)
    }
}

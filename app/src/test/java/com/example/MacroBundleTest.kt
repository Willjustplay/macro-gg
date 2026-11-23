package com.example

import com.example.data.ClickAction
import com.example.data.ImageAction
import com.example.data.LoopAction
import com.example.data.Macro
import com.example.data.MacroBundleCodec
import com.example.data.RunMacroAction
import com.example.data.Slot
import com.example.data.SwipeAction
import com.example.data.findAction
import com.example.data.mapAllActions
import com.example.data.updateAction
import com.example.data.walkWithSlot
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

class MacroBundleTest {

    @Test
    fun exportAndImportBundlesNestedAndLinkedMacroImagesAndRemapsIds() {
        val fakePng1 = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3)
        val fakePng2 = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 4, 5, 6)
        val fakePng3 = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 7, 8, 9)

        val subMacro = Macro(
            id = "sub-macro-1",
            name = "Sub Macro",
            actions = listOf(
                ImageAction(
                    id = "sub-img-1",
                    imageName = "sub_btn.png",
                    elseActions = listOf(
                        // Deliberate cycle back to root to verify cycle-safe traversal
                        RunMacroAction(id = "cycle-ref", targetMacroId = "root-macro-1", targetMacroName = "Root")
                    )
                )
            )
        )

        val rootMacro = Macro(
            id = "root-macro-1",
            name = "Root Macro",
            actions = listOf(
                ImageAction(
                    id = "root-img-1",
                    imageName = "root_btn.png",
                    thenActions = listOf(
                        ClickAction(id = "then-click", x = 320, y = 640),
                        ImageAction(id = "then-img", imageName = "then_btn.png")
                    ),
                    elseActions = listOf(
                        RunMacroAction(id = "call-sub", targetMacroId = subMacro.id, targetMacroName = subMacro.name)
                    )
                )
            )
        )

        val storeMacros = mapOf(rootMacro.id to rootMacro, subMacro.id to subMacro)
        val storeImages = mapOf(
            "root_btn.png" to fakePng1,
            "then_btn.png" to fakePng2,
            "sub_btn.png" to fakePng3
        )

        val out = ByteArrayOutputStream()
        val exportResult = MacroBundleCodec.writeMacroZip(
            root = rootMacro,
            lookupMacro = { storeMacros[it] },
            loadImage = { name -> storeImages[name]?.let { name to it } },
            output = out
        )

        assertEquals(2, exportResult.macrosExported)
        assertEquals(3, exportResult.imagesExported)

        // Inspect actual ZIP entries
        val entries = LinkedHashSet<String>()
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zis ->
            while (true) {
                val e = zis.nextEntry ?: break
                entries += e.name
                zis.closeEntry()
            }
        }
        assertTrue("macro.json" in entries)
        assertTrue("linked_macros/sub-macro-1.json" in entries)
        assertTrue("images/root_btn.png" in entries)
        assertTrue("images/then_btn.png" in entries)
        assertTrue("images/sub_btn.png" in entries)

        // Round-trip import with fresh IDs
        var seq = 0
        val decoded = MacroBundleCodec.readBundle(ByteArrayInputStream(out.toByteArray())) {
            "new-id-${++seq}"
        }

        assertEquals(2, decoded.macros.size)
        assertEquals(3, decoded.images.size)
        assertArrayEquals(fakePng1, decoded.images["root_btn.png"])
        assertArrayEquals(fakePng2, decoded.images["then_btn.png"])
        assertArrayEquals(fakePng3, decoded.images["sub_btn.png"])

        val importedRoot = decoded.macros.first { it.name == "Root Macro" }
        val importedSub = decoded.macros.first { it.name == "Sub Macro" }
        assertNotEquals(rootMacro.id, importedRoot.id)
        assertNotEquals(subMacro.id, importedSub.id)

        val importedRun = (importedRoot.actions.first() as ImageAction).elseActions.first() as RunMacroAction
        assertEquals(importedSub.id, importedRun.targetMacroId)
    }

    @Test
    fun walkWithSlotAndNestedTargetPositionUpdate() {
        val thenClick = ClickAction(id = "then-click", x = 100, y = 200)
        val elseSwipe = SwipeAction(id = "else-swipe", x1 = 10, y1 = 20, x2 = 30, y2 = 40)
        val loopClick = ClickAction(id = "loop-click", x = 50, y = 60)

        val actions = listOf(
            ImageAction(
                id = "img-1",
                imageName = "icon.png",
                thenActions = listOf(thenClick),
                elseActions = listOf(elseSwipe)
            ),
            LoopAction(
                id = "loop-1",
                count = 3,
                actions = listOf(loopClick)
            )
        )

        val slotsById = actions.walkWithSlot().associate { (a, slot) -> a.id to slot }
        assertEquals(null, slotsById["img-1"])
        assertEquals(Slot.THEN, slotsById["then-click"])
        assertEquals(Slot.ELSE, slotsById["else-swipe"])
        assertEquals(null, slotsById["loop-1"])
        assertEquals(Slot.BODY, slotsById["loop-click"])

        // Simulate visual on-screen target placement updating nested Then Click & Else Swipe
        val updated = actions
            .updateAction("then-click") { (it as ClickAction).copy(x = 777, y = 888) }
            .updateAction("else-swipe") { (it as SwipeAction).copy(x1 = 111, y1 = 222, x2 = 333, y2 = 444) }

        val newThenClick = updated.findAction("then-click") as ClickAction
        val newElseSwipe = updated.findAction("else-swipe") as SwipeAction
        assertEquals(777, newThenClick.x)
        assertEquals(888, newThenClick.y)
        assertEquals(111, newElseSwipe.x1)
        assertEquals(444, newElseSwipe.y2)

        // Verify mapAllActions renames nested ImageAction cleanly
        val renamed = updated.mapAllActions { a ->
            if (a is ImageAction && a.imageName == "icon.png") a.copy(imageName = "icon_v2.png") else a
        }
        assertEquals("icon_v2.png", (renamed.findAction("img-1") as ImageAction).imageName)
    }
}

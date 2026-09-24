package com.blitz.downloader.data

import com.blitz.downloader.model.CameraMoveOutcome
import com.blitz.downloader.model.CameraVideoRules
import java.io.File
import java.io.IOException
import java.io.OutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CameraVideoMoverTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun setup(): Triple<File, File, DirectCameraVideoStorage> {
        val source = temporary.newFolder("Camera")
        val target = File(temporary.root, "history")
        val store = DirectCameraVideoStorage(source, target, { file ->
            when (file.extension.lowercase()) { "mp4", "mov" -> "video/mp4"; "jpg" -> "image/jpeg"; else -> null }
        })
        return Triple(source, target, store)
    }

    private class Journal : CameraTemporaryJournal {
        val ids = mutableSetOf<String>()
        override fun add(id: String) { ids += id }
        override fun remove(id: String) { ids -= id }
    }

    @Test fun prefixAndMediaRules() {
        listOf("IMG_1.mp4", "img1.mp4", "Vid_1.mov", "video1.mp4").forEach {
            assertFalse(it, CameraVideoRules.isCandidate(it, "video/mp4"))
        }
        assertTrue(CameraVideoRules.isCandidate("douyin_1.mp4", "video/mp4"))
        assertTrue(CameraVideoRules.isCandidate("123.MP4", "video/mp4"))
        assertTrue(CameraVideoRules.isCandidate("a.jpg", "image/jpeg"))
        assertTrue(CameraVideoRules.isCandidate("VID_1.jpg", "image/jpeg"))
        assertFalse(CameraVideoRules.isCandidate("ImG_1.PNG", "image/png"))
        listOf("mvimg1.jpg", "MVIMG_1.jpg", "PaNo_1.jpg", "pano1.jpg", "ReTouch_1.jpg", "retouch1.jpg").forEach {
            assertFalse(it, CameraVideoRules.isCandidate(it, "image/jpeg"))
        }
        assertTrue(CameraVideoRules.isCandidate("download_pano.jpg", "image/jpeg"))
        listOf("mvimg.mp4", "pano.mp4", "retouch.mp4").forEach {
            assertTrue(it, CameraVideoRules.isCandidate(it, "video/mp4"))
        }
        assertEquals("history_img", CameraVideoRules.destinationFolder("image/heic"))
        assertEquals("history", CameraVideoRules.destinationFolder("video/mp4"))
        assertNull(CameraVideoRules.destinationFolder("audio/mpeg"))
        assertFalse(CameraVideoRules.isCandidate("a.bin", null))
        assertEquals("a (2).mp4", CameraVideoRules.availableName("a.mp4") { it in setOf("a.mp4", "a (1).mp4") })
        assertEquals("a (1)", CameraVideoRules.availableName("a") { it == "a" })
    }

    @Test fun scanOnlyDirectVideosWithoutCreatingTarget() {
        val (source, target, store) = setup()
        File(source, "a.mp4").writeText("video")
        File(source, "IMG_1.mp4").writeText("camera")
        File(source, "b.jpg").writeText("image")
        listOf("MVIMG_1.jpg", "pano1.jpg", "ReTouch_1.jpg").forEach {
            File(source, it).writeText("preserved image")
        }
        File(source, "folder").mkdir()
        File(source, "folder/c.mp4").writeText("nested")
        assertEquals(listOf("a.mp4", "b.jpg"), store.scan().map { it.name })
        assertFalse(target.exists())
    }

    @Test fun emptyAndMissingAreNotReadErrors() {
        val (source, _, store) = setup()
        assertTrue(store.scan().isEmpty())
        assertTrue(source.delete())
        assertTrue(store.scan().isEmpty())
        source.writeText("not a directory")
        assertThrows(IOException::class.java) { store.scan() }
    }

    @Test fun movePreservesBytesAndExistingNames() {
        val (source, target, store) = setup()
        val input = File(source, "clip.mp4").apply { writeBytes(ByteArray(200_000) { it.toByte() }) }
        val expected = input.readBytes()
        target.mkdir()
        File(target, "clip.mp4").writeText("existing")
        File(target, "clip (1).mp4").writeText("existing 1")
        val journal = Journal()
        val result = CameraVideoMover(store, journal).move(store.scan().single())
        assertEquals(CameraMoveOutcome.SUCCESS, result.outcome)
        assertFalse(input.exists())
        assertArrayEquals(expected, File(target, "clip (2).mp4").readBytes())
        assertEquals("existing", File(target, "clip.mp4").readText())
        assertEquals("existing 1", File(target, "clip (1).mp4").readText())
        assertTrue(journal.ids.isEmpty())
        assertEquals(3, target.listFiles()!!.size)
    }

    @Test fun changedCandidateIsSkippedBeforeTargetCreation() {
        val (source, target, store) = setup()
        val input = File(source, "clip.mp4").apply { writeText("before") }
        val candidate = store.scan().single()
        input.appendText("after")
        assertEquals(CameraMoveOutcome.SKIPPED, CameraVideoMover(store, Journal()).move(candidate).outcome)
        assertEquals("beforeafter", input.readText())
        assertFalse(target.exists())
    }

    @Test fun writeFailurePreservesSourceAndCleansTemporary() {
        val (source, target, store) = setup()
        val input = File(source, "clip.mp4").apply { writeText("original") }
        val broken = object : CameraVideoStorage by store {
            override fun write(id: String): OutputStream = object : OutputStream() {
                override fun write(value: Int) { throw IOException("模拟空间不足") }
            }
        }
        val journal = Journal()
        assertEquals(CameraMoveOutcome.FAILED, CameraVideoMover(broken, journal).move(store.scan().single()).outcome)
        assertEquals("original", input.readText())
        assertTrue(target.listFiles()!!.isEmpty())
        assertTrue(journal.ids.isEmpty())
    }

    @Test fun corruptedCopyNeverDeletesSource() {
        val (source, _, store) = setup()
        val input = File(source, "clip.mp4").apply { writeText("original") }
        val corrupt = object : CameraVideoStorage by store {
            override fun write(id: String): OutputStream = object : OutputStream() {
                override fun write(value: Int) { /* 模拟成功返回但丢弃写入 */ }
            }
        }
        assertEquals(CameraMoveOutcome.FAILED, CameraVideoMover(corrupt, Journal()).move(store.scan().single()).outcome)
        assertEquals("original", input.readText())
    }

    @Test fun sourceDeleteFailureKeepsCompleteCopy() {
        val (source, target, store) = setup()
        val input = File(source, "clip.mp4").apply { writeText("original") }
        val restricted = object : CameraVideoStorage by store {
            override fun delete(id: String) = if (id == input.path) false else store.delete(id)
        }
        val journal = Journal()
        val result = CameraVideoMover(restricted, journal).move(store.scan().single())
        assertEquals(CameraMoveOutcome.FAILED, result.outcome)
        assertEquals(File(target, "clip.mp4").path, result.destination)
        assertEquals("original", input.readText())
        assertEquals("original", File(target, "clip.mp4").readText())
        assertTrue(journal.ids.isEmpty())
    }

    @Test fun sourceChangedDuringCopyKeepsSource() {
        val (source, target, store) = setup()
        val input = File(source, "clip.mp4").apply { writeText("original") }
        val changing = object : CameraVideoStorage by store {
            override fun write(id: String): OutputStream {
                val delegate = store.write(id)
                return object : java.io.FilterOutputStream(delegate) {
                    override fun close() { super.close(); input.appendText("changed") }
                }
            }
        }
        val result = CameraVideoMover(changing, Journal()).move(store.scan().single())
        assertEquals(CameraMoveOutcome.SKIPPED, result.outcome)
        assertTrue(input.readText().startsWith("original"))
        assertTrue(target.listFiles()!!.isEmpty())
    }

    @Test fun failedCleanupRetainsOnlyOwnedIdentities() {
        val (source, target, store) = setup()
        File(source, "clip.mp4").writeText("original")
        target.mkdir()
        val unrelated = File(target, ".someone.partial").apply { writeText("unrelated") }
        val broken = object : CameraVideoStorage by store {
            override fun write(id: String): OutputStream { throw IOException("模拟中断") }
            override fun delete(id: String) = false
        }
        val journal = Journal()
        CameraVideoMover(broken, journal).move(store.scan().single())
        assertEquals(1, journal.ids.size)
        assertFalse(unrelated.path in journal.ids)
        assertEquals("unrelated", unrelated.readText())
        // 新执行器不会依据日志自动清理或删除源文件。
        CameraVideoMover(store, journal)
        assertTrue(File(source, "clip.mp4").exists())
        assertTrue(File(journal.ids.single()).exists())
    }

    @Test fun revokedAccessNeverCreatesTarget() {
        val (source, target, store) = setup()
        File(source, "clip.mp4").writeText("original")
        val revoked = object : CameraVideoStorage by store {
            override fun checkAccess() { throw SecurityException("权限已撤回") }
        }
        assertEquals(CameraMoveOutcome.FAILED, CameraVideoMover(revoked, Journal()).move(store.scan().single()).outcome)
        assertFalse(target.exists())
        assertTrue(File(source, "clip.mp4").exists())
    }

    @Test fun mixedBatchRoutesImagesAndVideosWithIndependentCollisions() {
        val (source, target, store) = setup()
        val images = File(target.parentFile, "history_img").apply { mkdir() }
        File(images, "VID_picture.jpg").writeText("existing image")
        File(source, "VID_picture.jpg").writeText("new image")
        File(source, "IMG_keep.jpg").writeText("camera image")
        File(source, "img_keep.mp4").writeText("camera video")
        File(source, "clip.mp4").writeText("video")
        val mover = CameraVideoMover(store, Journal())
        store.scan().forEach { assertEquals(CameraMoveOutcome.SUCCESS, mover.move(it).outcome) }
        assertEquals("new image", File(images, "VID_picture (1).jpg").readText())
        assertEquals("existing image", File(images, "VID_picture.jpg").readText())
        assertEquals("video", File(target, "clip.mp4").readText())
        assertEquals(setOf("IMG_keep.jpg", "img_keep.mp4"), source.list()!!.toSet())
        assertEquals(listOf("clip.mp4"), target.list()!!.toList())
    }
}

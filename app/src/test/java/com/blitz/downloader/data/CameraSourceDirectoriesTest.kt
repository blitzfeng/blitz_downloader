package com.blitz.downloader.data

import com.blitz.downloader.model.CameraMoveOutcome
import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Assume.assumeNoException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CameraSourceDirectoriesTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun storage(dcim: File) = DirectCameraVideoStorage(dcim, File(temporary.root, "Download/history"), {
        when (it.extension) { "mp4" -> "video/mp4"; "jpg" -> "image/jpeg"; else -> null }
    }, isSymbolicLink = { Files.isSymbolicLink(it.toPath()) })

    @Test fun scansOnlyDcimAndCameraFilesWithSharedRules() {
        val dcim = temporary.newFolder("DCIM")
        val camera = File(dcim, "Camera").apply { mkdir() }
        for (directory in listOf(dcim, camera)) {
            File(directory, "clip.mp4").writeText(directory.name)
            File(directory, "photo.jpg").writeText(directory.name)
            File(directory, "IMG_keep.mp4").writeText("keep")
            File(directory, "PaNo_keep.jpg").writeText("keep")
            File(directory, "notes.txt").writeText("keep")
        }
        val screenshots = File(dcim, "Screenshots").apply { mkdir() }
        val nested = File(camera, "nested").apply { mkdir() }
        File(screenshots, "other.mp4").writeText("keep")
        File(nested, "other.jpg").writeText("keep")
        val store = storage(dcim)
        val candidates = store.scan()
        assertEquals(4, candidates.size)
        assertEquals(4, candidates.map { it.id }.toSet().size)
        assertEquals(setOf(dcim.path, camera.path), candidates.map { it.sourceDirectory }.toSet())
        for (candidate in candidates) assertEquals(candidate, store.snapshot(candidate.id))
        assertThrows(IOException::class.java) { store.snapshot(File(screenshots, "other.mp4").path) }
        assertThrows(IOException::class.java) { store.snapshot(File(nested, "other.jpg").path) }
        assertFalse(File(temporary.root, "Download").exists())
    }

    @Test fun missingCameraDoesNotHideDcimCandidates() {
        val dcim = temporary.newFolder("DCIM")
        File(dcim, "clip.mp4").writeText("root")
        assertEquals(listOf("clip.mp4"), storage(dcim).scan().map { it.name })
    }

    @Test fun unreadableRootReportsDirectoryInsteadOfEmptyResults() {
        val dcim = temporary.newFolder("DCIM")
        val unreadable = object : File(dcim.path) {
            override fun listFiles(): Array<File>? = null
        }
        val error = assertThrows(IOException::class.java) { storage(unreadable).scan() }
        assertTrue(error.message.orEmpty().contains(dcim.path))
    }

    @Test fun invalidCameraDoesNotReturnPartialRootScan() {
        val dcim = temporary.newFolder("DCIM")
        File(dcim, "clip.mp4").writeText("root")
        val camera = File(dcim, "Camera").apply { writeText("not a directory") }
        val error = assertThrows(IOException::class.java) { storage(dcim).scan() }
        assertTrue(error.message.orEmpty().contains(camera.path))
    }

    @Test fun sameNamesFromBothSourcesKeepAllContents() {
        val dcim = temporary.newFolder("DCIM")
        val camera = File(dcim, "Camera").apply { mkdir() }
        for (directory in listOf(dcim, camera)) {
            File(directory, "clip.mp4").writeText(directory.name)
            File(directory, "photo.jpg").writeText(directory.name)
        }
        val videoTarget = File(temporary.root, "Download/history").apply { mkdirs() }
        File(videoTarget, "clip.mp4").writeText("existing")
        val store = storage(dcim)
        val journal = object : CameraTemporaryJournal {
            override fun add(id: String) {}
            override fun remove(id: String) {}
        }
        val mover = CameraVideoMover(store, journal)
        store.scan().forEach { assertEquals(CameraMoveOutcome.SUCCESS, mover.move(it).outcome) }
        assertTrue(store.scan().isEmpty())
        assertEquals("existing", File(videoTarget, "clip.mp4").readText())
        assertEquals(setOf("existing", "DCIM", "Camera"), videoTarget.listFiles()!!.map { it.readText() }.toSet())
        val imageTarget = File(temporary.root, "Download/history_img")
        assertEquals(setOf("DCIM", "Camera"), imageTarget.listFiles()!!.map { it.readText() }.toSet())
    }

    @Test fun externalLinksAreNeverScannedOrMoved() {
        val dcim = temporary.newFolder("DCIM")
        val outside = temporary.newFolder("outside")
        val external = File(outside, "clip.mp4").apply { writeText("keep") }
        val link = File(dcim, "linked.mp4")
        try {
            Files.createSymbolicLink(link.toPath(), external.toPath())
        } catch (e: Exception) {
            assumeNoException("当前环境不支持创建符号链接", e)
        }
        val store = storage(dcim)
        assertTrue(store.scan().isEmpty())
        assertThrows(IOException::class.java) { store.snapshot(link.path) }
        Files.createSymbolicLink(File(dcim, "Camera").toPath(), outside.toPath())
        assertThrows(IOException::class.java) { store.scan() }
        assertEquals("keep", external.readText())
    }
}

package com.blitz.downloader.data

import com.blitz.downloader.model.CameraMoveOutcome
import com.blitz.downloader.model.CameraOrganizationPhase
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CameraOrganizationRunnerTest {
    @get:Rule val temporary = TemporaryFolder()
    private val journal = object : CameraTemporaryJournal {
        override fun add(id: String) {}
        override fun remove(id: String) {}
    }
    private fun store(): DirectCameraVideoStorage {
        val source = temporary.newFolder("Camera")
        File(source, "a.mp4").writeText("video")
        File(source, "b.jpg").writeText("image")
        return DirectCameraVideoStorage(source, File(temporary.root, "history"), {
            if (it.extension == "jpg") "image/jpeg" else "video/mp4"
        })
    }
    private fun runner(storage: CameraVideoStorage, refresh: (String, String, Boolean) -> Unit = { _, _, _ -> }) =
        CameraOrganizationRunner(CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), { storage }, journal, { emptyList() }, refresh)

    @Test fun confirmationIdentityAndRepeatClicksDoNotRepeatMoves() {
        val storage = store()
        val runner = runner(storage)
        runner.scan()
        val preview = runner.state.value
        runner.scan()
        assertEquals(preview, runner.state.value)
        runner.confirm("stale")
        assertEquals(preview, runner.state.value)
        runner.confirm(preview.batchId)
        val complete = runner.state.value
        assertEquals(CameraOrganizationPhase.FINISHED, complete.phase)
        assertEquals(2, complete.results.count { it.outcome == CameraMoveOutcome.SUCCESS })
        runner.confirm(preview.batchId)
        assertEquals(complete, runner.state.value)
    }

    @Test fun cancellationDoesNotCreateEitherDestination() {
        val runner = runner(store())
        runner.scan()
        runner.cancelPreview(runner.state.value.batchId)
        assertEquals(CameraOrganizationPhase.IDLE, runner.state.value.phase)
        assertFalse(File(temporary.root, "history").exists())
        assertFalse(File(temporary.root, "history_img").exists())
        assertEquals(2, File(temporary.root, "Camera").list()!!.size)
    }

    @Test fun mixedResultsKeepCountsAndExcludeNewFiles() {
        val storage = store()
        val runner = runner(storage)
        runner.scan()
        File(temporary.root, "Camera/b.jpg").appendText("changed")
        File(temporary.root, "Camera/new.jpg").writeText("new")
        runner.confirm(runner.state.value.batchId)
        val result = runner.state.value
        assertEquals(1, result.results.count { it.outcome == CameraMoveOutcome.SUCCESS })
        assertEquals(1, result.results.count { it.outcome == CameraMoveOutcome.SKIPPED })
        assertEquals(0, result.unprocessed)
        assertTrue(File(temporary.root, "Camera/new.jpg").exists())
    }

    @Test fun revokedPermissionStopsAndRetainsUnprocessedCount() {
        val storage = store()
        var granted = true
        val guarded = object : CameraVideoStorage by storage {
            override fun checkAccess() { if (!granted) throw SecurityException("revoked") }
        }
        val runner = runner(guarded) { _, _, _ -> granted = false }
        runner.scan()
        runner.confirm(runner.state.value.batchId)
        assertEquals(1, runner.state.value.results.size)
        assertEquals(1, runner.state.value.unprocessed)
        assertEquals("revoked", runner.state.value.message)
    }

    @Test fun indexFailureDoesNotTurnSuccessfulMoveIntoFailure() {
        val runner = runner(store()) { _, _, _ -> throw IOException("scanner") }
        runner.scan()
        runner.confirm(runner.state.value.batchId)
        assertEquals(2, runner.state.value.results.size)
        assertTrue(runner.state.value.results.all { it.outcome == CameraMoveOutcome.SUCCESS && it.indexWarning != null })
    }
}

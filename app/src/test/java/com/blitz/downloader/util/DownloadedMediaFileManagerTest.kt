package com.blitz.downloader.util

import com.blitz.downloader.data.DownloadMediaType
import com.blitz.downloader.data.db.DownloadedVideoEntity
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DownloadedMediaFileManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun resolveFile_relativeAndAbsolutePaths() {
        val root = tempFolder.newFolder("storage")
        val rel = DownloadedMediaFileManager.resolveFile("Download/bDouyin/videos/a.mp4", root)
        assertEquals(File(root, "Download/bDouyin/videos/a.mp4").absolutePath, rel.absolutePath)

        val absTarget = File(tempFolder.root, "direct.mp4")
        val abs = DownloadedMediaFileManager.resolveFile(absTarget.absolutePath, root)
        assertEquals(absTarget.absolutePath, abs.absolutePath)

        val empty = DownloadedMediaFileManager.resolveFile("", root)
        assertEquals("", empty.path)
    }

    @Test
    fun findImageSetFiles_scansOnlyMatchingSiblingsAndLivePhotos() {
        val imagesDir = tempFolder.newFolder("images")
        val img1 = File(imagesDir, "creator_dance_01.jpg").apply { writeText("img1") }
        val live1 = File(imagesDir, "creator_dance_01.mp4").apply { writeText("live1") }
        val img2 = File(imagesDir, "creator_dance_02.webp").apply { writeText("img2") }
        val img3 = File(imagesDir, "creator_dance_03.png").apply { writeText("img3") }

        // 不应被匹配的文件：其他作者/作品、非连续编号等
        val other1 = File(imagesDir, "other_author_01.jpg").apply { writeText("other") }
        val other2 = File(imagesDir, "creator_dance_extra.jpg").apply { writeText("other2") }

        val result = DownloadedMediaFileManager.findImageSetFiles(img1)
        val resultNames = result.map { it.name }.toSet()

        assertEquals(4, result.size)
        assertTrue(resultNames.contains(img1.name))
        assertTrue(resultNames.contains(live1.name))
        assertTrue(resultNames.contains(img2.name))
        assertTrue(resultNames.contains(img3.name))
        assertFalse(resultNames.contains(other1.name))
        assertFalse(resultNames.contains(other2.name))
    }

    @Test
    fun resolveEntityFiles_videoEntity_includesMainAndCover() {
        val root = tempFolder.newFolder("root")
        val entity = DownloadedVideoEntity(
            awemeId = "12345",
            downloadType = "post",
            userName = "test_user",
            mediaType = DownloadMediaType.VIDEO,
            filePath = "Download/bDouyin/videos/test.mp4",
            coverPath = "Download/bDouyin/covers/test.jpg",
        )

        val files = DownloadedMediaFileManager.resolveEntityFiles(entity, root)
        assertEquals(2, files.size)
        assertEquals(File(root, entity.filePath).absolutePath, files[0].absolutePath)
        assertEquals(File(root, entity.coverPath).absolutePath, files[1].absolutePath)
    }

    @Test
    fun resolveEntityFiles_imageEntity_includesSiblingImagesWithoutDuplicatingCover() {
        val root = tempFolder.newFolder("root_img")
        val imgDir = File(root, "Download/bDouyin/images").apply { mkdirs() }
        val img1 = File(imgDir, "author_photo_01.jpg").apply { writeText("1") }
        val img2 = File(imgDir, "author_photo_02.jpg").apply { writeText("2") }

        val entity = DownloadedVideoEntity(
            awemeId = "67890",
            downloadType = "post",
            userName = "author",
            mediaType = DownloadMediaType.IMAGE,
            filePath = "Download/bDouyin/images/author_photo_01.jpg",
            coverPath = "Download/bDouyin/images/author_photo_01.jpg",
        )

        val files = DownloadedMediaFileManager.resolveEntityFiles(entity, root)
        assertEquals(2, files.size)
        val fileNames = files.map { it.name }.toSet()
        assertTrue(fileNames.contains(img1.name))
        assertTrue(fileNames.contains(img2.name))
    }

    @Test
    fun deleteMediaFiles_deletesFilesFromDisk() {
        val folder = tempFolder.newFolder("to_delete")
        val f1 = File(folder, "video.mp4").apply { writeText("video") }
        val f2 = File(folder, "cover.jpg").apply { writeText("cover") }
        val f3 = File(folder, "non_existent.mp4")

        assertTrue(f1.exists())
        assertTrue(f2.exists())
        assertFalse(f3.exists())

        val count = DownloadedMediaFileManager.deleteMediaFiles(context = null, files = listOf(f1, f2, f3))

        assertEquals(3, count)
        assertFalse(f1.exists())
        assertFalse(f2.exists())
        assertFalse(f3.exists())
    }

    @Test
    fun findOrphanMediaFiles_detectsOrphanedFilesAccurately() {
        val root = tempFolder.newFolder("storage_orphan")
        val videoDir = File(root, "Download/bDouyin/videos").apply { mkdirs() }
        val imageDir = File(root, "Download/bDouyin/images").apply { mkdirs() }
        val coverDir = File(root, "Download/bDouyin/covers").apply { mkdirs() }

        // 视频：保留的一对 vs 孤儿
        val keptVideo = File(videoDir, "kept_video.mp4").apply { writeText("kept") }
        val orphanVideo = File(videoDir, "orphan_video.mp4").apply { writeText("orphan") }
        val noMediaVideo = File(videoDir, ".nomedia").apply { writeText("") }

        // 封面：保留 vs 孤儿
        val keptCover = File(coverDir, "kept_cover.jpg").apply { writeText("kept") }
        val orphanCover = File(coverDir, "orphan_cover.jpg").apply { writeText("orphan") }

        // 图集：保留的图集 (含兄弟图与实况动图) vs 孤儿图集
        val keptImg1 = File(imageDir, "kept_gallery_01.jpg").apply { writeText("img1") }
        val keptLive1 = File(imageDir, "kept_gallery_01.mp4").apply { writeText("live1") }
        val keptImg2 = File(imageDir, "kept_gallery_02.jpg").apply { writeText("img2") }

        val orphanImg1 = File(imageDir, "orphan_gallery_01.jpg").apply { writeText("orphan1") }
        val orphanImg2 = File(imageDir, "orphan_gallery_02.jpg").apply { writeText("orphan2") }

        val entity1 = DownloadedVideoEntity(
            awemeId = "111",
            downloadType = "post",
            userName = "author1",
            mediaType = DownloadMediaType.VIDEO,
            filePath = "Download/bDouyin/videos/kept_video.mp4",
            coverPath = "Download/bDouyin/covers/kept_cover.jpg",
        )
        val entity2 = DownloadedVideoEntity(
            awemeId = "222",
            downloadType = "post",
            userName = "author2",
            mediaType = DownloadMediaType.IMAGE,
            filePath = "Download/bDouyin/images/kept_gallery_01.jpg",
            coverPath = "Download/bDouyin/images/kept_gallery_01.jpg",
        )

        val orphans = DownloadedMediaFileManager.findOrphanMediaFiles(
            allEntities = listOf(entity1, entity2),
            rootDir = root,
        )

        val orphanNames = orphans.map { it.name }.toSet()
        assertEquals(4, orphans.size)
        assertTrue(orphanNames.contains(orphanVideo.name))
        assertTrue(orphanNames.contains(orphanCover.name))
        assertTrue(orphanNames.contains(orphanImg1.name))
        assertTrue(orphanNames.contains(orphanImg2.name))

        // 验证合法文件未被误判为孤儿
        assertFalse(orphanNames.contains(keptVideo.name))
        assertFalse(orphanNames.contains(keptCover.name))
        assertFalse(orphanNames.contains(keptImg1.name))
        assertFalse(orphanNames.contains(keptLive1.name))
        assertFalse(orphanNames.contains(keptImg2.name))
        // 验证 .nomedia 未被当作孤儿
        assertFalse(orphanNames.contains(noMediaVideo.name))

        // 验证各孤儿文件的类型分类
        val videoOrphan = orphans.first { it.name == orphanVideo.name }
        assertEquals(OrphanMediaKind.VIDEO, videoOrphan.mediaKind)

        val coverOrphan = orphans.first { it.name == orphanCover.name }
        assertEquals(OrphanMediaKind.COVER, coverOrphan.mediaKind)

        val imgOrphan = orphans.first { it.name == orphanImg1.name }
        assertEquals(OrphanMediaKind.IMAGE, imgOrphan.mediaKind)
    }

    @Test
    fun formatFileSize_formatsVariousByteSizes() {
        assertEquals("0 B", NumberFormatUtils.formatFileSize(0L))
        assertEquals("500 B", NumberFormatUtils.formatFileSize(500L))
        assertEquals("1.5 KB", NumberFormatUtils.formatFileSize(1536L))
        assertEquals("20.0 MB", NumberFormatUtils.formatFileSize(20 * 1024 * 1024L))
        assertEquals("1.25 GB", NumberFormatUtils.formatFileSize((1.25 * 1024 * 1024 * 1024).toLong()))
    }

    @Test
    fun normalizeRelativeKey_matchesDifferentPathRepresentations() {
        val key1 = DownloadedMediaFileManager.normalizeRelativeKey("Download/bDouyin/videos/test.mp4")
        val key2 = DownloadedMediaFileManager.normalizeRelativeKey("/storage/emulated/0/Download/bDouyin/videos/test.mp4")
        val key3 = DownloadedMediaFileManager.normalizeRelativeKey("C:\\storage\\Download\\bDouyin\\videos\\test.mp4")
        assertEquals("bdouyin/videos/test.mp4", key1)
        assertEquals("bdouyin/videos/test.mp4", key2)
        assertEquals("bdouyin/videos/test.mp4", key3)
    }

    @Test
    fun findOrphanMediaFiles_withExcessivelyLongFileNameEntity_doesNotCrashAndIdentifiesOrphans() {
        val root = tempFolder.newFolder("storage_long_path")
        val videoDir = File(root, "Download/bDouyin/videos").apply { mkdirs() }
        val orphanFile = File(videoDir, "real_orphan.mp4").apply { writeText("orphan") }

        // 模拟数据库中存在历史遗留的超长文件名记录（如超过 255 字节的中文字符串）
        val superLongName = "超长视频标题".repeat(40) // 240 chars = 720 bytes
        val entityWithSuperLongPath = DownloadedVideoEntity(
            awemeId = "9999999",
            downloadType = "post",
            userName = "long_user",
            mediaType = DownloadMediaType.VIDEO,
            filePath = "Download/bDouyin/videos/${superLongName}.mp4",
            coverPath = "Download/bDouyin/covers/${superLongName}_cover.jpg",
        )

        // 验证不会因 UnixFileSystem.canonicalize0 抛出 IOException: File name too long
        val orphans = DownloadedMediaFileManager.findOrphanMediaFiles(
            allEntities = listOf(entityWithSuperLongPath),
            rootDir = root,
        )

        assertEquals(1, orphans.size)
        assertEquals(orphanFile.name, orphans[0].name)
    }
}

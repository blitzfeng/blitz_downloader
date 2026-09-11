package com.blitz.downloader.data

import com.blitz.downloader.data.db.TagCollectFolderMappingRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TagFolderMappingTest {

    @Test
    fun parseFolderNames_emptyAndBlank_returnsEmptyList() {
        assertTrue(VideoTagRepository.parseFolderNames("").isEmpty())
        assertTrue(VideoTagRepository.parseFolderNames("   ").isEmpty())
        assertTrue(VideoTagRepository.parseFolderNames("  ; ; , \n ").isEmpty())
    }

    @Test
    fun parseFolderNames_singleFolder_returnsTrimmed() {
        val result = VideoTagRepository.parseFolderNames("  技术干货  ")
        assertEquals(listOf("技术干货"), result)
    }

    @Test
    fun parseFolderNames_multipleSeparatorsAndDeduplication() {
        val input = "技术分享; 编程干货,数码，手机\n电脑\r\n知识库；编程干货 ; 技术分享"
        val result = VideoTagRepository.parseFolderNames(input)
        val expected = listOf("技术分享", "编程干货", "数码", "手机", "电脑", "知识库")
        assertEquals(expected, result)
    }

    @Test
    fun folderMappingMatching_matchesMappedTags() {
        val mappings = listOf(
            TagCollectFolderMappingRow(tagName = "科技", collectFolderNames = "技术分享;数码科技"),
            TagCollectFolderMappingRow(tagName = "编程", collectFolderNames = "技术分享;Python教程"),
            TagCollectFolderMappingRow(tagName = "生活", collectFolderNames = "日常Vlog"),
        )

        // 模拟 ensureCollectFolderTagLinked 的匹配逻辑
        fun findMatchedTags(folderName: String): List<String> {
            val name = folderName.trim()
            if (name.isEmpty()) return emptyList()
            return mappings.filter { mapping ->
                VideoTagRepository.parseFolderNames(mapping.collectFolderNames)
                    .any { it.equals(name, ignoreCase = true) }
            }.map { it.tagName }
        }

        // 匹配 "技术分享" 应命中 "科技" 和 "编程"
        assertEquals(listOf("科技", "编程"), findMatchedTags("技术分享"))

        // 匹配 "数码科技" 应只命中 "科技"
        assertEquals(listOf("科技"), findMatchedTags("数码科技"))

        // 大小写不敏感匹配
        assertEquals(listOf("编程"), findMatchedTags("python教程"))

        // 未命中任何映射
        assertTrue(findMatchedTags("美食探店").isEmpty())
    }
}

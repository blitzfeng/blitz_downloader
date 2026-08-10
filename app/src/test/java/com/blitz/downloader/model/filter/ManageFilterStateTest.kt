package com.blitz.downloader.model.filter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 筛选层之间的互斥清理。
 *
 * 这些规则只写在 `with*` 系列里、调用处不手动清，因此一旦漏清就会出现
 * 「标签栏选了 A，精细检索又说不包含 A」这类自相矛盾的条件。
 */
class ManageFilterStateTest {

    private val query = TagQuery("美女", listOf(TagQueryRule(TagQueryOp.AND, "舞蹈")))

    @Test
    fun `默认未激活精细检索`() {
        assertEquals(TagQuery(), ManageFilterState().tagQuery)
    }

    @Test
    fun `withTagQuery 清掉标签 搜索 作者`() {
        val before = ManageFilterState(
            searchQuery = "张三",
            authorSecId = "sec",
            authorName = "张三",
            tags = setOf("舞蹈"),
        )
        val after = before.withTagQuery(query)
        assertEquals(query, after.tagQuery)
        assertEquals(emptySet<String>(), after.tags)
        assertEquals("", after.searchQuery)
        assertEquals("", after.authorSecId)
        assertEquals("", after.authorName)
    }

    @Test
    fun `withTags 清掉精细检索`() {
        val after = ManageFilterState().withTagQuery(query).withTags(setOf("舞蹈"))
        assertEquals(TagQuery(), after.tagQuery)
        assertEquals(setOf("舞蹈"), after.tags)
    }

    @Test
    fun `withAuthor 清掉精细检索`() {
        val after = ManageFilterState().withTagQuery(query).withAuthor("sec", "张三")
        assertEquals(TagQuery(), after.tagQuery)
    }

    @Test
    fun `清空作者筛选时不动精细检索`() {
        val after = ManageFilterState().withTagQuery(query).withAuthor(null, null)
        assertEquals(query, after.tagQuery)
    }

    @Test
    fun `搜索与精细检索互不清除`() {
        // 与「搜索不清标签栏多选」保持同一口径：退出搜索后仍回到之前的标签条件
        val after = ManageFilterState().withTagQuery(query).withSearchQuery("张三")
        assertEquals(query, after.tagQuery)
    }

    @Test
    fun `menuTitleSignature 随精细检索变化`() {
        val a = ManageFilterState()
        val b = a.withTagQuery(query)
        assertNotEquals(a.menuTitleSignature, b.menuTitleSignature)
    }

    @Test
    fun `menuTitleSignature 不随搜索词变化`() {
        val a = ManageFilterState()
        val b = a.withSearchQuery("张三")
        assertEquals(a.menuTitleSignature, b.menuTitleSignature)
    }

    @Test
    fun `精细检索不算内存筛选层标志`() {
        // hasMemoryOnlyFilter 只服务「本来能分页、需要切全量」的那两层；
        // 精细检索自己的取数分支就是一次性全量，不该重复表达
        assertTrue(!ManageFilterState().withTagQuery(query).hasMemoryOnlyFilter)
    }
}

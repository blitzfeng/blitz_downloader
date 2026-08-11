package com.blitz.downloader.model.filter

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [TagQuery] 的左结合求值。
 *
 * 这里是「标签精细检索」唯一有纯 JVM 覆盖的地方：求值语义一旦回归（比如有人顺手
 * 改成「先与后或」），界面上看起来一切正常、只是筛出来的结果不对，很难靠手测发现。
 */
class TagQueryTest {

    /** 标签 → 打了该标签的 awemeId 集合。 */
    private val idsByTag = mapOf(
        "美女" to setOf("a", "b", "c", "d"),
        "舞蹈" to setOf("b", "c"),
        "唱歌" to setOf("e"),
        "广告" to setOf("c", "e"),
    )

    @Test
    fun `未激活时结果为空`() {
        assertEquals(emptySet<String>(), TagQuery().evaluate(idsByTag))
    }

    @Test
    fun `无规则时结果等于基准标签集合`() {
        val q = TagQuery(base = "美女")
        assertEquals(setOf("a", "b", "c", "d"), q.evaluate(idsByTag))
    }

    @Test
    fun `包含取交集`() {
        val q = TagQuery("美女", listOf(TagQueryRule(TagQueryOp.AND, "舞蹈")))
        assertEquals(setOf("b", "c"), q.evaluate(idsByTag))
    }

    @Test
    fun `不包含取差集`() {
        val q = TagQuery("美女", listOf(TagQueryRule(TagQueryOp.NOT, "广告")))
        assertEquals(setOf("a", "b", "d"), q.evaluate(idsByTag))
    }

    @Test
    fun `或取并集`() {
        val q = TagQuery("舞蹈", listOf(TagQueryRule(TagQueryOp.OR, "唱歌")))
        assertEquals(setOf("b", "c", "e"), q.evaluate(idsByTag))
    }

    @Test
    fun `多行从上到下左结合`() {
        // ((美女 ∩ 舞蹈) ∪ 唱歌) − 广告 = ({b,c} ∪ {e}) − {c,e} = {b}
        val q = TagQuery(
            base = "美女",
            rules = listOf(
                TagQueryRule(TagQueryOp.AND, "舞蹈"),
                TagQueryRule(TagQueryOp.OR, "唱歌"),
                TagQueryRule(TagQueryOp.NOT, "广告"),
            ),
        )
        assertEquals(setOf("b"), q.evaluate(idsByTag))
    }

    @Test
    fun `行序影响结果`() {
        // ((美女 ∩ 舞蹈) − 广告) ∪ 唱歌 = ({b,c} − {c,e}) ∪ {e} = {b,e}
        val q = TagQuery(
            base = "美女",
            rules = listOf(
                TagQueryRule(TagQueryOp.AND, "舞蹈"),
                TagQueryRule(TagQueryOp.NOT, "广告"),
                TagQueryRule(TagQueryOp.OR, "唱歌"),
            ),
        )
        assertEquals(setOf("b", "e"), q.evaluate(idsByTag))
    }

    @Test
    fun `标签为空的行被忽略`() {
        val q = TagQuery(
            base = "美女",
            rules = listOf(
                TagQueryRule(TagQueryOp.AND, ""),
                TagQueryRule(TagQueryOp.AND, "舞蹈"),
            ),
        )
        assertEquals(setOf("b", "c"), q.evaluate(idsByTag))
    }

    @Test
    fun `基准标签查无记录时结果为空`() {
        val q = TagQuery("不存在的标签", listOf(TagQueryRule(TagQueryOp.OR, "唱歌")))
        // 基准为空集，或上「唱歌」后仍应得到唱歌那一组——并集不因基准为空而短路
        assertEquals(setOf("e"), q.evaluate(idsByTag))
    }

    @Test
    fun `involvedTags 含基准与各行标签且忽略空行`() {
        val q = TagQuery(
            base = "美女",
            rules = listOf(
                TagQueryRule(TagQueryOp.AND, "舞蹈"),
                TagQueryRule(TagQueryOp.OR, ""),
                TagQueryRule(TagQueryOp.NOT, "舞蹈"),
            ),
        )
        assertEquals(setOf("美女", "舞蹈"), q.involvedTags)
    }

    @Test
    fun `未激活时 involvedTags 为空`() {
        assertEquals(emptySet<String>(), TagQuery().involvedTags)
    }

    @Test
    fun `activeRuleCount 不数没选完的行`() {
        val q = TagQuery(
            base = "美女",
            rules = listOf(
                TagQueryRule(TagQueryOp.AND, "舞蹈"),
                TagQueryRule(TagQueryOp.OR, ""),
            ),
        )
        assertEquals(1, q.activeRuleCount)
    }
}

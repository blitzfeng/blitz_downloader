package com.blitz.downloader.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TagHierarchyTest {

    @Test
    fun ancestorsOf_noParent_returnsEmpty() {
        val parents = mapOf("颜值" to "")
        assertEquals(emptyList<String>(), TagHierarchy.ancestorsOf("颜值", emptyMap()))
    }

    @Test
    fun ancestorsOf_singleParent_returnsThatParent() {
        val parents = mapOf("甜妹" to "颜值")
        assertEquals(listOf("颜值"), TagHierarchy.ancestorsOf("甜妹", parents))
    }

    @Test
    fun ancestorsOf_multiLevelChain_returnsFullChainNearToFar() {
        val parents = mapOf("A" to "B", "B" to "C")
        assertEquals(listOf("B", "C"), TagHierarchy.ancestorsOf("A", parents))
    }

    @Test
    fun ancestorsOf_cycleInData_terminatesAndDoesNotIncludeSelf() {
        // 正常流程下 setParentTag 的环检测会阻止这种数据出现，这里只验证脏数据下不会死循环
        val parents = mapOf("A" to "B", "B" to "A")
        val result = TagHierarchy.ancestorsOf("A", parents)
        assertTrue("A" !in result)
    }

    @Test
    fun descendantsOf_noChildren_returnsEmpty() {
        assertEquals(emptySet<String>(), TagHierarchy.descendantsOf("甜妹", mapOf("甜妹" to "颜值")))
    }

    @Test
    fun descendantsOf_multiLevel_returnsAllLevels() {
        val parents = mapOf("甜妹" to "颜值", "纯欲" to "颜值", "白皙甜妹" to "甜妹")
        val result = TagHierarchy.descendantsOf("颜值", parents)
        assertEquals(setOf("甜妹", "纯欲", "白皙甜妹"), result)
    }

    @Test
    fun pruneAuthorHighFreqTags_emptyCandidates_returnsEmpty() {
        val result = TagHierarchy.pruneAuthorHighFreqTags(
            candidates = emptyList<String>(),
            getTagName = { it },
            parents = emptyMap(),
        )
        assertEquals(emptyList<String>(), result)
    }

    @Test
    fun pruneAuthorHighFreqTags_noHierarchy_takesTopMaxCount() {
        val candidates = listOf("T1", "T2", "T3", "T4", "T5", "T6")
        val result = TagHierarchy.pruneAuthorHighFreqTags(
            candidates = candidates,
            getTagName = { it },
            parents = emptyMap(),
            maxCount = 4,
        )
        assertEquals(listOf("T1", "T2", "T3", "T4"), result)
    }

    @Test
    fun pruneAuthorHighFreqTags_parentAndChildInTop4_dropsParentAndReplenishes() {
        // 用户需求核心场景：前 4 包含「颜值」与「纯欲」，剔除「颜值」，顺延增选「可爱」
        val candidates = listOf("颜值", "纯欲", "穿搭", "舞蹈", "可爱", "美食")
        val parents = mapOf(
            "纯欲" to "颜值",
            "可爱" to "颜值",
        )
        val result = TagHierarchy.pruneAuthorHighFreqTags(
            candidates = candidates,
            getTagName = { it },
            parents = parents,
            maxCount = 4,
        )
        assertEquals(listOf("纯欲", "穿搭", "舞蹈", "可爱"), result)
    }

    @Test
    fun pruneAuthorHighFreqTags_multiLevelHierarchy_dropsAllAncestors() {
        // 白皙甜妹 -> 甜妹 -> 颜值
        val candidates = listOf("颜值", "甜妹", "白皙甜妹", "穿搭", "舞蹈")
        val parents = mapOf(
            "甜妹" to "颜值",
            "白皙甜妹" to "甜妹",
        )
        val result = TagHierarchy.pruneAuthorHighFreqTags(
            candidates = candidates,
            getTagName = { it },
            parents = parents,
            maxCount = 4,
        )
        // 颜值与甜妹均被白皙甜妹剔除，从剩余候选增选 舞蹈，最终为 白皙甜妹、穿搭、舞蹈
        assertEquals(listOf("白皙甜妹", "穿搭", "舞蹈"), result)
    }

    @Test
    fun pruneAuthorHighFreqTags_ancestorOutsideTop4_skippedDuringReplenishment() {
        // 子标签在前 4，其父标签在第 5 位：增选时不应重新把父标签选入
        val candidates = listOf("纯欲", "穿搭", "舞蹈", "美食", "颜值", "游戏")
        val parents = mapOf("纯欲" to "颜值")
        val result = TagHierarchy.pruneAuthorHighFreqTags(
            candidates = candidates,
            getTagName = { it },
            parents = parents,
            maxCount = 4,
        )
        assertEquals(listOf("纯欲", "穿搭", "舞蹈", "美食"), result)
    }

    @Test
    fun pruneAuthorHighFreqTags_descendantOutsideTop4_skippedDuringReplenishment() {
        // 前 4 中「颜值」与「纯欲」并存，剔除「颜值」后待增选 1 个；
        // 候选池下一位是「白皙纯欲」（「纯欲」的子标签）：由于父标签「纯欲」已入选，跳过「白皙纯欲」，增选「游戏」
        val candidates = listOf("颜值", "纯欲", "穿搭", "舞蹈", "白皙纯欲", "游戏")
        val parents = mapOf(
            "纯欲" to "颜值",
            "白皙纯欲" to "纯欲",
        )
        val result = TagHierarchy.pruneAuthorHighFreqTags(
            candidates = candidates,
            getTagName = { it },
            parents = parents,
            maxCount = 4,
        )
        assertEquals(listOf("纯欲", "穿搭", "舞蹈", "游戏"), result)
    }

    @Test
    fun pruneAuthorHighFreqTags_fewerThanMaxCount_returnsAllPrunedWithoutPadding() {
        val candidates = listOf("颜值", "纯欲")
        val parents = mapOf("纯欲" to "颜值")
        val result = TagHierarchy.pruneAuthorHighFreqTags(
            candidates = candidates,
            getTagName = { it },
            parents = parents,
            maxCount = 4,
        )
        assertEquals(listOf("纯欲"), result)
    }
}

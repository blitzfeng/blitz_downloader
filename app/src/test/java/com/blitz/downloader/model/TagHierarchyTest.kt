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
}

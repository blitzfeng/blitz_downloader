package com.blitz.downloader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadedVideoRepositoryRelationTest {

    @Test
    fun buildUserRelationFromPost_liked_returnsLike() {
        assertEquals("like", DownloadedVideoRepository.buildUserRelationFromPost(userDigged = 1, collectStat = 0))
    }

    @Test
    fun buildUserRelationFromPost_likedAndCollected_returnsLikeCollect() {
        assertEquals("like|collect", DownloadedVideoRepository.buildUserRelationFromPost(userDigged = 1, collectStat = 1))
    }

    @Test
    fun buildUserRelationFromPost_onlyCollected_returnsCollect() {
        assertEquals("collect", DownloadedVideoRepository.buildUserRelationFromPost(userDigged = 0, collectStat = 1))
    }

    @Test
    fun buildUserRelationFromPost_neither_returnsEmpty() {
        assertEquals("", DownloadedVideoRepository.buildUserRelationFromPost(userDigged = 0, collectStat = 0))
    }

    @Test
    fun buildUserRelationFromLike_matchesExpected() {
        assertEquals("like", DownloadedVideoRepository.buildUserRelationFromLike(collectStat = 0))
        assertEquals("like|collect", DownloadedVideoRepository.buildUserRelationFromLike(collectStat = 1))
    }

    @Test
    fun buildUserRelationFromCollection_matchesExpected() {
        assertEquals("like|舞蹈", DownloadedVideoRepository.buildUserRelationFromCollection(userDigged = 1, folderName = "舞蹈"))
        assertEquals("舞蹈", DownloadedVideoRepository.buildUserRelationFromCollection(userDigged = 0, folderName = "舞蹈"))
    }

    @Test
    fun hasLikeRelation_variousFormats() {
        assertTrue(DownloadedVideoRepository.hasLikeRelation("like"))
        assertTrue(DownloadedVideoRepository.hasLikeRelation("like|collect"))
        assertTrue(DownloadedVideoRepository.hasLikeRelation("collect|like"))
        assertTrue(DownloadedVideoRepository.hasLikeRelation("like|舞蹈"))
        assertTrue(DownloadedVideoRepository.hasLikeRelation("LIKE"))

        assertFalse(DownloadedVideoRepository.hasLikeRelation("dislike"))
        assertFalse(DownloadedVideoRepository.hasLikeRelation("likes"))
        assertFalse(DownloadedVideoRepository.hasLikeRelation("collect"))
        assertFalse(DownloadedVideoRepository.hasLikeRelation(""))
        assertFalse(DownloadedVideoRepository.hasLikeRelation(null))
    }
}

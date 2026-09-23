package com.blitz.downloader.data

import com.blitz.downloader.data.db.LikedListIndexSourceType

/** 来源键把类型、所有者与未来收藏夹标识同时纳入身份，避免相同 awemeId 串表。 */
fun likedListIndexSourceKey(ownerSecUserId: String, collectionId: String? = null): String =
    "${LikedListIndexSourceType.LIKE}|$ownerSecUserId|${collectionId.orEmpty()}"

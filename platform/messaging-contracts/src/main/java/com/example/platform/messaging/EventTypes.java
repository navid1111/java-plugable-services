package com.example.platform.messaging;

import java.util.Set;

public final class EventTypes {
    public static final String POST_CREATED_V1 = "post.created.v1";
    public static final String POST_UPDATED_V1 = "post.updated.v1";
    public static final String POST_DELETED_V1 = "post.deleted.v1";
    public static final String POST_LIKE_COUNT_CHANGED_V1 = "post.like-count-changed.v1";
    public static final String FOLLOW_CREATED_V1 = "follow.created.v1";
    public static final String FOLLOW_DELETED_V1 = "follow.deleted.v1";
    public static final String USER_REGISTERED_V1 = "user.registered.v1";
    public static final String USER_PROFILE_UPDATED_V1 = "user.profile-updated.v1";
    public static final String USER_DEACTIVATED_V1 = "user.deactivated.v1";
    public static final String COMMENT_CREATED_V1 = "comment.created.v1";
    public static final String COMMENT_DELETED_V1 = "comment.deleted.v1";
    public static final String MEDIA_UPLOADED_V1 = "media.uploaded.v1";
    public static final String MEDIA_PROCESSING_COMPLETED_V1 = "media.processing-completed.v1";
    public static final String MEDIA_PROCESSING_FAILED_V1 = "media.processing-failed.v1";
    public static final String MEDIA_DELETED_V1 = "media.deleted.v1";
    public static final String LEETCODE_JUDGE_REQUESTED_V1 =
            "leetcode.submission.judge.requested.v1";
    public static final String LEETCODE_JUDGE_COMPLETED_V1 =
            "leetcode.submission.judge.completed.v1";

    public static final Set<String> REGISTERED = Set.of(
            POST_CREATED_V1, POST_UPDATED_V1, POST_DELETED_V1,
            POST_LIKE_COUNT_CHANGED_V1, FOLLOW_CREATED_V1, FOLLOW_DELETED_V1,
            USER_REGISTERED_V1, USER_PROFILE_UPDATED_V1, USER_DEACTIVATED_V1,
            COMMENT_CREATED_V1, COMMENT_DELETED_V1, MEDIA_UPLOADED_V1,
            MEDIA_PROCESSING_COMPLETED_V1, MEDIA_PROCESSING_FAILED_V1, MEDIA_DELETED_V1,
            LEETCODE_JUDGE_REQUESTED_V1, LEETCODE_JUDGE_COMPLETED_V1);

    private EventTypes() {}
}

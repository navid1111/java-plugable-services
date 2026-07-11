package com.example.platform.messaging;

import java.util.Set;

public final class EventTypes {
    public static final String POST_CREATED_V1 = "post.created.v1";
    public static final String POST_UPDATED_V1 = "post.updated.v1";
    public static final String POST_DELETED_V1 = "post.deleted.v1";
    public static final String POST_LIKE_COUNT_CHANGED_V1 = "post.like-count-changed.v1";
    public static final String FOLLOW_CREATED_V1 = "follow.created.v1";
    public static final String FOLLOW_DELETED_V1 = "follow.deleted.v1";

    public static final Set<String> REGISTERED = Set.of(
            POST_CREATED_V1, POST_UPDATED_V1, POST_DELETED_V1,
            POST_LIKE_COUNT_CHANGED_V1, FOLLOW_CREATED_V1, FOLLOW_DELETED_V1);

    private EventTypes() {}
}

package com.example.tweeter.service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.tweeter.model.Post;
import com.example.tweeter.repository.FollowRepository;
import com.example.tweeter.repository.PostRepository;
import com.example.platform.messaging.EventEnvelope;
import com.example.platform.messaging.EventTypes;
import com.example.platform.messaging.post.PostSnapshot;
import com.example.platform.messaging.post.FollowChanged;
import com.example.platform.messaging.support.OutboxMessage;
import com.example.platform.messaging.support.OutboxMessageRepository;
import com.example.platform.messaging.support.SafeEventSerializer;

import tools.jackson.databind.ObjectMapper;

@Service
public class PostService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final PostRepository posts;
    private final FollowRepository follows;
    private final OutboxMessageRepository outbox;
    private final SafeEventSerializer eventSerializer;

    public PostService(PostRepository posts, FollowRepository follows,
            OutboxMessageRepository outbox, ObjectMapper objectMapper) {
        this.posts = posts;
        this.follows = follows;
        this.outbox = outbox;
        this.eventSerializer = new SafeEventSerializer(objectMapper);
    }

    public record FeedPage(List<Post> items, String nextCursor) {
    }

    private record FeedCursor(Instant createdAt, Long id) {
    }

    @Transactional
    public Post create(String authorUsername, String content) {
        return create(null, authorUsername, content);
    }

    @Transactional
    public Post create(String authorUserId, String authorUsername, String content) {
        String trimmed = requireText(content, "content");
        if (trimmed.length() > 280) {
            throw new IllegalArgumentException("content must be 280 characters or fewer");
        }
        Post post = posts.saveAndFlush(new Post(authorUserId, authorUsername, trimmed));
        emitSnapshot(EventTypes.POST_CREATED_V1, post);
        return post;
    }

    @Transactional
    public Post update(Long id, String actorUsername, String content, long expectedVersion) {
        Post post = ownedActivePost(id, actorUsername);
        requireVersion(post, expectedVersion);
        String trimmed = requireText(content, "content");
        if (trimmed.length() > 280) {
            throw new IllegalArgumentException("content must be 280 characters or fewer");
        }
        post.updateContent(trimmed);
        posts.flush();
        emitSnapshot(EventTypes.POST_UPDATED_V1, post);
        return post;
    }

    @Transactional
    public Post delete(Long id, String actorUsername, long expectedVersion) {
        Post post = ownedActivePost(id, actorUsername);
        requireVersion(post, expectedVersion);
        post.delete(Instant.now());
        posts.flush();
        emit(EventTypes.POST_DELETED_V1, post,
                new com.example.platform.messaging.post.PostDeleted(
                        post.getId().toString(), actorUsername, post.getDeletedAt()));
        return post;
    }

    private Post ownedActivePost(Long id, String actorUsername) {
        Post post = posts.findById(id).orElseThrow(() -> new IllegalArgumentException("post not found"));
        if (!post.getAuthorUsername().equals(requireText(actorUsername, "username"))) {
            throw new IllegalArgumentException("only the author may change this post");
        }
        if (post.isDeleted()) throw new IllegalArgumentException("post is deleted");
        return post;
    }

    private void requireVersion(Post post, long expectedVersion) {
        long currentVersion = post.getVersion() + 1;
        if (expectedVersion != currentVersion) {
            throw new org.springframework.dao.OptimisticLockingFailureException(
                    "post version conflict: expected " + expectedVersion + ", current " + currentVersion);
        }
    }

    private void emitSnapshot(String eventType, Post post) {
        emit(eventType, post, new PostSnapshot(post.getId().toString(), post.getAuthorUserId(), post.getAuthorUsername(),
                post.getContent(), "public", post.getCreatedAt(), post.getUpdatedAt()));
    }

    private void emit(String eventType, Post post, Object payload) {
        EventEnvelope<Object> event = EventEnvelope.fact(
                eventType, 1, "tweeter-service", "post", post.getId().toString(),
                post.getVersion() + 1, UUID.randomUUID(), null, null, payload);
        outbox.save(new OutboxMessage(event.eventId(), event.aggregateType(), event.aggregateId(),
                event.eventType(), event.eventVersion(), eventSerializer.serialize(event), event.occurredAt()));
    }

    private void emitFollow(String eventType, String follower, String followee) {
        String aggregateId = follower + "->" + followee;
        EventEnvelope<FollowChanged> event = EventEnvelope.fact(eventType, 1, "tweeter-service",
                "follow", aggregateId, 1, UUID.randomUUID(), null, null,
                new FollowChanged("legacy:" + follower, "legacy:" + followee, Instant.now()));
        outbox.save(new OutboxMessage(event.eventId(), event.aggregateType(), event.aggregateId(),
                event.eventType(), event.eventVersion(), eventSerializer.serialize(event), event.occurredAt()));
    }

    @Transactional(readOnly = true)
    public Optional<Post> findById(Long id) {
        return posts.findById(id);
    }

    @Transactional(readOnly = true)
    public List<Post> findByAuthor(String authorUsername) {
        return posts.findByAuthorUsernameOrderByCreatedAtDescIdDesc(authorUsername);
    }
    @Transactional(readOnly = true)
    public List<Post> findByAuthorUserId(String authorUserId) {
        return posts.findByAuthorUserIdOrderByCreatedAtDescIdDesc(authorUserId);
    }

    @Transactional
    public void follow(String followerUsername, String followeeUsername) {
        follow(null, followerUsername, followeeUsername);
    }

    @Transactional
    public void follow(String followerUserId, String followerUsername, String followeeUsername) {
        String follower = requireText(followerUsername, "follower username");
        String followee = requireText(followeeUsername, "followee username");
        if (follower.equals(followee)) {
            throw new IllegalArgumentException("cannot follow yourself");
        }
        if (follows.insertIfMissing(followerUserId, follower, followee) == 1) {
            emitFollow(EventTypes.FOLLOW_CREATED_V1, follower, followee);
        }
    }

    @Transactional
    public void unfollow(String followerUsername, String followeeUsername) {
        String follower = requireText(followerUsername, "follower username");
        String followee = requireText(followeeUsername, "followee username");
        if (follows.deleteByFollowerUsernameAndFolloweeUsername(follower, followee) == 1) {
            emitFollow(EventTypes.FOLLOW_DELETED_V1, follower, followee);
        }
    }

    @Transactional(readOnly = true)
    public FeedPage feed(String username, String cursor, int requestedPageSize) {
        return feed(null, username, cursor, requestedPageSize);
    }

    @Transactional(readOnly = true)
    public FeedPage feed(String userId, String username, String cursor, int requestedPageSize) {
        String follower = requireText(username, "username");
        int pageSize = clampPageSize(requestedPageSize);
        int fetchSize = pageSize + 1;

        List<Post> fetched;
        if (cursor == null || cursor.isBlank()) {
            fetched = posts.findFeedFirstPage(userId, follower, fetchSize);
        } else {
            FeedCursor parsed = decodeCursor(cursor);
            fetched = posts.findFeedAfterCursor(userId, follower, parsed.createdAt(), parsed.id(), fetchSize);
        }

        boolean hasMore = fetched.size() > pageSize;
        List<Post> pageItems = hasMore ? fetched.subList(0, pageSize) : fetched;
        String nextCursor = hasMore ? encodeCursor(pageItems.get(pageItems.size() - 1)) : null;
        return new FeedPage(pageItems, nextCursor);
    }

    private int clampPageSize(int requestedPageSize) {
        if (requestedPageSize <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(requestedPageSize, MAX_PAGE_SIZE);
    }

    private String encodeCursor(Post post) {
        String raw = post.getCreatedAt() + "|" + post.getId();
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private FeedCursor decodeCursor(String cursor) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = raw.split("\\|", 2);
            if (parts.length != 2) {
                throw new IllegalArgumentException("invalid cursor");
            }
            return new FeedCursor(Instant.parse(parts[0]), Long.parseLong(parts[1]));
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid cursor");
        }
    }

    private String requireText(String value, String fieldName) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return value.trim();
    }
}

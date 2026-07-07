package com.example.tweeter.service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.tweeter.model.Post;
import com.example.tweeter.repository.FollowRepository;
import com.example.tweeter.repository.PostRepository;

@Service
public class PostService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final PostRepository posts;
    private final FollowRepository follows;

    public PostService(PostRepository posts, FollowRepository follows) {
        this.posts = posts;
        this.follows = follows;
    }

    public record FeedPage(List<Post> items, String nextCursor) {
    }

    private record FeedCursor(Instant createdAt, Long id) {
    }

    @Transactional
    public Post create(String authorUsername, String content) {
        String trimmed = requireText(content, "content");
        if (trimmed.length() > 280) {
            throw new IllegalArgumentException("content must be 280 characters or fewer");
        }
        return posts.save(new Post(authorUsername, trimmed));
    }

    @Transactional(readOnly = true)
    public Optional<Post> findById(Long id) {
        return posts.findById(id);
    }

    @Transactional(readOnly = true)
    public List<Post> findByAuthor(String authorUsername) {
        return posts.findByAuthorUsernameOrderByCreatedAtDescIdDesc(authorUsername);
    }

    @Transactional
    public void follow(String followerUsername, String followeeUsername) {
        String follower = requireText(followerUsername, "follower username");
        String followee = requireText(followeeUsername, "followee username");
        if (follower.equals(followee)) {
            throw new IllegalArgumentException("cannot follow yourself");
        }
        follows.insertIfMissing(follower, followee);
    }

    @Transactional
    public void unfollow(String followerUsername, String followeeUsername) {
        String follower = requireText(followerUsername, "follower username");
        String followee = requireText(followeeUsername, "followee username");
        follows.deleteByFollowerUsernameAndFolloweeUsername(follower, followee);
    }

    @Transactional(readOnly = true)
    public FeedPage feed(String username, String cursor, int requestedPageSize) {
        String follower = requireText(username, "username");
        int pageSize = clampPageSize(requestedPageSize);
        int fetchSize = pageSize + 1;

        List<Post> fetched;
        if (cursor == null || cursor.isBlank()) {
            fetched = posts.findFeedFirstPage(follower, fetchSize);
        } else {
            FeedCursor parsed = decodeCursor(cursor);
            fetched = posts.findFeedAfterCursor(follower, parsed.createdAt(), parsed.id(), fetchSize);
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

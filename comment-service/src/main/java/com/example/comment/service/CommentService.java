package com.example.comment.service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.comment.model.Comment;
import com.example.comment.repository.CommentRepository;
import com.example.platform.messaging.support.TargetProjectionStore;

@Service
public class CommentService {

    private static final int MAX_CONTENT_LENGTH = 500;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_TARGET_TYPE_LENGTH = 100;
    private static final int MAX_TARGET_ID_LENGTH = 200;
    private static final Pattern TARGET_TYPE = Pattern.compile("[A-Za-z][A-Za-z0-9_.-]*");
    private static final Pattern TARGET_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.:-]*");

    private final CommentRepository comments;
    private final TargetProjectionStore targets;

    public CommentService(CommentRepository comments, TargetProjectionStore targets) {
        this.comments = comments;
        this.targets = targets;
    }

    public record CommentPage(List<Comment> items, String nextCursor) {
    }

    private record CommentCursor(Instant createdAt, Long id) {
    }

    @Transactional
    public Comment create(String authorUsername, String targetType, String targetId, String content) {
        String author = requireText(authorUsername, "author username");
        String type = requireTargetType(targetType);
        String id = requireTargetId(targetId);
        targets.requireActive(type, id);
        String trimmed = requireText(content, "content");
        if (trimmed.length() > MAX_CONTENT_LENGTH) {
            throw new IllegalArgumentException("content must be 500 characters or fewer");
        }
        return comments.save(new Comment(type, id, author, trimmed));
    }

    @Transactional(readOnly = true)
    public Optional<Comment> findById(Long id) {
        return comments.findById(id);
    }

    @Transactional(readOnly = true)
    public CommentPage findByTarget(String targetType, String targetId, String cursor, int requestedPageSize) {
        String type = requireTargetType(targetType);
        String id = requireTargetId(targetId);
        targets.requireActive(type, id);
        int pageSize = clampPageSize(requestedPageSize);
        int fetchSize = pageSize + 1;

        List<Comment> fetched;
        if (cursor == null || cursor.isBlank()) {
            fetched = comments.findTargetFirstPage(type, id, fetchSize);
        } else {
            CommentCursor parsed = decodeCursor(cursor);
            fetched = comments.findTargetAfterCursor(type, id, parsed.createdAt(), parsed.id(), fetchSize);
        }

        boolean hasMore = fetched.size() > pageSize;
        List<Comment> pageItems = hasMore ? fetched.subList(0, pageSize) : fetched;
        String nextCursor = hasMore ? encodeCursor(pageItems.get(pageItems.size() - 1)) : null;
        return new CommentPage(pageItems, nextCursor);
    }

    @Transactional
    public void deleteOwn(String username, Long commentId) {
        String requester = requireText(username, "username");
        Comment comment = comments.findById(commentId)
                .orElseThrow(() -> new NotFoundException("comment not found"));
        if (!comment.getAuthorUsername().equals(requester)) {
            throw new ForbiddenException("cannot delete another user's comment");
        }
        comments.delete(comment);
    }

    private int clampPageSize(int requestedPageSize) {
        if (requestedPageSize <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(requestedPageSize, MAX_PAGE_SIZE);
    }

    private String encodeCursor(Comment comment) {
        String raw = comment.getCreatedAt() + "|" + comment.getId();
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private CommentCursor decodeCursor(String cursor) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = raw.split("\\|", 2);
            if (parts.length != 2) {
                throw new IllegalArgumentException("invalid cursor");
            }
            return new CommentCursor(Instant.parse(parts[0]), Long.parseLong(parts[1]));
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid cursor");
        }
    }

    private String requireTargetType(String value) {
        String trimmed = requireText(value, "targetType");
        if (trimmed.length() > MAX_TARGET_TYPE_LENGTH || !TARGET_TYPE.matcher(trimmed).matches()) {
            throw new IllegalArgumentException("targetType must start with a letter and contain only letters, numbers, dots, underscores, or hyphens");
        }
        return trimmed;
    }

    private String requireTargetId(String value) {
        String trimmed = requireText(value, "targetId");
        if (trimmed.length() > MAX_TARGET_ID_LENGTH || !TARGET_ID.matcher(trimmed).matches()) {
            throw new IllegalArgumentException("targetId must contain only letters, numbers, dots, underscores, hyphens, or colons");
        }
        return trimmed;
    }

    private String requireText(String value, String fieldName) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return value.trim();
    }

    public static class NotFoundException extends RuntimeException {
        public NotFoundException(String message) {
            super(message);
        }
    }

    public static class ForbiddenException extends RuntimeException {
        public ForbiddenException(String message) {
            super(message);
        }
    }
}

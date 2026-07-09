package com.example.media.service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.example.media.cloudinary.CloudinaryClient;
import com.example.media.model.MediaAsset;
import com.example.media.repository.MediaAssetRepository;

@Service
public class MediaService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_TARGET_TYPE_LENGTH = 100;
    private static final int MAX_TARGET_ID_LENGTH = 200;
    private static final int MAX_CAPTION_LENGTH = 500;
    private static final int MAX_ALT_TEXT_LENGTH = 500;
    private static final Pattern TARGET_TYPE = Pattern.compile("[A-Za-z][A-Za-z0-9_.-]*");
    private static final Pattern TARGET_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.:-]*");

    private final MediaAssetRepository assets;
    private final CloudinaryClient cloudinary;
    private final long maxImageBytes;
    private final long maxVideoBytes;
    private final Set<String> allowedImageFormats;
    private final Set<String> allowedVideoFormats;

    public MediaService(
            MediaAssetRepository assets,
            CloudinaryClient cloudinary,
            @Value("${MEDIA_MAX_IMAGE_BYTES:10485760}") long maxImageBytes,
            @Value("${MEDIA_MAX_VIDEO_BYTES:104857600}") long maxVideoBytes,
            @Value("${MEDIA_ALLOWED_IMAGE_FORMATS:jpg,jpeg,png,gif,webp}") String allowedImageFormats,
            @Value("${MEDIA_ALLOWED_VIDEO_FORMATS:mp4,mov,webm}") String allowedVideoFormats) {
        this.assets = assets;
        this.cloudinary = cloudinary;
        this.maxImageBytes = maxImageBytes;
        this.maxVideoBytes = maxVideoBytes;
        this.allowedImageFormats = csvSet(allowedImageFormats);
        this.allowedVideoFormats = csvSet(allowedVideoFormats);
    }

    public record MediaPage(List<MediaAsset> items, String nextCursor) {
    }

    private record MediaCursor(Instant createdAt, Long id) {
    }

    private record MediaType(String resourceType, String format, long maxBytes) {
    }

    @Transactional
    public MediaAsset upload(
            String uploaderUsername,
            String targetType,
            String targetId,
            MultipartFile file,
            String caption,
            String altText) {
        String uploader = requireText(uploaderUsername, "username");
        String type = requireTargetType(targetType);
        String id = requireTargetId(targetId);
        String trimmedCaption = optionalText(caption, "caption", MAX_CAPTION_LENGTH);
        String trimmedAltText = optionalText(altText, "altText", MAX_ALT_TEXT_LENGTH);
        MediaType mediaType = validateFile(file);

        CloudinaryClient.UploadResult upload = cloudinary.upload(file, mediaType.resourceType());
        try {
            MediaAsset asset = new MediaAsset(
                    type,
                    id,
                    uploader,
                    upload.publicId(),
                    upload.resourceType(),
                    upload.format() == null || upload.format().isBlank() ? mediaType.format() : upload.format(),
                    upload.secureUrl(),
                    upload.thumbnailUrl(),
                    originalFilename(file),
                    upload.bytes() > 0 ? upload.bytes() : file.getSize(),
                    upload.width(),
                    upload.height(),
                    upload.durationSeconds(),
                    trimmedCaption,
                    trimmedAltText);
            return assets.save(asset);
        } catch (RuntimeException e) {
            try {
                cloudinary.destroy(upload.publicId(), upload.resourceType());
            } catch (RuntimeException ignored) {
                // Best-effort cleanup. Preserve the original database failure.
            }
            throw e;
        }
    }

    @Transactional(readOnly = true)
    public Optional<MediaAsset> findById(Long id) {
        return assets.findByIdAndDeletedAtIsNull(id);
    }

    @Transactional(readOnly = true)
    public MediaPage findByTarget(String targetType, String targetId, String cursor, int requestedPageSize) {
        String type = requireTargetType(targetType);
        String id = requireTargetId(targetId);
        int pageSize = clampPageSize(requestedPageSize);
        int fetchSize = pageSize + 1;

        List<MediaAsset> fetched;
        if (cursor == null || cursor.isBlank()) {
            fetched = assets.findTargetFirstPage(type, id, fetchSize);
        } else {
            MediaCursor parsed = decodeCursor(cursor);
            fetched = assets.findTargetAfterCursor(type, id, parsed.createdAt(), parsed.id(), fetchSize);
        }

        boolean hasMore = fetched.size() > pageSize;
        List<MediaAsset> pageItems = hasMore ? fetched.subList(0, pageSize) : fetched;
        String nextCursor = hasMore ? encodeCursor(pageItems.get(pageItems.size() - 1)) : null;
        return new MediaPage(pageItems, nextCursor);
    }

    @Transactional
    public void deleteOwn(String username, Long mediaId) {
        String requester = requireText(username, "username");
        MediaAsset asset = assets.findByIdAndDeletedAtIsNull(mediaId)
                .orElseThrow(() -> new NotFoundException("media asset not found"));
        if (!asset.getUploaderUsername().equals(requester)) {
            throw new ForbiddenException("cannot delete another user's media");
        }
        cloudinary.destroy(asset.getPublicId(), asset.getResourceType());
        asset.markDeleted();
        assets.save(asset);
    }

    private MediaType validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("file is required");
        }
        String format = extension(originalFilename(file));
        String contentType = file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT);

        boolean image = contentType.startsWith("image/") || allowedImageFormats.contains(format);
        boolean video = contentType.startsWith("video/") || allowedVideoFormats.contains(format);

        if (image && allowedImageFormats.contains(format)) {
            if (file.getSize() > maxImageBytes) {
                throw new PayloadTooLargeException("image exceeds configured size limit");
            }
            return new MediaType("image", format, maxImageBytes);
        }
        if (video && allowedVideoFormats.contains(format)) {
            if (file.getSize() > maxVideoBytes) {
                throw new PayloadTooLargeException("video exceeds configured size limit");
            }
            return new MediaType("video", format, maxVideoBytes);
        }
        throw new IllegalArgumentException("file must be an allowed image or video format");
    }

    private String originalFilename(MultipartFile file) {
        String filename = file.getOriginalFilename();
        if (filename == null || filename.isBlank()) {
            return "upload";
        }
        return filename.trim();
    }

    private String extension(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            throw new IllegalArgumentException("file extension is required");
        }
        return filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private int clampPageSize(int requestedPageSize) {
        if (requestedPageSize <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(requestedPageSize, MAX_PAGE_SIZE);
    }

    private String encodeCursor(MediaAsset asset) {
        String raw = asset.getCreatedAt() + "|" + asset.getId();
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private MediaCursor decodeCursor(String cursor) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = raw.split("\\|", 2);
            if (parts.length != 2) {
                throw new IllegalArgumentException("invalid cursor");
            }
            return new MediaCursor(Instant.parse(parts[0]), Long.parseLong(parts[1]));
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

    private String optionalText(String value, String fieldName, int maxLength) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() > maxLength) {
            throw new IllegalArgumentException(fieldName + " must be " + maxLength + " characters or fewer");
        }
        return trimmed;
    }

    private Set<String> csvSet(String value) {
        return Arrays.stream(value.split(","))
                .map(item -> item.trim().toLowerCase(Locale.ROOT))
                .filter(item -> !item.isBlank())
                .collect(Collectors.toUnmodifiableSet());
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

    public static class PayloadTooLargeException extends RuntimeException {
        public PayloadTooLargeException(String message) {
            super(message);
        }
    }
}

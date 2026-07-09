package com.example.media.controller;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;

import com.example.media.cloudinary.CloudinaryClient;
import com.example.media.model.MediaAsset;
import com.example.media.security.JwtHelper;
import com.example.media.service.MediaService;

@RestController
@RequestMapping("/media")
public class MediaController {

    private final MediaService media;
    private final JwtHelper jwtHelper;

    public MediaController(MediaService media, JwtHelper jwtHelper) {
        this.media = media;
        this.jwtHelper = jwtHelper;
    }

    public record MediaAssetResponse(
            Long id,
            String targetType,
            String targetId,
            String uploaderUsername,
            String publicId,
            String resourceType,
            String format,
            String secureUrl,
            String thumbnailUrl,
            String originalFilename,
            long bytes,
            Integer width,
            Integer height,
            Double durationSeconds,
            String caption,
            String altText,
            Instant createdAt) {
        static MediaAssetResponse from(MediaAsset asset) {
            return new MediaAssetResponse(
                    asset.getId(),
                    asset.getTargetType(),
                    asset.getTargetId(),
                    asset.getUploaderUsername(),
                    asset.getPublicId(),
                    asset.getResourceType(),
                    asset.getFormat(),
                    asset.getSecureUrl(),
                    asset.getThumbnailUrl(),
                    asset.getOriginalFilename(),
                    asset.getBytes(),
                    asset.getWidth(),
                    asset.getHeight(),
                    asset.getDurationSeconds(),
                    asset.getCaption(),
                    asset.getAltText(),
                    asset.getCreatedAt());
        }
    }

    public record TargetMediaResponse(List<MediaAssetResponse> items, String nextCursor) {
    }

    @PostMapping(value = "/targets/{targetType}/{targetId}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> upload(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String targetType,
            @PathVariable String targetId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) String caption,
            @RequestParam(required = false) String altText) {
        try {
            String username = jwtHelper.extractUsername(authorization);
            MediaAsset asset = media.upload(username, targetType, targetId, file, caption, altText);
            return ResponseEntity.status(HttpStatus.CREATED).body(MediaAssetResponse.from(asset));
        } catch (MediaService.PayloadTooLargeException e) {
            return error(HttpStatus.PAYLOAD_TOO_LARGE, e.getMessage());
        } catch (CloudinaryClient.CloudinaryConfigurationException e) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
        } catch (CloudinaryClient.CloudinaryException e) {
            return error(HttpStatus.BAD_GATEWAY, e.getMessage());
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable Long id) {
        try {
            jwtHelper.extractUsername(authorization);
            return media.findById(id)
                    .<ResponseEntity<?>>map(asset -> ResponseEntity.ok(MediaAssetResponse.from(asset)))
                    .orElseGet(() -> notFound("media asset not found"));
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
    }

    @GetMapping("/targets/{targetType}/{targetId}")
    public ResponseEntity<?> byTarget(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String targetType,
            @PathVariable String targetId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int pageSize) {
        try {
            jwtHelper.extractUsername(authorization);
            MediaService.MediaPage page = media.findByTarget(targetType, targetId, cursor, pageSize);
            List<MediaAssetResponse> items = page.items().stream()
                    .map(MediaAssetResponse::from)
                    .toList();
            return ResponseEntity.ok(new TargetMediaResponse(items, page.nextCursor()));
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable Long id) {
        try {
            String username = jwtHelper.extractUsername(authorization);
            media.deleteOwn(username, id);
            return ResponseEntity.noContent().build();
        } catch (MediaService.ForbiddenException e) {
            return error(HttpStatus.FORBIDDEN, e.getMessage());
        } catch (MediaService.NotFoundException e) {
            return notFound(e.getMessage());
        } catch (CloudinaryClient.CloudinaryConfigurationException e) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
        } catch (CloudinaryClient.CloudinaryException e) {
            return error(HttpStatus.BAD_GATEWAY, e.getMessage());
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, String>> maxUpload(MaxUploadSizeExceededException e) {
        return error(HttpStatus.PAYLOAD_TOO_LARGE, "file exceeds configured upload limit");
    }

    private ResponseEntity<Map<String, String>> badRequest(String message) {
        return error(HttpStatus.BAD_REQUEST, message);
    }

    private ResponseEntity<Map<String, String>> notFound(String message) {
        return error(HttpStatus.NOT_FOUND, message);
    }

    private ResponseEntity<Map<String, String>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message));
    }
}

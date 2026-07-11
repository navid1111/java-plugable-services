package com.example.media.cloudinary;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class CloudinaryClient {

    private static final String API_BASE = "https://api.cloudinary.com/v1_1";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String cloudName;
    private final String apiKey;
    private final String apiSecret;
    private final String uploadFolder;

    public CloudinaryClient(
            @Value("${CLOUDINARY_CLOUD_NAME:}") String cloudName,
            @Value("${CLOUDINARY_API_KEY:}") String apiKey,
            @Value("${CLOUDINARY_API_SECRET:}") String apiSecret,
            @Value("${CLOUDINARY_UPLOAD_FOLDER:pluggable-services-dev}") String uploadFolder) {
        this.httpClient = HttpClient.newHttpClient();
        this.objectMapper = new ObjectMapper();
        this.cloudName = cloudName == null ? "" : cloudName.trim();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.apiSecret = apiSecret == null ? "" : apiSecret.trim();
        this.uploadFolder = uploadFolder == null ? "" : uploadFolder.trim();
    }

    public record UploadResult(
            String publicId,
            String resourceType,
            String format,
            String secureUrl,
            String thumbnailUrl,
            long bytes,
            Integer width,
            Integer height,
            Double durationSeconds) {
    }

    public record DirectUploadAuthorization(String uploadUrl, String apiKey, long timestamp,
            String signature, String publicId, String folder) {}

    public DirectUploadAuthorization authorizeDirectUpload(String resourceType, String publicId) {
        requireConfigured();
        long timestamp = Instant.now().getEpochSecond();
        Map<String, String> params = new LinkedHashMap<>();
        params.put("public_id", publicId);
        params.put("timestamp", String.valueOf(timestamp));
        if (!uploadFolder.isBlank()) params.put("folder", uploadFolder);
        return new DirectUploadAuthorization(API_BASE + "/" + urlEncodePath(cloudName) + "/"
                + resourceType + "/upload", apiKey, timestamp, sign(params), publicId, uploadFolder);
    }

    public UploadResult upload(MultipartFile file, String resourceType) {
        requireConfigured();
        String boundary = "media-service-" + UUID.randomUUID();
        Map<String, String> signedParams = new LinkedHashMap<>();
        signedParams.put("timestamp", String.valueOf(Instant.now().getEpochSecond()));
        if (!uploadFolder.isBlank()) {
            signedParams.put("folder", uploadFolder);
        }

        String signature = sign(signedParams);
        String endpoint = API_BASE + "/" + urlEncodePath(cloudName) + "/" + resourceType + "/upload";

        try {
            byte[] body = multipartBody(boundary, signedParams, signature, file);
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new CloudinaryException("Cloudinary upload failed with status " + response.statusCode());
            }

            JsonNode json = objectMapper.readTree(response.body());
            String publicId = requiredText(json, "public_id");
            String returnedResourceType = optionalText(json, "resource_type", resourceType);
            String format = optionalText(json, "format", "");
            String secureUrl = requiredText(json, "secure_url");
            long bytes = optionalLong(json, "bytes");
            Integer width = optionalInteger(json, "width");
            Integer height = optionalInteger(json, "height");
            Double duration = optionalDouble(json, "duration");
            String thumbnailUrl = thumbnailUrl(returnedResourceType, secureUrl);
            return new UploadResult(
                    publicId,
                    returnedResourceType,
                    format,
                    secureUrl,
                    thumbnailUrl,
                    bytes,
                    width,
                    height,
                    duration);
        } catch (IOException e) {
            throw new CloudinaryException("Cloudinary upload failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CloudinaryException("Cloudinary upload interrupted", e);
        }
    }

    public void destroy(String publicId, String resourceType) {
        requireConfigured();
        Map<String, String> signedParams = new LinkedHashMap<>();
        signedParams.put("public_id", publicId);
        signedParams.put("invalidate", "true");
        signedParams.put("timestamp", String.valueOf(Instant.now().getEpochSecond()));
        String signature = sign(signedParams);

        Map<String, String> form = new LinkedHashMap<>(signedParams);
        form.put("api_key", apiKey);
        form.put("signature", signature);

        String endpoint = API_BASE + "/" + urlEncodePath(cloudName) + "/" + resourceType + "/destroy";
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(urlEncodedForm(form)))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new CloudinaryException("Cloudinary destroy failed with status " + response.statusCode());
            }
            JsonNode json = objectMapper.readTree(response.body());
            String result = optionalText(json, "result", "");
            if (!"ok".equals(result) && !"not found".equals(result)) {
                throw new CloudinaryException("Cloudinary destroy returned result " + result);
            }
        } catch (IOException e) {
            throw new CloudinaryException("Cloudinary destroy failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CloudinaryException("Cloudinary destroy interrupted", e);
        }
    }

    private void requireConfigured() {
        if (cloudName.isBlank() || apiKey.isBlank() || apiSecret.isBlank()
                || cloudName.startsWith("your-") || apiKey.startsWith("your-") || apiSecret.startsWith("your-")) {
            throw new CloudinaryConfigurationException("Cloudinary credentials are not configured");
        }
    }

    private String sign(Map<String, String> params) {
        String canonical = params.entrySet().stream()
                .sorted(Comparator.comparing(Map.Entry::getKey))
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .reduce((left, right) -> left + "&" + right)
                .orElse("");
        return sha1(canonical + apiSecret);
    }

    private String sha1(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] hashed = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte b : hashed) {
                result.append(String.format("%02x", b));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 is not available", e);
        }
    }

    private byte[] multipartBody(
            String boundary,
            Map<String, String> signedParams,
            String signature,
            MultipartFile file) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (Map.Entry<String, String> entry : signedParams.entrySet()) {
            writeFormField(output, boundary, entry.getKey(), entry.getValue());
        }
        writeFormField(output, boundary, "api_key", apiKey);
        writeFormField(output, boundary, "signature", signature);
        writeFileField(output, boundary, file);
        output.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return output.toByteArray();
    }

    private void writeFormField(ByteArrayOutputStream output, String boundary, String name, String value)
            throws IOException {
        output.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        output.write(("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        output.write(value.getBytes(StandardCharsets.UTF_8));
        output.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private void writeFileField(ByteArrayOutputStream output, String boundary, MultipartFile file)
            throws IOException {
        String filename = file.getOriginalFilename() == null ? "upload" : file.getOriginalFilename();
        String contentType = file.getContentType() == null ? "application/octet-stream" : file.getContentType();
        output.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        output.write(("Content-Disposition: form-data; name=\"file\"; filename=\"" + sanitizeFilename(filename) + "\"\r\n")
                .getBytes(StandardCharsets.UTF_8));
        output.write(("Content-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        output.write(file.getBytes());
        output.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private String sanitizeFilename(String filename) {
        return filename.replace("\"", "").replace("\r", "").replace("\n", "");
    }

    private String requiredText(JsonNode json, String fieldName) {
        JsonNode value = json.get(fieldName);
        if (value == null || value.asText().isBlank()) {
            throw new CloudinaryException("Cloudinary response missing " + fieldName);
        }
        return value.asText();
    }

    private String optionalText(JsonNode json, String fieldName, String defaultValue) {
        JsonNode value = json.get(fieldName);
        if (value == null || value.asText() == null || value.asText().isBlank()) {
            return defaultValue;
        }
        return value.asText();
    }

    private long optionalLong(JsonNode json, String fieldName) {
        JsonNode value = json.get(fieldName);
        return value == null ? 0L : value.asLong();
    }

    private Integer optionalInteger(JsonNode json, String fieldName) {
        JsonNode value = json.get(fieldName);
        return value == null || value.isNull() ? null : value.asInt();
    }

    private Double optionalDouble(JsonNode json, String fieldName) {
        JsonNode value = json.get(fieldName);
        return value == null || value.isNull() ? null : value.asDouble();
    }

    private String thumbnailUrl(String resourceType, String secureUrl) {
        if (secureUrl == null || !secureUrl.contains("/upload/")) {
            return secureUrl;
        }
        if ("video".equals(resourceType)) {
            String withTransform = secureUrl.replace("/upload/", "/upload/so_0,c_fill,w_320,h_240/");
            int extension = withTransform.lastIndexOf('.');
            return extension > 0 ? withTransform.substring(0, extension) + ".jpg" : withTransform;
        }
        return secureUrl.replace("/upload/", "/upload/c_fill,w_320,h_240/");
    }

    private String urlEncodedForm(Map<String, String> values) {
        return values.entrySet().stream()
                .map(entry -> urlEncode(entry.getKey()) + "=" + urlEncode(entry.getValue()))
                .reduce((left, right) -> left + "&" + right)
                .orElse("");
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String urlEncodePath(String value) {
        return urlEncode(value).replace("+", "%20");
    }

    public static class CloudinaryException extends RuntimeException {
        public CloudinaryException(String message) {
            super(message);
        }

        public CloudinaryException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static class CloudinaryConfigurationException extends CloudinaryException {
        public CloudinaryConfigurationException(String message) {
            super(message);
        }
    }
}

package com.example.platform.messaging.support;

import java.net.URI;
import java.net.http.*;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionOperations;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Audited, repeatable username-to-stable-ID backfill driven by auth's internal export. */
public class UserIdentityBackfill {
    public record Mapping(String userId, String username) {}
    public record Target(String updateSql, String unresolvedCountSql) {}
    public record Report(int exportedUsers, int updatedRows, long unresolvedRows) {}
    private final URI auth; private final String token; private final ObjectMapper mapper;
    private final JdbcTemplate jdbc; private final TransactionOperations transactions; private final List<Target> targets;
    private final HttpClient http=HttpClient.newHttpClient();
    public UserIdentityBackfill(String authBaseUrl, String token, ObjectMapper mapper, JdbcTemplate jdbc,
            TransactionOperations transactions, List<Target> targets) {
        auth=URI.create(authBaseUrl); this.token=token; this.mapper=mapper; this.jdbc=jdbc;
        this.transactions=transactions; this.targets=List.copyOf(targets);
    }
    public Report run() {
        List<Mapping> mappings=fetch();
        int updated=transactions.execute(status -> {
            int count=0;
            for(Mapping mapping:mappings) for(Target target:targets)
                count+=jdbc.update(target.updateSql(),mapping.userId(),mapping.username());
            return count;
        });
        long unresolved=targets.stream().mapToLong(target ->
                jdbc.queryForObject(target.unresolvedCountSql(),Long.class)).sum();
        return new Report(mappings.size(),updated,unresolved);
    }
    private List<Mapping> fetch() {
        try {
            List<Mapping> result=new ArrayList<>(); long checkpoint=0; boolean more;
            do {
                HttpRequest request=HttpRequest.newBuilder(auth.resolve("/internal/users/export?afterId="+checkpoint+"&pageSize=500"))
                        .header("X-Internal-Service-Token",token).GET().build();
                HttpResponse<String> response=http.send(request,HttpResponse.BodyHandlers.ofString());
                if(response.statusCode()!=200) throw new IllegalStateException("auth export returned "+response.statusCode());
                JsonNode page=mapper.readTree(response.body()); more=page.path("hasMore").asBoolean();
                checkpoint=page.path("checkpoint").asLong();
                for(JsonNode user:page.path("items")) result.add(new Mapping(user.path("userId").asText(),user.path("username").asText()));
            } while(more);
            return result;
        } catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("backfill interrupted",e);}
        catch(Exception e){throw new IllegalStateException("identity backfill failed",e);}
    }
}

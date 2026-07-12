package com.example.media.config;
import java.util.List; import org.springframework.beans.factory.annotation.Value; import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty; import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate; import org.springframework.transaction.support.TransactionOperations;
import com.example.platform.messaging.support.UserIdentityBackfill; import com.example.platform.messaging.support.WorkloadJwtIssuer; import tools.jackson.databind.ObjectMapper;
@Configuration public class IdentityBackfillConfiguration {
 @Bean UserIdentityBackfill mediaIdentityBackfill(ObjectMapper m,JdbcTemplate j,TransactionOperations tx,
  WorkloadJwtIssuer workloadJwt,@Value("${auth.internal.base-url:http://auth-service:8080}")String url){return new UserIdentityBackfill(url,workloadJwt,m,j,tx,List.of(
   new UserIdentityBackfill.Target("UPDATE media_assets SET uploader_user_id=? WHERE uploader_username=? AND uploader_user_id IS NULL","SELECT COUNT(*) FROM media_assets WHERE uploader_user_id IS NULL"),
   new UserIdentityBackfill.Target("UPDATE media_upload_intents SET owner_user_id=? WHERE owner_username=? AND owner_user_id IS NULL","SELECT COUNT(*) FROM media_upload_intents WHERE owner_user_id IS NULL")));}
 @Bean @ConditionalOnProperty(name="identity.backfill.run-on-startup",havingValue="true") ApplicationRunner mediaBackfillRunner(UserIdentityBackfill b){return a->System.out.println("identity_backfill="+b.run());}
}

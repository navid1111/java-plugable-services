package com.example.comment.config;
import java.util.List; import org.springframework.beans.factory.annotation.Value; import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty; import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate; import org.springframework.transaction.support.TransactionOperations;
import com.example.platform.messaging.support.UserIdentityBackfill; import tools.jackson.databind.ObjectMapper;
@Configuration public class IdentityBackfillConfiguration {
 @Bean UserIdentityBackfill commentIdentityBackfill(ObjectMapper m,JdbcTemplate j,TransactionOperations tx,
  @Value("${auth.internal.base-url:http://auth-service:8080}")String url,@Value("${internal.service.token:local-dev-internal-token}")String token){return new UserIdentityBackfill(url,token,m,j,tx,List.of(
   new UserIdentityBackfill.Target("UPDATE comments SET author_user_id=? WHERE author_username=? AND author_user_id IS NULL","SELECT COUNT(*) FROM comments WHERE author_user_id IS NULL")));}
 @Bean @ConditionalOnProperty(name="identity.backfill.run-on-startup",havingValue="true") ApplicationRunner commentBackfillRunner(UserIdentityBackfill b){return a->System.out.println("identity_backfill="+b.run());}
}

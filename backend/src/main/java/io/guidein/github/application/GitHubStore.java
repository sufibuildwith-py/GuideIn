package io.guidein.github.application;

import io.guidein.github.infrastructure.client.*;
import io.guidein.platform.api.TenantContext;
import io.guidein.jobs.api.FailureCategory;
import java.util.*;
import java.time.Instant;
import java.util.function.Supplier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Explicit transactions keep JDBC context local; no provider request executes in these transactions. */
public final class GitHubStore implements GitHubProviderClient.AccessGuard {
    final JdbcClient jdbc;
    private final TenantContext context;
    private final TransactionTemplate transactions;
    public GitHubStore(JdbcClient jdbc,TenantContext context,PlatformTransactionManager manager) {
        this.jdbc=jdbc;this.context=context;transactions=new TransactionTemplate(manager);
    }
    <T>T transaction(UUID tenant,Supplier<T> work) {
        return transactions.execute(status->{if(tenant!=null) context.setTenant(tenant);return work.get();});
    }
    Route route(long installation) {
        return transaction(null,()->jdbc.sql("SELECT tenant_id,installation_id FROM github_installation_routes WHERE installation_external_id=:id")
                .param("id",installation).query((rs,n)->new Route(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),installation)).optional().orElse(null));
    }
    Installation installation(Route route,boolean lock) {
        return jdbc.sql("SELECT status,generation,account_login,cooldown_until FROM github_installations WHERE tenant_id=:tenant AND id=:id"+(lock?" FOR UPDATE":""))
                .param("tenant",route.tenant()).param("id",route.id())
                .query((rs,n)->new Installation(route,rs.getString(1),rs.getLong(2),rs.getString(3),rs.getTimestamp(4)==null?null:rs.getTimestamp(4).toInstant()))
                .optional().orElseThrow(()->new ProviderFailure(FailureCategory.AUTHORIZATION,null));
    }
    @Override public void check(long external,long generation) {
        Route route=route(external);
        if(route==null) throw new ProviderFailure(FailureCategory.AUTHORIZATION,null);
        transaction(route.tenant(),()->{
            var state=installation(route,false);
            if(state.generation()!=generation || !(state.status().equals("ACTIVE") || state.status().equals("ACCESS_REDUCED") || state.status().equals("PENDING_BINDING")))
                throw new ProviderFailure(FailureCategory.AUTHORIZATION,null);
            if(state.cooldown()!=null && state.cooldown().isAfter(Instant.now())) throw new ProviderFailure(FailureCategory.RATE_LIMIT,state.cooldown());
            return null;
        });
    }
    record Route(UUID tenant,UUID id,long external) { }
    record Installation(Route route,String status,long generation,String account,Instant cooldown) { }
}

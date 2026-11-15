package com.proofly.tenancy;

import com.proofly.common.TenantId;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

import javax.sql.DataSource;
import java.util.Collections;

@Configuration
@EnableConfigurationProperties(TenancyProperties.class)
public class DataSourceConfig {

    @Bean(name = "controlPlaneDataSource")
    public DataSource controlPlaneDataSource(
            @Value("${proofly.control-plane.jdbc-url}") String jdbcUrl,
            @Value("${proofly.control-plane.username}") String username,
            @Value("${proofly.control-plane.password}") String password,
            @Value("${proofly.control-plane.max-pool-size:12}") int maxPoolSize,
            @Value("${proofly.control-plane.min-idle:2}") int minIdle
    ) {
        HikariConfig cfg = new HikariConfig();
        cfg.setPoolName("proofly-control-plane");
        cfg.setJdbcUrl(jdbcUrl);
        cfg.setUsername(username);
        cfg.setPassword(password);
        cfg.setMaximumPoolSize(maxPoolSize);
        cfg.setMinimumIdle(minIdle);
        cfg.setConnectionTimeout(5000);
        HikariDataSource ds = new HikariDataSource(cfg);

        // Only control-plane migrations run on startup; tenant DBs never block boot.
        Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration/control")
                .load()
                .migrate();

        return ds;
    }

    @Bean
    @Primary
    public DataSource tenantRoutingDataSource(
            @Qualifier("controlPlaneDataSource") DataSource controlPlaneDataSource,
            TenantDataSourceRegistry registry
    ) {
        AbstractRoutingDataSource routing = new AbstractRoutingDataSource() {
            @Override
            protected Object determineCurrentLookupKey() {
                return TenantContext.requireCurrent();
            }

            @Override
            protected DataSource determineTargetDataSource() {
                TenantId tenantId = TenantContext.requireCurrent();
                return registry.getOrCreate(tenantId);
            }
        };
        routing.setTargetDataSources(Collections.emptyMap());
        routing.setLenientFallback(false);
        routing.setDefaultTargetDataSource(controlPlaneDataSource);
        routing.afterPropertiesSet();
        return routing;
    }
}

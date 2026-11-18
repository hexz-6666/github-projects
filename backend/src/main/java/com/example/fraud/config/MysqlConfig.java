package com.example.fraud.config;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@Configuration
@Profile("mysql")
@EnableTransactionManagement
public class MysqlConfig {
    @Bean
    public DataSource dataSource(Environment environment) {
        HikariDataSource dataSource = DataSourceBuilder.create()
                .type(HikariDataSource.class)
                .url(environment.getRequiredProperty("DB_URL"))
                .username(environment.getRequiredProperty("DB_USERNAME"))
                .password(environment.getRequiredProperty("DB_PASSWORD"))
                .driverClassName("com.mysql.cj.jdbc.Driver")
                .build();
        dataSource.addDataSourceProperty("connectionTimeZone", "UTC");
        dataSource.addDataSourceProperty("forceConnectionTimeZoneToSession", "true");
        // DATETIME values are UTC by contract; pin the session as well so
        // DATE_FORMAT and boundary predicates cannot depend on the host zone.
        dataSource.setConnectionInitSql("SET time_zone = '+00:00'");
        return dataSource;
    }

    @Bean
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean
    public PlatformTransactionManager transactionManager(DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }
}

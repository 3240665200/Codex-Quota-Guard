package com.pingche.web.quota;

import static org.assertj.core.api.Assertions.assertThat;
import java.math.BigDecimal;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import javax.sql.DataSource;

class UsdBillingMigrationTest {
    @Test void upgradesExistingRowsWithoutConvertingTokenCapsAndFreezesHistoricalPrices() {
        // H2 2.4 caches an IN-constraint comparator against the creating session.
        // Keep that test session open across Flyway and fixture insertion.
        var dataSource=new SingleConnectionDataSource("jdbc:h2:mem:usd-migration-"+UUID.randomUUID()+";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","",true);
        try { verifyUpgrade(dataSource); } finally { dataSource.destroy(); }
    }

    @Test
    @EnabledIfEnvironmentVariable(named="CQG_USD_TEST_POSTGRES_URL",matches="jdbc:postgresql://.+")
    void upgradesPostgresUsingAnIsolatedDisposableSchema() {
        String url=System.getenv("CQG_USD_TEST_POSTGRES_URL");
        String schema="usd_check_"+UUID.randomUUID().toString().replace("-", "");
        var control=new JdbcTemplate(new DriverManagerDataSource(url,"postgres",""));
        control.execute("create schema "+schema);
        try {
            verifyUpgrade(new DriverManagerDataSource(url+(url.contains("?")?"&":"?")+"currentSchema="+schema,"postgres",""));
        } finally {
            // Only this randomly named, test-owned schema is removed.
            control.execute("drop schema "+schema+" cascade");
        }
    }

    private void verifyUpgrade(DataSource dataSource) {
        Flyway.configure().dataSource(dataSource).target("37").load().migrate();
        var jdbc=new JdbcTemplate(dataSource);
        UUID user=UUID.randomUUID();
        UUID account=UUID.randomUUID(),team=UUID.randomUUID();
        jdbc.update("insert into official_accounts(id,label,estimated_total_tokens,created_at,updated_at,version) values(?,'Migration account',9000000,current_timestamp,current_timestamp,0)",account);
        jdbc.update("insert into teams(id,name,status,five_hour_quota_percent,weekly_quota_percent,created_at,updated_at,version,official_account_id,total_token_limit) values(?,'Migration team','ACTIVE',100,100,current_timestamp,current_timestamp,0,?,9000000)",team,account);
        jdbc.update("insert into users(id,email,password_hash,display_name,role,status,created_at,updated_at,session_version,five_hour_token_limit) values(?,?,?,?,'USER','ACTIVE',current_timestamp,current_timestamp,0,1234)",user,"migration@example.com","synthetic","Migration");
        jdbc.update("insert into usage_model_prices(model,input_price,output_price,cache_read_price,cache_write_price,updated_at) values('migration-fixture',2,10,0.2,3,current_timestamp)");
        jdbc.update("insert into codex_usage_events(id,user_id,total_tokens,occurred_at,received_at,source,model,input_tokens,cached_input_tokens,cache_write_input_tokens,output_tokens,reasoning_output_tokens) values(?,?,1500000,current_timestamp,current_timestamp,'TOKEN_USAGE_RECORD','migration-fixture',1000000,200000,100000,500000,200000)","a".repeat(64),user);
        jdbc.update("insert into codex_usage_events(id,user_id,total_tokens,occurred_at,received_at,source) values(?,?,99,current_timestamp,current_timestamp,'TOKEN_USAGE_RECORD')","b".repeat(64),user);
        jdbc.update("update codex_usage_events set team_id=?",team);
        var flyway=Flyway.configure().dataSource(dataSource).load();
        flyway.migrate();
        assertThat(jdbc.queryForObject("select cost_usd from usage_billing_entries where event_id=?",BigDecimal.class,"a".repeat(64))).isEqualByComparingTo("6.74");
        assertThat(jdbc.queryForObject("select origin from usage_billing_entries where event_id=?",String.class,"a".repeat(64))).isEqualTo("HISTORICAL_RECALC");
        assertThat(jdbc.queryForObject("select mode_status from usage_billing_entries where event_id=?",String.class,"a".repeat(64))).isEqualTo("UNRECORDED");
        assertThat(jdbc.queryForObject("select cost_usd from usage_billing_entries where event_id=?",BigDecimal.class,"b".repeat(64))).isNull();
        assertThat(jdbc.queryForObject("select cost_status from usage_billing_entries where event_id=?",String.class,"b".repeat(64))).isEqualTo("MISSING_BREAKDOWN");
        assertThat(jdbc.queryForObject("select five_hour_token_limit from users where id=?",Long.class,user)).isEqualTo(1234);
        assertThat(jdbc.queryForObject("select five_hour_usd_limit from users where id=?",BigDecimal.class,user)).isNull();
        assertThat(jdbc.queryForObject("select total_usd_limit from teams where id=?",BigDecimal.class,team)).isNull();
        assertThat(jdbc.queryForObject("select budget_usd from official_accounts where id=?",BigDecimal.class,account)).isNull();
        assertThat(jdbc.queryForObject("select total_token_limit from teams where id=?",Long.class,team)).isEqualTo(9000000);
        assertThat(jdbc.queryForObject("select estimated_total_tokens from official_accounts where id=?",Long.class,account)).isEqualTo(9000000);
        assertThat(jdbc.queryForObject("select official_account_id from usage_billing_entries where event_id=?",UUID.class,"a".repeat(64))).isEqualTo(account);
        jdbc.update("update usage_model_prices set input_price=99 where model='migration-fixture'");
        flyway.migrate();
        assertThat(jdbc.queryForObject("select count(*) from usage_billing_entries",Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select cost_usd from usage_billing_entries where event_id=?",BigDecimal.class,"a".repeat(64))).isEqualByComparingTo("6.74");
    }
}

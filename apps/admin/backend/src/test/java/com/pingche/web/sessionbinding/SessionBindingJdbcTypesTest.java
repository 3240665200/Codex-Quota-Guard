package com.pingche.web.sessionbinding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.pingche.web.officialsession.OfficialAccountInfo;
import com.pingche.web.officialsession.OfficialConnectionRefreshResponse;
import com.pingche.web.officialsession.OfficialConnectionService;
import com.pingche.web.officialsession.OfficialSessionInfo;
import com.pingche.web.officialsession.OfficialSessions;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import tools.jackson.databind.ObjectMapper;

/** H2 runs SQL while a driver contract rejects types unsupported by PgJDBC. */
class SessionBindingJdbcTypesTest {
    @Test void readsAndWritesBindPostgresCompatibleTimesWithoutARealPostgresConnection() throws Exception {
        var source = new SingleConnectionDataSource("jdbc:h2:mem:binding-types-" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "", true);
        try {
            JdbcTemplate setup = new JdbcTemplate(source);
            setup.execute("create table users(id uuid primary key,status varchar(16),team_id uuid,display_name varchar(120),email varchar(320))");
            setup.execute("create table official_accounts(id uuid primary key)");
            setup.execute("create table teams(id uuid primary key,status varchar(16),official_account_id uuid,version bigint)");
            setup.execute("create table meter_devices(id uuid primary key,user_id uuid,status varchar(16),expires_at timestamp with time zone)");
            setup.execute("create table official_account_connections(official_account_id uuid primary key,requested_account_id varchar(160),status varchar(32),revision uuid)");
            setup.execute("create table official_session_logout_plans(official_account_id uuid,status varchar(32))");
            new ResourceDatabasePopulator(new ClassPathResource("db/migration/V24__add_session_binding_queue.sql"),
                    new ClassPathResource("db/migration/V29__automate_session_binding_handover.sql")).execute(source);
            Instant now = Instant.parse("2026-10-15T01:00:00Z");
            UUID user = UUID.randomUUID(), account = UUID.randomUUID(), team = UUID.randomUUID(), device = UUID.randomUUID();
            setup.update("insert into official_accounts values (?)", account);
            setup.update("insert into users values (?,'ACTIVE',?,'Synthetic','synthetic@example.test')", user, team);
            setup.update("insert into teams values (?,'ACTIVE',?,0)", team, account);
            setup.update("insert into meter_devices values (?,?,'ACTIVE',?)", device, user, now.plusSeconds(7200).atOffset(ZoneOffset.UTC));
            setup.update("insert into official_account_connections values (?,'synthetic-account','CONNECTED',?)", account, UUID.randomUUID());

            List<OffsetDateTime> boundTimes = new ArrayList<>();
            DataSource strict = strictJdbcTypes(source, boundTimes);
            var official = mock(OfficialConnectionService.class);
            var service = new SessionBindingService(new JdbcTemplate(strict), official, Clock.fixed(now, ZoneOffset.UTC),
                    new ObjectMapper(), new DataSourceTransactionManager(strict));
            when(official.refresh(account)).thenReturn(snapshot(now, List.of("existing")));
            var attempt = service.create(user, device, device, null);
            assertThat(service.prepare(user, attempt.id()).loginAllowed()).isTrue();
            when(official.refresh(account)).thenReturn(snapshot(now, List.of("existing", "candidate")));
            assertThat(service.detect(user, attempt.id()).candidateSessionId()).isEqualTo("candidate");
            var binding = service.confirm(user, attempt.id(), "candidate").binding();
            assertThat(binding.status()).isEqualTo("ACTIVE");
            service.release(user, binding.id());
            var cancelled = service.create(user, device, device, null);
            service.prepare(user, cancelled.id());
            assertThat(service.cancel(user, cancelled.id()).quarantined()).isTrue();
            assertThat(service.recover(account, true).quarantined()).isFalse();
            assertThat(boundTimes).isNotEmpty().allSatisfy(time -> assertThat(time.getOffset()).isEqualTo(ZoneOffset.UTC));
            assertThat(boundTimes).contains(now.atOffset(ZoneOffset.UTC), now.plusSeconds(1800).atOffset(ZoneOffset.UTC));
        } finally { source.destroy(); }
    }

    private DataSource strictJdbcTypes(DataSource delegate, List<OffsetDateTime> boundTimes) {
        return new AbstractDataSource() {
            @Override public Connection getConnection() throws SQLException { return wrap(delegate.getConnection()); }
            @Override public Connection getConnection(String user, String password) throws SQLException { return wrap(delegate.getConnection(user, password)); }
            private Connection wrap(Connection connection) {
                return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                    Object value = invoke(connection, method, args);
                    if ("prepareStatement".equals(method.getName()) && value instanceof PreparedStatement statement)
                        return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{PreparedStatement.class}, (prepared, operation, parameters) -> {
                            if ("setObject".equals(operation.getName()) && parameters != null && parameters.length >= 2) {
                                if (parameters[1] instanceof Instant) throw new SQLException("PgJDBC cannot infer a JDBC SQL type for Instant", "07006");
                                if (parameters[1] instanceof OffsetDateTime time) boundTimes.add(time);
                            }
                            return invoke(statement, operation, parameters);
                        });
                    return value;
                });
            }
        };
    }

    private Object invoke(Object target, Method method, Object[] parameters) throws Throwable {
        try { return method.invoke(target, parameters); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }

    private OfficialConnectionRefreshResponse snapshot(Instant now, List<String> ids) {
        var identity = new OfficialAccountInfo("synthetic-user", "synthetic@example.test", "Synthetic", "synthetic-account", "Synthetic", "plus", "owner", false, true);
        var devices = ids.stream().map(id -> new OfficialSessionInfo(id, id, "windows", "Synthetic", null, null, null,
                false, false, now, now, null, null, null, List.of("Codex"))).toList();
        return new OfficialConnectionRefreshResponse(null, identity, new OfficialSessions(devices, true), now);
    }
}

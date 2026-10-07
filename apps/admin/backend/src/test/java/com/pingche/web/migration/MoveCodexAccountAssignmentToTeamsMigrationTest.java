package com.pingche.web.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;

class MoveCodexAccountAssignmentToTeamsMigrationTest {

    @Test
    void movesAnUnambiguousTeamAssignmentAndRemovesTheLegacyUserColumn() throws Exception {
        try (HikariDataSource dataSource = dataSource()) {
            migrateToVersion(dataSource, "6");

            UUID accountId = UUID.randomUUID();
            UUID teamId = UUID.randomUUID();
            UUID userId = UUID.randomUUID();
            insertAccount(dataSource, accountId);
            insertTeam(dataSource, teamId);
            insertUser(dataSource, userId, teamId, accountId);

            migrateToVersion(dataSource, "7");

            try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement query = connection.prepareStatement(
                    "select assigned_codex_account_id from teams where id = ?"
            )) {
                query.setObject(1, teamId);
                try (ResultSet result = query.executeQuery()) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getObject(1, UUID.class)).isEqualTo(accountId);
                }
            }
            Throwable missingLegacyColumn = catchThrowable(() -> {
                try (PreparedStatement legacyQuery = connection.prepareStatement(
                        "select assigned_codex_account_id from users"
                )) {
                    legacyQuery.executeQuery();
                }
            });
            assertThat(missingLegacyColumn).isInstanceOf(SQLException.class);
            }
        }
    }

    @Test
    void refusesToDiscardALegacyAssignmentForAUserWithoutATeam() throws Exception {
        try (HikariDataSource dataSource = dataSource()) {
            migrateToVersion(dataSource, "6");

            UUID accountId = UUID.randomUUID();
            UUID userId = UUID.randomUUID();
            insertAccount(dataSource, accountId);
            insertUser(dataSource, userId, null, accountId);

            Throwable failure = catchThrowable(() -> migrateToVersion(dataSource, "7"));
            assertThat(failure).isInstanceOf(FlywayException.class);
            assertThat(rootCause(failure).getMessage())
                    .contains("cannot be safely moved")
                    .contains("Assign each affected user to a team");

            try (Connection connection = dataSource.getConnection();
                    PreparedStatement query = connection.prepareStatement("""
                            select assigned_codex_account_id
                              from users
                             where id = ?
                            """)) {
                query.setObject(1, userId);
                try (ResultSet result = query.executeQuery()) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getObject(1, UUID.class)).isEqualTo(accountId);
                }
                Throwable addedTeamColumn = catchThrowable(() -> {
                    try (PreparedStatement teamQuery = connection.prepareStatement(
                            "select assigned_codex_account_id from teams"
                    )) {
                        teamQuery.executeQuery();
                    }
                });
                assertThat(addedTeamColumn).isInstanceOf(SQLException.class);
            }
        }
    }

    private HikariDataSource dataSource() {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl("jdbc:h2:mem:team_account_migration_"
                + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        dataSource.setMinimumIdle(1);
        dataSource.setMaximumPoolSize(3);
        return dataSource;
    }

    private void migrateToVersion(DataSource dataSource, String version) {
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .target(version)
                .load()
                .migrate();
    }

    private Throwable rootCause(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root;
    }

    private void insertAccount(DataSource dataSource, UUID accountId) throws SQLException {
        Instant now = Instant.parse("2026-10-06T00:00:00Z");
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        insert into codex_accounts (
                            id, email, display_name, status, credential_ciphertext,
                            credential_iv, created_at, updated_at, version
                        ) values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)) {
            statement.setObject(1, accountId);
            statement.setString(2, "legacy-account@example.com");
            statement.setString(3, "Legacy account");
            statement.setString(4, "ACTIVE");
            statement.setString(5, "ciphertext");
            statement.setString(6, "iv");
            statement.setTimestamp(7, Timestamp.from(now));
            statement.setTimestamp(8, Timestamp.from(now));
            statement.setLong(9, 0L);
            statement.executeUpdate();
        }
    }

    private void insertTeam(DataSource dataSource, UUID teamId) throws SQLException {
        Instant now = Instant.parse("2026-10-06T00:00:00Z");
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        insert into teams (
                            id, name, description, status, five_hour_quota_percent,
                            weekly_quota_percent, created_at, updated_at, version
                        ) values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)) {
            statement.setObject(1, teamId);
            statement.setString(2, "Legacy team " + teamId);
            statement.setString(3, "");
            statement.setString(4, "ACTIVE");
            statement.setInt(5, 100);
            statement.setInt(6, 100);
            statement.setTimestamp(7, Timestamp.from(now));
            statement.setTimestamp(8, Timestamp.from(now));
            statement.setLong(9, 0L);
            statement.executeUpdate();
        }
    }

    private void insertUser(DataSource dataSource, UUID userId, UUID teamId, UUID accountId)
            throws SQLException {
        Instant now = Instant.parse("2026-10-06T00:00:00Z");
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        insert into users (
                            id, email, password_hash, display_name, role, status,
                            created_at, updated_at, session_version,
                            assigned_codex_account_id, team_id,
                            five_hour_quota_percent, weekly_quota_percent
                        ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)) {
            statement.setObject(1, userId);
            statement.setString(2, "legacy-user-" + userId + "@example.com");
            statement.setString(3, "password-hash");
            statement.setString(4, "Legacy user");
            statement.setString(5, "USER");
            statement.setString(6, "ACTIVE");
            statement.setTimestamp(7, Timestamp.from(now));
            statement.setTimestamp(8, Timestamp.from(now));
            statement.setLong(9, 0L);
            statement.setObject(10, accountId);
            statement.setObject(11, teamId);
            statement.setObject(12, null);
            statement.setObject(13, null);
            statement.executeUpdate();
        }
    }
}

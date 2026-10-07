package db.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * Replaces the legacy user-to-Codex-account relation with team ownership.
 *
 * <p>The legacy shape cannot represent multiple account assignments within a
 * single team. Rather than guessing a winner, splitting a team, or discarding
 * a user's access, this migration rejects those rows before changing schema.
 * Operators can resolve them deliberately on the V6 schema and rerun.
 */
public class V7__move_codex_account_assignment_to_teams extends BaseJavaMigration {

    @Override
    public boolean canExecuteInTransaction() {
        return true;
    }

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        MigrationSafety safety = inspectLegacyAssignments(connection);
        if (!safety.isSafe()) {
            throw new FlywayException(
                    "Legacy Codex account assignments cannot be safely moved to team ownership "
                            + "(usersWithoutTeam=" + safety.usersWithoutTeam()
                            + ", teamsWithMixedAssignments=" + safety.teamsWithMixedAssignments()
                            + ", usageSnapshotsWithDifferentAccount="
                            + safety.usageSnapshotsWithDifferentAccount() + "). "
                            + "Assign each affected user to a team, ensure every affected team has "
                            + "one legacy Codex account, and remove or correct stale usage snapshots "
                            + "before rerunning Flyway. No data was changed."
            );
        }

        try (Statement statement = connection.createStatement()) {
            statement.execute("alter table teams add column assigned_codex_account_id uuid");
            statement.execute("""
                    alter table teams
                        add constraint teams_assigned_codex_account_id_fk
                        foreign key (assigned_codex_account_id)
                        references codex_accounts (id)
                        on delete set null
                    """);
            statement.execute("""
                    create index teams_assigned_codex_account_id_idx
                        on teams (assigned_codex_account_id)
                    """);
        }

        try (PreparedStatement update = connection.prepareStatement("""
                update teams
                   set assigned_codex_account_id = ?
                 where id = ?
                """)) {
            for (TeamAssignment assignment : safety.assignments()) {
                update.setObject(1, assignment.codexAccountId());
                update.setObject(2, assignment.teamId());
                update.addBatch();
            }
            update.executeBatch();
        }

        try (Statement statement = connection.createStatement()) {
            statement.execute("alter table users drop constraint users_assigned_codex_account_id_fk");
            statement.execute("alter table users drop column assigned_codex_account_id");
        }
    }

    private MigrationSafety inspectLegacyAssignments(Connection connection) throws SQLException {
        long usersWithoutTeam = count(connection, """
                select count(*)
                  from users
                 where assigned_codex_account_id is not null
                   and team_id is null
                """);
        long teamsWithMixedAssignments = count(connection, """
                select count(*)
                  from (
                        select team_id
                          from users
                         where team_id is not null
                         group by team_id
                        having count(assigned_codex_account_id) > 0
                           and (
                                count(assigned_codex_account_id) <> count(*)
                                or count(distinct assigned_codex_account_id) <> 1
                           )
                       ) conflicting_teams
                """);
        long usageSnapshotsWithDifferentAccount = count(connection, """
                select count(*)
                  from user_quota_usage usage_snapshot
                  join users user_account on user_account.id = usage_snapshot.user_id
                 where user_account.assigned_codex_account_id is null
                    or usage_snapshot.codex_account_id <> user_account.assigned_codex_account_id
                """);
        return new MigrationSafety(
                usersWithoutTeam,
                teamsWithMixedAssignments,
                usageSnapshotsWithDifferentAccount,
                legacyTeamAssignments(connection)
        );
    }

    private long count(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet result = statement.executeQuery()) {
            result.next();
            return result.getLong(1);
        }
    }

    private List<TeamAssignment> legacyTeamAssignments(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                select team_id, assigned_codex_account_id
                  from users
                 where team_id is not null
                   and assigned_codex_account_id is not null
                 group by team_id, assigned_codex_account_id
                """)) {
            try (ResultSet result = statement.executeQuery()) {
                List<TeamAssignment> assignments = new ArrayList<>();
                while (result.next()) {
                    assignments.add(new TeamAssignment(
                            result.getObject(1, UUID.class),
                            result.getObject(2, UUID.class)
                    ));
                }
                return assignments;
            }
        }
    }

    private record TeamAssignment(UUID teamId, UUID codexAccountId) {
    }

    private record MigrationSafety(
            long usersWithoutTeam,
            long teamsWithMixedAssignments,
            long usageSnapshotsWithDifferentAccount,
            List<TeamAssignment> assignments
    ) {
        private boolean isSafe() {
            return usersWithoutTeam == 0
                    && teamsWithMixedAssignments == 0
                    && usageSnapshotsWithDifferentAccount == 0;
        }
    }
}

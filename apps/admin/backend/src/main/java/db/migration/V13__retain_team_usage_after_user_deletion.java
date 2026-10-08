package db.migration;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Keep received team usage in its rolling window after a member is deleted. */
public class V13__retain_team_usage_after_user_deletion extends BaseJavaMigration {

    @Override
    public boolean canExecuteInTransaction() {
        return true;
    }

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        String constraint = null;
        try (ResultSet keys = connection.getMetaData().getImportedKeys(
                connection.getCatalog(), connection.getSchema(), "codex_usage_events")) {
            while (keys.next()) {
                if ("codex_usage_events".equalsIgnoreCase(keys.getString("FKTABLE_NAME"))
                        && "user_id".equalsIgnoreCase(keys.getString("FKCOLUMN_NAME"))
                        && "users".equalsIgnoreCase(keys.getString("PKTABLE_NAME"))) {
                    constraint = keys.getString("FK_NAME");
                    break;
                }
            }
        }
        if (constraint == null || !constraint.matches("[A-Za-z_][A-Za-z_0-9]*")) {
            throw new FlywayException("Could not identify the usage event user foreign key");
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("alter table codex_usage_events drop constraint \"" + constraint + "\"");
            statement.execute("alter table codex_usage_events alter column user_id drop not null");
            statement.execute("""
                    alter table codex_usage_events
                    add constraint codex_usage_events_user_id_fk
                    foreign key (user_id) references users (id) on delete set null
                    """);
        }
    }
}

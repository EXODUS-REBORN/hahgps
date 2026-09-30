package ru.hahgps.storage;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * SQLite-провайдер.
 */
public class SqliteStorageProvider extends AbstractSqlStorageProvider {

    private final File dbFile;

    public SqliteStorageProvider(File dbFile) {
        super(false);
        this.dbFile = dbFile;
    }

    @Override
    public void init() throws Exception {
        Class.forName("org.sqlite.JDBC");
        dbFile.getParentFile().mkdirs();
        connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
        try (var st = connection.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA foreign_keys=ON");
        }
        createSchema();
    }

    @Override
    protected void reconnect() throws SQLException {
        if (connection != null && !connection.isClosed()) {
            return;
        }
        connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
    }
}

package ru.hahgps.storage;
import java.io.File;
import java.sql.DriverManager;
import java.sql.SQLException;

public class SqliteStorageProvider extends AbstractSqlStorageProvider {

    private final File dbFile;

    public SqliteStorageProvider(File dbFile) {
        super(false);
        this.dbFile = dbFile;
    }

    @Override
    public void init() throws Exception {
        Class.forName("org.sqlite.JDBC");
        File parent = dbFile.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
        applyPragmas();
        createSchema();
    }

    /** PRAGMA живут в конкретном соединении — их надо повторять после каждого reconnect. */
    private void applyPragmas() throws SQLException {
        try (var st = connection.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA foreign_keys=ON");
            // ждём освобождения файла вместо мгновенного "database is locked"
            st.execute("PRAGMA busy_timeout=5000");
        }
    }

    @Override
    protected void reconnect() throws SQLException {
        if (connection != null && !connection.isClosed()) {
            return;
        }
        connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
        applyPragmas();
    }
}

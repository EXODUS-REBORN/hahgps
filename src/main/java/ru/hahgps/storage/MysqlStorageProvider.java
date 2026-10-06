package ru.hahgps.storage;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public class MysqlStorageProvider extends AbstractSqlStorageProvider {

    private final String host;
    private final int port;
    private final String database;
    private final String username;
    private final String password;
    private final boolean useSsl;

    public MysqlStorageProvider(String host, int port, String database, String username,
                                String password, boolean useSsl) {
        super(true);
        this.host = host;
        this.port = port;
        this.database = database;
        this.username = username;
        this.password = password;
        this.useSsl = useSsl;
    }

    @Override
    public void init() throws Exception {
        Class.forName("com.mysql.cj.jdbc.Driver");
        connection = newConnection();
        createSchema();
    }

    private Connection newConnection() throws SQLException {
        StringBuilder url = new StringBuilder(160)
                .append("jdbc:mysql://").append(host).append(':').append(port).append('/').append(database)
                .append("?useSSL=").append(useSsl)
                .append("&serverTimezone=UTC")
                .append("&connectTimeout=5000&socketTimeout=30000");
        if (!useSsl) {
            // Публичный ключ сервера запрашивается только при незашифрованном соединении.
            url.append("&allowPublicKeyRetrieval=true");
        }
        return DriverManager.getConnection(url.toString(), username, password);
    }

    @Override
    protected void reconnect() throws SQLException {
        if (connection != null && !connection.isClosed()) {
            return;
        }
        connection = newConnection();
    }
}

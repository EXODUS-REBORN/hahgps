package ru.hahgps.storage;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * MySQL-провайдер (одно соединение с автоматическим переподключением).
 */
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
        String url = "jdbc:mysql://" + host + ":" + port + "/" + database +
                "?useSSL=" + useSsl + "&allowPublicKeyRetrieval=true&serverTimezone=UTC";
        return DriverManager.getConnection(url, username, password);
    }

    @Override
    protected void reconnect() throws SQLException {
        if (connection != null && !connection.isClosed()) {
            return;
        }
        connection = newConnection();
    }
}

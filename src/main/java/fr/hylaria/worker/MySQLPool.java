package fr.hylaria.worker;

import com.zaxxer.hikari.*;

import java.sql.Connection;
import java.sql.SQLException;

public class MySQLPool {

    private static HikariDataSource ds;

    public static void init(String host, String db, String user, String pass) {
        if (ds != null) return;

        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl("jdbc:mysql://" + host + "/" + db + "?useSSL=false&allowPublicKeyRetrieval=true");
        cfg.setUsername(user);
        cfg.setPassword(pass);

        cfg.setMaximumPoolSize(10);
        cfg.setMinimumIdle(2);
        cfg.setConnectionTimeout(8000);
        cfg.setIdleTimeout(30000);
        cfg.setMaxLifetime(60000);

        ds = new HikariDataSource(cfg);
        System.out.println("[MySQL] Pool Hikari initialisé");
    }

    public static Connection get() throws SQLException {
        return ds.getConnection();
    }
}

package fr.hylaria.worker;

import com.rabbitmq.client.*;
import org.json.JSONObject;
import com.zaxxer.hikari.*;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;

import java.util.concurrent.ScheduledExecutorService;

public class DeployGameWorker implements Runnable {

    private static final String KUBECTL = "kubectl";
    private static HikariDataSource dataSource;

    private void initPool(String host, String dbName, String user, String pass) {
        if (dataSource != null) return;

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:mysql://" + host + "/" + dbName + "?useSSL=false&allowPublicKeyRetrieval=true");
        config.setUsername(user);
        config.setPassword(pass);

        config.setMaximumPoolSize(10);
        config.setMinimumIdle(2);
        config.setIdleTimeout(30000);
        config.setConnectionTimeout(8000);
        config.setMaxLifetime(120000);

        dataSource = new HikariDataSource(config);
        System.out.println("[MySQL] Pool Hikari initialisé pour GameWorker");
    }

    private Connection getConn() throws Exception {
        return dataSource.getConnection();
    }

    private int findAvailablePort(String host, String dbName, String user, String pass) {
        initPool(host, dbName, user, pass);

        final int START = 25580;
        final int END = 25620;

        try (Connection conn = getConn()) {
            Set<Integer> usedPortsDB = new HashSet<>();
            // We still check servers to avoid collision with normal servers
            ResultSet rs = conn.prepareStatement("SELECT port FROM servers").executeQuery();
            while (rs.next()) usedPortsDB.add(rs.getInt(1));

            String result = ShellExecutor.runAndGet(
                    KUBECTL + " get pods -o jsonpath='{.items[*].spec.containers[*].ports[*].containerPort}'"
            );

            Set<String> usedK3SPorts = new HashSet<>(Arrays.asList(result.split(" ")));

            for (int port = START; port <= END; port++) {
                boolean usedInDB = usedPortsDB.contains(port);
                boolean usedInK3s = usedK3SPorts.contains(String.valueOf(port));

                if (!usedInDB && !usedInK3s) {
                    System.out.println("[PORT] Port disponible trouvé pour game : " + port);
                    return port;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        throw new RuntimeException("Aucun port disponible entre " + START + " et " + END);
    }

    @Override
    public void run() {
        try {
            Channel channel = RabbitMQManager.createChannel();
            channel.queueDeclare("deploy-game", true, false, false, null);

            channel.basicConsume("deploy-game", false, (consumerTag, delivery) -> {
                try {
                    String msg = new String(delivery.getBody(), "UTF-8");
                    JSONObject data = new JSONObject(msg);
                    System.out.println("[DeployGame] Reçu : " + data);

                    String dbHost = data.getString("dbHost");
                    String dbName = data.getString("dbName");
                    String dbUser = data.getString("dbUser");
                    String dbPass = data.getString("dbPass");
                    String gameType = data.getString("gameType");
                    String mode = data.getString("mode");

                    initPool(dbHost, dbName, dbUser, dbPass);

                    String serverName = gameType.toLowerCase() + "-" + mode.toLowerCase() + "-" + (1000 + new Random().nextInt(9000));
                    int port = findAvailablePort(dbHost, dbName, dbUser, dbPass);

                    int minPlayers = GameDefaultConfig.getMinPlayers(gameType, mode);
                    int maxPlayers = GameDefaultConfig.getMaxPlayers(gameType, mode);

                    // Insert LOADING en base dans la table games et servers
                    try (Connection conn = getConn();
                         PreparedStatement stmt = conn.prepareStatement(
                                 "INSERT INTO games (server_name, game_type, mode, map_name, state, min_players, max_players, current_players, started_at, queue) " +
                                         "VALUES (?, ?, ?, 'random', 'LOADING', ?, ?, 0, NOW(), 'none')"
                         );
                         PreparedStatement stmtServers = conn.prepareStatement(
                                 "INSERT INTO servers (server_name, port, max_slots, available_slots, status, server_type, restricted, created_at) " +
                                         "VALUES (?, ?, 0, 0, 'LOADING', ?, false, NOW())"
                         )) {
                        // games
                        stmt.setString(1, serverName);
                        stmt.setString(2, gameType);
                        stmt.setString(3, mode);
                        stmt.setInt(4, minPlayers);
                        stmt.setInt(5, maxPlayers);
                        stmt.executeUpdate();

                        // servers
                        stmtServers.setString(1, serverName);
                        stmtServers.setInt(2, port);
                        stmtServers.setString(3, gameType);
                        stmtServers.executeUpdate();
                    }

                    // Génération YAML (On utilise les templates game)
                    String templateDir = "/opt/infra/deployments";
                    String genDir = "/tmp/k3s-gen-games";
                    new java.io.File(genDir).mkdirs();

                    for (String suffix : new String[]{"pvc-template.yaml", "deployment-template.yaml"}) {
                        String templatePath = templateDir + "/game-" + suffix;
                        String targetPath = genDir + "/" + serverName + "-" + suffix;

                        String sedCommand = String.format("sed 's/__SERVER_NAME__/%s/g; s/__SERVER_PORT__/%d/g; s/__GAME_TYPE__/%s/g; s/__MODE__/%s/g' %s > %s",
                                serverName, port, gameType, mode, templatePath, targetPath);

                        ShellExecutor.run(sedCommand);
                    }

                    // Déploiement
                    ShellExecutor.run(KUBECTL + " apply -f " + genDir);
                    System.out.println("[DeployGame] YAML appliqué pour " + serverName);

                    ShellExecutor.run(
                            KUBECTL + " wait --for=condition=Ready pod -l app=" + serverName + " --timeout=60s"
                    );

                    String podIp = ShellExecutor.runAndGet(
                            KUBECTL + " get pod -l app=" + serverName + " -o jsonpath='{.items[0].status.podIP}'"
                    ).trim();

                    String podName = ShellExecutor.runAndGet(
                            KUBECTL + " get pod -l app=" + serverName + " -o jsonpath='{.items[0].metadata.name}'"
                    ).trim();

                    // Nettoyage YAML
                    ShellExecutor.run("rm -f " + genDir + "/" + serverName + "-*.yaml");

                    // Velocity
                    ShellExecutor.run(
                            KUBECTL + " wait --for=condition=Ready pod -l app=velocity --timeout=60s"
                    );

                    String velocityIp = ShellExecutor.runAndGet(
                            KUBECTL + " get pod -l app=velocity -o jsonpath='{.items[0].status.podIP}'"
                    ).trim();

                    String curl = String.format(
                            "curl -X POST http://%s:8081/add-server -H 'Content-Type: application/json' " +
                                    "-d '{\"name\":\"%s\",\"ip\":\"%s\",\"port\":%d,\"type\":\"%s\",\"restricted\":false}'",
                            velocityIp, serverName, podIp, port, gameType);

                    ShellExecutor.run(curl);

                    // ─── UPDATE DB final pour servers ───
                    try (Connection conn = getConn();
                         PreparedStatement updateServers = conn.prepareStatement(
                                 "UPDATE servers SET k3s_server_name = ?, ip = ?, max_slots = ?, available_slots = ?, status = 'LOADING', restricted = false WHERE server_name = ?")) {

                        updateServers.setString(1, podName);
                        updateServers.setString(2, podIp);
                        updateServers.setInt(3, maxPlayers);
                        updateServers.setInt(4, maxPlayers);
                        updateServers.setString(5, serverName);
                        updateServers.executeUpdate();
                    }

                    // Transition LOADING → ONLINE pour games et servers
                    ScheduledExecutorService scheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
                    scheduler.schedule(() -> {
                        try (Connection conn = getConn();
                             PreparedStatement updateGames = conn.prepareStatement(
                                     "UPDATE games SET state = 'WAITING' WHERE server_name = ?");
                             PreparedStatement updateServers = conn.prepareStatement(
                                     "UPDATE servers SET status = 'ONLINE' WHERE server_name = ?")) {

                            updateGames.setString(1, serverName);
                            updateGames.executeUpdate();

                            updateServers.setString(1, serverName);
                            updateServers.executeUpdate();
                            System.out.println("[DeployGame] Game " + serverName + " WAITING & ONLINE in servers.");
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }, 45, java.util.concurrent.TimeUnit.SECONDS);

                    channel.basicAck(delivery.getEnvelope().getDeliveryTag(), false);

                } catch (Exception e) {
                    e.printStackTrace();
                    channel.basicNack(delivery.getEnvelope().getDeliveryTag(), false, false);
                }

            }, consumerTag -> {});
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}

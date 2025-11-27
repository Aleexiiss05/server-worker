package fr.hylaria.worker;

import com.rabbitmq.client.*;
import org.json.JSONObject;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Random;
import java.util.concurrent.ScheduledExecutorService;

public class DeployWorker implements Runnable {

    private static final String KUBECTL = "kubectl";

    private int findAvailablePort(String host, String dbName, String user, String pass) {
        final int START = 25580;
        final int END = 25620;

        try (Connection conn = DriverManager.getConnection("jdbc:mysql://" + host + "/" + dbName, user, pass)) {
            for (int port = START; port <= END; port++) {
                boolean usedInDB = false;
                boolean usedInK3s = false;

                try (PreparedStatement stmt = conn.prepareStatement("SELECT COUNT(*) FROM servers WHERE port = ?")) {
                    stmt.setInt(1, port);
                    var rs = stmt.executeQuery();
                    if (rs.next() && rs.getInt(1) > 0) usedInDB = true;
                }

                String result = ShellExecutor.runAndGet(
                        KUBECTL + " get pods -o jsonpath='{.items[*].spec.containers[*].ports[*].containerPort}'"
                );

                if (result.contains(String.valueOf(port))) usedInK3s = true;

                if (!usedInDB && !usedInK3s) return port;
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
            channel.queueDeclare("deploy-server", true, false, false, null);

            channel.basicConsume("deploy-server", false, (consumerTag, delivery) -> {
                try {
                    String msg = new String(delivery.getBody(), "UTF-8");
                    JSONObject data = new JSONObject(msg);
                    System.out.println("[Deploy] Reçu : " + data);

                    String dbHost = data.getString("dbHost");
                    String dbName = data.getString("dbName");
                    String dbUser = data.getString("dbUser");
                    String dbPass = data.getString("dbPass");
                    String serverType = data.getString("serverType");
                    boolean isRestricted = data.optBoolean("restricted", false);

                    String serverName = serverType.equalsIgnoreCase("hub")
                            ? "hub-" + (1000 + new Random().nextInt(9000))
                            : serverType.toLowerCase() + "-" + (1000 + new Random().nextInt(9000));

                    int port = findAvailablePort(dbHost, dbName, dbUser, dbPass);

                    // Insert initial LOADING entry
                    try (Connection conn = DriverManager.getConnection("jdbc:mysql://" + dbHost + "/" + dbName, dbUser, dbPass);
                         PreparedStatement stmt = conn.prepareStatement(
                                 "INSERT INTO servers (server_name, port, max_slots, available_slots, status, server_type, restricted, created_at) " +
                                         "VALUES (?, ?, 0, 0, 'LOADING', ?, ?, NOW())")) {

                        stmt.setString(1, serverName);
                        stmt.setInt(2, port);
                        stmt.setString(3, serverType);
                        stmt.setBoolean(4, isRestricted);
                        stmt.executeUpdate();
                    }

                    String templateDir = "/opt/infra/deployments";
                    String genDir = "/tmp/k3s-gen";
                    new java.io.File(genDir).mkdirs();

                    String prefix = serverType.equalsIgnoreCase("hub") ? "hub" : "game";

                    for (String suffix : new String[]{"pvc-template.yaml", "deployment-template.yaml"}) {
                        String templatePath = templateDir + "/" + prefix + "-" + suffix;
                        String targetPath = genDir + "/" + serverName + "-" + suffix;

                        String sedCommand = serverType.equalsIgnoreCase("hub")
                                ? String.format("sed 's/__SERVER_NAME__/%s/g; s/__SERVER_PORT__/%d/g' %s > %s",
                                serverName, port, templatePath, targetPath)
                                : String.format("sed 's/__SERVER_NAME__/%s/g; s/__SERVER_PORT__/%d/g; s/__GAME_TYPE__/%s/g' %s > %s",
                                serverName, port, serverType, templatePath, targetPath);

                        ShellExecutor.run(sedCommand);
                    }

                    // Apply manifests
                    ShellExecutor.run(KUBECTL + " apply -f " + genDir);
                    System.out.println("[Deploy] Fichiers YAML appliqués.");

                    // Wait for pod creation
                    ShellExecutor.run(
                            KUBECTL + " wait --for=condition=Ready pod -l app=" + serverName + " --timeout=60s"
                    );

                    String podIp = ShellExecutor.runAndGet(
                            KUBECTL + " get pod -l app=" + serverName + " -o jsonpath='{.items[0].status.podIP}'"
                    ).trim();

                    String podName = ShellExecutor.runAndGet(
                            KUBECTL + " get pod -l app=" + serverName + " -o jsonpath='{.items[0].metadata.name}'"
                    ).trim();

                    System.out.println("[Deploy] Pod IP : " + podIp);
                    System.out.println("[Deploy] Pod nom : " + podName);

                    // Cleanup YAMLs
                    ShellExecutor.run("rm -f " + genDir + "/" + serverName + "-*.yaml");
                    System.out.println("[Deploy] Fichiers YAML temporaires supprimés.");

                    // Find Velocity
                    ShellExecutor.run(
                            KUBECTL + " wait --for=condition=Ready pod -l app=velocity --timeout=60s"
                    );

                    String velocityIp = ShellExecutor.runAndGet(
                            KUBECTL + " get pod -l app=velocity -o jsonpath='{.items[0].status.podIP}'"
                    ).trim();

                    System.out.println("[Deploy] Velocity IP = " + velocityIp);

                    // Add server via Velocity API
                    String curl = String.format(
                            "curl -X POST http://%s:8081/add-server -H 'Content-Type: application/json' " +
                                    "-d '{\"name\":\"%s\",\"ip\":\"%s\",\"port\":%d,\"type\":\"%s\",\"restricted\":%s}'",
                            velocityIp, serverName, podIp, port, serverType, isRestricted);

                    ShellExecutor.run(curl);

                    // Update DB with final info
                    try (Connection conn = DriverManager.getConnection("jdbc:mysql://" + dbHost + "/" + dbName, dbUser, dbPass);
                         PreparedStatement update = conn.prepareStatement(
                                 "UPDATE servers SET k3s_server_name = ?, ip = ?, max_slots = 100, available_slots = 100, status = 'LOADING', restricted = ? WHERE server_name = ?")) {

                        update.setString(1, podName);
                        update.setString(2, podIp);
                        update.setBoolean(3, isRestricted);
                        update.setString(4, serverName);
                        update.executeUpdate();
                    }

                    // Schedule ONLINE update
                    ScheduledExecutorService scheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
                    scheduler.schedule(() -> {
                        try (Connection conn = DriverManager.getConnection("jdbc:mysql://" + dbHost + "/" + dbName, dbUser, dbPass);
                             PreparedStatement update = conn.prepareStatement(
                                     "UPDATE servers SET status = 'ONLINE' WHERE server_name = ?")) {

                            update.setString(1, serverName);
                            update.executeUpdate();
                            System.out.println("[Deploy] Serveur " + serverName + " mis en ONLINE.");
                        } catch (SQLException e) {
                            e.printStackTrace();
                        }
                    }, 45, java.util.concurrent.TimeUnit.SECONDS);

                    System.out.println("[Deploy] Serveur " + serverName + " déployé.");
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

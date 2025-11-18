package fr.hylaria.worker;

import com.rabbitmq.client.Channel;
import org.json.JSONObject;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

public class ShutdownAllWorker implements Runnable {

    @Override
    public void run() {
        try {
            Channel channel = RabbitMQManager.createChannel();
            channel.queueDeclare("shutdown-all", true, false, false, null);

            channel.basicConsume("shutdown-all", false, (consumerTag, delivery) -> {
                try {
                    String msg = new String(delivery.getBody(), "UTF-8");
                    JSONObject data = new JSONObject(msg);
                    System.out.println("[ShutdownAll] Reçu shutdown-all : " + data);

                    String dbHost = data.getString("dbHost");
                    String dbName = data.getString("dbName");
                    String dbUser = data.getString("dbUser");
                    String dbPass = data.getString("dbPass");

                    List<String> serverNames = getDynamicServers(dbHost, dbName, dbUser, dbPass);

                    if (serverNames.isEmpty()) {
                        System.out.println("[ShutdownAll] Aucun serveur dynamique à supprimer.");
                        channel.basicAck(delivery.getEnvelope().getDeliveryTag(), false);
                        return;
                    }

                    ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);

                    for (String serverName : serverNames) {
                        System.out.println("[ShutdownAll] Planification suppression de : " + serverName);

                        try (Connection conn = DriverManager.getConnection("jdbc:mysql://" + dbHost + "/" + dbName, dbUser, dbPass);
                             PreparedStatement update = conn.prepareStatement(
                                     "UPDATE servers SET status = 'MAINTENANCE' WHERE server_name = ?")) {
                            update.setString(1, serverName);
                            update.executeUpdate();
                            System.out.println("[ShutdownAll] " + serverName + " -> MAINTENANCE");
                        }

                        scheduler.schedule(() -> {
                            try {
                                System.out.println("[ShutdownAll] Suppression de : " + serverName);

                                // K8s
                                ShellExecutor.run("kubectl delete deployment " + serverName);
                                ShellExecutor.run("kubectl delete pvc " + serverName + "-pvc");

                                // IP Velocity
                                String velocityIp = ShellExecutor.runAndGet(
                                        "kubectl get pod -l app=velocity -o jsonpath={.items[0].status.podIP}"
                                ).trim();

                                if (velocityIp.isEmpty()) {
                                    System.err.println("[ShutdownAll] IP de Velocity introuvable");
                                } else {
                                    String curl = String.format(
                                            "curl -X POST http://%s:8081/remove-server?name=%s",
                                            velocityIp, serverName
                                    );
                                    ShellExecutor.run(curl);
                                    System.out.println("[ShutdownAll] Velocity notifié pour : " + serverName);
                                }

                                try (Connection conn = DriverManager.getConnection("jdbc:mysql://" + dbHost + "/" + dbName, dbUser, dbPass);
                                     PreparedStatement delete = conn.prepareStatement(
                                             "DELETE FROM servers WHERE server_name = ?")) {
                                    delete.setString(1, serverName);
                                    delete.executeUpdate();
                                    System.out.println("[ShutdownAll] " + serverName + " supprimé de la base");
                                }

                            } catch (Exception e) {
                                System.err.println("[ShutdownAll] Erreur suppression " + serverName);
                                e.printStackTrace();
                            }
                        }, 5, TimeUnit.SECONDS); // attente 5s
                    }

                    channel.basicAck(delivery.getEnvelope().getDeliveryTag(), false);

                    // Shutdown du scheduler après 30s
                    scheduler.schedule(() -> {
                        System.out.println("[ShutdownAll] Toutes les suppressions ont été planifiées.");
                        scheduler.shutdown();
                    }, 30, TimeUnit.SECONDS);

                } catch (Exception e) {
                    System.err.println("[ShutdownAll] Erreur globale RabbitMQ");
                    e.printStackTrace();
                    channel.basicNack(delivery.getEnvelope().getDeliveryTag(), false, false);
                }

            }, consumerTag -> {});
        } catch (Exception e) {
            System.err.println("[ShutdownAll] Échec initial du worker");
            e.printStackTrace();
        }
    }

    private List<String> getDynamicServers(String dbHost, String dbName, String dbUser, String dbPass) {
        List<String> servers = new ArrayList<>();
        String[] prefixes = {"hub", "game", "dev", "custom", "event", "freecube", "rush", "skywars", "bedwars"};

        try (Connection conn = DriverManager.getConnection("jdbc:mysql://" + dbHost + "/" + dbName, dbUser, dbPass)) {
            for (String prefix : prefixes) {
                try (PreparedStatement stmt = conn.prepareStatement("SELECT server_name FROM servers WHERE server_name LIKE ?")) {
                    stmt.setString(1, prefix + "%");
                    ResultSet rs = stmt.executeQuery();
                    while (rs.next()) {
                        servers.add(rs.getString("server_name"));
                    }
                }
            }
        } catch (SQLException e) {
            System.err.println("[ShutdownAll] Erreur DB");
            e.printStackTrace();
        }

        return servers;
    }
}

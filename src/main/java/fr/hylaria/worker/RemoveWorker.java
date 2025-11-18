package fr.hylaria.worker;

import com.rabbitmq.client.*;
import org.json.JSONObject;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.concurrent.*;

public class RemoveWorker implements Runnable {

    @Override
    public void run() {
        try {
            Channel channel = RabbitMQManager.createChannel();
            channel.queueDeclare("remove-server", true, false, false, null);

            channel.basicConsume("remove-server", false, (consumerTag, delivery) -> {
                try {
                    String msg = new String(delivery.getBody(), "UTF-8");
                    JSONObject data = new JSONObject(msg);
                    System.out.println("[Remove] 📩 Reçu : " + data);

                    String dbHost = data.getString("dbHost");
                    String dbName = data.getString("dbName");
                    String dbUser = data.getString("dbUser");
                    String dbPass = data.getString("dbPass");
                    String serverName = data.getString("serverName");

                    try (Connection conn = DriverManager.getConnection("jdbc:mysql://" + dbHost + "/" + dbName, dbUser, dbPass);
                         PreparedStatement update = conn.prepareStatement(
                                 "UPDATE servers SET status = 'MAINTENANCE' WHERE server_name = ?")) {
                        update.setString(1, serverName);
                        update.executeUpdate();
                        System.out.println("[Remove] " + serverName + " passé en MAINTENANCE");
                    }

                    ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
                    scheduler.schedule(() -> {
                        try {
                            System.out.println("[Remove] Suppression du serveur : " + serverName);

                            ShellExecutor.run("kubectl delete deployment " + serverName);
                            ShellExecutor.run("kubectl delete pvc " + serverName + "-pvc");

                            String velocityIp = ShellExecutor.runAndGet(
                                    "kubectl get pod -l app=velocity -o jsonpath={.items[0].status.podIP}"
                            ).trim();

                            if (velocityIp.isEmpty()) {
                                System.err.println("[Remove] Impossible de trouver l’IP du pod Velocity");
                            } else {
                                String curl = String.format(
                                        "curl -X POST http://%s:8081/remove-server?name=%s",
                                        velocityIp, serverName
                                );
                                ShellExecutor.run(curl);
                                System.out.println("[Remove] 🔄 Notifié Velocity de la suppression");
                            }

                            try (Connection conn = DriverManager.getConnection("jdbc:mysql://" + dbHost + "/" + dbName, dbUser, dbPass);
                                 PreparedStatement delete = conn.prepareStatement(
                                         "DELETE FROM servers WHERE server_name = ?")) {
                                delete.setString(1, serverName);
                                delete.executeUpdate();
                                System.out.println("[Remove] Serveur " + serverName + " supprimé de la base");
                            }

                        } catch (Exception e) {
                            System.err.println("[Remove] Erreur pendant la suppression du serveur " + serverName);
                            e.printStackTrace();
                        } finally {
                            scheduler.shutdown();
                        }
                    }, 5, TimeUnit.SECONDS);

                    channel.basicAck(delivery.getEnvelope().getDeliveryTag(), false);

                } catch (Exception e) {
                    System.err.println("[Remove] Erreur dans le traitement du message RabbitMQ");
                    e.printStackTrace();
                    channel.basicNack(delivery.getEnvelope().getDeliveryTag(), false, false);
                }

            }, consumerTag -> {});
        } catch (Exception e) {
            System.err.println("[Remove] Erreur d'initialisation du worker");
            e.printStackTrace();
        }
    }
}

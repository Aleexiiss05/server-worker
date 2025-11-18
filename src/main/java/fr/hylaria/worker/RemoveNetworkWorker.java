package fr.hylaria.worker;

import com.rabbitmq.client.Channel;
import org.json.JSONObject;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;

public class RemoveNetworkWorker implements Runnable {

    @Override
    public void run() {
        try {
            Channel channel = RabbitMQManager.createChannel();
            channel.queueDeclare("remove-network", true, false, false, null);

            channel.basicConsume("remove-network", false, (consumerTag, delivery) -> {
                try {
                    String msg = new String(delivery.getBody(), "UTF-8");
                    JSONObject data = new JSONObject(msg);
                    System.out.println("[Remove-Network] 📩 Reçu : " + data);

                    String dbHost = data.getString("dbHost");
                    String dbName = data.getString("dbName");
                    String dbUser = data.getString("dbUser");
                    String dbPass = data.getString("dbPass");
                    String serverName = "velocity";

                    System.out.println("[Remove-Network] ⛔ Suppression des ressources Kubernetes...");
                    ShellExecutor.run("kubectl delete deployment " + serverName);
                    ShellExecutor.run("kubectl delete pvc " + serverName + "-pvc");
                    ShellExecutor.run("kubectl delete service " + serverName);

                    System.out.println("[Remove-Network] 📦 Nettoyage base de données...");
                    try (Connection conn = DriverManager.getConnection("jdbc:mysql://" + dbHost + "/" + dbName, dbUser, dbPass);
                         PreparedStatement stmt = conn.prepareStatement("DELETE FROM servers WHERE server_name = ?")) {
                        stmt.setString(1, serverName);
                        int rows = stmt.executeUpdate();
                        if (rows > 0) {
                            System.out.println("[Remove-Network] 🗑️ Velocity supprimé de la base de données");
                        } else {
                            System.out.println("[Remove-Network] ℹ️ Velocity n’était pas en base");
                        }
                    }

                    System.out.println("[Remove-Network] ✅ Velocity supprimé avec succès");
                    channel.basicAck(delivery.getEnvelope().getDeliveryTag(), false);

                } catch (Exception e) {
                    System.err.println("[Remove-Network] ❌ Erreur lors de la suppression");
                    e.printStackTrace();
                    channel.basicNack(delivery.getEnvelope().getDeliveryTag(), false, false);
                }
            }, consumerTag -> {});
        } catch (Exception e) {
            System.err.println("[Remove-Network] ❌ Erreur d'initialisation du worker");
            e.printStackTrace();
        }
    }
}

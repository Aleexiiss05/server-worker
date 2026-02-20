package fr.hylaria.worker;

import com.rabbitmq.client.Channel;
import org.json.JSONObject;

import java.sql.Connection;
import java.sql.PreparedStatement;

public class RemoveNetworkWorker implements Runnable {

    private static final String KUBECTL = "kubectl";

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

                    MySQLPool.init(dbHost, dbName, dbUser, dbPass);

                    String name = "velocity";

                    // SUPPRESSION K8S
                    ShellExecutor.run(KUBECTL + " delete deployment " + name);
                    ShellExecutor.run(KUBECTL + " delete pvc " + name + "-pvc");
                    ShellExecutor.run(KUBECTL + " delete service " + name);

                    // SUPPRESSION SQL
                    try (Connection conn = MySQLPool.get();
                         PreparedStatement stmt = conn.prepareStatement(
                                 "DELETE FROM servers WHERE server_name=?")) {

                        stmt.setString(1, name);
                        stmt.executeUpdate();
                    }

                    System.out.println("[Remove-Network] ✔ SUPPRIMÉ");
                    channel.basicAck(delivery.getEnvelope().getDeliveryTag(), false);

                } catch (Exception e) {
                    System.err.println("[Remove-Network] ❌ Erreur suppression");
                    e.printStackTrace();
                    channel.basicNack(delivery.getEnvelope().getDeliveryTag(), false, false);
                }

            }, consumerTag -> {});
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}

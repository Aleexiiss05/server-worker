package fr.hylaria.worker;

import com.rabbitmq.client.Channel;
import org.json.JSONObject;

import java.sql.Connection;
import java.sql.PreparedStatement;

public class RemoveWorker implements Runnable {

    private static final String KUBECTL = "kubectl";

    @Override
    public void run() {
        try {
            Channel channel = RabbitMQManager.createChannel();
            channel.queueDeclare("remove-server", true, false, false, null);

            channel.basicConsume("remove-server", false, (consumerTag, delivery) -> {
                try {

                    String msg = new String(delivery.getBody(), "UTF-8");
                    JSONObject data = new JSONObject(msg);

                    String dbHost = data.getString("dbHost");
                    String dbName = data.getString("dbName");
                    String dbUser = data.getString("dbUser");
                    String dbPass = data.getString("dbPass");
                    String name = data.getString("serverName");

                    MySQLPool.init(dbHost, dbName, dbUser, dbPass);

                    // MAINTENANCE
                    try (Connection conn = MySQLPool.get();
                         PreparedStatement stmt = conn.prepareStatement(
                                 "UPDATE servers SET status='MAINTENANCE' WHERE server_name=?")) {
                        stmt.setString(1, name);
                        stmt.executeUpdate();
                    }

                    // DELETE DEPLOYMENT
                    ShellExecutor.run(KUBECTL + " delete deployment " + name);
                    ShellExecutor.run(KUBECTL + " delete pvc " + name + "-pvc");

                    // REMOVE FROM VELOCITY
                    String velocityIp = ShellExecutor.runAndGet(
                            KUBECTL + " get pod -l app=velocity -o jsonpath='{.items[0].status.podIP}'"
                    ).trim();

                    if (!velocityIp.isEmpty()) {
                        ShellExecutor.run(
                                "curl -X POST http://" + velocityIp + ":8081/remove-server?name=" + name
                        );
                    }

                    // DELETE SQL
                    try (Connection conn = MySQLPool.get();
                         PreparedStatement stmt = conn.prepareStatement(
                                 "DELETE FROM servers WHERE server_name=?")) {
                        stmt.setString(1, name);
                        stmt.executeUpdate();
                    }

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

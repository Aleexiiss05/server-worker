package fr.hylaria.worker;

import com.rabbitmq.client.Channel;
import org.json.JSONObject;

import java.sql.Connection;
import java.sql.PreparedStatement;

public class DeployNetworkWorker implements Runnable {

    private static final String KUBECTL = "kubectl";

    @Override
    public void run() {
        try {
            Channel channel = RabbitMQManager.createChannel();
            channel.queueDeclare("deploy-network", true, false, false, null);

            channel.basicConsume("deploy-network", false, (consumerTag, delivery) -> {
                try {
                    String msg = new String(delivery.getBody(), "UTF-8");
                    JSONObject data = new JSONObject(msg);
                    System.out.println("[Deploy-Network] Reçu : " + data);

                    String dbHost = data.getString("dbHost");
                    String dbName = data.getString("dbName");
                    String dbUser = data.getString("dbUser");
                    String dbPass = data.getString("dbPass");

                    MySQLPool.init(dbHost, dbName, dbUser, dbPass);

                    String name = "velocity";
                    int port = 25565;

                    // Vérifier existence
                    try (Connection conn = MySQLPool.get();
                         PreparedStatement check = conn.prepareStatement(
                                 "SELECT COUNT(*) FROM servers WHERE server_name=?")) {

                        check.setString(1, name);
                        var rs = check.executeQuery();

                        if (rs.next() && rs.getInt(1) > 0) {
                            System.out.println("[Deploy-Network] ❌ Velocity existe déjà.");
                            channel.basicAck(delivery.getEnvelope().getDeliveryTag(), false);
                            return;
                        }
                    }

                    String genDir = "/tmp/k3s-gen";
                    String templateDir = "/opt/infra/deployments";

                    ShellExecutor.run("mkdir -p " + genDir);

                    // Génération YAML
                    for (String suffix : new String[] {
                            "pvc-template.yaml", "deployment-template.yaml", "service-template.yaml"
                    }) {
                        String tpl = templateDir + "/velocity-" + suffix;
                        String out = genDir + "/velocity-" + suffix;

                        ShellExecutor.run(
                                "sed 's/__SERVER_NAME__/" + name + "/g' " + tpl + " > " + out
                        );
                    }

                    // Apply
                    ShellExecutor.run(KUBECTL + " apply -f " + genDir);

                    // Wait ready
                    ShellExecutor.run(KUBECTL + " wait --for=condition=Ready pod -l app=velocity --timeout=120s");

                    // IP & name
                    String podIp = ShellExecutor.runAndGet(
                            KUBECTL + " get pod -l app=velocity -o jsonpath='{.items[0].status.podIP}'"
                    ).trim();

                    String podName = ShellExecutor.runAndGet(
                            KUBECTL + " get pod -l app=velocity -o jsonpath='{.items[0].metadata.name}'"
                    ).trim();

                    if (podIp.isEmpty()) throw new RuntimeException("IP Velocity introuvable.");

                    // Insert BDD
                    try (Connection conn = MySQLPool.get();
                         PreparedStatement insert = conn.prepareStatement(
                                 "INSERT INTO servers (server_name, server_type, port, max_slots, available_slots, status, restricted, ip, k3s_server_name, created_at) " +
                                         "VALUES (?, 'PROXY', ?, 500, 500, 'ONLINE', false, ?, ?, NOW())")) {

                        insert.setString(1, name);
                        insert.setInt(2, port);
                        insert.setString(3, podIp);
                        insert.setString(4, podName);
                        insert.executeUpdate();
                    }

                    System.out.println("[Deploy-Network] ✅ Velocity déployé.");
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

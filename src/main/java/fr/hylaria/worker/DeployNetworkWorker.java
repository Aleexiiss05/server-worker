package fr.hylaria.worker;

import com.rabbitmq.client.Channel;
import org.json.JSONObject;

import java.sql.Connection;
import java.sql.DriverManager;
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

                    String name = "velocity";
                    int port = 25565;

                    String templateDir = "/opt/infra/deployments";
                    String genDir = "/tmp/k3s-gen";

                    // Vérifier si Velocity existe déjà
                    try (Connection conn = DriverManager.getConnection(
                            "jdbc:mysql://" + dbHost + "/" + dbName, dbUser, dbPass);
                         PreparedStatement check = conn.prepareStatement(
                                 "SELECT COUNT(*) FROM servers WHERE server_name = ?")) {

                        check.setString(1, name);
                        var rs = check.executeQuery();

                        if (rs.next() && rs.getInt(1) > 0) {
                            System.out.println("Velocity existe déjà. Annulation.");
                            channel.basicAck(delivery.getEnvelope().getDeliveryTag(), false);
                            return;
                        }
                    }

                    ShellExecutor.run("mkdir -p " + genDir);

                    // Génération des fichiers YAML
                    for (String suffix :
                            new String[]{"pvc-template.yaml", "deployment-template.yaml", "service-template.yaml"}) {

                        String tpl = templateDir + "/velocity-" + suffix;
                        String out = genDir + "/velocity-" + suffix;

                        ShellExecutor.run(
                                "sed 's/__SERVER_NAME__/" + name + "/g' " + tpl + " > " + out
                        );
                    }

                    // Apply
                    ShellExecutor.run(
                            KUBECTL + " apply -f " + genDir
                    );

                    // Wait ready
                    ShellExecutor.run(
                            KUBECTL + " wait --for=condition=Ready pod -l app=" + name + " --timeout=90s"
                    );

                    // Récupérer IP du pod
                    String podIp = ShellExecutor.runAndGet(
                            KUBECTL + " get pod -l app=" + name +
                                    " -o jsonpath='{.items[0].status.podIP}'"
                    ).trim();

                    if (podIp.isEmpty())
                        throw new RuntimeException("IP Velocity introuvable");

                    String podName = ShellExecutor.runAndGet(
                            KUBECTL + " get pod -l app=" + name +
                                    " -o jsonpath='{.items[0].metadata.name}'"
                    ).trim();

                    // Insérer en BDD
                    try (Connection conn = DriverManager.getConnection(
                            "jdbc:mysql://" + dbHost + "/" + dbName, dbUser, dbPass);
                         PreparedStatement insert = conn.prepareStatement(
                                 "INSERT INTO servers " +
                                         "(server_name, server_type, port, max_slots, available_slots, status, restricted, ip, k3s_server_name, created_at) " +
                                         "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())")) {

                        insert.setString(1, name);
                        insert.setString(2, "PROXY");
                        insert.setInt(3, port);
                        insert.setInt(4, 100);
                        insert.setInt(5, 100);
                        insert.setString(6, "ONLINE");
                        insert.setBoolean(7, false);
                        insert.setString(8, podIp);
                        insert.setString(9, podName);

                        insert.executeUpdate();
                    }

                    System.out.println("[Deploy-Network] ✅ Velocity déployé et enregistré.");
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

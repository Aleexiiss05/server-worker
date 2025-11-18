package fr.hylaria.worker;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.DeliverCallback;
import org.json.JSONObject;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.Objects;

public class DeployNetworkWorker implements Runnable {

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

                    // Vérifier si déjà en base
                    String checkQuery = String.format(
                            "SELECT COUNT(*) FROM servers WHERE server_name = '%s'", name
                    );
                    try (Connection conn = DriverManager.getConnection("jdbc:mysql://" + dbHost + "/" + dbName, dbUser, dbPass);
                         PreparedStatement check = conn.prepareStatement("SELECT COUNT(*) FROM servers WHERE server_name = ?")) {

                        check.setString(1, name);
                        var rs = check.executeQuery();

                        if (!rs.next() || rs.getInt(1) > 0) {
                            System.out.println("❌ Velocity existe déjà. Annulation.");
                            channel.basicAck(delivery.getEnvelope().getDeliveryTag(), false);
                            return;
                        }

                    }


                    // Génération YAML
                    ShellExecutor.run("mkdir -p " + genDir);
                    for (String suffix : new String[]{"pvc-template.yaml", "deployment-template.yaml", "service-template.yaml"}) {
                        String input = String.format("%s/velocity-%s", templateDir, suffix);
                        String output = String.format("%s/velocity-%s", genDir, suffix);
                        ShellExecutor.run(String.format("sed 's/__SERVER_NAME__/%s/g' %s > %s", name, input, output));
                    }

                    // Déploiement
                    ShellExecutor.run("kubectl apply -f " + genDir + "/velocity-pvc-template.yaml");
                    ShellExecutor.run("kubectl apply -f " + genDir + "/velocity-deployment-template.yaml");
                    ShellExecutor.run("kubectl apply -f " + genDir + "/velocity-service-template.yaml");

                    // Attente disponibilité pod
                    ShellExecutor.run("kubectl wait --for=condition=Ready pod -l app=" + name + " --timeout=90s");

                    // Récupération IP
                    String podIp = ShellExecutor.runAndGet("kubectl get pod -l app=" + name + " -o jsonpath='{.items[0].status.podIP}'").trim();
                    if (podIp.isEmpty()) throw new RuntimeException("IP de Velocity introuvable");

                    // Insertion en base
                    try (Connection conn = DriverManager.getConnection("jdbc:mysql://" + dbHost + "/" + dbName, dbUser, dbPass);
                         PreparedStatement insert = conn.prepareStatement(
                                 "INSERT INTO servers (server_name, k3s_server_name, ip, port, max_slots, available_slots, status, server_type, created_at) " +
                                         "VALUES (?, ?, ?, ?, ?, ?, ?, ?, NOW())")) {

                        insert.setString(1, name);
                        insert.setString(2, name);
                        insert.setString(3, podIp);
                        insert.setInt(4, port);
                        insert.setInt(5, 100);
                        insert.setInt(6, 100);
                        insert.setString(7, "ONLINE");
                        insert.setString(8, "PROXY");

                        insert.executeUpdate();
                    }

                    System.out.println("[Deploy-Network] ✅ Velocity déployé et enregistré");

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

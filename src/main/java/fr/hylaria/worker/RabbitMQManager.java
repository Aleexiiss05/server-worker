package fr.hylaria.worker;

import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.Channel;

public class RabbitMQManager {

    private static Connection connection;

    public static void connectWithRetry() {
        String host = System.getenv().getOrDefault("RABBITMQ_HOST", "rabbitmq");
        int port = Integer.parseInt(System.getenv().getOrDefault("RABBITMQ_PORT", "5672"));
        String user = System.getenv().getOrDefault("RABBITMQ_USER", "guest");
        String pass = System.getenv().getOrDefault("RABBITMQ_PASS", "guest");

        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(host);
        factory.setPort(port);
        factory.setUsername(user);
        factory.setPassword(pass);

        System.out.println("[RabbitMQ] Connexion à " + host + ":" + port);

        while (true) {
            try {
                connection = factory.newConnection();
                System.out.println("[RabbitMQ] Connecté !");
                break;
            } catch (Exception e) {
                System.err.println("[RabbitMQ] Impossible de se connecter, retry dans 2s...");
                try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
            }
        }
    }

    public static Channel createChannel() throws Exception {
        if (connection == null || !connection.isOpen()) {
            throw new IllegalStateException("Connexion RabbitMQ non établie");
        }
        return connection.createChannel();
    }
}

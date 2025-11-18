package fr.hylaria.worker;

import com.rabbitmq.client.*;

public class RabbitMQManager {
    private static Connection connection;

    public static void connect(String host) throws Exception {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(host);
        connection = factory.newConnection();
    }

    public static Channel createChannel() throws Exception {
        return connection.createChannel();
    }
}

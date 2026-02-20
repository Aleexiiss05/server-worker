package fr.hylaria.worker;

public class Main {
    public static void main(String[] args) throws Exception {
        RabbitMQManager.connectWithRetry();
        System.out.println("DeployGameWorker lancé...");
        new Thread(new DeployNetworkWorker()).start();
        new Thread(new DeployWorker()).start();
        new Thread(new DeployGameWorker()).start();
        new Thread(new RemoveNetworkWorker()).start();
        new Thread(new RemoveWorker()).start();
        new Thread(new ShutdownAllWorker()).start();
    }
}

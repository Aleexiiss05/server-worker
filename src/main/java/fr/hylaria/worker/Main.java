package fr.hylaria.worker;

public class Main {
    public static void main(String[] args) throws Exception {
        RabbitMQManager.connect("localhost");
        System.out.println("DeployWorker lancé...");
        new Thread(new DeployNetworkWorker()).start();
        new Thread(new DeployWorker()).start();
        new Thread(new RemoveNetworkWorker()).start();
        new Thread(new RemoveWorker()).start();
        new Thread(new ShutdownAllWorker()).start();
    }
}

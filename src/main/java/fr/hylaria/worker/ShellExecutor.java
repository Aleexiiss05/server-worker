package fr.hylaria.worker;

import java.io.BufferedReader;
import java.io.InputStreamReader;

public class ShellExecutor {

    public static void run(String command) {
        try {
            Process process = new ProcessBuilder("bash", "-c", command)
                    .redirectErrorStream(true)
                    .start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    System.out.println("[shell] " + line);
                }
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                System.err.println("Command failed: " + command);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static String runAndGet(String command) {
        StringBuilder output = new StringBuilder();
        try {
            Process process = new ProcessBuilder("bash", "-c", command)
                    .redirectErrorStream(true)
                    .start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line);
                }
            }

            process.waitFor();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return output.toString();
    }
}

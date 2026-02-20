package fr.hylaria.worker;

import org.yaml.snakeyaml.Yaml;
import java.io.InputStream;
import java.util.Map;

public class GameDefaultConfig {

    private static final String CONFIG_FILE = "game-defaults.yml";
    private static Map<String, Object> config;

    @SuppressWarnings("unchecked")
    public static void init() {
        if (config != null) return;

        Yaml yaml = new Yaml();
        try (InputStream in = GameDefaultConfig.class.getClassLoader().getResourceAsStream(CONFIG_FILE)) {
            if (in == null) {
                System.out.println("[Config] Fichier " + CONFIG_FILE + " introuvable dans les ressources.");
                return;
            }
            config = yaml.load(in);
            System.out.println("[Config] Configuration YAML chargée avec succès.");
        } catch (Exception e) {
            e.printStackTrace();
            System.out.println("[Config] Erreur lors du chargement de la configuration.");
        }
    }

    @SuppressWarnings("unchecked")
    public static int getMinPlayers(String gameType, String mode) {
        if (config == null) init();
        if (config == null) return 2;
        try {
            String lowerType = gameType.toLowerCase();
            String lowerMode = mode.toLowerCase();
            
            Map<String, Object> typeMap = (Map<String, Object>) config.get(lowerType);
            if (typeMap != null) {
                Map<String, Object> modeMap = (Map<String, Object>) typeMap.get(lowerMode);
                if (modeMap != null && modeMap.containsKey("min_players")) {
                    return ((Number) modeMap.get("min_players")).intValue();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return 2; // Default fallback
    }

    @SuppressWarnings("unchecked")
    public static int getMaxPlayers(String gameType, String mode) {
        if (config == null) init();
        if (config == null) return 8;
        try {
            String lowerType = gameType.toLowerCase();
            String lowerMode = mode.toLowerCase();
            
            Map<String, Object> typeMap = (Map<String, Object>) config.get(lowerType);
            if (typeMap != null) {
                Map<String, Object> modeMap = (Map<String, Object>) typeMap.get(lowerMode);
                if (modeMap != null && modeMap.containsKey("max_players")) {
                    return ((Number) modeMap.get("max_players")).intValue();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return 8; // Default fallback
    }
}

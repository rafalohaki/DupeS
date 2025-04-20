package com.rafalohaki.DupeS;

// Import the ORIGINAL bStats packages for compilation
import org.bstats.bukkit.Metrics; // Corrected import
import org.bstats.charts.SimplePie;   // Corrected import

public class MetricsManager {

    private final DupeS plugin;
    // Your specific plugin ID obtained from bStats
    private static final int PLUGIN_ID = 25551;

    public MetricsManager(DupeS plugin) {
        this.plugin = plugin;
    }

    public void initializeMetrics() {
        // Ensure this runs safely, catching potential NoClassDefFoundError if shading failed
        // though the primary fix is the import correction above.
        try {
            // The constructor and methods will use the classes from the original packages here.
            // Maven Shade will handle replacing these with the relocated ones in the final JAR.
            Metrics metrics = new Metrics(plugin, PLUGIN_ID);

            // --- Add Custom Charts ---
            // Track if permission is required
            metrics.addCustomChart(new SimplePie("require_permission", () ->
                    String.valueOf(plugin.isRequirePermission())
            ));

            // Track if NBT duplication is prevented
            metrics.addCustomChart(new SimplePie("prevent_nbt_duplication", () ->
                    String.valueOf(plugin.isPreventNbtDuplication())
            ));

            // Track if messages are enabled
            metrics.addCustomChart(new SimplePie("enable_messages", () ->
                    String.valueOf(plugin.isEnableMessages())
            ));

            // Track if logging is enabled
            metrics.addCustomChart(new SimplePie("log_successful_duplications", () ->
                    String.valueOf(plugin.isLogSuccessfulDuplications())
            ));

            // Example: Track dupe chance category (adjust ranges as needed)
            metrics.addCustomChart(new SimplePie("dupe_chance_category", () -> {
                double chance = plugin.getDupeChance();
                if (chance <= 0) return "0%";
                if (chance < 25) return "1-24%";
                if (chance < 50) return "25-49%";
                if (chance < 75) return "50-74%";
                if (chance < 100) return "75-99%";
                return "100%";
            }));

             // Example: Track number of blacklisted items category
            metrics.addCustomChart(new SimplePie("blacklist_size_category", () -> {
                int size = plugin.getBlacklistSize();
                if (size == 0) return "0";
                if (size <= 5) return "1-5";
                if (size <= 10) return "6-10";
                return "11+";
            }));


            plugin.log("bStats metrics initialized successfully (Plugin ID: " + PLUGIN_ID + ").");

        } catch (Exception e) {
            plugin.getLogger().warning("Failed to initialize bStats metrics: " + e.getMessage());
            // Optionally print stack trace for debugging if needed
            // e.printStackTrace();
        } catch (NoClassDefFoundError e) {
             // This might happen if shading failed or dependencies are missing at runtime
             plugin.getLogger().severe("Failed to find bStats classes. Shading might have failed or bStats dependency is missing.");
             plugin.getLogger().severe("bStats Error: " + e.getMessage());
        }
    }
}
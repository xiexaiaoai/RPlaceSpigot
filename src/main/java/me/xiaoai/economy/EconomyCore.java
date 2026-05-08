package me.xiaoai.economy;

import me.xiaoai.rplace.RPlace;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * 经济系统核心管理类
 * 修复版：增加了对多个配置文件的初始化支持及详细的后台排错日志
 */
public class EconomyCore {

    private final RPlace plugin;
    private static EconomyCore instance;

    private File configFile;
    private FileConfiguration dataConfig;


    private File evolutionFile;
    private FileConfiguration evolutionConfig;

    public EconomyCore(RPlace plugin) {
        this.plugin = plugin;
        instance = this;

        // 初始化主经济配置文件
        this.configFile = new File(plugin.getDataFolder(), "economy_settings.yml");
        if (!configFile.exists()) {
            plugin.saveResource("economy_settings.yml", false);
        }

        // 修复：初始化进化配置文件，防止后台报错
        this.evolutionFile = new File(plugin.getDataFolder(), "evolution_settings.yml");
        if (!evolutionFile.exists()) {
            // 请确保你的 resources 目录下有这个文件，否则会抛出错误
            try {
                plugin.saveResource("evolution_settings.yml", false);
            } catch (Exception e) {
                Bukkit.getLogger().warning("[Economy] 无法自动生成 evolution_settings.yml，请手动创建该文件。");
            }
        }

        reloadCustomConfig();
    }

    /**
     * 配置文件加载逻辑
     * 包含详细的后台输出，用于排查 YAML 格式错误
     */
    /**
     * 加载/重载配置文件 (支持 UTF-8 编码读取)
     */
    public void reloadCustomConfig() {
        try {
            // 1. 加载主经济配置文件
            if (configFile.exists()) {
                InputStreamReader reader = new InputStreamReader(new FileInputStream(configFile), StandardCharsets.UTF_8);
                dataConfig = YamlConfiguration.loadConfiguration(reader);

                ConfigurationSection shopSection = dataConfig.getConfigurationSection("shop_items");
                if (shopSection == null) {
                    Bukkit.getLogger().severe("[Economy] !!! 配置错误：在 economy_settings.yml 中找不到 'shop_items' 节点 !!!");
                } else {
                    Bukkit.getLogger().info("[Economy] 成功加载 economy_settings.yml，识别到商品数量: " + shopSection.getKeys(false).size());
                }
            }

            // 2. 加载进化系统配置文件
            if (evolutionFile.exists()) {
                InputStreamReader evoReader = new InputStreamReader(new FileInputStream(evolutionFile), StandardCharsets.UTF_8);
                evolutionConfig = YamlConfiguration.loadConfiguration(evoReader);
                Bukkit.getLogger().info("[Economy] 成功加载 evolution_settings.yml");
            }
        } catch (Exception e) {
            Bukkit.getLogger().severe("[Economy] 加载配置文件时发生异常:");
            e.printStackTrace();
        }
    }

    public FileConfiguration getConfig() {
        if (dataConfig == null) reloadCustomConfig();
        return dataConfig;
    }

    // --- 进化系统核心接口 ---

    public FileConfiguration getEvolutionConfig() {
        if (evolutionConfig == null) reloadCustomConfig();
        return evolutionConfig;
    }

    public void saveEvolutionConfig() {
        try {
            evolutionConfig.save(evolutionFile);
        } catch (java.io.IOException e) {
            e.printStackTrace();
        }
    }

    public boolean withdrawPlayer(UUID uuid, double amount) {
        double current = getBalance(uuid);
        if (current >= amount) {
            setBalance(uuid, current - amount);
            return true;
        }
        return false;
    }

    /**
     * 配置持久化保存
     */
    public void saveCustomConfig() {
        try {
            getConfig().save(configFile);
            // 如果有修改进化配置的需求，也可以在这里保存
            // getEvolutionConfig().save(evolutionFile);
        } catch (Exception e) {
            Bukkit.getLogger().severe("[Economy] 保存配置文件失败!");
            e.printStackTrace();
        }
    }

    public void init() {
        EconomyCommand cmd = new EconomyCommand();
        plugin.getCommand("money").setExecutor(cmd);
        plugin.getCommand("money").setTabCompleter(cmd);
        plugin.getServer().getPluginManager().registerEvents(new EconomyListener(), plugin);

        startRefreshTask();
    }

    public static EconomyCore getInstance() { return instance; }

    public double getBalance(UUID uuid) {
        return getConfig().getDouble("players.data." + uuid.toString() + ".balance", 12000.0);
    }

    public void setBalance(UUID uuid, double amount) {
        String path = "players.data." + uuid.toString();
        String name = Bukkit.getOfflinePlayer(uuid).getName();
        getConfig().set(path + ".name", name != null ? name : "Unknown");
        getConfig().set(path + ".balance", Math.max(0, amount));
        saveCustomConfig();
    }
    public void updatePlayerStat(UUID uuid, String statPath, int newValue) {
        getConfig().set("player_data." + uuid.toString() + ".stats." + statPath, newValue);
        saveCustomConfig();
    }

    public int getPlayerStat(UUID uuid, String statPath, int defaultValue) {
        return getConfig().getInt("player_data." + uuid.toString() + ".stats." + statPath, defaultValue);
    }

    public void addBalance(UUID uuid, double amount) {
        setBalance(uuid, getBalance(uuid) + amount);
    }

    public boolean takeBalance(UUID uuid, double amount) {
        double current = getBalance(uuid);
        if (current < amount) return false;
        setBalance(uuid, current - amount);
        return true;
    }

    public void refreshRandomShop() {
        ConfigurationSection source = getConfig().getConfigurationSection("random_pool_source");
        if (source == null || source.getKeys(false).isEmpty()) {
            Bukkit.getLogger().warning("[Economy] 随机商店刷新失败：random_pool_source 节点为空或不存在。");
            return;
        }

        List<String> allKeys = new ArrayList<>(source.getKeys(false));
        getConfig().set("random_shop.pool", null);

        int min = getConfig().getInt("random_shop.min_items", 3);
        int max = getConfig().getInt("random_shop.max_items", 7);
        if (min > max) min = max;

        int targetCount = java.util.concurrent.ThreadLocalRandom.current().nextInt(min, max + 1);
        targetCount = Math.min(targetCount, allKeys.size());

        Collections.shuffle(allKeys);
        for (int i = 0; i < targetCount; i++) {
            String key = allKeys.get(i);
            getConfig().set("random_shop.pool." + key, source.get(key));
        }

        getConfig().set("shop_settings.last_refresh", System.currentTimeMillis());
        saveCustomConfig();
        Bukkit.getLogger().info("[Economy] 随机商店刷新完成 (" + targetCount + "件商品)");
    }

    private void startRefreshTask() {
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            long last = getConfig().getLong("shop_settings.last_refresh", 0);
            String intervalStr = getConfig().getString("shop_settings.refresh_interval", "1h").toLowerCase();
            long intervalMs = parseInterval(intervalStr);

            if (System.currentTimeMillis() - last >= intervalMs) {
                ConfigurationSection source = getConfig().getConfigurationSection("random_pool_source");
                if (source != null && !source.getKeys(false).isEmpty()) {
                    Bukkit.getScheduler().runTask(plugin, this::refreshRandomShop);
                }
            }
        }, 100L, 600L);
    }

    private long parseInterval(String str) {
        try {
            if (str.endsWith("mo")) return Long.parseLong(str.replace("mo", "")) * 30 * 24 * 3600 * 1000L;
            if (str.endsWith("s")) return Long.parseLong(str.replace("s", "")) * 1000L;
            if (str.endsWith("m")) return Long.parseLong(str.replace("m", "")) * 60 * 1000L;
            if (str.endsWith("h")) return Long.parseLong(str.replace("h", "")) * 3600 * 1000L;
            if (str.endsWith("d")) return Long.parseLong(str.replace("d", "")) * 24 * 3600 * 1000L;
            if (str.endsWith("w")) return Long.parseLong(str.replace("w", "")) * 7 * 24 * 3600 * 1000L;
        } catch (Exception e) {
            return 3600000L;
        }
        return 3600000L;
    }
}
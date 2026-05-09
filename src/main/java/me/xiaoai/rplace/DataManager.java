package me.xiaoai.rplace;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.UUID;

public class DataManager {
    private final RPlace plugin;

    // 新增：独立的数据文件对象
    private File dataFile;
    private FileConfiguration dataConfig;

    public DataManager(RPlace plugin) {
        this.plugin = plugin;
        setupDataFile(); // 初始化独立存储文件
    }

    /**
     * 第一步：初始化 data.yml 文件
     */
    public void setupDataFile() {
        if (!plugin.getDataFolder().exists()) {
            plugin.getDataFolder().mkdirs();
        }

        dataFile = new File(plugin.getDataFolder(), "data.yml");

        if (!dataFile.exists()) {
            try {
                dataFile.createNewFile();
            } catch (IOException e) {
                plugin.getLogger().severe("无法创建 data.yml!");
            }
        }
        dataConfig = YamlConfiguration.loadConfiguration(dataFile);
    }

    /**
     * 获取 data.yml 配置对象
     */
    public FileConfiguration getData() {
        return dataConfig;
    }

    /**
     * 保存排行榜与留言数据到 data.yml
     */
    public void saveRankData() {
        dataConfig.set("rankings", null);

        for (UUID uuid : plugin.placedCountMap.keySet()) {
            String path = "rankings." + uuid.toString();

            // 建议：直接获取 count 避免重复查询 Map
            int count = plugin.placedCountMap.get(uuid);
            dataConfig.set(path + ".count", count);

            // 玩家名称：getName() 有时会触发网络 IO（如果缓存失效），对于保存逻辑没大碍
            String lastKnownName = org.bukkit.Bukkit.getOfflinePlayer(uuid).getName();
            dataConfig.set(path + ".name", lastKnownName != null ? lastKnownName : "Unknown");

            // 留言保存
            String msg = plugin.playerMessages.get(uuid);
            if (msg != null) {
                dataConfig.set(path + ".message", msg);
            } else {
                // 如果你希望在文件里也看到“默认感言”，可以取消下面这行的注释
                 dataConfig.set(path + ".message", "这家伙很懒，什么都没留。");
            }
        }

        try {
            dataConfig.save(dataFile);
        } catch (IOException e) {
            plugin.getLogger().severe("§c[RPlace] 无法保存 data.yml 排名数据!");
        }
    }
    /**
     * 保存所有在线玩家的能量、时间及配置
     */
    public void savePlayerData() {
        for (UUID uuid : plugin.currentPoints.keySet()) {
            String path = "players." + uuid.toString();
            dataConfig.set(path + ".current", plugin.currentPoints.get(uuid));
            dataConfig.set(path + ".lastRecover", plugin.lastRecoverTime.get(uuid));
            dataConfig.set(path + ".max", plugin.maxPointsMap.getOrDefault(uuid, plugin.defaultMaxPoints));
            dataConfig.set(path + ".speed", plugin.recoverSpeedMap.getOrDefault(uuid, plugin.defaultRecoverSpeed));
        }
        try {
            dataConfig.save(dataFile);
        } catch (IOException e) {
            plugin.getLogger().severe("§c[RPlace] 无法保存玩家动态数据!");
        }
    }

    /**
     * 加载特定玩家数据（建议在 PlayerJoinEvent 调用）
     */
    public void saveSinglePlayerData(UUID uuid) {
        String path = "players." + uuid.toString();
        dataConfig.set(path + ".current", plugin.currentPoints.getOrDefault(uuid, plugin.defaultMaxPoints));
        dataConfig.set(path + ".lastRecover", plugin.lastRecoverTime.getOrDefault(uuid, System.currentTimeMillis()));
        dataConfig.set(path + ".max", plugin.maxPointsMap.getOrDefault(uuid, plugin.defaultMaxPoints));
        dataConfig.set(path + ".speed", plugin.recoverSpeedMap.getOrDefault(uuid, plugin.defaultRecoverSpeed));
        try {
            dataConfig.save(dataFile);
        } catch (IOException e) {
            plugin.getLogger().severe("§c[RPlace] 无法为玩家 " + uuid + " 保存单人数据!");
        }
    }

    public void loadSinglePlayerData(UUID uuid) {
        String path = "players." + uuid.toString();
        if (dataConfig.contains(path)) {
            plugin.currentPoints.put(uuid, dataConfig.getInt(path + ".current"));
            plugin.lastRecoverTime.put(uuid, dataConfig.getLong(path + ".lastRecover"));
            plugin.maxPointsMap.put(uuid, dataConfig.getInt(path + ".max"));
            plugin.recoverSpeedMap.put(uuid, dataConfig.getInt(path + ".speed"));
        } else {
            plugin.currentPoints.put(uuid, plugin.defaultMaxPoints);
            plugin.lastRecoverTime.put(uuid, System.currentTimeMillis());
            plugin.maxPointsMap.put(uuid, plugin.defaultMaxPoints);
            plugin.recoverSpeedMap.put(uuid, plugin.defaultRecoverSpeed);
        }
    }

    /**
     * 从 data.yml 加载排行榜与留言数据
     */
    public void loadRankData() {
        // 1. 强制从硬盘重新读取文件，确保拿到的是最新的 data.yml 内容
        dataConfig = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(dataFile);

        // 2. 检查节点是否存在，不存在说明是第一次运行或还没数据
        if (dataConfig.getConfigurationSection("rankings") == null) return;

        // 3. 遍历每一个玩家的 UUID 节点
        for (String key : dataConfig.getConfigurationSection("rankings").getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(key);
                String path = "rankings." + key;

                // 读取放置次数：如果不存在则默认为 0
                int count = dataConfig.getInt(path + ".count", 0);
                plugin.placedCountMap.put(uuid, count);

                // 读取留言：如果存在则存入内存
                if (dataConfig.contains(path + ".message")) {
                    String msg = dataConfig.getString(path + ".message");
                    if (msg != null && !msg.isEmpty()) {
                        plugin.playerMessages.put(uuid, msg);
                    }
                }

            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("§c[RPlace] 发现无效的 UUID 格式并跳过: " + key);
            } catch (Exception e) {
                plugin.getLogger().warning("§c[RPlace] 解析玩家 " + key + " 的数据时发生未知错误");
            }
        }
        plugin.getLogger().info("§a[RPlace] 已成功从硬盘加载所有排行榜与留言数据。");
    }

    /**
     * 原有的保存 config.yml 逻辑
     */
    public void saveConfigData() {
        FileConfiguration config = plugin.getConfig();

        // 保存画布设置
        config.set("canvas.world", plugin.canvasWorld);
        config.set("canvas.minX", plugin.minX);
        config.set("canvas.maxX", plugin.maxX);
        config.set("canvas.minZ", plugin.minZ);
        config.set("canvas.maxZ", plugin.maxZ);
        config.set("canvas.y", plugin.canvasY);
        config.set("canvas.isSet", plugin.isCanvasSet);

        // 保存全局设置
        config.set("settings.defaultMaxPoints", plugin.defaultMaxPoints);
        config.set("settings.defaultRecoverSpeed", plugin.defaultRecoverSpeed);
        config.set("settings.placeReward", plugin.placeReward);

        // 清理旧数据并重新保存玩家数据
        config.set("players.data", null);
        for (UUID uuid : plugin.maxPointsMap.keySet()) {
            String path = "players.data." + uuid.toString();

            OfflinePlayer op = Bukkit.getOfflinePlayer(uuid);
            if (op.getName() != null) {
                config.set(path + ".name", op.getName());
            }

            config.set(path + ".max", plugin.maxPointsMap.get(uuid));
            config.set(path + ".speed", plugin.recoverSpeedMap.get(uuid));

            if (plugin.adminPlayers.containsKey(uuid) && plugin.adminPlayers.get(uuid) != RPlace.AdminMode.OFF) {
                config.set(path + ".adminMode", plugin.adminPlayers.get(uuid).name());
            }
        }

        plugin.saveConfig();
    }

    /**
     * 原有的加载 config.yml 逻辑
     */
    public void loadConfigData() {
        FileConfiguration config = plugin.getConfig();

        plugin.canvasWorld = config.getString("canvas.world");
        plugin.minX = config.getInt("canvas.minX");
        plugin.maxX = config.getInt("canvas.maxX");
        plugin.minZ = config.getInt("canvas.minZ");
        plugin.maxZ = config.getInt("canvas.maxZ");
        plugin.canvasY = config.getInt("canvas.y");
        plugin.isCanvasSet = config.getBoolean("canvas.isSet", false);

        plugin.defaultMaxPoints = config.getInt("settings.defaultMaxPoints", 1);
        plugin.defaultRecoverSpeed = config.getInt("settings.defaultRecoverSpeed", 60);
        plugin.placeReward = config.getDouble("settings.placeReward", 1.0);

        if (config.getConfigurationSection("players.data") != null) {
            for (String key : config.getConfigurationSection("players.data").getKeys(false)) {
                try {
                    UUID uuid = UUID.fromString(key);
                    String path = "players.data." + key;

                    plugin.maxPointsMap.put(uuid, config.getInt(path + ".max"));
                    plugin.recoverSpeedMap.put(uuid, config.getInt(path + ".speed"));

                    if (config.contains(path + ".adminMode")) {
                        String modeStr = config.getString(path + ".adminMode");
                        plugin.adminPlayers.put(uuid, RPlace.AdminMode.valueOf(modeStr));
                    }
                } catch (Exception e) {
                    plugin.getLogger().warning("解析玩家数据失败: " + key);
                }
            }
        }
    }
}
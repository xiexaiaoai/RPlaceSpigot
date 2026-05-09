package me.xiaoai.rplace;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.HashMap;
import java.util.Objects;
import java.util.UUID;

/* 第一部分：RPlace 插件主类

如果 插件被服务器加载
则 初始化包含能量点数、恢复时间、玩家特权及上帝模式状态的通过设置永久开启，如果你想让这种星号，注释自动变成好看的排版，去这里，点击左上角的齿轮，设置内存映射表
并 定义画布边界坐标、默认恢复速度及全局奖励系数等核心变量。
*/
public class RPlace extends JavaPlugin {
    private static RPlace instance;
    public final HashMap<UUID, Integer> currentPoints = new HashMap<>();
    public final HashMap<UUID, Long> lastRecoverTime = new HashMap<>();
    public final HashMap<UUID, Integer> maxPointsMap = new HashMap<>();
    public final HashMap<UUID, Integer> recoverSpeedMap = new HashMap<>();
    public final HashMap<UUID, AdminMode> adminPlayers = new HashMap<>();
    public final HashMap<UUID, Integer> placedCountMap = new HashMap<>();
    public final HashMap<UUID, String> playerMessages = new HashMap<>();

    public int defaultMaxPoints = 1;
    public int defaultRecoverSpeed = 60;
    public double placeReward = 1.0;


    public String canvasWorld;
    public int minX, maxX, minZ, maxZ;
    public int canvasY;
    public boolean isCanvasSet = false;

    private DataManager dataManager;

    public enum AdminMode { OFF, BASIC, GOD }
    public ScoreboardManager scoreboardManager;


    public static RPlace getInstance() {
        return instance;
    }

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        this.dataManager = new DataManager(this);

        // 在 dataManager 加载之后添加
        dataManager.loadConfigData();
        dataManager.loadRankData();
        this.scoreboardManager = new ScoreboardManager(this); // 初始化记分板系统

        me.xiaoai.economy.AccountCenterManager.init();

        RPlaceCommand cmdHandler = new RPlaceCommand(this);
        getCommand("rp").setExecutor(cmdHandler);
        getCommand("rp").setTabCompleter(cmdHandler);
        getServer().getPluginManager().registerEvents(new RPlaceListener(this), this);

        getServer().getPluginManager().registerEvents(new me.xiaoai.economy.MenuStickinessHandler(), this);

        // 3. 初始化经济子系统
        me.xiaoai.economy.EconomyCore economy = new me.xiaoai.economy.EconomyCore(this);
        economy.init();

        // 4. 控制台日志
        getLogger().info("§a========================================");
        getLogger().info("§a  RPlace 核心系统 (Canvas) 已加载");
        getLogger().info("§a  MenuStickiness 菜单绑定系统 已就绪");
        getLogger().info("§a========================================");
    }

    /* 第三部分：插件禁用逻辑

    如果 插件执行关闭流程
    则 检查数据管理器是否有效：
    若有效，则 强制执行一次数据持久化保存。
    */
    @Override
    public void onDisable() {
        if (dataManager != null) {
            // 确保服务器正常关服、崩溃自动重启或 /reload 时，内存中所有玩家的实时能量和时间戳能立即写回硬盘。
            dataManager.savePlayerData();
            dataManager.saveRankData();
            dataManager.saveConfigData();
        }
    }


    /* 第四部分：冷却时间计算

    如果 调用该方法获取玩家冷却
    则 计算当前系统时间与上次恢复时间的时间差：
    若 时间差未达到恢复步长，则 返回剩余所需的秒数
    若 已达到步长，则 返回 0。
    */
    public long getCooldown(Player p) {
        UUID uuid = p.getUniqueId();
        long now = System.currentTimeMillis();

        // --- 关键修正：确保 speed 不为 0 ---
        int speed = recoverSpeedMap.getOrDefault(uuid, defaultRecoverSpeed);
        if (speed <= 0) speed = 1;

        long last = lastRecoverTime.getOrDefault(uuid, now);
        long secondsSinceLast = (now - last) / 1000;

        return Math.max(0, speed - (secondsSinceLast % speed));

    }


    /* 第五部分：能量点数校验与消耗逻辑

    如果 玩家处于 GOD 模式
    则 直接跳过校验返回成功

    如果 玩家为普通模式
    则 根据时间差计算已恢复的点数：
    若 时间差超过速度间隔且能量未满，则 增加能量并校准上次恢复时间戳
    若 能量已处于满载状态，则 重置恢复基准时间至当前

    如果 计算后的当前能量大于 0
    则 扣除 1 点能量，并根据是否从满能量状态扣除来决定是否重置恢复计时
    最后 返回 true 允许操作，否则返回 false 拦截操作。
    */
    // 核心逻辑：获取玩家当前的实时体力（包含自动恢复计算）
    public int getRealTimePoints(Player p) {
        UUID uuid = p.getUniqueId();
        int max = maxPointsMap.getOrDefault(uuid, defaultMaxPoints);
        int speed = recoverSpeedMap.getOrDefault(uuid, defaultRecoverSpeed);
        int current = currentPoints.getOrDefault(uuid, max);
        long last = lastRecoverTime.getOrDefault(uuid, System.currentTimeMillis());

        long now = System.currentTimeMillis();
        long secondsSinceLast = (now - last) / 1000;

        if (secondsSinceLast >= speed && current < max) {
            int recovered = (int) (secondsSinceLast / speed);
            int newPoints = Math.min(max, current + recovered);
            // 注意：这里只是查询，不直接写入 Map，保证 UI 刷新的安全性
            return newPoints;
        }
        return current;
    }

    // 修改你刚才发的消耗逻辑，使其调用上面的逻辑
    public boolean checkAndConsumePoint(Player p) {
        UUID uuid = p.getUniqueId();
        if (adminPlayers.getOrDefault(uuid, AdminMode.OFF) == AdminMode.GOD) return true;

        // 1. 先计算并获取当前的真实体力
        int max = maxPointsMap.getOrDefault(uuid, defaultMaxPoints);
        int speed = recoverSpeedMap.getOrDefault(uuid, defaultRecoverSpeed);
        int current = getRealTimePoints(p);

        // 2. 更新最后恢复时间点 (对齐刻度)
        long now = System.currentTimeMillis();
        long last = lastRecoverTime.getOrDefault(uuid, now);
        long secondsSinceLast = (now - last) / 1000;

        if (secondsSinceLast >= speed && current < max) {
            lastRecoverTime.put(uuid, now - (secondsSinceLast % speed) * 1000);
        } else if (current >= max) {
            lastRecoverTime.put(uuid, now);
        }

        // 3. 执行消耗并保存结果
        if (current > 0) {
            currentPoints.put(uuid, current - 1);
            if (current == max) lastRecoverTime.put(uuid, now);

            // --- 新增：瞬时反馈刷新 ---
            if (scoreboardManager != null && scoreboardManager.enabledPlayers.contains(uuid)) {
                scoreboardManager.updateScoreboard(p);
            }
            // -----------------------

            return true;
        }
        return false;
    }

    /* 第六部分：画布区域 XZ 坐标校验

    如果 画布已设置且世界名称匹配
    则 检查传入坐标的 X 与 Z 是否均落在 min 和 max 的闭区间内：
    若 全部落在区间内，则 返回 true
    否则 返回 false。
    */
    public boolean isInCanvasXZ(Location l) {
        return isCanvasSet && Objects.requireNonNull(l.getWorld()).getName().equals(canvasWorld) &&
                l.getBlockX() >= minX && l.getBlockX() <= maxX &&
                l.getBlockZ() >= minZ && l.getBlockZ() <= maxZ;
    }

    /* 第七部分：保护区范围校验（画布外围 100 格）

    如果 画布未设置或世界不匹配
    则 返回 false

    如果 基础校验通过
    则 计算坐标点距离画布 X 轴边界与 Z 轴边界的距离：
    若 坐标在画布外且距离两轴边界均在 100 格以内，则 返回 true
    否则 返回 false。
    */
    public boolean isInsideProtection(Location l) {
        if (!isCanvasSet || !Objects.requireNonNull(l.getWorld()).getName().equals(canvasWorld)) return false;
        double dx = getDist(l.getX(), minX, maxX);
        double dz = getDist(l.getZ(), minZ, maxZ);
        return (dx > 0 || dz > 0) && dx <= 100 && dz <= 100;
    }

    /* 第八部分：区间距离计算工具

    如果 数值小于最小值，则 返回与最小值的差值
    如果 数值大于最大值，则 返回与最大值的差值
    否则 返回 0 表示在区间内。
    */
    public double getDist(double val, double min, double max) {
        if (val < min) return min - val;
        if (val > max) return val - max;
        return 0;
    }


    /* 第九部分：数据管理器获取接口

    如果 调用该方法
    则 返回当前持有的 DataManager 实例。
    */
    public DataManager getDataManager() { return dataManager; }


}

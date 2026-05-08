package me.xiaoai.economy;

import me.xiaoai.economy.gui.EconomyMainMenu;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * 经济系统监听器：处理商店交互、导航、核心交易及进化系统逻辑
 */
public class EconomyListener implements Listener {

    private final HashMap<UUID, Integer> pageMap = new HashMap<>();   // 追踪玩家当前所在页码
    private final HashMap<UUID, Integer> rowMap = new HashMap<>();    // 追踪玩家当前商店维度/类型 ID
    private final HashMap<UUID, Integer> resetClickMap = new HashMap<>();
    private final HashMap<UUID, Long> resetLastTimeMap = new HashMap<>();

    /**
     * 维度钟交互：右键识别 Lore 中的 STORE_ID 并初始化对应的商店 GUI
     */
    @EventHandler(priority = org.bukkit.event.EventPriority.HIGHEST)
    public void onPlayerInteract(org.bukkit.event.player.PlayerInteractEvent event) {
        if (!event.getAction().name().contains("RIGHT")) return;

        ItemStack item = event.getItem();
        if (item == null || !item.hasItemMeta() || !item.getItemMeta().hasLore()) return;

        List<String> lore = item.getItemMeta().getLore();
        for (String line : lore) {
            if (line.contains("STORE_ID:")) {
                event.setCancelled(true);
                try {
                    // 剥离颜色代码提取纯文本 ID，确保跨版本解析稳定
                    String plainLine = org.bukkit.ChatColor.stripColor(line);
                    String idStr = plainLine.split("STORE_ID:")[1].trim();
                    int shopId = Integer.parseInt(idStr);
                    Player player = event.getPlayer();

                    pageMap.put(player.getUniqueId(), 0);
                    rowMap.put(player.getUniqueId(), shopId);

                    new EconomyMainMenu().open(player, 0, shopId);
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_XYLOPHONE, 1.0f, 1.2f);
                    return;
                } catch (Exception e) {
                    // 容错：当前行格式不符则尝试下一行 Lore
                }
            }
        }
    }

    /**
     * GUI 点击调度：拦截带 §0 标识的自定义界面，分发至导航、进化或交易逻辑
     */
    @EventHandler
    public void onGuiClick(org.bukkit.event.inventory.InventoryClickEvent event) {
        String title = event.getView().getTitle();
        if (title.equals("§0请放入要佩戴的物品")) return;
        if (!title.contains("§0")) return;
        event.setCancelled(true); // 锁定界面，防止物品被玩家取走

        int slot = event.getRawSlot();
        if (slot < 0 || slot >= 54) return;

        Player player = (Player) event.getWhoClicked();
        UUID uuid = player.getUniqueId();
        EconomyMainMenu menu = new EconomyMainMenu();
        int curRow = rowMap.getOrDefault(uuid, 0);

        // 导航栏功能区块 (Slot 17, 26, 35, 44)
        if (slot == 17 || slot == 26 || slot == 35 || slot == 44) {
            if (slot == 44) { // 特殊跳转：进化中心 (Row 99)
                rowMap.put(uuid, 99);
                pageMap.put(uuid, 0);
                menu.open(player, 0, 99);
                player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1, 1.2f);
            } else {
                handleNavigationClick(event, player, uuid, slot, curRow, getHeldDimensionId(player), menu);
            }
            return;
        }

        // 逻辑分流
        if (curRow == 99) {
            handleEvolutionUpgradeLogic(player, slot, uuid, menu);
        } else if (curRow == 1) {
            // 账户中心点击处理
            if (slot == 10 || slot == 13 || slot == 16 || slot == 30 || slot == 32) {
                AccountCenter.handleClick(player, slot);
            }
        } else if (slot % 9 < 7 && event.getCurrentItem() != null) {
            // 核心交易区 (排除侧边栏)
            processTrade(player, uuid, slot, pageMap.getOrDefault(uuid, 0), curRow, event.getClick(), menu);
        }
    }

    /**
     * 进化系统逻辑：处理 stamina_level(体力) 与 recovery_level(恢复) 的扣费升级及数据持久化
     */
    private void handleEvolutionUpgradeLogic(org.bukkit.entity.Player player, int slot, java.util.UUID uuid, me.xiaoai.economy.gui.EconomyMainMenu menu) {
        me.xiaoai.economy.EconomyCore core = me.xiaoai.economy.EconomyCore.getInstance();
        org.bukkit.configuration.file.FileConfiguration evoConfig = core.getEvolutionConfig();

        // 49号位：带有连点确认机制的重置逻辑
        if (slot == 49) {
            int count = pageMap.getOrDefault(uuid, 0) + 1;
            long resetCost = evoConfig.getLong("reset_settings.cost", 5000);
            int required = evoConfig.getInt("reset_settings.required_clicks", 10);

            if (count < required) {
                pageMap.put(uuid, count);
                player.sendMessage("§c§l重置 >> §f确认要重置所有进化？需再点击 §e" + (required - count) + " §f次");
                player.playSound(player.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_HAT, 1.0f, 0.5f + (count * 0.1f));
            } else {
                pageMap.put(uuid, 0);
                if (core.withdrawPlayer(uuid, (double)resetCost)) {
                    evoConfig.set("player_data." + uuid + ".stamina_level", 1);
                    evoConfig.set("player_data." + uuid + ".recovery_level", 1);
                    core.saveEvolutionConfig();

                    // 【API联动】重置为初始实权数值
                    me.xiaoai.rplace.RPlace rp = me.xiaoai.rplace.RPlace.getInstance();
                    rp.maxPointsMap.put(uuid, 1);
                    rp.recoverSpeedMap.put(uuid, 60);
                    saveToRPlaceData(rp, uuid, 1, 60);

                    player.sendMessage("§a§l重置 >> §f所有属性已归零。");
                    player.playSound(player.getLocation(), org.bukkit.Sound.UI_STONECUTTER_TAKE_RESULT, 1.0f, 1.0f);
                    menu.open(player, 0, 99);
                } else {
                    player.sendMessage("§c§l重置 >> §7金币不足 " + resetCost + " G");
                }
            }
            return;
        }

        // 29(体力)/33(恢复)：执行扣费并调用 API 赋予实权
        if (slot == 29 || slot == 33) {
            pageMap.put(uuid, 0);

            String typeKey = (slot == 29) ? "stamina_level" : "recovery_level";
            String configSection = (slot == 29) ? "max_points" : "recover_speed";

            int currentLv = evoConfig.getInt("player_data." + uuid + "." + typeKey, 1);
            int nextLv = currentLv + 1;
            long cost = evoConfig.getLong(configSection + ".costs." + nextLv, -1);

            if (cost <= 0) {
                player.sendMessage("§e§l进化 >> §f该属性已达最高等级");
                return;
            }

            if (core.withdrawPlayer(uuid, (double)cost)) {
                evoConfig.set("player_data." + uuid + ".name", player.getName());
                evoConfig.set("player_data." + uuid + "." + typeKey, nextLv);
                core.saveEvolutionConfig();

                // 【API 联动核心：计算实权数值并应用】
                me.xiaoai.rplace.RPlace rp = me.xiaoai.rplace.RPlace.getInstance();
                int newValue = calculateEvolutionValue(configSection, nextLv, evoConfig);

                if (slot == 29) {
                    rp.maxPointsMap.put(uuid, newValue); // 赋予体力上限实权
                    rp.getDataManager().getData().set("players.data." + uuid + ".max", newValue);
                } else {
                    rp.recoverSpeedMap.put(uuid, newValue); // 赋予恢复速度实权
                    rp.getDataManager().getData().set("players.data." + uuid + ".speed", newValue);
                }

                // 强制保存 RPlace 的数据文件
                try {
                    rp.getDataManager().getData().save(new java.io.File(rp.getDataFolder(), "data.yml"));
                } catch (java.io.IOException e) { e.printStackTrace(); }

                player.sendMessage("§a§l进化 >> §f升级成功！当前等级: §eLv." + nextLv + " §f(实权数值: §b" + newValue + "§f)");
                player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.2f);
                menu.open(player, 0, 99);
            } else {
                player.sendMessage("§c§l进化 >> §7金币不足，还差: §e" + (cost - core.getBalance(uuid)) + " G");
            }
        }
    }

    /**
     * 实权数值计算：体力累加，速度递减
     */
    private int calculateEvolutionValue(String type, int level, org.bukkit.configuration.file.FileConfiguration config) {
        int base = config.getInt(type + ".base_value", 1);
        if (type.equals("max_points")) {
            int total = base;
            org.bukkit.configuration.ConfigurationSection bonuses = config.getConfigurationSection(type + ".bonuses");
            if (bonuses != null) {
                for (int i = 2; i <= level; i++) {
                    total += bonuses.getInt(String.valueOf(i), 0);
                }
            }
            return total;
        } else {
            // 恢复速度逻辑：基础时间 - (等级-1) * 缩减值
            int reduction = config.getInt(type + ".reduction_per_level", 4);
            return Math.max(1, base - ((level - 1) * reduction));
        }
    }

    /**
     * 辅助方法：快速同步数据到 RPlace 文件
     */
    private void saveToRPlaceData(me.xiaoai.rplace.RPlace rp, java.util.UUID uuid, int max, int speed) {
        rp.getDataManager().getData().set("players.data." + uuid + ".max", max);
        rp.getDataManager().getData().set("players.data." + uuid + ".speed", speed);
        try {
            rp.getDataManager().getData().save(new java.io.File(rp.getDataFolder(), "data.yml"));
        } catch (java.io.IOException e) { e.printStackTrace(); }
    }

    private void sendBalanceLowMessage(org.bukkit.entity.Player p, long cost) {
        p.sendMessage("§c§l进化 >> §7升级所需资金不足！(需要 §e" + String.format("%,d", cost) + " §7金币)");
        p.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_VILLAGER_NO, 1, 1);
    }

    /**
     * 维度识别：解析主手 CLOCK 的 Lore，通过 "STORE_ID:" 提取绑定的维度 ID (-1 为无效)
     */
    private int getHeldDimensionId(Player player) {
        ItemStack handItem = player.getInventory().getItemInMainHand();
        if (handItem != null && handItem.getType() == Material.CLOCK && handItem.hasItemMeta()) {
            List<String> lore = handItem.getItemMeta().getLore();
            if (lore != null) {
                for (String line : lore) {
                    String plainLore = org.bukkit.ChatColor.stripColor(line);
                    if (plainLore.contains("STORE_ID:")) {
                        try {
                            return Integer.parseInt(plainLore.split(":")[1].trim());
                        } catch (Exception ignored) {}
                    }
                }
            }
        }
        return -1;
    }

    /**
     * 侧边栏导航：处理 Slot 17(主页/维度)、26(账户)、35(随机池) 的跳转与锚点(ANCHOR_DIM)逻辑
     */
    private void handleNavigationClick(org.bukkit.event.inventory.InventoryClickEvent event, Player player, UUID uuid, int slot, int curRow, int heldDimId, EconomyMainMenu menu) {
        int nextRow = 0;
        ItemStack clicked = event.getCurrentItem();
        int iconDimId = 0;
        int anchorDimId = 0;

        // 提取图标携带的目标 ID 与返回锚点
        if (clicked != null && clicked.hasItemMeta() && clicked.getItemMeta().getLore() != null) {
            for (String line : clicked.getItemMeta().getLore()) {
                String plain = org.bukkit.ChatColor.stripColor(line);
                if (plain.contains("STORE_ID:")) iconDimId = Integer.parseInt(plain.split(":")[1].trim());
                if (plain.contains("ANCHOR_DIM:")) anchorDimId = Integer.parseInt(plain.split(":")[1].trim());
            }
        }

        if (slot == 17) {
            if (heldDimId != -1) nextRow = heldDimId;       // 优先跳转手持钟维度
            else if (curRow == 1) nextRow = anchorDimId;    // 从账户页返回时使用锚点
            else nextRow = iconDimId;
        } else if (slot == 26) {
            nextRow = 1; // 账户中心
        } else if (slot == 35) {
            nextRow = (heldDimId != -1) ? (heldDimId + 10000) : iconDimId; // 随机池标识：维度ID + 10000
        }

        pageMap.put(uuid, 0);
        rowMap.put(uuid, nextRow);
        menu.open(player, 0, nextRow);
    }

    /**
     * 商店翻页：左键向前，右键向后，仅限 Row 0 或 维度商店生效
     */
    private void handlePagination(org.bukkit.event.inventory.InventoryClickEvent event, Player player, UUID uuid, int curPage, int curRow, EconomyMainMenu menu) {
        if (curRow == 0 || (curRow > 2 && curRow < 10000)) {
            int nextPage = (event.getClick() == org.bukkit.event.inventory.ClickType.LEFT) ? Math.max(0, curPage - 1) : curPage + 1;
            pageMap.put(uuid, nextPage);
            menu.open(player, nextPage, curRow);
        }
    }

    /**
     * 交易核心：处理买入(正价)/卖出(负价)、个人限购检测及 Config 数据同步
     */
    private void processTrade(Player player, UUID uuid, int slot, int curPage, int curRow, org.bukkit.event.inventory.ClickType click, EconomyMainMenu menu) {
        EconomyCore core = EconomyCore.getInstance();
        String path;

        // 映射配置路径：市场(0)、随机(2)、维度随机(>=10000)、维度商店(>2)
        if (curRow == 0) path = "market.items";
        else if (curRow == 2) path = "random_shop.pool";
        else if (curRow >= 10000) path = "market_dimension_" + (curRow - 10000) + ".random_pool";
        else if (curRow > 2) path = "market_dimension_" + curRow + ".items";
        else return;

        String itemKey = getItemKeyBySlot(path, curPage, slot);
        if (itemKey == null) return;

        ConfigurationSection section = core.getConfig().getConfigurationSection(path + "." + itemKey);
        if (section == null) return;

        double unitPrice = section.getDouble("price");
        int maxLimit = section.getInt("stock", -1);
        ItemStack shopItem = section.getItemStack("item");
        if (shopItem == null) return;

        // 随机商店强制单买，普通商店支持右键 64 堆叠买卖
        boolean isRandom = (curRow == 2 || curRow >= 10000);
        int amount = isRandom ? 1 : ((click == org.bukkit.event.inventory.ClickType.RIGHT) ? 64 : 1);
        String dataPath = "player_data." + uuid;
        int alreadyBought = core.getConfig().getInt(dataPath + ".history." + itemKey, 0);

        // 个人限购拦截
        if (maxLimit != -1 && (alreadyBought + amount) > maxLimit) {
            player.sendMessage("§c§l[!] §c购买失败：已达到个人限额。");
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
            return;
        }

        boolean transactionOccurred = false;
        if (unitPrice < 0) { // 出售给系统 (价格为负)
            double income = Math.abs(unitPrice) * amount;
            if (player.getInventory().containsAtLeast(shopItem, amount)) {
                ItemStack toRemove = shopItem.clone(); toRemove.setAmount(amount);
                player.getInventory().removeItem(toRemove);
                core.addBalance(uuid, income);
                transactionOccurred = true;
                player.sendMessage("§a§l[✔] §f出售成功！收益: §e" + income);
            } else player.sendMessage("§c§l[!] §c物品不足！");
        } else { // 从系统购买 (价格为正)
            double total = unitPrice * amount;
            if (core.getBalance(uuid) >= total && core.takeBalance(uuid, total)) {
                ItemStack toGive = shopItem.clone(); toGive.setAmount(amount);
                player.getInventory().addItem(toGive);
                transactionOccurred = true;
                player.sendMessage("§a§l[✔] §a购买成功！支出: §e" + total);
            } else player.sendMessage("§c§l[!] §c余额不足！还差: §e" + (total - core.getBalance(uuid)));
        }

        // 成功后持久化数据并刷新界面
        if (transactionOccurred) {
            core.getConfig().set(dataPath + ".name", player.getName());
            core.getConfig().set(dataPath + ".history." + itemKey, alreadyBought + amount);
            core.saveCustomConfig();
            player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
            menu.open(player, curPage, curRow);
        }
    }

    /**
     * 自动翻新：比较上次刷新时间与间隔(如 1h)，重置随机池并清除全服限购记录(player_data)
     */
    private void checkAndRefreshShop() {
        EconomyCore core = EconomyCore.getInstance();
        long lastRefresh = core.getConfig().getLong("shop_settings.last_refresh", 0);
        long currentTime = System.currentTimeMillis();
        String intervalStr = core.getConfig().getString("shop_settings.refresh_interval", "1h");
        long intervalMillis = parseIntervalToMillis(intervalStr);

        if (currentTime - lastRefresh >= intervalMillis) {
            refreshRandomShopPool(core);
            core.getConfig().set("player_data", null); // 翻新即清空限购流水
            core.getConfig().set("shop_settings.last_refresh", currentTime);
            core.saveCustomConfig();
            org.bukkit.Bukkit.broadcastMessage("§a§l[经济系统] §f商店货架已翻新！");
        }
    }

    /**
     * 时间解析：支持 s/m/h/d 单位，解析失败默认返回 3600000ms (1h)
     */
    private long parseIntervalToMillis(String interval) {
        try {
            String unit = interval.substring(interval.length() - 1).toLowerCase();
            long value = Long.parseLong(interval.substring(0, interval.length() - 1));
            switch (unit) {
                case "s": return value * 1000L;
                case "m": return value * 60 * 1000L;
                case "h": return value * 3600 * 1000L;
                case "d": return value * 24 * 3600 * 1000L;
                default: return 3600000L;
            }
        } catch (Exception e) { return 3600000L; }
    }

    /**
     * 池刷新逻辑：从 random_pool_source 节点乱序抽取 N 个(3-7)商品写入 random_shop.pool
     */
    private void refreshRandomShopPool(EconomyCore core) {
        ConfigurationSection source = core.getConfig().getConfigurationSection("random_pool_source");
        if (source == null) return;
        List<String> allKeys = new ArrayList<>(source.getKeys(false));
        if (allKeys.isEmpty()) return;

        java.util.Collections.shuffle(allKeys); // 乱序算法实现随机上架
        int min = core.getConfig().getInt("random_shop.min_items", 3);
        int max = core.getConfig().getInt("random_shop.max_items", 7);
        int amountToPick = (int) (Math.random() * (max - min + 1)) + min;

        core.getConfig().set("random_shop.pool", null);
        for (int i = 0; i < amountToPick && i < allKeys.size(); i++) {
            String key = allKeys.get(i);
            core.getConfig().set("random_shop.pool." + key, source.get(key));
        }
    }

    /**
     * 坐标转换：将 Slot (0-53) 映射至配置索引，自动计算页边距偏移并过滤侧边导航栏
     */
    private String getItemKeyBySlot(String path, int page, int slot) {
        ConfigurationSection section = EconomyCore.getInstance().getConfig().getConfigurationSection(path);
        if (section == null) return null;
        List<String> keys = new ArrayList<>(section.getKeys(false));

        int row = slot / 9, col = slot % 9;
        int clickedIndex = (row * 7) + col; // 映射 7 列显示区
        int finalIndex = (path.contains("market")) ? (page * 42) + clickedIndex : clickedIndex;

        return (finalIndex >= 0 && finalIndex < keys.size()) ? keys.get(finalIndex) : null;
    }

    /**
     * 穿戴处理：在 GUI 关闭时将 Slot 4 物品设置到玩家头盔栏，并归还原有头盔
     */
    @EventHandler
    public void onHeadGearClose(org.bukkit.event.inventory.InventoryCloseEvent event) {
        if (!event.getView().getTitle().equals("§0请放入要佩戴的物品")) return;

        Player player = (Player) event.getPlayer();
        ItemStack itemToWear = event.getInventory().getItem(4);

        if (itemToWear == null || itemToWear.getType() == Material.AIR) return;

        try {
            ItemStack oldHelmet = player.getInventory().getHelmet();
            player.getInventory().setHelmet(itemToWear.clone());
            event.getInventory().setItem(4, null); // 彻底移除 GUI 物品防止并发导致掉落/刷物

            if (oldHelmet != null && oldHelmet.getType() != Material.AIR) {
                player.getInventory().addItem(oldHelmet);
            }
            player.sendMessage("§a§lRPlace >> §f穿戴成功！");
        } catch (Exception e) {}
    }

    /**
     * 穿戴点击拦截：禁止在穿戴界面点击除 Slot 4 (投放区) 以外的 GUI 槽位
     */
    @EventHandler
    public void onHeadGearClick(org.bukkit.event.inventory.InventoryClickEvent event) {
        if (!event.getView().getTitle().equals("§0请放入要佩戴的物品")) return;
        int slot = event.getRawSlot();
        if (slot >= 0 && slot < 9 && slot != 4) event.setCancelled(true);
    }
}
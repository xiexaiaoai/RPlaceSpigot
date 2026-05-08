package me.xiaoai.economy.gui;

import me.xiaoai.economy.EconomyCore;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.io.File;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * 核心 GUI 逻辑：经济管理中心
 * 负责处理商店界面分发、多维度商店渲染、随机商店逻辑以及个人资产显示。
 * 包含动态余额注入系统，可根据玩家实时资产改变商品描述颜色。
 */
public class EconomyMainMenu {

    /**
     * 界面入口
     * row 参数规则：0=全球市场, 1=个人账户, 2=主世界随机店, >10000=维度随机店, 其他=特定维度店。
     */
    /**
     * 界面入口
     * row 参数规则：0=全球市场, 1=个人账户, 2=主世界随机店, 99=属性进化中心, >10000=维度随机店。
     */
    public void open(Player player, int page, int row) {
        String title = "§0经济管理中心";
        if (row == 1) {
            title = "§0个人账户中心";
        } else if (row == 99) {
            title = "§0属性进化中心";
        } else if (row >= 10000) {
            title = "§0维度随机商店 - " + (row - 10000);
        } else if (row > 2) {
            title = "§0维度商店 - " + row;
        }

        Inventory gui = Bukkit.createInventory(null, 54, title);
        drawNavigation(gui, player, page, row);

        me.xiaoai.economy.EconomyCore core = me.xiaoai.economy.EconomyCore.getInstance();

        if (row == 0) {
            renderMarket(gui, player, page);
        } else if (row == 1) {
            me.xiaoai.economy.AccountCenter.inject(gui, player);
        } else if (row == 99) {
            java.util.UUID uuid = player.getUniqueId();
            double balance = core.getBalance(uuid);
            FileConfiguration evoConfig = core.getEvolutionConfig();

            // --- 核心修复：从 evolution_settings.yml 中读取玩家独立的等级数据 ---
            // 注意：这里改为从 evoConfig 读取，保持与你思路中的 player_data 节点一致
            int maxLv = evoConfig.getInt("player_data." + uuid + ".stamina_level", 1);
            int speedLv = evoConfig.getInt("player_data." + uuid + ".recovery_level", 1);

            // 读取配置基础值
            int baseMax = evoConfig.getInt("max_points.base_value", 1);
            int baseSpeed = evoConfig.getInt("recover_speed.base_value", 60);
            int reduction = evoConfig.getInt("recover_speed.reduction_per_level", 4);

            // 计算当前数值
            int currentMaxPoints = baseMax;
            for (int i = 2; i <= maxLv; i++) {
                currentMaxPoints += evoConfig.getInt("max_points.bonuses." + i, 0);
            }
            int currentRecoverSpeed = baseSpeed - (speedLv - 1) * reduction;

            // 13号位：信息摘要
            gui.setItem(13, createItem(Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE, "§f§l系统属性摘要",
                    "§7当前体力上限: §f" + currentMaxPoints + " §8(Lv." + maxLv + ")",
                    "§7当前恢复周期: §f" + currentRecoverSpeed + "s §8(Lv." + speedLv + ")",
                    "", "§e§n点击下方图标执行独立升级"));

            // 29号位：体力上限强化
            int nextMaxLv = maxLv + 1;
            long maxCost = evoConfig.getLong("max_points.costs." + nextMaxLv, -1);
            if (maxCost > 0) {
                int bonus = evoConfig.getInt("max_points.bonuses." + nextMaxLv, 0);
                String status = (balance >= maxCost) ? "§a余额充足" : "§c余额不足";
                gui.setItem(29, createItem(Material.CHEST, "§b§l[1] 升级：体力上限",
                        "§7当前等级: §fLv." + maxLv,
                        "§7下一阶段: §bLv." + nextMaxLv + " (§f+" + bonus + "§b)",
                        "§f消耗金额: §e" + maxCost + " G",
                        "§f资金状态: " + status,
                        "", "§b[ 点击独立升级体力 ]"));
            } else {
                gui.setItem(29, createItem(Material.ENDER_CHEST, "§a§l体力上限 (MAX)", "§7当前等级: §fLv." + maxLv, "§7[ 已达物理极限 ]"));
            }

            // 33号位：恢复速率强化
            int nextSpeedLv = speedLv + 1;
            long speedCost = evoConfig.getLong("recover_speed.costs." + nextSpeedLv, -1);
            if (speedCost > 0) {
                int nextSpeedVal = baseSpeed - (nextSpeedLv - 1) * reduction;
                String status = (balance >= speedCost) ? "§a余额充足" : "§c余额不足";
                gui.setItem(33, createItem(Material.GLOWSTONE_DUST, "§d§l[2] 升级：恢复速率",
                        "§7当前等级: §fLv." + speedLv,
                        "§7下一阶段: §dLv." + nextSpeedLv + " (§f" + nextSpeedVal + "s§d)",
                        "§f消耗金额: §e" + speedCost + " G",
                        "§f资金状态: " + status,
                        "", "§d[ 点击独立升级速率 ]"));
            } else {
                gui.setItem(33, createItem(Material.BEACON, "§a§l恢复速率 (MAX)", "§7当前等级: §fLv." + speedLv, "§7[ 已达物理极限 ]"));
            }

            // 49号位：重置按钮
            long resetCost = evoConfig.getLong("reset_settings.cost", 5000);
            int reqClicks = evoConfig.getInt("reset_settings.required_clicks", 10);
            gui.setItem(49, createItem(Material.BARRIER, "§c§l危险操作：属性重置",
                    "§7将进化属性回退至 Lv.1",
                    "§7重置费用: §e" + resetCost + " G",
                    "",
                    "§4§l需连续点击 " + reqClicks + " 次执行重置",
                    "§7(不退还已消耗的金币)"));

        } else if (row == 2 || row >= 10000) {
            renderRandomShop(gui, player, row);
        } else {
            renderDimensionMarket(gui, player, page, row);
        }

        player.openInventory(gui);
    }

    // --- 数值计算辅助方法 (阶梯式增长，总价约 800w) ---

    /**
     * 体力上限升级费：前期便宜，后期稳步增加
     * 总计约 340w
     */
    private long getMaxUpgradeCost(int nextLv) {
        switch (nextLv) {
            case 2: return 5000;
            case 3: return 15000;
            case 4: return 40000;
            case 5: return 90000;
            case 6: return 180000;
            case 7: return 350000;
            case 8: return 600000;
            case 9: return 900000;
            case 10: return 1200000;
            default: return 0;
        }
    }

    /**
     * 恢复速率升级费：比体力上限更贵，后期涨幅更夸张
     * 总计约 460w
     */
    private long getSpeedUpgradeCost(int nextLv) {
        switch (nextLv) {
            case 2: return 8000;
            case 3: return 25000;
            case 4: return 60000;
            case 5: return 150000;
            case 6: return 300000;
            case 7: return 550000;
            case 8: return 900000;
            case 9: return 1300000;
            case 10: return 1800000;
            default: return 0;
        }
    }

    /**
     * 实时余额注入逻辑
     * 根据玩家当前余额与商品单价对比，自动切换 §a(足够) 或 §c(不足) 状态，并计算差额提示。
     */
    private void injectBalanceLore(ItemMeta meta, Player player, double price) {
        if (meta == null) return;
        double balance = EconomyCore.getInstance().getBalance(player.getUniqueId());
        List<String> lore = meta.hasLore() ? meta.getLore() : new ArrayList<>();

        lore.add("");
        String color = (balance >= price) ? "§a" : "§c";
        lore.add("§f账户余额: " + color + balance + " §6金币");

        if (balance < price) {
            lore.add("§c[!] 还差 " + String.format("%.1f", (price - balance)) + " 金币");
        }

        meta.setLore(lore);
    }

    /**
     * 维度商店渲染
     * 从 market_dimension_{id}.items 路径读取配置，支持分页显示与个人限购扣除计算。
     */
    private void renderDimensionMarket(Inventory gui, Player player, int page, int row) {
        clearContent(gui);
        String path = "market_dimension_" + row + ".items";
        ConfigurationSection items = EconomyCore.getInstance().getConfig().getConfigurationSection(path);
        if (items == null) return;

        List<String> keys = new ArrayList<>(items.getKeys(false));
        int startIndex = page * 42;

        for (int i = 0; i < 42 && (startIndex + i) < keys.size(); i++) {
            String key = keys.get(startIndex + i);
            ItemStack item = items.getItemStack(key + ".item");
            if (item == null) continue;

            double price = items.getDouble(key + ".price");
            int maxLimit = items.getInt(key + ".stock", -1);
            int bought = EconomyCore.getInstance().getConfig().getInt("player_data." + player.getUniqueId() + ".history." + key, 0);

            ItemStack displayItem = item.clone();
            ItemMeta meta = displayItem.getItemMeta();
            if (meta != null) {
                List<String> lore = new ArrayList<>();
                lore.add("§7商品单价: §a" + price + " 金币");
                lore.add("§7个人限额: " + (maxLimit == -1 ? "§b无限制" : "§f" + (maxLimit - bought) + " / " + maxLimit));
                meta.setLore(lore);
                injectBalanceLore(meta, player, price);
                displayItem.setItemMeta(meta);
            }
            gui.setItem((i / 7) * 9 + (i % 7), displayItem);
        }
    }

    /**
     * 全球市场渲染
     * 基础商店逻辑，渲染 market.items 下的所有公开流通商品。
     */
    public void renderMarket(Inventory gui, Player player, int page) {
        clearContent(gui);
        ConfigurationSection items = EconomyCore.getInstance().getConfig().getConfigurationSection("market.items");
        if (items == null) return;

        List<String> keys = new ArrayList<>(items.getKeys(false));
        int startIndex = page * 42;

        for (int i = 0; i < 42 && (startIndex + i) < keys.size(); i++) {
            String key = keys.get(startIndex + i);
            ItemStack item = items.getItemStack(key + ".item");
            if (item == null) continue;

            double price = items.getDouble(key + ".price");
            int maxLimit = items.getInt(key + ".stock", -1);
            int bought = EconomyCore.getInstance().getConfig().getInt("player_data." + player.getUniqueId() + "." + key, 0);

            ItemStack displayItem = item.clone();
            ItemMeta meta = displayItem.getItemMeta();
            if (meta != null) {
                List<String> lore = new ArrayList<>();
                lore.add("§7商品单价: §a" + price + " 金币");
                lore.add("§7个人限额: " + (maxLimit == -1 ? "§b无限制" : "§f" + (maxLimit - bought) + " / " + maxLimit));
                meta.setLore(lore);
                injectBalanceLore(meta, player, price);
                displayItem.setItemMeta(meta);
            }
            gui.setItem((i / 7) * 9 + (i % 7), displayItem);
        }
    }

    /**
     * 随机商店渲染
     * 处理逻辑根据 row 值自动在 random_shop.pool 和特定维度的 random_pool 之间切换。
     */
    public void renderRandomShop(Inventory gui, Player player, int row) {
        clearContent(gui);
        String path = (row >= 10000) ? "market_dimension_" + (row - 10000) + ".random_pool" : "random_shop.pool";
        ConfigurationSection pool = EconomyCore.getInstance().getConfig().getConfigurationSection(path);
        if (pool == null) return;

        List<String> keys = new ArrayList<>(pool.getKeys(false));
        for (int i = 0; i < 42 && i < keys.size(); i++) {
            String key = keys.get(i);
            ItemStack item = pool.getItemStack(key + ".item");
            if (item == null) continue;

            double price = pool.getDouble(key + ".price");
            int maxLimit = pool.getInt(key + ".stock", -1);
            int bought = EconomyCore.getInstance().getConfig().getInt("player_data." + player.getUniqueId() + "." + key, 0);

            ItemStack displayItem = item.clone();
            ItemMeta meta = displayItem.getItemMeta();
            if (meta != null) {
                List<String> lore = new ArrayList<>();
                lore.add("§e§l[随机限定]");
                lore.add("§7抢购价格: §a" + price + " 金币");
                lore.add("§7个人剩余: " + (maxLimit == -1 ? "§b不限量" : (maxLimit - bought)));
                meta.setLore(lore);
                injectBalanceLore(meta, player, price);
                displayItem.setItemMeta(meta);
            }
            gui.setItem((i / 7) * 9 + (i % 7), displayItem);
        }
    }

    /**
     * 账户信息显示
     * 使用玩家头颅作为载体，直接通过 EconomyCore 获取最新的金币数额。
     */
    public void renderAccount(Inventory gui, Player player) {
        clearContent(gui);
        double balance = EconomyCore.getInstance().getBalance(player.getUniqueId());

        ItemStack skull = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) skull.getItemMeta();
        if (meta != null) {
            meta.setOwningPlayer(player);
            meta.setDisplayName("§b§l" + player.getName() + " 的个人账户");
            meta.setLore(Arrays.asList("", "§f当前余额: §e" + balance + " 金币"));
            skull.setItemMeta(meta);
        }
        gui.setItem(22, skull);
    }

    /**
     * 导航栏绘制
     * 包含商店主页、多功能末影之眼（实时余额预览）以及翻页控制。
     */
    /**
     * 绘制精简版侧边导航栏
     */
    private void drawNavigation(Inventory gui, Player player, int page, int row) {
        double balance = EconomyCore.getInstance().getBalance(player.getUniqueId());
        int baseDimId = (row >= 10000) ? (row - 10000) : ((row > 2) ? row : 0);

        // 17: 商店主页
        gui.setItem(17, createItem(Material.CHEST, (row == baseDimId && row != 1) ? "§a▶ 商店主页" : "§7商店主页"));

        // 26: 个人账户
        gui.setItem(26, createItem(Material.ENDER_EYE, (row == 1) ? "§a▶ 个人账户" : "§7个人账户",
                "", "§f当前金币余额:", "§e§l" + balance + " §6G"));

        // 35: 随机商店
        gui.setItem(35, createItem(Material.CLOCK, (row == 2 || row >= 10000) ? "§a▶ 随机商店" : "§7随机商店",
                "§7每位玩家独立限购"));

        // 44: 进化之源 (附魔金苹果，无冗余介绍)
        gui.setItem(44, createItem(Material.ENCHANTED_GOLDEN_APPLE, (row == 1) ? "§d▶ 属性进化" : "§7属性进化",
                "", "§f永久强化你的体力与回充", "§7让你的灵魂得到跃迁"));

        if (row == 0 || (row > 2 && row < 10000)) {
            gui.setItem(53, createItem(Material.ARROW, "§f当前页码: §e" + (page + 1)));
        }
    }

    private void clearContent(Inventory gui) {
        for (int i = 0; i < 54; i++) {
            if (i % 9 < 7) gui.setItem(i, null);
        }
    }

    private ItemStack createItem(Material material, String name, String... lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            meta.setLore(Arrays.asList(lore));
            item.setItemMeta(meta);
        }
        return item;
    }
}
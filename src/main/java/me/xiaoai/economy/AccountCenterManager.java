package me.xiaoai.economy;

import me.xiaoai.economy.gui.EconomyMainMenu;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 个人账户中心接管类
 * 负责：定义物品、渲染布局、执行指令逻辑
 */
public class AccountCenterManager {

    // 定义一个简单的内部类来存储图标信息
    private static class MenuAction {
        ItemStack item;
        Consumer<Player> action;

        MenuAction(ItemStack item, Consumer<Player> action) {
            this.item = item;
            this.action = action;
        }
    }

    // 存储槽位与动作的映射
    private static final java.util.Map<Integer, MenuAction> actions = new java.util.HashMap<>();

    /**
     * 在这里添加你想在个人账户里显示的物品和功能
     */
    public static void init() {
        actions.clear();

        // 示例：添加一个返回主城的指令
        registerItem(10, createIcon(Material.COMPASS, "§6传送回城", "§7点击立即传送至主城"), player -> {
            player.performCommand("spawn");
            player.closeInventory();
        });

        // 示例：添加一个打开便携工作台的功能
        registerItem(12, createIcon(Material.CRAFTING_TABLE, "§d便携合成", "§7随时随地开启工作台"), player -> {
            player.openWorkbench(null, true);
        });


        // 你可以在这里无限添加 registerItem...
    }

    private static void registerItem(int slot, ItemStack item, Consumer<Player> action) {
        actions.put(slot, new MenuAction(item, action));
    }

    /**
     * 由 EconomyMainMenu 调用，用于填充界面
     */
    public static void injectIcons(Inventory gui) {
        actions.forEach((slot, menuAction) -> gui.setItem(slot, menuAction.item));
    }

    /**
     * 由 EconomyListener 调用，用于处理点击
     */
    public static boolean handleInteraction(Player player, int slot) {
        if (actions.containsKey(slot)) {
            actions.get(slot).action.accept(player);
            return true; // 代表事件已被接管
        }
        return false;
    }

    private static ItemStack createIcon(Material material, String name, String... loreLines) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            List<String> lore = new ArrayList<>(java.util.Arrays.asList(loreLines));
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }
}
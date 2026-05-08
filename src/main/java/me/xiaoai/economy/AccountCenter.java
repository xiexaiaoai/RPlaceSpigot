package me.xiaoai.economy;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class AccountCenter {

    public static void inject(Inventory gui, Player player) {
        gui.setItem(10, createIcon(Material.PAPER, "§b§l我的属性", "§7点击查看体力详情"));
        gui.setItem(13, createIcon(Material.EXPERIENCE_BOTTLE, "§a§l经验提取", "§7消耗 1 级经验换取 1 个经验瓶"));
        gui.setItem(16, createIcon(Material.LEATHER_HELMET, "§d§l戴在头上", "§7点击打开单格容器佩戴物品"));
        gui.setItem(30, createIcon(Material.PAINTING, "§6§l放置排行榜", "§7点击查看全服玩家放置数量排行", "", "§e▶ 点击打开"));
        gui.setItem(32, createIcon(Material.TNT, "§c§l灵魂重塑 (自杀)", "§7点击后立即原地去世"));
    }

    public static boolean handleClick(Player player, int slot) {
        List<Integer> validSlots = Arrays.asList(10, 13, 16, 30, 32);
        if (!validSlots.contains(slot)) return false;

        // 使用清脆的铃铛琴音效
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 1.0f, 1.8f);

        switch (slot) {
            case 10:
                player.closeInventory();
                player.performCommand("rp info");
                return true;

            case 13:
                if (player.getLevel() >= 1) {
                    player.setLevel(player.getLevel() - 1);
                    player.getInventory().addItem(new ItemStack(Material.EXPERIENCE_BOTTLE));
                    player.sendMessage("§a§lRPlace >> §f经验提取成功！");
                    player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 2.0f);
                } else {
                    player.sendMessage("§c§lRPlace >> §f你的等级不足 1 级。");
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_DIDGERIDOO, 1.0f, 1.0f);
                }
                return true;

            case 16:
                Inventory headGui = Bukkit.createInventory(null, 9, "§0请放入要佩戴的物品");
                ItemStack glass = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
                ItemMeta m = glass.getItemMeta();
                if(m != null) {
                    m.setDisplayName("§8[ 请放入中间空格 ]");
                    glass.setItemMeta(m);
                }
                for (int i = 0; i < 9; i++) {
                    if (i != 4) headGui.setItem(i, glass);
                }
                player.openInventory(headGui);
                return true;

            case 30:
                player.closeInventory();
                player.performCommand("rp top");
                return true;

            case 32:
                player.closeInventory();
                player.setHealth(0);
                player.playSound(player.getLocation(), Sound.ENTITY_GENERIC_EXTINGUISH_FIRE, 1.0f, 1.5f);
                return true;

            default:
                return false;
        }
    }

    private static ItemStack createIcon(Material m, String name, String... loreLines) {
        ItemStack item = new ItemStack(m);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            List<String> lores = new ArrayList<>();
            for (String line : loreLines) {
                if (line != null && !line.isEmpty()) lores.add(line);
            }
            meta.setLore(lores);
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            item.setItemMeta(meta);
        }
        return item;
    }
}
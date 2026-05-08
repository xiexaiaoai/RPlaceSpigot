package me.xiaoai.economy;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * 核心菜单绑定逻辑处理器
 * 负责确保玩家背包中始终拥有“菜单”道具，并限制其掉落、丢弃与转移。
 */
public class MenuStickinessHandler implements Listener {

    private static final String MENU_NAME = "§b§l菜单";

    /**
     * 获取标准菜单道具模板
     */
    public static ItemStack getMenuClock() {
        ItemStack clock = new ItemStack(Material.CLOCK);
        ItemMeta meta = clock.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(MENU_NAME);
            List<String> lore = new ArrayList<>();
            lore.add("§8STORE_ID:0");
            lore.add("");
            lore.add("§f全服通用核心菜单");
            lore.add("§7§o绑定道具：不可丢弃/不可掉落");
            meta.setLore(lore);
            clock.setItemMeta(meta);
        }
        return clock;
    }

    /**
     * 判断一个物品是否为核心菜单
     */
    private boolean isMenu(ItemStack item) {
        return item != null && item.getType() == Material.CLOCK &&
                item.hasItemMeta() && MENU_NAME.equals(item.getItemMeta().getDisplayName());
    }

    private void ensureMenu(Player player) {
        // 1. 扫描背包所有槽位
        for (ItemStack item : player.getInventory().getContents()) {
            if (isMenu(item)) return; // 只要发现有一个菜单，直接结束，不补发也不删多余的
        }

        // 2. 只有上面没 return（即全身彻底没有），才补发
        player.getInventory().addItem(getMenuClock());
    }

    @EventHandler
    public void onSneak(org.bukkit.event.player.PlayerToggleSneakEvent event) {
        // 玩家按下蹲伏键且处于“从站到蹲”的瞬间触发
        if (event.isSneaking()) {
            ensureMenu(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        // 核心拦截：禁止将菜单放入任何非玩家背包（如箱子、GUI里的坑位等）
        if (isMenu(event.getCurrentItem()) || isMenu(event.getCursor())) {
            if (event.getClickedInventory() != null && event.getClickedInventory().getType() != org.bukkit.event.inventory.InventoryType.PLAYER) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrop(org.bukkit.event.player.PlayerDropItemEvent event) {
        if (isMenu(event.getItemDrop().getItemStack())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDeath(org.bukkit.event.entity.PlayerDeathEvent event) {
        event.getDrops().removeIf(this::isMenu);
    }
}
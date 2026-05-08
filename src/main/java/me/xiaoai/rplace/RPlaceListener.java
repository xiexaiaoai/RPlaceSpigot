package me.xiaoai.rplace;

import me.xiaoai.economy.EconomyCore; // ✅ 导入经济插件包
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.inventory.ItemStack;
import java.util.Objects;

public class RPlaceListener implements Listener {
    private final RPlace plugin;

    public RPlaceListener(RPlace plugin) {
        this.plugin = plugin;
    }

    /* 第一部分：综合管理菜单（点图标会发生什么）

       1. 拦截检查：
          如果 你点的窗口标题不是“综合管理终端”，程序就直接不管；
          如果 是，则锁定图标不让你拿走。

       2. 上帝模式判断：
          如果 你点了“上帝模式”图标，程序会去查你的名单。
          - 如果 你已经是上帝，则把你剔除名单变回凡人，并发红字通知。
          - 如果 你不是上帝，则把你加入名单升为上帝，并发绿字通知。
          - 执行完后，则关闭窗口并重新帮你打开菜单，让你看到开关状态变了。

       3. 能量报告判断：
          如果 你点了“我的能量报告”，则关闭窗口并替你输入“/rp info”指令。

       4. 参数与坐标判断：
          如果 你点了剩下的设置图标，则关闭窗口并在聊天框发一段指令模板教你怎么用。
    */
    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!event.getView().getTitle().equals("§0RPlace 综合管理终端")) return;
        event.setCancelled(true);

        ItemStack item = event.getCurrentItem();
        if (item == null || !item.hasItemMeta()) return;

        Player p = (Player) event.getWhoClicked();
        String name = item.getItemMeta().getDisplayName();

        if (name.contains("上帝模式")) {
            RPlace.AdminMode current = plugin.adminPlayers.getOrDefault(p.getUniqueId(), RPlace.AdminMode.OFF);
            if (current == RPlace.AdminMode.GOD) {
                plugin.adminPlayers.put(p.getUniqueId(), RPlace.AdminMode.OFF);
                p.sendMessage("§6§lRPlace >> §7上帝模式已 §c§l关闭§7。");
            } else {
                plugin.adminPlayers.put(p.getUniqueId(), RPlace.AdminMode.GOD);
                p.sendMessage("§6§lRPlace >> §7上帝模式已 §a§l开启§7！");
            }
            p.closeInventory();
            if (plugin.getCommand("rp") != null && plugin.getCommand("rp").getExecutor() instanceof RPlaceCommand) {
                ((RPlaceCommand) plugin.getCommand("rp").getExecutor()).openHelpMenu(p);
            }
        }
        else if (name.contains("我的能量报告")) {
            p.closeInventory();
            p.performCommand("rp info");
        }
        else if (name.contains("系统参数调整")) {
            p.closeInventory();
            p.sendMessage("§e§lRPlace >> §f参数修改模板: §b/rp reward 2.0");
        }
        else if (name.contains("画布坐标定义")) {
            p.closeInventory();
            p.sendMessage("§d§lRPlace >> §f画布设置模板: §b/rp set <x1> <y> <z1> <x2> <y> <z2>");
        }
    }
    @EventHandler
    public void onLeaderboardClick(InventoryClickEvent event) {
        if (event.getView().getTitle().contains("像素放置")) {
            event.setCancelled(true);

            Player p = (Player) event.getWhoClicked();
            ItemStack item = event.getCurrentItem();
            if (item == null || !item.hasItemMeta()) return;

            Material type = item.getType();
            int slot = event.getRawSlot();

            // 1. 发言逻辑 (保持你喜欢的暴力模拟输入)
            if (slot == 4 || type == Material.LECTERN) {
                p.closeInventory();
                p.sendMessage("§6§lRPlace §8§l>> §f请接着输入留言内容：§e/rp §6msg §7<内容>");
                return;
            }

            // --- 翻页逻辑 ---
            int currentPage = 0;
            try {
                String title = event.getView().getTitle();
                String pageStr = title.replaceAll("[^0-9]", "");
                if (!pageStr.isEmpty()) {
                    currentPage = Integer.parseInt(pageStr) - 1;
                }
            } catch (Exception ignored) {}

            if (plugin.getCommand("rp") != null && plugin.getCommand("rp").getExecutor() instanceof RPlaceCommand) {
                RPlaceCommand cmd = (RPlaceCommand) plugin.getCommand("rp").getExecutor();

                if (type == Material.CYAN_CANDLE) {
                    // 如果已经是第0页，就不再往上翻了，直接开第0页
                    int targetPage = Math.max(0, currentPage - 1);
                    cmd.openLeaderboard(p, targetPage);
                }
                else if (type == Material.MAGENTA_CANDLE) {
                    // 不管后面有没有，直接页码+1
                    cmd.openLeaderboard(p, currentPage + 1);
                }
            }
        }
    }

    /* 第二部分：破坏方块的规矩（挖方块时谁在拦你）

       1. 范围与身份检查：
          如果 破坏的位置在画布内，程序会查你的身份。
          - 如果 你是“上帝”，则直接放行，让你随便拆。
          - 如果 你挖的高度不是画布指定的高度，则判定你在拆底座，强制拦截并弹红字警告。

       2. 能量检查：
          如果 你不是“基础模式”（即普通玩家），则检查你的能量。
          - 如果 能量不够，则拦截破坏，并弹字告诉你还要等几秒。
          - 如果 能量够，则拦截原生的掉落动作，手动把方块变空气（擦除效果），并弹提示。

       3. 保护区检查：
          如果 你在画布外面的保护区乱挖，且你不是上帝，则直接拦截并提示禁止破坏环境。
    */

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBreak(BlockBreakEvent event) {
        if (!plugin.isCanvasSet) return;
        Player p = event.getPlayer();
        Location loc = event.getBlock().getLocation();

        if (plugin.isInCanvasXZ(loc)) {
            RPlace.AdminMode mode = plugin.adminPlayers.getOrDefault(p.getUniqueId(), RPlace.AdminMode.OFF);
            if (mode == RPlace.AdminMode.GOD) return;

            if (loc.getBlockY() != plugin.canvasY) {
                event.setCancelled(true);
                p.sendActionBar("§4警告: 禁止破坏画布底座地板！");
                return;
            }

            if (mode != RPlace.AdminMode.BASIC) {
                if (!plugin.checkAndConsumePoint(p)) {
                    event.setCancelled(true);
                    long nextIn = plugin.getCooldown(p);
                    p.sendActionBar("§c能量不足！距离恢复还剩 " + nextIn + " 秒");
                    return;
                }
            }

            event.setCancelled(true);
            event.getBlock().setType(Material.AIR);
            p.sendActionBar("§e像素已擦除");
        }
        else if (plugin.isInsideProtection(loc)) {
            if (plugin.adminPlayers.getOrDefault(p.getUniqueId(), RPlace.AdminMode.OFF) == RPlace.AdminMode.GOD) return;
            event.setCancelled(true);
            p.sendActionBar("§c[保护区] 禁止破坏这里的环境");
        }
    }

    /* 第三部分：填色逻辑（右键点击方块时）

       1. 预选判断：
          如果 玩家是上帝模式、或者点的不是右键、或者是旁观者，则直接跳过不执行。

       2. 交互判断：
          如果 你右键点的是按钮或拉杆，则不拦截，让你正常触发机关。

       3. 填色动作：
          如果 你右键点的是画布区域。
          - 如果 你手里没拿方块，或者颜色跟地上已经一样了，则弹提示并停止。
          - 如果 你不是基础模式且能量不够，则提示能量不足并停止。
          - 如果 条件全满足，则把画布方块换成你手里的颜色，并弹成功提示。

       4. 消耗判定：
          如果 你不是创造模式也不是上帝/基础模式（即普通生存玩家），则从你手里扣除一个方块。
    */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent event) {
        if (!plugin.isCanvasSet) return;
        Player p = event.getPlayer();
        if (p.getGameMode() == GameMode.SPECTATOR) return;

        RPlace.AdminMode mode = plugin.adminPlayers.getOrDefault(p.getUniqueId(), RPlace.AdminMode.OFF);
        // 上帝模式不触发填色逻辑
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || mode == RPlace.AdminMode.GOD) return;

        Block clickedBlock = event.getClickedBlock();
        if (clickedBlock == null) return;
        Location loc = clickedBlock.getLocation();

        // 排除功能性方块交互
        if (clickedBlock.getType().name().endsWith("_BUTTON") || clickedBlock.getType() == Material.LEVER) return;

        if (plugin.isInCanvasXZ(loc)) {
            event.setCancelled(true);
            Block canvasBlock = loc.getWorld().getBlockAt(loc.getBlockX(), plugin.canvasY, loc.getBlockZ());
            Material handMat = p.getInventory().getItemInMainHand().getType();

            // 基础校验
            if (!handMat.isBlock() || handMat == Material.AIR) return;
            if (canvasBlock.getType() == handMat) {
                p.sendActionBar("§c此处已经是该方块了");
                return;
            }

            // 能量点校验（基础模式免除消耗）
            if (mode != RPlace.AdminMode.BASIC) {
                if (!plugin.checkAndConsumePoint(p)) {
                    long nextIn = plugin.getCooldown(p);
                    p.sendActionBar("§c能量不足！距离恢复还剩 " + nextIn + " 秒");
                    return;
                }
            }

            // --- [开始更新方块] ---
            canvasBlock.setType(handMat);
            p.sendActionBar("§a像素已更新");
            int currentCount = plugin.placedCountMap.getOrDefault(p.getUniqueId(), 0);
// 数量 +1 并存回内存
            plugin.placedCountMap.put(p.getUniqueId(), currentCount + 1);

            // 联动加金币逻辑
            if (p.getGameMode() != org.bukkit.GameMode.CREATIVE) {
                me.xiaoai.economy.EconomyCore core = me.xiaoai.economy.EconomyCore.getInstance();
                if (core != null) {
                    double reward = core.getConfig().getDouble("rewards.place_pixel", 1.0);
                    core.addBalance(p.getUniqueId(), reward);
                    // 聊天框调试提醒
                    p.sendMessage("§6§lRPlace >> §e已发放奖励: §f" + reward + " §e(当前余额: " + core.getBalance(p.getUniqueId()) + ")");
                } else {
                    // 报错提醒：如果看到这行，说明 RPlace.java 里的初始化没写对
                    p.sendMessage("§c§l错误 >> §7经济系统实例为空，请检查插件主类！");
                }
            }

            // --- [方块消耗逻辑] ---
            // 关键修复：只要不是创造模式且不是上帝模式，就正常扣除方块
            if (p.getGameMode() != org.bukkit.GameMode.CREATIVE && mode != RPlace.AdminMode.GOD) {
                ItemStack hand = p.getInventory().getItemInMainHand();
                if (hand.getAmount() > 1) {
                    hand.setAmount(hand.getAmount() - 1);
                } else {
                    p.getInventory().setItemInMainHand(null);
                }
            }
        }
    }

    /* 第四部分：放置拦截（禁止普通人直接摆方块）

       1. 拦截逻辑：
          如果 一个方块正试图在画布或保护区里被直接“放置”出来。
          - 如果 你是上帝，则让你随便放。
          - 如果 你不是上帝，则拦截动作（因为普通人必须通过右键填色逻辑来更新画布）。
    */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPhysicalPlace(BlockPlaceEvent event) {
        if (!plugin.isCanvasSet) return;
        Player p = event.getPlayer();

        if (plugin.adminPlayers.getOrDefault(p.getUniqueId(), RPlace.AdminMode.OFF) == RPlace.AdminMode.GOD) return;

        if (plugin.isInCanvasXZ(event.getBlock().getLocation()) || plugin.isInsideProtection(event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    /* 第五部分：地理边界（进画布起飞，出范围弹回）

       1. 自动起飞：
          如果 你现在走进了画布范围，程序会自动为你开启飞行权限，方便画画。

       2. 距离拦截：
          如果 你正在飞行，且离画布边界超过了 100 格。
          - 如果 你是普通玩家，程序会计算你当前位置指向画布中心的方向。
          - 执行一次强力弹回，将你向中心方向瞬间移动 40 格，并发送警告。
    */
    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        if (!plugin.isCanvasSet) return;
        Player p = event.getPlayer();
        RPlace.AdminMode mode = plugin.adminPlayers.getOrDefault(p.getUniqueId(), RPlace.AdminMode.OFF);
        if (mode == RPlace.AdminMode.GOD || p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) return;

        Location loc = p.getLocation();
        if (!Objects.requireNonNull(loc.getWorld()).getName().equals(plugin.canvasWorld)) return;

        double dx = plugin.getDist(loc.getX(), plugin.minX, plugin.maxX);
        double dz = plugin.getDist(loc.getZ(), plugin.minZ, plugin.maxZ);

        // 自动开启飞行
        if (dx == 0 && dz == 0) {
            if (!p.getAllowFlight()) p.setAllowFlight(true);
        }

        // 100格边界强制拉回
        if (p.isFlying() && mode == RPlace.AdminMode.OFF) {
            if (dx > 100 || dz > 100) {
                // 计算画布中心点
                double centerX = (plugin.minX + plugin.maxX) / 2.0;
                double centerZ = (plugin.minZ + plugin.maxZ) / 2.0;

                // 计算从玩家指向中心的方向向量
                Location target = loc.clone();
                org.bukkit.util.Vector vectorToCenter = new org.bukkit.util.Vector(centerX - loc.getX(), 0, centerZ - loc.getZ()).normalize();

                // 往中心方向弹回 40 格
                target.add(vectorToCenter.multiply(40));
                // 确保 Y 轴安全，防止卡进地板，至少保持在画布上方 5 格
                target.setY(Math.max(loc.getY(), plugin.canvasY + 5));

                p.teleport(target);
                p.sendMessage("§c§lRPlace >> §7你离画布太远了！已将你向中心拉回 40 格。");
            }
        }
    }

    /* 第七部分：飞行回收（离开边界禁飞）

       1. 禁飞保护：
          如果 你尝试开启飞行。
          - 如果 你不在创造模式、也不是上帝、且距离画布超过了 100 格的安全半径。
          - 程序会立刻拦截起飞动作并关闭权限，防止玩家直接飞离画布区域。
    */
    @EventHandler
    public void onToggleFlight(PlayerToggleFlightEvent event) {
        Player p = event.getPlayer();
        RPlace.AdminMode mode = plugin.adminPlayers.getOrDefault(p.getUniqueId(), RPlace.AdminMode.OFF);

        if (p.getGameMode() == org.bukkit.GameMode.CREATIVE ||
                p.getGameMode() == org.bukkit.GameMode.SPECTATOR ||
                mode != RPlace.AdminMode.OFF) return;

        if (event.isFlying()) {
            // 检查当前距离，允许在 100 格范围内飞行，超过则拦截
            double dx = plugin.getDist(p.getLocation().getX(), plugin.minX, plugin.maxX);
            double dz = plugin.getDist(p.getLocation().getZ(), plugin.minZ, plugin.maxZ);

            if (dx > 100 || dz > 100) {
                p.setAllowFlight(false);
                p.setFlying(false);
                event.setCancelled(true);
                p.sendMessage("§c§lRPlace >> §7此处距离画布过远，禁止飞行。");
            }
        }
    }
    /* 第八部分：合成限制（防止在画布世界利用资源）

       1. 维度与身份检查：
          如果 触发合成的位置是在画布设置的世界中。
          - 如果 玩家是“上帝”模式或者是创造模式，则允许合成。
          - 如果 是普通玩家，则直接拦截合成动作，并弹窗提示。
    */
    @org.bukkit.event.EventHandler
    public void onCraft(org.bukkit.event.inventory.CraftItemEvent event) {
        if (!plugin.isCanvasSet) return;

        // 获取点击合成台的玩家
        Player p = (Player) event.getWhoClicked();

        // 检查玩家当前所在的世界是否是画布世界
        if (p.getWorld().getName().equals(plugin.canvasWorld)) {

            // 权限检查：上帝模式和创造模式豁免
            RPlace.AdminMode mode = plugin.adminPlayers.getOrDefault(p.getUniqueId(), RPlace.AdminMode.OFF);
            if (mode == RPlace.AdminMode.GOD || p.getGameMode() == org.bukkit.GameMode.CREATIVE) {
                return;
            }

            // 拦截合成
            event.setCancelled(true);
            p.sendMessage("§c§lRPlace >> §7在该世界禁止合成物品，以维护画布资源平衡。");
        }

    }

}

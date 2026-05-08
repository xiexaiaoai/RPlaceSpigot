package me.xiaoai.rplace;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
/* 第一部分：RPlace 指令处理器类

如果 该类被实例化
则 接收并注入 RPlace 插件主类实例

最后 为插件提供 GUI 菜单、上帝模式切换及奖励系数调整的核心逻辑支持。
*/
public class RPlaceCommand implements CommandExecutor, TabCompleter {
    private final RPlace plugin;

    public RPlaceCommand(RPlace plugin) {
        this.plugin = plugin;
    }

    /* 第二部分：指令执行逻辑入口

    如果 指令发送者不是玩家
    则 直接中断逻辑并返回

    如果 参数长度为 0 或第一个参数为 "help"
    则 执行打开帮助菜单的方法

    如果 第一个参数为 "info"
    则 执行展示玩家信息的方法

    如果 玩家不具备 "rplace.admin" 权限
    则 发送无权提示并拦截后续指令

    如果 权限校验通过
    则 根据具体子命令执行分支：
    若为 "set"，则 跳转至画布设置逻辑
    若为 "reward"，则 跳转至奖励倍率设置
    若为 "limit"，则 跳转至玩家限制设置
    若为 "god"，则 跳转至上帝模式切换
    若为 "reload"，则 重载配置文件并提示玩家

    最后 结束指令处理流程。
    */
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) return true;

        // 1. 帮助菜单 (所有人可用)
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            openHelpMenu(player);
            return true;
        }

        // 2. 查看个人能量信息 (所有人可用)
        if (args[0].equalsIgnoreCase("info")) {
            showPlayerInfo(player);
            return true;
        }
        // --- 插入这段：处理排行榜指令 ---
        if (args[0].equalsIgnoreCase("top")) {
            openLeaderboard(player, 0);
            return true;
        }


        if (args[0].equalsIgnoreCase("msg") && args.length > 1) {
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i < args.length; i++) sb.append(args[i]).append(" ");
            plugin.playerMessages.put(player.getUniqueId(), sb.toString().trim());
            player.sendMessage("§6§lRPlace >> §a感言设置成功！");
            return true;
        }
        // 3. 查看排行榜 (所有人可用)
        if (args[0].equalsIgnoreCase("top")) {
            openLeaderboard(player, 0);
            return true;
        }

        // 4. 设置个人留言 (所有人可用 - 已添加实时保存逻辑)
        if (args[0].equalsIgnoreCase("msg")) {
            if (args.length < 2) {
                player.sendMessage("§c§lRPlace >> §7留言内容不能为空！格式: /rp msg <内容>");
                return true;
            }

            // 拼接内容
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i < args.length; i++) {
                sb.append(args[i]).append(" ");
            }
            String content = sb.toString().trim();

            // 长度限制
            if (content.length() > 50) {
                player.sendMessage("§c§lRPlace >> §7内容太长了！请控制在 50 字以内。");
                return true;
            }

            // 存入内存 HashMap
            plugin.playerMessages.put(player.getUniqueId(), content);

            // --- 核心改动：立刻强制保存到 data.yml，防止重启丢失 ---
            if (plugin.getDataManager() != null) {
                plugin.getDataManager().saveRankData();
            }

            player.sendMessage("§a§lRPlace >> §7留言保存成功！数据已实时同步至硬盘。");
            return true;
        }

        // --- 以下为管理员权限校验 ---
        if (!player.hasPermission("rplace.admin")) {
            player.sendMessage("§c§lRPlace >> §7你没有权限执行管理指令。");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "set":
                handleSetCanvas(player, args);
                break;
            case "reward":
                handleSetReward(player, args);
                break;
            case "limit":
                handleSetLimit(player, args);
                break;
            case "god":
                toggleGodMode(player);
                break;
            case "reload":
                plugin.getDataManager().loadConfigData();
                // 重载时同时刷新排行榜数据
                if (plugin.getDataManager() != null) {
                    plugin.getDataManager().loadRankData();
                }
                player.sendMessage("§a§lRPlace >> §7配置文件、排行榜及留言数据已重载。");
                break;
        }

        return true;
    }

    /**
     * 打开排行榜 GUI 界面
     */
    void openLeaderboard(Player player, int page) {
        Inventory inv = Bukkit.createInventory(null, 54, "§0像素放置 - 第 " + (page + 1) + " 页");

        updateLeaderboardItems(player, inv, page);

        player.openInventory(inv);
    }

    /* 第三部分：管理与帮助终端 GUI

    如果 玩家触发了菜单打开请求
    则 创建一个名为 "RPlace 综合管理终端" 的 27 格容器
    并 使用灰色玻璃板铺满背景

    如果 渲染查询图标
    则 固定生成“我的能量报告”书籍图标

    如果 玩家拥有管理员权限
    则 渲染管理专用组件：
    若 玩家已处于上帝模式，则 显示附魔金苹果及激活状态
    若 未处于上帝模式，则 显示普通金苹果及未激活状态
    并 生成“系统参数调整”与“画布坐标定义”图标

    如果 玩家为普通用户
    则 在中心位置生成“特权说明”翡翠图标

    最后 为玩家打开该图形化界面。
    */
    public void openHelpMenu(Player p) {
        Inventory inv = Bukkit.createInventory(null, 27, "§0RPlace 综合管理终端");

        ItemStack glass = createGuiItem(Material.GRAY_STAINED_GLASS_PANE, " ", "");
        for (int i = 0; i < 27; i++) inv.setItem(i, glass);

        inv.setItem(10, createGuiItem(Material.BOOK, "§b§l[查询] 我的能量报告",
                "§7指令: §f/rp info",
                "",
                "§f查看当前点数、恢复速度及奖励。",
                "",
                "§e▶ 点击立即在聊天框查看详情"));

        if (p.hasPermission("rplace.admin")) {
            RPlace.AdminMode mode = plugin.adminPlayers.getOrDefault(p.getUniqueId(), RPlace.AdminMode.OFF);
            Material godMat = (mode == RPlace.AdminMode.GOD) ? Material.ENCHANTED_GOLDEN_APPLE : Material.GOLDEN_APPLE;
            String godStatus = (mode == RPlace.AdminMode.GOD) ? "§a§l已激活" : "§c§l未激活";

            inv.setItem(12, createGuiItem(godMat, "§6§l[管理] 上帝模式开关",
                    "§7当前状态: " + godStatus,
                    "",
                    "§f上帝模式特权:",
                    "§7- §f无限能量点数 (无CD)",
                    "§7- §f放置不消耗背包材料",
                    "§7- §f无视所有放置限制",
                    "",
                    "§e▶ 点击切换模式状态"));

            inv.setItem(14, createGuiItem(Material.GOLD_INGOT, "§e§l[管理] 系统参数调整",
                    "§7当前放置奖励: §f" + plugin.placeReward,
                    "",
                    "§f常用指令引导:",
                    "§7- §f/rp reward <数值>: §7设置奖励",
                    "§7- §f/rp limit <玩家> <上限> <CD>: §7设置特权",
                    "",
                    "§e▶ 点击在聊天框获取模板"));

            inv.setItem(16, createGuiItem(Material.FILLED_MAP, "§d§l[核心] 画布坐标定义",
                    "§7当前状态: " + (plugin.isCanvasSet ? "§a已就绪" : "§c未设置"),
                    "",
                    "§f指令模板:",
                    "§7/rp set <x1> <y> <z1> <x2> <y> <z2>",
                    "",
                    "§e▶ 点击获取坐标设置指令示例"));
        } else {
            inv.setItem(13, createGuiItem(Material.EMERALD, "§a§l[特权] 如何变强？",
                    "§7你可以通过提升等级 or 购买特权:",
                    "",
                    "§f- 提升能量存储上限 (连放次数)",
                    "§f- 缩短能量恢复时间 (绘画CD)",
                    "",
                    "§7§o更多功能敬请期待..."));
        }

        p.openInventory(inv);
    }


    /* 第四部分：上帝模式切换逻辑

    如果 玩家当前状态为 GOD
    则 将其状态修改为 OFF 并发送关闭提示

    如果 玩家当前状态不为 GOD
    则 将其状态修改为 GOD 并发送开启提示
    */
    private void toggleGodMode(Player p) {
        RPlace.AdminMode current = plugin.adminPlayers.getOrDefault(p.getUniqueId(), RPlace.AdminMode.OFF);
        if (current == RPlace.AdminMode.GOD) {
            plugin.adminPlayers.put(p.getUniqueId(), RPlace.AdminMode.OFF);
            p.sendMessage("§6§lRPlace >> §7上帝模式已 §c§l关闭§7。");
        } else {
            plugin.adminPlayers.put(p.getUniqueId(), RPlace.AdminMode.GOD);
            p.sendMessage("§6§lRPlace >> §7上帝模式已 §a§l开启§7！");
        }
    }

    /* 第五部分：玩家信息查询展示

    如果 触发信息查询
    则 从插件映射表中检索该 UUID 对应的上限、当前能量及恢复速度：
    若 映射表不存在该玩家数据，则 采用系统默认缺省值

    最后 格式化拼接为美化的聊天框消息发送给玩家。
    */
    private void showPlayerInfo(Player p) {
        UUID uuid = p.getUniqueId();
        int max = plugin.maxPointsMap.getOrDefault(uuid, plugin.defaultMaxPoints);
        int current = plugin.currentPoints.getOrDefault(uuid, max);
        int speed = plugin.recoverSpeedMap.getOrDefault(uuid, plugin.defaultRecoverSpeed);

        p.sendMessage("§8§m--------------------------------------");
        p.sendMessage("§6§lRPlace §8>> §f账户详情");
        p.sendMessage("§7可用能量: §a" + current + " §7/ §2" + max + " §8(点数)");
        p.sendMessage("§7恢复效率: §e每 " + speed + " 秒 §f恢复 1 点");
        p.sendMessage("§7放置奖励: §f" + plugin.placeReward + " §7/ 像素格");
        p.sendMessage("§8§m--------------------------------------");
    }

    /* 第六部分：玩家特权限制设置逻辑

    如果 指令参数少于 4 个
    则 发送用法提示并返回

    如果 目标玩家不在线
    则 提示找不到玩家并返回

    如果 参数解析正常
    则 更新插件内存中的上限与速度映射表
    并 调用数据管理器保存至本地配置
    最后 向管理员发送更新成功提示

    若 数字解析异常，则 捕获错误并提示输入无效。
    */
    private void handleSetLimit(Player admin, String[] args) {
        if (args.length < 4) {
            admin.sendMessage("§c§lRPlace >> §7用法: /rp limit <玩家> <上限> <秒数>");
            return;
        }
        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) {
            admin.sendMessage("§c§lRPlace >> §7找不到玩家。");
            return;
        }
        try {
            int max = Integer.parseInt(args[2]);
            int speed = Integer.parseInt(args[3]);
            plugin.maxPointsMap.put(target.getUniqueId(), max);
            plugin.recoverSpeedMap.put(target.getUniqueId(), speed);
            plugin.getDataManager().saveConfigData();
            admin.sendMessage("§a§lRPlace >> §7玩家 §f" + target.getName() + " §7特权已更新！");
        } catch (NumberFormatException e) {
            admin.sendMessage("§c§lRPlace >> §7数字输入无效。");
        }
    }

    /* 第七部分：全局放置奖励设置

    如果 参数长度不足
    则 忽略操作

    如果 参数可以解析为双精度浮点数
    则 更新插件全局变量并写入配置文件
    最后 向管理员回馈调整后的数值。
    */
    private void handleSetReward(Player admin, String[] args) {
        if (args.length < 2) return;
        try {
            plugin.placeReward = Double.parseDouble(args[1]);
            plugin.getDataManager().saveConfigData();
            admin.sendMessage("§a§lRPlace >> §7全局奖励已调整为: §f" + plugin.placeReward);
        } catch (Exception e) { admin.sendMessage("§c§lRPlace >> §7参数错误。"); }
    }

    /* 第八部分：正方形场馆生成与管理员撤销逻辑 */

    /**
     * 异步生成 2000x2000 级场馆
     * 结构：y层为地基，y+1层为绘画层，外围 100 格为草地
     */
    /* 第八部分：正方形场馆生成 (高度平齐 & 异步防卡顿版) */
    private void handleSetCanvas(Player admin, String[] args) {
        // 指令格式: /rp set <x1> <y> <z1> <x2> <y> <z2>
        if (args.length < 7) {
            admin.sendMessage("§c§lRPlace >> §7模板: /rp set <x1> <y> <z1> <x2> <y> <z2>");
            return;
        }
        try {
            plugin.canvasWorld = admin.getWorld().getName();
            int x1 = Integer.parseInt(args[1]);
            int y = Integer.parseInt(args[2]); // 基准行走高度
            int z1 = Integer.parseInt(args[3]);
            int x2 = Integer.parseInt(args[4]);
            int z2 = Integer.parseInt(args[6]); // 获取最后一个坐标参数

            plugin.canvasY = y;
            plugin.minX = Math.min(x1, x2);
            plugin.maxX = Math.max(x1, x2);
            plugin.minZ = Math.min(z1, z2);
            plugin.maxZ = Math.max(z1, z2);

            int padding = 100; // 外围草地走廊宽度
            int startX = plugin.minX - padding;
            int endX = plugin.maxX + padding;
            int startZ = plugin.minZ - padding;
            int endZ = plugin.maxZ + padding;

            admin.sendMessage("§e§lRPlace >> §f正在异步部署 2000x2000 级平齐场馆...");

            new org.bukkit.scheduler.BukkitRunnable() {
                int curX = startX;
                @Override
                public void run() {
                    // 每刻(Tick)处理 50 行，平衡速度与流畅度
                    for (int i = 0; i < 50; i++) {
                        if (curX > endX) {
                            admin.sendMessage("§a§lRPlace >> §7[✔] 场馆部署完成！画布与草地已完全平齐。");
                            plugin.isCanvasSet = true;
                            plugin.getDataManager().saveConfigData();
                            this.cancel();
                            return;
                        }
                        for (int z = startZ; z <= endZ; z++) {
                            if (curX >= plugin.minX && curX <= plugin.maxX && z >= plugin.minZ && z <= plugin.maxZ) {
                                // 【画布区】
                                // y - 1 层：埋入地下的地基 (白色混凝土)
                                admin.getWorld().getBlockAt(curX, y - 1, z).setType(Material.WHITE_CONCRETE);
                                // y 层：可操作绘画层 (白色混凝土)
                                admin.getWorld().getBlockAt(curX, y, z).setType(Material.WHITE_CONCRETE);
                            } else {
                                // 【外围走廊】
                                // y 层：草方块 (与绘画层平齐，无需跳跃)
                                admin.getWorld().getBlockAt(curX, y, z).setType(Material.GRASS_BLOCK);
                                // 保持草地下方干净（回填空气）
                                admin.getWorld().getBlockAt(curX, y - 1, z).setType(Material.AIR);
                            }
                        }
                        curX++;
                    }

                    // 每 200 行打印一次后台进度
                    if (curX % 200 == 0) {
                        double progress = (double)(curX - startX) / (endX - startX) * 100;
                        Bukkit.getLogger().info("[RPlace] 场馆生成中: " + String.format("%.2f", progress) + "%");
                    }
                }
            }.runTaskTimer(plugin, 1L, 1L);

        } catch (Exception e) {
            admin.sendMessage("§c§lRPlace >> §7坐标解析失败，请检查输入。");
        }
    }

    /* 第九部分：GUI 物品创建工具 */
    private ItemStack createGuiItem(Material mat, String name, String... lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            meta.setLore(Arrays.asList(lore));
            item.setItemMeta(meta);
        }
        return item;
    }
    // 这是打开排行榜的方法
    /* 完整排行榜 GUI 方法 - 已移除背景玻璃并启用正版头颅显示 */
    // 这个方法只负责填东西，不负责开窗口
    /* 完整排行榜更新方法 - 已按新需求重构：置顶个人、无玻璃背景、讲台发言、蜡烛翻页 */
    public void updateLeaderboardItems(Player p, Inventory inv, int page) {
        List<UUID> sorted = new ArrayList<>(plugin.placedCountMap.keySet());
        sorted.sort((u1, u2) -> plugin.placedCountMap.get(u2).compareTo(plugin.placedCountMap.get(u1)));

        // 1. 置顶个人头颅 (4号位)
        ItemStack selfSkull = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta selfMeta = (SkullMeta) selfSkull.getItemMeta();
        if (selfMeta != null) {
            selfMeta.setOwningPlayer(p);
            selfMeta.setDisplayName("§e§l我的排名信息");
            int rank = sorted.indexOf(p.getUniqueId()) + 1;
            int count = plugin.placedCountMap.getOrDefault(p.getUniqueId(), 0);
            selfMeta.setLore(Arrays.asList(
                    "§7当前排名: §b" + (rank == 0 ? "未上榜" : rank),
                    "§7累计放置: §a" + count + " 块",
                    "",
                    "§e[!] 点击在此发言"
            ));
            selfSkull.setItemMeta(selfMeta);
        }
        inv.setItem(4, selfSkull);

        // 2. 填充 36 个排行榜头颅 (9-44号位)
        int start = page * 36;
        for (int i = 0; i < 36 && (start + i) < sorted.size(); i++) {
            UUID id = sorted.get(start + i);
            int count = plugin.placedCountMap.get(id);
            String message = plugin.playerMessages.getOrDefault(id, "这个玩家很神秘，什么都没留。");
            String name = Bukkit.getOfflinePlayer(id).getName();

            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            if (meta != null) {
                meta.setOwningPlayer(Bukkit.getOfflinePlayer(id));
                meta.setDisplayName("§f第 §6" + (start + i + 1) + " §f名: §b" + (name != null ? name : "未知玩家"));
                meta.setLore(Arrays.asList("§7放置数量: §a" + count, "§7留言: §f" + message));
                head.setItemMeta(meta);
            }
            inv.setItem(i + 9, head);
        }

        // --- 3. 底部功能区 (强制显示两个蜡烛) ---

        // 讲台中心 (49号位)
        inv.setItem(49, createGuiItem(Material.LECTERN, "§6§l像素发言", "§7点击自动输入指令进行发言"));

        // 左侧：上一页 (48号位) - 强制显示，点就完事了
        inv.setItem(48, createGuiItem(Material.CYAN_CANDLE, "§b⬅ 上一页", "§7前往第 " + (page <= 0 ? 1 : page) + " 页"));

        // 右侧：下一页 (50号位) - 强制显示，不管后面有没有数据
        inv.setItem(50, createGuiItem(Material.MAGENTA_CANDLE, "§d下一页 ➡", "§7前往第 " + (page + 2) + " 页"));
    }


    /* 第十部分：补全参数建议 */
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player player)) return new ArrayList<>();

        // 第一级指令补全
        if (args.length == 1) {
            List<String> list = new ArrayList<>();
            // 所有人指令
            list.addAll(Arrays.asList("help", "info", "top", "msg"));
            // 管理指令
            if (player.hasPermission("rplace.admin")) {
                list.addAll(Arrays.asList("set", "reward", "limit", "reload", "god"));
            }
            return list.stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .collect(java.util.stream.Collectors.toList());
        }

        String sub = args[0].toLowerCase();

        // 1. 留言功能说明 (使用 # 置顶)
        if (sub.equals("msg")) {
            if (args.length == 2) return Arrays.asList("# >>> [ 填写感言 ] 请输入你想在排行榜显示的内容(50字内)");
        }

        // --- 管理员指令说明 (需要权限) ---
        if (!player.hasPermission("rplace.admin")) return new ArrayList<>();

        if (sub.equals("set")) {
            if (args.length == 2) return Arrays.asList(player.getWorld().getName(), "# >>> [ 1.世界名 ] 输入画布所在的世界名称");
            if (args.length == 3) return Arrays.asList("0", "# >>> [ 2.最小X ] 画布左上角的 X 坐标");
            if (args.length == 4) return Arrays.asList("100", "# >>> [ 3.最大X ] 画布右下角的 X 坐标");
            if (args.length == 5) return Arrays.asList("0", "# >>> [ 4.最小Z ] 画布左上角的 Z 坐标");
            if (args.length == 6) return Arrays.asList("100", "# >>> [ 5.最大Z ] 画布右下角的 Z 坐标");
            if (args.length == 7) return Arrays.asList("64", "# >>> [ 6.高度Y ] 画布所在的方块高度");
        }

        if (sub.equals("limit")) {
            if (args.length == 2) {
                List<String> players = Bukkit.getOnlinePlayers().stream()
                        .map(Player::getName)
                        .collect(java.util.stream.Collectors.toList());
                // 把提示信息插到索引 0 的位置，并用 # 确保它排在最前面
                players.add(0, "# >>> [ 玩家名 ] 选择要修改上限的玩家");
                return players;
            }
            if (args.length == 3) return Arrays.asList("10", "100", "# >>> [ 上限值 ] 玩家最大可拥有的能量点数");
        }

        if (sub.equals("reward")) {
            if (args.length == 2) return Arrays.asList("1.0", "2.0", "# >>> [ 奖励倍率 ] 设置全局放置后的经济奖励系数");
        }

        if (sub.equals("god")) {
            return Arrays.asList("# >>> [ 切换状态 ] 开启后放置不消耗能量且无视CD");
        }

        if (sub.equals("reload")) {
            return Arrays.asList("# >>> [ 立即生效 ] 重新加载 data.yml 和 config.yml");
        }

        if (sub.equals("top")) {
            return Arrays.asList("# >>> [ 打开排行 ] 查看玩家放置数量排行榜");
        }

        return new ArrayList<>();
    }

}
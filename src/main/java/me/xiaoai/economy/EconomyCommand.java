package me.xiaoai.economy;

import me.xiaoai.economy.gui.EconomyMainMenu;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 经济系统指令核心处理器
 * 采用了“控制台驱动”的安全设计：核心的 open 指令禁止玩家直接输入，
 * 必须通过命令方块或后台执行，以确保商店的访问受道具或特定坐标控制。
 * 集成了商品上架、维度钟发放、余额增删及随机商店手动刷新功能。
 */
public class EconomyCommand implements CommandExecutor, TabCompleter {

    /**
     * 指令主分发器
     * 负责初步的权限校验。只有具备 rplace.admin 权限的人员才能执行。
     * 对于 open 子指令，强制校验执行者身份，拦截玩家手动绕过道具限制的行为。
     */
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (sender instanceof Player && !sender.hasPermission("rplace.admin")) {
            sender.sendMessage("§c§l[!] §c该指令已被禁用，请使用专用道具开启商店。");
            return true;
        }

        if (args.length == 0) {
            if (sender instanceof Player) {
                new EconomyMainMenu().open((Player) sender, 0, 0);
            } else {
                sender.sendMessage("控制台/命令方块请使用: /money open <玩家/@p> <维度ID>");
            }
            return true;
        }

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "open":
                if (sender instanceof Player) {
                    sender.sendMessage("§c§l[!] §c'open' 指令仅限命令方块调用，禁止手动输入。");
                    return true;
                }
                handleOpenCommand(sender, args);
                break;

            case "additem":
                if (sender instanceof Player) {
                    handleAddHandItem((Player) sender, args);
                }
                break;

            case "give":
                handleGiveShopItem(sender, args);
                break;

            case "refresh":
                EconomyCore.getInstance().refreshRandomShop();
                sender.sendMessage("§a§l[!] §a随机商店已强制刷新！");
                if (sender instanceof Player) {
                    ((Player) sender).playSound(((Player) sender).getLocation(), Sound.BLOCK_ANVIL_USE, 1.0f, 1.5f);
                }
                break;

            case "admin":
                if (sender instanceof Player) {
                    handleAdminCommand((Player) sender, args);
                }
                break;

            default:
                sender.sendMessage("§7可用指令: open (限后台), additem, give, refresh, admin");
                break;

            case "reload":
                EconomyCore.getInstance().reloadCustomConfig();
                sender.sendMessage("§a§l[✔] §a经济系统配置文件已重新加载！");
                if (sender instanceof Player) {
                    ((Player) sender).playSound(((Player) sender).getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
                }
                break;
        }
        return true;
    }

    /**
     * 远程界面开启逻辑
     * 支持 @p 等选择器，主要用于命令方块触发，根据传入的维度 ID 展示对应的商店内容。
     */
    private void handleOpenCommand(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("用法: /money open <玩家/@p> <维度ID>");
            return;
        }

        Player target = parsePlayer(sender, args[1]);
        if (target == null) {
            sender.sendMessage("§c§l[!] §c错误：无法找到目标玩家 '" + args[1] + "'。");
            return;
        }

        int row = (args.length >= 3) ? tryParseInt(args[2], 0) : 0;
        new EconomyMainMenu().open(target, 0, row);
    }

    /**
     * 维度传送钟发放系统
     * 创建一个带有特定 Lore 的时钟道具。Lore 中的 STORE_ID 是 GUI 识别维度的唯一标识。
     */
    private void handleGiveShopItem(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage("§7用法: /money give <玩家/@p> <维度ID>");
            return;
        }

        Player target = parsePlayer(sender, args[1]);
        if (target == null) {
            sender.sendMessage("§c§l[!] §c找不到玩家: " + args[1]);
            return;
        }

        int dimId = tryParseInt(args[2], 0);
        ItemStack clock = new ItemStack(Material.CLOCK);
        ItemMeta meta = clock.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§b§l维度传送钟 §7(右键开启)");
            List<String> lore = new ArrayList<>();
            lore.add("§8STORE_ID:" + dimId);
            lore.add("");
            lore.add("§f持有此钟可开启维度商店: §e#" + dimId);
            lore.add("§7该商店拥有独立物品与限购额度");
            meta.setLore(lore);
            clock.setItemMeta(meta);
        }
        target.getInventory().addItem(clock);
        sender.sendMessage("§a§l[✔] §f已发放维度钟 #" + dimId + " 给 " + target.getName());
    }

    /**
     * 商品自动化上架逻辑
     * 核心逻辑：获取玩家主手物品，根据参数判断是存入“全球市场”、“维度商店”还是“随机奖池”。
     * 随机模式下支持权重设定及维度限制功能。
     */
    private void handleAddHandItem(Player player, String[] args) {
        if (args.length < 3) {
            player.sendMessage("§7用法: /money additem <维度ID/random> <价格> [库存/权重] [随机限购] [指定维度]");
            return;
        }

        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand == null || hand.getType() == Material.AIR) {
            player.sendMessage("§c§l[!] §c请先手持物品。");
            return;
        }

        try {
            String typeInput = args[1].toLowerCase();
            double price = Double.parseDouble(args[2]);
            int val = (args.length >= 4) ? Integer.parseInt(args[3]) : -1;

            EconomyCore core = EconomyCore.getInstance();
            String path;
            String typeName;

            if (typeInput.equals("random") || typeInput.equals("r")) {
                int targetDim = (args.length >= 6) ? Integer.parseInt(args[5]) : -1;
                path = (targetDim == -1) ? "random_pool_source." : "market_dimension_" + targetDim + ".random_pool.";
                typeName = (targetDim == -1) ? "全局随机仓库" : "维度 " + targetDim + " 的专属随机仓库";
                int stock = (args.length >= 5) ? Integer.parseInt(args[4]) : 1;
                String key = hand.getType().name() + "_" + System.currentTimeMillis();
                core.getConfig().set(path + key + ".item", hand.clone());
                core.getConfig().set(path + key + ".price", price);
                core.getConfig().set(path + key + ".weight", (val <= 0 ? 10 : val));
                core.getConfig().set(path + key + ".stock", stock);
            } else {
                int dimId = Integer.parseInt(typeInput);
                path = (dimId == 0) ? "market.items." : "market_dimension_" + dimId + ".items.";
                typeName = (dimId == 0) ? "全球市场" : "维度商店-" + dimId;
                String key = hand.getType().name() + "_" + System.currentTimeMillis();
                core.getConfig().set(path + key + ".item", hand.clone());
                core.getConfig().set(path + key + ".price", price);
                core.getConfig().set(path + key + ".stock", val);
            }

            core.saveCustomConfig();
            player.sendMessage("§a§l[✔] §f成功上架到: §e" + typeName);
        } catch (Exception e) {
            player.sendMessage("§c§l[!] §c参数错误。");
        }
    }

    /**
     * 管理员余额操作接口
     * 直接对接 EconomyCore 的 API，提供对指定玩家账户余额的增、删、改功能。
     */
    private void handleAdminCommand(Player admin, String[] args) {
        if (args.length < 4) {
            admin.sendMessage("§7用法: /money admin <add|set|take> <玩家> <金额>");
            return;
        }

        Player target = Bukkit.getPlayer(args[2]);
        if (target == null) {
            admin.sendMessage("§c玩家不在线。");
            return;
        }

        try {
            double amount = Double.parseDouble(args[3]);
            EconomyCore core = EconomyCore.getInstance();
            switch (args[1].toLowerCase()) {
                case "add": core.addBalance(target.getUniqueId(), amount); break;
                case "set": core.setBalance(target.getUniqueId(), amount); break;
                case "take": core.takeBalance(target.getUniqueId(), amount); break;
            }
            admin.sendMessage("§a§l[✔] §f已更新账户。");
        } catch (Exception e) {
            admin.sendMessage("§c金额无效。");
        }
    }

    /**
     * 玩家解析工具
     * 兼容 Bukkit 原生选择器（如 @p, @a[distance=..5]），若解析失败则退化为普通 ID 匹配。
     */
    private Player parsePlayer(CommandSender sender, String input) {
        try {
            List<org.bukkit.entity.Entity> entities = Bukkit.getServer().selectEntities(sender, input);
            for (org.bukkit.entity.Entity e : entities) {
                if (e instanceof Player) return (Player) e;
            }
        } catch (Exception e) {
            return Bukkit.getPlayer(input);
        }
        return null;
    }

    /**
     * 简单的数字转换安全封装
     */
    private int tryParseInt(String s, int def) {
        try { return Integer.parseInt(s); } catch (Exception e) { return def; }
    }

    /**
     * 指令补全系统
     * 针对 additem 指令提供了极其详细的动态提示，引导管理员完成复杂参数（价格、权重、限额）的输入。
     */
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player) || !sender.hasPermission("rplace.admin")) return new ArrayList<>();

        // 第一级指令补全
        if (args.length == 1) {
            return Arrays.asList("additem", "admin", "give", "open", "refresh", "reload").stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .collect(java.util.stream.Collectors.toList());
        }

        String sub = args[0].toLowerCase();

        // 1. additem 指令逻辑
        if (sub.equals("additem")) {
            if (args.length == 2) return Arrays.asList("! >>> [ 设定目标 ] 0=全球 / 数字=维度ID / random=随机池仓库", "0", "10", "random");
            if (args.length == 3) return Arrays.asList("! >>> [ 设定价格 ] 正数为买入价格 / 负数为商店回收价格", "100.0", "-10.0");

            boolean isRandom = args[1].equalsIgnoreCase("random");
            if (args.length == 4) {
                return isRandom ?
                        Arrays.asList("! >>> [ 设定权重 ] 数字越大随机刷出的概率越高", "50", "100") :
                        Arrays.asList("! >>> [ 设定库存 ] 玩家可买总数, -1为无限供应", "-1", "64");
            }
            if (args.length == 5 && isRandom) return Arrays.asList("! >>> [ 刷新数量 ] 该物品每次被抽中时生成的货架库存", "1", "16");
            if (args.length == 6 && isRandom) return Arrays.asList("! >>> [ 维度限制 ] 该物品仅在指定维度池刷新, -1为全服通用", "-1", "10");
        }

        // 2. give 或 open 指令逻辑
        if (sub.equals("give") || sub.equals("open")) {
            if (args.length == 2) {
                List<String> players = Bukkit.getOnlinePlayers().stream()
                        .map(Player::getName)
                        .collect(java.util.stream.Collectors.toList());
                // 使用 ! 确保置顶
                players.add(0, "! >>> [ 选择玩家 ] 请选择或输入目标玩家的ID");
                return players;
            }
            if (args.length == 3) return Arrays.asList("! >>> [ 商店编号 ] 输入对应的维度ID开启特定商店", "0", "10");
        }

        // 3. admin 指令逻辑
        if (sub.equals("admin")) {
            if (args.length == 2) return Arrays.asList("! >>> [ 资金操作 ] add=增加 / set=统一设为 / take=扣除", "add", "set", "take");
            if (args.length == 3) {
                List<String> players = Bukkit.getOnlinePlayers().stream()
                        .map(Player::getName)
                        .collect(java.util.stream.Collectors.toList());
                players.add(0, "! >>> [ 目标玩家 ] 请选择要操作资金的对象");
                return players;
            }
            if (args.length == 4) return Arrays.asList("! >>> [ 输入金额 ] 请输入要操作的数字额度", "100", "1000");
        }

        // 4. reload 指令逻辑
        if (sub.equals("reload")) {
            return Arrays.asList("! >>> [ 立即生效 ] 重载所有yml配置文件");
        }

        return new ArrayList<>();
    }
}
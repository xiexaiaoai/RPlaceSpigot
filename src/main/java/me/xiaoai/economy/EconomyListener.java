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
import org.bukkit.configuration.file.YamlConfiguration;
import java.io.File;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
/**
 * 经济系统监听器中心
 * 负责处理所有与经济相关的玩家交互，包括：
 * 1. 手持“维度钟”触发的商店开启逻辑。
 * 2. GUI 界面内的点击、导航、翻页。
 * 3. 核心买卖交易逻辑（包含限购检测、余额变动与物品分发）。
 */
public class EconomyListener implements Listener {

    private final HashMap<UUID, Integer> pageMap = new HashMap<>();
    private final HashMap<UUID, Integer> rowMap = new HashMap<>();
    private final HashMap<UUID, Integer> resetClickMap = new HashMap<>();
    private final HashMap<UUID, Long> resetLastTimeMap = new HashMap<>();

    /**
     * 维度钟交互处理器
     * 监听右键点击动作，通过解析物品 Lore 中的 STORE_ID 标识符来判断开启哪个维度的商店。
     * 内置了颜色代码剥离逻辑，确保 ID 提取的跨版本稳定性。
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
                    // 容错处理：若当前行解析失败，继续匹配下一行
                }
            }
        }
    }

    /**
     * GUI 点击事件总调度
     * 拦截所有标题带 §0 的界面点击。流程分为三步：
     * 1. 状态预检（获取当前页码、维度及手持锁定状态）。
     * 2. 导航控制（处理侧边栏切换与翻页）。
     * 3. 交易处理（计算价格、检查限购并更新 YML 数据）。
     */
    @EventHandler
    public void onGuiClick(org.bukkit.event.inventory.InventoryClickEvent event) {
        String title = event.getView().getTitle();

        if (title.equals("§0请放入要佩戴的物品")) return;
        if (!title.contains("§0")) return;

        event.setCancelled(true);

        int slot = event.getRawSlot();
        if (slot < 0 || slot >= 54) return;

        org.bukkit.entity.Player player = (org.bukkit.entity.Player) event.getWhoClicked();
        java.util.UUID uuid = player.getUniqueId();
        me.xiaoai.economy.gui.EconomyMainMenu menu = new me.xiaoai.economy.gui.EconomyMainMenu();

        int curPage = pageMap.getOrDefault(uuid, 0);
        int curRow = rowMap.getOrDefault(uuid, 0);
        int heldDimId = getHeldDimensionId(player);

        // org.bukkit.Bukkit.broadcastMessage("§e§l调试 >> §f当前行(Row): " + curRow + " | 点击槽位: " + slot);

        if (curRow == 1) {
            if (slot == 10 || slot == 13 || slot == 16 || slot == 30 || slot == 32) {
                if (me.xiaoai.economy.AccountCenter.handleClick(player, slot)) return;
            }
            if (slot % 9 < 7) return;
        }

        if (slot == 17 || slot == 26 || slot == 35 || slot == 44) {
            if (slot == 44) {
                rowMap.put(uuid, 99);
                pageMap.put(uuid, 0);
                menu.open(player, 0, 99);
                player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1, 1.2f);
            } else {
                handleNavigationClick(event, player, uuid, slot, curRow, heldDimId, menu);
            }
            return;
        }

        if (slot == 53) {
            handlePagination(event, player, uuid, curPage, curRow, menu);
            return;
        }

        if (curRow == 99) {
            if (slot == 49) {
                player.sendMessage("§a§l进化 >> §f属性已重置！");
                player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1, 1.0f);
                return;
            }

            if ((slot == 29 || slot == 33) && event.getCurrentItem() != null) {
                handleEvolutionUpgrade(player, slot);
                menu.open(player, 0, 99);
            }
            return;
        }

        if (slot % 9 < 7 && event.getCurrentItem() != null && event.getCurrentItem().getType() != org.bukkit.Material.AIR) {
            processTrade(player, uuid, slot, curPage, curRow, event.getClick(), menu);
        }
    }

    private void handleEvolutionUpgrade(Player player, int slot) {
        me.xiaoai.economy.EconomyCore core = me.xiaoai.economy.EconomyCore.getInstance();
        java.util.UUID uuid = player.getUniqueId();

        int currentLv = core.getConfig().getInt("player_data." + uuid + ".stats.level", 1);
        int nextLv = currentLv + 1;

        long cost = (slot == 29) ? getMaxUpgradeCost(nextLv) : getSpeedUpgradeCost(nextLv);

        if (cost <= 0) {
            player.sendMessage("§c§l进化 >> §7等级已达上限或配置错误。");
            return;
        }

        if (core.getBalance(uuid) >= cost) {
            if (core.takeBalance(uuid, cost)) {
                core.getConfig().set("player_data." + uuid + ".stats.level", nextLv);
                core.saveCustomConfig();
                player.sendMessage("§a§l进化 >> §f升级成功！当前等级: §e" + nextLv);
                player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 1, 1.2f);
            }
        } else {
            sendBalanceLowMessage(player, cost);
        }
    }


    /**
     * [总结] 发送余额不足的提示给玩家。
     * [具体流程]
     * 1. 接收玩家对象和升级所需的花费金额。
     * 2. 向玩家发送一段带有颜色格式的字符串，显示所需花费的金币数量。
     * 3. 播放村民摇头拒绝的音效提示玩家购买失败。
     */
    private void sendBalanceLowMessage(Player p, long cost) {
        p.sendMessage("§c§l进化 >> §7升级所需资金不足！(需要 §e" + String.format("%,d", cost) + " §7金币)");
        p.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_VILLAGER_NO, 1, 1);
    }

    /**
     * [总结] 获取指定等级的体力上限升级所需金额。
     * [具体流程]
     * 1. 定义一个包含了各等级升级费用的静态数组。
     * 2. 检查传入的目标等级（nextLv）是否在合法区间内（0-10）。
     * 3. 如果合法，则将其作为索引返回数组中的指定金额，否则返回 0 容错处理。
     */
    private long getMaxUpgradeCost(int nextLv) {
        long[] costs = {0, 0, 5000, 15000, 40000, 90000, 180000, 350000, 600000, 900000, 1200000};
        return (nextLv >= 0 && nextLv <= 10) ? costs[nextLv] : 0;
    }

    /**
     * [总结] 获取指定等级的恢复速率升级所需金额。
     * [具体流程]
     * 1. 定义一个包含了各等级升级费用的静态数组（价格不同于体力升级）。
     * 2. 检查传入的目标等级（nextLv）是否在 0-10 区间内。
     * 3. 合法即提取并返回该对应索引位上的价格数字，否则安全返回 0。
     */
    private long getSpeedUpgradeCost(int nextLv) {
        long[] costs = {0, 0, 8000, 25000, 60000, 150000, 300000, 550000, 900000, 1300000, 1800000};
        return (nextLv >= 0 && nextLv <= 10) ? costs[nextLv] : 0;
    }

    /**
     * [总结] 解析玩家手持物品，尝试提取绑定的维度ID。
     * [具体流程]
     * 1. 获取玩家主手持有的物品，检查其是否为时钟（Material.CLOCK）并且是否包含物品元数据(ItemMeta)。
     * 2. 如果满足，提取出该物品的所有 Lore(说明文本)。
     * 3. 逐行遍历并去除文本的颜色代码，搜索是否包含 "STORE_ID:" 关键字。
     * 4. 如果找到对应行，将冒号后面的字符串截取并转换为整型数字返回，解析失败或者没有绑定则返回 -1。
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
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
        }
        return -1;
    }

    /**
     * [总结] 处理 GUI 界面右侧功能导航栏的点击逻辑。
     * [具体流程]
     * 1. 获取被点击导航图标的 Lore 中的 "STORE_ID" (图标代表的维度ID) 和 "ANCHOR_DIM" (主界面的锚点维度ID)。
     * 2. 判断点击的具体槽位以决定跳转的下个页面行号（nextRow）：
     * - 17号位(主页): 如果手持特定维度钟回特定维度，如果在查询页则回锚点，否则去图标维度。
     * - 26号位(眼睛): 固定跳转至 ID 为 1 的界面（通常为个人余额与流水界面）。
     * - 35号位(随机): 如果手持维度钟则跳转到该维度的专属随机池(维度ID+10000)，否则跳转到图标所在的随机池。
     * 3. 将目标页码(pageMap)置为 0，并将计算出的 nextRow 更新至玩家数据中。
     * 4. 调用 menu.open 打开指定的目标页面渲染给玩家。
     */
    private void handleNavigationClick(org.bukkit.event.inventory.InventoryClickEvent event, Player player, UUID uuid, int slot, int curRow, int heldDimId, EconomyMainMenu menu) {
        int nextRow = 0;
        ItemStack clicked = event.getCurrentItem();
        int iconDimId = 0;
        int anchorDimId = 0;

        if (clicked != null && clicked.hasItemMeta() && clicked.getItemMeta().getLore() != null) {
            for (String line : clicked.getItemMeta().getLore()) {
                String plain = org.bukkit.ChatColor.stripColor(line);
                if (plain.contains("STORE_ID:")) iconDimId = Integer.parseInt(plain.split(":")[1].trim());
                if (plain.contains("ANCHOR_DIM:")) anchorDimId = Integer.parseInt(plain.split(":")[1].trim());
            }
        }

        if (slot == 17) {
            if (heldDimId != -1) nextRow = heldDimId;
            else if (curRow == 1) nextRow = anchorDimId;
            else nextRow = iconDimId;
        } else if (slot == 26) {
            nextRow = 1;
        } else if (slot == 35) {
            nextRow = (heldDimId != -1) ? (heldDimId + 10000) : iconDimId;
        }

        pageMap.put(uuid, 0);
        rowMap.put(uuid, nextRow);
        menu.open(player, 0, nextRow);
    }

    /**
     * [总结] 处理商店内物品列表的翻页交互。
     * [具体流程]
     * 1. 过滤判断当前页面是否允许翻页。0(全球市场)和大于2且小于10000的普通维度商店可以翻页。
     * 2. 检测玩家是按左键还是右键来控制页码加减（左键上一页，使用 Math.max 确保最低页为 0，右键下一页）。
     * 3. 将计算出的新页码覆盖保存到玩家的 pageMap 记录中。
     * 4. 基于相同的行号(curRow)与新的页码(nextPage)重新打开 GUI。
     */
    private void handlePagination(org.bukkit.event.inventory.InventoryClickEvent event, Player player, UUID uuid, int curPage, int curRow, EconomyMainMenu menu) {
        if (curRow == 0 || (curRow > 2 && curRow < 10000)) {
            int nextPage = (event.getClick() == org.bukkit.event.inventory.ClickType.LEFT) ? Math.max(0, curPage - 1) : curPage + 1;
            pageMap.put(uuid, nextPage);
            menu.open(player, nextPage, curRow);
        }
    }

    /**
     * [总结] 玩家点击物品后触发的实际购买或出售的核心交易机制。
     * [具体流程]
     * 1. 路径判定：根据 curRow 推导出要读取 Config 中的哪个物品列表路径（市场/随机池/维度商店）。
     * 2. 定位物品：调用 getItemKeyBySlot 将点击槽位转化为 Config 的键名，读取目标物品段落并获取价格、库存限制及物品堆数据。
     * 3. 数量判定：判断是否处于随机商店内（仅限买1个）以及玩家的点击方式（右键可批量买64个，其他默认为1个）。
     * 4. 限购拦截：读取该玩家的购买历史记录，如配置了最高限购量且超限则阻断交易。
     * 5. 执行交易：
     * - [如果是负价(系统回收)]: 检查玩家背包是否有足够该物品。有的话扣除对应物品并给玩家增加金币。
     * - [如果是正价(系统出售)]: 检查玩家余额是否充足。足够的话扣除金币，并把物品存入玩家背包。
     * 6. 数据写入：交易发生后，把玩家累积限购量写回 Config 并执行保存。
     * 7. 刷新反馈：播放成功音效，重新刷新并打开当前 GUI 页面以展现最新的余额和库存状态。
     */
    private void processTrade(Player player, UUID uuid, int slot, int curPage, int curRow, org.bukkit.event.inventory.ClickType click, EconomyMainMenu menu) {
        EconomyCore core = EconomyCore.getInstance();
        String path;
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

        boolean isRandom = (curRow == 2 || curRow >= 10000);
        int amount = isRandom ? 1 : ((click == org.bukkit.event.inventory.ClickType.RIGHT) ? 64 : 1);
        String dataPath = "player_data." + uuid;
        int alreadyBought = core.getConfig().getInt(dataPath + ".history." + itemKey, 0);

        if (maxLimit != -1 && (alreadyBought + amount) > maxLimit) {
            player.sendMessage("§c§l[!] §c购买失败：已达到个人限额。");
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
            return;
        }

        boolean transactionOccurred = false;
        if (unitPrice < 0) {
            double income = Math.abs(unitPrice) * amount;
            if (player.getInventory().containsAtLeast(shopItem, amount)) {
                ItemStack toRemove = shopItem.clone();
                toRemove.setAmount(amount);
                player.getInventory().removeItem(toRemove);
                core.addBalance(uuid, income);
                transactionOccurred = true;
                player.sendMessage("§a§l[✔] §f出售成功！收益: §e" + income);
            } else {
                player.sendMessage("§c§l[!] §c物品不足！");
            }
        } else {
            double total = unitPrice * amount;
            if (core.getBalance(uuid) >= total) {
                if (core.takeBalance(uuid, total)) {
                    ItemStack toGive = shopItem.clone();
                    toGive.setAmount(amount);
                    player.getInventory().addItem(toGive);
                    transactionOccurred = true;
                    player.sendMessage("§a§l[✔] §a购买成功！支出: §e" + total);
                }
            } else {
                player.sendMessage("§c§l[!] §c余额不足！还差: §e" + (total - core.getBalance(uuid)));
            }
        }

        if (transactionOccurred) {
            core.getConfig().set(dataPath + ".name", player.getName());
            core.getConfig().set(dataPath + ".history." + itemKey, alreadyBought + amount);
            core.saveCustomConfig();
            player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
            menu.open(player, curPage, curRow);
        }
    }

    /**
     * [总结] 检查随机商店倒计时并在到达时间时执行全体刷新。
     * [具体流程]
     * 1. 获取系统配置中的 "last_refresh" 上次刷新时间的时间戳。
     * 2. 调用 parseIntervalToMillis 把配置里的倒计时字符串(如"1h")转换为毫秒数。
     * 3. 比较 当前时间 减去 上次刷新时间，如果大于等于设定的间隔毫秒数，说明需要刷新了。
     * 4. 调用 refreshRandomShopPool 抽取重构新的随机商品池。
     * 5. 将配置文件中的 "player_data" 节点直接清空，以此来清除所有玩家针对此商店买入数量的限购限制。
     * 6. 更新 "last_refresh" 为当前时间，保存配置并向全服广播商店翻新公告。
     */
    private void checkAndRefreshShop() {
        EconomyCore core = EconomyCore.getInstance();
        long lastRefresh = core.getConfig().getLong("shop_settings.last_refresh", 0);
        long currentTime = System.currentTimeMillis();
        String intervalStr = core.getConfig().getString("shop_settings.refresh_interval", "1h");
        long intervalMillis = parseIntervalToMillis(intervalStr);

        if (currentTime - lastRefresh >= intervalMillis) {
            refreshRandomShopPool(core);
            core.getConfig().set("player_data", null);
            core.getConfig().set("shop_settings.last_refresh", currentTime);
            core.saveCustomConfig();
            org.bukkit.Bukkit.broadcastMessage("§a§l[经济系统] §f商店货架已翻新！");
        }
    }

    /**
     * [总结] 将人类可读的短时间字符串转化为计算机用毫秒时间戳。
     * [具体流程]
     * 1. 分别提取输入字符串（例如"1d", "3h", "30m"）的最后一位字符作为单位(s/m/h/d)以及前面的纯数字部分。
     * 2. 根据获取的单位使用 switch case 把对应的时间数字乘上秒/分/时/天的进位乘数再乘 1000 转为毫秒。
     * 3. 任何解析失败异常都会通过 catch 捕获，并默认返回 1 小时 (3600000L) 作为容错降级方案。
     */
    private long parseIntervalToMillis(String interval) {
        try {
            String unit = interval.substring(interval.length() - 1).toLowerCase();
            long value = Long.parseLong(interval.substring(0, interval.length() - 1));
            switch (unit) {
                case "s":
                    return value * 1000L;
                case "m":
                    return value * 60 * 1000L;
                case "h":
                    return value * 3600 * 1000L;
                case "d":
                    return value * 24 * 3600 * 1000L;
                default:
                    return 3600000L;
            }
        } catch (Exception e) {
            return 3600000L;
        }
    }

    /**
     * [总结] 从完整的大随机池资源中打乱顺序抽取一部分商品放入上架的随机池。
     * [具体流程]
     * 1. 尝试读取配置文件里负责存放所有随机库底层数据的 "random_pool_source" 节点。如果为空则终止。
     * 2. 获取下面所有的键值对 Key，并放入一个 ArrayList 里。
     * 3. 使用 Collections.shuffle 将整个 ArrayList 的物品顺序完全打乱。
     * 4. 从配置中获取每次应当刷新上架的物品下限 (min) 和 上限 (max)，随机在这个区间内 roll 一个总数量 (amountToPick)。
     * 5. 清除原有的展示商品节点 "random_shop.pool"。
     * 6. 使用 for 循环将刚才打乱列表中的前 N 个物品重新写入进展示节点中完成刷新动作。
     */
    private void refreshRandomShopPool(EconomyCore core) {
        ConfigurationSection source = core.getConfig().getConfigurationSection("random_pool_source");
        if (source == null) return;
        List<String> allKeys = new ArrayList<>(source.getKeys(false));
        if (allKeys.isEmpty()) return;
        java.util.Collections.shuffle(allKeys);
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
     * [总结] 界面槽位到配置文件键名的转换器(消除侧边栏空白偏移)。
     * [具体流程]
     * 1. 根据传入的路径从配置中提取该节点下的全部物品名称列表（顺序存入 List 集合）。
     * 2. 将玩家点击的从 0 开始的一维 54 格 slot 计算转换为其在矩阵中的 row(行) 与 col(列)。
     * 3. 因为 GUI 的最右两列被固定导航栏和边框占据，展示区实际宽为 7，将行列转换为展示区的 0~41 内部索引 (clickedIndex)。
     * 4. 检查是否为支持翻页的市场界面，如果是，则用页码乘以单页的 42 格算出跨页距，相加得出在 List 集合中的最终绝对索引(finalIndex)。
     * 5. 若此索引处于合法区间，则从 List 里提取出对应配置键名字符串返回，否则返回 null 供外部丢弃操作。
     */
    private String getItemKeyBySlot(String path, int page, int slot) {
        ConfigurationSection section = EconomyCore.getInstance().getConfig().getConfigurationSection(path);
        if (section == null) return null;
        List<String> keys = new ArrayList<>(section.getKeys(false));
        int row = slot / 9, col = slot % 9;
        int clickedIndex = (row * 7) + col;
        int finalIndex = (path.contains("market")) ? (page * 42) + clickedIndex : clickedIndex;
        if (finalIndex >= 0 && finalIndex < keys.size()) return keys.get(finalIndex);
        return null;
    }
// ================= [ 新增：处理戴在头上的箱子逻辑 ] =================

    // ================= [ 处理戴在头上的箱子逻辑 ] =================

    @EventHandler
    public void onHeadGearClose(org.bukkit.event.inventory.InventoryCloseEvent event) {
        String title = event.getView().getTitle();
        org.bukkit.entity.Player player = (org.bukkit.entity.Player) event.getPlayer();

        if (!title.equals("§0请放入要佩戴的物品")) return;

        org.bukkit.inventory.Inventory inv = event.getInventory();
        ItemStack itemToWear = inv.getItem(4);

        if (itemToWear == null || itemToWear.getType() == org.bukkit.Material.AIR) return;

        try {
            ItemStack oldHelmet = player.getInventory().getHelmet();
            player.getInventory().setHelmet(itemToWear.clone());
            inv.setItem(4, null);

            if (oldHelmet != null && oldHelmet.getType() != org.bukkit.Material.AIR) {
                player.getInventory().addItem(oldHelmet);
            }
            player.sendMessage("§a§lRPlace >> §f穿戴成功！");
        } catch (Exception e) {}
    }

    @EventHandler
    public void onHeadGearClick(org.bukkit.event.inventory.InventoryClickEvent event) {
        if (!event.getView().getTitle().equals("§0请放入要佩戴的物品")) return;

        int slot = event.getRawSlot();
        if (slot >= 0 && slot < 9 && slot != 4) {
            event.setCancelled(true);
        }
    }
}
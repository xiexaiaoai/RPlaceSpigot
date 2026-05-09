package me.xiaoai.rplace;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scoreboard.*;

import java.util.HashSet;
import java.util.UUID;

public class ScoreboardManager {

    private final RPlace plugin;
    public final HashSet<UUID> enabledPlayers = new HashSet<>();

    public ScoreboardManager(RPlace plugin) {
        this.plugin = plugin;
        startUpdateTask();
    }

    private void startUpdateTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    if (enabledPlayers.contains(player.getUniqueId())) {
                        updateScoreboard(player);
                    }
                }
            }
        }.runTaskTimer(plugin, 20L, 20L);
    }

    public void updateScoreboard(Player player) {
        Scoreboard board = player.getScoreboard();
        Objective obj = board.getObjective("rplace");

        if (obj == null || !obj.getName().equals("rplace")) {
            board = Bukkit.getScoreboardManager().getNewScoreboard();
            obj = board.registerNewObjective("rplace", "dummy", "§6§lRPlace 画布状态");
            obj.setDisplaySlot(DisplaySlot.SIDEBAR);
        }

        UUID uuid = player.getUniqueId();
        int current = plugin.getRealTimePoints(player);
        int max = plugin.maxPointsMap.getOrDefault(uuid, plugin.defaultMaxPoints);
        long cooldown = plugin.getCooldown(player);

        updateLine(board, obj, "§7----------------", 4);
        updateLine(board, obj, "§f剩余次数: §a" + current + " §7/ §2" + max, 3);

        if (current >= max) {
            updateLine(board, obj, "§f恢复进度: §e已满载", 2);
        } else {
            updateLine(board, obj, "§f恢复倒计时: §e" + cooldown + "秒", 2);
        }

        updateLine(board, obj, "§7---------------- ", 1);
        updateLine(board, obj, "§8输入 /rp board 关闭", 0);

        if (player.getScoreboard() != board) {
            player.setScoreboard(board);
        }
    }

    private void updateLine(Scoreboard board, Objective obj, String text, int score) {
        for (String entry : board.getEntries()) {
            if (obj.getScore(entry).getScore() == score && !entry.equals(text)) {
                board.resetScores(entry);
            }
        }
        obj.getScore(text).setScore(score);
    }

    public void toggle(Player player) {
        UUID uuid = player.getUniqueId();
        if (enabledPlayers.contains(uuid)) {
            enabledPlayers.remove(uuid);
            player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
            player.sendMessage("§a[RPlace] 个人状态记分板已隐藏。");
        } else {
            enabledPlayers.add(uuid);
            updateScoreboard(player);
            player.sendMessage("§a[RPlace] 个人状态记分板已开启。");
        }
    }
}
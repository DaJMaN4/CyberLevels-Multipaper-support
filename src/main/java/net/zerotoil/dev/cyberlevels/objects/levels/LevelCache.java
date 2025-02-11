package net.zerotoil.dev.cyberlevels.objects.levels;

import com.github.puregero.multilib.MultiLib;
import net.zerotoil.dev.cyberlevels.CyberLevels;
import net.zerotoil.dev.cyberlevels.objects.MySQL;
import net.zerotoil.dev.cyberlevels.objects.RewardObject;
import net.zerotoil.dev.cyberlevels.objects.leaderboard.Leaderboard;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.io.BufferedWriter;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

public class LevelCache {

    private final CyberLevels main;

    private final Long startLevel;
    private final Double startExp;
    private final Long maxLevel;

    private BukkitTask autoSave;

    private final Map<Player, PlayerData> playerLevels;
    private Map<Long, LevelData> levelData;
    private Leaderboard leaderboard;

    private final boolean doCommandMultiplier;
    private final boolean doEventMultiplier;

    private final boolean addLevelReward;

    private final boolean leaderboardEnabled;
    private final boolean syncLeaderboardAutoSave;
    private final boolean leaderboardInstantUpdate;

    private final boolean preventDuplicateRewards;
    private final boolean stackComboExp;

    private final boolean messageAutoSave;
    private final boolean messageConsole;

    private Integer watchdog = 0;

    private MySQL mySQL;

    public LevelCache(CyberLevels main) {
        this.main = main;
        Configuration levelsYML = main.levelUtils().levelsYML();
        startLevel = levelsYML.getLong("levels.starting.level");
        startExp = levelsYML.getDouble("levels.starting.experience");
        maxLevel = levelsYML.getLong("levels.maximum.level");
        Configuration config = main.files().getConfig("config");
        doCommandMultiplier = config.getBoolean("config.multipliers.commands", false);
        doEventMultiplier = config.getBoolean("config.multipliers.events", true);
        addLevelReward = config.getBoolean("config.add-level-reward", false);
        leaderboardEnabled = config.getBoolean("config.leaderboard.enabled", false);
        syncLeaderboardAutoSave = config.getBoolean("config.leaderboard.sync-on-auto-save", true) && leaderboardEnabled;
        leaderboardInstantUpdate = config.getBoolean("config.leaderboard.instant-update", false) && leaderboardEnabled;
        preventDuplicateRewards = config.getBoolean("config.prevent-duplicate-rewards", false);
        stackComboExp = config.getBoolean("config.stack-combo-exp", true);
        messageAutoSave = config.getBoolean("config.messages.auto-save", true);
        messageConsole = config.getBoolean("config.messages.message-console", true);

        playerLevels = new HashMap<>();
        clearLevelData();
        startAutoSave();
        if (config.getBoolean("config.mysql.enabled")) {
            try {
                mySQL = new MySQL(main, new String[]{
                        config.getString("config.mysql.host"),
                        config.getString("config.mysql.port"),
                        config.getString("config.mysql.database"),
                        config.getString("config.mysql.username"),
                        config.getString("config.mysql.password"),
                        config.getString("config.mysql.table")},
                        config.getBoolean("config.mysql.ssl"));
            } catch (Exception e) {
                mySQL = null;
                main.logger("&dSwitched to flat-file storage.", "");
            }
        }
    }

    public void loadLevelData() {

        main.logger("&dLoading level data...");
        long startTime = System.currentTimeMillis();

        ConfigurationSection levelSection = main.files().getConfig("levels").getConfigurationSection("levels.experience.level");
        Set<String> levels = new HashSet<>();
        if (levelSection != null) levels = levelSection.getKeys(false);

        long l = startLevel;

        while (l <= maxLevel) {
            levelData.put(l, new LevelData(main, l));
            levels.remove(l + "");
            l++;
        }

        main.logger("&7Loaded &e" + (l - startLevel) + " &7level(s) in &a" + (System.currentTimeMillis() - startTime) + "ms&7.", "");

    }

    public void loadLeaderboard() {

        if (!leaderboardEnabled) return;
        main.logger("&dLoading leaderboard data...");
        long startTime = System.currentTimeMillis();

        leaderboard = new Leaderboard(main);

        main.logger("&7Loaded &e" + leaderboard.getTopTenPlayers().size() + " &7players in &a" + (System.currentTimeMillis() - startTime) + "ms&7.", "");
    }

    public void loadRewards() {
        if (!main.files().getConfig("rewards").isConfigurationSection("rewards")) return;
        main.logger("&dLoading reward data...");
        long startTime = System.currentTimeMillis(), counter = 0;
        for (String s : main.files().getConfig("rewards").getConfigurationSection("rewards").getKeys(false)) {
            new RewardObject(main, s);
            counter++;
        }
        main.logger("&7Loaded &e" + counter + " &7reward(s) in &a" + (System.currentTimeMillis() - startTime) + "ms&7.", "");

    }

    public void cancelAutoSave() {
        if (autoSave == null) return;
        autoSave.cancel();
        autoSave = null;
    }

    public void startAutoSave() {
        if (!main.files().getConfig("config").getBoolean("config.auto-save.enabled")) return;
        autoSave = (new BukkitRunnable() {
            @Override
            public void run() {
                long startTime = System.currentTimeMillis();
                saveOnlinePlayers(false);
                Bukkit.getScheduler().runTaskAsynchronously(main, () -> {
                    if (syncLeaderboardAutoSave) leaderboard.updateLeaderboard();
                    if (messageAutoSave) main.langUtils().sendMixed(null, main.files().getConfig("lang")
                            .getString("messages.auto-save")
                            .replace("{ms}", (System.currentTimeMillis() - startTime) + ""));
                    startAutoSave();
                });
            }
        }).runTaskLater(main, 20L * Math.max(1, main.files().getConfig("config").getLong("config.auto-save.interval")));
    }

    public void clearLevelData() {
        levelData = new HashMap<>();
    }

    public void loadPlayer(Player player) {
        PlayerData playerData;
        playerData = mySQL.getPlayerData(player);
        playerLevels.put(player, playerData);
    }

    public void loadExternalPlayer(UUID data) {
        watchdog++;
        if (watchdog > 5) {
            watchdog = 0;
            Bukkit.getConsoleSender().sendMessage(ChatColor.RED + "Player with UUID " + data.toString() + " could not be found." +
                    "Check your connection to external servers.");
            return;
        }
        Bukkit.getScheduler().runTaskLater(main, new Runnable() {
            @Override
            public void run() {
                for (Player player : MultiLib.getAllOnlinePlayers()) {
                    if (player.getUniqueId().equals(data)) {
                        main.levelCache().loadPlayer(player);
                        loadPlayer(player);
                        if (playerLevels.get(player) != null) {
                            return;
                        }
                    }
                }
                loadExternalPlayer(data);
            }
        }, 20L);
    }

    public void updateExternalPlayer(Player player) {
        mySQL.updatePlayer(player);
    }

    public void updateLocalPlayer(Player player) {
        PlayerData playerData;
        playerData = mySQL.getPlayerData(player);
        playerLevels.put(player, playerData);
    }

    public void savePlayer(Player player, boolean clearData) {

        PlayerData playerData = playerLevels.get(player);
        String uuid = player.getUniqueId().toString();
        if (mySQL == null) {
            try {
                String content = playerData.getLevel() + "\n" + main.levelUtils().roundStringDecimal(playerData.getExp()) + "\n" + playerData.getMaxLevel();
                BufferedWriter writer = Files.newBufferedWriter(Paths.get(main.getDataFolder().getAbsolutePath() + File.separator + "player_data" + File.separator + uuid + ".clv"));
                writer.write(content);
                writer.close();
            } catch (Exception e) {
                main.logger("&cFailed to save data for " + player.getName() + ".");
            }
        }
        else mySQL.updatePlayer(player);
        if (clearData) playerLevels.remove(player);
    }

    public void loadOnlinePlayers() {
        if (MultiLib.getAllOnlinePlayers().isEmpty()) return;
        main.logger("&dLoading data for online players...");
        long startTime = System.currentTimeMillis(), counter = 0;
        for (Player player : MultiLib.getAllOnlinePlayers()) {
            loadPlayer(player);
            counter++;
        }
        main.logger("&7Loaded data for &e" + counter + " &7online player(s) in &a" + (System.currentTimeMillis() - startTime) + "ms&7.", "");
    }

    public void saveOnlinePlayers(boolean clearData) {
        for (Player player : MultiLib.getAllOnlinePlayers())
            savePlayer(player, clearData);
    }

    public Long startLevel() {
        return startLevel;
    }

    public Double startExp() {
        return startExp;
    }

    public Long maxLevel() {
        return maxLevel;
    }

    public Map<Player, PlayerData> playerLevels() {
        return playerLevels;
    }

    public Map<Long, LevelData> levelData() {
        return levelData;
    }

    public MySQL getMySQL() {
        return mySQL;
    }

    public boolean doCommandMultiplier() {
        return doCommandMultiplier;
    }

    public boolean doEventMultiplier() {
        return doEventMultiplier;
    }

    public boolean addLevelReward() {
        return addLevelReward;
    }

    public Leaderboard getLeaderboard() {
        return leaderboard;
    }

    public boolean isLeaderboardEnabled() {
        return leaderboardEnabled;
    }

    public boolean isLeaderboardInstantUpdate() {
        return leaderboardInstantUpdate;
    }

    public boolean isStackComboExp() {
        return stackComboExp;
    }

    public boolean isPreventDuplicateRewards() {
        return preventDuplicateRewards;
    }

    public boolean isMessageConsole() {
        return messageConsole;
    }

}

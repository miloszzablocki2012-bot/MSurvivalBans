package pl.msurvival.bans;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.Locale;

public final class MSurvivalBans extends JavaPlugin implements Listener {

    private File bansFile;
    private FileConfiguration bans;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadBans();
        Bukkit.getPluginManager().registerEvents(this, this);
        registerCommands();
        getLogger().info("MSurvivalBans wlaczony!");
    }

    private void registerCommands() {
        getCommand("tempban").setExecutor((sender, command, label, args) -> {
            if (!sender.hasPermission("msurvivalbans.admin")) {
                sender.sendMessage(msg("no-permission"));
                return true;
            }
            if (args.length < 3) {
                sender.sendMessage(msg("usage-tempban"));
                return true;
            }
            long duration = parseDurationMillis(args[1]);
            if (duration <= 0) {
                sender.sendMessage(color("&cZły czas. Użyj np. 10m, 2h, 7d, 1w."));
                return true;
            }
            String name = args[0];
            String reason = join(args, 2);
            long expires = System.currentTimeMillis() + duration;
            setBan(name, reason, expires, sender.getName());
            kickIfOnline(name, reason, expires);
            sender.sendMessage(msg("tempbanned").replace("%player%", name).replace("%time%", args[1]).replace("%reason%", reason));
            return true;
        });

        getCommand("ban").setExecutor((sender, command, label, args) -> {
            if (!sender.hasPermission("msurvivalbans.admin")) {
                sender.sendMessage(msg("no-permission"));
                return true;
            }
            if (args.length < 2) {
                sender.sendMessage(msg("usage-ban"));
                return true;
            }
            String name = args[0];
            String reason = join(args, 1);
            setBan(name, reason, 0L, sender.getName());
            kickIfOnline(name, reason, 0L);
            sender.sendMessage(msg("banned").replace("%player%", name).replace("%reason%", reason));
            return true;
        });

        getCommand("unban").setExecutor((sender, command, label, args) -> {
            if (!sender.hasPermission("msurvivalbans.admin")) {
                sender.sendMessage(msg("no-permission"));
                return true;
            }
            if (args.length != 1) {
                sender.sendMessage(msg("usage-unban"));
                return true;
            }
            String name = normalize(args[0]);
            if (!bans.contains("bans." + name)) {
                sender.sendMessage(msg("not-banned"));
                return true;
            }
            bans.set("bans." + name, null);
            saveBans();
            sender.sendMessage(msg("unbanned").replace("%player%", args[0]));
            return true;
        });

        getCommand("checkban").setExecutor((sender, command, label, args) -> {
            if (!sender.hasPermission("msurvivalbans.admin")) {
                sender.sendMessage(msg("no-permission"));
                return true;
            }
            if (args.length != 1) {
                sender.sendMessage(msg("usage-checkban"));
                return true;
            }
            String path = "bans." + normalize(args[0]);
            if (!bans.contains(path) || isExpired(args[0])) {
                sender.sendMessage(msg("not-banned"));
                return true;
            }
            String reason = bans.getString(path + ".reason", "Brak powodu");
            long expires = bans.getLong(path + ".expires", 0L);
            sender.sendMessage(msg("check").replace("%player%", args[0]).replace("%reason%", reason).replace("%expires%", expiresText(expires)));
            return true;
        });

        getCommand("kick").setExecutor((sender, command, label, args) -> {
            if (!sender.hasPermission("msurvivalbans.admin")) {
                sender.sendMessage(msg("no-permission"));
                return true;
            }
            if (args.length < 2) {
                sender.sendMessage(msg("usage-kick"));
                return true;
            }
            Player target = Bukkit.getPlayerExact(args[0]);
            if (target == null) {
                sender.sendMessage(msg("player-not-online"));
                return true;
            }
            String reason = join(args, 1);
            target.kickPlayer(color(getConfig().getString("kick-screen", "&cWyrzucono: %reason%").replace("%reason%", reason)));
            sender.sendMessage(msg("kicked").replace("%player%", target.getName()).replace("%reason%", reason));
            return true;
        });
    }

    @EventHandler
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        String name = event.getName();
        if (!isBanned(name)) return;

        String path = "bans." + normalize(name);
        String reason = bans.getString(path + ".reason", "Brak powodu");
        long expires = bans.getLong(path + ".expires", 0L);
        String screen = getConfig().getString("ban-screen", "&cZbanowany\\nPowód: %reason%\\nWygasa: %expires%");
        event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, color(screen.replace("%reason%", reason).replace("%expires%", expiresText(expires))));
    }

    private void setBan(String name, String reason, long expires, String staff) {
        String path = "bans." + normalize(name);
        bans.set(path + ".name", name);
        bans.set(path + ".reason", reason);
        bans.set(path + ".expires", expires);
        bans.set(path + ".staff", staff);
        bans.set(path + ".created", System.currentTimeMillis());
        saveBans();
    }

    private boolean isBanned(String name) {
        String path = "bans." + normalize(name);
        if (!bans.contains(path)) return false;
        if (isExpired(name)) {
            bans.set(path, null);
            saveBans();
            return false;
        }
        return true;
    }

    private boolean isExpired(String name) {
        long expires = bans.getLong("bans." + normalize(name) + ".expires", 0L);
        return expires > 0 && System.currentTimeMillis() >= expires;
    }

    private void kickIfOnline(String name, String reason, long expires) {
        Player player = Bukkit.getPlayerExact(name);
        if (player == null) return;
        String screen = getConfig().getString("ban-screen", "&cZbanowany\\nPowód: %reason%\\nWygasa: %expires%");
        player.kickPlayer(color(screen.replace("%reason%", reason).replace("%expires%", expiresText(expires))));
    }

    private void loadBans() {
        bansFile = new File(getDataFolder(), "bans.yml");
        if (!bansFile.exists()) {
            try {
                getDataFolder().mkdirs();
                bansFile.createNewFile();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        bans = YamlConfiguration.loadConfiguration(bansFile);
    }

    private void saveBans() {
        try {
            bans.save(bansFile);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private long parseDurationMillis(String input) {
        if (input == null || input.length() < 2) return -1L;
        String number = input.substring(0, input.length() - 1);
        char unit = Character.toLowerCase(input.charAt(input.length() - 1));
        long amount;
        try {
            amount = Long.parseLong(number);
        } catch (NumberFormatException e) {
            return -1L;
        }
        if (amount <= 0) return -1L;
        switch (unit) {
            case 's': return amount * 1000L;
            case 'm': return amount * 60L * 1000L;
            case 'h': return amount * 60L * 60L * 1000L;
            case 'd': return amount * 24L * 60L * 60L * 1000L;
            case 'w': return amount * 7L * 24L * 60L * 60L * 1000L;
            default: return -1L;
        }
    }

    private String expiresText(long expires) {
        if (expires <= 0) return "nigdy";
        long millis = expires - System.currentTimeMillis();
        if (millis <= 0) return "wygasł";
        long sec = millis / 1000L;
        long days = sec / 86400L;
        sec %= 86400L;
        long hours = sec / 3600L;
        sec %= 3600L;
        long minutes = sec / 60L;
        if (days > 0) return days + "d " + hours + "h";
        if (hours > 0) return hours + "h " + minutes + "m";
        if (minutes > 0) return minutes + "m";
        return sec + "s";
    }

    private String join(String[] args, int start) {
        StringBuilder builder = new StringBuilder();
        for (int i = start; i < args.length; i++) {
            if (i > start) builder.append(" ");
            builder.append(args[i]);
        }
        return builder.toString();
    }

    private String normalize(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }

    private String msg(String key) {
        return color(getConfig().getString("messages.prefix", "") + getConfig().getString("messages." + key, ""));
    }

    private String color(String text) {
        if (text == null) return "";
        return ChatColor.translateAlternateColorCodes('&', text);
    }
}

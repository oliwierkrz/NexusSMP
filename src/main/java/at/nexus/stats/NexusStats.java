package at.nexus.stats;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.*;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * NexusStats - Economy + Scoreboard [PLUGIN 1]
 * Sidebar: name, money, kills, deaths, playtime, website.
 */
public final class NexusStats extends JavaPlugin implements Listener {

    public static final TextColor BLUE_BRIGHT = TextColor.fromHexString("#4FC3F7");
    public static final TextColor BLUE_DEEP   = TextColor.fromHexString("#0D47A1");
    public static final TextColor MONEY       = TextColor.fromHexString("#00E676");
    public static final TextColor GREY        = TextColor.fromHexString("#9E9E9E");
    public static final TextColor WHITE       = TextColor.fromHexString("#FFFFFF");
    public static final TextColor REDC        = TextColor.fromHexString("#FF5252");
    public static final TextColor GOLD        = TextColor.fromHexString("#FFD54F");

    private final Map<UUID, Long> balances = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> kills  = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> deaths = new ConcurrentHashMap<>();
    private final Map<UUID, Board> boards   = new ConcurrentHashMap<>();
    private final AtomicBoolean dirty       = new AtomicBoolean(false);

    private File dataFile;
    private long startBalance;
    private BukkitTask boardTask, saveTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.startBalance = toCents(getConfig().getDouble("start-balance", 1000.0));
        this.dataFile = new File(getDataFolder(), "balances.yml");
        loadData();

        getServer().getPluginManager().registerEvents(this, this);

        this.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            var commands = event.registrar();
            commands.register("balance", "Show your balance", List.of("bal", "money"),
                    new SimpleCmd(this::cmdBalance));
            commands.register("pay", "Send money to a player", new SimpleCmd(this::cmdPay));
            commands.register("baltop", "Richest players", new SimpleCmd((s, a) -> cmdBaltop(s)));
            commands.register("eco", "Manage the economy", new SimpleCmd(this::cmdEco));
        });

        boardTask = Bukkit.getScheduler().runTaskTimer(this, this::tickBoards, 20L, 20L);
        saveTask  = Bukkit.getScheduler().runTaskTimerAsynchronously(
                        this, () -> { if (dirty.compareAndSet(true, false)) save(); }, 6000L, 6000L);

        for (Player p : Bukkit.getOnlinePlayers()) {
            ensure(p.getUniqueId());
            boards.put(p.getUniqueId(), new Board(p, this));
        }
        getLogger().info("NexusStats enabled. " + balances.size() + " accounts loaded.");
    }

    @Override
    public void onDisable() {
        if (boardTask != null) boardTask.cancel();
        if (saveTask  != null) saveTask.cancel();
        save();
        boards.values().forEach(Board::destroy);
        boards.clear();
    }

    // ── Public hooks for Sell / Shop / AH ──
    public void depositPublic(UUID id, long cents) { deposit(id, cents); }
    public boolean takePublic(UUID id, long cents) { return withdraw(id, cents); }
    public String fmtPublic(long cents) { return fmt(cents); }

    private static final class SimpleCmd implements BasicCommand {
        interface Handler { void run(CommandSender sender, String[] args); }
        private final Handler handler;
        SimpleCmd(Handler handler) { this.handler = handler; }
        @Override public void execute(@NotNull CommandSourceStack stack, @NotNull String[] args) {
            handler.run(stack.getSender(), args);
        }
    }

    private void ensure(UUID id) {
        if (balances.putIfAbsent(id, startBalance) == null) dirty.set(true);
        kills.putIfAbsent(id, 0);
        deaths.putIfAbsent(id, 0);
    }

    private long balance(UUID id) { return balances.getOrDefault(id, 0L); }

    private void deposit(UUID id, long cents) {
        if (cents <= 0) return;
        balances.merge(id, cents, Long::sum);
        dirty.set(true);
    }

    private boolean withdraw(UUID id, long cents) {
        if (cents <= 0) return true;
        final boolean[] ok = {false};
        balances.compute(id, (k, v) -> {
            long cur = (v == null) ? 0L : v;
            if (cur < cents) return cur;
            ok[0] = true;
            return cur - cents;
        });
        if (ok[0]) dirty.set(true);
        return ok[0];
    }

    public static long toCents(double d) {
        return BigDecimal.valueOf(d).setScale(2, RoundingMode.HALF_UP)
                .movePointRight(2).longValueExact();
    }

    public static String fmt(long cents) {
        double v = cents / 100.0;
        boolean neg = v < 0;
        v = Math.abs(v);
        String s;
        if      (v >= 1e12) s = trim(v / 1e12) + "T";
        else if (v >= 1e9)  s = trim(v / 1e9)  + "B";
        else if (v >= 1e6)  s = trim(v / 1e6)  + "M";
        else if (v >= 1e3)  s = trim(v / 1e3)  + "k";
        else s = (v == Math.floor(v)) ? String.valueOf((long) v) : String.format("%.2f", v);
        return neg ? "-" + s : s;
    }

    private static String trim(double d) {
        double r = Math.round(d * 100.0) / 100.0;
        if (r == Math.floor(r)) return String.valueOf((long) r);
        String s = String.format("%.2f", r);
        while (s.endsWith("0")) s = s.substring(0, s.length() - 1);
        if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        return s;
    }

    /**
     * Mixed playtime:
     *   < 1h  -> "22m 32s"
     *   < 1d  -> "2h 15m"
     *   >=1d  -> "10 days"
     */
    public static String playtime(Player p) {
        int ticks = p.getStatistic(Statistic.PLAY_ONE_MINUTE);
        long totalSec = ticks / 20L;
        long days = totalSec / 86400L;
        if (days >= 1) return days + (days == 1 ? " day" : " days");
        long hours = totalSec / 3600L;
        long mins  = (totalSec % 3600L) / 60L;
        long secs  = totalSec % 60L;
        if (hours >= 1) return hours + "h " + mins + "m";
        return mins + "m " + secs + "s";
    }

    public static long parseAmount(String in) {
        if (in == null || in.isBlank()) return -1;
        String s = in.trim().toLowerCase().replace(",", ".").replace("$", "");
        double mult = 1.0;
        switch (s.charAt(s.length() - 1)) {
            case 'k' -> { mult = 1e3;  s = s.substring(0, s.length() - 1); }
            case 'm' -> { mult = 1e6;  s = s.substring(0, s.length() - 1); }
            case 'b' -> { mult = 1e9;  s = s.substring(0, s.length() - 1); }
            case 't' -> { mult = 1e12; s = s.substring(0, s.length() - 1); }
            default  -> { }
        }
        try {
            double v = Double.parseDouble(s) * mult;
            if (v <= 0 || Double.isNaN(v) || Double.isInfinite(v) || v > 1e15) return -1;
            return toCents(v);
        } catch (NumberFormatException ex) { return -1; }
    }

    // ── Kill / Death tracking ──
    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        Player victim = e.getEntity();
        ensure(victim.getUniqueId());
        deaths.merge(victim.getUniqueId(), 1, Integer::sum);
        Player killer = victim.getKiller();
        if (killer != null && !killer.getUniqueId().equals(victim.getUniqueId())) {
            ensure(killer.getUniqueId());
            kills.merge(killer.getUniqueId(), 1, Integer::sum);
        }
        dirty.set(true);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        ensure(p.getUniqueId());
        boards.put(p.getUniqueId(), new Board(p, this));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Board b = boards.remove(e.getPlayer().getUniqueId());
        if (b != null) b.destroy();
    }

    private void tickBoards() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            Board b = boards.get(p.getUniqueId());
            if (b != null) b.update(p);
        }
    }

    private static final class Board {
        private static final String[] KEYS =
                {"§0","§1","§2","§3","§4","§5","§6","§7","§8","§9","§a"};

        private final NexusStats plugin;
        private final Scoreboard board;
        private final Objective obj;
        private final Team[] lines = new Team[KEYS.length];

        private String lastName = "", lastBal = "", lastKills = "", lastDeaths = "", lastTime = "";

        Board(Player p, NexusStats plugin) {
            this.plugin = plugin;
            this.board  = Bukkit.getScoreboardManager().getNewScoreboard();

            Component title = Component.text()
                    .append(Component.text("\u2694 ", BLUE_BRIGHT))
                    .append(Component.text("Nexus", BLUE_BRIGHT, TextDecoration.BOLD))
                    .append(Component.text("SMP", BLUE_DEEP, TextDecoration.BOLD))
                    .append(Component.text(" \u2694", BLUE_BRIGHT)).build();

            this.obj = board.registerNewObjective("nexus", Criteria.DUMMY, title);
            obj.setDisplaySlot(DisplaySlot.SIDEBAR);

            for (int i = 0; i < KEYS.length; i++) {
                Team t = board.registerNewTeam("l" + i);
                t.addEntry(KEYS[i]);
                lines[i] = t;
                obj.getScore(KEYS[i]).setScore(KEYS.length - i);
            }

            // static lines
            set(0, Component.empty());
            // 1 = name (dynamic)
            // 2 = money (dynamic)
            // 3 = kills (dynamic)
            // 4 = deaths (dynamic)
            // 5 = playtime (dynamic)
            set(6, Component.empty());
            set(7, Component.text("play.nexussmp.net", GREY));
            set(8, Component.empty());
            set(9, Component.empty());
            set(10, Component.empty());

            p.setScoreboard(board);
            update(p);
        }

        private void set(int i, Component c) { lines[i].prefix(c); }

        void update(Player p) {
            String name = p.getName();
            if (!name.equals(lastName)) {
                lastName = name;
                set(1, Component.text()
                        .append(Component.text("\u2666 ", BLUE_BRIGHT))
                        .append(Component.text(name, BLUE_BRIGHT, TextDecoration.BOLD)).build());
            }

            String bal = fmt(plugin.balance(p.getUniqueId()));
            if (!bal.equals(lastBal)) {
                lastBal = bal;
                set(2, Component.text()
                        .append(Component.text("$ ", MONEY, TextDecoration.BOLD))
                        .append(Component.text("Money ", WHITE))
                        .append(Component.text(bal, MONEY, TextDecoration.BOLD)).build());
            }

            String k = String.valueOf(plugin.kills.getOrDefault(p.getUniqueId(), 0));
            if (!k.equals(lastKills)) {
                lastKills = k;
                set(3, Component.text()
                        .append(Component.text("\u2694 ", REDC))
                        .append(Component.text("Kills ", WHITE))
                        .append(Component.text(k, REDC, TextDecoration.BOLD)).build());
            }

            String d = String.valueOf(plugin.deaths.getOrDefault(p.getUniqueId(), 0));
            if (!d.equals(lastDeaths)) {
                lastDeaths = d;
                set(4, Component.text()
                        .append(Component.text("\u2620 ", GOLD))
                        .append(Component.text("Deaths ", WHITE))
                        .append(Component.text(d, GOLD, TextDecoration.BOLD)).build());
            }

            String time = playtime(p);
            if (!time.equals(lastTime)) {
                lastTime = time;
                set(5, Component.text()
                        .append(Component.text("\u23F1 ", BLUE_BRIGHT))
                        .append(Component.text("Playtime ", WHITE))
                        .append(Component.text(time, BLUE_BRIGHT)).build());
            }
        }

        void destroy() {
            try {
                obj.unregister();
                for (Team t : lines) t.unregister();
            } catch (IllegalStateException ignored) { }
        }
    }

    // ── persistence ──
    private void loadData() {
        if (!dataFile.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(dataFile);
        for (String k : y.getKeys(false)) {
            try {
                UUID id = UUID.fromString(k);
                balances.put(id, y.getLong(k + ".bal", y.getLong(k, 0)));
                kills.put(id, y.getInt(k + ".kills", 0));
                deaths.put(id, y.getInt(k + ".deaths", 0));
            } catch (IllegalArgumentException ignored) { }
        }
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (UUID id : balances.keySet()) {
            String b = id.toString();
            y.set(b + ".bal", balances.getOrDefault(id, 0L));
            y.set(b + ".kills", kills.getOrDefault(id, 0));
            y.set(b + ".deaths", deaths.getOrDefault(id, 0));
        }
        try {
            if (!getDataFolder().exists()) getDataFolder().mkdirs();
            y.save(dataFile);
        } catch (IOException ex) {
            getLogger().severe("FAILED to save balances.yml: " + ex.getMessage());
        }
    }

    // ── commands ──
    private void cmdBalance(CommandSender s, String[] a) {
        if (a.length == 0) {
            if (!(s instanceof Player p)) {
                s.sendMessage(Component.text("Usage: /balance <player>", NamedTextColor.RED)); return;
            }
            msgBal(s, "Your balance: ", balance(p.getUniqueId())); return;
        }
        OfflinePlayer t = Bukkit.getOfflinePlayer(a[0]);
        if (!t.hasPlayedBefore() && !t.isOnline()) {
            s.sendMessage(Component.text("Player not found.", NamedTextColor.RED)); return;
        }
        msgBal(s, t.getName() + ": ", balance(t.getUniqueId()));
    }

    private void cmdPay(CommandSender s, String[] a) {
        if (!(s instanceof Player p)) { s.sendMessage(Component.text("Players only.", NamedTextColor.RED)); return; }
        if (a.length < 2) { s.sendMessage(Component.text("Usage: /pay <player> <amount>", NamedTextColor.RED)); return; }
        Player t = Bukkit.getPlayerExact(a[0]);
        if (t == null) { s.sendMessage(Component.text("That player is not online.", NamedTextColor.RED)); return; }
        if (t.getUniqueId().equals(p.getUniqueId())) { s.sendMessage(Component.text("You can't pay yourself.", NamedTextColor.RED)); return; }
        long cents = parseAmount(a[1]);
        if (cents <= 0) { s.sendMessage(Component.text("Invalid amount. Example: /pay Steve 50k", NamedTextColor.RED)); return; }
        if (!withdraw(p.getUniqueId(), cents)) { s.sendMessage(Component.text("You don't have enough money.", NamedTextColor.RED)); return; }
        deposit(t.getUniqueId(), cents);
        msgBal(p, "Sent to " + t.getName() + ": ", cents);
        msgBal(t, "Received from " + p.getName() + ": ", cents);
    }

    private void cmdBaltop(CommandSender s) {
        List<Map.Entry<UUID, Long>> top = new ArrayList<>(balances.entrySet());
        top.sort(Map.Entry.<UUID, Long>comparingByValue().reversed());
        s.sendMessage(Component.text("── NexusSMP Baltop ──", BLUE_BRIGHT, TextDecoration.BOLD));
        for (int i = 0; i < Math.min(10, top.size()); i++) {
            var e = top.get(i);
            String n = Bukkit.getOfflinePlayer(e.getKey()).getName();
            s.sendMessage(Component.text()
                    .append(Component.text("#" + (i + 1) + " ", BLUE_DEEP, TextDecoration.BOLD))
                    .append(Component.text((n == null ? "?" : n) + " ", WHITE))
                    .append(Component.text("$" + fmt(e.getValue()), MONEY)).build());
        }
    }

    private void cmdEco(CommandSender s, String[] a) {
        if (!s.hasPermission("nexus.eco.admin")) { s.sendMessage(Component.text("No permission.", NamedTextColor.RED)); return; }
        if (a.length < 3) { s.sendMessage(Component.text("/eco <give|take|set> <player> <amount>", NamedTextColor.RED)); return; }
        OfflinePlayer t = Bukkit.getOfflinePlayer(a[1]);
        if (!t.hasPlayedBefore() && !t.isOnline()) { s.sendMessage(Component.text("Player not found.", NamedTextColor.RED)); return; }
        long cents = parseAmount(a[2]);
        if (cents < 0) { s.sendMessage(Component.text("Invalid amount.", NamedTextColor.RED)); return; }
        UUID id = t.getUniqueId();
        switch (a[0].toLowerCase()) {
            case "give" -> { deposit(id, cents); msgBal(s, "Gave " + t.getName() + ": ", cents); }
            case "take" -> {
                if (!withdraw(id, cents)) { s.sendMessage(Component.text("That player doesn't have enough money.", NamedTextColor.RED)); return; }
                msgBal(s, "Took from " + t.getName() + ": ", cents);
            }
            case "set"  -> { balances.put(id, Math.max(0, cents)); dirty.set(true); msgBal(s, t.getName() + " now has ", cents); }
            default -> s.sendMessage(Component.text("/eco <give|take|set> <player> <amount>", NamedTextColor.RED));
        }
    }

    private void msgBal(CommandSender s, String prefix, long cents) {
        s.sendMessage(Component.text()
                .append(Component.text(prefix, BLUE_BRIGHT))
                .append(Component.text("$" + fmt(cents), MONEY, TextDecoration.BOLD)).build());
    }
}

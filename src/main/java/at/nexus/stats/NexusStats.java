package at.nexus.stats;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.bukkit.command.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.ServicePriority;
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
 * ═══════════════════════════════════════════════════════════════════
 *  NexusStats  -  Economy + Scoreboard        [PLUGIN 1 von 3]
 * ═══════════════════════════════════════════════════════════════════
 *
 *  EIGENSTAENDIGES PLUGIN. Einfach als JAR in plugins/ ziehen.
 *
 *  Muss ZUERST laufen - NexusShop und NexusSell brauchen es.
 *  Sie finden es automatisch ueber Bukkits ServicesManager,
 *  du musst NICHTS verbinden oder importieren.
 *
 *  ANZEIGE (rechts im Bild, Donut-Style):
 *      NexusSMP          <- fett, blau
 *      ─────────
 *      Balance
 *      $50k              <- 50000 wird zu "50k"
 *
 *      Playtime
 *      10 hours          <- oder "1 day" / "3 days"
 *
 *      Online: 12
 *      ─────────
 *
 *  PERFORMANCE:
 *   - Update 1x pro Sekunde, nicht jeden Tick.
 *   - Board wird EINMAL gebaut, danach nur Text geaendert.
 *     Kein Neuaufbau -> kein Flackern.
 *   - Speichern async, nur bei echten Aenderungen (dirty-Flag).
 * ═══════════════════════════════════════════════════════════════════
 */
public final class NexusStats extends JavaPlugin
        implements Listener, CommandExecutor, TabCompleter {

    // ── Blaues Theme ────────────────────────────────────────────────
    public static final TextColor BLUE_BRIGHT = TextColor.fromHexString("#4FC3F7");
    public static final TextColor BLUE_DEEP   = TextColor.fromHexString("#0D47A1");
    public static final TextColor MONEY       = TextColor.fromHexString("#00E676");
    public static final TextColor GREY        = TextColor.fromHexString("#9E9E9E");
    public static final TextColor WHITE       = TextColor.fromHexString("#FFFFFF");

    /** Kontostand in CENTS als long. Niemals double fuer Geld - Grund am Dateiende. */
    private final Map<UUID, Long> balances = new ConcurrentHashMap<>();
    private final Map<UUID, Board> boards  = new ConcurrentHashMap<>();
    private final AtomicBoolean dirty      = new AtomicBoolean(false);

    private File dataFile;
    private long startBalance;
    private BukkitTask boardTask, saveTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.startBalance = toCents(getConfig().getDouble("start-balance", 1000.0));
        this.dataFile = new File(getDataFolder(), "balances.yml");
        loadBalances();

        // ═══ HIER passiert die Magie ═══
        // Wir melden uns als Service an. Shop und Sell holen sich das
        // per Bukkit.getServicesManager().load(NexusBank.class).
        // Dadurch braucht KEINE der drei Dateien die andere zu importieren.
        Bukkit.getServicesManager().register(
                NexusBank.class, new BankImpl(), this, ServicePriority.Normal);

        getServer().getPluginManager().registerEvents(this, this);
        for (String c : new String[]{"balance", "pay", "baltop", "eco"}) {
            var cmd = getCommand(c);
            if (cmd != null) { cmd.setExecutor(this); cmd.setTabCompleter(this); }
        }

        boardTask = Bukkit.getScheduler().runTaskTimer(this, this::tickBoards, 20L, 20L);
        saveTask  = Bukkit.getScheduler().runTaskTimerAsynchronously(
                        this, () -> { if (dirty.compareAndSet(true, false)) save(); },
                        6000L, 6000L);

        for (Player p : Bukkit.getOnlinePlayers()) {
            ensure(p.getUniqueId());
            boards.put(p.getUniqueId(), new Board(p, this));
        }
        getLogger().info("NexusStats aktiv. " + balances.size() + " Konten.");
    }

    @Override
    public void onDisable() {
        if (boardTask != null) boardTask.cancel();
        if (saveTask  != null) saveTask.cancel();
        save();                                   // Shutdown: SYNCHRON, sonst Geldverlust
        boards.values().forEach(Board::destroy);
        boards.clear();
        Bukkit.getServicesManager().unregisterAll(this);
    }

    // ═══════════════════════════════════════════════════════════════
    //  SERVICE-INTERFACE  - Shop und Sell reden hierueber mit uns.
    //  Muss in ALLEN DREI Dateien identisch sein (ist es).
    // ═══════════════════════════════════════════════════════════════
    public interface NexusBank {
        long getCents(UUID id);
        void give(UUID id, long cents);
        boolean take(UUID id, long cents);
        String format(long cents);
    }

    private final class BankImpl implements NexusBank {
        @Override public long getCents(UUID id)  { return balance(id); }
        @Override public void give(UUID id, long c) { deposit(id, c); }
        @Override public boolean take(UUID id, long c) { return withdraw(id, c); }
        @Override public String format(long c)   { return fmt(c); }
    }

    // ═══════════════════════════════════════════════════════════════
    //  ECONOMY
    // ═══════════════════════════════════════════════════════════════

    private void ensure(UUID id) {
        if (balances.putIfAbsent(id, startBalance) == null) dirty.set(true);
    }

    private long balance(UUID id) { return balances.getOrDefault(id, 0L); }

    private void deposit(UUID id, long cents) {
        if (cents <= 0) return;
        balances.merge(id, cents, Long::sum);      // atomar
        dirty.set(true);
    }

    /**
     * @return false wenn nicht gedeckt - dann wurde NICHTS abgezogen.
     * compute() ist atomar: verhindert, dass zwei gleichzeitige Kaeufe
     * beide durchgehen obwohl nur einer gedeckt waere.
     */
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

    // ═══════════════════════════════════════════════════════════════
    //  FORMAT  -  50000 -> "50k"   1500000 -> "1.5M"
    // ═══════════════════════════════════════════════════════════════

    public static String fmt(long cents) {
        double v = cents / 100.0;
        boolean neg = v < 0;
        v = Math.abs(v);
        String s;
        if      (v >= 1e12) s = trim(v / 1e12) + "T";
        else if (v >= 1e9)  s = trim(v / 1e9)  + "B";
        else if (v >= 1e6)  s = trim(v / 1e6)  + "M";
        else if (v >= 1e3)  s = trim(v / 1e3)  + "k";
        else s = (v == Math.floor(v)) ? String.valueOf((long) v)
                                      : String.format("%.2f", v);
        return neg ? "-" + s : s;
    }

    /** 1.0 -> "1"  |  1.5 -> "1.5"   (keine sinnlose ".0") */
    private static String trim(double d) {
        double r = Math.round(d * 10.0) / 10.0;
        return (r == Math.floor(r)) ? String.valueOf((long) r)
                                    : String.format("%.1f", r);
    }

    /**
     * Playtime EXAKT wie gewuenscht.
     * Singular/Plural korrekt: "1 day" vs "3 days", "1 hour" vs "10 hours".
     */
    public static String playtime(Player p) {
        int ticks = p.getStatistic(Statistic.PLAY_ONE_MINUTE);   // Vanilla-Stat, in Ticks
        long min = ticks / 20L / 60L;

        long days = min / 1440L;
        if (days >= 1) return days + (days == 1 ? " day" : " days");

        long hours = min / 60L;
        if (hours >= 1) return hours + (hours == 1 ? " hour" : " hours");

        return min + (min == 1 ? " minute" : " minutes");
    }

    /** Versteht "500", "50k", "1.5M", "2b". -1 = ungueltig. */
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

    // ═══════════════════════════════════════════════════════════════
    //  SCOREBOARD
    // ═══════════════════════════════════════════════════════════════

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

    /**
     * TRICK gegen Flackern: Jede Zeile ist ein Team mit einem
     * unsichtbaren Farbcode als Entry. Beim Update wird nur der
     * Team-Prefix ueberschrieben - das Board selbst wird NIE
     * neu aufgebaut.
     */
    private static final class Board {
        private static final String[] KEYS =
                {"§0","§1","§2","§3","§4","§5","§6","§7","§8"};

        private final NexusStats plugin;
        private final Scoreboard board;
        private final Objective obj;
        private final Team[] lines = new Team[KEYS.length];

        private String lastBal = "", lastTime = "";
        private int lastOnline = -1;

        Board(Player p, NexusStats plugin) {
            this.plugin = plugin;
            this.board  = Bukkit.getScoreboardManager().getNewScoreboard();

            // ── Titel: NexusSMP, fett, zweifarbig blau ──
            Component title = Component.text()
                    .append(Component.text("Nexus", BLUE_BRIGHT, TextDecoration.BOLD))
                    .append(Component.text("SMP",   BLUE_DEEP,   TextDecoration.BOLD))
                    .build();

            this.obj = board.registerNewObjective("nexus", Criteria.DUMMY, title);
            obj.setDisplaySlot(DisplaySlot.SIDEBAR);

            for (int i = 0; i < KEYS.length; i++) {
                Team t = board.registerNewTeam("l" + i);
                t.addEntry(KEYS[i]);
                lines[i] = t;
                obj.getScore(KEYS[i]).setScore(KEYS.length - i);  // hoeher = weiter oben
            }

            set(0, Component.text("                  ", GREY, TextDecoration.STRIKETHROUGH));
            set(1, Component.text("Balance",  BLUE_BRIGHT, TextDecoration.BOLD));
            // 2 = Geld     (dynamisch)
            set(3, Component.empty());
            set(4, Component.text("Playtime", BLUE_BRIGHT, TextDecoration.BOLD));
            // 5 = Playtime (dynamisch)
            set(6, Component.empty());
            // 7 = Online   (dynamisch)
            set(8, Component.text("                  ", GREY, TextDecoration.STRIKETHROUGH));

            p.setScoreboard(board);
            update(p);
        }

        private void set(int i, Component c) { lines[i].prefix(c); }

        /** 1x/Sekunde. Schreibt nur bei ECHTER Aenderung. */
        void update(Player p) {
            String bal = fmt(plugin.balance(p.getUniqueId()));
            if (!bal.equals(lastBal)) {
                lastBal = bal;
                set(2, Component.text()
                        .append(Component.text("$", MONEY, TextDecoration.BOLD))
                        .append(Component.text(bal, WHITE)).build());
            }

            String time = playtime(p);
            if (!time.equals(lastTime)) {
                lastTime = time;
                set(5, Component.text(time, WHITE));
            }

            int online = Bukkit.getOnlinePlayers().size();
            if (online != lastOnline) {
                lastOnline = online;
                set(7, Component.text()
                        .append(Component.text("Online: ", GREY))
                        .append(Component.text(String.valueOf(online), BLUE_BRIGHT)).build());
            }
        }

        void destroy() {
            try {
                obj.unregister();
                for (Team t : lines) t.unregister();
            } catch (IllegalStateException ignored) { }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  PERSISTENZ
    // ═══════════════════════════════════════════════════════════════

    private void loadBalances() {
        if (!dataFile.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(dataFile);
        int bad = 0;
        for (String k : y.getKeys(false)) {
            try { balances.put(UUID.fromString(k), y.getLong(k)); }
            catch (IllegalArgumentException ex) { bad++; }
        }
        if (bad > 0) getLogger().warning(bad + " ungueltige Eintraege uebersprungen.");
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        balances.forEach((k, v) -> y.set(k.toString(), v));
        try {
            if (!getDataFolder().exists()) getDataFolder().mkdirs();
            y.save(dataFile);
        } catch (IOException ex) {
            getLogger().severe("balances.yml NICHT gespeichert: " + ex.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  COMMANDS
    // ═══════════════════════════════════════════════════════════════

    @Override
    public boolean onCommand(@NotNull CommandSender s, @NotNull Command c,
                             @NotNull String label, @NotNull String[] a) {
        switch (c.getName().toLowerCase()) {

            case "balance" -> {
                if (a.length == 0) {
                    if (!(s instanceof Player p)) {
                        s.sendMessage(Component.text("Nutzung: /balance <Spieler>", NamedTextColor.RED));
                        return true;
                    }
                    msgBal(s, "Dein Kontostand: ", balance(p.getUniqueId()));
                    return true;
                }
                OfflinePlayer t = Bukkit.getOfflinePlayer(a[0]);
                if (!t.hasPlayedBefore() && !t.isOnline()) {
                    s.sendMessage(Component.text("Spieler nicht gefunden.", NamedTextColor.RED));
                    return true;
                }
                msgBal(s, t.getName() + ": ", balance(t.getUniqueId()));
            }

            case "pay" -> {
                if (!(s instanceof Player p)) {
                    s.sendMessage(Component.text("Nur fuer Spieler.", NamedTextColor.RED));
                    return true;
                }
                if (a.length < 2) {
                    s.sendMessage(Component.text("Nutzung: /pay <Spieler> <Betrag>", NamedTextColor.RED));
                    return true;
                }
                Player t = Bukkit.getPlayerExact(a[0]);
                if (t == null) {
                    s.sendMessage(Component.text("Spieler ist nicht online.", NamedTextColor.RED));
                    return true;
                }
                if (t.getUniqueId().equals(p.getUniqueId())) {
                    s.sendMessage(Component.text("Du kannst dir nicht selbst Geld schicken.", NamedTextColor.RED));
                    return true;
                }
                long cents = parseAmount(a[1]);
                if (cents <= 0) {
                    s.sendMessage(Component.text("Ungueltiger Betrag. Bsp: /pay Steve 50k", NamedTextColor.RED));
                    return true;
                }
                if (!withdraw(p.getUniqueId(), cents)) {
                    s.sendMessage(Component.text("Du hast nicht genug Geld.", NamedTextColor.RED));
                    return true;
                }
                deposit(t.getUniqueId(), cents);
                msgBal(p, "Gesendet an " + t.getName() + ": ", cents);
                msgBal(t, "Erhalten von " + p.getName() + ": ", cents);
            }

            case "baltop" -> {
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

            case "eco" -> {
                if (!s.hasPermission("nexus.eco.admin")) {
                    s.sendMessage(Component.text("Keine Berechtigung.", NamedTextColor.RED));
                    return true;
                }
                if (a.length < 3) {
                    s.sendMessage(Component.text("/eco <give|take|set> <Spieler> <Betrag>", NamedTextColor.RED));
                    return true;
                }
                OfflinePlayer t = Bukkit.getOfflinePlayer(a[1]);
                if (!t.hasPlayedBefore() && !t.isOnline()) {
                    s.sendMessage(Component.text("Spieler nicht gefunden.", NamedTextColor.RED));
                    return true;
                }
                long cents = parseAmount(a[2]);
                if (cents < 0) {
                    s.sendMessage(Component.text("Ungueltiger Betrag.", NamedTextColor.RED));
                    return true;
                }
                UUID id = t.getUniqueId();
                switch (a[0].toLowerCase()) {
                    case "give" -> { deposit(id, cents);
                        msgBal(s, "Gegeben an " + t.getName() + ": ", cents); }
                    case "take" -> {
                        if (!withdraw(id, cents)) {
                            s.sendMessage(Component.text("Nicht genug Geld.", NamedTextColor.RED));
                            return true;
                        }
                        msgBal(s, "Genommen von " + t.getName() + ": ", cents);
                    }
                    case "set"  -> { balances.put(id, Math.max(0, cents)); dirty.set(true);
                        msgBal(s, t.getName() + " hat jetzt ", cents); }
                    default -> s.sendMessage(Component.text("/eco <give|take|set> <Spieler> <Betrag>", NamedTextColor.RED));
                }
            }
            default -> { return false; }
        }
        return true;
    }

    private void msgBal(CommandSender s, String prefix, long cents) {
        s.sendMessage(Component.text()
                .append(Component.text(prefix, BLUE_BRIGHT))
                .append(Component.text("$" + fmt(cents), MONEY, TextDecoration.BOLD))
                .build());
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender s, @NotNull Command c,
                                      @NotNull String label, @NotNull String[] a) {
        if (c.getName().equalsIgnoreCase("eco") && a.length == 1)
            return List.of("give", "take", "set");
        if (a.length == 1 || (c.getName().equalsIgnoreCase("eco") && a.length == 2)) {
            List<String> n = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) n.add(p.getName());
            return n;
        }
        return Collections.emptyList();
    }
}

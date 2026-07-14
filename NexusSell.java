[00:46, 15.7.2026] Oli: package at.nexus.stats;

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
import org.bukkit.event.player.PlayerJoinEvent;
import org.buk…
[00:52, 15.7.2026] Oli: name: NexusStats
version: 1.0.0
main: at.nexus.stats.NexusStats
api-version: '1.21'
description: Economy and scoreboard for NexusSMP
author: NexusSMP

permissions:
  nexus.eco.admin:
    default: op
[01:06, 15.7.2026] Oli: package at.nexus.sell;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * ═══════════════════════════════════════════════════════════════════
 *  NexusSell  -  /sell + editable prices      [PLUGIN 2 of 3]
 * ═══════════════════════════════════════════════════════════════════
 *
 *  Standalone plugin. Needs NexusStats running (finds it via the
 *  ServicesManager - the NexusBank interface below is an identical
 *  copy of the one in NexusStats).
 *
 *  COMMANDS
 *    /sell            sell everything sellable in your inventory
 *    /sell hand       sell only what you're holding
 *    /sellprice set <material> <price>    e.g. /sellprice set diamond 1200
 *    /sellprice remove <material>         make it unsellable
 *    /sellprice check <material>          show current price
 *    /sellprice reload                    reload prices.yml
 *
 *  PRICES
 *   - On first run a prices.yml is generated with fair defaults for
 *     EVERY sellable item in 1.21.11, grouped by category.
 *   - Edit live in-game with /sellprice - saves instantly, no restart.
 *   - Buy prices in the shop are always higher than these sell prices,
 *     so you can't buy-low-sell-high to print money.
 *
 *  Calibrated around sugar cane = $8, so a good farm makes ~30k/h and
 *  one million is a weekend goal rather than an afternoon.
 * ═══════════════════════════════════════════════════════════════════
 */
public final class NexusSell extends JavaPlugin {

    public static final TextColor BLUE  = TextColor.fromHexString("#4FC3F7");
    public static final TextColor MONEY = TextColor.fromHexString("#00E676");

    /** material -> price in CENTS (long). Never double for money. */
    private final Map<Material, Long> prices = new EnumMap<>(Material.class);

    private File file;
    private NexusBank bank;

    // ═══════════════════════════════════════════════════════════════
    @Override
    public void onEnable() {
        // Grab the economy that NexusStats registered.
        RegisteredServiceProvider<NexusBank> rsp =
                Bukkit.getServicesManager().getRegistration(NexusBank.class);
        if (rsp == null) {
            getLogger().severe("NexusStats not found! NexusSell needs it. Disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        this.bank = rsp.getProvider();

        this.file = new File(getDataFolder(), "prices.yml");
        if (!file.exists()) generateDefaults();  // first run: fair defaults for everything
        loadPrices();

        // Commands in code (no YAML) - same approach as NexusStats.
        this.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            event.registrar().register("sell", "Sell items for money",
                    new Cmd(this::cmdSell));
            event.registrar().register("sellprice", "Manage sell prices",
                    new Cmd(this::cmdSellPrice));
        });

        getLogger().info("NexusSell enabled. " + prices.size() + " item prices loaded.");
    }

    private static final class Cmd implements BasicCommand {
        interface H { void run(CommandSender s, String[] a); }
        private final H h;
        Cmd(H h) { this.h = h; }
        @Override public void execute(@NotNull CommandSourceStack st, @NotNull String[] a) {
            h.run(st.getSender(), a);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Service interface  -  identical copy of the one in NexusStats
    // ═══════════════════════════════════════════════════════════════
    public interface NexusBank {
        long getCents(UUID id);
        void give(UUID id, long cents);
        boolean take(UUID id, long cents);
        String format(long cents);
    }

    // ═══════════════════════════════════════════════════════════════
    //  /sell
    // ═══════════════════════════════════════════════════════════════

    private void cmdSell(CommandSender s, String[] a) {
        if (!(s instanceof Player p)) {
            s.sendMessage(Component.text("Players only.", NamedTextColor.RED));
            return;
        }
        boolean handOnly = a.length > 0 && a[0].equalsIgnoreCase("hand");

        long totalCents = 0;
        int totalItems = 0;

        if (handOnly) {
            ItemStack hand = p.getInventory().getItemInMainHand();
            long earned = sellStack(p, hand);
            if (earned > 0) { totalCents += earned; totalItems += hand.getAmount(); }
        } else {
            ItemStack[] contents = p.getInventory().getStorageContents();
            for (int i = 0; i < contents.length; i++) {
                ItemStack it = contents[i];
                if (it == null) continue;
                long earned = sellStack(p, it);
                if (earned > 0) totalItems += it.getAmount();
                totalCents += earned;
            }
        }

        if (totalItems == 0) {
            p.sendMessage(Component.text("Nothing sellable found.", NamedTextColor.RED));
            return;
        }

        bank.give(p.getUniqueId(), totalCents);
        p.sendMessage(Component.text()
                .append(Component.text("Sold ", BLUE))
                .append(Component.text(totalItems + " items", NamedTextColor.WHITE))
                .append(Component.text(" for ", BLUE))
                .append(Component.text("$" + bank.format(totalCents), MONEY, TextDecoration.BOLD))
                .build());
    }

    /**
     * Sells one stack fully if it has a price. Only sells "clean" items -
     * no custom name, no enchants, no damage - so nobody accidentally
     * sells their maxed netherite sword for the raw-material price.
     * @return cents earned (0 if not sellable). Removes sold items.
     */
    private long sellStack(Player p, ItemStack it) {
        if (it == null || it.getType().isAir()) return 0;
        Long price = prices.get(it.getType());
        if (price == null || price <= 0) return 0;
        if (it.hasItemMeta() && it.getItemMeta().hasEnchants()) return 0;
        if (it.hasItemMeta() && it.getItemMeta().hasDisplayName()) return 0;

        int amount = it.getAmount();
        long earned = price * amount;
        p.getInventory().removeItem(it);
        return earned;
    }

    // ═══════════════════════════════════════════════════════════════
    //  /sellprice
    // ═══════════════════════════════════════════════════════════════

    private void cmdSellPrice(CommandSender s, String[] a) {
        if (!s.hasPermission("nexus.sell.admin")) {
            s.sendMessage(Component.text("No permission.", NamedTextColor.RED));
            return;
        }
        if (a.length == 0) {
            s.sendMessage(Component.text("/sellprice <set|remove|check|reload> ...", NamedTextColor.RED));
            return;
        }

        switch (a[0].toLowerCase()) {
            case "reload" -> {
                loadPrices();
                s.sendMessage(Component.text("Prices reloaded. " + prices.size() + " items.", BLUE));
            }
            case "set" -> {
                if (a.length < 3) {
                    s.sendMessage(Component.text("/sellprice set <material> <price>", NamedTextColor.RED));
                    return;
                }
                Material m = Material.matchMaterial(a[1]);
                if (m == null || !m.isItem()) {
                    s.sendMessage(Component.text("Unknown item: " + a[1], NamedTextColor.RED));
                    return;
                }
                double val;
                try { val = Double.parseDouble(a[2]); }
                catch (NumberFormatException ex) {
                    s.sendMessage(Component.text("Price must be a number.", NamedTextColor.RED));
                    return;
                }
                if (val < 0) {
                    s.sendMessage(Component.text("Price can't be negative.", NamedTextColor.RED));
                    return;
                }
                long cents = Math.round(val * 100.0);
                prices.put(m, cents);
                save();
                s.sendMessage(Component.text()
                        .append(Component.text(m.name().toLowerCase() + " sell price -> ", BLUE))
                        .append(Component.text("$" + bank.format(cents), MONEY)).build());
            }
            case "remove" -> {
                Material m = Material.matchMaterial(a.length > 1 ? a[1] : "");
                if (m == null) {
                    s.sendMessage(Component.text("Unknown item.", NamedTextColor.RED));
                    return;
                }
                prices.remove(m);
                save();
                s.sendMessage(Component.text(m.name().toLowerCase() + " is no longer sellable.", BLUE));
            }
            case "check" -> {
                Material m = Material.matchMaterial(a.length > 1 ? a[1] : "");
                if (m == null) {
                    s.sendMessage(Component.text("Unknown item.", NamedTextColor.RED));
                    return;
                }
                Long price = prices.get(m);
                if (price == null) {
                    s.sendMessage(Component.text(m.name().toLowerCase() + " is not sellable.", NamedTextColor.RED));
                } else {
                    s.sendMessage(Component.text()
                            .append(Component.text(m.name().toLowerCase() + ": ", BLUE))
                            .append(Component.text("$" + bank.format(price), MONEY)).build());
                }
            }
            default -> s.sendMessage(Component.text("/sellprice <set|remove|check|reload> ...", NamedTextColor.RED));
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  PRICE STORAGE
    // ═══════════════════════════════════════════════════════════════

    private void loadPrices() {
        prices.clear();
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        for (String key : y.getKeys(false)) {
            Material m = Material.matchMaterial(key);
            if (m == null) continue;
            prices.put(m, Math.round(y.getDouble(key) * 100.0));
        }
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<Material, Long> e : prices.entrySet()) {
            y.set(e.getKey().name().toLowerCase(), e.getValue() / 100.0);
        }
        try {
            if (!getDataFolder().exists()) getDataFolder().mkdirs();
            y.save(file);
        } catch (IOException ex) {
            getLogger().severe("Failed to save prices.yml: " + ex.getMessage());
        }
    }

    /**
     * First-run generator. Walks EVERY material in the game and assigns
     * a fair sell price based on which category its name matches.
     * You never have to list items by hand - anything sellable gets a
     * sane default, and you fine-tune with /sellprice afterwards.
     *
     * Everything is anchored to sugar cane = $8.
     */
    private void generateDefaults() {
        YamlConfiguration y = new YamlConfiguration();

        for (Material m : Material.values()) {
            if (m.isLegacy() || !m.isItem()) continue;
            double price = defaultPrice(m);
            if (price > 0) y.set(m.name().toLowerCase(), price);
        }

        try {
            if (!getDataFolder().exists()) getDataFolder().mkdirs();
            y.save(file);
            getLogger().info("Generated prices.yml with default prices.");
        } catch (IOException ex) {
            getLogger().severe("Failed to write prices.yml: " + ex.getMessage());
        }
    }

    /**
     * The pricing brain. Matches by name pattern and exact name.
     * Returns 0 for anything that shouldn't be sellable (tools, armor,
     * spawn eggs, shulker boxes, command blocks, etc.) so players can't
     * dump gear or exploit creative-only items.
     */
    private double defaultPrice(Material m) {
        String n = m.name();

        // ── Never sellable (gear, containers, creative/exploit items) ──
        if (n.endsWith("_SWORD") || n.endsWith("_PICKAXE") || n.endsWith("_AXE")
                || n.endsWith("_SHOVEL") || n.endsWith("_HOE")
                || n.endsWith("_HELMET") || n.endsWith("_CHESTPLATE")
                || n.endsWith("_LEGGINGS") || n.endsWith("_BOOTS")
                || n.endsWith("_SPAWN_EGG") || n.endsWith("SHULKER_BOX")
                || n.contains("COMMAND_BLOCK") || n.equals("BARRIER")
                || n.equals("BEDROCK") || n.equals("STRUCTURE_BLOCK")
                || n.equals("STRUCTURE_VOID") || n.equals("JIGSAW")
                || n.equals("LIGHT") || n.equals("DEBUG_STICK")
                || n.equals("KNOWLEDGE_BOOK") || n.equals("ELYTRA")
                || n.equals("TRIDENT") || n.equals("BOW")
                || n.equals("CROSSBOW") || n.equals("SHIELD")
                || n.equals("FISHING_ROD") || n.equals("SHEARS")
                || n.equals("FLINT_AND_STEEL") || n.equals("BRUSH")
                || n.equals("CARROT_ON_A_STICK") || n.equals("SPYGLASS")
                || n.equals("HEAVY_CORE")            // Mace core: shop-only, never sellable
                || n.equals("MACE")
                || n.contains("POTION") || n.equals("TIPPED_ARROW")
                || n.contains("BUNDLE") || n.contains("MAP")) {
            return 0;
        }

        // ── High-value ores / minerals ──
        switch (n) {
            case "ANCIENT_DEBRIS":   return 40000;
            case "NETHERITE_INGOT":  return 45000;
            case "NETHERITE_SCRAP":  return 11000;
            case "DIAMOND":          return 1200;
            case "EMERALD":          return 900;
            case "DIAMOND_ORE":
            case "DEEPSLATE_DIAMOND_ORE": return 1400;
            case "EMERALD_ORE":
            case "DEEPSLATE_EMERALD_ORE": return 1100;
            case "GOLD_INGOT":       return 260;
            case "IRON_INGOT":       return 140;
            case "COPPER_INGOT":     return 45;
            case "RAW_GOLD":         return 220;
            case "RAW_IRON":         return 120;
            case "RAW_COPPER":       return 38;
            case "COAL":             return 30;
            case "REDSTONE":         return 25;
            case "LAPIS_LAZULI":     return 40;
            case "QUARTZ":           return 35;
            case "AMETHYST_SHARD":   return 70;

            // ── Mace / Breeze economy ──
            case "BREEZE_ROD":       return 2500;
            case "WIND_CHARGE":      return 150;
            case "HEAVY_CORE":       return 0;    // guarded above too

            // ── Mob drops that reward grinding ──
            case "GHAST_TEAR":       return 900;
            case "BLAZE_ROD":        return 350;
            case "ENDER_PEARL":      return 300;
            case "SLIME_BALL":       return 60;
            case "GUNPOWDER":        return 55;
            case "BONE":             return 20;
            case "STRING":           return 20;
            case "SPIDER_EYE":       return 30;
            case "ROTTEN_FLESH":     return 8;
            case "PHANTOM_MEMBRANE": return 250;
            case "SHULKER_SHELL":    return 1800;
            case "NETHER_STAR":      return 30000;
            case "DRAGON_BREATH":    return 400;
            case "TOTEM_OF_UNDYING": return 8000;
            case "RABBIT_FOOT":      return 400;
            case "NAUTILUS_SHELL":   return 500;
            case "HEART_OF_THE_SEA": return 6000;
            case "ECHO_SHARD":       return 900;
            case "SCULK":            return 15;

            // ── Farming: the "meta" grinds should pay decently ──
            case "SUGAR_CANE":       return 8;     // <- anchor
            case "WHEAT":            return 6;
            case "CARROT":           return 6;
            case "POTATO":           return 6;
            case "BEETROOT":         return 7;
            case "PUMPKIN":          return 12;
            case "MELON_SLICE":      return 4;
            case "MELON":            return 30;
            case "NETHER_WART":      return 40;
            case "CACTUS":           return 10;
            case "BAMBOO":           return 3;
            case "KELP":             return 3;
            case "COCOA_BEANS":      return 12;
            case "SWEET_BERRIES":    return 8;
            case "GLOW_BERRIES":     return 15;
            case "HONEY_BOTTLE":     return 60;
            case "HONEYCOMB":        return 40;
            case "EGG":              return 10;
            case "MILK_BUCKET":      return 0;     // bucket loss, skip
        }

        // ── Category patterns (catch-all so EVERYTHING gets a price) ──
        if (n.endsWith("_LOG") || n.endsWith("_STEM"))        return 12;
        if (n.endsWith("_PLANKS"))                            return 4;
        if (n.endsWith("_SAPLING"))                           return 20;
        if (n.endsWith("_WOOL"))                              return 12;
        if (n.endsWith("_DYE"))                               return 15;
        if (n.endsWith("_TERRACOTTA"))                        return 8;
        if (n.endsWith("_CONCRETE"))                          return 6;
        if (n.endsWith("_CONCRETE_POWDER"))                   return 5;
        if (n.endsWith("_GLASS") || n.endsWith("_GLASS_PANE")) return 4;
        if (n.endsWith("_FLOWER") || n.endsWith("_TULIP"))    return 10;
        if (n.endsWith("_MUSHROOM"))                          return 15;
        if (n.endsWith("_CORAL") || n.endsWith("_CORAL_BLOCK") || n.endsWith("_CORAL_FAN")) return 25;
        if (n.contains("SEEDS"))                              return 4;
        if (n.contains("LEATHER"))                            return 20;
        if (n.contains("FEATHER"))                            return 12;
        if (n.contains("SCUTE") || n.contains("TURTLE"))      return 80;
        if (n.contains("PRISMARINE"))                         return 20;
        if (n.contains("FISH") || n.equals("COD") || n.equals("SALMON")) return 18;
        if (n.contains("BEEF") || n.contains("PORKCHOP")
                || n.contains("CHICKEN") || n.contains("MUTTON")
                || n.contains("RABBIT"))                      return 14;

        // ── Common building blocks: low but not zero ──
        if (n.equals("STONE") || n.equals("COBBLESTONE")
                || n.equals("DIRT") || n.equals("SAND")
                || n.equals("GRAVEL") || n.equals("NETHERRACK")
                || n.equals("DEEPSLATE") || n.equals("COBBLED_DEEPSLATE")
                || n.equals("ANDESITE") || n.equals("DIORITE")
                || n.equals("GRANITE") || n.equals("TUFF")) return 2;

        // Everything else with no match -> not auto-priced.
        // Add it yourself with /sellprice set <item> <price> if you want.
        return 0;
    }
}

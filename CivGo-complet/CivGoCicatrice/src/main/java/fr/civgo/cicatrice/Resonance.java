package fr.civgo.cicatrice;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Résonance Lunaire : la Cicatrice grandit.
 *  - Résonance (niveaux 0 à 5) : gagnée en survivant aux Noirs, surtout dans le Cratère. Donne des pouvoirs.
 *  - Pacte de Cicatrice : deux joueurs se lient et se soutiennent pendant les Noirs. Trahir son pacte marque en rouge.
 *  - Pulsation lunaire : révèle les monstres autour de soi.
 */
class Resonance implements Listener {

    private static final int[] LEVELS = {0, 60, 200, 450, 900, 1600};
    private static final String[] PERKS = {
            "",
            "Immunité à la Faiblesse de la zone du Cratère.",
            "Pulsation lunaire : /cicatrice pulsation (révèle les monstres).",
            "Ton Pacte devient plus puissant (régénération et résistance accrues).",
            "Immunité à tous les effets de la zone du Cratère.",
            "Dernier souffle : tu survis à un coup mortel (recharge 10 min)."
    };

    private final CivGoCicatrice pl;
    private final File file;
    private final Map<UUID, Integer> points = new HashMap<>();
    private final Map<UUID, UUID> pact = new HashMap<>();
    private final Map<UUID, Long> parjureUntil = new HashMap<>();
    private final Map<UUID, UUID> pending = new HashMap<>();       // cible -> demandeur
    private final Map<UUID, Long> pendingTime = new HashMap<>();
    private final Map<UUID, Long> pulseCd = new HashMap<>();
    private final Map<UUID, Long> breathCd = new HashMap<>();
    private final Set<UUID> survivors = new HashSet<>();
    private boolean noirWas = false;
    private int minutes = 0;

    Resonance(CivGoCicatrice pl) {
        this.pl = pl;
        this.file = new File(pl.getDataFolder(), "resonance.yml");
    }

    void start() {
        load();
        Bukkit.getPluginManager().registerEvents(this, pl);
        Bukkit.getScheduler().runTaskTimer(pl, this::minuteTick, 1200L, 1200L);
        Bukkit.getScheduler().runTaskTimer(pl, this::pactTick, 40L, 40L);
        for (Player p : Bukkit.getOnlinePlayers()) refreshMeta(p);
    }

    // ------------------------------------------------------------ données

    private void load() {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection s = y.getConfigurationSection("players");
        if (s == null) return;
        for (String k : s.getKeys(false)) {
            try {
                UUID id = UUID.fromString(k);
                points.put(id, s.getInt(k + ".points", 0));
                String pa = s.getString(k + ".pact");
                if (pa != null) pact.put(id, UUID.fromString(pa));
                long pj = s.getLong(k + ".parjure", 0);
                if (pj > System.currentTimeMillis()) parjureUntil.put(id, pj);
            } catch (IllegalArgumentException ignored) {
            }
        }
        for (Map.Entry<UUID, Long> e : parjureUntil.entrySet()) {
            pl.revealUntil.put(e.getKey(), e.getValue());
            pl.markOverride.put(e.getKey(), Color.RED);
        }
    }

    void save() {
        YamlConfiguration y = new YamlConfiguration();
        Set<UUID> all = new HashSet<>(points.keySet());
        all.addAll(pact.keySet());
        for (UUID id : all) {
            String k = "players." + id;
            y.set(k + ".points", points.getOrDefault(id, 0));
            if (pact.containsKey(id)) y.set(k + ".pact", pact.get(id).toString());
            if (parjureUntil.containsKey(id)) y.set(k + ".parjure", parjureUntil.get(id));
        }
        try {
            pl.getDataFolder().mkdirs();
            y.save(file);
        } catch (IOException ex) {
            pl.getLogger().warning("Sauvegarde de la Résonance impossible : " + ex.getMessage());
        }
    }

    int level(UUID id) {
        int pts = points.getOrDefault(id, 0), lvl = 0;
        for (int i = 1; i < LEVELS.length; i++) if (pts >= LEVELS[i]) lvl = i;
        return lvl;
    }

    private void refreshMeta(Player p) {
        p.setMetadata("civgo_resonance", new FixedMetadataValue(pl, level(p.getUniqueId())));
    }

    private void add(Player p, int n) {
        UUID id = p.getUniqueId();
        int before = level(id);
        points.merge(id, n, Integer::sum);
        int after = level(id);
        refreshMeta(p);
        if (after > before) {
            p.sendTitle("§d✦ Résonance " + after, "§7" + PERKS[after], 10, 90, 30);
            p.sendMessage(ChatColor.LIGHT_PURPLE + "Ta Cicatrice se ravive : " + ChatColor.GRAY + PERKS[after]);
            p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1f, 0.8f);
            p.getWorld().spawnParticle(Particle.END_ROD, p.getLocation().add(0, 1, 0), 40, 0.4, 0.7, 0.4, 0.08);
            save();
        }
    }

    // ------------------------------------------------------------ utilitaires

    private boolean inZone(Player p) {
        Plugin n = Bukkit.getPluginManager().getPlugin("CivGoNoirs");
        if (n == null || !n.isEnabled()) return false;
        double dx = p.getLocation().getX() - n.getConfig().getDouble("crater.x", 0);
        double dz = p.getLocation().getZ() - n.getConfig().getDouble("crater.z", 0);
        return Math.sqrt(dx * dx + dz * dz) <= n.getConfig().getDouble("zone.radius", 100);
    }

    private boolean noirAnywhere() {
        for (World w : Bukkit.getWorlds()) if (CivGoCicatrice.isNoir(w)) return true;
        return false;
    }

    private String rp(UUID id) {
        String n = pl.name(id);
        if (n != null) return n;
        Player p = Bukkit.getPlayer(id);
        return p != null ? p.getName() : "quelqu'un";
    }

    private void bar(Player p, String t) {
        p.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(t));
    }

    // ------------------------------------------------------------ gain de Résonance

    private void minuteTick() {
        minutes++;
        boolean noir = noirAnywhere();
        if (noir && !noirWas) {                                    // début d'un Noir
            survivors.clear();
            for (Player p : Bukkit.getOnlinePlayers()) if (CivGoCicatrice.isNoir(p.getWorld())) survivors.add(p.getUniqueId());
        }
        if (!noir && noirWas) {                                    // fin d'un Noir : bonus aux survivants
            for (UUID id : survivors) {
                Player p = Bukkit.getPlayer(id);
                if (p != null && p.isOnline() && pl.has(id)) {
                    add(p, 10);
                    p.sendMessage(ChatColor.LIGHT_PURPLE + "✦ Tu as survécu au Noir : +10 de Résonance.");
                }
            }
            survivors.clear();
        }
        noirWas = noir;

        if (noir) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (!CivGoCicatrice.isNoir(p.getWorld()) || !pl.has(p.getUniqueId())) continue;
                if (p.getGameMode() != GameMode.SURVIVAL && p.getGameMode() != GameMode.ADVENTURE) continue;
                add(p, inZone(p) ? 3 : 1);                         // le Cratère résonne trois fois plus
            }
        }
        // fin des marques de Parjure
        long now = System.currentTimeMillis();
        for (UUID id : new HashSet<>(parjureUntil.keySet())) {
            if (parjureUntil.get(id) < now) {
                parjureUntil.remove(id);
                pl.markOverride.remove(id);
                pl.revealUntil.remove(id);
                save();
            }
        }
        if (minutes % 5 == 0) save();
    }

    @EventHandler
    public void onKill(EntityDeathEvent e) {
        Player k = e.getEntity().getKiller();
        if (k == null || !(e.getEntity() instanceof Monster) || !pl.has(k.getUniqueId())) return;
        if (!CivGoCicatrice.isNoir(k.getWorld()) && !inZone(k)) return;
        if (Math.random() < 0.5) add(k, inZone(k) ? 2 : 1);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        refreshMeta(e.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        survivors.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        survivors.remove(e.getEntity().getUniqueId());
    }

    // ------------------------------------------------------------ Dernier souffle (niveau 5)

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFatal(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p) || level(p.getUniqueId()) < 5) return;
        if (p.getHealth() - e.getFinalDamage() > 0) return;
        long now = System.currentTimeMillis();
        Long last = breathCd.get(p.getUniqueId());
        if (last != null && now - last < 10 * 60_000L) return;
        breathCd.put(p.getUniqueId(), now);
        e.setCancelled(true);
        p.setHealth(Math.min(8.0, p.getHealth() > 0 ? Math.max(1.0, 8.0) : 8.0));
        p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 100, 1));
        p.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 100, 1));
        p.getWorld().spawnParticle(Particle.END_ROD, p.getLocation().add(0, 1, 0), 60, 0.5, 0.8, 0.5, 0.12);
        p.getWorld().playSound(p.getLocation(), Sound.ITEM_TOTEM_USE, 1f, 1.2f);
        p.sendMessage(ChatColor.LIGHT_PURPLE + "✦ Ta Cicatrice t'a retenu du bord : Dernier souffle !");
    }

    // ------------------------------------------------------------ Pacte et Parjure

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player v)) return;
        Entity d = e.getDamager();
        Player a = null;
        if (d instanceof Player pd) a = pd;
        else if (d instanceof Projectile pr && pr.getShooter() instanceof Player ps) a = ps;
        if (a == null || a.equals(v)) return;
        UUID partner = pact.get(a.getUniqueId());
        if (partner == null || !partner.equals(v.getUniqueId())) return;
        breakPact(a.getUniqueId(), v.getUniqueId());
        long until = System.currentTimeMillis() + 30 * 60_000L;
        parjureUntil.put(a.getUniqueId(), until);
        pl.revealUntil.put(a.getUniqueId(), until);
        pl.markOverride.put(a.getUniqueId(), Color.RED);
        Bukkit.broadcastMessage(ChatColor.RED + "✦ " + rp(a.getUniqueId()) + " a trahi son Pacte de Cicatrice. Sa marque brûle désormais aux yeux de tous.");
        a.playSound(a.getLocation(), Sound.ENTITY_WITHER_SPAWN, 0.6f, 0.7f);
        save();
    }

    private void breakPact(UUID a, UUID b) {
        pact.remove(a);
        pact.remove(b);
        save();
    }

    private void pactTick() {
        if (pact.isEmpty()) return;
        for (Map.Entry<UUID, UUID> en : pact.entrySet()) {
            if (en.getKey().compareTo(en.getValue()) > 0) continue;         // une fois par paire
            Player a = Bukkit.getPlayer(en.getKey()), b = Bukkit.getPlayer(en.getValue());
            if (a == null || b == null || !a.getWorld().equals(b.getWorld())) continue;
            if (!CivGoCicatrice.isNoir(a.getWorld())) continue;
            if (a.getLocation().distanceSquared(b.getLocation()) > 100) continue;
            boolean strong = level(a.getUniqueId()) >= 3 || level(b.getUniqueId()) >= 3;
            for (Player p : new Player[]{a, b}) {
                p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 80, strong ? 1 : 0, true, false, true));
                p.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 80, strong ? 1 : 0, true, false, true));
                p.getWorld().spawnParticle(Particle.END_ROD, p.getLocation().add(0, 1.2, 0), 2, 0.3, 0.3, 0.3, 0.01);
            }
        }
    }

    // ------------------------------------------------------------ commandes

    boolean handle(CommandSender s, String[] a) {
        String sub = a[0].toLowerCase();
        switch (sub) {
            case "resonance" -> {
                if (a.length >= 4 && (a[1].equalsIgnoreCase("give") || a[1].equalsIgnoreCase("set"))) {
                    if (!s.hasPermission("civgo.cicatrice.admin")) {
                        s.sendMessage(ChatColor.RED + "Tu n'as pas la permission.");
                        return true;
                    }
                    Player t = Bukkit.getPlayerExact(a[2]);
                    if (t == null) {
                        s.sendMessage(ChatColor.RED + "Joueur introuvable.");
                        return true;
                    }
                    try {
                        int n = Integer.parseInt(a[3]);
                        if (a[1].equalsIgnoreCase("set")) points.put(t.getUniqueId(), 0);
                        add(t, n);
                        save();
                        s.sendMessage(ChatColor.LIGHT_PURPLE + t.getName() + " : " + points.getOrDefault(t.getUniqueId(), 0) + " de Résonance (niveau " + level(t.getUniqueId()) + ").");
                    } catch (NumberFormatException ex) {
                        s.sendMessage(ChatColor.RED + "Nombre invalide.");
                    }
                    return true;
                }
                if (!(s instanceof Player p)) return true;
                UUID id = p.getUniqueId();
                int lvl = level(id), pts = points.getOrDefault(id, 0);
                s.sendMessage(ChatColor.LIGHT_PURPLE + "✦ Résonance niveau " + lvl + ChatColor.GRAY + " (" + pts
                        + (lvl < 5 ? " / " + LEVELS[lvl + 1] : " - maximum") + ")");
                for (int i = 1; i <= 5; i++) {
                    s.sendMessage((lvl >= i ? ChatColor.WHITE : ChatColor.DARK_GRAY) + "  Niveau " + i + " : " + PERKS[i]);
                }
                UUID pa = pact.get(id);
                s.sendMessage(ChatColor.GRAY + "Pacte : " + (pa == null ? "aucun" : rp(pa)));
                return true;
            }
            case "pulsation" -> {
                if (!(s instanceof Player p)) return true;
                int lvl = level(p.getUniqueId());
                if (lvl < 2) {
                    s.sendMessage(ChatColor.RED + "Il faut la Résonance niveau 2 (tape /cicatrice resonance).");
                    return true;
                }
                long now = System.currentTimeMillis();
                Long last = pulseCd.get(p.getUniqueId());
                if (last != null && now - last < 60_000L) {
                    bar(p, "§7Pulsation prête dans " + ((60_000L - (now - last)) / 1000 + 1) + " s");
                    return true;
                }
                pulseCd.put(p.getUniqueId(), now);
                double r = 12 + 4 * lvl;
                int found = 0;
                for (Entity en : p.getNearbyEntities(r, r, r)) {
                    if (en instanceof Monster m) {
                        m.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 160, 0, true, false, false));
                        found++;
                    }
                }
                Location c = p.getLocation().add(0, 0.3, 0);
                for (int i = 0; i < 48; i++) {
                    double ang = Math.PI * 2 * i / 48;
                    p.getWorld().spawnParticle(Particle.END_ROD, c.clone().add(Math.cos(ang) * 2.5, 0, Math.sin(ang) * 2.5), 1, 0, 0, 0, 0);
                }
                p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1.2f, 0.7f);
                bar(p, "§d✦ Pulsation lunaire : §f" + found + (found > 1 ? " présences" : " présence") + " révélée" + (found > 1 ? "s" : ""));
                return true;
            }
            case "pacte" -> {
                if (!(s instanceof Player p)) return true;
                if (a.length < 2) {
                    s.sendMessage(ChatColor.RED + "Usage : /cicatrice pacte <joueur>");
                    return true;
                }
                Player t = Bukkit.getPlayerExact(a[1]);
                if (t == null || t.equals(p) || !pl.has(t.getUniqueId()) || !pl.has(p.getUniqueId())) {
                    s.sendMessage(ChatColor.RED + "Joueur introuvable (ou sans identité).");
                    return true;
                }
                if (level(p.getUniqueId()) < 1) {
                    s.sendMessage(ChatColor.RED + "Il faut la Résonance niveau 1 pour sceller un Pacte.");
                    return true;
                }
                if (pact.containsKey(p.getUniqueId()) || pact.containsKey(t.getUniqueId())) {
                    s.sendMessage(ChatColor.RED + "L'un de vous a déjà un Pacte. (/cicatrice rompre)");
                    return true;
                }
                if (p.getLocation().getWorld().equals(t.getWorld()) && p.getLocation().distance(t.getLocation()) > 6) {
                    s.sendMessage(ChatColor.RED + "Approche-toi de lui (6 blocs) : un Pacte se scelle face à face.");
                    return true;
                }
                pending.put(t.getUniqueId(), p.getUniqueId());
                pendingTime.put(t.getUniqueId(), System.currentTimeMillis());
                s.sendMessage(ChatColor.LIGHT_PURPLE + "Tu proposes un Pacte de Cicatrice à " + rp(t.getUniqueId()) + ".");
                t.sendMessage(ChatColor.LIGHT_PURPLE + "✦ " + rp(p.getUniqueId()) + " te propose un Pacte de Cicatrice. "
                        + ChatColor.GRAY + "Tape /cicatrice accepter (60 s). Le trahir te marquerait en rouge pour tous.");
                return true;
            }
            case "accepter" -> {
                if (!(s instanceof Player p)) return true;
                UUID req = pending.get(p.getUniqueId());
                Long when = pendingTime.get(p.getUniqueId());
                Player r = req == null ? null : Bukkit.getPlayer(req);
                if (r == null || when == null || System.currentTimeMillis() - when > 60_000L) {
                    s.sendMessage(ChatColor.RED + "Aucune proposition de Pacte en attente.");
                    return true;
                }
                if (pact.containsKey(p.getUniqueId()) || pact.containsKey(req)) {
                    s.sendMessage(ChatColor.RED + "L'un de vous a déjà un Pacte.");
                    return true;
                }
                pending.remove(p.getUniqueId());
                pendingTime.remove(p.getUniqueId());
                pact.put(p.getUniqueId(), req);
                pact.put(req, p.getUniqueId());
                save();
                for (Player x : new Player[]{p, r}) {
                    x.sendMessage(ChatColor.LIGHT_PURPLE + "✦ Pacte de Cicatrice scellé entre " + rp(p.getUniqueId()) + " et " + rp(req)
                            + ChatColor.GRAY + ". Pendant les Noirs, côte à côte, vos Cicatrices se soutiennent.");
                    x.playSound(x.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1f, 1.2f);
                    x.getWorld().spawnParticle(Particle.END_ROD, x.getLocation().add(0, 1, 0), 30, 0.4, 0.6, 0.4, 0.06);
                }
                return true;
            }
            case "rompre" -> {
                if (!(s instanceof Player p)) return true;
                UUID pa = pact.get(p.getUniqueId());
                if (pa == null) {
                    s.sendMessage(ChatColor.RED + "Tu n'as aucun Pacte.");
                    return true;
                }
                breakPact(p.getUniqueId(), pa);
                s.sendMessage(ChatColor.GRAY + "Pacte rompu. Sans trahison, aucune marque.");
                Player o = Bukkit.getPlayer(pa);
                if (o != null) o.sendMessage(ChatColor.GRAY + rp(p.getUniqueId()) + " a rompu votre Pacte.");
                return true;
            }
            default -> {
                return false;
            }
        }
    }
}

package fr.civgo.objets;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.inventory.meta.components.EquippableComponent;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * CivGo Objets : donne un pouvoir à chaque objet du pack de ressources CivGo.
 * Les objets sont reconnus grâce à leur modèle (composant item_model "civgo:<nom>"), donc ceux donnés par /give marchent aussi.
 * Le Casque Lunaire est un objet purement esthétique : un vrai casque 3D posé sur la tête du joueur.
 */
public class CivGoObjets extends JavaPlugin implements Listener, TabExecutor {

    private static final String HAT_TAG = "civgo_hat";

    private record Def(String id, Material base, String color, String name, List<String> lore) {}

    private final Map<String, Def> defs = new LinkedHashMap<>();
    private final Map<UUID, Long> waveCooldown = new HashMap<>();
    private final Map<UUID, ItemDisplay> hats = new HashMap<>();
    private final Set<String> placed = new HashSet<>();
    private NamespacedKey minedKey;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        minedKey = new NamespacedKey(this, "mined");
        defs();
        purgeOldEntities();
        Bukkit.getPluginManager().registerEvents(this, this);
        getCommand("objets").setExecutor(this);
        Bukkit.getScheduler().runTaskTimer(this, this::hatTick, 20L, 2L);
    }

    @Override
    public void onDisable() {
        for (ItemDisplay d : hats.values()) {
            if (d.isValid()) d.remove();
        }
        hats.clear();
    }

    /** Nettoie les anciens casques flottants et les anciennes lanternes (objet supprimé). */
    private void purgeOldEntities() {
        for (World w : Bukkit.getWorlds()) {
            for (Entity en : w.getEntities()) {
                if (en.getScoreboardTags().contains(HAT_TAG) || en.getScoreboardTags().contains("civgo_lantern")) en.remove();
            }
        }
        File old = new File(getDataFolder(), "lanternes.yml");
        if (old.exists()) {
            YamlConfiguration y = YamlConfiguration.loadConfiguration(old);
            for (String line : y.getStringList("lanternes")) {
                String[] p = line.split(";")[0].split(",");
                World w = p.length == 4 ? Bukkit.getWorld(p[0]) : null;
                if (w == null) continue;
                try {
                    Block b = w.getBlockAt(Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]));
                    if (b.getType() == Material.LIGHT) b.setType(Material.AIR, false);
                } catch (NumberFormatException ignored) {
                }
            }
            old.delete();
        }
    }

    // ------------------------------------------------------------ objets

    private void defs() {
        add("boussole_cratere", Material.STICK, "§6", "Boussole du Cratère",
                "§7Clic droit : direction et distance du Cratère.");
        add("epee_lune", Material.NETHERITE_SWORD, "§d", "Épée de Lune",
                "§7Frappe lunaire : +25 % de dégâts aux monstres, la cible luit 4 s.",
                "§7Pendant un Noir : +25 % de dégâts et vol de vie.",
                "§7Clic droit : Onde lunaire (recharge 20 s).");
        add("pioche_eclat", Material.NETHERITE_PICKAXE, "§d", "Pioche d'Éclat",
                "§7Tous les 150 blocs minés : +1 Éclat sur ton solde.");
        add("arc_noirs", Material.BOW, "§5", "Arc des Noirs",
                "§7Tire des Éclats : +70 % de dégâts, éclats lumineux à l'impact.",
                "§7Révèle la Cicatrice du joueur touché pendant 8 s.");
        add("casque_lunaire", Material.STICK, "§f", "Casque Lunaire",
                "§7Un casque lunaire qui se pose sur la tête.",
                "§7Purement esthétique : clic droit pour l'enfiler.");
        add("amulette_cicatrice", Material.STICK, "§6", "Amulette de Cicatrice",
                "§7Tenue en main secondaire : cache ta Cicatrice pendant les Noirs.",
                "§7L'Arc des Noirs la révèle quand même.");
        add("fiole_lueur", Material.POTION, "§d", "Fiole de Lueur",
                "§7Vision nocturne.");
    }

    private void add(String id, Material base, String color, String name, String... lore) {
        defs.put(id, new Def(id, base, color, name, List.of(lore)));
    }

    private ItemStack make(String id) {
        Def d = defs.get(id);
        ItemStack it = new ItemStack(d.base());
        ItemMeta m = it.getItemMeta();
        m.setItemModel(NamespacedKey.fromString("civgo:" + id));
        m.setDisplayName(d.color() + d.name());
        m.setLore(d.lore());
        if (m instanceof PotionMeta pm) pm.setBasePotionType(PotionType.NIGHT_VISION);
        if (id.equals("casque_lunaire")) {
            // se porte sur la tête, mais l'armure portée est invisible : le vrai casque 3D est affiché par le plugin
            EquippableComponent eq = m.getEquippable();
            eq.setSlot(EquipmentSlot.HEAD);
            eq.setModel(NamespacedKey.fromString("civgo:vide"));
            m.setEquippable(eq);
            m.setMaxStackSize(1);
        }
        it.setItemMeta(m);
        return it;
    }

    /** Nom du modèle CivGo d'un objet (ex : "epee_lune"), ou null. */
    private String model(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return null;
        ItemMeta m = it.getItemMeta();
        if (!m.hasItemModel()) return null;
        NamespacedKey k = m.getItemModel();
        return k != null && k.getNamespace().equals("civgo") ? k.getKey() : null;
    }

    private boolean noirActive() {
        for (World w : Bukkit.getWorlds()) {
            for (MetadataValue v : w.getMetadata("civgo_noir")) {
                if (v.asBoolean()) return true;
            }
        }
        return false;
    }

    private Economy econ() {
        RegisteredServiceProvider<Economy> r = Bukkit.getServicesManager().getRegistration(Economy.class);
        return r == null ? null : r.getProvider();
    }

    private void bar(Player p, String text) {
        p.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(text));
    }

    private double maxHealth(Player p) {
        Attribute a = Registry.ATTRIBUTE.get(NamespacedKey.minecraft("max_health"));
        AttributeInstance inst = a == null ? null : p.getAttribute(a);
        return inst == null ? 20.0 : inst.getValue();
    }

    private static Particle.DustOptions violet(float size) {
        return new Particle.DustOptions(Color.fromRGB(180, 100, 255), size);
    }

    // ------------------------------------------------------------ Casque Lunaire (vrai casque 3D posé sur la tête)

    private void hatTick() {
        double yawOffset = getConfig().getDouble("casque.yaw-offset", 180);
        boolean invert = getConfig().getBoolean("casque.invert-pitch", true);
        double height = getConfig().getDouble("casque.height-offset", 0.16);
        float scale = (float) getConfig().getDouble("casque.scale", 0.8);
        boolean ownerSees = getConfig().getBoolean("casque.visible-to-owner", true);

        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID id = p.getUniqueId();
            boolean wearing = "casque_lunaire".equals(model(p.getInventory().getHelmet()))
                    && p.getGameMode() != GameMode.SPECTATOR && !p.isInvisible() && !p.isSwimming() && !p.isGliding();
            if (!wearing) {
                removeHat(id);
                continue;
            }
            Location eye = p.getEyeLocation();
            Location at = new Location(p.getWorld(), eye.getX(), eye.getY() + height, eye.getZ(),
                    (float) (eye.getYaw() + yawOffset), invert ? -eye.getPitch() : eye.getPitch());
            ItemDisplay d = hats.get(id);
            if (d == null || !d.isValid() || !d.getWorld().equals(at.getWorld())) {
                if (d != null && d.isValid()) d.remove();
                d = spawnHat(at, scale);
                hats.put(id, d);
                if (!ownerSees) p.hideEntity(this, d);
            } else {
                d.teleport(at);
            }
        }
    }

    private ItemDisplay spawnHat(Location at, float scale) {
        ItemStack shown = make("casque_lunaire");
        return at.getWorld().spawn(at, ItemDisplay.class, dd -> {
            dd.setItemStack(shown);
            dd.setTransformation(new Transformation(new Vector3f(0f, 0f, 0f), new AxisAngle4f(0f, 0f, 0f, 1f),
                    new Vector3f(scale, scale, scale), new AxisAngle4f(0f, 0f, 0f, 1f)));
            dd.setPersistent(false);
            dd.setTeleportDuration(2);
            dd.addScoreboardTag(HAT_TAG);
        });
    }

    private void removeHat(UUID id) {
        ItemDisplay d = hats.remove(id);
        if (d != null && d.isValid()) d.remove();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        removeHat(e.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------ Épée de Lune (dégâts)

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Player p && e.getEntity() instanceof LivingEntity t
                && e.getCause() == EntityDamageEvent.DamageCause.ENTITY_ATTACK
                && "epee_lune".equals(model(p.getInventory().getItemInMainHand()))) {
            double mult = 1.0;
            if (t instanceof Monster) mult *= getConfig().getDouble("epee.monster-multiplier", 1.25);
            boolean noir = noirActive();
            if (noir) mult *= getConfig().getDouble("epee.noir-multiplier", 1.25);
            e.setDamage(e.getDamage() * mult);
            t.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 80, 0, true, false, false));
            t.getWorld().spawnParticle(Particle.END_ROD, t.getLocation().add(0, 1, 0), 10, 0.3, 0.4, 0.3, 0.05);
            if (noir) {
                double heal = Math.min(2.0, e.getDamage() * 0.1);
                p.setHealth(Math.min(maxHealth(p), p.getHealth() + heal));
            }
        }
    }

    // ------------------------------------------------------------ Épée : Onde lunaire / Boussole (clic droit)

    @EventHandler
    public void onUse(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;
        Player p = e.getPlayer();
        String id = model(p.getInventory().getItemInMainHand());
        if (id == null) return;

        if (id.equals("epee_lune")) {
            long now = System.currentTimeMillis();
            long cd = getConfig().getInt("epee.wave-cooldown-seconds", 20) * 1000L;
            Long last = waveCooldown.get(p.getUniqueId());
            if (last != null && now - last < cd) {
                bar(p, "§7Onde lunaire prête dans " + ((cd - (now - last)) / 1000 + 1) + " s");
                return;
            }
            waveCooldown.put(p.getUniqueId(), now);
            lunarWave(p);
        } else if (id.equals("boussole_cratere")) {
            compass(p);
        }
    }

    private void lunarWave(Player p) {
        double r = getConfig().getDouble("epee.wave-radius", 5);
        double dmg = getConfig().getDouble("epee.wave-damage", 8);
        Location c = p.getLocation().add(0, 0.2, 0);
        p.getWorld().playSound(c, Sound.BLOCK_AMETHYST_CLUSTER_BREAK, 1.2f, 0.6f);
        p.getWorld().playSound(c, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1f, 0.7f);
        for (int i = 0; i < 40; i++) {
            double ang = Math.PI * 2 * i / 40;
            p.getWorld().spawnParticle(Particle.END_ROD, c.clone().add(Math.cos(ang) * r, 0.3, Math.sin(ang) * r), 1, 0, 0, 0, 0);
            p.getWorld().spawnParticle(Particle.DUST, c.clone().add(Math.cos(ang) * r * 0.6, 0.4, Math.sin(ang) * r * 0.6), 1, 0, 0, 0, 0, violet(1.5f));
        }
        for (Entity en : p.getNearbyEntities(r, 2.5, r)) {
            if (!(en instanceof Monster m)) continue;
            m.damage(dmg, p);
            Vector kb = m.getLocation().toVector().subtract(p.getLocation().toVector()).setY(0.3);
            if (kb.lengthSquared() > 1.0E-6) m.setVelocity(kb.normalize().multiply(0.9).setY(0.35));
            m.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 100, 0, true, false, false));
        }
        p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 60, 0, true, false, true));
    }

    private void compass(Player p) {
        Plugin n = Bukkit.getPluginManager().getPlugin("CivGoNoirs");
        if (n == null) {
            bar(p, "§7La boussole ne trouve pas le Cratère.");
            return;
        }
        double cx = n.getConfig().getDouble("crater.x", 0), cz = n.getConfig().getDouble("crater.z", 0);
        double dx = cx - p.getLocation().getX(), dz = cz - p.getLocation().getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        double bearing = Math.toDegrees(Math.atan2(-dx, dz));
        double rel = bearing - p.getLocation().getYaw();
        while (rel > 180) rel -= 360;
        while (rel < -180) rel += 360;
        String[] arrows = {"↑", "↗", "→", "↘", "↓", "↙", "←", "↖"};
        int idx = (int) Math.round(rel / 45.0);
        if (idx < 0) idx += 8;
        bar(p, "§6✦ Cratère §f" + arrows[idx % 8] + " §7" + (int) dist + " blocs");
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 1.6f);
    }

    // ------------------------------------------------------------ Arc des Noirs

    @EventHandler(ignoreCancelled = true)
    public void onShoot(EntityShootBowEvent e) {
        if (!(e.getEntity() instanceof Player) || !"arc_noirs".equals(model(e.getBow()))) return;
        if (!(e.getProjectile() instanceof AbstractArrow arrow)) return;
        arrow.setDamage(arrow.getDamage() * getConfig().getDouble("arc.damage-multiplier", 1.7));
        arrow.setCritical(true);
        arrow.setMetadata("civgo_arc", new FixedMetadataValue(this, e.getEntity().getUniqueId().toString()));
        new BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                if (!arrow.isValid() || arrow.isInBlock() || ++t > 100) {
                    cancel();
                    return;
                }
                Location l = arrow.getLocation();
                arrow.getWorld().spawnParticle(Particle.DUST, l, 2, 0.05, 0.05, 0.05, 0, violet(1.1f));
                arrow.getWorld().spawnParticle(Particle.END_ROD, l, 1, 0.02, 0.02, 0.02, 0.0);
            }
        }.runTaskTimer(this, 1L, 1L);
    }

    @EventHandler
    public void onArrowHit(ProjectileHitEvent e) {
        if (!(e.getEntity() instanceof AbstractArrow arrow) || !arrow.hasMetadata("civgo_arc")) return;
        Entity hit = e.getHitEntity();
        Location l = hit != null ? hit.getLocation().add(0, 1.0, 0) : arrow.getLocation();
        World w = l.getWorld();
        w.spawnParticle(Particle.END_ROD, l, 28, 0.35, 0.35, 0.35, 0.07);
        w.spawnParticle(Particle.DUST, l, 36, 0.4, 0.4, 0.4, 0, violet(1.6f));
        w.spawnParticle(Particle.GLOW, l, 12, 0.4, 0.4, 0.4, 0.02);
        w.playSound(l, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1.2f, 1.4f);
        if (hit instanceof Player target && !arrow.getMetadata("civgo_arc").isEmpty()
                && !target.getUniqueId().toString().equals(arrow.getMetadata("civgo_arc").get(0).asString())) {
            int secs = getConfig().getInt("arc.reveal-seconds", 8);
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "cicatrice reveal " + target.getName() + " " + secs);
            target.sendMessage(ChatColor.LIGHT_PURPLE + "Un éclat t'a touché : ta Cicatrice est révélée !");
            Player shooter = Bukkit.getPlayer(UUID.fromString(arrow.getMetadata("civgo_arc").get(0).asString()));
            if (shooter != null) bar(shooter, "§d✦ Cicatrice révélée : §f" + target.getDisplayName());
        }
    }

    // ------------------------------------------------------------ Pioche d'Éclat

    @EventHandler(ignoreCancelled = true)
    public void onPlaceBlock(BlockPlaceEvent e) {
        if (placed.size() > 50000) placed.clear();
        placed.add(key(e.getBlockPlaced()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        ItemStack tool = p.getInventory().getItemInMainHand();
        if (!"pioche_eclat".equals(model(tool)) || p.getGameMode() != GameMode.SURVIVAL) return;
        Block b = e.getBlock();
        if (placed.remove(key(b))) return;                  // pas de gain sur un bloc posé par un joueur
        if (b.getType().getHardness() < 0.5f) return;

        int goal = Math.max(1, getConfig().getInt("pioche.blocks-per-eclat", 150));
        ItemMeta m = tool.getItemMeta();
        int n = m.getPersistentDataContainer().getOrDefault(minedKey, PersistentDataType.INTEGER, 0) + 1;
        boolean reward = n >= goal;
        if (reward) n -= goal;
        m.getPersistentDataContainer().set(minedKey, PersistentDataType.INTEGER, n);
        tool.setItemMeta(m);
        p.getInventory().setItemInMainHand(tool);

        if (reward) {
            Economy ec = econ();
            if (ec != null) ec.depositPlayer(p, 1);
            p.sendMessage(ChatColor.LIGHT_PURPLE + "✦ La pioche extrait un Éclat : +1 Éclat sur ton solde.");
            p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1f, 1.3f);
            p.getWorld().spawnParticle(Particle.END_ROD, b.getLocation().add(0.5, 0.5, 0.5), 16, 0.3, 0.3, 0.3, 0.05);
        } else if (n % 10 == 0) {
            bar(p, "§dPioche d'Éclat §7: " + n + " / " + goal);
        }
    }

    private String key(Block b) {
        return b.getWorld().getName() + "," + b.getX() + "," + b.getY() + "," + b.getZ();
    }

    // ------------------------------------------------------------ commande

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (!s.hasPermission("civgo.objets.admin")) {
            s.sendMessage(ChatColor.RED + "Tu n'as pas la permission.");
            return true;
        }
        if (a.length == 0 || a[0].equalsIgnoreCase("liste")) {
            s.sendMessage(ChatColor.LIGHT_PURPLE + "Objets : " + ChatColor.WHITE + String.join(", ", defs.keySet()));
            s.sendMessage(ChatColor.GRAY + "/objets donner <objet> [joueur]");
            return true;
        }
        if (a[0].equalsIgnoreCase("donner") && a.length >= 2) {
            if (!defs.containsKey(a[1].toLowerCase())) {
                s.sendMessage(ChatColor.RED + "Objet inconnu. Tape /objets liste.");
                return true;
            }
            Player t = a.length > 2 ? Bukkit.getPlayerExact(a[2]) : (s instanceof Player pl ? pl : null);
            if (t == null) {
                s.sendMessage(ChatColor.RED + "Joueur introuvable.");
                return true;
            }
            for (ItemStack over : t.getInventory().addItem(make(a[1].toLowerCase())).values()) {
                t.getWorld().dropItemNaturally(t.getLocation(), over);
            }
            s.sendMessage(ChatColor.LIGHT_PURPLE + defs.get(a[1].toLowerCase()).name() + " donné à " + t.getName() + ".");
            return true;
        }
        s.sendMessage(ChatColor.GRAY + "/objets liste | /objets donner <objet> [joueur]");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String alias, String[] a) {
        if (a.length == 1) return List.of("liste", "donner");
        if (a.length == 2 && a[0].equalsIgnoreCase("donner")) return new ArrayList<>(defs.keySet());
        return null;
    }
}

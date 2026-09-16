package com.example.elytralimit;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ElytraLimit extends JavaPlugin implements Listener, TabExecutor {

    private static final long WARNING_COOLDOWN_MILLIS = 1_000L;
    private static final String BYPASS_PERMISSION = "elytralimit.bypass";

    private final Map<UUID, Long> lastWarning = new ConcurrentHashMap<>();

    private boolean enabled;
    private boolean limitVertical;
    private boolean limitStep;
    private boolean limitVclip;
    private double maxHorizontalPerTick;
    private double maxVerticalPerTick;
    private double maxStepHeight;
    private double vclipMinDistance;
    private double vclipMaxDistance;
    private Component warningMessage;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();
        getServer().getPluginManager().registerEvents(this, this);

        if (getCommand("elytralimit") != null) {
            getCommand("elytralimit").setExecutor(this);
            getCommand("elytralimit").setTabCompleter(this);
        }

        getLogger().info("ElytraLimit включён. Лимит Meteor ElytraFly: "
                + format(maxHorizontalPerTick) + " блоков/тик.");
    }

    @Override
    public void onDisable() {
        lastWarning.clear();
    }

    private void loadSettings() {
        migrateConfigIfNeeded();
        // Meteor's ElytraFly horizontal-speed is blocks per tick, not blocks per second.
        // If the new key is absent, use the original blocks-per-second key for compatibility.
        if (getConfig().contains("max-speed-blocks-per-tick")) {
            maxHorizontalPerTick = readPositive("max-speed-blocks-per-tick", 3.0D);
        } else {
            maxHorizontalPerTick = readPositive("max-speed-blocks-per-second", 60.0D) / 20.0D;
        }

        enabled = getConfig().getBoolean("enabled", true);
        limitVertical = getConfig().getBoolean("limit-vertical", false);
        maxVerticalPerTick = readPositive("max-vertical-blocks-per-second", 10.0D) / 20.0D;

        limitStep = getConfig().getBoolean("limit-step", true);
        maxStepHeight = readRange("max-step-height", 2.3D, 0.0D, 2.3D);

        limitVclip = getConfig().getBoolean("limit-vclip", true);
        vclipMinDistance = readRange("vclip-min-distance", 5.0D, 0.0D, 10.0D);
        vclipMaxDistance = readRange("vclip-max-distance", 10.0D, vclipMinDistance, 10.0D);

        String configuredMessage = getConfig().getString("speed-warning-message", "");
        if (configuredMessage == null || configuredMessage.isBlank()) {
            warningMessage = null;
        } else {
            warningMessage = LegacyComponentSerializer.legacyAmpersand()
                    .deserialize(configuredMessage);
        }
    }

    private void migrateConfigIfNeeded() {
        if (!getConfig().contains("max-speed-blocks-per-tick")) {
            // Existing installations keep their old value, but receive the new key so the
            // owner can immediately tune the limit in the same units as Meteor.
            double migratedPerTick = 3.0D;
            getConfig().set("max-speed-blocks-per-tick", migratedPerTick);
            saveConfig();
            getLogger().info("Добавлен параметр max-speed-blocks-per-tick = "
                    + format(migratedPerTick)
                    + ". Для лимита Meteor 3.0 измените его и выполните /elytralimit reload.");
        }
    }

    private double readPositive(String path, double fallback) {
        double value = getConfig().getDouble(path, fallback);
        if (!Double.isFinite(value) || value <= 0.0D) {
            getLogger().warning("Параметр '" + path + "' должен быть больше 0. Использую "
                    + fallback + ".");
            return fallback;
        }
        return value;
    }

    private double readRange(String path, double fallback, double min, double max) {
        double value = getConfig().getDouble(path, fallback);
        if (!Double.isFinite(value) || value < min || value > max) {
            getLogger().warning("Параметр '" + path + "' должен быть от " + min + " до "
                    + max + ". Использую " + fallback + ".");
            return fallback;
        }
        return value;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (!enabled || player.hasPermission(BYPASS_PERMISSION) || event.getTo() == null
                || !event.getFrom().getWorld().equals(event.getTo().getWorld())) {
            return;
        }

        // Do not interfere with legitimate server/plugin teleports.
        if (event instanceof PlayerTeleportEvent) {
            return;
        }

        Location from = event.getFrom();
        Location to = event.getTo();
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        double dy = to.getY() - from.getY();
        boolean limited = false;
        Location limitedLocation = to.clone();

        // Limit only while gliding. This catches Meteor's regular and packet-style movement.
        if (player.isGliding()) {
            double horizontalSquared = dx * dx + dz * dz;
            double maxHorizontalSquared = maxHorizontalPerTick * maxHorizontalPerTick;
            if (horizontalSquared > maxHorizontalSquared) {
                double scale = maxHorizontalPerTick / Math.sqrt(horizontalSquared);
                limitedLocation.setX(from.getX() + dx * scale);
                limitedLocation.setZ(from.getZ() + dz * scale);
                limited = true;
            }

            // Vertical speed is intentionally ascent-only, as in the original config.
            if (limitVertical && dy > maxVerticalPerTick) {
                limitedLocation.setY(from.getY() + maxVerticalPerTick);
                limited = true;
            }
        }

        // Anti-step: cancel the whole movement packet instead of clamping Y.
        // Clamping could leave the player inside a block and effectively allow clipping.
        if (limitStep && !player.isGliding() && dy > maxStepHeight
                && isLikelyInstantVerticalMove(from, to, dy)) {
            event.setTo(from);
            event.setCancelled(true);
            stopUpwardVelocity(player);
            sendWarning(player);
            return;
        }

        // Anti-vclip: cancel the whole movement packet instead of clamping Y.
        // This prevents a partial correction from still placing the player in a block.
        if (limitVclip && !player.isGliding() && dy < -vclipMinDistance
                && dy >= -vclipMaxDistance && isLikelyInstantVerticalMove(from, to, dy)) {
            event.setTo(from);
            event.setCancelled(true);
            sendWarning(player);
            return;
        }

        if (limited) {
            event.setTo(limitedLocation);
            sendWarning(player);
        }
    }

    private boolean isLikelyInstantVerticalMove(Location from, Location to, double dy) {
        // Ordinary jump/fall movement has a small Y delta. Large deltas are the events targeted here.
        return Math.abs(dy) > 2.3D || from.getBlockX() != to.getBlockX()
                || from.getBlockZ() != to.getBlockZ();
    }

    private void stopUpwardVelocity(Player player) {
        Vector velocity = player.getVelocity();
        if (velocity.getY() > 0.0D) {
            velocity.setY(0.0D);
            player.setVelocity(velocity);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVelocityCheck(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (!enabled || !player.isGliding() || player.hasPermission(BYPASS_PERMISSION)) {
            return;
        }

        Vector velocity = player.getVelocity();
        double horizontalSquared = velocity.getX() * velocity.getX()
                + velocity.getZ() * velocity.getZ();
        if (horizontalSquared > maxHorizontalPerTick * maxHorizontalPerTick
                || limitVertical && velocity.getY() > maxVerticalPerTick) {
            Bukkit.getScheduler().runTask(this, () -> {
                if (!player.isOnline() || !player.isGliding()) {
                    return;
                }
                Vector limitedVelocity = player.getVelocity();
                double currentHorizontalSquared = limitedVelocity.getX() * limitedVelocity.getX()
                        + limitedVelocity.getZ() * limitedVelocity.getZ();
                if (currentHorizontalSquared > maxHorizontalPerTick * maxHorizontalPerTick) {
                    double scale = maxHorizontalPerTick / Math.sqrt(currentHorizontalSquared);
                    limitedVelocity.setX(limitedVelocity.getX() * scale);
                    limitedVelocity.setZ(limitedVelocity.getZ() * scale);
                }
                if (limitVertical && limitedVelocity.getY() > maxVerticalPerTick) {
                    limitedVelocity.setY(maxVerticalPerTick);
                }
                player.setVelocity(limitedVelocity);
                sendWarning(player);
            });
        }
    }

    private boolean shouldSendWarning(Player player) {
        long now = System.currentTimeMillis();
        Long previous = lastWarning.putIfAbsent(player.getUniqueId(), now);
        if (previous == null || now - previous >= WARNING_COOLDOWN_MILLIS) {
            lastWarning.put(player.getUniqueId(), now);
            return true;
        }
        return false;
    }

    private void sendWarning(Player player) {
        if (warningMessage != null && shouldSendWarning(player)) {
            player.sendActionBar(warningMessage);
        }
    }

    private String format(double value) {
        return String.format(java.util.Locale.US, "%.2f", value);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("elytralimit.admin")) {
                sender.sendMessage("§cУ вас нет прав на эту команду.");
                return true;
            }
            reloadConfig();
            loadSettings();
            sender.sendMessage("§aElytraLimit: конфигурация перезагружена. Лимит скорости: §f"
                    + format(maxHorizontalPerTick) + " §aблоков/тик.");
            return true;
        }

        sender.sendMessage("§eИспользование: §f/elytralimit reload");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command,
                                      String alias, String[] args) {
        if (args.length == 1) {
            return Collections.singletonList("reload");
        }
        return new ArrayList<>();
    }
}

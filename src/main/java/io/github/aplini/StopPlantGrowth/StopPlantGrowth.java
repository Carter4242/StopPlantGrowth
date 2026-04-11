package io.github.aplini.StopPlantGrowth;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;

public final class StopPlantGrowth extends JavaPlugin implements Listener {

    private static final byte LOCKED_FLAG = 1;
    private static final String KEY_PREFIX = "igc_";
    private static final String DEFAULT_DISABLE_MESSAGE = "&cGrowth disabled for this plant.";
    private static final String DEFAULT_ENABLE_MESSAGE = "&aGrowth enabled for this plant.";
    private static final String PREFIX = "&7[&aStopPlantGrowth&7] ";

    private Set<Material> upwardGrowthPlants;
    private Set<Material> downwardGrowthPlants;
    private Set<Material> shearSoundPlants;
    private String disableMessage;
    private String enableMessage;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadPlantMaterials();
        getServer().getPluginManager().registerEvents(this, this);

        PluginCommand command = Objects.requireNonNull(getCommand("igc"));
        command.setExecutor(this);
        command.setTabCompleter(this);
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        ItemStack item = event.getItem();
        if (item == null || item.getType() != Material.SHEARS) {
            return;
        }

        Block clickedBlock = event.getClickedBlock();
        if (clickedBlock == null) {
            return;
        }

        GrowthDirection growthDirection = getGrowthDirection(clickedBlock.getType());
        if (growthDirection == null) {
            return;
        }

        Block anchorBlock = getAnchorBlock(clickedBlock, growthDirection);
        boolean locked = hasLock(anchorBlock);
        setLock(anchorBlock, !locked);

        Player player = event.getPlayer();
        if (locked) {
            player.sendMessage(colorize(enableMessage));
        } else {
            player.sendMessage(colorize(disableMessage));
        }

        if (player.getGameMode() == GameMode.SURVIVAL || player.getGameMode() == GameMode.ADVENTURE) {
            damageHeldShears(item, player);
        }

        if (shearSoundPlants.contains(clickedBlock.getType())) {
            player.playSound(clickedBlock.getLocation(), Sound.ENTITY_SHEEP_SHEAR, 1.0f, 1.0f);
        }

        event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockGrow(BlockGrowEvent event) {
        GrowthDirection growthDirection = getGrowthDirection(event.getNewState().getType());
        if (growthDirection == null) {
            return;
        }

        Block growthSource = event.getBlock().getRelative(growthDirection.sourceDirection());
        if (!isTrackedPlant(growthSource.getType(), growthDirection)) {
            return;
        }

        if (hasLockedBlockInColumn(growthSource, growthDirection)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockSpread(BlockSpreadEvent event) {
        GrowthDirection growthDirection = getGrowthDirection(event.getNewState().getType());
        if (growthDirection == null) {
            return;
        }

        Block growthSource = event.getSource();
        if (!isTrackedPlant(growthSource.getType(), growthDirection)) {
            return;
        }

        if (hasLockedBlockInColumn(growthSource, growthDirection)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        clearLock(event.getBlock());
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        clearLock(event.getBlockPlaced());
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockBurn(BlockBurnEvent event) {
        clearLock(event.getBlock());
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockFade(BlockFadeEvent event) {
        clearLock(event.getBlock());
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockPhysics(BlockPhysicsEvent event) {
        Block block = event.getBlock();
        Bukkit.getScheduler().runTask(this, () -> clearLockIfPlantMissing(block));
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        clearLocks(event.blockList());
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        clearLocks(event.blockList());
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        clearLock(event.getBlock());
    }

    private void reloadPlantMaterials() {
        reloadConfig();
        upwardGrowthPlants = parseMaterials(getConfig().getStringList("upward-growth-plants"), "upward-growth-plants");
        downwardGrowthPlants = parseMaterials(getConfig().getStringList("downward-growth-plants"), "downward-growth-plants");
        shearSoundPlants = parseMaterials(getConfig().getStringList("shear-sound-plants"), "shear-sound-plants");
        disableMessage = getConfig().getString("messages.disable-growth", DEFAULT_DISABLE_MESSAGE);
        enableMessage = getConfig().getString("messages.enable-growth", DEFAULT_ENABLE_MESSAGE);
    }

    private Set<Material> parseMaterials(List<String> values, String configKey) {
        Set<Material> materials = EnumSet.noneOf(Material.class);
        for (String value : values) {
            try {
                materials.add(Material.valueOf(value.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException exception) {
                getLogger().warning("Invalid material in \"" + configKey + "\": " + value);
            }
        }
        return materials;
    }

    private GrowthDirection getGrowthDirection(Material material) {
        if (upwardGrowthPlants.contains(material)) {
            return GrowthDirection.UPWARD;
        }
        if (downwardGrowthPlants.contains(material)) {
            return GrowthDirection.DOWNWARD;
        }
        return null;
    }

    private boolean isTrackedPlant(Material material, GrowthDirection growthDirection) {
        return getMaterials(growthDirection).contains(material);
    }

    private Set<Material> getMaterials(GrowthDirection growthDirection) {
        if (growthDirection == GrowthDirection.UPWARD) {
            return upwardGrowthPlants;
        }
        return downwardGrowthPlants;
    }

    private boolean hasLockedBlockInColumn(Block origin, GrowthDirection growthDirection) {
        Block cursor = origin;
        while (isTrackedPlant(cursor.getType(), growthDirection)) {
            if (hasLock(cursor)) {
                return true;
            }
            cursor = cursor.getRelative(BlockFace.UP);
        }

        cursor = origin.getRelative(BlockFace.DOWN);
        while (isTrackedPlant(cursor.getType(), growthDirection)) {
            if (hasLock(cursor)) {
                return true;
            }
            cursor = cursor.getRelative(BlockFace.DOWN);
        }
        return false;
    }

    private Block getAnchorBlock(Block origin, GrowthDirection growthDirection) {
        Block cursor = origin;
        Block next = cursor.getRelative(growthDirection.anchorDirection());
        while (isTrackedPlant(next.getType(), growthDirection)) {
            cursor = next;
            next = cursor.getRelative(growthDirection.anchorDirection());
        }
        return cursor;
    }

    private boolean hasLock(Block block) {
        PersistentDataContainer container = block.getChunk().getPersistentDataContainer();
        return container.has(getBlockKey(block), PersistentDataType.BYTE);
    }

    private void clearLocks(List<Block> blocks) {
        for (Block block : blocks) {
            clearLock(block);
        }
    }

    private void clearLock(Block block) {
        PersistentDataContainer container = block.getChunk().getPersistentDataContainer();
        NamespacedKey key = getBlockKey(block);
        if (container.has(key, PersistentDataType.BYTE)) {
            container.remove(key);
        }
    }

    private void clearLockIfPlantMissing(Block block) {
        if (getGrowthDirection(block.getType()) != null) {
            return;
        }
        clearLock(block);
    }

    private void setLock(Block block, boolean locked) {
        PersistentDataContainer container = block.getChunk().getPersistentDataContainer();
        NamespacedKey key = getBlockKey(block);
        if (locked) {
            container.set(key, PersistentDataType.BYTE, LOCKED_FLAG);
            return;
        }
        clearLock(block);
    }

    private NamespacedKey getBlockKey(Block block) {
        return new NamespacedKey(this, KEY_PREFIX + block.getX() + "_" + block.getY() + "_" + block.getZ());
    }

    private enum GrowthDirection {
        UPWARD(BlockFace.DOWN, BlockFace.DOWN),
        DOWNWARD(BlockFace.UP, BlockFace.UP);

        private final BlockFace sourceDirection;
        private final BlockFace anchorDirection;

        GrowthDirection(BlockFace sourceDirection, BlockFace anchorDirection) {
            this.sourceDirection = sourceDirection;
            this.anchorDirection = anchorDirection;
        }

        public BlockFace sourceDirection() {
            return sourceDirection;
        }

        public BlockFace anchorDirection() {
            return anchorDirection;
        }
    }


    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if(args.length == 1){
            List<String> list = new ArrayList<>();
            addTabComplete(list, args[0], "reload");
            addTabComplete(list, args[0], "chunk");
            addTabComplete(list, args[0], "clear");
            return list;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("clear")) {
            List<String> list = new ArrayList<>();
            addTabComplete(list, args[1], "1");
            addTabComplete(list, args[1], "2");
            addTabComplete(list, args[1], "3");
            addTabComplete(list, args[1], "4");
            addTabComplete(list, args[1], "5");
            return list;
        }
        return Collections.emptyList();
    }
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args){

        if(args.length == 0){
            sender.sendMessage(
                    "\n"+
                            colorize(PREFIX + "&fCommands:\n")+
                            colorize("  &7- &a/igc reload &8- &fReload config\n")+
                            colorize("  &7- &a/igc chunk  &8- &fList locked plants in your chunk\n")+
                            colorize("  &7- &a/igc clear [radius] &8- &fClear chunk locks (radius 1-5)\n")
            );
            return true;
        }

        else if(args[0].equalsIgnoreCase("reload")){
            if(!sender.hasPermission("StopPlantGrowth.reload")){
                sender.sendMessage(colorize(PREFIX + "&cYou do not have permission."));
                return true;
            }

            reloadPlantMaterials();
            sender.sendMessage(colorize(PREFIX + "&aConfiguration reloaded."));
            return true;
        }

        else if(args[0].equalsIgnoreCase("chunk")){
            if(!sender.hasPermission("StopPlantGrowth.chunk")){
                sender.sendMessage(colorize(PREFIX + "&cYou do not have permission."));
                return true;
            }

            if(!(sender instanceof Player player)){
                sender.sendMessage(colorize(PREFIX + "&cOnly players can use this command."));
                return true;
            }

            List<LockedPlantEntry> lockedPlants = getLockedPlantsInChunk(player.getLocation().getChunk());
            if(lockedPlants.isEmpty()){
                sender.sendMessage(colorize(PREFIX + "&eNo locked plants found in this chunk."));
                return true;
            }

            sender.sendMessage(colorize(PREFIX + "&aLocked plants in this chunk:"));
            for(LockedPlantEntry lockedPlant : lockedPlants){
                player.sendMessage(createLockedPlantMessage(lockedPlant));
            }
            return true;
        }

        else if(args[0].equalsIgnoreCase("clear")){
            if(!sender.hasPermission("StopPlantGrowth.clear")){
                sender.sendMessage(colorize(PREFIX + "&cYou do not have permission."));
                return true;
            }

            if(!(sender instanceof Player player)){
                sender.sendMessage(colorize(PREFIX + "&cOnly players can use this command."));
                return true;
            }

            int radius = 1;
            if (args.length >= 2) {
                try {
                    radius = Integer.parseInt(args[1]);
                } catch (NumberFormatException exception) {
                    sender.sendMessage(colorize(PREFIX + "&cRadius must be a number from 1 to 5."));
                    return true;
                }
            }

            if (radius < 1 || radius > 5) {
                sender.sendMessage(colorize(PREFIX + "&cRadius must be between 1 and 5."));
                return true;
            }

            int removedCount = clearLockedPlantsInRadius(player.getLocation().getChunk(), radius);
            sender.sendMessage(colorize(PREFIX + "&aRemoved &f" + removedCount + "&a lock(s) in radius &f" + radius + "&a."));
            return true;
        }

        sender.sendMessage(colorize(PREFIX + "&cUnknown subcommand. Use &f/igc"));
        return true;
    }

    private void addTabComplete(List<String> list, String input, String value){
        if(value.startsWith(input.toLowerCase(Locale.ROOT))){
            list.add(value);
        }
    }

    private List<LockedPlantEntry> getLockedPlantsInChunk(Chunk chunk){
        List<LockedPlantEntry> lockedPlants = new ArrayList<>();
        for(NamespacedKey key : chunk.getPersistentDataContainer().getKeys()){
            if(!isLockKey(key)){
                continue;
            }

            LockedPlantEntry lockedPlant = parseLockedPlant(chunk, key);
            if(lockedPlant != null){
                lockedPlants.add(lockedPlant);
            }
        }
        return lockedPlants;
    }

    private int clearLockedPlantsInChunk(Chunk chunk){
        List<NamespacedKey> keysToRemove = new ArrayList<>();
        for(NamespacedKey key : chunk.getPersistentDataContainer().getKeys()){
            if(isLockKey(key)){
                keysToRemove.add(key);
            }
        }

        PersistentDataContainer container = chunk.getPersistentDataContainer();
        for(NamespacedKey key : keysToRemove){
            container.remove(key);
        }
        return keysToRemove.size();
    }

    private int clearLockedPlantsInRadius(Chunk centerChunk, int radius) {
        int removed = 0;
        int chunkRadius = radius - 1;
        World world = centerChunk.getWorld();
        int centerX = centerChunk.getX();
        int centerZ = centerChunk.getZ();

        for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
            for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
                Chunk chunk = world.getChunkAt(centerX + dx, centerZ + dz);
                removed += clearLockedPlantsInChunk(chunk);
            }
        }
        return removed;
    }

    private boolean isLockKey(NamespacedKey key){
        return key.getNamespace().equals(getName().toLowerCase(Locale.ROOT)) && key.getKey().startsWith(KEY_PREFIX);
    }

    private LockedPlantEntry parseLockedPlant(Chunk chunk, NamespacedKey key){
        String rawKey = key.getKey();
        if(!rawKey.startsWith(KEY_PREFIX)){
            return null;
        }

        String[] parts = rawKey.substring(KEY_PREFIX.length()).split("_");
        if(parts.length != 3){
            return null;
        }

        try {
            int x = Integer.parseInt(parts[0]);
            int y = Integer.parseInt(parts[1]);
            int z = Integer.parseInt(parts[2]);
            Material material = chunk.getWorld().getBlockAt(x, y, z).getType();
            return new LockedPlantEntry(x, y, z, material);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private String createLockedPlantMessage(LockedPlantEntry lockedPlant){
        String coords = lockedPlant.x() + "/" + lockedPlant.y() + "/" + lockedPlant.z();
        return colorize("&7  - [&f" + coords + "&7] &f" + lockedPlant.material());
    }

    private record LockedPlantEntry(int x, int y, int z, Material material) {
    }

    private String colorize(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    private void damageHeldShears(ItemStack shears, Player player) {
        ItemMeta itemMeta = shears.getItemMeta();
        if (!(itemMeta instanceof Damageable damageableMeta)) {
            return;
        }

        int newDamage = damageableMeta.getDamage() + 1;
        if (newDamage >= shears.getType().getMaxDurability()) {
            player.getInventory().setItemInMainHand(null);
            player.playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 1.0f, 1.0f);
            return;
        }

        damageableMeta.setDamage(newDamage);
        shears.setItemMeta(itemMeta);
        player.getInventory().setItemInMainHand(shears);
    }
}

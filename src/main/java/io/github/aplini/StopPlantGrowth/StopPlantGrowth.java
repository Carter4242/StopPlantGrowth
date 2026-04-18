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
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.UUID;

public final class StopPlantGrowth extends JavaPlugin implements Listener {

    private static final byte LOCKED_FLAG = 1;
    private static final String KEY_PREFIX = "igc_";
    private static final int MIN_CHUNK_RADIUS = 1;
    private static final int MAX_CHUNK_RADIUS = 9;
    private static final int MIN_BLOCK_RADIUS = 1;
    private static final int MAX_BLOCK_RADIUS = 128;
    private static final String DEFAULT_DISABLE_MESSAGE = "&cLocked &f%plant% &cprotections.";
    private static final String DEFAULT_ENABLE_MESSAGE = "&aUnlocked &f%plant% &aprotections.";
    private static final String DEFAULT_LOCK_REMOVED_MESSAGE = "&eGrowth lock removed for &f%plant%&e";
    private static final String DEFAULT_EXPLOSION_LOCKS_REMOVED_MESSAGE = "&eRemoved &f%count%&e growth lock(s) from explosion damage &7(&f%types%&7)";
    private static final String DEFAULT_LOCKED_PROTECTION_BREAK_MESSAGE = "&cThis &f%plant%&c is locked and can't be broken. Unlock it with shears first.";
    private static final String DEFAULT_LOCKED_PROTECTION_TRAMPLE_MESSAGE = "&cThis &f%plant%&c is locked and can't be trampled. Unlock it with shears first.";
    private static final String DEFAULT_LOCKED_PROTECTION_HARVEST_MESSAGE = "&cThis &f%plant%&c is locked and can't be harvested. Unlock it with shears first.";
    private static final String DEFAULT_BONEMEAL_LOCKED_MESSAGE = "&cThis &f%plant%&c is locked and can't be grown with bonemeal. Unlock it with shears first.";
    private static final String PREFIX = "&7[&aStopPlantGrowth&7] ";

    private Set<Material> upwardGrowthPlants;
    private Set<Material> downwardGrowthPlants;
    private Set<Material> ageGrowthPlants;
    private Set<Material> saplingGrowthPlants;
    private Set<Material> mushroomGrowthPlants;
    private final Set<UUID> breakRestrictionBypassPlayers = new HashSet<>();
    private String disableMessage;
    private String enableMessage;
    private String lockRemovedMessage;
    private String explosionLocksRemovedMessage;
    private String lockedProtectionBreakMessage;
    private String lockedProtectionTrampleMessage;
    private String lockedProtectionHarvestMessage;
    private String bonemealLockedMessage;
    private String breakBypassEnabledMessage;
    private String breakBypassDisabledMessage;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadPlantMaterials();
        getServer().getPluginManager().registerEvents(this, this);

        PluginCommand command = Objects.requireNonNull(getCommand("spg"));
        command.setExecutor(this);
        command.setTabCompleter(this);
    }

    @Override
    public void onDisable() {
        breakRestrictionBypassPlayers.clear();
    }

    @EventHandler(ignoreCancelled = true)
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
        boolean ageBasedPlant = isAgeBasedPlant(clickedBlock.getType());
        boolean saplingPlant = isSaplingPlant(clickedBlock.getType());
        boolean mushroomPlant = isMushroomPlant(clickedBlock.getType());
        if (growthDirection == null && !ageBasedPlant && !saplingPlant && !mushroomPlant) {
            return;
        }

        Block lockBlock = (ageBasedPlant || saplingPlant || mushroomPlant) ? clickedBlock : getAnchorBlock(clickedBlock, growthDirection);
        boolean locked = hasLock(lockBlock);
        Player player = event.getPlayer();

        setLock(lockBlock, !locked);

        String plantName = formatPlantName(clickedBlock.getType());
        if (locked) {
            player.sendMessage(formatPlayerMessage(enableMessage, plantName));
        } else {
            player.sendMessage(formatPlayerMessage(disableMessage, plantName));
        }

        player.playSound(clickedBlock.getLocation(), Sound.ENTITY_SHEEP_SHEAR, 1.0f, 1.0f);

        event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockGrow(BlockGrowEvent event) {
        GrowthDirection growthDirection = getGrowthDirection(event.getNewState().getType());
        if (growthDirection != null) {
            Block growthSource = event.getBlock().getRelative(growthDirection.sourceDirection());
            if (!isTrackedPlant(growthSource.getType(), growthDirection)) {
                return;
            }

            if (hasLockedBlockInColumn(growthSource, growthDirection)) {
                event.setCancelled(true);
            }
            return;
        }

        if (isAgeBasedPlant(event.getBlock().getType()) && hasLock(event.getBlock())) {
            event.setCancelled(true);
            return;
        }

        if (isStemFruitGrowth(event) && hasLockedAdjacentStem(event.getBlock())) {
            event.setCancelled(true);
        }

        if (isSaplingPlant(event.getBlock().getType()) && hasLock(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockSpread(BlockSpreadEvent event) {
        if (isMushroomPlant(event.getSource().getType()) && hasLock(event.getSource())) {
            event.setCancelled(true);
            return;
        }

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
        Block block = event.getBlock();
        boolean bypassEnabled = breakRestrictionBypassPlayers.contains(event.getPlayer().getUniqueId());
        if (!bypassEnabled && hasLock(block) && isHarvestableCrop(block.getType())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(formatPlayerMessage(PREFIX + lockedProtectionBreakMessage, formatPlantName(block.getType())));
            return;
        }
        if (clearLock(block)) {
            String plantName = formatPlantName(block.getType());
            event.getPlayer().sendMessage(formatPlayerMessage(lockRemovedMessage, plantName));
        }
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
        RemovalSummary summary = clearLocksWithSummary(event.blockList());
        if (summary.count() <= 0) {
            return;
        }

        if (event.getEntity() instanceof TNTPrimed primedTnt && primedTnt.getSource() instanceof Player player) {
            player.sendMessage(formatPlayerMessage(explosionLocksRemovedMessage, null, summary.count(), summary.formattedTypes()));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        if (event.getBlock().getType() == Material.FARMLAND) {
            Block cropAbove = event.getBlock().getRelative(BlockFace.UP);
            if (hasLock(cropAbove) && isTrampleSensitiveCrop(cropAbove.getType())) {
                event.setCancelled(true);
                return;
            }
        }
        clearLock(event.getBlock());
    }

    @EventHandler(ignoreCancelled = true)
    public void onStructureGrow(StructureGrowEvent event) {
        Material lockedSource = findLockedStructureSourceNear(event.getLocation(), event.getSpecies());
        if (lockedSource == null) {
            return;
        }

        event.setCancelled(true);
        if (event.isFromBonemeal() && event.getPlayer() != null) {
            event.getPlayer().sendMessage(formatPlayerMessage(bonemealLockedMessage, formatPlantName(lockedSource)));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerHarvestOrTrample(PlayerInteractEvent event) {
        Action action = event.getAction();
        Block clickedBlock = event.getClickedBlock();
        if (clickedBlock == null) {
            return;
        }

        if (action == Action.PHYSICAL && clickedBlock.getType() == Material.FARMLAND) {
            Block cropAbove = clickedBlock.getRelative(BlockFace.UP);
            if (hasLock(cropAbove) && isTrampleSensitiveCrop(cropAbove.getType())) {
                event.setCancelled(true);
                event.getPlayer().sendMessage(formatPlayerMessage(PREFIX + lockedProtectionTrampleMessage, formatPlantName(cropAbove.getType())));
            }
            return;
        }

        if (action != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND) {
            return;
        }

        if (isBonemeal(event.getItem()) && isProtectedByLock(clickedBlock)) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(formatPlayerMessage(bonemealLockedMessage, formatPlantName(clickedBlock.getType())));
            return;
        }

        if (event.getItem() != null && event.getItem().getType() == Material.SHEARS) {
            return;
        }

        if (isRightClickHarvestable(clickedBlock.getType()) && isProtectedByLock(clickedBlock)) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(formatPlayerMessage(PREFIX + lockedProtectionHarvestMessage, formatPlantName(clickedBlock.getType())));
        }
    }

    private void reloadPlantMaterials() {
        reloadConfig();
        upwardGrowthPlants = parseMaterials(getConfig().getStringList("upward-growth-plants"), "upward-growth-plants");
        downwardGrowthPlants = parseMaterials(getConfig().getStringList("downward-growth-plants"), "downward-growth-plants");
        ageGrowthPlants = parseMaterials(getConfig().getStringList("age-growth-plants"), "age-growth-plants");
        saplingGrowthPlants = parseMaterials(getConfig().getStringList("sapling-growth-plants"), "sapling-growth-plants");
        mushroomGrowthPlants = parseMaterials(getConfig().getStringList("mushroom-growth-plants"), "mushroom-growth-plants");
        disableMessage = getConfig().getString("messages.disable-growth", DEFAULT_DISABLE_MESSAGE);
        enableMessage = getConfig().getString("messages.enable-growth", DEFAULT_ENABLE_MESSAGE);
        lockRemovedMessage = getConfig().getString("messages.lock-removed", DEFAULT_LOCK_REMOVED_MESSAGE);
        explosionLocksRemovedMessage = getConfig().getString("messages.explosion-locks-removed", DEFAULT_EXPLOSION_LOCKS_REMOVED_MESSAGE);
        lockedProtectionBreakMessage = getConfig().getString("messages.locked-protection-break", DEFAULT_LOCKED_PROTECTION_BREAK_MESSAGE);
        lockedProtectionTrampleMessage = getConfig().getString("messages.locked-protection-trample", DEFAULT_LOCKED_PROTECTION_TRAMPLE_MESSAGE);
        lockedProtectionHarvestMessage = getConfig().getString("messages.locked-protection-harvest", DEFAULT_LOCKED_PROTECTION_HARVEST_MESSAGE);
        bonemealLockedMessage = getConfig().getString("messages.bonemeal-locked", DEFAULT_BONEMEAL_LOCKED_MESSAGE);
        breakBypassEnabledMessage = getConfig().getString("messages.break-bypass-enabled", "&eBreak protection bypass enabled for your session.");
        breakBypassDisabledMessage = getConfig().getString("messages.break-bypass-disabled", "&aBreak protection bypass disabled for your session.");
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

    private int clearLocks(List<Block> blocks) {
        int removed = 0;
        for (Block block : blocks) {
            if (clearLock(block)) {
                removed++;
            }
        }
        return removed;
    }

    private RemovalSummary clearLocksWithSummary(List<Block> blocks) {
        int removed = 0;
        Set<Material> types = EnumSet.noneOf(Material.class);
        for (Block block : blocks) {
            if (clearLock(block)) {
                removed++;
                types.add(block.getType());
            }
        }
        return new RemovalSummary(removed, types);
    }

    private boolean clearLock(Block block) {
        PersistentDataContainer container = block.getChunk().getPersistentDataContainer();
        NamespacedKey key = getBlockKey(block);
        if (container.has(key, PersistentDataType.BYTE)) {
            container.remove(key);
            return true;
        }
        return false;
    }

    private void clearLockIfPlantMissing(Block block) {
        if (isTrackedMaterial(block.getType())) {
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
            addTabComplete(list, args[0], "stats");
            addTabComplete(list, args[0], "clear");
            addTabComplete(list, args[0], "breakbypass");
            return list;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("breakbypass")) {
            List<String> list = new ArrayList<>();
            addTabComplete(list, args[1], "on");
            addTabComplete(list, args[1], "off");
            addTabComplete(list, args[1], "toggle");
            addTabComplete(list, args[1], "status");
            return list;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("stats")) {
            List<String> list = new ArrayList<>();
            addRadiusCompletions(list, args[1]);
            return list;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("clear")) {
            List<String> list = new ArrayList<>();
            addTabComplete(list, args[1], "chunks");
            addTabComplete(list, args[1], "blocks");
            return list;
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("clear") && args[1].equalsIgnoreCase("chunks")) {
            List<String> list = new ArrayList<>();
            addRadiusCompletions(list, args[2]);
            addTypeCompletions(list, args[2]);
            return list;
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("clear") && args[1].equalsIgnoreCase("blocks")) {
            List<String> list = new ArrayList<>();
            addBlockRadiusCompletions(list, args[2]);
            addTypeCompletions(list, args[2]);
            return list;
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("clear") && args[1].equalsIgnoreCase("chunks")) {
            List<String> list = new ArrayList<>();
            addRadiusCompletions(list, args[3]);
            addTypeCompletions(list, args[3]);
            return list;
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("clear") && args[1].equalsIgnoreCase("blocks")) {
            List<String> list = new ArrayList<>();
            addBlockRadiusCompletions(list, args[3]);
            addTypeCompletions(list, args[3]);
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
                            colorize("  &7- &a/spg reload &8- &fReload config\n")+
                            colorize("  &7- &a/spg chunk  &8- &fList locked plants in your chunk\n")+
                            colorize("  &7- &a/spg stats [radius] &8- &fShow lock stats (radius 1-9)\n")+
                            colorize("  &7- &a/spg clear chunks [radius] [type] &8- &fClear by chunk radius (1-9)\n")+
                            colorize("  &7- &a/spg clear blocks [radius] [type] &8- &fClear by block radius (1-128)\n")+
                            colorize("  &7- &a/spg breakbypass [on|off|toggle|status] &8- &fSession break bypass\n")
            );
            return true;
        }

        else if(args[0].equalsIgnoreCase("reload")){
            if(!sender.hasPermission("StopPlantGrowth.reload")){
                sender.sendMessage(colorize(PREFIX + "&cYou do not have permission to use &f/spg reload&c."));
                return true;
            }

            reloadPlantMaterials();
            sender.sendMessage(colorize(PREFIX + "&aConfiguration reloaded."));
            return true;
        }

        else if(args[0].equalsIgnoreCase("chunk")){
            if(!sender.hasPermission("StopPlantGrowth.chunk")){
                sender.sendMessage(colorize(PREFIX + "&cYou do not have permission to use &f/spg chunk&c."));
                return true;
            }

            if(!(sender instanceof Player player)){
                sender.sendMessage(colorize(PREFIX + "&cOnly players can use &f/spg chunk&c."));
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

        else if(args[0].equalsIgnoreCase("stats")){
            if(!sender.hasPermission("StopPlantGrowth.stats")){
                sender.sendMessage(colorize(PREFIX + "&cYou do not have permission to use &f/spg stats&c."));
                return true;
            }

            if(!(sender instanceof Player player)){
                sender.sendMessage(colorize(PREFIX + "&cOnly players can use &f/spg stats&c."));
                return true;
            }

            int radius = parseRadius(args, 1, 1);
            if (radius < MIN_CHUNK_RADIUS || radius > MAX_CHUNK_RADIUS) {
                sender.sendMessage(colorize(PREFIX + "&cChunk radius must be between " + MIN_CHUNK_RADIUS + " and " + MAX_CHUNK_RADIUS + "."));
                return true;
            }

            List<LockedPlantEntry> lockedPlants = getLockedPlantsInRadius(player.getLocation().getChunk(), radius);
            if (lockedPlants.isEmpty()) {
                sender.sendMessage(colorize(PREFIX + "&eNo locked plants found in chunk radius &f" + radius + "&e."));
                return true;
            }

            Map<Material, Integer> byMaterial = new HashMap<>();
            for (LockedPlantEntry entry : lockedPlants) {
                byMaterial.merge(entry.material(), 1, Integer::sum);
            }

            sender.sendMessage(colorize(PREFIX + "&aLocked plants in chunk radius &f" + radius + "&a: &f" + lockedPlants.size()));
            byMaterial.entrySet().stream()
                    .sorted((left, right) -> Integer.compare(right.getValue(), left.getValue()))
                    .forEach(entry -> sender.sendMessage(colorize("&7  - &f" + entry.getKey() + "&7: &a" + entry.getValue())));
            return true;
        }

        else if(args[0].equalsIgnoreCase("clear")){
            if(!sender.hasPermission("StopPlantGrowth.clear")){
                sender.sendMessage(colorize(PREFIX + "&cYou do not have permission to use &f/spg clear&c."));
                return true;
            }

            if(!(sender instanceof Player player)){
                sender.sendMessage(colorize(PREFIX + "&cOnly players can use &f/spg clear&c."));
                return true;
            }

            if (args.length == 1) {
                sender.sendMessage(colorize(PREFIX + "&fClear modes:"));
                sender.sendMessage(colorize("&7  - &a/spg clear chunks [radius] [type] &8(1-9 chunks)"));
                sender.sendMessage(colorize("&7  - &a/spg clear blocks [radius] [type] &8(1-128 blocks)"));
                return true;
            }

            String clearMode = args[1].toLowerCase(Locale.ROOT);
            if (!clearMode.equals("chunks") && !clearMode.equals("blocks")) {
                sender.sendMessage(colorize(PREFIX + "&cUsage: /spg clear <chunks|blocks> [radius] [type]"));
                return true;
            }

            int minRadius = clearMode.equals("chunks") ? MIN_CHUNK_RADIUS : MIN_BLOCK_RADIUS;
            int maxRadius = clearMode.equals("chunks") ? MAX_CHUNK_RADIUS : MAX_BLOCK_RADIUS;

            if (args.length > 4) {
                sender.sendMessage(colorize(PREFIX + "&cUsage: /spg clear <chunks|blocks> [radius] [type]"));
                return true;
            }

            int radius = 1;
            Material typeFilter = null;
            boolean radiusProvided = false;
            boolean typeProvided = false;
            for (int index = 2; index < args.length; index++) {
                String token = args[index];
                Integer parsedRadius = tryParseInt(token);
                if (parsedRadius != null) {
                    if (radiusProvided) {
                        sender.sendMessage(colorize(PREFIX + "&cRadius can only be provided once."));
                        return true;
                    }
                    radius = parsedRadius;
                    radiusProvided = true;
                    continue;
                }

                Material parsedType = parseTypeFilter(token);
                if (parsedType == null && !token.equalsIgnoreCase("all")) {
                    sender.sendMessage(colorize(PREFIX + "&cUnknown type &f" + token + "&c. Use a tracked material or &fall&c."));
                    return true;
                }
                if (typeProvided) {
                    sender.sendMessage(colorize(PREFIX + "&cType can only be provided once."));
                    return true;
                }
                typeFilter = parsedType;
                typeProvided = true;
            }

            if (radius < minRadius || radius > maxRadius) {
                String unitLabel = clearMode.equals("chunks") ? "Chunk radius" : "Block radius";
                sender.sendMessage(colorize(PREFIX + "&c" + unitLabel + " must be between " + minRadius + " and " + maxRadius + "."));
                return true;
            }

            int removedCount = clearMode.equals("chunks")
                    ? clearLockedPlantsInRadius(player.getLocation().getChunk(), radius, typeFilter)
                    : clearLockedPlantsInBlockRadius(player.getLocation(), radius, typeFilter);
            String typeText = typeFilter == null ? "all types" : typeFilter.name();
            String unit = clearMode.equals("chunks") ? "chunk" : "block";
            sender.sendMessage(colorize(PREFIX + "&aRemoved &f" + removedCount + "&a lock(s) in " + unit + " radius &f" + radius + "&a for &f" + typeText + "&a."));
            return true;
        }

        else if (args[0].equalsIgnoreCase("breakbypass")) {
            if (!sender.hasPermission("StopPlantGrowth.breakbypass")) {
                sender.sendMessage(colorize(PREFIX + "&cYou do not have permission to use &f/spg breakbypass&c."));
                return true;
            }
            if (!(sender instanceof Player player)) {
                sender.sendMessage(colorize(PREFIX + "&cOnly players can use &f/spg breakbypass&c."));
                return true;
            }

            String mode = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "toggle";
            UUID uuid = player.getUniqueId();
            boolean enabled = breakRestrictionBypassPlayers.contains(uuid);

            if (mode.equals("on")) {
                if (!enabled) {
                    breakRestrictionBypassPlayers.add(uuid);
                }
                player.sendMessage(colorize(PREFIX + breakBypassEnabledMessage));
                return true;
            }

            if (mode.equals("off")) {
                breakRestrictionBypassPlayers.remove(uuid);
                player.sendMessage(colorize(PREFIX + breakBypassDisabledMessage));
                return true;
            }

            if (mode.equals("status")) {
                String status = enabled ? "&eenabled" : "&adisabled";
                player.sendMessage(colorize(PREFIX + "&fBreak protection bypass is currently " + status + "&f."));
                return true;
            }

            if (!mode.equals("toggle")) {
                player.sendMessage(colorize(PREFIX + "&cUsage: /spg breakbypass [on|off|toggle|status]"));
                return true;
            }

            if (enabled) {
                breakRestrictionBypassPlayers.remove(uuid);
                player.sendMessage(colorize(PREFIX + breakBypassDisabledMessage));
            } else {
                breakRestrictionBypassPlayers.add(uuid);
                player.sendMessage(colorize(PREFIX + breakBypassEnabledMessage));
            }
            return true;
        }

        sender.sendMessage(colorize(PREFIX + "&cUnknown subcommand. Use &f/spg"));
        return true;
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        breakRestrictionBypassPlayers.remove(event.getPlayer().getUniqueId());
    }

    private void addTabComplete(List<String> list, String input, String value){
        if(value.toLowerCase(Locale.ROOT).startsWith(input.toLowerCase(Locale.ROOT))){
            list.add(value);
        }
    }

    private void addRadiusCompletions(List<String> list, String input) {
        for (int radius = MIN_CHUNK_RADIUS; radius <= MAX_CHUNK_RADIUS; radius++) {
            addTabComplete(list, input, String.valueOf(radius));
        }
    }

    private void addBlockRadiusCompletions(List<String> list, String input) {
        addTabComplete(list, input, "8");
        addTabComplete(list, input, "16");
        addTabComplete(list, input, "32");
        addTabComplete(list, input, "64");
        addTabComplete(list, input, "128");
    }

    private void addTypeCompletions(List<String> list, String input) {
        addTabComplete(list, input, "all");
        for (Material material : getTrackedMaterials()) {
            addTabComplete(list, input, material.name());
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
        return clearLockedPlantsInChunk(chunk, null);
    }

    private int clearLockedPlantsInChunk(Chunk chunk, Material typeFilter){
        List<NamespacedKey> keysToRemove = new ArrayList<>();
        for(NamespacedKey key : chunk.getPersistentDataContainer().getKeys()){
            if(!isLockKey(key)){
                continue;
            }

            if (typeFilter == null) {
                keysToRemove.add(key);
                continue;
            }

            LockedPlantEntry entry = parseLockedPlant(chunk, key);
            if (entry != null && entry.material() == typeFilter) {
                keysToRemove.add(key);
            }
        }

        PersistentDataContainer container = chunk.getPersistentDataContainer();
        for(NamespacedKey key : keysToRemove){
            container.remove(key);
        }
        return keysToRemove.size();
    }

    private int clearLockedPlantsInRadius(Chunk centerChunk, int radius, Material typeFilter) {
        int removed = 0;
        int chunkRadius = radius - 1;
        World world = centerChunk.getWorld();
        int centerX = centerChunk.getX();
        int centerZ = centerChunk.getZ();

        for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
            for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
                Chunk chunk = world.getChunkAt(centerX + dx, centerZ + dz);
                removed += clearLockedPlantsInChunk(chunk, typeFilter);
            }
        }
        return removed;
    }

    private int clearLockedPlantsInBlockRadius(Location center, int radius, Material typeFilter) {
        World world = center.getWorld();
        if (world == null) {
            return 0;
        }

        int centerX = center.getBlockX();
        int centerZ = center.getBlockZ();
        int radiusSquared = radius * radius;
        int chunkRadius = (int) Math.ceil(radius / 16.0);
        int centerChunkX = centerX >> 4;
        int centerChunkZ = centerZ >> 4;

        int removed = 0;
        for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
            for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
                Chunk chunk = world.getChunkAt(centerChunkX + dx, centerChunkZ + dz);
                removed += clearLockedPlantsInChunkByBlockRadius(chunk, centerX, centerZ, radiusSquared, typeFilter);
            }
        }
        return removed;
    }

    private int clearLockedPlantsInChunkByBlockRadius(Chunk chunk, int centerX, int centerZ, int radiusSquared, Material typeFilter) {
        List<NamespacedKey> keysToRemove = new ArrayList<>();
        for (NamespacedKey key : chunk.getPersistentDataContainer().getKeys()) {
            if (!isLockKey(key)) {
                continue;
            }

            LockedPlantEntry entry = parseLockedPlant(chunk, key);
            if (entry == null) {
                continue;
            }

            if (typeFilter != null && entry.material() != typeFilter) {
                continue;
            }

            int dx = entry.x() - centerX;
            int dz = entry.z() - centerZ;
            if ((dx * dx) + (dz * dz) <= radiusSquared) {
                keysToRemove.add(key);
            }
        }

        PersistentDataContainer container = chunk.getPersistentDataContainer();
        for (NamespacedKey key : keysToRemove) {
            container.remove(key);
        }
        return keysToRemove.size();
    }

    private List<LockedPlantEntry> getLockedPlantsInRadius(Chunk centerChunk, int radius) {
        List<LockedPlantEntry> lockedPlants = new ArrayList<>();
        int chunkRadius = radius - 1;
        World world = centerChunk.getWorld();
        int centerX = centerChunk.getX();
        int centerZ = centerChunk.getZ();

        for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
            for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
                Chunk chunk = world.getChunkAt(centerX + dx, centerZ + dz);
                lockedPlants.addAll(getLockedPlantsInChunk(chunk));
            }
        }
        return lockedPlants;
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

    private Set<Material> getTrackedMaterials() {
        Set<Material> tracked = EnumSet.noneOf(Material.class);
        tracked.addAll(upwardGrowthPlants);
        tracked.addAll(downwardGrowthPlants);
        tracked.addAll(ageGrowthPlants);
        tracked.addAll(saplingGrowthPlants);
        tracked.addAll(mushroomGrowthPlants);
        return tracked;
    }

    private boolean isAgeBasedPlant(Material material) {
        return ageGrowthPlants.contains(material);
    }

    private boolean isSaplingPlant(Material material) {
        return saplingGrowthPlants.contains(material);
    }

    private boolean isMushroomPlant(Material material) {
        return mushroomGrowthPlants.contains(material);
    }

    private boolean isStemFruitGrowth(BlockGrowEvent event) {
        Material grown = event.getNewState().getType();
        return grown == Material.PUMPKIN || grown == Material.MELON;
    }

    private boolean hasLockedAdjacentStem(Block fruitBlock) {
        for (BlockFace face : List.of(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST)) {
            Block adjacent = fruitBlock.getRelative(face);
            Material type = adjacent.getType();
            if ((type == Material.PUMPKIN_STEM || type == Material.ATTACHED_PUMPKIN_STEM
                    || type == Material.MELON_STEM || type == Material.ATTACHED_MELON_STEM)
                    && hasLock(adjacent)) {
                return true;
            }
        }
        return false;
    }

    private boolean isProtectedByLock(Block block) {
        if (hasLock(block)) {
            return true;
        }

        GrowthDirection direction = getGrowthDirection(block.getType());
        return direction != null && hasLockedBlockInColumn(block, direction);
    }

    private Material findLockedStructureSourceNear(Location origin, TreeType species) {
        World world = origin.getWorld();
        if (world == null) {
            return null;
        }

        boolean mangroveGrowth = species == TreeType.MANGROVE || species == TreeType.TALL_MANGROVE;
        int baseX = origin.getBlockX();
        int baseY = origin.getBlockY();
        int baseZ = origin.getBlockZ();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    Block block = world.getBlockAt(baseX + dx, baseY + dy, baseZ + dz);
                    Material type = block.getType();
                    boolean isStructureSource = isSaplingPlant(type)
                            || isMushroomPlant(type)
                            || (mangroveGrowth && type == Material.MANGROVE_PROPAGULE);
                    if (isStructureSource && hasLock(block)) {
                        return block.getType();
                    }
                }
            }
        }
        return null;
    }

    private boolean isTrackedMaterial(Material material) {
        return getGrowthDirection(material) != null
                || isAgeBasedPlant(material)
                || isSaplingPlant(material)
                || isMushroomPlant(material);
    }

    private boolean isRightClickHarvestable(Material material) {
        return material == Material.SWEET_BERRY_BUSH
                || material == Material.CAVE_VINES
                || material == Material.CAVE_VINES_PLANT;
    }

    private boolean isHarvestableCrop(Material material) {
        return material == Material.WHEAT
                || material == Material.CARROTS
                || material == Material.POTATOES
                || material == Material.BEETROOTS
                || material == Material.NETHER_WART
                || material == Material.COCOA
                || material == Material.SWEET_BERRY_BUSH
                || material == Material.TORCHFLOWER_CROP
                || material == Material.PITCHER_CROP;
    }

    private boolean isTrampleSensitiveCrop(Material material) {
        return material == Material.WHEAT
                || material == Material.CARROTS
                || material == Material.POTATOES
                || material == Material.BEETROOTS
                || material == Material.PUMPKIN_STEM
                || material == Material.MELON_STEM
                || material == Material.TORCHFLOWER_CROP
                || material == Material.PITCHER_CROP;
    }

    private boolean isBonemeal(ItemStack itemStack) {
        return itemStack != null && itemStack.getType() == Material.BONE_MEAL;
    }

    private Material parseTypeFilter(String input) {
        if (input.equalsIgnoreCase("all")) {
            return null;
        }
        Material material = Material.matchMaterial(input.toUpperCase(Locale.ROOT));
        if (material == null || !getTrackedMaterials().contains(material)) {
            return null;
        }
        return material;
    }

    private Integer tryParseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private int parseRadius(String[] args, int index, int defaultValue) {
        if (args.length <= index) {
            return defaultValue;
        }
        Integer parsed = tryParseInt(args[index]);
        if (parsed == null) {
            return -1;
        }
        return parsed;
    }

    private String colorize(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    private String formatPlayerMessage(String template, String plantName) {
        return formatPlayerMessage(template, plantName, null, null);
    }

    private String formatPlayerMessage(String template, String plantName, Integer count, String types) {
        String resolved = template
                .replace("%plant%", plantName == null ? "" : plantName)
                .replace("{plant}", plantName == null ? "" : plantName)
                .replace("%count%", count == null ? "0" : String.valueOf(count))
                .replace("{count}", count == null ? "0" : String.valueOf(count))
                .replace("%types%", types == null ? "N/A" : types)
                .replace("{types}", types == null ? "N/A" : types);
        return colorize(resolved);
    }

    private record RemovalSummary(int count, Set<Material> types) {
        private String formattedTypes() {
            if (types.isEmpty()) {
                return "N/A";
            }
            List<String> names = new ArrayList<>();
            for (Material material : types) {
                names.add(formatMaterialName(material));
            }
            Collections.sort(names);
            return String.join(", ", names);
        }
    }

    private String formatPlantName(Material material) {
        return formatMaterialName(material);
    }

    private static String formatMaterialName(Material material) {
        String[] parts = material.name().toLowerCase(Locale.ROOT).split("_");
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            builder.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return builder.toString();
    }

}

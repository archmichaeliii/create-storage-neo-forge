package net.fxnt.fxntstorage.backpack.upgrade;

import net.fxnt.fxntstorage.backpack.BackpackItem;
import net.fxnt.fxntstorage.backpack.main.BackpackContainer;
import net.fxnt.fxntstorage.backpack.main.BackpackMenu;
import net.fxnt.fxntstorage.backpack.main.IBackpackContainer;
import net.fxnt.fxntstorage.backpack.util.BackpackHelper;
import net.fxnt.fxntstorage.config.ConfigManager;
import net.fxnt.fxntstorage.util.Util;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.stats.Stats;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.items.IItemHandlerModifiable;

import java.util.List;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Handles all magnet upgrade logic: periodic item attraction and on-touch item pickup redirection.
 * Designed as a stateless utility with per-player tick tracking.
 */
public final class MagnetUpgradeHandler {

    private static final WeakHashMap<Player, Integer> tickCounters = new WeakHashMap<>();

    /** How often (in ticks) the magnet scans for nearby items. */
    private static final int SCAN_INTERVAL = 30;

    /** Speed at which items are pulled toward the player (blocks/tick). */
    private static final double ATTRACTION_SPEED = 0.5;

    /** Distance at which items are collected instantly instead of attracted. */
    private static final double COLLECT_DISTANCE_SQ = 1.5 * 1.5;

    private MagnetUpgradeHandler() {}

    // ── Periodic scan (called every player tick from EventHandler) ───────────

    /**
     * Called every server-side player tick. Manages its own per-player interval
     * so the caller doesn't need to track ticks.
     */
    public static void tick(Player player, BackpackOnBackUpgradeHandler upgradeHandler) {
        if (player.level().isClientSide) return;
        if (!upgradeHandler.hasUpgrade(Util.MAGNET_UPGRADE)) return;

        int count = tickCounters.getOrDefault(player, 0) + 1;
        if (count < SCAN_INTERVAL) {
            tickCounters.put(player, count);
            return;
        }
        tickCounters.put(player, 0);

        scanAndCollect(player, upgradeHandler);
    }

    /**
     * Scans for nearby item entities and either attracts or collects them.
     */
    private static void scanAndCollect(Player player, BackpackOnBackUpgradeHandler upgradeHandler) {
        int range = ConfigManager.CommonConfig.BACKPACK_MAGNET_RANGE.get();
        AABB boundingBox = new AABB(player.blockPosition()).inflate(range);
        List<ItemEntity> nearbyItems = player.level().getEntitiesOfClass(ItemEntity.class, boundingBox);

        if (nearbyItems.isEmpty()) return;

        ItemStack backpackStack = BackpackHelper.getEquippedBackpackStack(player);
        if (backpackStack.isEmpty()) return;

        IBackpackContainer container = getContainer(player, backpackStack);
        boolean filterMode = isFilterModeEnabled(player);
        boolean changed = false;

        for (ItemEntity itemEntity : nearbyItems) {
            if (!isEligible(itemEntity, player)) continue;

            // When filter mode is on, only attract/collect items that match existing backpack contents
            if (filterMode && !backpackContainsItem(container, itemEntity.getItem())) continue;

            double distSq = itemEntity.distanceToSqr(player);

            if (distSq <= COLLECT_DISTANCE_SQ) {
                // Close enough — collect directly
                changed |= collectItem(player, container, itemEntity);
            } else {
                // Pull item toward player
                attractItem(itemEntity, player);
            }
        }

        if (changed) {
            container.setDataChanged();
        }
    }

    // ── Item pickup interception (called from ItemEntityMixin) ──────────────

    /**
     * Attempts to redirect a touched item entity into the backpack.
     * Returns true if the item was handled (caller should cancel vanilla pickup).
     */
    public static boolean onItemPickup(Player player, ItemEntity itemEntity, UUID target, int pickupDelay) {
        if (player.level().isClientSide) return false;

        ItemStack backpackStack = BackpackHelper.getEquippedBackpackStack(player);
        if (backpackStack.isEmpty()) return false;

        BackpackOnBackUpgradeHandler upgradeHandler = new BackpackOnBackUpgradeHandler(player);
        if (!upgradeHandler.hasUpgrade(Util.ITEMPICKUP_UPGRADE) && !upgradeHandler.hasUpgrade(Util.MAGNET_UPGRADE)) {
            return false;
        }

        if (pickupDelay != 0) return false;
        if (target != null && !target.equals(player.getUUID())) return false;

        ItemStack stack = itemEntity.getItem();
        int originalCount = stack.getCount();

        IBackpackContainer container = getContainer(player, backpackStack);

        // When filter mode is on, only redirect to backpack if the item matches existing contents
        if (isFilterModeEnabled(player) && !backpackContainsItem(container, stack)) {
            return false; // Let vanilla pickup handle it → goes to player inventory
        }

        boolean inserted = insertIntoBackpack(container, itemEntity);

        if (inserted) {
            player.take(itemEntity, originalCount);
            if (stack.isEmpty()) {
                itemEntity.discard();
                stack.setCount(originalCount); // restore for stat tracking
            }
            player.awardStat(Stats.ITEM_PICKED_UP.get(stack.getItem()), originalCount);
            player.onItemPickup(itemEntity);
            container.setDataChanged();
            return true;
        }
        return false;
    }

    // ── Filtering ───────────────────────────────────────────────────────────

    /**
     * Checks whether the magnet filter mode is enabled for this player.
     * When enabled, the magnet only picks up items matching existing backpack contents.
     */
    private static boolean isFilterModeEnabled(Player player) {
        CompoundTag settings = player.getPersistentData().getCompound(ConfigManager.FXNTSTORAGE_SETTINGS_TAG);
        return settings.contains("MagnetFilterToBackpackContents") && settings.getBoolean("MagnetFilterToBackpackContents");
    }

    /**
     * Checks whether the backpack already contains an item of the same type.
     */
    private static boolean backpackContainsItem(IBackpackContainer container, ItemStack itemStack) {
        IItemHandlerModifiable itemHandler = container.getItemHandler();
        Item targetItem = itemStack.getItem();
        for (int i = Util.ITEM_SLOT_START_RANGE; i < Util.ITEM_SLOT_END_RANGE; i++) {
            ItemStack slotStack = itemHandler.getStackInSlot(i);
            if (!slotStack.isEmpty() && slotStack.getItem() == targetItem) {
                return true;
            }
        }
        return false;
    }

    /**
     * Determines whether an item entity is eligible for magnet collection.
     */
    private static boolean isEligible(ItemEntity itemEntity, Player player) {
        if (itemEntity.isRemoved()) return false;
        if (itemEntity.getItem().isEmpty()) return false;

        // Never pick up backpacks
        if (itemEntity.getItem().getItem() instanceof BackpackItem) return false;

        // Respect pickup delay for items not targeted at this player
        if (itemEntity.hasPickUpDelay()) return false;

        // Skip items being processed by Create mod fans
        if (isCreateFanProcessing(itemEntity, player)) return false;

        return true;
    }

    /**
     * Checks if this item entity is currently being processed by a Create mod fan contraption.
     */
    private static boolean isCreateFanProcessing(ItemEntity itemEntity, Player player) {
        CompoundTag settings = player.getPersistentData().getCompound(ConfigManager.FXNTSTORAGE_SETTINGS_TAG);
        if (!settings.contains("IgnoreFanProcessing") || !settings.getBoolean("IgnoreFanProcessing")) {
            return false;
        }

        CompoundTag nbt = itemEntity.getPersistentData();
        if (!nbt.contains("CreateData")) return false;

        CompoundTag createData = nbt.getCompound("CreateData");
        if (!createData.contains("Processing")) return false;

        CompoundTag processing = createData.getCompound("Processing");
        return processing.contains("Time") && processing.getInt("Time") > 0;
    }

    // ── Collection ──────────────────────────────────────────────────────────

    /**
     * Pulls an item entity toward the player using velocity.
     */
    private static void attractItem(ItemEntity itemEntity, Player player) {
        Vec3 playerPos = player.position().add(0, 0.5, 0);
        Vec3 itemPos = itemEntity.position();
        Vec3 direction = playerPos.subtract(itemPos).normalize();

        itemEntity.setDeltaMovement(direction.scale(ATTRACTION_SPEED));
        itemEntity.setNoGravity(true);
        itemEntity.hasImpulse = true;
    }

    /**
     * Collects an item entity directly into the backpack container.
     * Returns true if any items were moved.
     */
    private static boolean collectItem(Player player, IBackpackContainer container, ItemEntity itemEntity) {
        int countBefore = itemEntity.getItem().getCount();
        boolean inserted = insertIntoBackpack(container, itemEntity);

        if (!inserted) return false;

        ItemStack remaining = itemEntity.getItem();
        int collected = countBefore - remaining.getCount();

        if (remaining.isEmpty()) {
            player.take(itemEntity, countBefore);
            itemEntity.discard();
        } else if (collected > 0) {
            player.take(itemEntity, collected);
        }

        return collected > 0;
    }

    /**
     * Inserts the item entity's stack into the backpack. Mutates the entity's ItemStack in place
     * (shrinking it as items are moved). Returns true if any items were transferred.
     */
    private static boolean insertIntoBackpack(IBackpackContainer container, ItemEntity itemEntity) {
        ItemStack newStack = itemEntity.getItem();
        IItemHandlerModifiable itemHandler = container.getItemHandler();
        int startIndex = Util.ITEM_SLOT_START_RANGE;
        int endIndex = Util.ITEM_SLOT_END_RANGE;
        int stackMultiplier = container.getStackMultiplier();
        int initialCount = newStack.getCount();

        // First pass: merge into existing matching stacks
        if (!newStack.isDamageableItem() && newStack.getComponentsPatch().isEmpty() && !newStack.isBarVisible()) {
            for (int i = startIndex; i < endIndex && !newStack.isEmpty(); i++) {
                ItemStack slotStack = itemHandler.getStackInSlot(i);
                if (slotStack.isEmpty() || !ItemStack.isSameItemSameComponents(newStack, slotStack)) continue;

                int maxSize = Math.max(newStack.getMaxStackSize(), stackMultiplier * newStack.getMaxStackSize());
                int space = maxSize - slotStack.getCount();
                if (space <= 0) continue;

                int transfer = Math.min(space, newStack.getCount());
                slotStack.grow(transfer);
                newStack.shrink(transfer);
            }
        }

        // Second pass: place into first empty slot
        if (!newStack.isEmpty()) {
            for (int i = startIndex; i < endIndex; i++) {
                if (!itemHandler.getStackInSlot(i).isEmpty()) continue;

                int maxSize = Math.max(newStack.getMaxStackSize(), stackMultiplier * newStack.getMaxStackSize());
                int transfer = Math.min(maxSize, newStack.getCount());
                ItemStack toInsert = newStack.split(transfer);
                itemHandler.setStackInSlot(i, toInsert);
                break;
            }
        }

        return newStack.getCount() < initialCount;
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private static IBackpackContainer getContainer(Player player, ItemStack backpackStack) {
        if (player.containerMenu instanceof BackpackMenu backpackMenu
                && backpackMenu.backpackType == Util.BACKPACK_ON_BACK) {
            return backpackMenu.container;
        }
        return new BackpackContainer(backpackStack, player);
    }

    /**
     * Removes tracking data for a player (call on logout).
     */
    public static void removePlayer(Player player) {
        tickCounters.remove(player);
    }
}

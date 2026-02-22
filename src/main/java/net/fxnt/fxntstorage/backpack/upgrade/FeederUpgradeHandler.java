package net.fxnt.fxntstorage.backpack.upgrade;

import net.fxnt.fxntstorage.backpack.main.BackpackContainer;
import net.fxnt.fxntstorage.backpack.main.BackpackMenu;
import net.fxnt.fxntstorage.backpack.main.IBackpackContainer;
import net.fxnt.fxntstorage.backpack.util.BackpackHelper;
import net.fxnt.fxntstorage.config.ConfigManager;
import net.fxnt.fxntstorage.util.Util;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.SuspiciousStewEffects;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.items.IItemHandlerModifiable;
import org.jetbrains.annotations.NotNull;

import java.util.WeakHashMap;

/**
 * Handles all feeder upgrade logic: periodic auto-feeding from the backpack.
 * Designed as a stateless utility with per-player tick tracking.
 */
public final class FeederUpgradeHandler {

    private static final WeakHashMap<Player, Integer> tickCounters = new WeakHashMap<>();

    /** How often (in ticks) the feeder checks for feeding. */
    private static final int SCAN_INTERVAL = 15;

    private FeederUpgradeHandler() {}

    // ── Periodic scan (called every player tick from EventHandler) ───────────

    /**
     * Called every server-side player tick. Manages its own per-player interval
     * so the caller doesn't need to track ticks.
     */
    public static void tick(Player player, BackpackOnBackUpgradeHandler upgradeHandler) {
        if (player.level().isClientSide) return;
        if (!upgradeHandler.hasUpgrade(Util.FEEDER_UPGRADE)) return;

        int count = tickCounters.getOrDefault(player, 0) + 1;
        if (count < SCAN_INTERVAL) {
            tickCounters.put(player, count);
            return;
        }
        tickCounters.put(player, 0);

        tryFeedPlayer(player);
    }

    // ── Feeding ─────────────────────────────────────────────────────────────

    /**
     * Searches the backpack for valid food and feeds the player if needed.
     */
    private static void tryFeedPlayer(Player player) {
        if (!shouldFeedPlayer(player)) return;

        ItemStack backpackStack = BackpackHelper.getEquippedBackpackStack(player);
        if (backpackStack.isEmpty()) return;

        IBackpackContainer container = getContainer(player, backpackStack);
        IItemHandlerModifiable itemHandler = container.getItemHandler();

        for (int i = Util.ITEM_SLOT_START_RANGE; i < Util.ITEM_SLOT_END_RANGE; i++) {
            ItemStack food = itemHandler.getStackInSlot(i);
            if (!isEdible(food, player) || hasNegativeEffects(food, player)) continue;

            feedPlayer(player, container, itemHandler, i, food);
            return;
        }
    }

    /**
     * Feeds the player directly without hand-swapping.
     * Uses finishUsingItem to properly handle all food types including
     * those with special behaviors (stews returning bowls, honey clearing poison, etc.).
     */
    private static void feedPlayer(Player player, IBackpackContainer container,
                                   IItemHandlerModifiable itemHandler, int slot, ItemStack food) {
        String foodName = food.getItem().getName(food).getString();

        // Create a single-count copy to consume
        ItemStack singleFood = food.copyWithCount(1);

        // Apply the food directly via finishUsingItem — no hand-swapping needed.
        // finishUsingItem handles nutrition, saturation, effects, sounds, and stats.
        // It also returns remainder items (e.g., bowl from stew, bottle from honey).
        ItemStack remainder = EventHooks.onItemUseFinish(
                player, singleFood.copy(), 0,
                singleFood.getItem().finishUsingItem(singleFood, player.level(), player)
        );

        // Shrink the original food stack
        food.shrink(1);
        itemHandler.setStackInSlot(slot, food);

        // Insert remainder (e.g., bowls, bottles) into backpack
        if (!remainder.isEmpty()) {
            insertRemainder(player, container, itemHandler, remainder);
        }

        container.setDataChanged();

        // Display message if enabled
        if (player.getPersistentData().getCompound(ConfigManager.FXNTSTORAGE_SETTINGS_TAG).getBoolean("DisplayFeederMessage")) {
            String foodNameFormatted = (Util.isVowel(foodName.charAt(0)) ? "an" : "a") + " §a" + foodName + "§r";
            player.displayClientMessage(Component.translatable("item.fxntstorage.backpack_feeder_upgrade.message", foodNameFormatted), true);
        }
    }

    // ── Filtering ───────────────────────────────────────────────────────────

    /**
     * Determines whether the player should be fed based on hunger and health thresholds.
     */
    private static boolean shouldFeedPlayer(Player player) {
        if (player.isCreative() || player.isSpectator()) return false;

        FoodData foodData = player.getFoodData();
        int hunger = foodData.getFoodLevel();
        float health = player.getHealth();
        float maxHealth = player.getMaxHealth();

        CompoundTag fxntSettings = player.getPersistentData().getCompound(ConfigManager.FXNTSTORAGE_SETTINGS_TAG);

        // Feed immediately if health is low
        double healthThreshold = (double) fxntSettings.getInt("FeederHealthThreshold") / 100;
        if (health < maxHealth * healthThreshold && hunger < 20)
            return true;

        return hunger < fxntSettings.getDouble("FeederHungerLevel");
    }

    /**
     * Checks whether an item stack is valid edible food with nutrition.
     */
    private static boolean isEdible(@NotNull ItemStack stack, LivingEntity player) {
        if (!stack.has(DataComponents.FOOD)) return false;

        FoodProperties foodProperties = stack.getItem().getFoodProperties(stack, player);
        return foodProperties != null && foodProperties.nutrition() > 0;
    }

    /**
     * Checks whether a food item has negative effects that should prevent auto-feeding.
     */
    private static boolean hasNegativeEffects(@NotNull ItemStack food, Player player) {
        FoodProperties foodProperties = food.getFoodProperties(player);
        if (foodProperties == null) return false;

        // Chorus Fruit teleports — only allow if config permits
        if (food.is(Items.CHORUS_FRUIT) && !player.getPersistentData().getCompound(ConfigManager.FXNTSTORAGE_SETTINGS_TAG).getBoolean("AllowChorusFruit"))
            return true;

        // Ominous Bottles always blocked
        if (food.is(Items.OMINOUS_BOTTLE)) return true;

        // Suspicious stew with harmful effects
        SuspiciousStewEffects stewEffects = food.get(DataComponents.SUSPICIOUS_STEW_EFFECTS);
        if (stewEffects != null) {
            for (SuspiciousStewEffects.Entry entry : stewEffects.effects()) {
                if (entry.effect().value().getCategory().equals(MobEffectCategory.HARMFUL))
                    return true;
            }
        }

        // Any food with harmful mob effects
        for (FoodProperties.PossibleEffect effect : foodProperties.effects()) {
            MobEffectInstance instance = effect.effectSupplier().get();
            if (instance.getEffect().value().getCategory().equals(MobEffectCategory.HARMFUL))
                return true;
        }
        return false;
    }

    // ── Remainder handling ──────────────────────────────────────────────────

    /**
     * Inserts a remainder item (e.g., bowl, bottle) into the backpack.
     * Falls back to dropping the item if the backpack is full.
     */
    private static void insertRemainder(Player player, IBackpackContainer container,
                                        IItemHandlerModifiable itemHandler, ItemStack remainder) {
        int firstEmptySlot = -1;

        // First pass: try to merge with existing matching stacks
        for (int j = Util.ITEM_SLOT_START_RANGE; j < Util.ITEM_SLOT_END_RANGE; j++) {
            ItemStack stack = itemHandler.getStackInSlot(j);

            if (stack.isEmpty() && firstEmptySlot < 0) {
                firstEmptySlot = j;
                continue;
            }

            if (ItemStack.isSameItemSameComponents(stack, remainder)
                    && stack.getCount() < container.getStackMultiplier() * remainder.getMaxStackSize()) {
                ItemStack insertResult = itemHandler.insertItem(j, remainder, false);
                if (insertResult.isEmpty()) return;
                remainder = insertResult;
            }
        }

        // Second pass: place in first empty slot
        if (!remainder.isEmpty() && firstEmptySlot >= 0) {
            itemHandler.insertItem(firstEmptySlot, remainder, false);
            return;
        }

        // Backpack full — drop the remainder
        if (!remainder.isEmpty()) {
            player.drop(remainder, true);
        }
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

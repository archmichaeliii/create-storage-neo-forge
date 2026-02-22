package net.fxnt.fxntstorage.backpack.upgrade;

import com.simibubi.create.AllItems;
import net.fxnt.fxntstorage.backpack.BackpackBlock;
import net.fxnt.fxntstorage.backpack.main.BackpackContainer;
import net.fxnt.fxntstorage.backpack.main.BackpackMenu;
import net.fxnt.fxntstorage.backpack.main.IBackpackContainer;
import net.fxnt.fxntstorage.backpack.util.BackpackHelper;
import net.fxnt.fxntstorage.config.ConfigManager;
import net.fxnt.fxntstorage.init.ModDataComponents;
import net.fxnt.fxntstorage.init.ModTags;
import net.fxnt.fxntstorage.item.upgrades.UpgradeItem;
import net.fxnt.fxntstorage.util.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.items.IItemHandlerModifiable;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;

public class BackpackOnBackUpgradeHandler {

    private final Player player;
    private final ItemStack itemStack;
    private final Set<String> cachedUpgrades;
    private IBackpackContainer cachedContainer;

    public BackpackOnBackUpgradeHandler(Player player) {
        this.player = player;
        this.itemStack = BackpackHelper.getEquippedBackpackStack(player);
        this.cachedUpgrades = loadUpgrades();
    }

    /**
     * Loads all upgrade names once during construction. If the BACKPACK_UPGRADES
     * component is corrupt/empty, falls back to scanning the container items
     * (expensive) but only does so once per handler instance instead of per-call.
     */
    private Set<String> loadUpgrades() {
        if (this.itemStack.isEmpty()) return Set.of();

        List<String> upgrades = this.itemStack.getComponents().get(ModDataComponents.BACKPACK_UPGRADES);
        if (upgrades != null && !upgrades.isEmpty()) {
            return new HashSet<>(upgrades);
        }

        // Fallback: check actual upgrade items in CONTAINER component.
        // The BACKPACK_UPGRADES string list can become empty/corrupt under
        // certain sync conditions. The actual items are the source of truth.
        ItemContainerContents contents = this.itemStack.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY);
        List<ItemStack> items = contents.stream().toList();
        int upgradeStart = BackpackBlock.ITEM_SLOT_COUNT + BackpackBlock.TOOL_SLOT_COUNT;
        int upgradeEnd = Math.min(upgradeStart + BackpackBlock.UPGRADE_SLOT_COUNT, items.size());

        Set<String> result = new HashSet<>();
        for (int i = upgradeStart; i < upgradeEnd; i++) {
            if (items.get(i).getItem() instanceof UpgradeItem upgradeItem) {
                result.add(upgradeItem.getUpgradeName());
            }
        }

        // Repair the corrupt BACKPACK_UPGRADES component so future ticks skip the fallback
        if (!result.isEmpty()) {
            this.itemStack.set(ModDataComponents.BACKPACK_UPGRADES, new ArrayList<>(result));
        }

        return result;
    }

    public boolean hasUpgrade(String upgradeName) {
        return cachedUpgrades.contains(upgradeName);
    }

    private IBackpackContainer getContainer() {
        if (cachedContainer != null) return cachedContainer;
        if (player.containerMenu instanceof BackpackMenu backPackMenu && backPackMenu.backpackType == Util.BACKPACK_ON_BACK) {
            cachedContainer = backPackMenu.container;
        } else {
            cachedContainer = new BackpackContainer(this.itemStack, this.player);
        }
        return cachedContainer;
    }

    // Magnet and item pickup logic delegated to MagnetUpgradeHandler
    // Feeder logic delegated to FeederUpgradeHandler

    // SERVER SIDE
    public void applyPickBlockUpgrade(ItemStack pickedStack) {
        if (this.itemStack.isEmpty() || this.player.level().isClientSide || !hasUpgrade(Util.PICKBLOCK_UPGRADE)) return;
        PickBlockHandler.pickBlockHandler(player, getContainer(), pickedStack);
    }

    // SERVER SIDE
    public void applyRefillUpgrade() {
        if (itemStack.isEmpty() || player.level().isClientSide || !hasUpgrade(Util.REFILL_UPGRADE)) return;

        // Check for matching items in player inventory and backpack and fill hand stack
        refillHand(player.getMainHandItem(), false);
        refillHand(player.getOffhandItem(), true);
    }

    private void refillHand(@NotNull ItemStack handItem, boolean isOffHand) {
        if (handItem.isEmpty() || handItem.getCount() >= handItem.getMaxStackSize()) return;

        if (!(handItem.getItem() instanceof BlockItem)) return; // Continue only if a placeable block
        if (isBlacklisted(handItem)) return; // Check if item is blacklisted

        int requiredItems = handItem.getMaxStackSize() - handItem.getCount();
        int ignorePlayerSlot = isOffHand ? 40 : player.getInventory().selected;

        // Check Player inventory first
        int remaining = refillFromPlayerInventory(handItem, requiredItems, ignorePlayerSlot);
        if (remaining > 0) {
            refillFromBackpack(handItem, remaining);
        }
    }

    private boolean isBlacklisted(ItemStack stack) {
        // Check tags
        if (stack.is(ModTags.Items.REFILL_BLACKLIST)) return true;

        // Check config list
        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
        String fullId = itemId.toString();

        for (String pattern : ConfigManager.CommonConfig.REFILL_BLACKLIST.get()) {
            if (pattern.endsWith(":*")) {
                // Wildcard match, blacklist namespace
                String namespace = pattern.substring(0, pattern.length() - 2);
                if (itemId.getNamespace().equals(namespace)) return true;
            } else {
                // Exact match
                if (fullId.equals(pattern)) return true;
            }
        }
        return false;
    }

    private int refillFromPlayerInventory(ItemStack handItem, int requiredItems, int ignoreSlot) {
        Container inventory = player.getInventory();

        int remaining = requiredItems;

        for (int i = 0; i < inventory.getContainerSize(); i++) {
            if (i == ignoreSlot) continue;

            ItemStack inventoryItem = inventory.getItem(i);
            if (inventoryItem.isEmpty()) continue;
            if (!ItemStack.isSameItemSameComponents(handItem, inventoryItem)) continue;

            int amountToTransfer = Math.min(remaining, inventoryItem.getCount());
            inventoryItem.shrink(amountToTransfer);
            handItem.grow(amountToTransfer);

            remaining -= amountToTransfer;
        }

        if (requiredItems != remaining) {
            if (player.containerMenu instanceof BackpackMenu menu) {
                menu.container.setDataChanged();
            } else {
                player.containerMenu.slotsChanged(inventory);
            }
        }

        return remaining;
    }

    private void refillFromBackpack(ItemStack handItem, int requiredItems) {
        IBackpackContainer container = getContainer();
        IItemHandlerModifiable itemHandler = container.getItemHandler();
        if (itemHandler == null) return;

        int remaining = requiredItems;

        for (int i = Util.ITEM_SLOT_START_RANGE; i < Util.ITEM_SLOT_END_RANGE; i++) {
            ItemStack containerItem = itemHandler.getStackInSlot(i);
            if (containerItem.isEmpty()) continue;
            if (!ItemStack.isSameItemSameComponents(handItem, containerItem)) continue;

            int amountToTransfer = Math.min(remaining, containerItem.getCount());
            ItemStack extracted = itemHandler.extractItem(i, amountToTransfer, false);
            if (extracted.isEmpty()) continue;

            handItem.grow(extracted.getCount());
            remaining -= extracted.getCount();
        }

        if (requiredItems != remaining) {
            container.setDataChanged();
        }
    }

    public boolean applyFallDamageUpgrade() {
        return !this.itemStack.isEmpty() && !this.player.level().isClientSide && hasUpgrade(Util.FALLDAMAGE_UPGRADE);
    }

    // SERVER SIDE
    public void fromAttackBlockEvent(Player player, Level level, InteractionHand hand, BlockPos pos) {
        if (this.itemStack.isEmpty() || hand != InteractionHand.OFF_HAND && player.isSpectator() || level.isClientSide || !player.isAlive()
                || player.isSleeping() || player.isDeadOrDying() || !hasUpgrade(Util.TOOLSWAP_UPGRADE)
                || player.getMainHandItem().is(AllItems.WRENCH)) return;

        ToolSwapHandler toolSwapHandler = new ToolSwapHandler(player, getContainer(), Util.TOOL_SLOT_START_RANGE, Util.TOOL_SLOT_END_RANGE);
        toolSwapHandler.doToolSwap(pos, null);
    }

    public void fromAttackEntityEvent(Player player, Level level, InteractionHand hand, LivingEntity entity) {
        if (this.itemStack.isEmpty() || hand != InteractionHand.OFF_HAND && player.isSpectator() || level.isClientSide || !player.isAlive()
                || player.isSleeping() || player.isDeadOrDying() || !hasUpgrade(Util.TOOLSWAP_UPGRADE)
                || player.getMainHandItem().is(AllItems.WRENCH)) return;

        ToolSwapHandler toolSwapHandler = new ToolSwapHandler(player, getContainer(), Util.TOOL_SLOT_START_RANGE, Util.TOOL_SLOT_END_RANGE);
        toolSwapHandler.doToolSwap(null, entity);
    }

    public void applyOreMiningUpgrade(Level level, BlockPos origin, Player player, boolean mineAllBlocks, int maxBlocks) {
        BlockState targetState = level.getBlockState(origin);
        List<BlockPos> vein = findVein(level, origin, targetState, mineAllBlocks, maxBlocks);

        List<ItemStack> drops = new ArrayList<>();
        ItemStack tool = player.getMainHandItem();

        int blocksMined = 0;

        for (BlockPos pos : vein) {
            if (tool.isEmpty()) break;

            BlockState state = level.getBlockState(pos);
            if (level.isEmptyBlock(pos)) continue;

            BlockEntity blockEntity = level.getBlockEntity(pos);

            LootParams.Builder lootParams = new LootParams.Builder((ServerLevel) level)
                    .withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(pos))
                    .withParameter(LootContextParams.TOOL, tool)
                    .withParameter(LootContextParams.BLOCK_STATE, state)
                    .withOptionalParameter(LootContextParams.THIS_ENTITY, player)
                    .withOptionalParameter(LootContextParams.BLOCK_ENTITY, blockEntity);

            List<ItemStack> blockDrops = state.getDrops(lootParams);
            drops.addAll(blockDrops);

            level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            state.onDestroyedByPlayer(level, pos, player, true, level.getFluidState(pos));

            if (state.getDestroySpeed(level, pos) >= 0.0F) {
                tool.hurtAndBreak(1, player, EquipmentSlot.MAINHAND);
                if (!player.getAbilities().instabuild) {
                    player.causeFoodExhaustion(0.2F);
                }
            }

            blocksMined++;
        }

        List<ItemStack> condensed = new ArrayList<>();

        for (ItemStack stack : drops) {
            if (!stack.isEmpty()) {
                boolean merged = false;

                for (ItemStack existing : condensed) {
                    if (ItemStack.isSameItemSameComponents(existing, stack) && existing.isStackable()) {
                        int transferable = Math.min(stack.getCount(), existing.getMaxStackSize() - existing.getCount());
                        if (transferable > 0) {
                            existing.grow(transferable);
                            stack.shrink(transferable);
                            if (stack.isEmpty()) {
                                merged = true;
                                break;
                            }
                        }
                    }
                }

                if (!merged && !stack.isEmpty()) {
                    condensed.add(stack.copy());
                }
            }
        }

        for (ItemStack drop : condensed) {
            Block.popResource(level, origin, drop);
        }

        if (!FMLEnvironment.production) {
            Component msg = Component.literal("Successfully mined §a" + blocksMined + "§r out of §a" + vein.size() + "§r");
            player.displayClientMessage(msg, false);
        }
    }

    public List<BlockPos> findVein(Level level, BlockPos start, BlockState targetState, boolean mineAllBlocks, int maxBlocks) {
        Set<BlockPos> visited = new HashSet<>();
        Queue<BlockPos> toVisit = new ArrayDeque<>();
        toVisit.add(start);

        while (!toVisit.isEmpty() && visited.size() < maxBlocks) {
            BlockPos current = toVisit.poll();
            if (!visited.add(current)) continue; // skip if already visited

            // Check all 26 surrounding positions in a 3x3x3 cube
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue; // Skip the center block

                        BlockPos neighbor = current.offset(dx, dy, dz);
                        if (visited.contains(neighbor)) continue;

                        BlockState neighborState = level.getBlockState(neighbor);

                        if (!neighborState.getBlock().equals(targetState.getBlock())) continue;
                        if (!mineAllBlocks && !neighborState.is(ModTags.Blocks.ORE_MINING_BLOCK)) continue;

                        toVisit.add(neighbor);
                    }
                }
            }
        }

        // Sort positions by distance from player
        return visited.stream()
                .sorted(Comparator.comparingDouble(blockPos -> blockPos.distSqr(player.blockPosition())))
                .toList();
    }

    @SuppressWarnings("deprecation")
    public void applyTorchDeployerUpgrade(Player player) {
        BlockPos playerPos = player.blockPosition();
        BlockPos belowPos = playerPos.below();
        Level level = player.level();

        CompoundTag settings = player.getPersistentData().getCompound(ConfigManager.FXNTSTORAGE_SETTINGS_TAG);
        int lightLevel = settings.getInt("TorchDeployerLightLevel");
        int cooldown = settings.getInt("TorchDeployerCooldown");
        String sourceValue = settings.getString("TorchDeployerLightSource");

        ConfigManager.ClientConfig.TorchDeployerLightSource lightSource;
        try {
            lightSource = ConfigManager.ClientConfig.TorchDeployerLightSource.valueOf(
                    sourceValue.isEmpty() ? "BLOCK_LIGHT" : sourceValue
            );
        } catch (IllegalArgumentException e) {
            lightSource = ConfigManager.ClientConfig.TorchDeployerLightSource.BLOCK_LIGHT;
        }

        int blockLightLevel = (lightSource == ConfigManager.ClientConfig.TorchDeployerLightSource.SKY_LIGHT)
                ? level.getBrightness(LightLayer.BLOCK, playerPos)
                : level.getMaxLocalRawBrightness(playerPos);

        if (blockLightLevel <= lightLevel &&
                level.getBlockState(belowPos).isSolid() &&
                level.getBlockState(playerPos).isAir()) {

            if (!TorchDeployerManager.canPlaceTorch(player, cooldown)) return;

            // Place torch
            IBackpackContainer container = getContainer();
            IItemHandlerModifiable itemHandler = container.getItemHandler();

            for (int slot = Util.ITEM_SLOT_START_RANGE; slot < Util.ITEM_SLOT_END_RANGE; slot++) {
                ItemStack stack = itemHandler.getStackInSlot(slot);
                if (stack.is(Items.TORCH)) {
                    stack.shrink(1);
                    itemHandler.setStackInSlot(slot, stack);

                    level.setBlock(playerPos, Blocks.TORCH.defaultBlockState(), Block.UPDATE_ALL);
                    level.playSound(null, playerPos, SoundEvents.WOOD_PLACE, SoundSource.BLOCKS);

                    container.setDataChanged();
                    if (!FMLEnvironment.production) {
                        player.displayClientMessage(Component.literal("Placed §a1§r torch with §a" + stack.getCount() + "§r left in the stack"), false);
                    }

                    break;
                }
            }
        }
    }

}

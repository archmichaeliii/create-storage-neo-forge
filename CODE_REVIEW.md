# Code Review — Create: Storage (`fxntstorage`)

NeoForge 1.21.1 / Create 6.0.8 / Java 21. Review covered all ~180 Java source
files (~20.6k LOC) across the backpack, storage-box, controller/network,
passer, simple-storage, networking, mixin, and compat subsystems.

Focus was on **real bugs** — item duplication / loss, crashes, client/server
desync, and persistence errors — not style. Every Critical/High finding was
traced through the source to confirm it is real and reachable.

> **Build note:** changes were verified by inspection. A full Gradle build was
> not run in the review environment (no dependency cache; the NeoForge moddev
> setup pulls hundreds of MB and decompiles MC). Run `./gradlew build` locally
> before release.

---

## Fixed

### Batch 1 — duplication / loss / crashes in common paths

| Sev | Area | File | Summary |
|-----|------|------|---------|
| 🔴 Critical | Network insert dupe | `storage_network/StorageNetwork.java` (`insertItems`) | Looped over boxes with the original full stack instead of `remaining`, so a stack spanning multiple boxes was duplicated (double-click controller / package unpacking). Now uses `remaining`. |
| 🔴 Critical | Pick-block item loss | `backpack/upgrade/PickBlockHandler.java` | Top-up computed `amountToMove - hotbarStackSize` (could go negative → empty stack), destroying the held stack. Now `amountToMove + hotbarStackSize`. |
| 🟠 High | Passer item loss | `passer/PasserEntity.java` | `EXACTLY` mode never re-clamped the count, extracted the full amount then dropped it when the destination couldn't take it all. Now skips the move unless the destination accepts the full exact amount, and returns any leftover to the source. |
| 🟠 High | Void destroys items | `simple_storage/SimpleStorageBoxEntity.java` (`insertItem`) | Void upgrade returned `EMPTY` ("accepted") even for filter-rejected items → silent deletion. Now only voids items the box actually accepts (`isItemValid`), scoped to the storage slot. |
| 🟠 High | Container contract | `container/StorageBoxEntity.java` (`removeItem`/`removeItemNoUpdate`) | Returned the *remaining* slot stack (and `removeItemNoUpdate` never cleared the slot) — a dupe/stuck-item vector. Now returns the extracted items / clears the slot. |
| 🟠 High | Crash on equip | `backpack/BackpackItem.java` (`canEquip`) | `(Player) entity` cast crashed for non-player `LivingEntity` (mob equipping a dropped backpack with Curios). Now `instanceof`-guarded. |
| 🟠 High | Crash on reopen | `backpack/BackpackEntity.java` (`getStacks`/`readInventory`) + `backpack/main/BackpackContainer.java` | The internal ghost buffer slot was serialized into the item `CONTAINER` component; reopening the 138-slot item container with a 139-entry payload threw `IndexOutOfBounds`. Ghost slot now excluded; reads bounded. |

### Batch 2 — packet validation, off-by-ones, persistence, threading

| Sev | Area | File | Summary |
|-----|------|------|---------|
| 🟠 High | Unvalidated packet | `network/handler/ServerPayloadHandler.java` (`handleSortInventoryPacket`) + sort methods | Client-supplied `slotStart/slotEnd` flowed unchecked into `getSlot(i)` (server crash / stack manipulation via the stackMultiplier branch). Sort ranges now clamped to the container's own slots in `BackpackMenu`, `StorageBoxMenu`, `StorageBoxMountedMenu`. |
| 🟠 High | Backpack dupe | `network/handler/ServerPayloadHandler.java` (`handleTransferRecipePacket`) | On a partial transfer the handler `return`ed without committing the backpack's in-memory removals (`setDataChanged` skipped) → items left both in the grid and the backpack. Now returns partially-collected items to the player and `break`s into the persistence/sync path. |
| 🟠 High | Null player | `compat/emi/EMICraftingRecipeHandler.java`, `EMIStonecuttingRecipeHandler.java` | `Minecraft.player` cached at plugin-load (null) and never refreshed → backpack never offered as an ingredient source / NPE. Now fetched per call with a null guard (matches the inventory handler). |
| 🟡 Medium | Off-by-one bounds | `storage_network/StorageNetwork.java` | `getStackInSlot`/`extractItem`/`canPlaceItem` used `slot > size`, `getSlotLimit`/`canTakeItem` used `slot <= size` → `IndexOutOfBounds` at `slot == size`. All now `0 <= slot < size`. |
| 🟡 Medium | Stale void timing | `container/StorageBoxEntity.java` (`insertItem`) | Void used the throttled `percentageUsed` field (lags ~1s) → could void into free space. Now computes fullness live and won't void filter-rejected items. |
| 🟡 Medium | Extract lock | `container/StorageBoxEntity.java` (`extractItem`) | Extraction was gated by the insertion filter, stranding stored items if the filter changed. Filter gate removed from extraction. |
| 🟡 Medium | Crash on bad NBT | `util/SortOrder.java` + 7 call sites | `SortOrder.valueOf("")` threw on contraption/block NBT without a `SortOrder` key. Added `SortOrder.byName()` (defaults to `COUNT`); all `valueOf` sites routed through it. |
| 🟡 Medium | Simulate side effects | `simple_storage/mounted/SimpleStorageBoxMountedStorage.java` (`insertItem`) | A *simulated* insert set the filter and `markDirty()`. Moved both inside the `!simulate` branch. |
| 🟡 Medium | Off-by-one (armor) | `controller/StorageControllerEntity.java` (`transferAllItemsFromPlayer`) | `i <= items.size()` read `getItem(36)` (boots slot), vacuuming worn armor. Now `i < items.size()`. |
| 🟡 Medium | Stale capability | `controller/StorageInterfaceEntity.java` (`forgetController`) | Did not invalidate capabilities when dropping its controller. Now calls `level.invalidateCapabilities`. |
| 🟡 Medium | Thread safety | `cache/PasserShapeCache.java`, `cache/BackpackShapeCache.java` | `clearCache()` (client keybind thread) mutated a shared map read on the server thread → transient `null` shape → NPE. Now builds a new map and swaps a `volatile` reference atomically. |
| 🟡 Medium | Wrong light layer | `backpack/upgrade/BackpackOnBackUpgradeHandler.java` (`applyTorchDeployerUpgrade`) | `SKY_LIGHT` mode measured `LightLayer.BLOCK`. Now reads `LightLayer.SKY`. |
| ⚪ Low | O(n²) BFS | `storage_network/StorageNetwork.java` (`getConnectedComponents`) | `List.contains` visited-check replaced with a `HashSet`. |
| ⚪ Low | Client packet bounds | `network/handler/ClientPayloadHandler.java` | `handleSyncContainerPacket`/`handleSyncSlotCountPacket` indexed by slot count without bounds; `handleSyncMountedStoragePacket` could NPE on a missing block. Bounds/null guards added. |
| ⚪ Low | Latent NPE | `backpack/main/BackpackContainer.java` (`setSortOrder`) | Unguarded `player.level()` (player is nullable for the item capability). Now null-guarded like its siblings. |

---

## Open (documented, not changed)

Left unfixed pending review — either a design decision, or a fix with
regression risk that couldn't be build-tested here.

### Medium

- **`backpack/upgrade/ToolSwapHandler.java:38-41` — `static` last-state fields cause cross-player contamination.**
  `lastBlockState/lastTool/lastEntity/lastWeapon` are `static` but the handler is created per player per attack, so on a server all players share them and the swap dedup misfires (skips/redoes swaps). No item loss/dupe.
  *Fix:* make them per-player (`Map<UUID, …>`, server thread). Store the entity as an **id (int)**, not a `LivingEntity` reference, to avoid pinning entities in a static map.

- **`backpack/main/BackpackMenu.java:321-336` — shift-clicking an upgrade with full upgrade slots leaks it into item storage.**
  When no upgrade slot is free, `quickMoveStack` falls through to the general move and deposits the upgrade into the normal item area.
  *Fix:* after the free-upgrade-slot scan fails for an upgrade item coming from the player inventory, `return ItemStack.EMPTY;` instead of falling through.

- **`backpack/upgrade/MagnetUpgradeHandler.java:223` — attracted items get `setNoGravity(true)` that is never cleared.**
  With a 30-tick scan interval, simply dropping `noGravity` makes items sink between pulls, so a proper fix is stateful.
  *Fix:* keep a per-player `Set<Integer>` of item-entity ids attracted last scan; at the start of each scan, `setNoGravity(false)` on ids not re-attracted this scan (resolve via `level.getEntity(id)`).

- **`network/handler/ServerPayloadHandler.java:91-96` — `PickBlockUpgradePacket` trusts a client-supplied `ItemStack`.**
  Limited to moving the player's own backpack items (no cross-container theft), but it's unvalidated input driving inventory mutation and ignores stack components when matching.
  *Fix:* send only a block position and recompute the picked item server-side, or validate against the player's look target.

- **`network/handler/ServerPayloadHandler.java:157-171` — `SetMountedStorageDirtyPacket` has no ownership/range check.**
  Only forces a re-save (no item movement), but acts on any contraption by id at any distance.
  *Fix:* require the matching mounted menu open / interaction range before honoring it.

### Low

- **`network/packet/SyncDataComponentPacket.java:16-23` — asymmetric codec** (conditional encode, unconditional decode). Currently unused, so dormant; make encode/decode symmetric before sending it.
- **`backpack/upgrade/MagnetUpgradeHandler.java:135-140` — `onItemPickup` awards the pickup stat/animation for the full stack on a partial insert** (cosmetic; no item loss). Use `originalCount - stack.getCount()`.
- **`network/packet/MountedStoragePacket.java` — dead duplicate** sharing `SyncMountedStoragePacket`'s `sync_mounted_storage` id. Unused; delete it (registering it would throw on the duplicate id).
- **`mixin/ItemStackMixin.java:21-35` — globally raises the ItemStack count codec ceiling to ~1,048,576** for all items (by design for oversized storage, but affects every mod). `MAX_COUNT` is also a non-`final` `@Unique` field.
- **`util/EventHandler.java` — refill cadence uses `static` tick counters**, so in multiplayer the interval advances ~N× with N players. Use a per-player counter (the magnet/feeder handlers already do).

---

## Checked and cleared (not bugs)

- `parseCustomNameSafe(...)` in the three block entities is an inherited vanilla
  `BlockEntity` helper (present on the shipped base branch), **not** undefined.
- `BackpackContainer.saveItemsToStack` upgrade-preservation and the
  `upgradesChanged` guard correctly avoid wiping the `BACKPACK_UPGRADES`
  component.
- `ServerPayloadHandler` wraps all logic in `enqueueWork` (main thread) and
  casts to `ServerPlayer` safely.
- Compat entrypoints (Curios/CarryOn/EveryComp/ConstructionStick/JEI/EMI/REI)
  are mod-loaded-gated, so missing optional mods don't `ClassNotFound`.

/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package net.raphimc.viabedrock.experimental.model.container;

import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.minecraft.BlockPosition;
import com.viaversion.viaversion.api.minecraft.item.Item;
import com.viaversion.viaversion.libs.mcstructs.text.TextComponent;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.experimental.ExperimentalPacketFactory;
import net.raphimc.viabedrock.experimental.model.container.player.InventoryContainer;
import net.raphimc.viabedrock.experimental.model.inventory.ItemStackRequestAction;
import net.raphimc.viabedrock.experimental.model.inventory.ItemStackRequestInfo;
import net.raphimc.viabedrock.experimental.model.inventory.ItemStackRequestSlotInfo;
import net.raphimc.viabedrock.experimental.storage.ExperimentalInventoryTracker;
import net.raphimc.viabedrock.experimental.storage.InventoryRequestStorage;
import net.raphimc.viabedrock.experimental.storage.InventoryRequestTracker;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerEnumName;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerType;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.TextProcessingEventOrigin;
import net.raphimc.viabedrock.protocol.data.enums.java.generated.ContainerInput;
import net.raphimc.viabedrock.protocol.model.BedrockItem;
import net.raphimc.viabedrock.protocol.model.FullContainerName;
import net.raphimc.viabedrock.protocol.rewriter.ItemRewriter;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;

public abstract class ExperimentalContainer {

    protected final UserConnection user;
    protected final byte containerId;
    protected final ContainerType type;
    protected final TextComponent title;
    protected final BlockPosition position;
    protected final BedrockItem[] items;
    protected final Set<String> validBlockTags;

    public ExperimentalContainer(final UserConnection user, final byte containerId, final ContainerType type, final TextComponent title, final BlockPosition position, final int size, final String... validBlockTags) {
        this.user = user;
        this.containerId = containerId;
        this.type = type;
        this.title = title;
        this.position = position;
        this.items = BedrockItem.emptyArray(size);
        this.validBlockTags = Set.of(validBlockTags);
    }

    protected ExperimentalContainer(final UserConnection user, final byte containerId, final ContainerType type, final TextComponent title, final BlockPosition position, final BedrockItem[] items, final Set<String> validBlockTags) {
        this.user = user;
        this.containerId = containerId;
        this.type = type;
        this.title = title;
        this.position = position;
        this.items = items;
        this.validBlockTags = validBlockTags;
    }

    public abstract FullContainerName getFullContainerName(int slot);

    public boolean handleClick(final int revision, final short javaSlot, final byte button, final ContainerInput action) {
        if (javaSlot == -1) return false; // TODO: Safeguard

        ExperimentalInventoryTracker inventoryTracker = user.get(ExperimentalInventoryTracker.class);
        InventoryRequestTracker inventoryRequestTracker = user.get(InventoryRequestTracker.class);
        ClickContext clickContext = new ClickContext(this, this.bedrockSlot(javaSlot), inventoryTracker, inventoryRequestTracker);

        /* TODO: Could potentially lead to a race condition if we receive a inventory update before the response for the request,
         *  a better solution would be to store the specific changes made in the request. From my testing this doesnt seem to happen though
         */
        clickContext.prevContainers.add(this.copy()); // Store previous state of the container

        List<ItemStackRequestAction> itemActions = switch (action) {
            case PICKUP -> this.singletonAction(this.handlePickupClick(clickContext, javaSlot, button));
            case SWAP -> this.handleSwapClick(clickContext, javaSlot, button);
            case QUICK_MOVE -> this.handleQuickMoveClick(clickContext, javaSlot);
            case THROW -> this.singletonAction(this.handleThrowClick(clickContext, javaSlot, button));
            default -> List.of();
        };

        if (itemActions.isEmpty()) {
            return false;
        }

        ItemStackRequestInfo request = new ItemStackRequestInfo(
                clickContext.inventoryRequestTracker.nextRequestId(),
                itemActions,
                List.of(),
                TextProcessingEventOrigin.unknown
        );

        clickContext.inventoryRequestTracker.addRequest(new InventoryRequestStorage(request, revision, clickContext.prevCursorContainer, clickContext.prevContainers)); // Store the request to track it later
        ExperimentalPacketFactory.sendBedrockInventoryRequest(user, new ItemStackRequestInfo[] {request});

        return true;
    }

    private ItemStackRequestAction handlePickupClick(final ClickContext clickContext, final short javaSlot, final byte button) {
        ExperimentalContainer container = clickContext.container;
        int bedrockSlot = clickContext.bedrockSlot;
        BedrockItem cursorItem = clickContext.inventoryTracker.getHudContainer().getItem(0);

        if (javaSlot == -999) {
            return this.dropCursorItem(clickContext.inventoryTracker, button);
        }

        if (!(container instanceof InventoryContainer) && (javaSlot < 0 || javaSlot >= container.getItems().length)) {
            ExperimentalContainer inventoryContainer = clickContext.inventoryTracker.getInventoryContainer();
            int invSlot = inventoryContainer.bedrockSlot(javaSlot - container.getItems().length + 9); // Map to inventory slot
            if (invSlot < 0 || invSlot >= inventoryContainer.getItems().length) {
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Tried to handle click for " + container.type() + ", but slot was out of bounds (" + javaSlot + ")");
                return null;
            }

            bedrockSlot = invSlot;
            container = inventoryContainer;
            clickContext.container = container;
            clickContext.bedrockSlot = bedrockSlot;
            clickContext.prevContainers.add(container.copy()); // Store previous state of the inventory container
        }

        if (container instanceof InventoryContainer) {
            if (javaSlot >= 0 && javaSlot < 5) {
                ExperimentalContainer hudContainer = clickContext.inventoryTracker.getHudContainer();
                int hudSlot  = hudContainer.bedrockSlot(javaSlot);

                bedrockSlot = hudSlot;
                container = hudContainer;
                clickContext.container = container;
                clickContext.bedrockSlot = hudSlot;
                clickContext.prevContainers.add(container.copy());

                // TODO: Crafting
            } else if (javaSlot >= 5 && javaSlot < 9) {
                // Armor slots
                ExperimentalContainer armorContainer = clickContext.inventoryTracker.getArmorContainer();
                int armorSlot = armorContainer.bedrockSlot(javaSlot);

                bedrockSlot = armorSlot;
                container = armorContainer;
                clickContext.container = armorContainer;
                clickContext.bedrockSlot = armorSlot;
                clickContext.prevContainers.add(armorContainer.copy());
            } else if (javaSlot == 45) {
                // Offhand
                ExperimentalContainer offhandContainer = clickContext.inventoryTracker.getOffhandContainer();
                int offhandSlot = offhandContainer.bedrockSlot(javaSlot);

                bedrockSlot  = offhandSlot;
                container = offhandContainer;
                clickContext.container = offhandContainer;
                clickContext.bedrockSlot = offhandSlot;
                clickContext.prevContainers.add(offhandContainer.copy());
            }
        }

        BedrockItem item = container.getItem(bedrockSlot);
        if (item.isEmpty() && cursorItem.isEmpty()) {
            return null;
        }

        if (cursorItem.isEmpty()) {
            return this.handlePickupTake(clickContext, container, bedrockSlot, button, item);
        }

        if (item.isEmpty() || (!item.isDifferent(cursorItem) && item.amount() < container.maxStackSizeForSlot(bedrockSlot, cursorItem))) {
            return this.handlePickupPlace(clickContext, container, bedrockSlot, button, cursorItem, item);
        }

        return this.handlePickupSwap(clickContext, container, bedrockSlot, cursorItem, item);
    }

    private ItemStackRequestAction handlePickupTake(final ClickContext clickContext, final ExperimentalContainer container, final int bedrockSlot, final byte button, final BedrockItem item) {
        int amountToTake = button == 0 ? item.amount() : (item.amount() + 1) / 2;

        BedrockItem finalCursorItem = this.copyStackWithAmount(item, amountToTake);
        clickContext.inventoryTracker.getHudContainer().setItem(0, finalCursorItem);

        BedrockItem finalContainerItem = this.itemAfterRemovingAmount(item, amountToTake);
        container.setItem(bedrockSlot, finalContainerItem);

        return new ItemStackRequestAction.TakeAction(
                amountToTake,
                container.stackRequestSlotInfo(bedrockSlot, this.stackNetId(item)),
                new ItemStackRequestSlotInfo(clickContext.inventoryTracker.getHudContainer().getFullContainerName(0), (byte) 0, 0)
        );
    }

    private ItemStackRequestAction handlePickupPlace(final ClickContext clickContext, final ExperimentalContainer container, final int bedrockSlot, final byte button, final BedrockItem cursorItem, final BedrockItem item) {
        if (!container.canPlaceItem(bedrockSlot, cursorItem)) {
            return null;
        }

        int amt = button == 0 ? cursorItem.amount() : 1;
        final int slotMaxStackSize = container.maxStackSizeForSlot(bedrockSlot, cursorItem);
        int amountToPlace = item.isDifferent(cursorItem) ? Math.min(amt, slotMaxStackSize) : Math.min(slotMaxStackSize - item.amount(), cursorItem.amount());
        if (amountToPlace <= 0) {
            return null;
        }

        final int containerNetId = this.stackNetId(item);
        BedrockItem finalContainerItem = item.copy();
        if (item.isDifferent(cursorItem)) {
            finalContainerItem = cursorItem.copy();
            finalContainerItem.setAmount(amountToPlace);
        } else {
            finalContainerItem.setAmount(item.amount() + amountToPlace);
        }
        container.setItem(bedrockSlot, finalContainerItem);

        final int cursorNetId = this.stackNetId(cursorItem);
        BedrockItem finalCursorItem = this.itemAfterRemovingAmount(cursorItem, amountToPlace);
        clickContext.inventoryTracker.getHudContainer().setItem(0, finalCursorItem);

        return new ItemStackRequestAction.PlaceAction(
                amountToPlace,
                new ItemStackRequestSlotInfo(clickContext.inventoryTracker.getHudContainer().getFullContainerName(0), (byte) 0, cursorNetId),
                container.stackRequestSlotInfo(bedrockSlot, containerNetId)
        );
    }

    private ItemStackRequestAction handlePickupSwap(final ClickContext clickContext, final ExperimentalContainer container, final int bedrockSlot, final BedrockItem cursorItem, final BedrockItem item) {
        if (!container.canPlaceItem(bedrockSlot, cursorItem) || cursorItem.amount() > container.maxStackSizeForSlot(bedrockSlot, cursorItem)) {
            return null;
        }

        BedrockItem cursorCopy = cursorItem.copy();
        BedrockItem itemCopy = item.copy();

        container.setItem(bedrockSlot, cursorCopy);
        clickContext.inventoryTracker.getHudContainer().setItem(0, itemCopy);

        return new ItemStackRequestAction.SwapAction(
                new ItemStackRequestSlotInfo(clickContext.inventoryTracker.getHudContainer().getFullContainerName(0), (byte) 0, this.stackNetId(cursorItem)),
                container.stackRequestSlotInfo(bedrockSlot, this.stackNetId(item))
        );
    }

    private List<ItemStackRequestAction> handleSwapClick(final ClickContext clickContext, final short javaSlot, final byte button) {
        final boolean offhandSwap = button == 40;
        if (!offhandSwap && (button < 0 || button > 8)) {
            return List.of();
        }

        final SlotRef source = this.resolveJavaSlot(clickContext, javaSlot);
        if (source == null) {
            ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Tried to handle swap for " + clickContext.container.type() + ", but slot was out of bounds (" + javaSlot + ")");
            return List.of();
        }

        ExperimentalContainer container = source.container();
        clickContext.container = container;
        clickContext.bedrockSlot = source.bedrockSlot();

        ExperimentalContainer swapContainer = offhandSwap ? clickContext.inventoryTracker.getOffhandContainer() : clickContext.inventoryTracker.getInventoryContainer();
        final int swapBedrockSlot = offhandSwap ? 0 : button;
        if (container == swapContainer && clickContext.bedrockSlot == swapBedrockSlot) {
            return List.of();
        }
        this.addPrevContainer(clickContext, container);
        this.addPrevContainer(clickContext, swapContainer);

        BedrockItem item = container.getItem(clickContext.bedrockSlot).copy();
        BedrockItem swapItem = swapContainer.getItem(swapBedrockSlot).copy();

        if (item.isEmpty() && swapItem.isEmpty()) {
            return List.of();
        }

        if (swapItem.isEmpty()) {
            if (!swapContainer.canPlaceItem(swapBedrockSlot, item) || item.amount() > swapContainer.maxStackSizeForSlot(swapBedrockSlot, item)) {
                return List.of();
            }

            container.setItem(clickContext.bedrockSlot, BedrockItem.empty());
            swapContainer.setItem(swapBedrockSlot, item);
            return List.of(new ItemStackRequestAction.PlaceAction(
                    item.amount(),
                    container.stackRequestSlotInfo(clickContext.bedrockSlot, this.stackNetId(item)),
                    swapContainer.stackRequestSlotInfo(swapBedrockSlot, 0)
            ));
        }

        if (!container.canPlaceItem(clickContext.bedrockSlot, swapItem)) {
            return List.of();
        }

        final int targetMaxStackSize = container.maxStackSizeForSlot(clickContext.bedrockSlot, swapItem);
        if (targetMaxStackSize <= 0) {
            return List.of();
        }

        if (item.isEmpty()) {
            final int amountToMove = Math.min(swapItem.amount(), targetMaxStackSize);
            container.setItem(clickContext.bedrockSlot, this.copyStackWithAmount(swapItem, amountToMove));
            swapContainer.setItem(swapBedrockSlot, this.itemAfterRemovingAmount(swapItem, amountToMove));
            return List.of(new ItemStackRequestAction.PlaceAction(
                    amountToMove,
                    swapContainer.stackRequestSlotInfo(swapBedrockSlot, this.stackNetId(swapItem)),
                    container.stackRequestSlotInfo(clickContext.bedrockSlot, 0)
            ));
        }

        if (!swapContainer.canPlaceItem(swapBedrockSlot, item)) {
            return List.of();
        }
        if (swapItem.amount() <= targetMaxStackSize) {
            if (item.amount() > swapContainer.maxStackSizeForSlot(swapBedrockSlot, item)) {
                return List.of();
            }

            container.setItem(clickContext.bedrockSlot, swapItem);
            swapContainer.setItem(swapBedrockSlot, item);
            return List.of(new ItemStackRequestAction.SwapAction(
                    swapContainer.stackRequestSlotInfo(swapBedrockSlot, this.stackNetId(swapItem)),
                    container.stackRequestSlotInfo(clickContext.bedrockSlot, this.stackNetId(item))
            ));
        }

        if (!clickContext.inventoryTracker.getHudContainer().getItem(0).isEmpty()) {
            return List.of();
        }

        final List<ItemStackRequestAction> actions = new ArrayList<>();
        actions.add(new ItemStackRequestAction.TakeAction(
                item.amount(),
                container.stackRequestSlotInfo(clickContext.bedrockSlot, this.stackNetId(item)),
                new ItemStackRequestSlotInfo(clickContext.inventoryTracker.getHudContainer().getFullContainerName(0), (byte) 0, 0)
        ));

        actions.add(new ItemStackRequestAction.PlaceAction(
                targetMaxStackSize,
                swapContainer.stackRequestSlotInfo(swapBedrockSlot, this.stackNetId(swapItem)),
                container.stackRequestSlotInfo(clickContext.bedrockSlot, 0)
        ));
        container.setItem(clickContext.bedrockSlot, this.copyStackWithAmount(swapItem, targetMaxStackSize));
        swapContainer.setItem(swapBedrockSlot, this.itemAfterRemovingAmount(swapItem, targetMaxStackSize));

        final InventoryContainer inventory = clickContext.inventoryTracker.getInventoryContainer();
        this.addPrevContainer(clickContext, inventory);
        final int movedToInventory = this.addCursorToInventoryActions(actions, inventory, item, item.amount(), true);
        if (movedToInventory < item.amount()) {
            actions.add(new ItemStackRequestAction.DropAction(
                    item.amount() - movedToInventory,
                    new ItemStackRequestSlotInfo(clickContext.inventoryTracker.getHudContainer().getFullContainerName(0), (byte) 0, this.stackNetId(item)),
                    false
            ));
        }
        return actions;
    }

    private List<ItemStackRequestAction> handleQuickMoveClick(final ClickContext clickContext, final short javaSlot) {
        final SlotRef source = this.resolveJavaSlot(clickContext, javaSlot);
        if (source == null) {
            return List.of();
        }

        BedrockItem sourceItem = source.container().getItem(source.bedrockSlot());
        if (sourceItem.isEmpty()) {
            return List.of();
        }

        final List<ItemStackRequestAction> actions = new ArrayList<>();
        final List<QuickMoveRange> ranges = this.quickMoveRanges(javaSlot, source);
        for (boolean mergePass : new boolean[]{true, false}) {
            for (QuickMoveRange range : ranges) {
                final int start = range.backwards() ? range.endJavaSlot() - 1 : range.startJavaSlot();
                final int end = range.backwards() ? range.startJavaSlot() - 1 : range.endJavaSlot();
                final int step = range.backwards() ? -1 : 1;
                for (int javaDestSlot = start; javaDestSlot != end && !sourceItem.isEmpty(); javaDestSlot += step) {
                    final int bedrockDestSlot = range.container().bedrockSlot(javaDestSlot);
                    if (source.container() == range.container() && source.bedrockSlot() == bedrockDestSlot) {
                        continue;
                    }
                    if (!range.container().canQuickMoveToSlot(bedrockDestSlot, sourceItem)) {
                        continue;
                    }

                    final BedrockItem destinationItem = range.container().getItem(bedrockDestSlot);
                    final int slotMaxStackSize = range.container().maxStackSizeForSlot(bedrockDestSlot, sourceItem);
                    if (mergePass) {
                        if (destinationItem.isEmpty() || destinationItem.isDifferent(sourceItem) || destinationItem.amount() >= slotMaxStackSize) {
                            continue;
                        }
                    } else if (!destinationItem.isEmpty()) {
                        continue;
                    }

                    final int amountToMove = mergePass
                            ? Math.min(sourceItem.amount(), slotMaxStackSize - destinationItem.amount())
                            : Math.min(sourceItem.amount(), slotMaxStackSize);
                    if (amountToMove <= 0) {
                        continue;
                    }

                    this.addPrevContainer(clickContext, source.container());
                    this.addPrevContainer(clickContext, range.container());
                    actions.add(new ItemStackRequestAction.PlaceAction(
                            amountToMove,
                            source.container().stackRequestSlotInfo(source.bedrockSlot(), this.stackNetId(sourceItem)),
                            range.container().stackRequestSlotInfo(bedrockDestSlot, this.stackNetId(destinationItem))
                    ));

                    final BedrockItem newSourceItem = this.itemAfterRemovingAmount(sourceItem, amountToMove);
                    source.container().setItem(source.bedrockSlot(), newSourceItem);
                    if (destinationItem == null || destinationItem.isEmpty()) {
                        final BedrockItem newDestinationItem = sourceItem.copy();
                        newDestinationItem.setAmount(amountToMove);
                        range.container().setItem(bedrockDestSlot, newDestinationItem);
                    } else {
                        final BedrockItem newDestinationItem = destinationItem.copy();
                        newDestinationItem.setAmount(destinationItem.amount() + amountToMove);
                        range.container().setItem(bedrockDestSlot, newDestinationItem);
                    }
                    sourceItem = newSourceItem;
                }
            }
        }

        return actions;
    }

    private List<QuickMoveRange> quickMoveRanges(final short javaSlot, final SlotRef source) {
        final ExperimentalInventoryTracker inventoryTracker = this.user.get(ExperimentalInventoryTracker.class);
        final InventoryContainer inventory = inventoryTracker.getInventoryContainer();
        if (this instanceof InventoryContainer) {
            final List<QuickMoveRange> ranges = new ArrayList<>();
            if (javaSlot >= 9 && javaSlot < 45) {
                final QuickMoveRange equipmentRange = this.equipmentQuickMoveRange(source, inventoryTracker);
                if (equipmentRange != null) {
                    ranges.add(equipmentRange);
                }
            }

            if (javaSlot >= 9 && javaSlot < 36) {
                ranges.add(new QuickMoveRange(inventory, 36, 45, false));
            } else if (javaSlot >= 36 && javaSlot < 45) {
                ranges.add(new QuickMoveRange(inventory, 9, 36, false));
            } else {
                ranges.add(new QuickMoveRange(inventory, 9, 45, false));
            }
            return ranges;
        }

        if (source.container() != inventory && source.container() != inventoryTracker.getArmorContainer() && source.container() != inventoryTracker.getOffhandContainer()) {
            return List.of(
                    new QuickMoveRange(inventory, 0, 9, true),
                    new QuickMoveRange(inventory, 9, inventory.size(), true)
            );
        }

        final BedrockItem sourceItem = source.container().getItem(source.bedrockSlot());
        return switch (this.type) {
            case FURNACE, BLAST_FURNACE, SMOKER -> this.isFurnaceFuel(sourceItem)
                    ? List.of(new QuickMoveRange(this, 1, 2, false), new QuickMoveRange(this, 0, 1, false))
                    : List.of(new QuickMoveRange(this, 0, 1, false), new QuickMoveRange(this, 1, 2, false));
            case BREWING_STAND -> {
                if (this.isItem(sourceItem, "minecraft:blaze_powder")) {
                    yield List.of(new QuickMoveRange(this, 4, 5, false));
                }
                if (this.isBrewingBottle(sourceItem)) {
                    yield List.of(new QuickMoveRange(this, 0, 3, false));
                }
                yield List.of(new QuickMoveRange(this, 3, 4, false));
            }
            case BEACON -> List.of(new QuickMoveRange(this, 0, 1, false));
            case ANVIL -> List.of(new QuickMoveRange(this, 0, 2, false));
            case ENCHANTMENT -> List.of(new QuickMoveRange(this, 0, 2, false));
            case SMITHING_TABLE -> List.of(new QuickMoveRange(this, 0, 3, false));
            case STONECUTTER -> List.of(new QuickMoveRange(this, 0, 1, false));
            case LOOM -> List.of(new QuickMoveRange(this, 0, 3, false));
            case CARTOGRAPHY -> List.of(new QuickMoveRange(this, 0, 2, false));
            case WORKBENCH -> List.of(new QuickMoveRange(this, 1, 10, false));
            case GRINDSTONE -> List.of(new QuickMoveRange(this, 0, 2, false));
            case CRAFTER -> List.of(new QuickMoveRange(this, 0, 9, false));
            default -> List.of(new QuickMoveRange(this, 0, this.size(), false));
        };
    }

    private QuickMoveRange equipmentQuickMoveRange(final SlotRef source, final ExperimentalInventoryTracker inventoryTracker) {
        final BedrockItem item = source.container().getItem(source.bedrockSlot());
        final int javaSlot = this.equipmentJavaSlot(item);
        if (javaSlot >= 5 && javaSlot < 9) {
            final ExperimentalContainer armorContainer = inventoryTracker.getArmorContainer();
            final int bedrockSlot = armorContainer.bedrockSlot(javaSlot);
            return armorContainer.getItem(bedrockSlot).isEmpty() ? new QuickMoveRange(armorContainer, javaSlot, javaSlot + 1, false) : null;
        }
        if (javaSlot == 45) {
            final ExperimentalContainer offhandContainer = inventoryTracker.getOffhandContainer();
            return offhandContainer.getItem(0).isEmpty() ? new QuickMoveRange(offhandContainer, 45, 46, false) : null;
        }
        return null;
    }

    private int equipmentJavaSlot(final BedrockItem item) {
        if (item.isEmpty()) {
            return -1;
        }

        final String identifier = this.user.get(ItemRewriter.class).getItems().inverse().get(item.identifier());
        if (identifier == null) {
            return -1;
        }

        final String name = identifier.startsWith("minecraft:") ? identifier.substring("minecraft:".length()) : identifier;
        if (name.endsWith("_helmet") || name.endsWith("_skull") || name.endsWith("_head") || name.equals("carved_pumpkin")) {
            return 5;
        }
        if (name.endsWith("_chestplate") || name.equals("elytra")) {
            return 6;
        }
        if (name.endsWith("_leggings")) {
            return 7;
        }
        if (name.endsWith("_boots")) {
            return 8;
        }
        if (name.equals("shield")) {
            return 45;
        }
        return -1;
    }

    private SlotRef resolveJavaSlot(final ClickContext clickContext, final short javaSlot) {
        if (javaSlot < 0) {
            return null;
        }

        ExperimentalContainer container = clickContext.container;
        int bedrockSlot = clickContext.bedrockSlot;
        final ExperimentalInventoryTracker inventoryTracker = clickContext.inventoryTracker;
        if (container.type() == ContainerType.CRAFTER && javaSlot == 45) {
            return new SlotRef(container, container.bedrockSlot(javaSlot));
        }
        if (!(container instanceof InventoryContainer) && (javaSlot < 0 || javaSlot >= container.getItems().length)) {
            final ExperimentalContainer inventoryContainer = inventoryTracker.getInventoryContainer();
            final int invSlot = inventoryContainer.bedrockSlot(javaSlot - container.getItems().length + 9);
            if (invSlot < 0 || invSlot >= inventoryContainer.getItems().length) {
                return null;
            }
            return new SlotRef(inventoryContainer, invSlot);
        }

        if (container instanceof InventoryContainer) {
            if (javaSlot >= 0 && javaSlot < 5) {
                final ExperimentalContainer hudContainer = inventoryTracker.getHudContainer();
                return new SlotRef(hudContainer, hudContainer.bedrockSlot(javaSlot));
            } else if (javaSlot >= 5 && javaSlot < 9) {
                final ExperimentalContainer armorContainer = inventoryTracker.getArmorContainer();
                return new SlotRef(armorContainer, armorContainer.bedrockSlot(javaSlot));
            } else if (javaSlot == 45) {
                final ExperimentalContainer offhandContainer = inventoryTracker.getOffhandContainer();
                return new SlotRef(offhandContainer, offhandContainer.bedrockSlot(javaSlot));
            }
        }

        return new SlotRef(container, bedrockSlot);
    }

    protected int maxStackSize(final BedrockItem item) {
        return this.user.get(ItemRewriter.class).maxStackSize(item);
    }

    protected int maxStackSizeForSlot(final int bedrockSlot, final BedrockItem item) {
        return this.maxStackSize(item);
    }

    protected boolean canPlaceItem(final int bedrockSlot, final BedrockItem item) {
        return true;
    }

    protected boolean canQuickMoveToSlot(final int bedrockSlot, final BedrockItem item) {
        return this.canPlaceItem(bedrockSlot, item);
    }

    protected String itemIdentifier(final BedrockItem item) {
        if (item == null || item.isEmpty()) {
            return null;
        }
        return this.user.get(ItemRewriter.class).getItems().inverse().get(item.identifier());
    }

    protected boolean isItem(final BedrockItem item, final String identifier) {
        return identifier.equals(this.itemIdentifier(item));
    }

    protected boolean isAnyItem(final BedrockItem item, final String... identifiers) {
        final String itemIdentifier = this.itemIdentifier(item);
        if (itemIdentifier == null) {
            return false;
        }
        for (String identifier : identifiers) {
            if (identifier.equals(itemIdentifier)) {
                return true;
            }
        }
        return false;
    }

    protected boolean itemNameEndsWith(final BedrockItem item, final String suffix) {
        final String identifier = this.itemIdentifier(item);
        return identifier != null && identifier.endsWith(suffix);
    }

    protected boolean hasEnchantmentTag(final BedrockItem item) {
        return item.tag() != null && item.tag().contains("ench");
    }

    protected boolean isDamageableItem(final BedrockItem item) {
        return this.user.get(ItemRewriter.class).isDamageableItem(item);
    }

    protected int maxDamage(final BedrockItem item) {
        return this.user.get(ItemRewriter.class).maxDamage(item);
    }

    protected boolean isFurnaceFuel(final BedrockItem item) {
        return this.user.get(ItemRewriter.class).isFurnaceFuel(item);
    }

    protected boolean isBrewingBottle(final BedrockItem item) {
        return this.isAnyItem(item, "minecraft:potion", "minecraft:splash_potion", "minecraft:lingering_potion", "minecraft:glass_bottle");
    }

    private void addPrevContainer(final ClickContext clickContext, final ExperimentalContainer container) {
        this.addPrevContainer(clickContext.prevContainers, container);
    }

    protected void addPrevContainer(final List<ExperimentalContainer> prevContainers, final ExperimentalContainer container) {
        for (ExperimentalContainer prevContainer : prevContainers) {
            if (prevContainer.containerId() == container.containerId()) {
                return;
            }
        }
        prevContainers.add(container.copy());
    }

    private ItemStackRequestAction handleThrowClick(final ClickContext clickContext, final short javaSlot, final byte button) {
        if (javaSlot == -999) {
            return this.dropCursorItem(clickContext.inventoryTracker, button);
        }

        final SlotRef source = this.resolveJavaSlot(clickContext, javaSlot);
        if (source == null) {
            ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Tried to handle throw for " + clickContext.container.type() + ", but slot was out of bounds (" + javaSlot + ")");
            return null;
        }

        ExperimentalContainer container = source.container();
        int bedrockSlot = source.bedrockSlot();
        this.addPrevContainer(clickContext, container);

        BedrockItem item = container.getItem(bedrockSlot);

        if (item.isEmpty()) {
            return null;
        }

        int amountToDrop = button == 0 ? 1 : item.amount();

        BedrockItem finalContainerItem = this.itemAfterRemovingAmount(item, amountToDrop);
        container.setItem(bedrockSlot, finalContainerItem);

        return new ItemStackRequestAction.DropAction(
                amountToDrop,
                container.stackRequestSlotInfo(bedrockSlot, this.stackNetId(item)),
                false
        );
    }

    private ItemStackRequestAction dropCursorItem(final ExperimentalInventoryTracker inventoryTracker, final byte button) {
        BedrockItem cursorItem = inventoryTracker.getHudContainer().getItem(0);
        if (cursorItem.isEmpty()) {
            return null;
        }

        int amountToDrop = button == 0 ? cursorItem.amount() : 1;
        inventoryTracker.getHudContainer().setItem(0, this.itemAfterRemovingAmount(cursorItem, amountToDrop));

        return new ItemStackRequestAction.DropAction(
                amountToDrop,
                inventoryTracker.getHudContainer().stackRequestSlotInfo(0, this.stackNetId(cursorItem)),
                false
        );
    }

    protected ItemStackRequestAction dropItem(final ExperimentalContainer container, final int bedrockSlot, final int amountToDrop) {
        BedrockItem item = container.getItem(bedrockSlot);
        if (item.isEmpty()) {
            return null;
        }

        BedrockItem finalContainerItem = this.itemAfterRemovingAmount(item, amountToDrop);
        container.setItem(bedrockSlot, finalContainerItem);

        return new ItemStackRequestAction.DropAction(
                amountToDrop,
                container.stackRequestSlotInfo(bedrockSlot, this.stackNetId(item)),
                false
        );
    }

    private List<ItemStackRequestAction> singletonAction(final ItemStackRequestAction action) {
        return action == null ? List.of() : List.of(action);
    }

    protected int stackNetId(final BedrockItem item) {
        return item.netId() != null ? item.netId() : 0;
    }

    protected BedrockItem copyStackWithAmount(final BedrockItem item, final int amount) {
        BedrockItem copy = item.copy();
        copy.setAmount(amount);
        return copy;
    }

    protected int addToInventory(final ExperimentalContainer inventory, final BedrockItem item, final int amount, final boolean backwards) {
        if (item.isEmpty() || amount <= 0) {
            return 0;
        }

        int remaining = amount;
        for (boolean mergePass : new boolean[]{true, false}) {
            for (int slot : this.inventorySlots(inventory, backwards)) {
                if (remaining <= 0) {
                    break;
                }
                final BedrockItem target = inventory.getItem(slot);
                if (mergePass) {
                    if (target.isEmpty() || target.isDifferent(item) || target.amount() >= this.maxStackSize(item)) {
                        continue;
                    }
                } else if (!target.isEmpty()) {
                    continue;
                }

                final int amountToMove = mergePass
                        ? Math.min(remaining, this.maxStackSize(item) - target.amount())
                        : Math.min(remaining, this.maxStackSize(item));
                if (amountToMove <= 0) {
                    continue;
                }

                if (target.isEmpty()) {
                    inventory.setItem(slot, this.copyStackWithAmount(item, amountToMove));
                } else {
                    final BedrockItem newTarget = target.copy();
                    newTarget.setAmount(target.amount() + amountToMove);
                    inventory.setItem(slot, newTarget);
                }
                remaining -= amountToMove;
            }
        }

        return amount - remaining;
    }

    protected List<Integer> inventorySlots(final ExperimentalContainer inventory, final boolean backwards) {
        final List<Integer> slots = new ArrayList<>(inventory.size());
        if (backwards && inventory.type() == ContainerType.INVENTORY && inventory.size() == 36) {
            for (int slot = 8; slot >= 0; slot--) {
                slots.add(slot);
            }
            for (int slot = inventory.size() - 1; slot >= 9; slot--) {
                slots.add(slot);
            }
            return slots;
        }

        if (backwards) {
            for (int slot = inventory.size() - 1; slot >= 0; slot--) {
                slots.add(slot);
            }
        } else {
            for (int slot = 0; slot < inventory.size(); slot++) {
                slots.add(slot);
            }
        }
        return slots;
    }

    protected BedrockItem itemAfterRemovingAmount(final BedrockItem item, final int amountToRemove) {
        if (amountToRemove >= item.amount()) {
            return BedrockItem.empty();
        }

        BedrockItem copy = item.copy();
        copy.setAmount(item.amount() - amountToRemove);
        return copy;
    }

    protected boolean handleCreatedOutputClick(final int revision, final byte button, final ContainerInput action, final BedrockItem resultItem, final List<ItemStackRequestAction> headerActions, final List<ResultIngredient> ingredients) {
        if (action != ContainerInput.PICKUP && action != ContainerInput.QUICK_MOVE && action != ContainerInput.SWAP) {
            return false;
        }
        if (resultItem.isEmpty()) {
            return false;
        }

        final ExperimentalInventoryTracker inventoryTracker = this.user.get(ExperimentalInventoryTracker.class);
        final InventoryContainer inventory = inventoryTracker.getInventoryContainer();
        final BedrockItem cursorItem = inventoryTracker.getHudContainer().getItem(0);
        if (action == ContainerInput.PICKUP && !cursorItem.isEmpty() && (cursorItem.isDifferent(resultItem) || cursorItem.amount() + resultItem.amount() > this.maxStackSize(resultItem))) {
            return false;
        }
        if (action == ContainerInput.QUICK_MOVE && this.inventoryCapacity(inventory, resultItem) < resultItem.amount()) {
            return false;
        }
        final SwapDestination swapDestination = action == ContainerInput.SWAP ? this.resultSwapDestination(button, resultItem) : null;
        if (action == ContainerInput.SWAP && swapDestination == null) {
            return false;
        }

        final InventoryRequestTracker inventoryRequestTracker = this.user.get(InventoryRequestTracker.class);
        final int requestId = inventoryRequestTracker.nextRequestId();
        final List<ItemStackRequestAction> actions = new ArrayList<>(headerActions);
        actions.add(new ItemStackRequestAction.CraftResultsDeprecatedAction(List.of(resultItem), 1));
        for (ResultIngredient ingredient : ingredients) {
            final BedrockItem item = this.getItem(ingredient.bedrockSlot());
            final int count = Math.min(ingredient.count(), item.amount());
            if (ingredient.requestAction() && !item.isEmpty() && count > 0) {
                actions.add(new ItemStackRequestAction.ConsumeAction(
                        count,
                        this.stackRequestSlotInfo(ingredient.bedrockSlot(), this.stackNetId(item))
                ));
            }
        }

        if (action == ContainerInput.PICKUP) {
            actions.add(new ItemStackRequestAction.PlaceAction(
                    resultItem.amount(),
                    new ItemStackRequestSlotInfo(new FullContainerName(ContainerEnumName.CreatedOutputContainer, null), (byte) 50, requestId),
                    new ItemStackRequestSlotInfo(new FullContainerName(ContainerEnumName.CursorContainer, null), (byte) 0, this.stackNetId(cursorItem))
            ));
        } else if (action == ContainerInput.QUICK_MOVE) {
            this.addOutputToInventoryActions(actions, inventory, resultItem, resultItem.amount(), requestId);
        } else {
            actions.add(this.placeCreatedOutputAction(resultItem, requestId, swapDestination));
        }

        final ItemStackRequestInfo request = new ItemStackRequestInfo(requestId, actions, List.of(), TextProcessingEventOrigin.unknown);
        final List<ExperimentalContainer> prevContainers = new ArrayList<>();
        this.addPrevContainer(prevContainers, this);
        if (action == ContainerInput.QUICK_MOVE) {
            this.addPrevContainer(prevContainers, inventory);
        } else if (action == ContainerInput.SWAP) {
            this.addPrevContainer(prevContainers, swapDestination.container());
        }
        inventoryRequestTracker.addRequest(new InventoryRequestStorage(request, revision, inventoryTracker.getHudContainer().copy(), prevContainers));
        ExperimentalPacketFactory.sendBedrockInventoryRequest(this.user, new ItemStackRequestInfo[]{request});

        for (ResultIngredient ingredient : ingredients) {
            final BedrockItem item = this.getItem(ingredient.bedrockSlot());
            this.setItem(ingredient.bedrockSlot(), this.itemAfterRemovingAmount(item, ingredient.count()));
        }
        this.setItem(50, this.createdOutputAfterTake(resultItem));

        if (action == ContainerInput.PICKUP) {
            if (cursorItem.isEmpty()) {
                inventoryTracker.getHudContainer().setItem(0, resultItem.copy());
            } else {
                final BedrockItem newCursorItem = cursorItem.copy();
                newCursorItem.setAmount(cursorItem.amount() + resultItem.amount());
                inventoryTracker.getHudContainer().setItem(0, newCursorItem);
            }
        } else if (action == ContainerInput.QUICK_MOVE) {
            this.addToInventory(inventory, resultItem, resultItem.amount(), true);
            ExperimentalPacketFactory.sendJavaContainerSetContent(this.user, inventory);
        } else {
            swapDestination.container().setItem(swapDestination.bedrockSlot(), resultItem.copy());
            ExperimentalPacketFactory.sendJavaContainerSetContent(this.user, swapDestination.container());
        }

        ExperimentalPacketFactory.sendJavaContainerSetContent(this.user, this);
        return true;
    }

    protected BedrockItem createdOutputAfterTake(final BedrockItem resultItem) {
        return BedrockItem.empty();
    }

    protected SwapDestination resultSwapDestination(final byte button, final BedrockItem resultItem) {
        final ExperimentalInventoryTracker inventoryTracker = this.user.get(ExperimentalInventoryTracker.class);
        final ExperimentalContainer destinationContainer;
        final int destinationSlot;
        if (button >= 0 && button < 9) {
            destinationContainer = inventoryTracker.getInventoryContainer();
            destinationSlot = button;
        } else if (button == 40) {
            destinationContainer = inventoryTracker.getOffhandContainer();
            destinationSlot = 0;
        } else {
            return null;
        }

        final BedrockItem destinationItem = destinationContainer.getItem(destinationSlot);
        if (!destinationItem.isEmpty()) {
            return null;
        }
        if (!destinationContainer.canPlaceItem(destinationSlot, resultItem) || resultItem.amount() > destinationContainer.maxStackSizeForSlot(destinationSlot, resultItem)) {
            return null;
        }
        return new SwapDestination(destinationContainer, destinationSlot);
    }

    protected ItemStackRequestAction placeCreatedOutputAction(final BedrockItem resultItem, final int requestId, final SwapDestination destination) {
        return new ItemStackRequestAction.PlaceAction(
                resultItem.amount(),
                new ItemStackRequestSlotInfo(new FullContainerName(ContainerEnumName.CreatedOutputContainer, null), (byte) 50, requestId),
                destination.container().stackRequestSlotInfo(destination.bedrockSlot(), 0)
        );
    }

    protected ItemStackRequestAction takeCreatedOutputAction(final BedrockItem resultItem, final int requestId, final SwapDestination destination) {
        return new ItemStackRequestAction.TakeAction(
                resultItem.amount(),
                new ItemStackRequestSlotInfo(new FullContainerName(ContainerEnumName.CreatedOutputContainer, null), (byte) 50, requestId),
                destination.container().stackRequestSlotInfo(destination.bedrockSlot(), 0)
        );
    }

    private void addOutputToInventoryActions(final List<ItemStackRequestAction> actions, final InventoryContainer inventory, final BedrockItem resultItem, final int totalAmount, final int requestId) {
        int remaining = totalAmount;
        for (boolean mergePass : new boolean[]{true, false}) {
            for (int slot : this.inventorySlots(inventory, true)) {
                if (remaining <= 0) {
                    break;
                }
                final BedrockItem destinationItem = inventory.getItem(slot);
                if (mergePass) {
                    if (destinationItem.isEmpty() || destinationItem.isDifferent(resultItem) || destinationItem.amount() >= this.maxStackSize(resultItem)) {
                        continue;
                    }
                } else if (!destinationItem.isEmpty()) {
                    continue;
                }

                final int amountToMove = mergePass
                        ? Math.min(remaining, this.maxStackSize(resultItem) - destinationItem.amount())
                        : Math.min(remaining, this.maxStackSize(resultItem));
                actions.add(new ItemStackRequestAction.PlaceAction(
                        amountToMove,
                        new ItemStackRequestSlotInfo(new FullContainerName(ContainerEnumName.CreatedOutputContainer, null), (byte) 50, requestId),
                        inventory.stackRequestSlotInfo(slot, this.stackNetId(destinationItem))
                ));
                remaining -= amountToMove;
            }
        }
    }

    private int addCursorToInventoryActions(final List<ItemStackRequestAction> actions, final InventoryContainer inventory, final BedrockItem item, final int totalAmount, final boolean backwards) {
        int remaining = totalAmount;
        for (boolean mergePass : new boolean[]{true, false}) {
            for (int slot : this.inventorySlots(inventory, backwards)) {
                if (remaining <= 0) {
                    break;
                }
                final BedrockItem destinationItem = inventory.getItem(slot);
                if (mergePass) {
                    if (destinationItem.isEmpty() || destinationItem.isDifferent(item) || destinationItem.amount() >= this.maxStackSize(item)) {
                        continue;
                    }
                } else if (!destinationItem.isEmpty()) {
                    continue;
                }

                final int amountToMove = mergePass
                        ? Math.min(remaining, this.maxStackSize(item) - destinationItem.amount())
                        : Math.min(remaining, this.maxStackSize(item));
                if (amountToMove <= 0) {
                    continue;
                }

                actions.add(new ItemStackRequestAction.PlaceAction(
                        amountToMove,
                        new ItemStackRequestSlotInfo(new FullContainerName(ContainerEnumName.CursorContainer, null), (byte) 0, this.stackNetId(item)),
                        inventory.stackRequestSlotInfo(slot, this.stackNetId(destinationItem))
                ));

                if (destinationItem.isEmpty()) {
                    inventory.setItem(slot, this.copyStackWithAmount(item, amountToMove));
                } else {
                    final BedrockItem newDestinationItem = destinationItem.copy();
                    newDestinationItem.setAmount(destinationItem.amount() + amountToMove);
                    inventory.setItem(slot, newDestinationItem);
                }
                remaining -= amountToMove;
            }
        }
        return totalAmount - remaining;
    }

    protected int inventoryCapacity(final InventoryContainer inventory, final BedrockItem resultItem) {
        int capacity = 0;
        for (int slot = inventory.size() - 1; slot >= 0; slot--) {
            final BedrockItem item = inventory.getItem(slot);
            if (item.isEmpty()) {
                capacity += this.maxStackSize(resultItem);
            } else if (!item.isDifferent(resultItem)) {
                capacity += this.maxStackSize(resultItem) - item.amount();
            }
        }
        return capacity;
    }

    private static final class ClickContext {
        private ExperimentalContainer container;
        private int bedrockSlot;
        private final ExperimentalInventoryTracker inventoryTracker;
        private final InventoryRequestTracker inventoryRequestTracker;
        private final List<ExperimentalContainer> prevContainers = new ArrayList<>();
        private final ExperimentalContainer prevCursorContainer;

        private ClickContext(final ExperimentalContainer container, final int bedrockSlot, final ExperimentalInventoryTracker inventoryTracker, final InventoryRequestTracker inventoryRequestTracker) {
            this.container = container;
            this.bedrockSlot = bedrockSlot;
            this.inventoryTracker = inventoryTracker;
            this.inventoryRequestTracker = inventoryRequestTracker;
            this.prevCursorContainer = inventoryTracker.getHudContainer().copy();
        }
    }

    private record SlotRef(ExperimentalContainer container, int bedrockSlot) {
    }

    private record QuickMoveRange(ExperimentalContainer container, int startJavaSlot, int endJavaSlot, boolean backwards) {
    }

    protected record SwapDestination(ExperimentalContainer container, int bedrockSlot) {
    }

    public record ResultIngredient(int bedrockSlot, int count, boolean requestAction) {
        public ResultIngredient(final int bedrockSlot, final int count) {
            this(bedrockSlot, count, true);
        }
    }

    public boolean handleButtonClick(final int button) {
        return false;
    }

    public void clearItems() {
        for (int i = 0; i < this.items.length; i++) {
            this.items[i] = BedrockItem.empty();
        }
    }

    public Item getJavaItem(final int slot) {
        return this.user.get(ItemRewriter.class).javaItem(this.getItem(slot));
    }

    public Item[] getJavaItems() {
        return this.user.get(ItemRewriter.class).javaItems(this.items);
    }

    public BedrockItem getItem(final int bedrockSlot) {
        return this.items[bedrockSlot];
    }

    public BedrockItem[] getItems() {
        return this.copyItems();
    }

    public boolean setItem(final int bedrockSlot, final BedrockItem item) {
        if (bedrockSlot < 0 || bedrockSlot >= this.items.length) {
            ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Tried to set item for " + this.type + ", but slot was out of bounds (" + bedrockSlot + ")");
            return false;
        }

        final BedrockItem oldItem = this.items[bedrockSlot];
        this.items[bedrockSlot] = item;
        this.onSlotChanged(bedrockSlot, oldItem, item);
        return true;
    }

    public boolean setItems(final BedrockItem[] items) {
        if (items.length != this.items.length) {
            ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Tried to set items for " + this.type + ", but items array length was not correct (" + items.length + " != " + this.items.length + ")");
            return false;
        }

        for (int i = 0; i < items.length; i++) {
            this.items[i] = items[i] != null ? items[i].copy() : BedrockItem.empty();
        }
        return true;
    }

    public int javaSlot(final int bedrockSlot) {
        return bedrockSlot;
    }

    public int bedrockSlot(final int javaSlot) {
        return javaSlot;
    }

    public int stackRequestSlot(final int bedrockSlot) {
        return bedrockSlot;
    }

    public int bedrockSlotFromStackRequest(final int requestSlot) {
        return requestSlot;
    }

    public ItemStackRequestSlotInfo stackRequestSlotInfo(final int bedrockSlot, final int stackNetId) {
        return new ItemStackRequestSlotInfo(this.getFullContainerName(bedrockSlot), (byte) this.stackRequestSlot(bedrockSlot), stackNetId);
    }

    public byte javaContainerId() {
        return this.containerId();
    }

    public int size() {
        return this.items.length;
    }

    public byte containerId() {
        return this.containerId;
    }

    public ContainerType type() {
        return this.type;
    }

    public TextComponent title() {
        return this.title;
    }

    public BlockPosition position() {
        return this.position;
    }

    public boolean isValidBlockTag(final String tag) {
        if (tag == null) {
            return false;
        } else {
            return this.validBlockTags.contains(tag);
        }
    }

    protected void onSlotChanged(final int javaSlot, final BedrockItem oldItem, final BedrockItem newItem) {
    }

    public ExperimentalContainer copy() { // TODO: This probably isnt the best way to do this
        BedrockItem[] itemsCopy = this.copyItems();
        return new ExperimentalContainer(this.user, this.containerId, this.type, this.title, this.position, itemsCopy, this.validBlockTags) {
            @Override
            public FullContainerName getFullContainerName(int slot) {
                return ExperimentalContainer.this.getFullContainerName(slot);
            }

            @Override
            public int stackRequestSlot(final int bedrockSlot) {
                return ExperimentalContainer.this.stackRequestSlot(bedrockSlot);
            }

            @Override
            public int bedrockSlotFromStackRequest(final int requestSlot) {
                return ExperimentalContainer.this.bedrockSlotFromStackRequest(requestSlot);
            }
        };
    }

    private BedrockItem[] copyItems() {
        final BedrockItem[] itemsCopy = new BedrockItem[this.items.length];
        for (int i = 0; i < this.items.length; i++) {
            itemsCopy[i] = this.items[i] != null ? this.items[i].copy() : BedrockItem.empty();
        }
        return itemsCopy;
    }

    public short translateContainerData(int containerData) {
        ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "translateContainerData not implemented for container type: " + this.type);
        return -1;
    }

    public int translateContainerDataValue(final int containerData, final int value) {
        return value;
    }
}

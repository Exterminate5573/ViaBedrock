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
package net.raphimc.viabedrock.experimental.model.container.player;

import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.minecraft.BlockPosition;
import com.viaversion.viaversion.api.minecraft.item.Item;
import com.viaversion.viaversion.api.minecraft.item.StructuredItem;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.protocols.v1_21_11to26_1.packet.ClientboundPackets26_1;
import com.viaversion.viaversion.api.type.types.version.VersionedTypes;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.experimental.ExperimentalPacketFactory;
import net.raphimc.viabedrock.experimental.model.container.ExperimentalContainer;
import net.raphimc.viabedrock.experimental.model.inventory.ItemStackRequestAction;
import net.raphimc.viabedrock.experimental.model.inventory.ItemStackRequestInfo;
import net.raphimc.viabedrock.experimental.model.inventory.ItemStackRequestSlotInfo;
import net.raphimc.viabedrock.experimental.model.recipe.ShapedRecipe;
import net.raphimc.viabedrock.experimental.model.recipe.ShapelessRecipe;
import net.raphimc.viabedrock.experimental.storage.*;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.ServerboundBedrockPackets;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerEnumName;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerID;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerType;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.InteractPacket_Action;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.TextProcessingEventOrigin;
import net.raphimc.viabedrock.protocol.data.enums.java.generated.ContainerInput;
import net.raphimc.viabedrock.protocol.model.BedrockItem;
import net.raphimc.viabedrock.protocol.model.FullContainerName;
import net.raphimc.viabedrock.protocol.rewriter.ItemRewriter;
import net.raphimc.viabedrock.protocol.storage.EntityTracker;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

public class InventoryContainer extends ExperimentalContainer {

    private byte selectedHotbarSlot = 0;

    public InventoryContainer(final UserConnection user) {
        super(user, (byte) ContainerID.CONTAINER_ID_INVENTORY.getValue(), ContainerType.INVENTORY, null, null, 36);
    }

    public InventoryContainer(final UserConnection user, final byte containerId, final BlockPosition position, final InventoryContainer inventoryContainer) {
        super(user, containerId, inventoryContainer.type, inventoryContainer.title, position, inventoryContainer.items, inventoryContainer.validBlockTags);
        this.selectedHotbarSlot = inventoryContainer.selectedHotbarSlot;
    }

    @Override
    public FullContainerName getFullContainerName(int slot) {
        if (slot < 9) {
            return new FullContainerName(ContainerEnumName.HotbarContainer, null);
        }

        return new FullContainerName(ContainerEnumName.InventoryContainer, null);
    }

    @Override
    public Item[] getJavaItems() {
        final ExperimentalInventoryTracker inventoryTracker = this.user.get(ExperimentalInventoryTracker.class);
        final Item[] inventoryItems = super.getJavaItems();
        final Item[] armorItems = inventoryTracker.getArmorContainer().getActualJavaItems();
        final Item[] offhandItems = inventoryTracker.getOffhandContainer().getActualJavaItems();
        final ExperimentalContainer hudContainer = inventoryTracker.getHudContainer();

        final Item[] combinedItems = StructuredItem.emptyArray(46);
        combinedItems[0] = hudContainer.getJavaItem(50);
        System.arraycopy(armorItems, 0, combinedItems, 5, armorItems.length);
        System.arraycopy(inventoryItems, 9, combinedItems, 9, 27);
        System.arraycopy(inventoryItems, 0, combinedItems, 36, 9);
        System.arraycopy(offhandItems, 0, combinedItems, 45, offhandItems.length);
        for (int i = 0; i < 4; i++) {
            combinedItems[1 + i] = hudContainer.getJavaItem(28 + i);
        }
        return combinedItems;
    }

    @Override
    public boolean setItems(BedrockItem[] items) {
        if (items.length != this.size()) {
            final BedrockItem[] newItems = this.getItems();
            System.arraycopy(items, 0, newItems, 0, Math.min(items.length, newItems.length));
            items = newItems;
        }
        return super.setItems(items);
    }

    @Override
    public int javaSlot(final int slot) {
        if (slot < 9) {
            return 36 + slot;
        } else {
            return super.javaSlot(slot);
        }
    }

    @Override
    public int bedrockSlot(final int slot) {
        if (slot >= 36 && slot < 45) {
            return slot - 36;
        } else {
            return super.bedrockSlot(slot);
        }
    }

    @Override
    public byte javaContainerId() {
        return (byte) ContainerID.CONTAINER_ID_INVENTORY.getValue();
    }

    @Override
    public boolean handleClick(final int revision, final short javaSlot, final byte button, final ContainerInput action) {
        if (!ViaBedrock.getConfig().shouldEnableExperimentalFeatures()) {
            return super.handleClick(revision, javaSlot, button, action);
        }

        final ExperimentalInventoryTracker inventoryTracker = this.user.get(ExperimentalInventoryTracker.class);
        if (javaSlot == 0) {
            final CraftingDataTracker.RecipeMatch recipeMatch = this.recipeMatch(inventoryTracker);
            final BedrockItem resultItem = this.resultItem(recipeMatch);
            this.updateCraftingOutputSlot(revision, inventoryTracker, resultItem);
            if (recipeMatch == null || resultItem.isEmpty()) {
                return false;
            }

            return switch (action) {
                case PICKUP -> this.craftToCursor(revision, inventoryTracker, resultItem, recipeMatch);
                case QUICK_MOVE -> this.craftToInventory(revision, inventoryTracker, resultItem, recipeMatch);
                case SWAP -> this.craftToSwapDestination(revision, inventoryTracker, resultItem, recipeMatch, button);
                default -> false;
            };
        }

        final boolean result = super.handleClick(revision, javaSlot, button, action);
        if (javaSlot >= 1 && javaSlot <= 4) {
            this.updateCraftingOutputSlot(revision, inventoryTracker, this.resultItem(this.recipeMatch(inventoryTracker)));
        }
        return result;
    }

    private CraftingDataTracker.RecipeMatch recipeMatch(final ExperimentalInventoryTracker inventoryTracker) {
        return this.user.get(CraftingDataTracker.class).getRecipeMatch(inventoryTracker.getHudContainer(), "crafting_table");
    }

    private BedrockItem resultItem(final CraftingDataTracker.RecipeMatch recipeMatch) {
        return recipeMatch == null ? BedrockItem.empty() : this.resultItem(recipeMatch.craftingData());
    }

    private BedrockItem resultItem(final CraftingDataStorage craftingDataStorage) {
        if (craftingDataStorage == null) {
            return BedrockItem.empty();
        }

        return switch (craftingDataStorage.type()) {
            case SHAPELESS -> ((ShapelessRecipe) craftingDataStorage.recipe()).getResults().get(0);
            case SHAPED -> ((ShapedRecipe) craftingDataStorage.recipe()).getResults().get(0);
            default -> {
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Unknown recipe type for inventory crafting: " + craftingDataStorage.type());
                yield BedrockItem.empty();
            }
        };
    }

    private void updateCraftingOutputSlot(final int revision, final ExperimentalInventoryTracker inventoryTracker, final BedrockItem resultItem) {
        inventoryTracker.getHudContainer().setItem(50, resultItem);

        final PacketWrapper containerSlot = PacketWrapper.create(ClientboundPackets26_1.CONTAINER_SET_SLOT, this.user);
        containerSlot.write(Types.VAR_INT, (int) this.javaContainerId());
        containerSlot.write(Types.VAR_INT, revision);
        containerSlot.write(Types.SHORT, (short) 0);
        containerSlot.write(VersionedTypes.V1_21_11.item, this.user.get(ItemRewriter.class).javaItem(resultItem));
        containerSlot.send(BedrockProtocol.class);
    }

    private boolean craftToCursor(final int revision, final ExperimentalInventoryTracker inventoryTracker, final BedrockItem resultItem, final CraftingDataTracker.RecipeMatch recipeMatch) {
        final BedrockItem cursorItem = inventoryTracker.getHudContainer().getItem(0);
        if (!cursorItem.isEmpty() && (cursorItem.isDifferent(resultItem) || cursorItem.amount() + resultItem.amount() > this.maxStackSize(resultItem))) {
            return false;
        }

        final InventoryRequestTracker inventoryRequestTracker = this.user.get(InventoryRequestTracker.class);
        final int requestId = inventoryRequestTracker.nextRequestId();
        final List<ItemStackRequestAction> actions = this.craftActions(inventoryTracker, recipeMatch, resultItem, 1, requestId);
        if (!this.remaindersFit(inventoryTracker, recipeMatch.ingredients(), 1)) {
            return false;
        }
        actions.add(new ItemStackRequestAction.TakeAction(
                resultItem.amount(),
                new ItemStackRequestSlotInfo(new FullContainerName(ContainerEnumName.CreatedOutputContainer, null), (byte) 50, requestId),
                new ItemStackRequestSlotInfo(new FullContainerName(ContainerEnumName.CursorContainer, null), (byte) 0, cursorItem.netId() != null ? cursorItem.netId() : 0)
        ));

        this.sendCraftRequest(revision, inventoryTracker, inventoryRequestTracker, requestId, actions);
        if (cursorItem.isEmpty()) {
            inventoryTracker.getHudContainer().setItem(0, resultItem.copy());
        } else {
            final BedrockItem newCursorItem = cursorItem.copy();
            newCursorItem.setAmount(cursorItem.amount() + resultItem.amount());
            inventoryTracker.getHudContainer().setItem(0, newCursorItem);
        }
        this.consumeIngredients(inventoryTracker, recipeMatch.ingredients(), 1);
        this.updateCraftingOutputSlot(revision, inventoryTracker, this.resultItem(this.recipeMatch(inventoryTracker)));
        ExperimentalPacketFactory.sendJavaContainerSetContent(this.user, this);
        return true;
    }

    private boolean craftToInventory(final int revision, final ExperimentalInventoryTracker inventoryTracker, final BedrockItem resultItem, final CraftingDataTracker.RecipeMatch recipeMatch) {
        final int maxCrafts = this.maxCrafts(inventoryTracker, recipeMatch.ingredients());
        if (maxCrafts <= 0) {
            return false;
        }

        final int craftLimit = this.hasCraftingRemainders(inventoryTracker, recipeMatch.ingredients()) ? 1 : maxCrafts;
        final int crafts = this.craftsThatFit(inventoryTracker, resultItem, recipeMatch.ingredients(), craftLimit);
        if (crafts <= 0) {
            return false;
        }

        final InventoryRequestTracker inventoryRequestTracker = this.user.get(InventoryRequestTracker.class);
        final int requestId = inventoryRequestTracker.nextRequestId();
        final List<ItemStackRequestAction> actions = this.craftActions(inventoryTracker, recipeMatch, resultItem, crafts, requestId);
        this.addInventoryTransferActions(actions, resultItem, crafts * resultItem.amount(), requestId);

        this.sendCraftRequest(revision, inventoryTracker, inventoryRequestTracker, requestId, actions);
        this.addToInventory(this, resultItem, crafts * resultItem.amount(), true);
        this.consumeIngredients(inventoryTracker, recipeMatch.ingredients(), crafts);
        this.updateCraftingOutputSlot(revision, inventoryTracker, this.resultItem(this.recipeMatch(inventoryTracker)));
        ExperimentalPacketFactory.sendJavaContainerSetContent(this.user, this);
        return true;
    }

    private boolean craftToSwapDestination(final int revision, final ExperimentalInventoryTracker inventoryTracker, final BedrockItem resultItem, final CraftingDataTracker.RecipeMatch recipeMatch, final byte button) {
        final SwapDestination swapDestination = this.resultSwapDestination(button, resultItem);
        if (swapDestination == null) {
            return false;
        }

        final ExperimentalContainer inventoryCopy = this.copy();
        if (swapDestination.container() == this) {
            inventoryCopy.setItem(swapDestination.bedrockSlot(), resultItem.copy());
        }
        if (!this.remaindersFit(inventoryTracker, recipeMatch.ingredients(), inventoryCopy, 1)) {
            return false;
        }

        final InventoryRequestTracker inventoryRequestTracker = this.user.get(InventoryRequestTracker.class);
        final int requestId = inventoryRequestTracker.nextRequestId();
        final List<ItemStackRequestAction> actions = this.craftActions(inventoryTracker, recipeMatch, resultItem, 1, requestId);
        actions.add(this.takeCreatedOutputAction(resultItem, requestId, swapDestination));

        final ItemStackRequestInfo request = new ItemStackRequestInfo(requestId, actions, List.of(), TextProcessingEventOrigin.unknown);
        final List<ExperimentalContainer> prevContainers = new ArrayList<>();
        this.addPrevContainer(prevContainers, this);
        this.addPrevContainer(prevContainers, inventoryTracker.getHudContainer());
        this.addPrevContainer(prevContainers, swapDestination.container());
        inventoryRequestTracker.addRequest(new InventoryRequestStorage(request, revision, inventoryTracker.getHudContainer().copy(), prevContainers));
        ExperimentalPacketFactory.sendBedrockInventoryRequest(this.user, new ItemStackRequestInfo[]{request});

        swapDestination.container().setItem(swapDestination.bedrockSlot(), resultItem.copy());
        this.consumeIngredients(inventoryTracker, recipeMatch.ingredients(), 1);
        this.updateCraftingOutputSlot(revision, inventoryTracker, this.resultItem(this.recipeMatch(inventoryTracker)));
        ExperimentalPacketFactory.sendJavaContainerSetContent(this.user, this);
        if (swapDestination.container() != this) {
            ExperimentalPacketFactory.sendJavaContainerSetContent(this.user, swapDestination.container());
        }
        return true;
    }

    private void sendCraftRequest(final int revision, final ExperimentalInventoryTracker inventoryTracker, final InventoryRequestTracker inventoryRequestTracker, final int requestId, final List<ItemStackRequestAction> actions) {
        final ItemStackRequestInfo request = new ItemStackRequestInfo(requestId, actions, List.of(), TextProcessingEventOrigin.unknown);
        final List<ExperimentalContainer> prevContainers = new ArrayList<>();
        prevContainers.add(this.copy());
        prevContainers.add(inventoryTracker.getHudContainer().copy());
        inventoryRequestTracker.addRequest(new InventoryRequestStorage(request, revision, inventoryTracker.getHudContainer().copy(), prevContainers));
        ExperimentalPacketFactory.sendBedrockInventoryRequest(this.user, new ItemStackRequestInfo[]{request});
    }

    private List<ItemStackRequestAction> craftActions(final ExperimentalInventoryTracker inventoryTracker, final CraftingDataTracker.RecipeMatch recipeMatch, final BedrockItem resultItem, final int crafts, final int requestId) {
        final List<ItemStackRequestAction> actions = new ArrayList<>();
        actions.add(new ItemStackRequestAction.CraftRecipeAction(recipeMatch.craftingData().networkId(), crafts));
        actions.add(new ItemStackRequestAction.CraftResultsDeprecatedAction(List.of(resultItem), crafts));
        for (CraftingDataTracker.IngredientUse ingredient : recipeMatch.ingredients()) {
            final int inputSlot = ingredient.bedrockSlot();
            final BedrockItem item = inventoryTracker.getHudContainer().getItem(inputSlot);
            if (!item.isEmpty()) {
                actions.add(new ItemStackRequestAction.ConsumeAction(
                        ingredient.count() * crafts,
                        inventoryTracker.getHudContainer().stackRequestSlotInfo(inputSlot, item.netId() != null ? item.netId() : 0)
                ));
            }
        }
        for (CraftingDataTracker.IngredientUse ingredient : recipeMatch.ingredients()) {
            final int inputSlot = ingredient.bedrockSlot();
            if (!this.craftingRemainder(inventoryTracker.getHudContainer().getItem(inputSlot)).isEmpty()) {
                actions.add(new ItemStackRequestAction.CreateAction(inputSlot));
            }
        }
        return actions;
    }

    private void addInventoryTransferActions(final List<ItemStackRequestAction> actions, final BedrockItem resultItem, final int totalAmount, final int requestId) {
        int remaining = totalAmount;
        for (boolean mergePass : new boolean[]{true, false}) {
            for (int slot : this.inventorySlots(this, true)) {
                if (remaining <= 0) {
                    break;
                }
                final BedrockItem destinationItem = this.getItem(slot);
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
                actions.add(new ItemStackRequestAction.TakeAction(
                        amountToMove,
                        new ItemStackRequestSlotInfo(new FullContainerName(ContainerEnumName.CreatedOutputContainer, null), (byte) 50, requestId),
                        this.stackRequestSlotInfo(slot, destinationItem.netId() != null ? destinationItem.netId() : 0)
                ));
                remaining -= amountToMove;
            }
        }
    }

    private int craftsThatFit(final ExperimentalInventoryTracker inventoryTracker, final BedrockItem resultItem, final List<CraftingDataTracker.IngredientUse> ingredients, final int maxCrafts) {
        for (int crafts = maxCrafts; crafts > 0; crafts--) {
            final ExperimentalContainer inventoryCopy = this.copy();
            final int totalResultAmount = crafts * resultItem.amount();
            if (this.addToInventory(inventoryCopy, resultItem, totalResultAmount, true) != totalResultAmount) {
                continue;
            }
            if (this.remaindersFit(inventoryTracker, ingredients, inventoryCopy, crafts)) {
                return crafts;
            }
        }
        return 0;
    }

    private int maxCrafts(final ExperimentalInventoryTracker inventoryTracker, final List<CraftingDataTracker.IngredientUse> ingredients) {
        int maxCrafts = Integer.MAX_VALUE;
        for (CraftingDataTracker.IngredientUse ingredient : ingredients) {
            final BedrockItem item = inventoryTracker.getHudContainer().getItem(ingredient.bedrockSlot());
            if (!item.isEmpty() && ingredient.count() > 0) {
                maxCrafts = Math.min(maxCrafts, item.amount() / ingredient.count());
            }
        }
        return maxCrafts == Integer.MAX_VALUE ? 0 : maxCrafts;
    }

    private boolean hasCraftingRemainders(final ExperimentalInventoryTracker inventoryTracker, final List<CraftingDataTracker.IngredientUse> ingredients) {
        for (CraftingDataTracker.IngredientUse ingredient : ingredients) {
            if (!this.craftingRemainder(inventoryTracker.getHudContainer().getItem(ingredient.bedrockSlot())).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private boolean remaindersFit(final ExperimentalInventoryTracker inventoryTracker, final List<CraftingDataTracker.IngredientUse> ingredients, final int crafts) {
        return this.remaindersFit(inventoryTracker, ingredients, this.copy(), crafts);
    }

    private boolean remaindersFit(final ExperimentalInventoryTracker inventoryTracker, final List<CraftingDataTracker.IngredientUse> ingredients, final ExperimentalContainer inventoryCopy, final int crafts) {
        for (int craft = 0; craft < crafts; craft++) {
            for (CraftingDataTracker.IngredientUse ingredient : ingredients) {
                for (int i = 0; i < ingredient.count(); i++) {
                    final BedrockItem item = inventoryTracker.getHudContainer().getItem(ingredient.bedrockSlot());
                    final BedrockItem remainder = this.craftingRemainder(item);
                    if (remainder.isEmpty()) {
                        continue;
                    }

                    final BedrockItem newItem = this.itemAfterRemovingAmount(item, 1);
                    if (newItem.isEmpty() || !newItem.isDifferent(remainder) && newItem.amount() < this.maxStackSize(newItem)) {
                        continue;
                    }
                    if (this.addToInventory(inventoryCopy, remainder, remainder.amount(), true) != remainder.amount()) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private void consumeIngredients(final ExperimentalInventoryTracker inventoryTracker, final List<CraftingDataTracker.IngredientUse> ingredients, final int crafts) {
        for (int craft = 0; craft < crafts; craft++) {
            for (CraftingDataTracker.IngredientUse ingredient : ingredients) {
                for (int i = 0; i < ingredient.count(); i++) {
                    this.consumeIngredient(inventoryTracker, ingredient.bedrockSlot());
                }
            }
        }
    }

    private void consumeIngredient(final ExperimentalInventoryTracker inventoryTracker, final int inputSlot) {
        final BedrockItem item = inventoryTracker.getHudContainer().getItem(inputSlot);
        if (item.isEmpty()) {
            return;
        }

        final BedrockItem remainder = this.craftingRemainder(item);
        final BedrockItem newItem = this.itemAfterRemovingAmount(item, 1);
        inventoryTracker.getHudContainer().setItem(inputSlot, newItem);
        if (!remainder.isEmpty()) {
            if (newItem.isEmpty()) {
                inventoryTracker.getHudContainer().setItem(inputSlot, remainder);
            } else if (!newItem.isDifferent(remainder) && newItem.amount() < this.maxStackSize(newItem)) {
                final BedrockItem mergedItem = newItem.copy();
                mergedItem.setAmount(newItem.amount() + remainder.amount());
                inventoryTracker.getHudContainer().setItem(inputSlot, mergedItem);
            } else {
                this.addToInventory(this, remainder, remainder.amount(), true);
            }
        }
    }

    private BedrockItem craftingRemainder(final BedrockItem item) {
        final ItemRewriter itemRewriter = this.user.get(ItemRewriter.class);
        final String itemName = itemRewriter.getItems().inverse().get(item.identifier());
        if ("minecraft:written_book".equals(itemName) || (itemName != null && itemName.endsWith("_banner") && item.tag() != null && item.tag().contains("Patterns"))) {
            final BedrockItem remainder = item.copy();
            remainder.setAmount(1);
            return remainder;
        }
        final String remainderName = switch (itemName) {
            case "minecraft:water_bucket", "minecraft:lava_bucket", "minecraft:milk_bucket" -> "minecraft:bucket";
            case "minecraft:dragon_breath", "minecraft:honey_bottle" -> "minecraft:glass_bottle";
            default -> null;
        };
        if (remainderName == null) {
            return BedrockItem.empty();
        }

        final Integer remainderId = itemRewriter.getItems().get(remainderName);
        if (remainderId == null) {
            return BedrockItem.empty();
        }
        return new BedrockItem(remainderId);
    }

    public byte getSelectedHotbarSlot() {
        return this.selectedHotbarSlot;
    }

    public BedrockItem getSelectedHotbarItem() {
        return this.getItem(this.selectedHotbarSlot);
    }

    public void sendSelectedHotbarSlotToClient() {
        final PacketWrapper setHeldSlot = PacketWrapper.create(ClientboundPackets26_1.SET_HELD_SLOT, this.user);
        setHeldSlot.write(Types.VAR_INT, (int) this.selectedHotbarSlot);
        setHeldSlot.send(BedrockProtocol.class);
    }

    public void setSelectedHotbarSlot(final byte slot, final PacketWrapper mobEquipment) {
        final BedrockItem oldItem = this.getItem(this.selectedHotbarSlot);
        final BedrockItem newItem = this.getItem(slot);
        this.selectedHotbarSlot = slot;
        this.onSelectedHotbarSlotChanged(oldItem, newItem, mobEquipment);
    }

    @Override
    protected void onSlotChanged(final int slot, final BedrockItem oldItem, final BedrockItem newItem) {
        super.onSlotChanged(slot, oldItem, newItem);
        if (slot == this.selectedHotbarSlot) {
            final PacketWrapper mobEquipment = PacketWrapper.create(ServerboundBedrockPackets.MOB_EQUIPMENT, this.user);
            this.onSelectedHotbarSlotChanged(oldItem, newItem, mobEquipment);
            mobEquipment.sendToServer(BedrockProtocol.class);
        }
    }

    private void onSelectedHotbarSlotChanged(final BedrockItem oldItem, final BedrockItem newItem, final PacketWrapper mobEquipment) {
        if (oldItem.isDifferent(newItem)) {
            final PacketWrapper interact = PacketWrapper.create(ServerboundBedrockPackets.INTERACT, this.user);
            interact.write(Types.UNSIGNED_BYTE, (short) InteractPacket_Action.InteractUpdate.getValue()); // action
            interact.write(BedrockTypes.UNSIGNED_VAR_LONG, 0L); // target entity runtime id
            interact.write(BedrockTypes.OPTIONAL_POSITION_3F, null); // position
            interact.sendToServer(BedrockProtocol.class);
        }

        mobEquipment.write(BedrockTypes.UNSIGNED_VAR_LONG, this.user.get(EntityTracker.class).getClientPlayer().runtimeId()); // entity runtime id
        mobEquipment.write(this.user.get(ItemRewriter.class).itemType(), newItem); // item
        mobEquipment.write(Types.BYTE, this.selectedHotbarSlot); // slot
        mobEquipment.write(Types.BYTE, this.selectedHotbarSlot); // selected slot
        mobEquipment.write(Types.BYTE, this.containerId); // container id
    }

}

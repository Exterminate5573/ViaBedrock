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
package net.raphimc.viabedrock.experimental.model.container.block;

import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.minecraft.BlockPosition;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.api.type.types.version.VersionedTypes;
import com.viaversion.viaversion.libs.mcstructs.text.TextComponent;
import com.viaversion.viaversion.protocols.v1_21_11to26_1.packet.ClientboundPackets26_1;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.experimental.ExperimentalPacketFactory;
import net.raphimc.viabedrock.experimental.model.container.ExperimentalContainer;
import net.raphimc.viabedrock.experimental.model.container.player.InventoryContainer;
import net.raphimc.viabedrock.experimental.model.inventory.ItemStackRequestAction;
import net.raphimc.viabedrock.experimental.model.inventory.ItemStackRequestInfo;
import net.raphimc.viabedrock.experimental.model.inventory.ItemStackRequestSlotInfo;
import net.raphimc.viabedrock.experimental.model.recipe.ItemDescriptor;
import net.raphimc.viabedrock.experimental.model.recipe.ShapelessRecipe;
import net.raphimc.viabedrock.experimental.storage.*;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerEnumName;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerType;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.TextProcessingEventOrigin;
import net.raphimc.viabedrock.protocol.data.enums.java.generated.ContainerInput;
import net.raphimc.viabedrock.protocol.data.generated.bedrock.CustomBlockTags;
import net.raphimc.viabedrock.protocol.model.BedrockItem;
import net.raphimc.viabedrock.protocol.model.FullContainerName;
import net.raphimc.viabedrock.protocol.rewriter.ItemRewriter;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

public class StonecutterContainer extends ExperimentalContainer {

    private final List<CraftingDataStorage> currentRecipes = new ArrayList<>();
    private int selectedRecipe = 0;
    private BedrockItem lastRecipeInput = BedrockItem.empty();

    public StonecutterContainer(UserConnection user, byte containerId, TextComponent title, BlockPosition position) {
        super(user, containerId, ContainerType.STONECUTTER, title, position, 2, "stonecutter_block", "stonecutter");
    }

    @Override
    public FullContainerName getFullContainerName(int slot) {
        return switch (slot) {
            case 3 -> new FullContainerName(ContainerEnumName.StonecutterInputContainer, null);
            case 50 -> new FullContainerName(ContainerEnumName.CreatedOutputContainer, null); //TODO: CreatedOutputContainer?
            default -> throw new IllegalArgumentException("Invalid slot for Stonecutter Container: " + slot);
        };
    }

    @Override
    public int javaSlot(final int slot) {
        return switch (slot) {
            case 3 -> 0;
            case 50 -> 1;
            default -> super.javaSlot(slot);
        };
    }

    @Override
    public int bedrockSlot(final int slot) {
        return switch (slot) {
            case 0 -> 3;
            case 1 -> 50;
            default -> super.bedrockSlot(slot);
        };
    }

    @Override
    public BedrockItem getItem(int bedrockSlot) {
        if (bedrockSlot == 3) {
            return this.items[0];
        } else if (bedrockSlot == 50) {
            return this.items[1];
        } else {
            throw new IllegalArgumentException("Bedrock Slot out of bounds for stonecutter (getItem): " + bedrockSlot);
        }
    }

    @Override
    public boolean setItem(final int bedrockSlot, final BedrockItem item) {
        if (bedrockSlot == 3) {
            return super.setItem(0, item);
        } else if (bedrockSlot == 50) {
            return super.setItem(1, item);
        } else {
            throw new IllegalArgumentException("Bedrock Slot out of bounds for stonecutter (setItem): " + bedrockSlot);
        }
    }

    @Override
    protected boolean canPlaceItem(final int bedrockSlot, final BedrockItem item) {
        return bedrockSlot == 3;
    }

    @Override
    public boolean handleClick(final int revision, final short javaSlot, final byte button, final ContainerInput action) {
        boolean result = false;
        if (javaSlot != 1) {
            // Handle click first so we update the crafting grid before checking for a recipe
            result = super.handleClick(revision, javaSlot, button, action);
        }
        if (!ViaBedrock.getConfig().shouldEnableExperimentalFeatures()) {
            return result;
        }
        //TODO: This is experimental code...

        if (javaSlot == 0 || javaSlot == 1) {
            this.updateRecipeData(this.getItem(3));
            this.updateOutputSlot(revision, this.selectedResultItem());
        } else {
            return result;
        }

        if (this.currentRecipes.isEmpty()) {
            return result;
        }
        if (this.selectedRecipe >= this.currentRecipes.size()) {
            this.selectedRecipe = 0;
        }

        final CraftingDataStorage craftingDataStorage = this.currentRecipes.get(this.selectedRecipe);
        final BedrockItem resultItem = this.selectedResultItem();

        if (javaSlot != 1) {
            return result;
        }
        if (action != ContainerInput.PICKUP && action != ContainerInput.QUICK_MOVE && action != ContainerInput.SWAP) {
            return false;
        }

        ExperimentalInventoryTracker inventoryTracker = user.get(ExperimentalInventoryTracker.class);
        InventoryRequestTracker inventoryRequestTracker = user.get(InventoryRequestTracker.class);
        InventoryContainer inventory = inventoryTracker.getInventoryContainer();

        List<ExperimentalContainer> prevContainers = new ArrayList<>();
        this.addPrevContainer(prevContainers, this);
        this.addPrevContainer(prevContainers, inventory);
        ExperimentalContainer prevCursorContainer = inventoryTracker.getHudContainer().copy();

        int nextRequestId = inventoryRequestTracker.nextRequestId();
        BedrockItem sourceItem = this.getItem(3);
        BedrockItem cursorItem = inventoryTracker.getHudContainer().getItem(0);
        if (action == ContainerInput.PICKUP && !cursorItem.isEmpty() && (cursorItem.isDifferent(resultItem) || cursorItem.amount() + resultItem.amount() > this.maxStackSize(resultItem))) {
            return false;
        }
        final SwapDestination swapDestination = action == ContainerInput.SWAP ? this.resultSwapDestination(button, resultItem) : null;
        if (action == ContainerInput.SWAP && swapDestination == null) {
            return false;
        }
        if (swapDestination != null) {
            this.addPrevContainer(prevContainers, swapDestination.container());
        }

        final int inputCost = this.inputCost(craftingDataStorage);
        final int maxCraftsByInput = sourceItem.amount() / inputCost;
        int craftableAmount = action == ContainerInput.QUICK_MOVE ? Math.min(maxCraftsByInput, this.craftsThatFit(inventory, resultItem, maxCraftsByInput)) : 1;
        if (craftableAmount <= 0) {
            return false;
        }
        int toConsume = inputCost * craftableAmount;

        List<ItemStackRequestAction> actions = new ArrayList<>();
        actions.add(new ItemStackRequestAction.CraftRecipeAction(craftingDataStorage.networkId(), craftableAmount));
        actions.add(new ItemStackRequestAction.CraftResultsDeprecatedAction(List.of(resultItem), craftableAmount));
        actions.add(new ItemStackRequestAction.ConsumeAction(
                toConsume,
                new ItemStackRequestSlotInfo(
                        this.getFullContainerName(3),
                        (byte) 3,
                        sourceItem.netId() != null ? sourceItem.netId() : 0
                )
        ));
        if (action == ContainerInput.PICKUP) {
            actions.add(new ItemStackRequestAction.TakeAction(
                    resultItem.amount(),
                    new ItemStackRequestSlotInfo(new FullContainerName(ContainerEnumName.CreatedOutputContainer, null), (byte) 50, nextRequestId),
                    new ItemStackRequestSlotInfo(new FullContainerName(ContainerEnumName.CursorContainer, null), (byte) 0, cursorItem.netId() != null ? cursorItem.netId() : 0)
            ));
        } else if (action == ContainerInput.QUICK_MOVE) {
            this.addInventoryTransferActions(actions, inventory, resultItem, craftableAmount * resultItem.amount(), nextRequestId);
        } else {
            actions.add(this.takeCreatedOutputAction(resultItem, nextRequestId, swapDestination));
        }

        ItemStackRequestInfo request = new ItemStackRequestInfo(
                nextRequestId,
                actions,
                List.of(),
                TextProcessingEventOrigin.unknown
        );

        inventoryRequestTracker.addRequest(new InventoryRequestStorage(request, revision, prevCursorContainer, prevContainers)); // Store the request to track it later
        ExperimentalPacketFactory.sendBedrockInventoryRequest(user, new ItemStackRequestInfo[]{request});

        if (action == ContainerInput.PICKUP) {
            if (cursorItem.isEmpty()) {
                inventoryTracker.getHudContainer().setItem(0, resultItem.copy());
            } else {
                BedrockItem newCursorItem = cursorItem.copy();
                newCursorItem.setAmount(cursorItem.amount() + resultItem.amount());
                inventoryTracker.getHudContainer().setItem(0, newCursorItem);
            }
        } else if (action == ContainerInput.QUICK_MOVE) {
            this.addToInventory(inventory, resultItem, craftableAmount * resultItem.amount(), true);
            ExperimentalPacketFactory.sendJavaContainerSetContent(user, inventory);
        } else {
            swapDestination.container().setItem(swapDestination.bedrockSlot(), resultItem.copy());
            ExperimentalPacketFactory.sendJavaContainerSetContent(user, swapDestination.container());
        }
        this.setItem(3, this.itemAfterRemovingAmount(sourceItem, toConsume));

        this.updateRecipeData(this.getItem(3));
        this.updateOutputSlot(revision, this.selectedResultItem());
        ExperimentalPacketFactory.sendJavaContainerSetContent(user, this);
        return true;
    }

    @Override
    public boolean handleButtonClick(final int button) {
        this.updateRecipeData(this.getItem(3));
        if (button >= this.currentRecipes.size()) {
            ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Invalid recipe button clicked in stonecutter: " + button);
            return false;
        }

        this.selectedRecipe = button;
        this.updateOutputSlot(0, this.selectedResultItem());

        return true;
    }

    // TODO: Refactor to CraftingDataTracker
    private void updateRecipeData(BedrockItem item) {
        CraftingDataTracker craftingDataTracker = user.get(CraftingDataTracker.class);
        if (this.lastRecipeInput.isDifferent(item)) {
            this.selectedRecipe = 0;
            this.lastRecipeInput = item.copy();
        }
        this.currentRecipes.clear();

        for (CraftingDataStorage craftingData : craftingDataTracker.getCraftingDataList()) {
            if (craftingData.recipe() == null || !craftingData.recipe().getRecipeTag().equals("stonecutter")) {
                continue;
            }

            switch (craftingData.type()) {
                case SHAPELESS -> {
                    ShapelessRecipe recipe = (ShapelessRecipe) craftingData.recipe();
                    if (recipe.getIngredients().isEmpty()) {
                        continue;
                    }

                    final ItemDescriptor ingredient = recipe.getIngredients().get(0);
                    if (ingredient.matchesItem(this.user, item) && item.amount() >= ingredient.amount()) {
                        this.currentRecipes.add(craftingData);
                    }
                }
                default -> ViaBedrock.getPlatform().getLogger().warning(
                        "Unknown recipe type for stonecutter: " + craftingData.type() + " in recipe " + craftingData.recipe().getUniqueId()
                );
            }
        }

        if (this.selectedRecipe >= this.currentRecipes.size()) {
            this.selectedRecipe = 0;
        }
        //TODO: update buttons
    }

    private BedrockItem selectedResultItem() {
        if (this.currentRecipes.isEmpty()) {
            return BedrockItem.empty();
        }
        if (this.selectedRecipe >= this.currentRecipes.size()) {
            this.selectedRecipe = 0;
        }

        final CraftingDataStorage craftingDataStorage = this.currentRecipes.get(this.selectedRecipe);
        return switch (craftingDataStorage.type()) {
            case SHAPELESS -> ((ShapelessRecipe) craftingDataStorage.recipe()).getResults().get(0);
            default -> {
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Unknown recipe type for stonecutter: " + craftingDataStorage.type());
                yield BedrockItem.empty();
            }
        };
    }

    private int inputCost(final CraftingDataStorage craftingDataStorage) {
        if (craftingDataStorage.recipe() instanceof ShapelessRecipe recipe && !recipe.getIngredients().isEmpty()) {
            return Math.max(1, recipe.getIngredients().get(0).amount());
        }
        return 1;
    }

    private void updateOutputSlot(final int revision, final BedrockItem resultItem) {
        this.setItem(50, resultItem);

        ItemRewriter itemRewriter = user.get(ItemRewriter.class);
        PacketWrapper containerSlot = PacketWrapper.create(ClientboundPackets26_1.CONTAINER_SET_SLOT, user);
        containerSlot.write(Types.VAR_INT, (int) this.containerId());
        containerSlot.write(Types.VAR_INT, revision);
        containerSlot.write(Types.SHORT, (short) 1); // Output slot
        containerSlot.write(VersionedTypes.V26_1.item, itemRewriter.javaItem(resultItem));
        containerSlot.send(BedrockProtocol.class);
    }

    private void addInventoryTransferActions(final List<ItemStackRequestAction> actions, final InventoryContainer inventory, final BedrockItem resultItem, final int totalAmount, final int requestId) {
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
                actions.add(new ItemStackRequestAction.TakeAction(
                        amountToMove,
                        new ItemStackRequestSlotInfo(new FullContainerName(ContainerEnumName.CreatedOutputContainer, null), (byte) 50, requestId),
                        new ItemStackRequestSlotInfo(inventory.getFullContainerName(slot), (byte) slot, destinationItem.netId() != null ? destinationItem.netId() : 0)
                ));
                remaining -= amountToMove;
            }
        }
    }

    private int craftsThatFit(final InventoryContainer inventory, final BedrockItem resultItem, final int maxCrafts) {
        int remainingResultItems = maxCrafts * resultItem.amount();
        for (boolean mergePass : new boolean[]{true, false}) {
            for (int slot = inventory.size() - 1; slot >= 0 && remainingResultItems > 0; slot--) {
                final BedrockItem destinationItem = inventory.getItem(slot);
                if (mergePass) {
                    if (destinationItem.isEmpty() || destinationItem.isDifferent(resultItem) || destinationItem.amount() >= this.maxStackSize(resultItem)) {
                        continue;
                    }
                    remainingResultItems -= this.maxStackSize(resultItem) - destinationItem.amount();
                } else if (destinationItem.isEmpty()) {
                    remainingResultItems -= this.maxStackSize(resultItem);
                }
            }
        }

        final int fittingResultItems = maxCrafts * resultItem.amount() - Math.max(remainingResultItems, 0);
        return fittingResultItems / resultItem.amount();
    }

}

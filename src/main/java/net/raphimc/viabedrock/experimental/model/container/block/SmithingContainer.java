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
import net.raphimc.viabedrock.experimental.model.recipe.SmithingRecipe;
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

public class SmithingContainer extends ExperimentalContainer {

    public static final int RESULT_SLOT = 50;
    public static final int INPUT_SLOT = 51;
    public static final int MATERIAL_SLOT = 52;
    public static final int TEMPLATE_SLOT = 53;

    public SmithingContainer(UserConnection user, byte containerId, TextComponent title, BlockPosition position) {
        super(user, containerId, ContainerType.SMITHING_TABLE, title, position, 4, CustomBlockTags.SMITHING_TABLE);
    }

    @Override
    public FullContainerName getFullContainerName(int slot) {
        return switch (slot) {
            case TEMPLATE_SLOT -> new FullContainerName(ContainerEnumName.SmithingTableTemplateContainer, null);
            case INPUT_SLOT -> new FullContainerName(ContainerEnumName.SmithingTableInputContainer, null);
            case MATERIAL_SLOT -> new FullContainerName(ContainerEnumName.SmithingTableMaterialContainer, null);
            case RESULT_SLOT -> new FullContainerName(ContainerEnumName.SmithingTableResultPreviewContainer, null); //TODO: CreatedOutputContainer?
            default -> throw new IllegalArgumentException("Invalid slot for Smithing Container: " + slot);
        };
    }

    @Override
    public int javaSlot(final int slot) {
        return switch (slot) {
            case TEMPLATE_SLOT -> 0;
            case INPUT_SLOT -> 1;
            case MATERIAL_SLOT -> 2;
            case RESULT_SLOT -> 3;
            default -> super.javaSlot(slot);
        };
    }

    @Override
    public int bedrockSlot(final int slot) {
        return switch (slot) {
            case 0 -> TEMPLATE_SLOT;
            case 1 -> INPUT_SLOT;
            case 2 -> MATERIAL_SLOT;
            case 3 -> RESULT_SLOT;
            default -> super.bedrockSlot(slot);
        };
    }

    @Override
    public BedrockItem getItem(int bedrockSlot) {
        return switch (bedrockSlot) {
            case TEMPLATE_SLOT -> this.items[0];
            case INPUT_SLOT -> this.items[1];
            case MATERIAL_SLOT -> this.items[2];
            case RESULT_SLOT -> this.items[3];
            default -> throw new IllegalArgumentException("Invalid slot for Smithing Container: " + bedrockSlot);
        };
    }

    @Override
    public boolean setItem(final int bedrockSlot, final BedrockItem item) {
        return switch (bedrockSlot) {
            case TEMPLATE_SLOT -> super.setItem(0, item);
            case INPUT_SLOT -> super.setItem(1, item);
            case MATERIAL_SLOT -> super.setItem(2, item);
            case RESULT_SLOT -> super.setItem(3, item);
            default -> throw new IllegalArgumentException("Invalid slot for Smithing Container: " + bedrockSlot);
        };
    }

    @Override
    public boolean setItems(final BedrockItem[] items) {
        if (items.length != this.items.length) {
            ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Tried to set items for " + this.type + ", but items array length was not correct (" + items.length + " != " + this.items.length + ")");
            return false;
        }

        return super.setItems(items);
    }

    @Override
    public boolean handleClick(int revision, short javaSlot, byte button, ContainerInput action) {
        boolean result = false;
        if (javaSlot != 3) {
            result = super.handleClick(revision, javaSlot, button, action);
        }
        if (!ViaBedrock.getConfig().shouldEnableExperimentalFeatures()) {
            return result;
        }

        final CraftingDataStorage craftingDataStorage = this.recipeData();
        final BedrockItem resultItem = this.resultItem(craftingDataStorage);
        this.updateOutputSlot(revision, resultItem);

        if (javaSlot != 3) {
            return result;
        }
        if (craftingDataStorage == null || resultItem.isEmpty() || (action != ContainerInput.PICKUP && action != ContainerInput.QUICK_MOVE)) {
            return false;
        }

        final ExperimentalInventoryTracker inventoryTracker = user.get(ExperimentalInventoryTracker.class);
        final InventoryContainer inventory = inventoryTracker.getInventoryContainer();
        final BedrockItem cursorItem = inventoryTracker.getHudContainer().getItem(0);
        if (action == ContainerInput.PICKUP && !cursorItem.isEmpty() && (cursorItem.isDifferent(resultItem) || cursorItem.amount() + resultItem.amount() > this.maxStackSize(resultItem))) {
            return false;
        }
        if (action == ContainerInput.QUICK_MOVE && this.inventoryCapacity(inventory, resultItem) < resultItem.amount()) {
            return false;
        }

        final InventoryRequestTracker inventoryRequestTracker = user.get(InventoryRequestTracker.class);
        final int requestId = inventoryRequestTracker.nextRequestId();
        final List<ItemStackRequestAction> actions = this.craftActions(craftingDataStorage, resultItem, requestId);
        if (action == ContainerInput.PICKUP) {
            actions.add(new ItemStackRequestAction.TakeAction(
                    resultItem.amount(),
                    new ItemStackRequestSlotInfo(new FullContainerName(ContainerEnumName.CreatedOutputContainer, null), (byte) 50, requestId),
                    new ItemStackRequestSlotInfo(new FullContainerName(ContainerEnumName.CursorContainer, null), (byte) 0, cursorItem.netId() != null ? cursorItem.netId() : 0)
            ));
        } else {
            this.addOutputToInventoryActions(actions, inventory, resultItem, resultItem.amount(), requestId);
        }

        final ItemStackRequestInfo request = new ItemStackRequestInfo(requestId, actions, List.of(), TextProcessingEventOrigin.unknown);
        final List<ExperimentalContainer> prevContainers = new ArrayList<>();
        prevContainers.add(this.copy());
        prevContainers.add(inventory.copy());
        inventoryRequestTracker.addRequest(new InventoryRequestStorage(request, revision, inventoryTracker.getHudContainer().copy(), prevContainers));
        ExperimentalPacketFactory.sendBedrockInventoryRequest(user, new ItemStackRequestInfo[]{request});

        if (action == ContainerInput.PICKUP) {
            if (cursorItem.isEmpty()) {
                inventoryTracker.getHudContainer().setItem(0, resultItem.copy());
            } else {
                final BedrockItem newCursorItem = cursorItem.copy();
                newCursorItem.setAmount(cursorItem.amount() + resultItem.amount());
                inventoryTracker.getHudContainer().setItem(0, newCursorItem);
            }
        } else {
            this.addToInventory(inventory, resultItem, resultItem.amount(), true);
            ExperimentalPacketFactory.sendJavaContainerSetContent(user, inventory);
        }
        this.consumeInputs();
        this.updateOutputSlot(revision, BedrockItem.empty());
        ExperimentalPacketFactory.sendJavaContainerSetContent(user, this);
        return true;
    }

    private CraftingDataStorage recipeData() {
        return user.get(CraftingDataTracker.class).getRecipeData(this, "smithing_table");
    }

    private BedrockItem resultItem(final CraftingDataStorage craftingDataStorage) {
        if (craftingDataStorage == null) {
            return BedrockItem.empty();
        }

        final BedrockItem outputItem = this.getItem(50);
        if (!outputItem.isEmpty()) {
            return outputItem;
        }

        final BedrockItem recipeResult = ((SmithingRecipe) craftingDataStorage.recipe()).getResult();
        if (!recipeResult.isEmpty()) {
            return recipeResult;
        }
        return BedrockItem.empty();
    }

    private void updateOutputSlot(final int revision, final BedrockItem resultItem) {
        this.setItem(50, resultItem);
        final PacketWrapper containerSlot = PacketWrapper.create(ClientboundPackets26_1.CONTAINER_SET_SLOT, user);
        containerSlot.write(Types.VAR_INT, (int) this.containerId());
        containerSlot.write(Types.VAR_INT, revision);
        containerSlot.write(Types.SHORT, (short) 3);
        containerSlot.write(VersionedTypes.V26_1.item, user.get(ItemRewriter.class).javaItem(resultItem));
        containerSlot.send(BedrockProtocol.class);
    }

    private List<ItemStackRequestAction> craftActions(final CraftingDataStorage craftingDataStorage, final BedrockItem resultItem, final int requestId) {
        final List<ItemStackRequestAction> actions = new ArrayList<>();
        actions.add(new ItemStackRequestAction.CraftRecipeAction(craftingDataStorage.networkId(), 1));
        actions.add(new ItemStackRequestAction.CraftResultsDeprecatedAction(List.of(resultItem), 1));
        for (int slot : new int[]{TEMPLATE_SLOT, INPUT_SLOT, MATERIAL_SLOT}) {
            final BedrockItem item = this.getItem(slot);
            if (!item.isEmpty()) {
                actions.add(new ItemStackRequestAction.ConsumeAction(
                        1,
                        new ItemStackRequestSlotInfo(this.getFullContainerName(slot), (byte) slot, item.netId() != null ? item.netId() : 0)
                ));
            }
        }
        return actions;
    }

    private void consumeInputs() {
        this.setItem(TEMPLATE_SLOT, this.itemAfterRemovingAmount(this.getItem(TEMPLATE_SLOT), 1));
        this.setItem(INPUT_SLOT, this.itemAfterRemovingAmount(this.getItem(INPUT_SLOT), 1));
        this.setItem(MATERIAL_SLOT, this.itemAfterRemovingAmount(this.getItem(MATERIAL_SLOT), 1));
    }

    private void addOutputToInventoryActions(final List<ItemStackRequestAction> actions, final InventoryContainer inventory, final BedrockItem resultItem, final int totalAmount, final int requestId) {
        int remaining = totalAmount;
        for (boolean mergePass : new boolean[]{true, false}) {
            for (int slot = inventory.size() - 1; slot >= 0 && remaining > 0; slot--) {
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

    private int inventoryCapacity(final InventoryContainer inventory, final BedrockItem resultItem) {
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

}

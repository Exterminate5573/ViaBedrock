/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2025 RK_01/RaphiMC and contributors
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
import net.raphimc.viabedrock.experimental.model.inventory.ItemStackRequestAction;
import net.raphimc.viabedrock.experimental.model.inventory.ItemStackRequestInfo;
import net.raphimc.viabedrock.experimental.model.inventory.ItemStackRequestSlotInfo;
import net.raphimc.viabedrock.experimental.model.recipe.ShapedRecipe;
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

public class CraftingTableContainer extends ExperimentalContainer {

    public CraftingTableContainer(UserConnection user, byte containerId, TextComponent title, BlockPosition position) {
        super(user, containerId, ContainerType.WORKBENCH, title, position, 10, CustomBlockTags.WORKBENCH);
    }

    @Override
    public FullContainerName getFullContainerName(int slot) {
        return switch (slot) {
            case 32, 33, 34, 35, 36, 37, 38, 39, 40 ->
                    new FullContainerName(ContainerEnumName.CraftingInputContainer, null);
            case 50 -> new FullContainerName(ContainerEnumName.CreatedOutputContainer, null);
            default -> throw new IllegalArgumentException("Invalid slot for Crafting Container: " + slot);
        };
    }

    @Override
    public int javaSlot(final int bedrockSlot) {
        return switch (bedrockSlot) {
            case 32, 33, 34, 35, 36, 37, 38, 39, 40 -> bedrockSlot - 31;
            case 50 -> 0;
            default -> super.javaSlot(bedrockSlot);
        };
    }

    @Override
    public int bedrockSlot(final int javaSlot) {
        return switch (javaSlot) {
            case 1, 2, 3, 4, 5, 6, 7, 8, 9 -> javaSlot + 31;
            case 0 -> 50;
            default -> super.bedrockSlot(javaSlot);
        };
    }

    @Override
    public BedrockItem getItem(int bedrockSlot) {
        return switch (bedrockSlot) {
            case 50 -> this.items[0];
            case 32, 33, 34, 35, 36, 37, 38, 39, 40 -> this.items[bedrockSlot - 31];
            default -> throw new IllegalArgumentException("Invalid slot for Crafting Container: " + bedrockSlot);
        };
    }

    @Override
    public boolean setItem(final int bedrockSlot, final BedrockItem item) {
        return switch (bedrockSlot) {
            case 50 -> super.setItem(0, item);
            case 32, 33, 34, 35, 36, 37, 38, 39, 40 -> super.setItem(bedrockSlot - 31, item);
            default -> throw new IllegalArgumentException("Invalid slot for Crafting Container: " + bedrockSlot);
        };
    }

    @Override
    protected boolean canPlaceItem(final int bedrockSlot, final BedrockItem item) {
        return bedrockSlot != 50;
    }

    @Override
    public boolean handleClick(final int revision, final short javaSlot, final byte button, final ContainerInput action) {
        if (!ViaBedrock.getConfig().shouldEnableExperimentalFeatures()) {
            return super.handleClick(revision, javaSlot, button, action);
        }

        if (javaSlot == 0) {
            final CraftingDataTracker.RecipeMatch recipeMatch = this.recipeMatch();
            final BedrockItem resultItem = this.resultItem(recipeMatch);
            this.updateOutputSlot(revision, resultItem);
            if (recipeMatch == null || resultItem.isEmpty()) {
                return false;
            }

            return switch (action) {
                case PICKUP -> this.craftToCursor(revision, resultItem, recipeMatch);
                case QUICK_MOVE -> this.craftToInventory(revision, resultItem, recipeMatch);
                case SWAP -> this.craftToSwapDestination(revision, resultItem, recipeMatch, button);
                default -> false;
            };
        }

        final boolean result = super.handleClick(revision, javaSlot, button, action);
        this.updateOutputSlot(revision, this.resultItem(this.recipeMatch()));
        return result;
    }

    private CraftingDataTracker.RecipeMatch recipeMatch() {
        final CraftingDataTracker tracker = user.get(CraftingDataTracker.class);
        return tracker.getRecipeMatch(this, "crafting_table");
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
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Unknown recipe type for crafting: " + craftingDataStorage.type());
                yield BedrockItem.empty();
            }
        };
    }

    private void updateOutputSlot(final int revision, final BedrockItem resultItem) {
        this.setItem(50, resultItem);
        final ItemRewriter itemRewriter = user.get(ItemRewriter.class);

        final PacketWrapper containerSlot = PacketWrapper.create(ClientboundPackets26_1.CONTAINER_SET_SLOT, user);
        containerSlot.write(Types.VAR_INT, (int) this.containerId());
        containerSlot.write(Types.VAR_INT, revision);
        containerSlot.write(Types.SHORT, (short) 0); // Output slot
        containerSlot.write(VersionedTypes.V26_1.item, itemRewriter.javaItem(resultItem));
        containerSlot.send(BedrockProtocol.class);
    }

    private boolean craftToCursor(final int revision, final BedrockItem resultItem, final CraftingDataTracker.RecipeMatch recipeMatch) {
        final ExperimentalInventoryTracker inventoryTracker = user.get(ExperimentalInventoryTracker.class);
        final BedrockItem cursorItem = inventoryTracker.getHudContainer().getItem(0);
        if (!cursorItem.isEmpty() && (cursorItem.isDifferent(resultItem) || cursorItem.amount() + resultItem.amount() > this.maxStackSize(resultItem))) {
            return false;
        }

        final InventoryRequestTracker inventoryRequestTracker = user.get(InventoryRequestTracker.class);
        final int requestId = inventoryRequestTracker.nextRequestId();
        final List<ItemStackRequestAction> actions = this.craftActions(recipeMatch, resultItem, 1, requestId);
        if (!this.remaindersFit(recipeMatch.ingredients(), inventoryTracker, 1)) {
            return false;
        }
        actions.add(new ItemStackRequestAction.TakeAction(
                resultItem.amount(),
                ItemStackRequestSlotInfo.createdOutput(requestId),
                ItemStackRequestSlotInfo.cursor(cursorItem.netId() != null ? cursorItem.netId() : 0)
        ));

        final List<ExperimentalContainer> prevContainers = new ArrayList<>();
        prevContainers.add(this.copy());
        prevContainers.add(inventoryTracker.getInventoryContainer().copy());
        final ExperimentalContainer prevCursorContainer = inventoryTracker.getHudContainer().copy();

        final ItemStackRequestInfo request = new ItemStackRequestInfo(requestId, actions, List.of(), TextProcessingEventOrigin.unknown);
        inventoryRequestTracker.addRequest(new InventoryRequestStorage(request, revision, prevCursorContainer, prevContainers));
        ExperimentalPacketFactory.sendBedrockInventoryRequest(user, new ItemStackRequestInfo[]{request});

        if (cursorItem.isEmpty()) {
            inventoryTracker.getHudContainer().setItem(0, resultItem.copy());
        } else {
            final BedrockItem newCursorItem = cursorItem.copy();
            newCursorItem.setAmount(cursorItem.amount() + resultItem.amount());
            inventoryTracker.getHudContainer().setItem(0, newCursorItem);
        }
        this.consumeIngredients(recipeMatch.ingredients(), inventoryTracker, 1);
        this.updateOutputSlot(revision, this.resultItem(this.recipeMatch()));
        ExperimentalPacketFactory.sendJavaContainerSetContent(user, this);
        return true;
    }

    private boolean craftToInventory(final int revision, final BedrockItem resultItem, final CraftingDataTracker.RecipeMatch recipeMatch) {
        final int maxCrafts = this.maxCrafts(recipeMatch.ingredients());
        if (maxCrafts <= 0) {
            return false;
        }

        final ExperimentalInventoryTracker inventoryTracker = user.get(ExperimentalInventoryTracker.class);
        final int craftLimit = this.hasCraftingRemainders(recipeMatch.ingredients()) ? 1 : maxCrafts;
        final int crafts = this.craftsThatFit(inventoryTracker.getInventoryContainer(), resultItem, recipeMatch.ingredients(), craftLimit);
        if (crafts <= 0) {
            return false;
        }

        final InventoryRequestTracker inventoryRequestTracker = user.get(InventoryRequestTracker.class);
        final int requestId = inventoryRequestTracker.nextRequestId();
        final List<ItemStackRequestAction> actions = this.craftActions(recipeMatch, resultItem, crafts, requestId);
        this.addInventoryTransferActions(actions, inventoryTracker.getInventoryContainer(), resultItem, crafts * resultItem.amount(), requestId);

        final List<ExperimentalContainer> prevContainers = new ArrayList<>();
        prevContainers.add(this.copy());
        prevContainers.add(inventoryTracker.getInventoryContainer().copy());
        final ExperimentalContainer prevCursorContainer = inventoryTracker.getHudContainer().copy();

        final ItemStackRequestInfo request = new ItemStackRequestInfo(requestId, actions, List.of(), TextProcessingEventOrigin.unknown);
        inventoryRequestTracker.addRequest(new InventoryRequestStorage(request, revision, prevCursorContainer, prevContainers));
        ExperimentalPacketFactory.sendBedrockInventoryRequest(user, new ItemStackRequestInfo[]{request});

        this.addToInventory(inventoryTracker.getInventoryContainer(), resultItem, crafts * resultItem.amount(), true);
        this.consumeIngredients(recipeMatch.ingredients(), inventoryTracker, crafts);
        this.updateOutputSlot(revision, this.resultItem(this.recipeMatch()));
        ExperimentalPacketFactory.sendJavaContainerSetContent(user, inventoryTracker.getInventoryContainer());
        ExperimentalPacketFactory.sendJavaContainerSetContent(user, this);
        return true;
    }

    private boolean craftToSwapDestination(final int revision, final BedrockItem resultItem, final CraftingDataTracker.RecipeMatch recipeMatch, final byte button) {
        final ExperimentalInventoryTracker inventoryTracker = user.get(ExperimentalInventoryTracker.class);
        final SwapDestination swapDestination = this.resultSwapDestination(button, resultItem);
        if (swapDestination == null) {
            return false;
        }

        final ExperimentalContainer inventoryCopy = inventoryTracker.getInventoryContainer().copy();
        if (swapDestination.container() == inventoryTracker.getInventoryContainer()) {
            inventoryCopy.setItem(swapDestination.bedrockSlot(), resultItem.copy());
        }
        if (!this.remaindersFit(recipeMatch.ingredients(), inventoryCopy, 1)) {
            return false;
        }

        final InventoryRequestTracker inventoryRequestTracker = user.get(InventoryRequestTracker.class);
        final int requestId = inventoryRequestTracker.nextRequestId();
        final List<ItemStackRequestAction> actions = this.craftActions(recipeMatch, resultItem, 1, requestId);
        actions.add(this.takeCreatedOutputAction(resultItem, requestId, swapDestination));

        final List<ExperimentalContainer> prevContainers = new ArrayList<>();
        this.addPrevContainer(prevContainers, this);
        this.addPrevContainer(prevContainers, inventoryTracker.getInventoryContainer());
        this.addPrevContainer(prevContainers, swapDestination.container());
        final ExperimentalContainer prevCursorContainer = inventoryTracker.getHudContainer().copy();

        final ItemStackRequestInfo request = new ItemStackRequestInfo(requestId, actions, List.of(), TextProcessingEventOrigin.unknown);
        inventoryRequestTracker.addRequest(new InventoryRequestStorage(request, revision, prevCursorContainer, prevContainers));
        ExperimentalPacketFactory.sendBedrockInventoryRequest(user, new ItemStackRequestInfo[]{request});

        swapDestination.container().setItem(swapDestination.bedrockSlot(), resultItem.copy());
        this.consumeIngredients(recipeMatch.ingredients(), inventoryTracker, 1);
        this.updateOutputSlot(revision, this.resultItem(this.recipeMatch()));
        ExperimentalPacketFactory.sendJavaContainerSetContent(user, inventoryTracker.getInventoryContainer());
        if (swapDestination.container() != inventoryTracker.getInventoryContainer()) {
            ExperimentalPacketFactory.sendJavaContainerSetContent(user, swapDestination.container());
        }
        ExperimentalPacketFactory.sendJavaContainerSetContent(user, this);
        return true;
    }

    private List<ItemStackRequestAction> craftActions(final CraftingDataTracker.RecipeMatch recipeMatch, final BedrockItem resultItem, final int crafts, final int requestId) {
        final List<ItemStackRequestAction> actions = new ArrayList<>();
        actions.add(new ItemStackRequestAction.CraftRecipeAction(recipeMatch.craftingData().networkId(), crafts));
        actions.add(new ItemStackRequestAction.CraftResultsDeprecatedAction(List.of(resultItem), crafts));
        for (CraftingDataTracker.IngredientUse ingredient : recipeMatch.ingredients()) {
            final int inputSlot = ingredient.bedrockSlot();
            final BedrockItem item = this.getItem(inputSlot);
            if (!item.isEmpty()) {
                actions.add(new ItemStackRequestAction.ConsumeAction(
                        ingredient.count() * crafts,
                        this.stackRequestSlotInfo(inputSlot, item.netId() != null ? item.netId() : 0)
                ));
            }
        }
        for (CraftingDataTracker.IngredientUse ingredient : recipeMatch.ingredients()) {
            final int inputSlot = ingredient.bedrockSlot();
            if (!this.craftingRemainder(this.getItem(inputSlot)).isEmpty()) {
                actions.add(new ItemStackRequestAction.CreateAction(inputSlot));
            }
        }
        return actions;
    }

    private void addInventoryTransferActions(final List<ItemStackRequestAction> actions, final ExperimentalContainer inventory, final BedrockItem resultItem, final int totalAmount, final int requestId) {
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
                        ItemStackRequestSlotInfo.createdOutput(requestId),
                        inventory.stackRequestSlotInfo(slot, destinationItem.netId() != null ? destinationItem.netId() : 0)
                ));
                remaining -= amountToMove;
            }
        }
    }

    private int craftsThatFit(final ExperimentalContainer inventory, final BedrockItem resultItem, final List<CraftingDataTracker.IngredientUse> ingredients, final int maxCrafts) {
        for (int crafts = maxCrafts; crafts > 0; crafts--) {
            final ExperimentalContainer inventoryCopy = inventory.copy();
            final int totalResultAmount = crafts * resultItem.amount();
            if (this.addToInventory(inventoryCopy, resultItem, totalResultAmount, true) != totalResultAmount) {
                continue;
            }
            if (this.remaindersFit(ingredients, inventoryCopy, crafts)) {
                return crafts;
            }
        }
        return 0;
    }

    private int maxCrafts(final List<CraftingDataTracker.IngredientUse> ingredients) {
        int maxCrafts = Integer.MAX_VALUE;
        for (CraftingDataTracker.IngredientUse ingredient : ingredients) {
            final BedrockItem item = this.getItem(ingredient.bedrockSlot());
            if (!item.isEmpty() && ingredient.count() > 0) {
                maxCrafts = Math.min(maxCrafts, item.amount() / ingredient.count());
            }
        }
        return maxCrafts == Integer.MAX_VALUE ? 0 : maxCrafts;
    }

    private boolean hasCraftingRemainders(final List<CraftingDataTracker.IngredientUse> ingredients) {
        for (CraftingDataTracker.IngredientUse ingredient : ingredients) {
            if (!this.craftingRemainder(this.getItem(ingredient.bedrockSlot())).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private boolean remaindersFit(final List<CraftingDataTracker.IngredientUse> ingredients, final ExperimentalInventoryTracker inventoryTracker, final int crafts) {
        return this.remaindersFit(ingredients, inventoryTracker.getInventoryContainer().copy(), crafts);
    }

    private boolean remaindersFit(final List<CraftingDataTracker.IngredientUse> ingredients, final ExperimentalContainer inventoryCopy, final int crafts) {
        for (int craft = 0; craft < crafts; craft++) {
            for (CraftingDataTracker.IngredientUse ingredient : ingredients) {
                for (int i = 0; i < ingredient.count(); i++) {
                    final BedrockItem item = this.getItem(ingredient.bedrockSlot());
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

    private void consumeIngredients(final List<CraftingDataTracker.IngredientUse> ingredients, final ExperimentalInventoryTracker inventoryTracker, final int crafts) {
        for (int craft = 0; craft < crafts; craft++) {
            for (CraftingDataTracker.IngredientUse ingredient : ingredients) {
                for (int i = 0; i < ingredient.count(); i++) {
                    this.consumeIngredient(ingredient.bedrockSlot(), inventoryTracker);
                }
            }
        }
    }

    private void consumeIngredient(final int inputSlot, final ExperimentalInventoryTracker inventoryTracker) {
        final BedrockItem item = this.getItem(inputSlot);
        if (item.isEmpty()) {
            return;
        }

        final BedrockItem remainder = this.craftingRemainder(item);
        final BedrockItem newItem = this.itemAfterRemovingAmount(item, 1);
        this.setItem(inputSlot, newItem);
        if (!remainder.isEmpty()) {
            if (newItem.isEmpty()) {
                this.setItem(inputSlot, remainder);
            } else if (!newItem.isDifferent(remainder) && newItem.amount() < this.maxStackSize(newItem)) {
                final BedrockItem mergedItem = newItem.copy();
                mergedItem.setAmount(newItem.amount() + remainder.amount());
                this.setItem(inputSlot, mergedItem);
            } else {
                this.addToInventory(inventoryTracker.getInventoryContainer(), remainder, remainder.amount(), true);
            }
        }
    }

    private BedrockItem craftingRemainder(final BedrockItem item) {
        final ItemRewriter itemRewriter = user.get(ItemRewriter.class);
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
}

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

import com.viaversion.nbt.tag.CompoundTag;
import com.viaversion.nbt.tag.NumberTag;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.minecraft.BlockPosition;
import com.viaversion.viaversion.libs.mcstructs.text.TextComponent;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.experimental.ExperimentalPacketFactory;
import net.raphimc.viabedrock.experimental.model.container.ExperimentalContainer;
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
import net.raphimc.viabedrock.protocol.data.generated.bedrock.CustomBlockTags;
import net.raphimc.viabedrock.protocol.model.BedrockItem;
import net.raphimc.viabedrock.protocol.model.FullContainerName;
import net.raphimc.viabedrock.protocol.rewriter.ItemRewriter;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

public class AnvilContainer extends ExperimentalContainer {

    private String renameText = "";

    public AnvilContainer(UserConnection user, byte containerId, TextComponent title, BlockPosition position) {
        super(user, containerId, ContainerType.ANVIL, title, position, 3, CustomBlockTags.ANVIL);
    }

    @Override
    public FullContainerName getFullContainerName(int slot) {
        return switch (slot) {
            case 1 -> new FullContainerName(ContainerEnumName.AnvilInputContainer, null);
            case 2 -> new FullContainerName(ContainerEnumName.AnvilMaterialContainer, null);
            case 50 -> new FullContainerName(ContainerEnumName.CreatedOutputContainer, null);
            default -> throw new IllegalArgumentException("Invalid slot for Anvil Container: " + slot);
        };
    }

    @Override
    public int javaSlot(final int slot) {
        return switch (slot) {
            case 1 -> 0;
            case 2 -> 1;
            case 50 -> 2;
            default -> super.javaSlot(slot);
        };
    }

    @Override
    public int bedrockSlot(final int slot) {
        return switch (slot) {
            case 0 -> 1;
            case 1 -> 2;
            case 2 -> 50;
            default -> super.bedrockSlot(slot);
        };
    }

    @Override
    public BedrockItem getItem(int bedrockSlot) {
        return switch (bedrockSlot) {
            case 1 -> this.items[0];
            case 2 -> this.items[1];
            case 50 -> this.items[2];
            default -> throw new IllegalArgumentException("Invalid slot for Anvil Container: " + bedrockSlot);
        };
    }

    @Override
    public boolean setItem(final int bedrockSlot, final BedrockItem item) {
        return switch (bedrockSlot) {
            case 1 -> super.setItem(0, item);
            case 2 -> super.setItem(1, item);
            case 50 -> super.setItem(2, item);
            default -> throw new IllegalArgumentException("Invalid slot for Anvil Container: " + bedrockSlot);
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
    public boolean handleClick(final int revision, final short javaSlot, final byte button, final ContainerInput action) {
        if (javaSlot == 2) {
            if (!ViaBedrock.getConfig().shouldEnableExperimentalFeatures()) {
                return false;
            }
            if (action != ContainerInput.PICKUP && action != ContainerInput.QUICK_MOVE) {
                return false;
            }

            final ExperimentalInventoryTracker inventoryTracker = user.get(ExperimentalInventoryTracker.class);
            final InventoryRequestTracker inventoryRequestTracker = user.get(InventoryRequestTracker.class);
            final InventoryContainer inventory = inventoryTracker.getInventoryContainer();
            final BedrockItem inputItem = this.getItem(1);
            final BedrockItem materialItem = this.getItem(2);
            final BedrockItem resultItem = this.getItem(50);
            if (inputItem.isEmpty() || resultItem.isEmpty()) {
                return false;
            }

            final BedrockItem cursorItem = inventoryTracker.getHudContainer().getItem(0);
            if (action == ContainerInput.PICKUP && !cursorItem.isEmpty() && (cursorItem.isDifferent(resultItem) || cursorItem.amount() + resultItem.amount() > this.maxStackSize(resultItem))) {
                return false;
            }
            if (action == ContainerInput.QUICK_MOVE && this.inventoryCapacity(inventory, resultItem) < resultItem.amount()) {
                return false;
            }

            final int requestId = inventoryRequestTracker.nextRequestId();
            final int materialConsumeCount = this.materialConsumeCount(inputItem, materialItem, resultItem);
            final List<ItemStackRequestAction> actions = new ArrayList<>();
            actions.add(new ItemStackRequestAction.CraftRecipeOptionalAction(0, 0));
            if (materialConsumeCount > 0) {
                actions.add(new ItemStackRequestAction.ConsumeAction(materialConsumeCount, new ItemStackRequestSlotInfo(
                        new FullContainerName(ContainerEnumName.AnvilMaterialContainer, null),
                        (byte) 2,
                        materialItem.netId() != null ? materialItem.netId() : 0
                )));
            }
            actions.add(new ItemStackRequestAction.ConsumeAction(1, new ItemStackRequestSlotInfo(
                    new FullContainerName(ContainerEnumName.AnvilInputContainer, null),
                    (byte) 1,
                    inputItem.netId() != null ? inputItem.netId() : 0
            )));

            if (action == ContainerInput.PICKUP) {
                actions.add(new ItemStackRequestAction.PlaceAction(
                        resultItem.amount(),
                        new ItemStackRequestSlotInfo(new FullContainerName(ContainerEnumName.CreatedOutputContainer, null), (byte) 50, requestId),
                        new ItemStackRequestSlotInfo(new FullContainerName(ContainerEnumName.CursorContainer, null), (byte) 0, cursorItem.netId() != null ? cursorItem.netId() : 0)
                ));
            } else {
                this.addOutputToInventoryActions(actions, inventory, resultItem, resultItem.amount(), requestId);
            }

            final List<String> filterStrings = new ArrayList<>();
            TextProcessingEventOrigin origin = TextProcessingEventOrigin.unknown;
            if (!this.getRenameText().isEmpty()) {
                filterStrings.add(this.getRenameText());
                origin = TextProcessingEventOrigin.AnvilText;
            }

            final ItemStackRequestInfo request = new ItemStackRequestInfo(requestId, actions, filterStrings, origin);
            final List<ExperimentalContainer> prevContainers = new ArrayList<>();
            prevContainers.add(this.copy());
            prevContainers.add(inventory.copy());
            inventoryRequestTracker.addRequest(new InventoryRequestStorage(request, revision, inventoryTracker.getHudContainer().copy(), prevContainers));
            ExperimentalPacketFactory.sendBedrockInventoryRequest(user, new ItemStackRequestInfo[]{request});

            this.setItem(1, this.itemAfterRemovingAmount(inputItem, 1));
            this.setItem(2, this.itemAfterRemovingAmount(materialItem, materialConsumeCount));
            this.setItem(50, BedrockItem.empty());
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
            ExperimentalPacketFactory.sendJavaContainerSetContent(user, this);
            return true;
        }
        return super.handleClick(revision, javaSlot, button, action);
    }

    private int materialConsumeCount(final BedrockItem inputItem, final BedrockItem materialItem, final BedrockItem resultItem) {
        if (materialItem.isEmpty() || this.isOnlyRenaming(inputItem, resultItem)) {
            return 0;
        }

        final int inputDamage = this.damageValue(inputItem);
        final int resultDamage = this.damageValue(resultItem);
        final String inputIdentifier = this.itemIdentifier(inputItem);
        if (inputDamage > resultDamage && inputIdentifier != null && materialItem.identifier() != inputItem.identifier()) {
            final int maxDamage = this.maxDamage(inputIdentifier);
            if (maxDamage > 0) {
                final int repairPerItem = Math.max(1, maxDamage / 4);
                final int repairedDamage = inputDamage - resultDamage;
                return Math.min(materialItem.amount(), Math.max(1, (repairedDamage + repairPerItem - 1) / repairPerItem));
            }
        }

        return 1;
    }

    private boolean isOnlyRenaming(final BedrockItem inputItem, final BedrockItem resultItem) {
        final BedrockItem inputWithoutName = this.withoutCustomName(inputItem);
        final BedrockItem resultWithoutName = this.withoutCustomName(resultItem);
        inputWithoutName.setAmount(resultWithoutName.amount());
        return !inputWithoutName.isDifferent(resultWithoutName);
    }

    private BedrockItem withoutCustomName(final BedrockItem item) {
        final BedrockItem copy = item.copy();
        if (copy.tag() == null) {
            return copy;
        }

        final CompoundTag tag = copy.tag().copy();
        if (tag.get("display") instanceof CompoundTag displayTag) {
            displayTag.remove("Name");
            if (displayTag.isEmpty()) {
                tag.remove("display");
            }
        }
        copy.setTag(tag.isEmpty() ? null : tag);
        return copy;
    }

    private int damageValue(final BedrockItem item) {
        if (item.tag() != null && item.tag().get("Damage") instanceof NumberTag damageTag) {
            return damageTag.asInt();
        }
        return 0;
    }

    private String itemIdentifier(final BedrockItem item) {
        return this.user.get(ItemRewriter.class).getItems().inverse().get(item.identifier());
    }

    private int maxDamage(final String identifier) {
        final String item = identifier.startsWith("minecraft:") ? identifier.substring("minecraft:".length()) : identifier;
        return switch (item) {
            case "wooden_sword", "wooden_shovel", "wooden_pickaxe", "wooden_axe", "wooden_hoe" -> 59;
            case "wooden_spear" -> 59;
            case "stone_sword", "stone_shovel", "stone_pickaxe", "stone_axe", "stone_hoe" -> 131;
            case "stone_spear" -> 131;
            case "copper_sword", "copper_shovel", "copper_pickaxe", "copper_axe", "copper_hoe", "copper_spear" -> 190;
            case "iron_sword", "iron_shovel", "iron_pickaxe", "iron_axe", "iron_hoe", "trident" -> 250;
            case "iron_spear" -> 250;
            case "golden_sword", "golden_shovel", "golden_pickaxe", "golden_axe", "golden_hoe" -> 32;
            case "golden_spear" -> 32;
            case "diamond_sword", "diamond_shovel", "diamond_pickaxe", "diamond_axe", "diamond_hoe" -> 1561;
            case "diamond_spear" -> 1561;
            case "netherite_sword", "netherite_shovel", "netherite_pickaxe", "netherite_axe", "netherite_hoe" -> 2031;
            case "netherite_spear" -> 2031;
            case "leather_helmet" -> 55;
            case "leather_chestplate" -> 80;
            case "leather_leggings" -> 75;
            case "leather_boots" -> 65;
            case "copper_helmet" -> 121;
            case "copper_chestplate" -> 176;
            case "copper_leggings" -> 165;
            case "copper_boots" -> 143;
            case "chainmail_helmet", "iron_helmet" -> 165;
            case "chainmail_chestplate", "iron_chestplate" -> 240;
            case "chainmail_leggings", "iron_leggings" -> 225;
            case "chainmail_boots", "iron_boots" -> 195;
            case "golden_helmet" -> 77;
            case "golden_chestplate" -> 112;
            case "golden_leggings" -> 105;
            case "golden_boots" -> 91;
            case "diamond_helmet" -> 363;
            case "diamond_chestplate" -> 528;
            case "diamond_leggings" -> 495;
            case "diamond_boots" -> 429;
            case "netherite_helmet" -> 407;
            case "netherite_chestplate" -> 592;
            case "netherite_leggings" -> 555;
            case "netherite_boots" -> 481;
            case "turtle_helmet" -> 275;
            case "elytra" -> 432;
            case "shield" -> 336;
            case "bow" -> 384;
            case "crossbow" -> 465;
            case "mace" -> 500;
            case "shears" -> 238;
            case "fishing_rod", "flint_and_steel", "brush" -> 64;
            case "carrot_on_a_stick" -> 25;
            case "warped_fungus_on_a_stick" -> 100;
            case "wolf_armor" -> 64;
            default -> 0;
        };
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
                actions.add(new ItemStackRequestAction.PlaceAction(
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

    public String getRenameText() {
        return renameText;
    }

    public void setRenameText(String renameText) {
        this.renameText = renameText;
    }

}

//[13:36:45:931] [SERVER BOUND] - ItemStackRequestPacket(requests=[ItemStackRequest(requestId=-57, actions=[CraftRecipeOptionalAction(recipeNetworkId=0, filteredStringIndex=0), ConsumeAction(count=1, source=ItemStackRequestSlotData(container=ANVIL_MATERIAL, slot=2, stackNetworkId=83, containerName=FullContainerName(container=ANVIL_MATERIAL, dynamicId=null))), ConsumeAction(count=1, source=ItemStackRequestSlotData(container=ANVIL_INPUT, slot=1, stackNetworkId=79, containerName=FullContainerName(container=ANVIL_INPUT, dynamicId=null))), PlaceAction(count=1, source=ItemStackRequestSlotData(container=CREATED_OUTPUT, slot=50, stackNetworkId=-57, containerName=FullContainerName(container=CREATED_OUTPUT, dynamicId=null)), destination=ItemStackRequestSlotData(container=HOTBAR_AND_INVENTORY, slot=1, stackNetworkId=0, containerName=FullContainerName(container=HOTBAR_AND_INVENTORY, dynamicId=null)))], filterStrings=[Diamond Swordaaaa], textProcessingEventOrigin=ANVIL_TEXT)])
//[13:36:45:985] [CLIENT BOUND] - ItemStackResponsePacket(entries=[ItemStackResponse(result=OK, requestId=-57, containers=[ItemStackResponseContainer(container=ANVIL_MATERIAL, items=[ItemStackResponseSlot(slot=2, hotbarSlot=2, count=0, stackNetworkId=0, customName=, durabilityCorrection=0, filteredCustomName=)], containerName=FullContainerName(container=ANVIL_MATERIAL, dynamicId=null)), ItemStackResponseContainer(container=ANVIL_INPUT, items=[ItemStackResponseSlot(slot=1, hotbarSlot=1, count=0, stackNetworkId=0, customName=, durabilityCorrection=0, filteredCustomName=)], containerName=FullContainerName(container=ANVIL_INPUT, dynamicId=null)), ItemStackResponseContainer(container=HOTBAR_AND_INVENTORY, items=[ItemStackResponseSlot(slot=1, hotbarSlot=1, count=1, stackNetworkId=84, customName=Diamond Swordaaaa, durabilityCorrection=0, filteredCustomName=)], containerName=FullContainerName(container=HOTBAR_AND_INVENTORY, dynamicId=null))])])

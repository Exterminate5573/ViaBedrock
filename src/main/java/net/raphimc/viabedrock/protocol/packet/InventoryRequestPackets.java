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
package net.raphimc.viabedrock.protocol.packet;

import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.protocols.v26_2to26_3.packet.ClientboundPackets26_3;
import com.viaversion.viaversion.protocols.v26_2to26_3.packet.ServerboundPackets26_3;

import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.api.model.container.Container;
import net.raphimc.viabedrock.api.model.container.block.*;
import net.raphimc.viabedrock.api.util.PacketFactory;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.ClientboundBedrockPackets;
import net.raphimc.viabedrock.protocol.ServerboundBedrockPackets;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.*;
import net.raphimc.viabedrock.protocol.model.BedrockItem;
import net.raphimc.viabedrock.protocol.model.inventory.*;
import net.raphimc.viabedrock.protocol.model.recipe.*;
import net.raphimc.viabedrock.protocol.rewriter.ItemRewriter;
import net.raphimc.viabedrock.protocol.storage.*;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;
import net.raphimc.viabedrock.protocol.types.InventoryTypes;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class InventoryRequestPackets {

    public static void register(final BedrockProtocol protocol) {
        protocol.registerServerbound(ServerboundPackets26_3.CONTAINER_BUTTON_CLICK, null, wrapper -> {
            wrapper.cancel();
            final int containerId = wrapper.read(Types.VAR_INT); // container id
            final int button = wrapper.read(Types.VAR_INT); // button

            final InventoryTracker inventoryTracker = wrapper.user().get(InventoryTracker.class);
            if (inventoryTracker.getPendingCloseContainer() != null) {
                wrapper.cancel();
                return;
            }
            final Container container = inventoryTracker.getContainerServerbound((byte) containerId);
            if (container == null) {
                wrapper.cancel();
                return;
            }
            if (!container.handleButtonClick(button)) {
                if (container.type() != ContainerType.INVENTORY) {
                    PacketFactory.sendJavaContainerSetContent(wrapper.user(), inventoryTracker.getInventoryContainer());
                }
                PacketFactory.sendJavaContainerSetContent(wrapper.user(), container);
            }
        });
        protocol.registerServerbound(ServerboundPackets26_3.SET_BEACON, null, wrapper -> {
            wrapper.cancel();

            int primaryPower = -1;
            int secondaryPower = -1;

            final boolean hasPrimary = wrapper.read(Types.BOOLEAN);
            if (hasPrimary) {
                primaryPower = wrapper.read(Types.VAR_INT);
            }
            final boolean hasSecondary = wrapper.read(Types.BOOLEAN);
            if (hasSecondary) {
                secondaryPower = wrapper.read(Types.VAR_INT);
            }

            final InventoryTracker inventoryTracker = wrapper.user().get(InventoryTracker.class);
            if (inventoryTracker.isContainerOpen() && inventoryTracker.getCurrentContainer() instanceof BeaconContainer beaconContainer) {
                beaconContainer.updateEffects(primaryPower, secondaryPower);
            }
        });
        protocol.registerServerbound(ServerboundPackets26_3.RENAME_ITEM, null, wrapper -> {
            wrapper.cancel();
            InventoryTracker inventoryTracker = wrapper.user().get(InventoryTracker.class);
            final String newName = wrapper.read(Types.STRING);

            if (inventoryTracker.isContainerOpen() && inventoryTracker.getCurrentContainer() instanceof AnvilContainer anvilContainer) {
                anvilContainer.setRenameText(newName);
            }
        });
        protocol.registerServerbound(ServerboundPackets26_3.CONTAINER_SLOT_STATE_CHANGED, ServerboundBedrockPackets.TOGGLE_CRAFTER_SLOT_REQUEST, wrapper -> {
            int slotId = wrapper.read(Types.VAR_INT);
            int windowId = wrapper.read(Types.VAR_INT);
            boolean state = wrapper.read(Types.BOOLEAN);

            InventoryTracker inventoryTracker = wrapper.user().get(InventoryTracker.class);
            Container container = inventoryTracker.getContainerServerbound((byte) windowId);
            if (!(container instanceof CrafterContainer)) {
                wrapper.cancel();
                return;
            }

            wrapper.write(BedrockTypes.INT_LE, container.position().x());
            wrapper.write(BedrockTypes.INT_LE, container.position().y());
            wrapper.write(BedrockTypes.INT_LE, container.position().z());
            wrapper.write(Types.UNSIGNED_BYTE, (short) slotId);
            wrapper.write(Types.BOOLEAN, !state);
        });

        protocol.registerClientbound(ClientboundBedrockPackets.CRAFTING_DATA, null, wrapper -> {
            wrapper.cancel();
            CraftingDataTracker craftingDataTracker = wrapper.user().get(CraftingDataTracker.class);
            ItemRewriter itemRewriter = wrapper.user().get(ItemRewriter.class);

            List<CraftingDataStorage> recipes = new ArrayList<>();
            final int craftingDataSize = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT);
            for (int i = 0; i < craftingDataSize; i++) {
                final RecipeType recipeType = RecipeType.getByValue(wrapper.read(BedrockTypes.VAR_INT));
                if (recipeType == null) {
                    ViaBedrock.getPlatform().getLogger().warning("Received unknown recipe type in crafting data");
                    return;
                }
                switch (recipeType) {
                    case SHAPELESS, USER_DATA_SHAPELESS, SHAPELESS_CHEMISTRY -> {
                        final String recipeId = wrapper.read(BedrockTypes.STRING);
                        final List<ItemDescriptor> ingredients = List.of(wrapper.read(InventoryTypes.ITEM_DESCRIPTORS));
                        final List<BedrockItem> results = List.of(wrapper.read(itemRewriter.itemInstanceArrayType()));
                        final UUID recipeUuid = wrapper.read(BedrockTypes.UUID);
                        final String recipeTag = wrapper.read(BedrockTypes.STRING);
                        final int priority = wrapper.read(BedrockTypes.VAR_INT);

                        // TODO: Sync unlocking recipes
                        if (recipeType == RecipeType.SHAPELESS || recipeType == RecipeType.USER_DATA_SHAPELESS) {
                            final byte unlock = wrapper.read(Types.BYTE);
                            if (unlock == 0) {
                                wrapper.read(InventoryTypes.ITEM_DESCRIPTORS);
                            }
                        }

                        final int netId = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT);

                        CraftingDataStorage recipe = new CraftingDataStorage(
                                recipeType,
                                netId,
                                new ShapelessRecipe(recipeId, recipeUuid, recipeTag, priority, ingredients, results)
                        );
                        recipes.add(recipe);
                    }
                    case SHAPED, SHAPED_CHEMISTRY -> {
                        final String recipeId = wrapper.read(BedrockTypes.STRING);
                        final int width = wrapper.read(BedrockTypes.VAR_INT);
                        final int height = wrapper.read(BedrockTypes.VAR_INT);
                        final ItemDescriptor[][] ingredients = new ItemDescriptor[height][width];
                        for (int row = 0; row < height; row++) {
                            for (int col = 0; col < width; col++) {
                                ingredients[row][col] = wrapper.read(InventoryTypes.ITEM_DESCRIPTOR_TYPE);
                            }
                        }

                        final List<BedrockItem> results = List.of(wrapper.read(itemRewriter.itemInstanceArrayType()));
                        final UUID recipeUuid = wrapper.read(BedrockTypes.UUID);
                        final String recipeTag = wrapper.read(BedrockTypes.STRING);
                        final int priority = wrapper.read(BedrockTypes.VAR_INT);
                        final boolean assumeSymmetric = wrapper.read(Types.BOOLEAN);

                        // TODO: Sync unlocking recipes
                        if (recipeType == RecipeType.SHAPED) {
                            final byte unlock = wrapper.read(Types.BYTE);
                            if (unlock == 0) {
                                wrapper.read(InventoryTypes.ITEM_DESCRIPTORS);
                            }
                        }

                        final int netId = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT);

                        CraftingDataStorage recipe = new CraftingDataStorage(
                                recipeType,
                                netId,
                                new ShapedRecipe(recipeId, recipeUuid, recipeTag, priority, ingredients, results, assumeSymmetric)
                        );
                        recipes.add(recipe);
                    }
                    case UNKNOWN_1, UNKNOWN_2 -> { // TODO: What is this for
                        final int itemData = wrapper.read(BedrockTypes.VAR_INT);

                        if (recipeType == RecipeType.UNKNOWN_2) {
                            final int itemAuxData = wrapper.read(BedrockTypes.VAR_INT);
                        }

                        BedrockItem result = wrapper.read(itemRewriter.itemInstanceType());

                        final String recipeTag = wrapper.read(BedrockTypes.STRING);
                    }
                    case MULTI -> { // TODO: What is this for
                        final UUID recipeUuid = wrapper.read(BedrockTypes.UUID);
                        final int netId = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT);
                    }
                    case SMITHING_TRANSFORM, SMITHING_TRIM -> {
                        final String recipeId = wrapper.read(BedrockTypes.STRING);
                        final ItemDescriptor template = wrapper.read(InventoryTypes.ITEM_DESCRIPTOR_TYPE);
                        final ItemDescriptor base = wrapper.read(InventoryTypes.ITEM_DESCRIPTOR_TYPE);
                        final ItemDescriptor addition = wrapper.read(InventoryTypes.ITEM_DESCRIPTOR_TYPE);
                        BedrockItem result = BedrockItem.empty();
                        if (recipeType == RecipeType.SMITHING_TRANSFORM) {
                            result = wrapper.read(itemRewriter.itemInstanceType());
                        }
                        final String recipeTag = wrapper.read(BedrockTypes.STRING);
                        final int netId = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT);

                        CraftingDataStorage recipe = new CraftingDataStorage(
                                recipeType,
                                netId,
                                new SmithingRecipe(recipeId, UUID.nameUUIDFromBytes(recipeId.getBytes(StandardCharsets.UTF_8)), recipeTag, 0, template, base, addition, result)
                        );
                        recipes.add(recipe);
                    }
                    default -> {
                        ViaBedrock.getPlatform().getLogger().warning("Received unsupported recipe type: " + recipeType);
                        return;
                    }
                }
            }
            craftingDataTracker.updateCraftingDataList(recipes);

            craftingDataTracker.sendJavaUpdateRecipes(wrapper.user());
            // Recipe book entries still need their ingredients and categories mapped.

            wrapper.clearPacket();

            // TODO: Potion Mixes

            // TODO: Container Mixes

            // TODO: Material Reducers

            // TODO: Clear recipes
        });
        protocol.registerClientbound(ClientboundBedrockPackets.ITEM_STACK_RESPONSE, null, wrapper -> {
            wrapper.cancel();
            InventoryTracker inventoryTracker = wrapper.user().get(InventoryTracker.class);
            InventoryRequestTracker inventoryRequestTracker = wrapper.user().get(InventoryRequestTracker.class);
            ItemStackResponseInfo[] infoList = wrapper.read(InventoryTypes.ITEM_STACK_RESPONSES);

            // Resync the inventory content based on the response
            for (ItemStackResponseInfo info : infoList) {

                InventoryRequestStorage requestInfo = inventoryRequestTracker.getRequest(info.requestId());
                if (requestInfo == null) {
                    ViaBedrock.getPlatform().getLogger().warning("Received item stack response for unknown request ID: " + info.requestId());
                    continue;
                }
                inventoryRequestTracker.removeRequest(info.requestId());

                if (info.result() != ItemStackNetResult.Success) {
                    ViaBedrock.getPlatform().getLogger().warning("Received unsuccessful item stack response: " + info.result());
                    inventoryTracker.getHudContainer().setItems(requestInfo.prevCursorContainer().getItems().clone());
                    for (Container container : requestInfo.prevContainers()) {
                        Container newContainer = inventoryTracker.getContainerClientbound(container.containerId(), null, null);
                        if (newContainer == null) continue;
                        newContainer.setItems(container.getItems().clone());
                        PacketFactory.sendJavaContainerSetContent(wrapper.user(), newContainer);  // Resync the container content on Java side
                    }
                    continue;
                }

                //TODO: This is required for crafting so that the cursor item net id is updated properly
                //TODO: Check that the items match the request, if not resync the container (We probably should do this anyway to be safe)
                List<Container> mismatchedContainers = new ArrayList<>();
                for (ItemStackResponseContainerInfo containerInfo : info.containers()) {
                    for (ItemStackResponseSlotInfo slotInfo : containerInfo.slots()) {
                        Container container = inventoryTracker.getContainerFromName(containerInfo.containerName(), slotInfo.slot());
                        if (container == null) {
                            ViaBedrock.getPlatform().getLogger().warning("Received item stack response for unknown container: " + containerInfo.containerName());
                            continue;
                        }

                        // Check if the item matches the expected item
                        BedrockItem expectedItem = container.getItem(slotInfo.slot());
                        if (expectedItem.isEmpty()) continue; //TODO
                        if (expectedItem.netId() == null || expectedItem.netId() != slotInfo.itemNetId() || expectedItem.amount() != slotInfo.amount()) {
                            BedrockItem newItem = expectedItem.copy();
                            newItem.setNetId(slotInfo.itemNetId());
                            newItem.setAmount(slotInfo.amount());
                            container.setItem(slotInfo.slot(), newItem);
                            if (container.getFullContainerName(slotInfo.slot()).name() != ContainerEnumName.CursorContainer) {
                                mismatchedContainers.add(container);
                            }
                        }
                        // TODO:  Handle custom name and durability
                    }
                }

                for (Container container : mismatchedContainers) {
                    PacketFactory.sendJavaContainerSetContent(wrapper.user(), container);  // Resync the container content on Java side
                }
                // Force resync cursor
                PacketFactory.sendJavaContainerSetContent(wrapper.user(), inventoryTracker.getInventoryContainer());
            }
        });
        protocol.registerClientbound(ClientboundBedrockPackets.CONTAINER_SET_DATA, ClientboundPackets26_3.CONTAINER_SET_DATA, wrapper -> {
            byte containerId = wrapper.read(Types.BYTE);
            int id = wrapper.read(BedrockTypes.VAR_INT);
            int value = wrapper.read(BedrockTypes.VAR_INT);

            Container container = wrapper.user().get(InventoryTracker.class).getContainerClientbound(containerId, null, null);
            if (container == null) {
                // TODO: This throws every time we open a container
                // Unknown container, ignore
                wrapper.cancel();
                ViaBedrock.getPlatform().getLogger().warning("Received ContainerSetData packet for unknown container: containerId=" + containerId + ", id=" + id + ", value=" + value);
                return;
            }
            int windowId = container.javaContainerId();
            if (windowId == -1) {
                // Unknown container, ignore
                wrapper.cancel();
                ViaBedrock.getPlatform().getLogger().warning("Received ContainerSetData packet for unknown container: containerId=" + containerId + ", id=" + id + ", value=" + value);
                return;
            }

            short javaId = container.translateContainerData(id);
            if (javaId == -1) {
                ViaBedrock.getPlatform().getLogger().warning("Received ContainerSetData packet with unknown id: containerId=" + containerId + ", id=" + id + ", value=" + value);
                wrapper.cancel();
                return;
            }

            wrapper.write(Types.VAR_INT, windowId);
            wrapper.write(Types.SHORT, javaId);
            wrapper.write(Types.SHORT, (short) value);
        });
        protocol.registerClientbound(ClientboundBedrockPackets.PLAYER_ENCHANT_OPTIONS, null, wrapper -> {
            wrapper.cancel();
            InventoryTracker inventoryTracker = wrapper.user().get(InventoryTracker.class);
            if (!(inventoryTracker.getCurrentContainer() instanceof EnchantmentContainer)){
                return;
            }
            EnchantmentContainer enchantmentContainer = (EnchantmentContainer) inventoryTracker.getCurrentContainer();

            final int size = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT); // size

            List<EnchantData> data = new ArrayList<>();
            for (int i = 0; i < size; i++) {
                final byte cost = wrapper.read(Types.BYTE); // cost
                wrapper.read(BedrockTypes.INT_LE); // slot

                // TODO: How does bedrock decide on what to show
                Enchant_Type selected = null;
                int level = -1;

                final int l1 = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT); // size
                for (int j = 0; j < l1; j++) {
                    final int id = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT); // enchant id
                    final byte lvl = wrapper.read(Types.BYTE); // enchant level

                    if (selected == null) {
                        selected = Enchant_Type.getByValue(id);
                        level = lvl;
                    }
                }

                final int l2 = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT); // size
                for (int j = 0; j < l2; j++) {
                    final int id = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT); // enchant id
                    final byte lvl = wrapper.read(Types.BYTE); // enchant level

                    if (selected == null) {
                        selected = Enchant_Type.getByValue(id);
                        level = lvl;
                    }
                }

                final int l3 = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT); // size
                for (int j = 0; j < l3; j++) {
                    final int id = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT); // enchant id
                    final byte lvl = wrapper.read(Types.BYTE); // enchant level

                    if (selected == null) {
                        selected = Enchant_Type.getByValue(id);
                        level = lvl;
                    }
                }

                wrapper.read(BedrockTypes.STRING); // Name
                final int netId = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT); // Enchant Net Id

                data.add(new EnchantData(cost, selected, level, netId));
            }

            enchantmentContainer.setEnchantData(data);
        });
    }

}

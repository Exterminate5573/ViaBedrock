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
package net.raphimc.viabedrock.experimental.storage;

import com.viaversion.viaversion.api.connection.StoredObject;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.minecraft.item.Item;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.api.type.types.version.VersionedTypes;
import com.viaversion.viaversion.protocols.v1_21_11to26_1.packet.ClientboundPackets26_1;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.experimental.model.container.ExperimentalContainer;
import net.raphimc.viabedrock.experimental.model.recipe.ItemDescriptor;
import net.raphimc.viabedrock.experimental.model.recipe.ShapedRecipe;
import net.raphimc.viabedrock.experimental.model.recipe.ShapelessRecipe;
import net.raphimc.viabedrock.experimental.model.recipe.SmithingRecipe;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.model.BedrockItem;
import net.raphimc.viabedrock.protocol.rewriter.ItemRewriter;

import java.util.ArrayList;
import java.util.List;

public class CraftingDataTracker extends StoredObject {

    private List<CraftingDataStorage> craftingDataList = new ArrayList<>();

    public CraftingDataTracker(UserConnection user) {
        super(user);
    }

    public List<CraftingDataStorage> getCraftingDataList() {
        return craftingDataList;
    }

    public void updateCraftingDataList(List<CraftingDataStorage> craftingDataList) {
        this.craftingDataList = craftingDataList;
    }

    public CraftingDataStorage getRecipeData(ExperimentalContainer container, String tag) {
        final int gridWidth = container.type() == net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerType.HUD ? 2 : 3;
        final int gridHeight = gridWidth;
        for (CraftingDataStorage craftingData : this.getCraftingDataList()) {
            if (craftingData.recipe() == null || !craftingData.recipe().getRecipeTag().equals(tag)) {
                continue;
            }

            switch (craftingData.type()) {
                case SHAPELESS -> {
                    if (matchShapelessRecipe(container, (ShapelessRecipe) craftingData.recipe(), gridWidth * gridHeight)) {
                        return craftingData;
                    }
                }
                case SHAPED -> {
                    if (matchShapedRecipe(container, (ShapedRecipe) craftingData.recipe(), gridWidth, gridHeight)) {
                        return craftingData;
                    }
                }
                case USER_DATA_SHAPELESS -> {
                    // TODO: Not supported yet
                }
                case SMITHING_TRIM, SMITHING_TRANSFORM -> {
                    // TODO: Hard coded slots for Smithing Container
                    SmithingRecipe smithingRecipe = (SmithingRecipe) craftingData.recipe();
                    if (smithingRecipe.getTemplate().matchesItem(this.user(), container.getItem(53)) &&
                            smithingRecipe.getBaseIngredient().matchesItem(this.user(), container.getItem(51)) &&
                            smithingRecipe.getAdditionIngredient().matchesItem(this.user(), container.getItem(52))) {
                        return craftingData;
                    }
                }
                default -> ViaBedrock.getPlatform().getLogger().warning(
                        "Unknown recipe type: " + craftingData.type() + " in recipe " + craftingData.recipe().getUniqueId()
                );
            }
        }
        return null;
    }

    private boolean matchShapelessRecipe(ExperimentalContainer container, ShapelessRecipe recipe, int gridSize) {
        if (recipe.getIngredients().size() > gridSize) {
            return false;
        }

        boolean[] used = new boolean[gridSize];
        for (ItemDescriptor descriptor : recipe.getIngredients()) {
            if (!findMatchingSlot(container, descriptor, used)) {
                return false;
            }
        }
        return noExtraItems(container, used);
    }

    private boolean matchShapedRecipe(ExperimentalContainer container, ShapedRecipe recipe, int gridWidth, int gridHeight) {
        int height = recipe.getPattern().length;
        int width = recipe.getPattern()[0].length;
        if (width > gridWidth || height > gridHeight) {
            return false;
        }

        for (int startY = 0; startY <= gridHeight - height; startY++) {
            for (int startX = 0; startX <= gridWidth - width; startX++) {
                if (matchesPattern(container, recipe, startX, startY, gridWidth, false) && noExtraItemsOutsidePattern(container, startX, startY, width, height, gridWidth, gridHeight)) {
                    return true;
                }
                if (recipe.isMirrored() && matchesPattern(container, recipe, startX, startY, gridWidth, true) && noExtraItemsOutsidePattern(container, startX, startY, width, height, gridWidth, gridHeight)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean findMatchingSlot(ExperimentalContainer container, ItemDescriptor descriptor, boolean[] used) {
        for (int slot = 0; slot < used.length; slot++) {
            if (used[slot]) continue;
            int inputSlot = container.bedrockSlot(slot + 1);
            BedrockItem item = container.getItem(inputSlot);
            if (descriptor.matchesItem(this.user(), item)) {
                used[slot] = true;
                return true;
            }
        }
        return false;
    }

    private boolean noExtraItems(ExperimentalContainer container, boolean[] used) {
        for (int slot = 0; slot < used.length; slot++) {
            int inputSlot = container.bedrockSlot(slot + 1);
            if (!used[slot] && !container.getItem(inputSlot).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private boolean matchesPattern(ExperimentalContainer container, ShapedRecipe recipe, int startX, int startY, int gridWidth, boolean mirrored) {
        int height = recipe.getPattern().length;
        int width = recipe.getPattern()[0].length;

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                ItemDescriptor descriptor = recipe.getPattern()[y][mirrored ? width - x - 1 : x];
                BedrockItem item = container.getItem(container.bedrockSlot((startY + y) * gridWidth + (startX + x) + 1));
                if (!descriptor.matchesItem(this.user(), item)) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean noExtraItemsOutsidePattern(ExperimentalContainer container, int startX, int startY, int width, int height, int gridWidth, int gridHeight) {
        for (int gx = 0; gx < gridWidth; gx++) {
            for (int gy = 0; gy < gridHeight; gy++) {
                if (gx >= startX && gx < startX + width && gy >= startY && gy < startY + height) {
                    continue;
                }
                if (!container.getItem(container.bedrockSlot(gy * gridWidth + gx + 1)).isEmpty()) {
                    return false;
                }
            }
        }
        return true;
    }


    public void sendJavaUpdateRecipes(final UserConnection user) {
        //TODO: Fix up this mess
        if (craftingDataList.isEmpty()) {
            ViaBedrock.getPlatform().getLogger().warning("No crafting data available to update.");
            return;
        }
        ItemRewriter itemRewriter = user.get(ItemRewriter.class);

        PacketWrapper packet = PacketWrapper.create(ClientboundPackets26_1.UPDATE_RECIPES, user);
        packet.write(Types.VAR_INT, 0); // Property Sets (Prefixed array) TODO: Sends registries e.g. furnace fuel, smithing template
        List<CraftingDataStorage> stonecutterList = craftingDataList.stream()
                .filter(c -> c.recipe().getRecipeTag().equals("stonecutter"))
                .filter(c -> c.recipe() instanceof ShapelessRecipe)
                .toList();
        packet.write(Types.VAR_INT, stonecutterList.size()); // Number of recipes
        for (CraftingDataStorage craftingData : stonecutterList) {
            //IDs
            packet.write(Types.VAR_INT, 2); // Type (Size + 1)
            ShapelessRecipe recipe = (ShapelessRecipe) craftingData.recipe();
            if (recipe.getIngredients().isEmpty()) {
                packet.write(Types.VAR_INT, BedrockProtocol.MAPPINGS.getJavaSlotDisplayId("minecraft:empty")); // Slot Display Type
            } else {
                recipe.getIngredients().get(0).writeJavaIngredientData(packet, user);
            }

            //Slot Display
            Item javaOutput = itemRewriter.javaItem(recipe.getResults().get(0));
            packet.write(Types.VAR_INT, BedrockProtocol.MAPPINGS.getJavaSlotDisplayId("minecraft:item_stack")); // Type
            packet.write(VersionedTypes.V26_1.itemTemplate, javaOutput);
        }

        packet.send(BedrockProtocol.class);
    }

    public void sendJavaRecipeBook(final UserConnection user) {
        if (craftingDataList.isEmpty()) {
            ViaBedrock.getPlatform().getLogger().warning("No crafting data available to send Java recipe book.");
            return;
        }

        PacketWrapper packet = PacketWrapper.create(ClientboundPackets26_1.RECIPE_BOOK_ADD, user);
        packet.write(Types.VAR_INT, craftingDataList.size()); // Number of recipes
        for (CraftingDataStorage craftingData : craftingDataList) {
            packet.write(Types.VAR_INT, craftingData.networkId()); // Recipe ID
            craftingData.recipe().writeJavaRecipeData(packet, user);
            packet.write(Types.OPTIONAL_VAR_INT, craftingData.networkId()); //TODO: Group Id
            packet.write(Types.VAR_INT, 1); // TODO: Category ID
            packet.write(Types.BOOLEAN, false); // Optional Ingredients list
            packet.write(Types.BYTE, (byte) 0x00); // Recipe Flags (0x01: show notification; 0x02: highlight as new)
        }
        packet.write(Types.BOOLEAN, false); //  Replace or Add
        packet.send(BedrockProtocol.class);
    }
}

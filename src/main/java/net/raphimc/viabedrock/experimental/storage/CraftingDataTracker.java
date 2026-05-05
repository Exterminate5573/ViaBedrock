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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class CraftingDataTracker extends StoredObject {

    private List<CraftingDataStorage> craftingDataList = new ArrayList<>();

    public record IngredientUse(int bedrockSlot, int count) {
    }

    public record RecipeMatch(CraftingDataStorage craftingData, List<IngredientUse> ingredients) {
    }

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
        final RecipeMatch match = this.getRecipeMatch(container, tag);
        return match != null ? match.craftingData() : null;
    }

    public RecipeMatch getRecipeMatch(ExperimentalContainer container, String tag) {
        final int gridWidth = container.type() == net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerType.HUD ? 2 : 3;
        final int gridHeight = gridWidth;
        for (CraftingDataStorage craftingData : this.getCraftingDataList()) {
            if (craftingData.recipe() == null || !craftingData.recipe().getRecipeTag().equals(tag)) {
                continue;
            }

            switch (craftingData.type()) {
                case SHAPELESS, USER_DATA_SHAPELESS -> {
                    final List<IngredientUse> ingredients = matchShapelessRecipe(container, (ShapelessRecipe) craftingData.recipe(), gridWidth * gridHeight);
                    if (ingredients != null) {
                        return new RecipeMatch(craftingData, ingredients);
                    }
                }
                case SHAPED -> {
                    final List<IngredientUse> ingredients = matchShapedRecipe(container, (ShapedRecipe) craftingData.recipe(), gridWidth, gridHeight);
                    if (ingredients != null) {
                        return new RecipeMatch(craftingData, ingredients);
                    }
                }
                case SMITHING_TRIM, SMITHING_TRANSFORM -> {
                    // TODO: Hard coded slots for Smithing Container
                    SmithingRecipe smithingRecipe = (SmithingRecipe) craftingData.recipe();
                    if (matchesIngredient(container.getItem(53), smithingRecipe.getTemplate(), 0) &&
                            matchesIngredient(container.getItem(51), smithingRecipe.getBaseIngredient(), 0) &&
                            matchesIngredient(container.getItem(52), smithingRecipe.getAdditionIngredient(), 0)) {
                        return new RecipeMatch(craftingData, List.of(
                                new IngredientUse(53, smithingRecipe.getTemplate().amount()),
                                new IngredientUse(51, smithingRecipe.getBaseIngredient().amount()),
                                new IngredientUse(52, smithingRecipe.getAdditionIngredient().amount())
                        ));
                    }
                }
                default -> ViaBedrock.getPlatform().getLogger().warning(
                        "Unknown recipe type: " + craftingData.type() + " in recipe " + craftingData.recipe().getUniqueId()
                );
            }
        }
        return null;
    }

    private List<IngredientUse> matchShapelessRecipe(ExperimentalContainer container, ShapelessRecipe recipe, int gridSize) {
        if (recipe.getIngredients().size() > gridSize) {
            return null;
        }

        final int[] usedCounts = new int[gridSize];
        if (!findMatchingSlots(container, recipe.getIngredients(), usedCounts, 0)) {
            return null;
        }
        return noExtraItems(container, usedCounts) ? ingredientUses(container, usedCounts) : null;
    }

    private List<IngredientUse> matchShapedRecipe(ExperimentalContainer container, ShapedRecipe recipe, int gridWidth, int gridHeight) {
        int height = recipe.getPattern().length;
        int width = recipe.getPattern()[0].length;
        if (width > gridWidth || height > gridHeight) {
            return null;
        }

        for (int startY = 0; startY <= gridHeight - height; startY++) {
            for (int startX = 0; startX <= gridWidth - width; startX++) {
                List<IngredientUse> ingredients = matchesPattern(container, recipe, startX, startY, gridWidth, false);
                if (ingredients != null && noExtraItemsOutsidePattern(container, startX, startY, width, height, gridWidth, gridHeight)) {
                    return ingredients;
                }
                if (recipe.isMirrored()) {
                    ingredients = matchesPattern(container, recipe, startX, startY, gridWidth, true);
                    if (ingredients != null && noExtraItemsOutsidePattern(container, startX, startY, width, height, gridWidth, gridHeight)) {
                        return ingredients;
                    }
                }
            }
        }
        return null;
    }

    private boolean findMatchingSlots(final ExperimentalContainer container, final List<ItemDescriptor> descriptors, final int[] usedCounts, final int descriptorIndex) {
        if (descriptorIndex >= descriptors.size()) {
            return true;
        }

        final ItemDescriptor descriptor = descriptors.get(descriptorIndex);
        for (int slot = 0; slot < usedCounts.length; slot++) {
            if (usedCounts[slot] > 0) {
                continue;
            }

            int inputSlot = container.bedrockSlot(slot + 1);
            BedrockItem item = container.getItem(inputSlot);
            if (matchesIngredient(item, descriptor, usedCounts[slot])) {
                usedCounts[slot] += descriptor.amount();
                if (findMatchingSlots(container, descriptors, usedCounts, descriptorIndex + 1)) {
                    return true;
                }
                usedCounts[slot] -= descriptor.amount();
            }
        }
        return false;
    }

    private boolean noExtraItems(ExperimentalContainer container, int[] usedCounts) {
        for (int slot = 0; slot < usedCounts.length; slot++) {
            int inputSlot = container.bedrockSlot(slot + 1);
            if (usedCounts[slot] == 0 && !container.getItem(inputSlot).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private List<IngredientUse> ingredientUses(final ExperimentalContainer container, final int[] usedCounts) {
        final List<IngredientUse> ingredients = new ArrayList<>();
        for (int slot = 0; slot < usedCounts.length; slot++) {
            if (usedCounts[slot] > 0) {
                ingredients.add(new IngredientUse(container.bedrockSlot(slot + 1), usedCounts[slot]));
            }
        }
        return ingredients;
    }

    private List<IngredientUse> matchesPattern(ExperimentalContainer container, ShapedRecipe recipe, int startX, int startY, int gridWidth, boolean mirrored) {
        int height = recipe.getPattern().length;
        int width = recipe.getPattern()[0].length;
        final Map<Integer, Integer> ingredients = new LinkedHashMap<>();

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                ItemDescriptor descriptor = recipe.getPattern()[y][mirrored ? width - x - 1 : x];
                final int bedrockSlot = container.bedrockSlot((startY + y) * gridWidth + (startX + x) + 1);
                BedrockItem item = container.getItem(bedrockSlot);
                if (!matchesIngredient(item, descriptor, 0)) {
                    return null;
                }
                if (descriptor.amount() > 0 && !item.isEmpty()) {
                    ingredients.merge(bedrockSlot, descriptor.amount(), Integer::sum);
                }
            }
        }
        return ingredients.entrySet().stream()
                .map(entry -> new IngredientUse(entry.getKey(), entry.getValue()))
                .toList();
    }

    private boolean matchesIngredient(final BedrockItem item, final ItemDescriptor descriptor, final int alreadyUsed) {
        if (!descriptor.matchesItem(this.user(), item)) {
            return false;
        }
        return item.isEmpty() || item.amount() - alreadyUsed >= descriptor.amount();
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

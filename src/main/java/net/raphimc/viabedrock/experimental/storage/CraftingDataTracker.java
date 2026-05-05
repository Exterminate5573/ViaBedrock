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
import com.viaversion.viaversion.util.Key;
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

    private record StonecutterUpdateRecipe(List<Integer> inputJavaItemIds, Item javaOutput) {
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
        List<StonecutterUpdateRecipe> stonecutterList = new ArrayList<>();
        for (CraftingDataStorage craftingData : craftingDataList) {
            if (craftingData.recipe() == null || !craftingData.recipe().getRecipeTag().equals("stonecutter") || !(craftingData.recipe() instanceof ShapelessRecipe recipe)) {
                continue;
            }
            if (recipe.getIngredients().isEmpty() || recipe.getResults().isEmpty()) {
                continue;
            }

            final List<Integer> inputJavaItemIds = this.javaIngredientItemIds(user, recipe.getIngredients().get(0));
            final Item javaOutput = itemRewriter.javaItem(recipe.getResults().get(0));
            if (inputJavaItemIds.isEmpty() || javaOutput == null || javaOutput.isEmpty()) {
                continue;
            }
            stonecutterList.add(new StonecutterUpdateRecipe(inputJavaItemIds, javaOutput));
        }
        packet.write(Types.VAR_INT, stonecutterList.size()); // Number of recipes
        for (StonecutterUpdateRecipe recipe : stonecutterList) {
            packet.write(Types.VAR_INT, recipe.inputJavaItemIds().size() + 1); // Direct ingredient holder set
            for (Integer inputJavaItemId : recipe.inputJavaItemIds()) {
                packet.write(Types.VAR_INT, inputJavaItemId);
            }

            packet.write(Types.VAR_INT, BedrockProtocol.MAPPINGS.getJavaSlotDisplayId("minecraft:item_stack")); // Slot display type
            packet.write(VersionedTypes.V26_1.itemTemplate, recipe.javaOutput());
        }

        packet.send(BedrockProtocol.class);
    }

    private List<Integer> javaIngredientItemIds(final UserConnection user, final ItemDescriptor descriptor) {
        final ItemRewriter itemRewriter = user.get(ItemRewriter.class);
        final List<Integer> javaItemIds = new ArrayList<>();

        if (descriptor instanceof ItemDescriptor.DefaultDescriptor defaultDescriptor && defaultDescriptor.itemId() > 0) {
            this.addJavaIngredientItemId(javaItemIds, itemRewriter, new BedrockItem(defaultDescriptor.itemId(), (short) defaultDescriptor.auxValue(), (byte) 1));
        } else if (descriptor instanceof ItemDescriptor.DeferredDescriptor deferredDescriptor) {
            final Integer bedrockItemId = itemRewriter.getItems().get(Key.namespaced(deferredDescriptor.fullName()));
            if (bedrockItemId != null) {
                this.addJavaIngredientItemId(javaItemIds, itemRewriter, new BedrockItem(bedrockItemId, (short) deferredDescriptor.auxValue(), (byte) 1));
            }
        }

        if (!javaItemIds.isEmpty()) {
            return javaItemIds;
        }

        for (Map.Entry<String, Integer> entry : itemRewriter.getItems().entrySet()) {
            final BedrockItem bedrockItem = new BedrockItem(entry.getValue());
            if (descriptor.matchesItem(user, bedrockItem)) {
                this.addJavaIngredientItemId(javaItemIds, itemRewriter, bedrockItem);
            }
        }
        return javaItemIds;
    }

    private void addJavaIngredientItemId(final List<Integer> javaItemIds, final ItemRewriter itemRewriter, final BedrockItem bedrockItem) {
        final Item javaItem = itemRewriter.javaItem(bedrockItem);
        if (javaItem == null || javaItem.isEmpty() || javaItemIds.contains(javaItem.identifier())) {
            return;
        }
        javaItemIds.add(javaItem.identifier());
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

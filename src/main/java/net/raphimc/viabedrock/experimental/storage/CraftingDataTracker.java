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
import net.raphimc.viabedrock.experimental.model.recipe.ItemDescriptor;
import net.raphimc.viabedrock.experimental.model.recipe.ItemDescriptorType;
import net.raphimc.viabedrock.experimental.model.recipe.ShapedRecipe;
import net.raphimc.viabedrock.experimental.model.recipe.ShapelessRecipe;
import net.raphimc.viabedrock.protocol.rewriter.ItemRewriter;

import java.util.*;

public class CraftingDataTracker extends StoredObject {

    private final Map<Integer, CraftingDataStorage> recipeMap = new HashMap<>();
    private List<CraftingDataStorage> craftingDataList = new ArrayList<>();

    public CraftingDataTracker(UserConnection user) {
        super(user);
    }

    public CraftingDataStorage getResult(List<Integer> ingredients) {
        final CraftingDataStorage storage = recipeMap.get(ingredients.hashCode());
        if (storage != null) { // Priority shaped result first
            return storage;
        }

        // Then check for shapeless.
        ingredients.removeIf(i -> i == -1);
        ingredients.sort(Comparator.comparingInt(Integer::intValue));

        return recipeMap.get(ingredients.hashCode());
    }

    public void updateCraftingDataList(List<CraftingDataStorage> craftingDataList) {
        this.craftingDataList = craftingDataList;

        for (final CraftingDataStorage storage : craftingDataList) {
            switch (storage.type()) {
                case SHAPELESS -> {
                    ShapelessRecipe shapelessRecipe = (ShapelessRecipe) storage.recipe();

                    final List<Integer> ingredients = new ArrayList<>();
                    shapelessRecipe.getIngredients().forEach(descriptor -> {
                        if (descriptor.getType() == ItemDescriptorType.DEFAULT) {
                            int itemId = ((ItemDescriptor.DefaultDescriptor) descriptor).itemId();

                            ingredients.add(itemId);
                        } else if (descriptor.getType() == ItemDescriptorType.ITEM_TAG) {
//                            String tag = ((ItemDescriptor.ItemTagDescriptor) descriptor).itemTag();
//                            Integer id = user().get(ItemRewriter.class).getItems().get(tag);
//                            if (id != null) {
//                                ingredients.add(id);
//                            }
                        }
                    });

                    ingredients.sort(Comparator.comparingInt(Integer::intValue));
                    recipeMap.put(ingredients.hashCode(), storage);
                }

                case SHAPED -> {
                    ShapedRecipe shapedRecipe = (ShapedRecipe) storage.recipe();

                    final List<Integer> ingredients = new ArrayList<>();
                    for (int i = 0; i < 9; i++) {
                        if (shapedRecipe.getPattern().size() <= i) {
                            ingredients.add(-1);
                            continue;
                        }

                        ItemDescriptor descriptor = shapedRecipe.getPattern().get(i);
                        switch (descriptor.getType()) {
                            case DEFAULT -> {
                                int itemId = ((ItemDescriptor.DefaultDescriptor) descriptor).itemId();
                                boolean empty = itemId == 0 || itemId == -1;
                                ingredients.add(empty ? -1 : itemId);
                            }
                            case INVALID -> ingredients.add(-1);
                            case ITEM_TAG -> {
//                                String tag = ((ItemDescriptor.ItemTagDescriptor) descriptor).itemTag();
//                                Integer id = user().get(ItemRewriter.class).getItems().get(tag);
//                                if (id != null) {
//                                    ingredients.add(id);
//                                }
                            }
                        }
                    }

                    recipeMap.put(ingredients.hashCode(), storage);
                }
            }
        }
    }
}

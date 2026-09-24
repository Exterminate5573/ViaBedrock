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
package net.raphimc.viabedrock.protocol.model.recipe;

import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.type.Types;

import net.raphimc.viabedrock.protocol.model.BedrockItem;

import java.util.List;
import java.util.UUID;

public class ShapelessRecipe extends Recipe {

    private final List<ItemDescriptor> ingredients;
    private final List<BedrockItem> results;

    public ShapelessRecipe(final String uniqueId, final UUID recipeId, final String recipeTag, final int priority, final List<ItemDescriptor> ingredients, final List<BedrockItem> results) {
        super(uniqueId, recipeId, recipeTag, priority);
        this.ingredients = ingredients;
        this.results = results;
    }

    public List<ItemDescriptor> getIngredients() {
        return this.ingredients;
    }

    public List<BedrockItem> getResults() {
        return this.results;
    }

    @Override
    public void writeJavaRecipeData(final PacketWrapper packet, final UserConnection user) {
        packet.write(Types.VAR_INT, 0); // Recipe Display Type
        packet.write(Types.VAR_INT, this.getIngredients().size()); // Ingredient Count
        for (ItemDescriptor ingredient : this.getIngredients()) {
            ingredient.writeJavaIngredientData(packet, user); // Write each ingredient
        }
        new ItemDescriptor.DefaultDescriptor(this.results.get(0).identifier(), this.results.get(0).auxValue()).writeJavaIngredientData(packet, user);
        new ItemDescriptor.InvalidDescriptor().writeJavaIngredientData(packet, user); //TODO: Crafting Station
    }

    @Override
    public String toString() {
        return "ShapelessRecipe{"
                + "uniqueId='" + getUniqueId() + '\''
                + ", recipeId=" + getRecipeId()
                + ", recipeTag='" + getRecipeTag() + '\''
                + ", priority=" + getPriority()
                + ", ingredients=" + this.ingredients
                + ", results=" + this.results
                + '}';
    }

}

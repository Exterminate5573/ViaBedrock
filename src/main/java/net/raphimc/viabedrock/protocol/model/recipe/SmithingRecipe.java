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

import java.util.UUID;

public class SmithingRecipe extends Recipe {

    private final ItemDescriptor template;
    private final ItemDescriptor baseIngredient;
    private final ItemDescriptor additionIngredient;
    private final BedrockItem result;

    public SmithingRecipe(final String uniqueId, final UUID recipeId, final String recipeTag, final int priority, final ItemDescriptor template, final ItemDescriptor baseIngredient, final ItemDescriptor additionIngredient, final BedrockItem result) {
        super(uniqueId, recipeId, recipeTag, priority);
        this.template = template;
        this.baseIngredient = baseIngredient;
        this.additionIngredient = additionIngredient;
        this.result = result;
    }

    @Override
    public void writeJavaRecipeData(final PacketWrapper packet, final UserConnection user) {
        packet.write(Types.VAR_INT, 4); // Smithing recipe type
        this.template.writeJavaIngredientData(packet, user);
        this.baseIngredient.writeJavaIngredientData(packet, user);
        this.additionIngredient.writeJavaIngredientData(packet, user);
        new ItemDescriptor.DefaultDescriptor(this.result.identifier(), this.result.auxValue()).writeJavaIngredientData(packet, user);
        new ItemDescriptor.InvalidDescriptor().writeJavaIngredientData(packet, user); //TODO: Crafting Station
    }

    @Override
    public String toString() {
        return "SmithingRecipe{"
                + "template=" + this.template
                + ", baseIngredient=" + this.baseIngredient
                + ", additionIngredient=" + this.additionIngredient
                + ", result=" + this.result
                + "} " + super.toString();
    }

    public ItemDescriptor getTemplate() {
        return this.template;
    }

    public ItemDescriptor getBaseIngredient() {
        return this.baseIngredient;
    }

    public ItemDescriptor getAdditionIngredient() {
        return this.additionIngredient;
    }

    public BedrockItem getResult() {
        return this.result;
    }

}

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

import java.util.UUID;

public abstract class Recipe {

    private final String uniqueId;
    private final UUID recipeId;
    private final String recipeTag;
    private final int priority;

    public Recipe(final String uniqueId, final UUID recipeId, final String recipeTag, final int priority) {
        this.uniqueId = uniqueId;
        this.recipeId = recipeId;
        this.recipeTag = recipeTag;
        this.priority = priority;
    }

    public String getUniqueId() {
        return this.uniqueId;
    }

    public UUID getRecipeId() {
        return this.recipeId;
    }

    public String getRecipeTag() {
        return this.recipeTag;
    }

    public int getPriority() {
        return this.priority;
    }

    public abstract void writeJavaRecipeData(final PacketWrapper packet, final UserConnection user);

    @Override
    public String toString() {
        return "Recipe{"
                + "uniqueId='" + this.uniqueId + '\''
                + ", recipeId=" + this.recipeId
                + ", recipeTag='" + this.recipeTag + '\''
                + ", priority=" + this.priority
                + '}';
    }

}

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
package net.raphimc.viabedrock.experimental.model.container.block;

import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.minecraft.BlockPosition;
import com.viaversion.viaversion.libs.mcstructs.text.TextComponent;
import net.raphimc.viabedrock.experimental.model.container.ExperimentalContainer;
import net.raphimc.viabedrock.experimental.model.inventory.ItemStackRequestAction;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerEnumName;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerType;
import net.raphimc.viabedrock.protocol.data.enums.java.generated.ContainerInput;
import net.raphimc.viabedrock.protocol.model.BedrockItem;
import net.raphimc.viabedrock.protocol.model.FullContainerName;

import java.util.List;

public class CartographyContainer extends ExperimentalContainer {

    public CartographyContainer(final UserConnection user, final byte containerId, final TextComponent title, final BlockPosition position) {
        super(user, containerId, ContainerType.CARTOGRAPHY, title, position, 3, "cartography_table");
    }

    @Override
    public FullContainerName getFullContainerName(final int slot) {
        return switch (slot) {
            case 12 -> new FullContainerName(ContainerEnumName.CartographyInputContainer, null);
            case 13 -> new FullContainerName(ContainerEnumName.CartographyAdditionalContainer, null);
            case 50 -> new FullContainerName(ContainerEnumName.CreatedOutputContainer, null);
            default -> throw new IllegalArgumentException("Invalid slot for Cartography Container: " + slot);
        };
    }

    @Override
    public int javaSlot(final int slot) {
        return switch (slot) {
            case 12 -> 0;
            case 13 -> 1;
            case 50 -> 2;
            default -> super.javaSlot(slot);
        };
    }

    @Override
    public int bedrockSlot(final int slot) {
        return switch (slot) {
            case 0 -> 12;
            case 1 -> 13;
            case 2 -> 50;
            default -> super.bedrockSlot(slot);
        };
    }

    @Override
    public BedrockItem getItem(final int bedrockSlot) {
        return switch (bedrockSlot) {
            case 12 -> super.getItem(0);
            case 13 -> super.getItem(1);
            case 50 -> super.getItem(2);
            default -> throw new IllegalArgumentException("Invalid slot for Cartography Container: " + bedrockSlot);
        };
    }

    @Override
    public boolean setItem(final int bedrockSlot, final BedrockItem item) {
        return switch (bedrockSlot) {
            case 12 -> super.setItem(0, item);
            case 13 -> super.setItem(1, item);
            case 50 -> super.setItem(2, item);
            default -> throw new IllegalArgumentException("Invalid slot for Cartography Container: " + bedrockSlot);
        };
    }

    @Override
    public boolean handleClick(final int revision, final short javaSlot, final byte button, final ContainerInput action) {
        if (javaSlot == 2) {
            return this.handleCreatedOutputClick(
                    revision,
                    button,
                    action,
                    this.getItem(50),
                    List.of(new ItemStackRequestAction.CraftRecipeOptionalAction(0, 0)),
                    List.of(new ResultIngredient(12, 1, false), new ResultIngredient(13, 1))
            );
        }
        return super.handleClick(revision, javaSlot, button, action);
    }

    @Override
    protected boolean canPlaceItem(final int bedrockSlot, final BedrockItem item) {
        if (item.isEmpty()) {
            return true;
        }
        return switch (bedrockSlot) {
            case 12 -> this.isItem(item, "minecraft:filled_map") || item.tag() != null && item.tag().contains("map_uuid");
            case 13 -> this.isAnyItem(item, "minecraft:paper", "minecraft:empty_map", "minecraft:glass_pane");
            case 50 -> false;
            default -> false;
        };
    }

}

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
package net.raphimc.viabedrock.api.model.container.block;

import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.minecraft.BlockPosition;
import com.viaversion.viaversion.libs.mcstructs.text.TextComponent;

import net.raphimc.viabedrock.api.model.container.Container;
import net.raphimc.viabedrock.protocol.PlayerActionPacketFactory;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerEnumName;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerType;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.TextProcessingEventOrigin;
import net.raphimc.viabedrock.protocol.data.enums.java.generated.ContainerInput;
import net.raphimc.viabedrock.protocol.data.generated.bedrock.CustomBlockTags;
import net.raphimc.viabedrock.protocol.model.BedrockItem;
import net.raphimc.viabedrock.protocol.model.FullContainerName;
import net.raphimc.viabedrock.protocol.model.inventory.ItemStackRequestAction;
import net.raphimc.viabedrock.protocol.model.inventory.ItemStackRequestInfo;
import net.raphimc.viabedrock.protocol.model.inventory.ItemStackRequestSlotInfo;
import net.raphimc.viabedrock.protocol.storage.InventoryRequestStorage;
import net.raphimc.viabedrock.protocol.storage.InventoryRequestTracker;
import net.raphimc.viabedrock.protocol.storage.InventoryTracker;

import java.util.ArrayList;
import java.util.List;

public class AnvilContainer extends Container {

    private String renameText = "";

    public AnvilContainer(final UserConnection user, final byte containerId, final TextComponent title, final BlockPosition position) {
        super(user, containerId, ContainerType.ANVIL, title, position, 3, CustomBlockTags.ANVIL);
    }

    @Override
    public FullContainerName getFullContainerName(final int slot) {
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
    public BedrockItem getItem(final int bedrockSlot) {
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
    public boolean handleClick(final int revision, final short javaSlot, final byte button, final ContainerInput action) {
        if (javaSlot == 2) {
            final InventoryTracker inventoryTracker = user.get(InventoryTracker.class);
            final InventoryRequestTracker inventoryRequestTracker = user.get(InventoryRequestTracker.class);

            final int requestId = inventoryRequestTracker.nextRequestId();

            final List<Container> prevContainers = new ArrayList<>();
            prevContainers.add(this.copy());
            prevContainers.add(inventoryTracker.getInventoryContainer().copy());
            final Container prevCursorContainer = inventoryTracker.getHudContainer().copy();

            final BedrockItem resultItem = this.getItem(1);

            final List<ItemStackRequestAction> actions = new ArrayList<>();
            actions.add(new ItemStackRequestAction.CraftRecipeOptionalAction(0, 0)); //TODO: This needs more debugging

            // TODO: Recipe Check
            if (!this.getItem(2).isEmpty()) {
                actions.add(new ItemStackRequestAction.ConsumeAction(1, /*Probably needs an algo*/ new ItemStackRequestSlotInfo(
                        new FullContainerName(ContainerEnumName.AnvilMaterialContainer, null),
                        (byte) 2,
                        this.getItem(2).netId()
                )));
            }

            actions.add(new ItemStackRequestAction.ConsumeAction(1, new ItemStackRequestSlotInfo(
                    new FullContainerName(ContainerEnumName.AnvilInputContainer, null),
                    (byte) 1,
                    this.getItem(1).netId()
            )));
            actions.add(new ItemStackRequestAction.PlaceAction(1, /*Probably needs an algo*/ new ItemStackRequestSlotInfo(
                    new FullContainerName(ContainerEnumName.CreatedOutputContainer, null),
                    (byte) 50,
                    requestId
            ), new ItemStackRequestSlotInfo(// TODO: Shift click
                    new FullContainerName(ContainerEnumName.CursorContainer, null),
                    (byte) 0,
                    0 // Will be filled by the server
            )));

            final List<String> filterStrings = new ArrayList<>();
            TextProcessingEventOrigin origin = TextProcessingEventOrigin.unknown;
            if (!this.getRenameText().isEmpty()) {
                filterStrings.add(this.getRenameText());
                origin = TextProcessingEventOrigin.AnvilText;

                //TODO: Set the renamed item name
                //resultItem.
            }

            final ItemStackRequestInfo request = new ItemStackRequestInfo(
                    requestId,
                    actions,
                    filterStrings,
                    origin
            );

            this.setItem(1, BedrockItem.empty()); // Clear the input item
            this.setItem(2, BedrockItem.empty()); // Clear the material item (TODO: May need an algo)
            inventoryTracker.getHudContainer().setItem(0, resultItem);

            inventoryRequestTracker.addRequest(new InventoryRequestStorage(request, revision, prevCursorContainer, prevContainers)); // Store the request to track it later
            PlayerActionPacketFactory.sendBedrockInventoryRequest(user, new ItemStackRequestInfo[]{request});
        } else {
            return false;
        }

        return super.handleClick(revision, javaSlot, button, action);
    }

    public String getRenameText() {
        return this.renameText;
    }

    public void setRenameText(final String renameText) {
        this.renameText = renameText;
    }

}

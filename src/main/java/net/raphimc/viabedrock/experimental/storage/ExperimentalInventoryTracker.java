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
package net.raphimc.viabedrock.experimental.storage;

import com.viaversion.viaversion.api.connection.StoredObject;
import com.viaversion.viaversion.api.connection.UserConnection;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.api.util.PacketFactory;
import net.raphimc.viabedrock.experimental.ExperimentalPacketFactory;
import net.raphimc.viabedrock.experimental.model.container.ExperimentalContainer;
import net.raphimc.viabedrock.experimental.model.container.dynamic.BundleContainer;
import net.raphimc.viabedrock.experimental.model.inventory.ItemStackRequestAction;
import net.raphimc.viabedrock.experimental.model.inventory.ItemStackRequestInfo;
import net.raphimc.viabedrock.experimental.model.inventory.ItemStackRequestSlotInfo;
import net.raphimc.viabedrock.experimental.model.container.player.ArmorContainer;
import net.raphimc.viabedrock.experimental.model.container.player.HudContainer;
import net.raphimc.viabedrock.experimental.model.container.player.InventoryContainer;
import net.raphimc.viabedrock.experimental.model.container.player.OffhandContainer;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerEnumName;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerID;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerType;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.TextProcessingEventOrigin;
import net.raphimc.viabedrock.protocol.model.BedrockItem;
import net.raphimc.viabedrock.protocol.model.FullContainerName;
import net.raphimc.viabedrock.protocol.model.Position3f;
import net.raphimc.viabedrock.protocol.rewriter.BlockStateRewriter;
import net.raphimc.viabedrock.protocol.rewriter.ItemRewriter;
import net.raphimc.viabedrock.protocol.storage.ChunkTracker;
import net.raphimc.viabedrock.protocol.storage.EntityTracker;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

public class ExperimentalInventoryTracker extends StoredObject {

    private final InventoryContainer inventoryContainer = new InventoryContainer(this.user());
    private final OffhandContainer offhandContainer = new OffhandContainer(this.user());
    private final ArmorContainer armorContainer = new ArmorContainer(this.user());
    private final HudContainer hudContainer = new HudContainer(this.user());
    private final Map<FullContainerName, BundleContainer> dynamicContainerRegistry = new HashMap<>();

    private ExperimentalContainer currentContainer = null;
    private ExperimentalContainer pendingCloseContainer = null;

    public ExperimentalInventoryTracker(final UserConnection user) {
        super(user);
    }

    public ExperimentalContainer getContainerClientbound(final byte containerId, final FullContainerName containerName, final BedrockItem storageItem) {
        if (containerId == this.inventoryContainer.containerId()) return this.inventoryContainer;
        if (containerId == this.offhandContainer.containerId()) return this.offhandContainer;
        if (containerId == this.armorContainer.containerId()) return this.armorContainer;
        if (containerId == this.hudContainer.containerId()) return this.hudContainer;
        if (containerId == ContainerID.CONTAINER_ID_REGISTRY.getValue() && containerName != null && containerName.name() == ContainerEnumName.DynamicContainer) {
            if (storageItem == null) {
                return null;
            }
            final String itemTag = BedrockProtocol.MAPPINGS.getBedrockCustomItemTags().getOrDefault(this.user().get(ItemRewriter.class).getItems().inverse().get(storageItem.identifier()), "");
            if (!storageItem.isEmpty() && itemTag.equals("bundle")) {
                return this.dynamicContainerRegistry.computeIfAbsent(containerName, cn -> new BundleContainer(this.user(), cn));
            } else {
                return null;
            }
        }
        if (this.currentContainer != null && containerId == this.currentContainer.containerId()) {
            return this.currentContainer;
        }
        return null;
    }

    public ExperimentalContainer getContainerServerbound(final byte containerId) {
        if (this.currentContainer != null && containerId == this.currentContainer.javaContainerId()) {
            return this.currentContainer;
        }
        if (containerId == this.inventoryContainer.javaContainerId()) {
            return this.inventoryContainer;
        }
        return null;
    }

    public ExperimentalContainer getContainerFromName(final FullContainerName containerName, int slot) {
        if (containerName == null) {
            return null;
        }
        if (this.currentContainer != null) {
            if (containerName.name() == ContainerEnumName.CreatedOutputContainer) {
                return this.currentContainer;
            }
            try {
                if (containerName.equals(this.currentContainer.getFullContainerName(slot))) {
                    return this.currentContainer;
                }
            } catch (IllegalArgumentException ignored) {
                // The response slot may belong to a different container namespace.
            }
        }
        if (containerName.name() == ContainerEnumName.InventoryContainer) return this.inventoryContainer;
        if (containerName.name() == ContainerEnumName.HotbarContainer) return this.inventoryContainer;
        if (containerName.name() == ContainerEnumName.OffhandContainer) return this.offhandContainer;
        if (containerName.name() == ContainerEnumName.ArmorContainer) return this.armorContainer;
        if (containerName.name() == ContainerEnumName.CursorContainer) return this.hudContainer;
        if (containerName.name() == ContainerEnumName.CraftingInputContainer) return this.hudContainer;
        if (containerName.name() == ContainerEnumName.CraftingOutputPreviewContainer) return this.hudContainer;
        if (containerName.name() == ContainerEnumName.CreatedOutputContainer) return this.hudContainer;
        if (containerName.name() == ContainerEnumName.DynamicContainer) {
            return this.dynamicContainerRegistry.get(containerName);
        }
        return null;
    }

    public BundleContainer getDynamicContainer(final FullContainerName containerName) {
        return this.dynamicContainerRegistry.get(containerName);
    }

    public void removeDynamicContainer(final FullContainerName containerName) {
        this.dynamicContainerRegistry.remove(containerName);
    }

    public void markPendingClose(final ExperimentalContainer container) {
        if (container == null) {
            return;
        }
        if (this.currentContainer == container) {
            this.currentContainer = null;
        }
        if (this.pendingCloseContainer != null) {
            return;
        }
        this.pendingCloseContainer = container;
    }

    public void setCurrentContainerClosed(final boolean serverInitiated) {
        final ExperimentalContainer closingContainer = this.currentContainer != null ? this.currentContainer : this.pendingCloseContainer;
        if (serverInitiated && closingContainer != null) {
            PacketFactory.sendBedrockContainerClose(this.user(), closingContainer.containerId(), ContainerType.NONE);
        }
        this.returnCursorItem();
        this.currentContainer = null;
        this.pendingCloseContainer = null;
    }

    private void returnCursorItem() {
        final BedrockItem cursorItem = this.hudContainer.getItem(0);
        if (cursorItem.isEmpty()) {
            return;
        }

        final InventoryRequestTracker inventoryRequestTracker = this.user().get(InventoryRequestTracker.class);
        final int requestId = inventoryRequestTracker.nextRequestId();
        final List<ItemStackRequestAction> actions = new ArrayList<>();
        final ExperimentalContainer previousInventory = this.inventoryContainer.copy();
        final ExperimentalContainer previousCursor = this.hudContainer.copy();

        BedrockItem remaining = cursorItem.copy();
        for (boolean mergePass : new boolean[]{true, false}) {
            for (int slot = this.inventoryContainer.size() - 1; slot >= 0 && !remaining.isEmpty(); slot--) {
                final BedrockItem destinationItem = this.inventoryContainer.getItem(slot);
                final int maxStackSize = this.user().get(ItemRewriter.class).maxStackSize(cursorItem);
                if (mergePass) {
                    if (destinationItem.isEmpty() || destinationItem.isDifferent(cursorItem) || destinationItem.amount() >= maxStackSize) {
                        continue;
                    }
                } else if (!destinationItem.isEmpty()) {
                    continue;
                }

                final int amountToMove = mergePass
                        ? Math.min(remaining.amount(), maxStackSize - destinationItem.amount())
                        : Math.min(remaining.amount(), maxStackSize);
                if (amountToMove <= 0) {
                    continue;
                }

                actions.add(new ItemStackRequestAction.PlaceAction(
                        amountToMove,
                        ItemStackRequestSlotInfo.cursor(this.stackNetId(cursorItem)),
                        this.inventoryContainer.stackRequestSlotInfo(slot, this.stackNetId(destinationItem))
                ));

                if (destinationItem.isEmpty()) {
                    final BedrockItem newDestinationItem = cursorItem.copy();
                    newDestinationItem.setAmount(amountToMove);
                    this.inventoryContainer.setItem(slot, newDestinationItem);
                } else {
                    final BedrockItem newDestinationItem = destinationItem.copy();
                    newDestinationItem.setAmount(destinationItem.amount() + amountToMove);
                    this.inventoryContainer.setItem(slot, newDestinationItem);
                }
                remaining = this.itemAfterRemovingAmount(remaining, amountToMove);
            }
        }

        if (!remaining.isEmpty()) {
            actions.add(new ItemStackRequestAction.DropAction(
                    remaining.amount(),
                    ItemStackRequestSlotInfo.cursor(this.stackNetId(cursorItem)),
                    false
            ));
        }

        this.hudContainer.setItem(0, BedrockItem.empty());
        if (actions.isEmpty()) {
            return;
        }

        final ItemStackRequestInfo request = new ItemStackRequestInfo(requestId, actions, List.of(), TextProcessingEventOrigin.unknown);
        inventoryRequestTracker.addRequest(new InventoryRequestStorage(request, 0, previousCursor, List.of(previousInventory)));
        ExperimentalPacketFactory.sendBedrockInventoryRequest(this.user(), new ItemStackRequestInfo[]{request});
        ExperimentalPacketFactory.sendJavaContainerSetContent(this.user(), this.inventoryContainer);
    }

    private int stackNetId(final BedrockItem item) {
        return item.netId() != null ? item.netId() : 0;
    }

    private BedrockItem itemAfterRemovingAmount(final BedrockItem item, final int amountToRemove) {
        if (amountToRemove >= item.amount()) {
            return BedrockItem.empty();
        }

        final BedrockItem copy = item.copy();
        copy.setAmount(item.amount() - amountToRemove);
        return copy;
    }

    public void tick() {
        if (this.currentContainer != null && this.currentContainer.position() != null) {
            if (this.currentContainer.type() == ContainerType.INVENTORY) return;

            final ChunkTracker chunkTracker = this.user().get(ChunkTracker.class);
            final BlockStateRewriter blockStateRewriter = this.user().get(BlockStateRewriter.class);
            final int blockState = chunkTracker.getBlockState(this.currentContainer.position());
            final String tag = blockStateRewriter.tag(blockState);
            if (!this.currentContainer.isValidBlockTag(tag)) {
                ViaBedrock.getPlatform().getLogger().log(Level.INFO, "Closing " + this.currentContainer.type() + " because block state is not valid for container type: " + blockState);
                this.forceCloseCurrentContainer();
                return;
            }

            final EntityTracker entityTracker = this.user().get(EntityTracker.class);
            final Position3f containerPosition = new Position3f(this.currentContainer.position().x() + 0.5F, this.currentContainer.position().y() + 0.5F, this.currentContainer.position().z() + 0.5F);
            final Position3f playerPosition = entityTracker.getClientPlayer().position();
            if (playerPosition.distanceTo(containerPosition) > 6) {
                ViaBedrock.getPlatform().getLogger().log(Level.INFO, "Closing " + this.currentContainer.type() + " because player is too far away (" + playerPosition.distanceTo(containerPosition) + " > 6)");
                this.forceCloseCurrentContainer();
            }
        }

        // TODO: Drop Cursor item if no container is open
    }

    public boolean isContainerOpen() {
        return this.currentContainer != null || this.pendingCloseContainer != null;
    }

    public InventoryContainer getInventoryContainer() {
        return this.inventoryContainer;
    }

    public OffhandContainer getOffhandContainer() {
        return this.offhandContainer;
    }

    public ArmorContainer getArmorContainer() {
        return this.armorContainer;
    }

    public HudContainer getHudContainer() {
        return this.hudContainer;
    }

    public ExperimentalContainer getCurrentContainer() {
        return this.currentContainer;
    }

    public void setCurrentContainer(final ExperimentalContainer container) {
        if (this.isContainerOpen()) {
            throw new IllegalStateException("There is already another container open");
        }
        this.currentContainer = container;
    }

    public ExperimentalContainer getPendingCloseContainer() {
        return this.pendingCloseContainer;
    }

    private void forceCloseCurrentContainer() {
        this.markPendingClose(this.currentContainer);
        PacketFactory.sendJavaContainerClose(this.user(), this.pendingCloseContainer.javaContainerId());
        PacketFactory.sendBedrockContainerClose(this.user(), this.pendingCloseContainer.containerId(), ContainerType.NONE);
    }

}

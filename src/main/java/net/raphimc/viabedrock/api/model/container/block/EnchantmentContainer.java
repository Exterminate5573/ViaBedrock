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

import com.viaversion.nbt.tag.CompoundTag;

import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.minecraft.BlockPosition;
import com.viaversion.viaversion.libs.mcstructs.text.TextComponent;

import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.api.model.container.Container;
import net.raphimc.viabedrock.api.util.PacketFactory;
import net.raphimc.viabedrock.api.util.RegistryUtil;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.PlayerActionPacketFactory;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.*;
import net.raphimc.viabedrock.protocol.data.generated.bedrock.CustomBlockTags;
import net.raphimc.viabedrock.protocol.model.BedrockItem;
import net.raphimc.viabedrock.protocol.model.FullContainerName;
import net.raphimc.viabedrock.protocol.model.inventory.ItemStackRequestAction;
import net.raphimc.viabedrock.protocol.model.inventory.ItemStackRequestInfo;
import net.raphimc.viabedrock.protocol.model.inventory.ItemStackRequestSlotInfo;
import net.raphimc.viabedrock.protocol.model.recipe.EnchantData;
import net.raphimc.viabedrock.protocol.storage.EntityTracker;
import net.raphimc.viabedrock.protocol.storage.InventoryRequestStorage;
import net.raphimc.viabedrock.protocol.storage.InventoryRequestTracker;
import net.raphimc.viabedrock.protocol.storage.InventoryTracker;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

public class EnchantmentContainer extends Container {

    List<EnchantData> data = new ArrayList<>();

    public EnchantmentContainer(final UserConnection user, final byte containerId, final TextComponent title, final BlockPosition position) {
        super(user, containerId, ContainerType.ENCHANTMENT, title, position, 2, CustomBlockTags.ENCHANTING_TABLE);
    }

    @Override
    public FullContainerName getFullContainerName(final int slot) {
        return switch (slot) {
            case 14 -> new FullContainerName(ContainerEnumName.EnchantingInputContainer, null);
            case 15 -> new FullContainerName(ContainerEnumName.EnchantingMaterialContainer, null);
            default -> throw new IllegalArgumentException("Invalid slot for Enchantment Container: " + slot);
        };
    }

    @Override
    public int javaSlot(final int slot) {
        return switch (slot) {
            case 14 -> 0;
            case 15 -> 1;
            default -> super.javaSlot(slot);
        };
    }

    @Override
    public int bedrockSlot(final int slot) {
        return switch (slot) {
            case 0 -> 14;
            case 1 -> 15;
            default -> super.bedrockSlot(slot);
        };
    }

    @Override
    public BedrockItem getItem(int bedrockSlot) {
        // Fix magic offset
        bedrockSlot -= 14;
        return this.items[bedrockSlot];
    }

    @Override
    public boolean setItem(final int bedrockSlot, final BedrockItem item) {
        // Fix magic offset
        return super.setItem(bedrockSlot - 14, item);
    }

    @Override
    public boolean handleButtonClick(final int button) {
        if (button < 0 || button > 2 || button >= this.data.size()) {
            ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Received invalid enchantment option button click: " + button);
            return false;
        }

        final InventoryTracker inventoryTracker = user.get(InventoryTracker.class);
        final InventoryRequestTracker inventoryRequestTracker = user.get(InventoryRequestTracker.class);
        final EntityTracker entityTracker = user.get(EntityTracker.class);

        final List<Container> prevContainers = new ArrayList<>();
        final Container prevCursorContainer = inventoryTracker.getHudContainer().copy();

        prevContainers.add(this.copy());

        final int reqId = inventoryRequestTracker.nextRequestId();

        final List<ItemStackRequestAction> actions = new ArrayList<>();

        final ItemStackRequestAction craftAction = new ItemStackRequestAction.CraftRecipeAction(this.data.get(button).netId(), 1);
        final ItemStackRequestAction consumeAction = new ItemStackRequestAction.ConsumeAction(1, new ItemStackRequestSlotInfo(
                this.getFullContainerName(14), (byte) 14, this.getItem(14).netId()
        ));
        final ItemStackRequestAction placeAction = new ItemStackRequestAction.PlaceAction(1,
                new ItemStackRequestSlotInfo(
                        new FullContainerName(ContainerEnumName.CreatedOutputContainer, null), (byte) 50, reqId
                ),
                new ItemStackRequestSlotInfo(
                        this.getFullContainerName(14), (byte) 14, reqId
                )
        );
        actions.add(craftAction);
        actions.add(consumeAction);
        actions.add(placeAction);

        if (entityTracker.getClientPlayer().gameType() == GameType.Survival || entityTracker.getClientPlayer().gameType() == GameType.Adventure) {
            final ItemStackRequestAction consumeAction2 = new ItemStackRequestAction.ConsumeAction(button + 1, new ItemStackRequestSlotInfo(
                    this.getFullContainerName(15), (byte) 15, this.getItem(15).netId()
            ));
            actions.add(consumeAction2);

            this.setItem(15, this.itemAfterRemovingAmount(this.getItem(15), button + 1));
        }

        final ItemStackRequestInfo request = new ItemStackRequestInfo(
                reqId,
                actions,
                List.of(),
                TextProcessingEventOrigin.unknown
        );

        inventoryRequestTracker.addRequest(new InventoryRequestStorage(request, 0, prevCursorContainer, prevContainers)); // Store the request to track it later
        PlayerActionPacketFactory.sendBedrockInventoryRequest(user, new ItemStackRequestInfo[] {request});

        return true;
    }

    public void setEnchantData(final List<EnchantData> data) {
        this.data = data;

        // Send to java client
        for (int i = 0; i < Math.min(this.data.size(), 3); i++) {
            final EnchantData d = this.data.get(i);
            PacketFactory.sendJavaContainerProperties(this.user, this, (short) i, (short) d.cost());

            final String javaEnchant = BedrockProtocol.MAPPINGS.getBedrockToJavaEnchantments().get(d.type());
            // Update the java item with the enchantment
            if (javaEnchant != null) {
                final CompoundTag enchantmentsRegistry = (CompoundTag) BedrockProtocol.MAPPINGS.getJavaRegistries().get("minecraft:enchantment");
                final CompoundTag enchantmentEntry = (CompoundTag) enchantmentsRegistry.get(javaEnchant);
                if (enchantmentEntry == null) {
                    ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Enchantment entry is null for enchantment " + javaEnchant);
                } else {
                    final int javaId = RegistryUtil.getRegistryIndex(enchantmentsRegistry, enchantmentEntry);
                    PacketFactory.sendJavaContainerProperties(this.user, this, (short) (i + 4), (short) javaId);
                }
            } else {
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Unknown enchantment with id " + d.type() + " and level " + d.level());
            }

            PacketFactory.sendJavaContainerProperties(this.user, this, (short) (i + 7), (short) d.level());
        }

    }

}

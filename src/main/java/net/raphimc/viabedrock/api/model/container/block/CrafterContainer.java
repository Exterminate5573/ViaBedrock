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
package net.raphimc.viabedrock.api.model.container.block;

import com.viaversion.nbt.tag.CompoundTag;
import com.viaversion.nbt.tag.ShortTag;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.minecraft.BlockPosition;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.libs.mcstructs.text.TextComponent;
import com.viaversion.viaversion.protocols.v26_2to26_3.packet.ClientboundPackets26_3;

import net.raphimc.viabedrock.api.model.container.Container;
import net.raphimc.viabedrock.protocol.BedrockProtocol;

import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerEnumName;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerType;
import net.raphimc.viabedrock.protocol.data.enums.java.generated.ContainerInput;
import net.raphimc.viabedrock.protocol.data.generated.bedrock.CustomBlockTags;
import net.raphimc.viabedrock.protocol.model.FullContainerName;
import net.raphimc.viabedrock.protocol.storage.ChunkTracker;

public class CrafterContainer extends Container {

    public CrafterContainer(final UserConnection user, final byte containerId, final TextComponent title, final BlockPosition position) {
        super(user, containerId, ContainerType.CRAFTER, title, position, 9, CustomBlockTags.CRAFTER);

        final boolean[] disabledSlots = this.getCrafterMetadata();
        for (short i = 0; i < 9; i++) {
            final boolean disabled = disabledSlots[i];

            final PacketWrapper setData = PacketWrapper.create(ClientboundPackets26_3.CONTAINER_SET_DATA, user);
            setData.write(Types.VAR_INT, (int) this.javaContainerId());
            setData.write(Types.SHORT, i);
            setData.write(Types.SHORT, (short) (disabled ? 1 : 0));
            setData.scheduleSend(BedrockProtocol.class);
        }
    }

    @Override
    public FullContainerName getFullContainerName(final int slot) {
        return new FullContainerName(ContainerEnumName.AnvilInputContainer, null); // Bedrock moment, from testing they send AnvilInput
    }

    @Override
    public boolean handleClick(final int revision, final short javaSlot, final byte button, final ContainerInput action) {
        if (javaSlot >= 0 && javaSlot <= 8) {

            // TODO: Minecraft wiki says it gets handled here but java currently sends a CONTAINER_SLOT_STATE_CHANGED packet which we can use instead

            return true;
        } else if (javaSlot == 45) {
            return true;
        }
        return super.handleClick(revision, javaSlot, button, action);
    }

    private boolean[] getCrafterMetadata() {
        final ChunkTracker ct = this.user.get(ChunkTracker.class);

        final CompoundTag tag = ct.getBlockEntity(position).tag();
        if (tag == null || !tag.contains("disabled_slots")) {
            return new boolean[9];
        }

        final boolean[] disabledSlots = new boolean[9];
        final int mask = ((ShortTag) tag.get("disabled_slots")).asInt();
        for (int i = 0; i < 9; i++) {
            disabledSlots[i] = (mask & (1 << i)) != 0;
        }

        return disabledSlots;
    }

}

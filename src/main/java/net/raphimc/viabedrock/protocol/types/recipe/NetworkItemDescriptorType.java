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
package net.raphimc.viabedrock.protocol.types.recipe;

import com.viaversion.viaversion.api.type.Type;
import io.netty.buffer.ByteBuf;

import net.raphimc.viabedrock.protocol.model.recipe.ItemDescriptor;
import net.raphimc.viabedrock.protocol.model.recipe.ItemDescriptorType;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;

public class NetworkItemDescriptorType extends Type<ItemDescriptor> {

    public NetworkItemDescriptorType() {
        super(ItemDescriptor.class);
    }

    @Override
    public ItemDescriptor read(final ByteBuf buffer) {
        final ItemDescriptorType type = ItemDescriptorType.getByValue(buffer.readByte());
        final ItemDescriptor result = switch (type) {
            case COMPLEX_ALIAS -> {
                final String name = BedrockTypes.STRING.read(buffer);
                yield new ItemDescriptor.ComplexAliasDescriptor(name);
            }
            case DEFAULT -> {
                final int itemId = buffer.readShortLE();
                final int auxValue = itemId != 0 ? buffer.readShortLE() : 0;
                yield new ItemDescriptor.DefaultDescriptor(itemId, auxValue);
            }
            case DEFERRED -> {
                final String fullName = BedrockTypes.STRING.read(buffer);
                final int auxValue = buffer.readIntLE();
                yield new ItemDescriptor.DeferredDescriptor(fullName, auxValue);
            }
            case INVALID -> new ItemDescriptor.InvalidDescriptor();
            case ITEM_TAG -> {
                final String itemTag = BedrockTypes.STRING.read(buffer);
                yield new ItemDescriptor.ItemTagDescriptor(itemTag);
            }
            case MOLANG -> {
                final String tagExpression = BedrockTypes.STRING.read(buffer);
                final int molangVersion = buffer.readUnsignedByte();
                yield new ItemDescriptor.MolangDescriptor(tagExpression, molangVersion);
            }
        };

        final int amount = BedrockTypes.VAR_INT.read(buffer);

        return result.withAmount(amount);
    }

    @Override
    public void write(final ByteBuf buffer, final ItemDescriptor value) {
        buffer.writeByte(value.getType().getValue());
        switch (value.getType()) {
            case COMPLEX_ALIAS -> {
                final ItemDescriptor.ComplexAliasDescriptor descriptor = (ItemDescriptor.ComplexAliasDescriptor) value;
                BedrockTypes.STRING.write(buffer, descriptor.name());
            }
            case DEFAULT -> {
                final ItemDescriptor.DefaultDescriptor descriptor = (ItemDescriptor.DefaultDescriptor) value;
                buffer.writeShortLE(descriptor.itemId());
                if (descriptor.itemId() != 0) {
                    buffer.writeShortLE(descriptor.auxValue());
                }
            }
            case DEFERRED -> {
                final ItemDescriptor.DeferredDescriptor descriptor = (ItemDescriptor.DeferredDescriptor) value;
                BedrockTypes.STRING.write(buffer, descriptor.fullName());
                buffer.writeIntLE(descriptor.auxValue());
            }
            case INVALID -> {
                // Nothing to write
            }
            case ITEM_TAG -> {
                final ItemDescriptor.ItemTagDescriptor descriptor = (ItemDescriptor.ItemTagDescriptor) value;
                BedrockTypes.STRING.write(buffer, descriptor.itemTag());
            }
            case MOLANG -> {
                final ItemDescriptor.MolangDescriptor descriptor = (ItemDescriptor.MolangDescriptor) value;
                BedrockTypes.STRING.write(buffer, descriptor.tagExpression());
                buffer.writeByte(descriptor.molangVersion());
            }
        }
        BedrockTypes.VAR_INT.write(buffer, value.amount());
    }

}

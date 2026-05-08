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
import com.viaversion.viaversion.libs.fastutil.ints.Int2ObjectMap;
import com.viaversion.viaversion.libs.fastutil.ints.Int2ObjectOpenHashMap;
import net.raphimc.viabedrock.protocol.model.BedrockItem;

import java.util.Objects;

public class CreativeContentStorage extends StoredObject {

    private final Int2ObjectMap<BedrockItem> creativeItems = new Int2ObjectOpenHashMap<>();

    public CreativeContentStorage(final UserConnection user) {
        super(user);
    }

    public void clear() {
        this.creativeItems.clear();
    }

    public void addCreativeItem(final int creativeNetworkId, final BedrockItem item) {
        if (!item.isEmpty()) {
            this.creativeItems.put(creativeNetworkId, item.copy());
        }
    }

    public BedrockItem creativeItem(final int creativeNetworkId) {
        final BedrockItem item = this.creativeItems.get(creativeNetworkId);
        return item != null ? item.copy() : BedrockItem.empty();
    }

    public Integer creativeNetworkId(final BedrockItem requestedItem) {
        if (requestedItem.isEmpty()) {
            return null;
        }

        Integer fallback = null;
        for (Int2ObjectMap.Entry<BedrockItem> entry : this.creativeItems.int2ObjectEntrySet()) {
            final BedrockItem creativeItem = entry.getValue();
            if (sameItem(creativeItem, requestedItem, true)) {
                return entry.getIntKey();
            }
            if (fallback == null && sameItem(creativeItem, requestedItem, false)) {
                fallback = entry.getIntKey();
            }
        }
        return fallback;
    }

    private static boolean sameItem(final BedrockItem creativeItem, final BedrockItem requestedItem, final boolean strictBlockRuntimeId) {
        if (creativeItem.identifier() != requestedItem.identifier() || creativeItem.data() != requestedItem.data()) {
            return false;
        }
        if (strictBlockRuntimeId && creativeItem.blockRuntimeId() != requestedItem.blockRuntimeId()) {
            return false;
        }
        return Objects.equals(creativeItem.tag(), requestedItem.tag());
    }

}

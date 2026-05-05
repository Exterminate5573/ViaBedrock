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
package net.raphimc.viabedrock.experimental.model.recipe;

import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.minecraft.item.Item;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.util.Key;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.model.BedrockItem;
import net.raphimc.viabedrock.protocol.rewriter.ItemRewriter;

import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public interface ItemDescriptor {

    ItemDescriptorType getType();

    boolean matchesItem(final UserConnection user, BedrockItem item);

    default int amount() {
        return 1;
    }

    ItemDescriptor withAmount(int amount);

    static boolean matchesAuxValue(final int auxValue, final BedrockItem item) {
        return auxValue == -1 || auxValue == Short.MAX_VALUE || auxValue == item.data() || auxValue == item.auxValue();
    }

    static Set<String> itemTags(final UserConnection user, final BedrockItem item) {
        if (item.isEmpty()) {
            return Set.of();
        }

        final ItemRewriter itemRewriter = user.get(ItemRewriter.class);
        final String itemName = itemRewriter.getItems().inverse().get(item.identifier());
        final Set<String> tags = BedrockProtocol.MAPPINGS.getBedrockItemTags().get(itemName);
        return tags == null ? Set.of() : tags;
    }

    static boolean writeFirstMatchingJavaItemData(final PacketWrapper packet, final UserConnection user, final Predicate<BedrockItem> predicate) {
        final ItemRewriter itemRewriter = user.get(ItemRewriter.class);
        for (Map.Entry<String, Integer> entry : itemRewriter.getItems().entrySet()) {
            final BedrockItem item = new BedrockItem(entry.getValue());
            if (!predicate.test(item)) {
                continue;
            }

            if (writeJavaItemData(packet, itemRewriter, item)) {
                return true;
            }
        }
        return false;
    }

    static boolean writeFirstNamedJavaItemData(final PacketWrapper packet, final UserConnection user, final Set<String> names) {
        final ItemRewriter itemRewriter = user.get(ItemRewriter.class);
        for (String name : names) {
            final Integer itemId = itemRewriter.getItems().get(Key.namespaced(name));
            if (itemId != null && writeJavaItemData(packet, itemRewriter, new BedrockItem(itemId))) {
                return true;
            }
        }
        return false;
    }

    static boolean writeJavaItemData(final PacketWrapper packet, final ItemRewriter itemRewriter, final BedrockItem bedrockItem) {
        final Item javaItem = itemRewriter.javaItem(bedrockItem);
        if (javaItem == null || javaItem.isEmpty()) {
            return false;
        }

        packet.write(Types.VAR_INT, BedrockProtocol.MAPPINGS.getJavaSlotDisplayId("minecraft:item")); // Slot Display Type
        packet.write(Types.VAR_INT, javaItem.identifier()); // Item ID
        return true;
    }

    default void writeJavaIngredientData(final PacketWrapper packet, final UserConnection user) {
        throw new UnsupportedOperationException("Not implemented for " + getType());
    }

    record ComplexAliasDescriptor(String name, int amount) implements ItemDescriptor {
        public ComplexAliasDescriptor(final String name) {
            this(name, 1);
        }

        @Override
        public ItemDescriptorType getType() {
            return ItemDescriptorType.COMPLEX_ALIAS;
        }

        @Override
        public boolean matchesItem(UserConnection user, BedrockItem item) {
            if (item.isEmpty()) {
                return false;
            }

            final ItemRewriter itemRewriter = user.get(ItemRewriter.class);
            final String itemName = itemRewriter.getItems().inverse().get(item.identifier());
            return name.equals(itemName) || ItemDescriptor.itemTags(user, item).contains(name);
        }

        @Override
        public void writeJavaIngredientData(final PacketWrapper packet, final UserConnection user) {
            final ItemRewriter itemRewriter = user.get(ItemRewriter.class);
            final Integer itemId = itemRewriter.getItems().get(Key.namespaced(name));
            if (itemId == null) {
                if (!ItemDescriptor.writeFirstMatchingJavaItemData(packet, user, item -> ItemDescriptor.itemTags(user, item).contains(name))) {
                    packet.write(Types.VAR_INT, BedrockProtocol.MAPPINGS.getJavaSlotDisplayId("minecraft:empty")); // Slot Display Type
                }
                return;
            }

            if (!ItemDescriptor.writeJavaItemData(packet, itemRewriter, new BedrockItem(itemId))) {
                packet.write(Types.VAR_INT, BedrockProtocol.MAPPINGS.getJavaSlotDisplayId("minecraft:empty")); // Slot Display Type
            }
        }

        @Override
        public ItemDescriptor withAmount(final int amount) {
            return new ComplexAliasDescriptor(this.name, amount);
        }
    }

    record DefaultDescriptor(int itemId, int auxValue, int amount) implements ItemDescriptor {
        public DefaultDescriptor(final int itemId, final int auxValue) {
            this(itemId, auxValue, 1);
        }

        @Override
        public ItemDescriptorType getType() {
            return ItemDescriptorType.DEFAULT;
        }

        @Override
        public boolean matchesItem(UserConnection user, BedrockItem item) {
            return ((itemId == -1 || itemId == 0) && item.isEmpty())
                    || (!item.isEmpty() && item.identifier() == itemId && ItemDescriptor.matchesAuxValue(auxValue, item));
        }

        @Override
        public void writeJavaIngredientData(final PacketWrapper packet, final UserConnection user) {
            ItemRewriter itemRewriter = user.get(ItemRewriter.class);
            Item javaItem = itemRewriter.javaItem(new BedrockItem(itemId));
            if (javaItem == null) {
                packet.write(Types.VAR_INT, BedrockProtocol.MAPPINGS.getJavaSlotDisplayId("minecraft:empty")); // Slot Display Type
                return;
            }

            packet.write(Types.VAR_INT, BedrockProtocol.MAPPINGS.getJavaSlotDisplayId("minecraft:item")); // Slot Display Type
            packet.write(Types.VAR_INT, javaItem.identifier()); // Item ID
        }

        @Override
        public ItemDescriptor withAmount(final int amount) {
            return new DefaultDescriptor(this.itemId, this.auxValue, amount);
        }

    }

    record DeferredDescriptor(String fullName, int auxValue, int amount) implements ItemDescriptor {
        public DeferredDescriptor(final String fullName, final int auxValue) {
            this(fullName, auxValue, 1);
        }

        @Override
        public ItemDescriptorType getType() {
            return ItemDescriptorType.DEFERRED;
        }

        @Override
        public boolean matchesItem(UserConnection user, BedrockItem item) {
            if (item.isEmpty()) {
                return false;
            }

            final ItemRewriter itemRewriter = user.get(ItemRewriter.class);
            final Integer itemId = itemRewriter.getItems().get(Key.namespaced(fullName));
            return itemId != null && item.identifier() == itemId && ItemDescriptor.matchesAuxValue(auxValue, item);
        }

        @Override
        public void writeJavaIngredientData(final PacketWrapper packet, final UserConnection user) {
            ItemRewriter itemRewriter = user.get(ItemRewriter.class);
            Integer itemId = itemRewriter.getItems().get(Key.namespaced(fullName));
            if (itemId == null) {
                packet.write(Types.VAR_INT, BedrockProtocol.MAPPINGS.getJavaSlotDisplayId("minecraft:empty")); // Slot Display Type
                return;
            }

            Item javaItem = itemRewriter.javaItem(new BedrockItem(itemId));
            if (javaItem == null) {
                packet.write(Types.VAR_INT, BedrockProtocol.MAPPINGS.getJavaSlotDisplayId("minecraft:empty")); // Slot Display Type
                return;
            }

            packet.write(Types.VAR_INT, BedrockProtocol.MAPPINGS.getJavaSlotDisplayId("minecraft:item")); // Slot Display Type
            packet.write(Types.VAR_INT, javaItem.identifier()); // Item ID
        }

        @Override
        public ItemDescriptor withAmount(final int amount) {
            return new DeferredDescriptor(this.fullName, this.auxValue, amount);
        }

    }

    record InvalidDescriptor() implements ItemDescriptor {
        @Override
        public ItemDescriptorType getType() {
            return ItemDescriptorType.INVALID;
        }

        @Override
        public boolean matchesItem(UserConnection user, BedrockItem item) {
            return item.isEmpty();
        }

        @Override
        public void writeJavaIngredientData(final PacketWrapper packet, final UserConnection user) {
            packet.write(Types.VAR_INT, BedrockProtocol.MAPPINGS.getJavaSlotDisplayId("minecraft:empty")); // Slot Display Type
        }

        @Override
        public ItemDescriptor withAmount(final int amount) {
            return this;
        }

    }

    record ItemTagDescriptor(String itemTag, int amount) implements ItemDescriptor {
        public ItemTagDescriptor(final String itemTag) {
            this(itemTag, 1);
        }

        @Override
        public ItemDescriptorType getType() {
            return ItemDescriptorType.ITEM_TAG;
        }

        @Override
        public boolean matchesItem(UserConnection user, BedrockItem item) {
            return ItemDescriptor.itemTags(user, item).contains(itemTag);
        }

        @Override
        public void writeJavaIngredientData(final PacketWrapper packet, final UserConnection user) {
            if (!ItemDescriptor.writeFirstMatchingJavaItemData(packet, user, item -> ItemDescriptor.itemTags(user, item).contains(itemTag))) {
                packet.write(Types.VAR_INT, BedrockProtocol.MAPPINGS.getJavaSlotDisplayId("minecraft:empty")); // Slot Display Type
            }
        }

        @Override
        public ItemDescriptor withAmount(final int amount) {
            return new ItemTagDescriptor(this.itemTag, amount);
        }

    }

    record MolangDescriptor(String tagExpression, int molangVersion, int amount) implements ItemDescriptor {
        public MolangDescriptor(final String tagExpression, final int molangVersion) {
            this(tagExpression, molangVersion, 1);
        }

        private static final Pattern ANY_TAG = Pattern.compile("(?:q|query)\\.any_tag\\(([^)]*)\\)");
        private static final Pattern ALL_TAGS = Pattern.compile("(?:q|query)\\.all_tags\\(([^)]*)\\)");
        private static final Pattern ITEM_NAME_ANY = Pattern.compile("(?:q|query)\\.is_item_name_any\\(([^)]*)\\)");
        private static final Pattern QUOTED_ARGUMENT = Pattern.compile("'([^']+)'|\"([^\"]+)\"");

        @Override
        public ItemDescriptorType getType() {
            return ItemDescriptorType.MOLANG;
        }

        @Override
        public boolean matchesItem(UserConnection user, BedrockItem item) {
            if (item.isEmpty()) {
                return false;
            }

            final Set<String> tags = ItemDescriptor.itemTags(user, item);
            final Matcher anyTag = ANY_TAG.matcher(tagExpression);
            if (anyTag.find()) {
                for (String tag : readArguments(anyTag.group(1))) {
                    if (tags.contains(tag)) {
                        return true;
                    }
                }
                return false;
            }

            final Matcher allTags = ALL_TAGS.matcher(tagExpression);
            if (allTags.find()) {
                final Set<String> requiredTags = readArguments(allTags.group(1));
                return !requiredTags.isEmpty() && tags.containsAll(requiredTags);
            }

            final Matcher itemNameAny = ITEM_NAME_ANY.matcher(tagExpression);
            if (itemNameAny.find()) {
                final ItemRewriter itemRewriter = user.get(ItemRewriter.class);
                final String itemName = itemRewriter.getItems().inverse().get(item.identifier());
                final Set<String> names = readArguments(itemNameAny.group(1));
                return names.contains(itemName);
            }

            return false;
        }

        @Override
        public void writeJavaIngredientData(final PacketWrapper packet, final UserConnection user) {
            final Matcher itemNameAny = ITEM_NAME_ANY.matcher(tagExpression);
            if (itemNameAny.find() && ItemDescriptor.writeFirstNamedJavaItemData(packet, user, readArguments(itemNameAny.group(1)))) {
                return;
            }

            final Matcher anyTag = ANY_TAG.matcher(tagExpression);
            final Matcher allTags = ALL_TAGS.matcher(tagExpression);
            if ((anyTag.find() || allTags.find()) && ItemDescriptor.writeFirstMatchingJavaItemData(packet, user, item -> this.matchesItem(user, item))) {
                return;
            }

            packet.write(Types.VAR_INT, BedrockProtocol.MAPPINGS.getJavaSlotDisplayId("minecraft:empty")); // Slot Display Type
        }

        @Override
        public ItemDescriptor withAmount(final int amount) {
            return new MolangDescriptor(this.tagExpression, this.molangVersion, amount);
        }

        private static Set<String> readArguments(final String arguments) {
            final java.util.Set<String> values = new java.util.HashSet<>();
            final Matcher matcher = QUOTED_ARGUMENT.matcher(arguments);
            while (matcher.find()) {
                values.add(matcher.group(1) != null ? matcher.group(1) : matcher.group(2));
            }
            return values;
        }
    }

}

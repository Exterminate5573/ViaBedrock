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
package net.raphimc.viabedrock.protocol.rewriter;

import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import com.viaversion.nbt.tag.CompoundTag;
import com.viaversion.nbt.tag.Tag;
import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.connection.StoredObject;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.minecraft.data.StructuredDataContainer;
import com.viaversion.viaversion.api.minecraft.data.StructuredDataKey;
import com.viaversion.viaversion.api.minecraft.item.Item;
import com.viaversion.viaversion.api.minecraft.item.StructuredItem;
import com.viaversion.viaversion.api.minecraft.item.data.ItemModel;
import com.viaversion.viaversion.api.type.Type;
import com.viaversion.viaversion.libs.fastutil.ints.Int2ObjectMap;
import com.viaversion.viaversion.libs.fastutil.ints.Int2ObjectOpenHashMap;
import com.viaversion.viaversion.libs.fastutil.ints.IntSortedSet;
import com.viaversion.viaversion.util.Key;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.api.model.BlockState;
import net.raphimc.viabedrock.api.resourcepack.definition.ItemDefinitions;
import net.raphimc.viabedrock.api.util.TextUtil;
import net.raphimc.viabedrock.experimental.rewriter.ExperimentalItemRewriter;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.data.BedrockMappingData;
import net.raphimc.viabedrock.protocol.data.ProtocolConstants;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ItemVersion;
import net.raphimc.viabedrock.protocol.data.generated.bedrock.CustomItemTags;
import net.raphimc.viabedrock.protocol.model.BedrockItem;
import net.raphimc.viabedrock.protocol.model.ItemEntry;
import net.raphimc.viabedrock.protocol.rewriter.item.BundleItemRewriter;
import net.raphimc.viabedrock.protocol.rewriter.resourcepack.CustomAttachableResourceRewriter;
import net.raphimc.viabedrock.protocol.rewriter.resourcepack.CustomItemTextureResourceRewriter;
import net.raphimc.viabedrock.protocol.storage.ResourcePackStorage;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;
import net.raphimc.viabedrock.protocol.types.array.ArrayType;
import net.raphimc.viabedrock.protocol.types.item.BedrockItemType;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

public class ItemRewriter extends StoredObject {

    private static final Map<String, NbtRewriter> ITEM_NBT_REWRITERS = new HashMap<>();

    private final BiMap<String, Integer> items;
    private final Set<String> componentItems;
    private final Int2ObjectMap<IntSortedSet> blockItemValidBlockStates;
    private final Type<BedrockItem> itemType;
    private final Type<BedrockItem[]> itemArrayType;
    private final Type<BedrockItem> itemInstanceType;
    private final Type<BedrockItem[]> itemInstanceArrayType;
    private final Int2ObjectMap<BedrockItem> javaToBedrockItems = new Int2ObjectOpenHashMap<>();

    static {
        // TODO: Add missing item nbt rewriters
        ITEM_NBT_REWRITERS.put(CustomItemTags.BUNDLE, new BundleItemRewriter());
    }

    public ItemRewriter(final UserConnection user, final ItemEntry[] itemEntries) {
        super(user);

        this.items = HashBiMap.create(itemEntries.length);
        this.componentItems = new HashSet<>();
        for (ItemEntry itemEntry : itemEntries) {
            this.items.inverse().remove(itemEntry.id());
            this.items.put(itemEntry.identifier(), itemEntry.id());
            if (itemEntry.version() == ItemVersion.DataDriven || (itemEntry.version() == ItemVersion.None && itemEntry.componentBased())) {
                this.componentItems.add(itemEntry.identifier());
            }
        }
        final Set<String> blockItems = new HashSet<>(BedrockProtocol.MAPPINGS.getBedrockBlockItems());
        blockItems.removeIf(identifier -> !this.items.containsKey(identifier));

        this.blockItemValidBlockStates = new Int2ObjectOpenHashMap<>(blockItems.size());
        final BlockStateRewriter blockStateRewriter = this.user().get(BlockStateRewriter.class);
        for (String identifier : blockItems) {
            IntSortedSet validBlockStates = blockStateRewriter.validBlockStates(identifier);
            if (validBlockStates == null) {
                final String[] components = identifier.split(":", 2);
                if (components[1].startsWith("item.")) {
                    validBlockStates = blockStateRewriter.validBlockStates(components[0] + ':' + components[1].substring(5));
                }
            }
            if (validBlockStates != null) {
                this.blockItemValidBlockStates.put(this.items.get(identifier).intValue(), validBlockStates);
            } else {
                Via.getPlatform().getLogger().log(Level.WARNING, "Missing block for block item: " + identifier);
            }
        }

        this.itemType = new BedrockItemType(this.items.getOrDefault("minecraft:shield", 0), this.blockItemValidBlockStates, false, true);
        this.itemArrayType = new ArrayType<>(this.itemType, BedrockTypes.UNSIGNED_VAR_INT);

        this.itemInstanceType = new BedrockItemType(this.items.getOrDefault("minecraft:shield", 0), this.blockItemValidBlockStates, false, false);
        this.itemInstanceArrayType = new ArrayType<>(this.itemInstanceType, BedrockTypes.UNSIGNED_VAR_INT);

        this.populateJavaToBedrockItems(blockStateRewriter);
    }

    private void populateJavaToBedrockItems(final BlockStateRewriter blockStateRewriter) {
        for (Map.Entry<String, Map<Integer, BedrockMappingData.JavaItemMapping>> entry : BedrockProtocol.MAPPINGS.getBedrockToJavaMetaItems().entrySet()) {
            final Integer bedrockId = this.items.get(entry.getKey());
            if (bedrockId == null) {
                continue;
            }
            for (Map.Entry<Integer, BedrockMappingData.JavaItemMapping> metaEntry : entry.getValue().entrySet()) {
                final int data = metaEntry.getKey() == null ? 0 : metaEntry.getKey();
                final BedrockItem item = new BedrockItem(bedrockId, (short) data, (byte) 1);
                this.javaToBedrockItems.putIfAbsent(metaEntry.getValue().id(), item);
            }
        }

        for (Map.Entry<String, Map<BlockState, BedrockMappingData.JavaItemMapping>> entry : BedrockProtocol.MAPPINGS.getBedrockToJavaBlockItems().entrySet()) {
            final Integer bedrockId = this.items.get(entry.getKey());
            if (bedrockId == null) {
                continue;
            }
            for (Map.Entry<BlockState, BedrockMappingData.JavaItemMapping> blockEntry : entry.getValue().entrySet()) {
                final int blockRuntimeId = blockStateRewriter.bedrockId(blockEntry.getKey());
                if (blockRuntimeId == -1) {
                    continue;
                }

                final BedrockItem item = new BedrockItem(bedrockId);
                item.setBlockRuntimeId(blockRuntimeId);
                this.javaToBedrockItems.putIfAbsent(blockEntry.getValue().id(), item);
            }
        }
    }

    public Item javaItem(final BedrockItem bedrockItem) {
        if (bedrockItem.isEmpty()) return StructuredItem.empty();

        final String identifier = this.items.inverse().get(bedrockItem.identifier());
        if (identifier == null) {
            ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Missing item identifier for id: " + bedrockItem.identifier());
            return StructuredItem.empty();
        }

        final BedrockMappingData.JavaItemMapping javaItemMapping;
        final Map<BlockState, BedrockMappingData.JavaItemMapping> blockItemMappings = BedrockProtocol.MAPPINGS.getBedrockToJavaBlockItems().get(identifier);
        if (blockItemMappings != null) {
            if (bedrockItem.blockRuntimeId() == 0) { // Manually constructed items might not have a valid block state set
                final IntSortedSet validBlockStates = this.blockItemValidBlockStates.get(bedrockItem.identifier());
                bedrockItem.setBlockRuntimeId(validBlockStates.firstInt());
            }
            javaItemMapping = blockItemMappings.get(this.user().get(BlockStateRewriter.class).blockState(bedrockItem.blockRuntimeId()));
        } else {
            final int meta = bedrockItem.data() & 0xFFFF;
            final String newIdentifier = BedrockProtocol.MAPPINGS.getBedrockItemUpgrader().upgradeMetaItem(identifier, meta);
            if (newIdentifier != null) {
                final Map<BlockState, BedrockMappingData.JavaItemMapping> newBlockItemMappings = BedrockProtocol.MAPPINGS.getBedrockToJavaBlockItems().get(newIdentifier);
                if (newBlockItemMappings != null) {
                    final IntSortedSet validBlockStates = this.blockItemValidBlockStates.get(bedrockItem.identifier());
                    javaItemMapping = newBlockItemMappings.get(this.user().get(BlockStateRewriter.class).blockState(validBlockStates.firstInt()));
                } else {
                    javaItemMapping = null;
                }
            } else {
                final Map<Integer, BedrockMappingData.JavaItemMapping> metaItemMappings = BedrockProtocol.MAPPINGS.getBedrockToJavaMetaItems().get(identifier);
                if (metaItemMappings != null) {
                    if (!metaItemMappings.containsKey(meta)) {
                        if (metaItemMappings.size() != 1 || meta != 0) {
                            ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Missing meta: " + meta + " for item: " + identifier);
                        }
                        javaItemMapping = metaItemMappings.get(null);
                    } else {
                        javaItemMapping = metaItemMappings.get(meta);
                    }
                } else {
                    javaItemMapping = null;
                }
            }
        }

        final Item javaItem;
        if (javaItemMapping != null) {
            final StructuredDataContainer data = ProtocolConstants.createStructuredDataContainer();
            if (javaItemMapping.overrideTag() != null) {
                // javaTag.setValue(this.overrideTag.copy().getValue());
                // TODO: Update: Fix this
            }
            if (javaItemMapping.name() != null) {
                final ResourcePackStorage resourcePackStorage = this.user().get(ResourcePackStorage.class);
                data.set(StructuredDataKey.ITEM_NAME, TextUtil.stringToNbt(resourcePackStorage.getTexts().get(javaItemMapping.name())));
                data.set(StructuredDataKey.LORE, new Tag[]{TextUtil.stringToNbt("§7[ViaBedrock] Mapped item: " + identifier)});
            }
            javaItem = new StructuredItem(javaItemMapping.id(), bedrockItem.amount(), data);
        } else {
            final ResourcePackStorage resourcePackStorage = this.user().get(ResourcePackStorage.class);
            final ItemDefinitions.ItemDefinition itemDefinition = resourcePackStorage.getItems().get(identifier);
            final StructuredDataContainer data = ProtocolConstants.createStructuredDataContainer();

            if (itemDefinition != null) {
                if (itemDefinition.displayNameComponent() != null) {
                    data.set(StructuredDataKey.ITEM_NAME, TextUtil.stringToNbt(resourcePackStorage.getTexts().translate(itemDefinition.displayNameComponent())));
                } else if (this.componentItems.contains(identifier)) {
                    data.set(StructuredDataKey.ITEM_NAME, TextUtil.stringToNbt(resourcePackStorage.getTexts().get("item." + Key.stripMinecraftNamespace(identifier))));
                } else {
                    data.set(StructuredDataKey.ITEM_NAME, TextUtil.stringToNbt(resourcePackStorage.getTexts().get("item." + Key.stripMinecraftNamespace(identifier) + ".name")));
                }

                if (resourcePackStorage.getAttachables().attachables().containsKey(identifier) && resourcePackStorage.isLoadedOnJavaClient() && resourcePackStorage.getConverterData().containsKey("ca_" + identifier + "_default")) {
                    data.set(StructuredDataKey.ITEM_MODEL, new ItemModel(CustomAttachableResourceRewriter.ITEM_MODEL_KEY));
                    data.set(StructuredDataKey.CUSTOM_MODEL_DATA1_21_4, CustomAttachableResourceRewriter.getCustomModelData(identifier + "_default"));
                } else if (itemDefinition.iconComponent() != null && resourcePackStorage.isLoadedOnJavaClient()) {
                    data.set(StructuredDataKey.ITEM_MODEL, new ItemModel(CustomItemTextureResourceRewriter.ITEM_MODEL_KEY));
                    data.set(StructuredDataKey.CUSTOM_MODEL_DATA1_21_4, CustomItemTextureResourceRewriter.getCustomModelData(itemDefinition.iconComponent() + "_0"));
                } else {
                    data.set(StructuredDataKey.LORE, new Tag[]{TextUtil.stringToNbt("§7[ViaBedrock] Custom item: " + identifier)});
                }
            } else {
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Missing bedrock -> java item mapping for " + identifier);
                data.set(StructuredDataKey.ITEM_NAME, TextUtil.stringToNbt("§cMissing item: " + identifier));
            }
            javaItem = new StructuredItem(BedrockProtocol.MAPPINGS.getJavaItems().get("minecraft:paper"), bedrockItem.amount(), data);
        }

        final CompoundTag bedrockTag = bedrockItem.tag();
        if (bedrockTag != null) {
            if (bedrockTag.get("display") instanceof CompoundTag display) {
                if (display.contains("Name")) { // Bedrock client defaults to empty string if the type is wrong
                    javaItem.dataContainer().set(StructuredDataKey.CUSTOM_NAME, TextUtil.stringToNbt(display.getString("Name", "")));
                }
            }
        }

        if (ViaBedrock.getConfig().shouldEnableExperimentalFeatures()) {
            ExperimentalItemRewriter.handleItem(this.user(), bedrockItem, bedrockTag, javaItem);
        }

        final String tag = BedrockProtocol.MAPPINGS.getBedrockCustomItemTags().get(identifier);
        if (ITEM_NBT_REWRITERS.containsKey(tag)) {
            ITEM_NBT_REWRITERS.get(tag).toJava(this.user(), bedrockItem, javaItem);
        }

        return javaItem;
    }

    public CompoundTag javaItem(final CompoundTag bedrockTag) {
        final CompoundTag javaTag = new CompoundTag();
        javaTag.putString("id", "minecraft:stone");
        return javaTag; // TODO: Support converting nbt items
    }

    public Item[] javaItems(final BedrockItem[] bedrockItems) {
        final Item[] javaItems = new Item[bedrockItems.length];
        for (int i = 0; i < bedrockItems.length; i++) {
            javaItems[i] = this.javaItem(bedrockItems[i]);
        }
        return javaItems;
    }

    public BedrockItem bedrockItem(final Item javaItem) {
        if (javaItem == null || javaItem.isEmpty()) {
            return BedrockItem.empty();
        }

        final BedrockItem mappedItem = this.javaToBedrockItems.get(javaItem.identifier());
        if (mappedItem == null) {
            final String identifier = BedrockProtocol.MAPPINGS.getJavaItems().inverse().get(javaItem.identifier());
            ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Missing java -> bedrock item mapping for " + identifier + " (" + javaItem.identifier() + ")");
            return BedrockItem.empty();
        }

        final BedrockItem bedrockItem = mappedItem.copy();
        bedrockItem.setAmount(javaItem.amount());
        return bedrockItem;
    }

    public BedrockItem[] bedrockItems(final Item[] javaItems) {
        final BedrockItem[] bedrockItems = new BedrockItem[javaItems.length];
        for (int i = 0; i < javaItems.length; i++) {
            bedrockItems[i] = this.bedrockItem(javaItems[i]);
        }
        return bedrockItems;
    }

    public int maxStackSize(final BedrockItem bedrockItem) {
        if (bedrockItem == null || bedrockItem.isEmpty()) {
            return 64;
        }

        final String identifier = this.items.inverse().get(bedrockItem.identifier());
        if (identifier == null) {
            return 64;
        }

        final ItemDefinitions.ItemDefinition itemDefinition = this.user().get(ResourcePackStorage.class).getItems().get(identifier);
        if (itemDefinition != null && itemDefinition.maxStackSize() != null) {
            return Math.max(1, itemDefinition.maxStackSize());
        }

        final CompoundTag item = BedrockProtocol.MAPPINGS.getBedrockItems().get(identifier);
        if (item != null && item.contains("maxStackSize")) {
            return item.getInt("maxStackSize", 64);
        }

        return vanillaMaxStackSize(identifier);
    }

    private static int vanillaMaxStackSize(final String identifier) {
        final String item = Key.stripMinecraftNamespace(identifier);
        if (item.equals("ender_pearl")
                || item.equals("snowball")
                || item.equals("egg")
                || item.equals("honey_bottle")
                || item.equals("armor_stand")
                || item.equals("written_book")
                || item.endsWith("_sign")
                || item.endsWith("_hanging_sign")
                || item.endsWith("_banner")) {
            return 16;
        }

        if (item.endsWith("_bed")
                || item.endsWith("_boat")
                || item.endsWith("_chest_boat")
                || item.endsWith("_minecart")
                || item.endsWith("_bucket")
                || item.endsWith("_sword")
                || item.endsWith("_shovel")
                || item.endsWith("_pickaxe")
                || item.endsWith("_axe")
                || item.endsWith("_hoe")
                || item.endsWith("_spear")
                || item.endsWith("_helmet")
                || item.endsWith("_chestplate")
                || item.endsWith("_leggings")
                || item.endsWith("_boots")
                || item.endsWith("_horse_armor")
                || item.equals("wolf_armor")
                || item.startsWith("music_disc_")
                || item.equals("bow")
                || item.equals("crossbow")
                || item.equals("trident")
                || item.equals("shield")
                || item.equals("mace")
                || item.equals("shears")
                || item.equals("brush")
                || item.equals("flint_and_steel")
                || item.equals("fishing_rod")
                || item.equals("carrot_on_a_stick")
                || item.equals("warped_fungus_on_a_stick")
                || item.equals("saddle")
                || item.equals("elytra")
                || item.equals("enchanted_book")
                || item.equals("potion")
                || item.equals("splash_potion")
                || item.equals("lingering_potion")
                || item.equals("ominous_bottle")
                || item.equals("mushroom_stew")
                || item.equals("rabbit_stew")
                || item.equals("beetroot_soup")
                || item.equals("suspicious_stew")
                || item.equals("cake")
                || item.equals("totem_of_undying")
                || item.equals("goat_horn")
                || item.equals("spyglass")
                || item.equals("bundle")
                || item.equals("white_shulker_box")
                || item.equals("orange_shulker_box")
                || item.equals("magenta_shulker_box")
                || item.equals("light_blue_shulker_box")
                || item.equals("yellow_shulker_box")
                || item.equals("lime_shulker_box")
                || item.equals("pink_shulker_box")
                || item.equals("gray_shulker_box")
                || item.equals("light_gray_shulker_box")
                || item.equals("cyan_shulker_box")
                || item.equals("purple_shulker_box")
                || item.equals("blue_shulker_box")
                || item.equals("brown_shulker_box")
                || item.equals("green_shulker_box")
                || item.equals("red_shulker_box")
                || item.equals("black_shulker_box")
                || item.equals("shulker_box")) {
            return 1;
        }

        return 64;
    }

    public BiMap<String, Integer> getItems() {
        return this.items;
    }

    public Set<String> getComponentItems() {
        return this.componentItems;
    }

    public Type<BedrockItem> itemType() {
        return this.itemType;
    }

    public Type<BedrockItem[]> itemArrayType() {
        return this.itemArrayType;
    }

    public Type<BedrockItem> itemInstanceType() {
        return this.itemInstanceType;
    }

    public Type<BedrockItem[]> itemInstanceArrayType() {
        return this.itemInstanceArrayType;
    }

    public interface NbtRewriter {

        void toJava(final UserConnection user, final BedrockItem bedrockItem, final Item javaItem);

    }

}

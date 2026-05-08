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
package net.raphimc.viabedrock.experimental.model.recipe;

import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.Enchant_Type;

import java.util.List;

public record EnchantData(int cost, List<Enchantment> enchantments, int netId) {

    public EnchantData {
        enchantments = List.copyOf(enchantments);
    }

    public boolean isValid() {
        return this.cost > 0 && this.netId > 0 && !this.enchantments.isEmpty();
    }

    public Enchant_Type type() {
        return this.enchantments.isEmpty() ? null : this.enchantments.get(0).type();
    }

    public int level() {
        return this.enchantments.isEmpty() ? 0 : this.enchantments.get(0).level();
    }

    public record Enchantment(Enchant_Type type, int level) {
    }
}

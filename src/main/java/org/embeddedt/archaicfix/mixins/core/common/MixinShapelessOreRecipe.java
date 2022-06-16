package org.embeddedt.archaicfix.mixins.core.common;

import com.google.common.collect.ImmutableSet;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.ShapelessRecipes;
import net.minecraftforge.oredict.ShapelessOreRecipe;
import org.embeddedt.archaicfix.ArchaicLogger;
import org.embeddedt.archaicfix.FixHelper;
import org.embeddedt.archaicfix.mixins.IAcceleratedRecipe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.Map;
import java.util.Set;

@Mixin(ShapelessOreRecipe.class)
public class MixinShapelessOreRecipe implements IAcceleratedRecipe {
    @Shadow(remap = false) private ArrayList<Object> input;
    private Set<Item> allPossibleInputs = null;

    private void genMatchCache() {
        allPossibleInputs = null;
        ImmutableSet.Builder<Item> builder = ImmutableSet.builder();
        for(Object o : input) {
            if(o instanceof ItemStack) {
                builder.add(((ItemStack) o).getItem());
            } else if(o instanceof ArrayList) {
                for(ItemStack stack : ((ArrayList<ItemStack>)o)) {
                    builder.add(stack.getItem());
                }
            } else if(o != null) {
                ArchaicLogger.LOGGER.warn("Couldn't optimize input value: " + o);
                return;
            }
        }
        allPossibleInputs = builder.build();
        FixHelper.recipesHoldingPotentialItems.add(this);
    }

    @Override
    public Set<Item> getPotentialItems() {
        if(allPossibleInputs == null)
            genMatchCache();
        return allPossibleInputs;
    }

    public void invalidatePotentialItems() {
        allPossibleInputs = null;
    }
}
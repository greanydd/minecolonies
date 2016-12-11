package com.minecolonies.coremod.colony.materials;

import com.minecolonies.coremod.entity.EntityCitizen;
import net.minecraft.item.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * This class tracks a request send by a citizen.
 */
public class Request
{
    private final EntityCitizen   worker;
    private       EntityCitizen   deliveryman;
    private final List<ItemStack> neededMaterials;

    /**
     * Create a new Request.
     *
     * @param worker          The worker needing the materials.
     * @param neededMaterials The materials requested.
     */
    public Request(@NotNull final EntityCitizen worker, @NotNull final ItemStack... neededMaterials)
    {
        this.worker = worker;
        // Hack to guarantee mutability
        this.neededMaterials = new ArrayList<>(Arrays.asList(neededMaterials));
        this.deliveryman = null;
    }

    /**
     * Check if this request is completed.
     *
     * @return true if no more items are needed.
     */
    public boolean isCompleted()
    {
        return neededMaterials.isEmpty();
    }

    /**
     * Cancel this request.
     */
    public void cancel()
    {
        neededMaterials.clear();
    }

    /**
     * Note down that this deliveryman will work on this request.
     *
     * @param deliveryman the deliveryman claiming this Request.
     */
    public void claimTask(final EntityCitizen deliveryman)
    {
        if (worker != null
              && deliveryman != this.deliveryman)
        {
            throw new IllegalStateException("Cannot claim the same request twice.");
        }
        this.deliveryman = deliveryman;
    }
}

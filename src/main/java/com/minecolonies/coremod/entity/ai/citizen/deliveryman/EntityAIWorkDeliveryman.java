package com.minecolonies.coremod.entity.ai.citizen.deliveryman;

import com.minecolonies.coremod.colony.Colony;
import com.minecolonies.coremod.colony.jobs.JobDeliveryman;
import com.minecolonies.coremod.colony.materials.Request;
import com.minecolonies.coremod.entity.ai.basic.AbstractEntityAIInteract;
import com.minecolonies.coremod.entity.ai.util.AIState;
import com.minecolonies.coremod.entity.ai.util.AITarget;
import net.minecraft.item.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.List;

import static com.minecolonies.coremod.entity.ai.util.AIState.*;

/**
 * AI class of the deliveryman.
 * <p>
 * He will transport goods from and to warehouses.
 * <p>
 * Requested items will be handled by him.
 */
public class EntityAIWorkDeliveryman extends AbstractEntityAIInteract<JobDeliveryman>
{

    private static final int NOT_FOUND_TIMEOUT = 20;

    /**
     * Initialize the deliveryman and add all his tasks.
     *
     * @param job the job he has.
     */
    public EntityAIWorkDeliveryman(@NotNull final JobDeliveryman job)
    {
        super(job);
        super.registerTargets(
          new AITarget(IDLE, () -> START_WORKING),
          new AITarget(START_WORKING, () -> DELIVERYMAN_FIND_NEW_WORK),
          new AITarget(DELIVERYMAN_FIND_NEW_WORK, this::findNewWork)
        );
        worker.setSkillModifier(2 * worker.getCitizenData().getDexterity() + worker.getCitizenData().getEndurance());
        worker.setCanPickUpLoot(true);
    }

    private AIState findNewWork()
    {
        final Colony colony = worker.getColony();

        final List<Request> requests = colony.getRequests();

        // Nothing to do, wait a second...
        this.setDelay(NOT_FOUND_TIMEOUT);
        return DELIVERYMAN_FIND_NEW_WORK;
    }
}

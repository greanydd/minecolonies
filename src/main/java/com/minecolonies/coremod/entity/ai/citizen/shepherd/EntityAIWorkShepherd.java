package com.minecolonies.coremod.entity.ai.citizen.shepherd;

import static com.minecolonies.coremod.entity.ai.util.AIState.FARMER_WORK;
import static com.minecolonies.coremod.entity.ai.util.AIState.IDLE;
import static com.minecolonies.coremod.entity.ai.util.AIState.PREPARING;
import static com.minecolonies.coremod.entity.ai.util.AIState.SHEPHERD_FEED;
import static com.minecolonies.coremod.entity.ai.util.AIState.SHEPHERD_OBSERVE;
import static com.minecolonies.coremod.entity.ai.util.AIState.SHEPHERD_SLAUGHTER;
import static com.minecolonies.coremod.entity.ai.util.AIState.START_WORKING;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.minecolonies.coremod.MineColonies;
import com.minecolonies.coremod.blocks.BlockHutField;
import com.minecolonies.coremod.colony.Colony;
import com.minecolonies.coremod.colony.buildings.BuildingShepherd;
import com.minecolonies.coremod.colony.jobs.JobShepherd;
import com.minecolonies.coremod.entity.EntityCitizen;
import com.minecolonies.coremod.entity.ai.basic.AbstractEntityAIInteract;
import com.minecolonies.coremod.entity.ai.util.AIState;
import com.minecolonies.coremod.entity.ai.util.AITarget;
import com.minecolonies.coremod.util.BlockUtils;
import com.minecolonies.coremod.util.InventoryUtils;
import com.minecolonies.coremod.util.Utils;

import net.minecraft.block.BlockCrops;
import net.minecraft.block.IGrowable;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.EntityPig;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.common.IPlantable;

/**
 * Shepard AI class.
 */
public class EntityAIWorkShepherd extends AbstractEntityAIInteract<JobShepherd>
{
    /**
     * The standard delay the farmer should have.
     */
    private static final int     STANDARD_DELAY      = 40;
    /**
     * The smallest delay the farmer should have.
     */
    private static final int     SMALLEST_DELAY      = 5;
    /**
     * The bonus the farmer gains each update is level/divider.
     */
    private static final double  DELAY_DIVIDER       = 1;
    /**
     * The EXP Earned per harvest.
     */
    private static final double  XP_PER_HARVEST      = 0.5;
    /**
     * How far to check for animals.
     */
    private static final double PADDOCK_SIZE         = 10;
    /**
     * Minimal number of animals.
     */
    private static final double MIN_ENTITIES         = 10;
    /**
     * Maximal number of animals.
     */
    private static final double MAX_ENTITIES         = 20;
    /**
     * How long to wait after looking to decide what to do.
     */
    private static final int     LOOK_WAIT           = 100;
    /**
     * Changed after finished harvesting in order to dump the inventory.
     */
    private              boolean shouldDumpInventory = false;

    /**
     * The offset to work at relative to the scarecrow.
     */
    @Nullable
    private BlockPos workingOffset;

    /**
     * Defines if the farmer should request seeds for the current field.
     */
 // :TODO: rbenning: Keine Seeds
    private boolean requestFood = true;

    /**
     * Defines if the farmer should try to get the seeds from his chest.
     */
    // :TODO: rbenning: Keine Seeds
    private boolean shouldTryToGetFood = true;

    /**
     * Variables used in handleOffset.
     */
    private int     totalDis;
    private int     dist;
    private boolean horizontal;

    /**
     * Constructor for the shepherd.
     * Defines the tasks the shepherd executes.
     *
     * @param job a shepherd job to use.
     */
    public EntityAIWorkShepherd(@NotNull final JobShepherd job)
    {
        super(job);
        super.registerTargets(
          new AITarget(IDLE, () -> START_WORKING),
          new AITarget(START_WORKING, this::startWorkingAtOwnBuilding),
          new AITarget(PREPARING, this::prepareForFeeding),
          // :TODO: rbenning : Ablaufplan
          new AITarget(SHEPHERD_FEED, this::feed),
          new AITarget(SHEPHERD_SLAUGHTER, this::prepareForSlaughter)
          //new AITarget(SHEPHERD_SLAUGHTER, this::cycle)
        );
        // :TODO: rbenning : Link Skill from BuildingShepard.View to this place, do not duplicate information
        worker.setSkillModifier(2 * worker.getCitizenData().getEndurance() + worker.getCitizenData().getDexterity());
        worker.setCanPickUpLoot(true);
    }

    /**
     * Redirects the farmer to his building.
     *
     * @return the next state.
     */
    private AIState startWorkingAtOwnBuilding()
    {
        if (walkToBuilding())
        {
            return getState();
        }
        return PREPARING;
    }

    /**
     * Prepares the farmer for farming.
     * Also requests the tools and checks if the farmer has sufficient fields.
     *
     * @return the next AIState
     */
    @NotNull
    private AIState prepareForFeeding()
    {
        @Nullable final BuildingShepherd building = getOwnBuilding();

        if (building == null || building.getBuildingLevel() < 1)
        {
            return AIState.PREPARING;
        }

        building.syncWithColony(world);

        if (building.getPaddocks().size() < getOwnBuilding().getBuildingLevel() && !building.assignManually())
        {
           // :TODO: rbenning : Neue Felder aufnehmen
            searchAndAddPaddocks();
        }

        if (building.hasNoFields())
        {
            chatSpamFilter.talkWithoutSpam("entity.shepherd.noFreePaddocks");
            return AIState.PREPARING;
        }

        if (building.getCurrentPaddock() == null && building.getPaddockToWorkOn() == null)
        {
            building.resetPaddocks();
            return AIState.IDLE;
        }

        @Nullable final Paddock currentField = building.getCurrentPaddock();

        // :TODO: rbenning : Wann wird das zurückgesetzt?
        if (currentField.needsWork())
        {
          if (canGoFeeding(currentField, building))
            {
                return walkToBlock(currentField.getLocation()) ? AIState.PREPARING : AIState.SHEPHERD_FEED;
            }
        }
        else
        {
            getOwnBuilding().setCurrentField(null);
        }
        return AIState.PREPARING;
    }

    /**
     * Returns the farmer's work building.
     *
     * @return building instance
     */
    @Override
    protected BuildingShepherd getOwnBuilding()
    {
        return (BuildingShepherd) worker.getWorkBuilding();
    }

    /**
     * Searches and adds a field that has not been taken yet for the farmer and then adds it to the list.
     */
    private void searchAndAddPaddocks()
    {
        final Colony colony = worker.getColony();
        if (colony != null)
        {
            @Nullable final Paddock newPaddock = colony.getFreePaddock(worker.getName());

            if (newPaddock != null && getOwnBuilding() != null)
            {
                getOwnBuilding().addPaddock(newPaddock);
            }
        }
    }

    /**
     * Checks if the farmer is ready to plant.
     *
     * @param currentField the field to plant.
     * @return true if he is ready.
     */
    private boolean canGoFeeding(@NotNull final Paddock currentField, @NotNull final BuildingShepherd buildingFarmer)
    {
      // :TODO: rbenning : Das Essen ist eigentlich durch den Typ festgesetzt.
        if (currentField.getFood() == null)
        {
          //MineColonies.getLogger().info("entity.shepherd.noFoodSet");
            chatSpamFilter.talkWithoutSpam("entity.shepherd.noFoodSet");
            buildingFarmer.setCurrentField(null);
            return false;
        }

        if (shouldTryToGetFood)
        {
            final ItemStack food = currentField.getFood();
            final int slot = worker.findFirstSlotInInventoryWith(food.getItem(), food.getItemDamage());
            if (slot != -1)
            {
                requestFood = false;
            }
            if (!walkToBuilding())
            {
                if (isInHut(food))
                {
                    requestFood = false;
                    isInHut(food);
                }
                shouldTryToGetFood = requestFood;
                if (requestFood)
                {
                  //MineColonies.getLogger().info("entity.shepherd.NeedFood");
                    chatSpamFilter.talkWithoutSpam("entity.shepherd.NeedFood", food.getItem().getItemStackDisplayName(food));
                }
            }
        }

        return !shouldTryToGetFood;
    }

    /**
     * Checks to see if field contains plants.
     *
     * @param field the field to check.
     * @return Boolean if there were plants found.
     */
    private boolean containsAnimals(final Paddock field)
    {
        MinecraftServer tServer = DimensionManager.getWorld(0).getMinecraftServer();
        for(Entity entity : tServer.getEntityWorld().getLoadedEntityList())
        {
          if (entity instanceof EntityPig)
          {
            double distance = entity.getDistanceSq(field.getLocation());
            // :TODO: rbenning : remove me
            MineColonies.getLogger().info("Pig at distance " + distance);
            if (distance < PADDOCK_SIZE)
            {
              return true;
            }
          }
        }
        return false;
    }
    
    /**
     * Handles the offset of the field for the farmer.
     *
     * @param field the field object.
     * @return true if successful.
     */
    private boolean handleOffset(@NotNull final Paddock field)
    {
        if (workingOffset == null)
        {
            workingOffset = new BlockPos(0, 0, 0);
            totalDis = 1;
            dist = 0;
            horizontal = true;
        }
        else
        {
            if (workingOffset.getZ() >= field.getWidthPlusZ() && workingOffset.getX() <= -field.getLengthMinusX())
            {
                workingOffset = null;
                return false;
            }
            else
            {
                if (totalDis == dist)
                {
                    horizontal = !horizontal;
                    dist = 0;
                    if (horizontal)
                    {
                        totalDis++;
                    }
                }
                if (horizontal)
                {
                    workingOffset = new BlockPos(workingOffset.getX(), 0, workingOffset.getZ() - Math.pow(-1, totalDis));
                }
                else
                {
                    workingOffset = new BlockPos(workingOffset.getX() - Math.pow(-1, totalDis), 0, workingOffset.getZ());
                }
                dist++;
            }

        /*
        //This is the zigzag method, This is here for future reference.
        if (workingOffset == null)
        {
            workingOffset = new BlockPos(-field.getLengthMinusX(), 0, -field.getWidthMinusZ());
        }
        else
        {
            final int absZ = Math.abs(workingOffset.getZ());
            if (workingOffset.getZ() >= field.getWidthPlusZ() && workingOffset.getX() >= field.getLengthPlusX())
            {
                workingOffset = null;
                return false;
            }
            else if (
                        (
                            //If we're checking an even row
                            ((field.getLengthPlusX() - absZ) % 2 == 0)
                            && workingOffset.getX() >= field.getLengthPlusX()
                        )
                        ||
                        (
                            //If we're checking an odd row
                            ((field.getLengthPlusX() - absZ) % 2 == 1)
                            && workingOffset.getX() <= -field.getLengthMinusX()
                        )
                    )
            {
                workingOffset = new BlockPos(workingOffset.getX(), 0, workingOffset.getZ() + 1);
            }
            else if ((field.getLengthPlusX() - absZ) % 2 == 0)
            {
                workingOffset = new BlockPos(workingOffset.getX() + 1, 0, workingOffset.getZ());
            }
            else
            {
                workingOffset = new BlockPos(workingOffset.getX() - 1, 0, workingOffset.getZ());
            }
        }*/
        }
        return true;
    }

    /**
     * Called to check when the InventoryShouldBeDumped.
     *
     * @return true if the conditions are met
     */
    @Override
    protected boolean wantInventoryDumped()
    {
        if (shouldDumpInventory)
        {
            shouldDumpInventory = false;
            return true;
        }
        return false;
    }

    /**
     * The main work cycle of the Famer.
     * This checks each block, harvests, tills, and plants.
     */
    private AIState cycle()
    {
        @Nullable final BuildingShepherd buildingFarmer = getOwnBuilding();

        if (buildingFarmer == null || checkForHoe() || buildingFarmer.getCurrentPaddock() == null)
        {
            return AIState.PREPARING;
        }

        @Nullable final Paddock field = buildingFarmer.getCurrentPaddock();

        if (workingOffset != null)
        {
            final BlockPos position = field.getLocation().down().south(workingOffset.getZ()).east(workingOffset.getX());
            // Still moving to the block
            if (walkToBlock(position.up()))
            {
                return AIState.FARMER_WORK;
            }

            // harvest the block if able to.
            if (harvestIfAble(position))
            {
                setDelay(getLevelDelay());
            }
        }

        if (!handleOffsetHarvest(field))
        {
            resetVariables();
            shouldDumpInventory = true;
            field.setNeedsWork(false);
            return AIState.IDLE;
        }
        return AIState.FARMER_WORK;
    }

    /**
     * Checks if we can harvest, and does so if we can.
     *
     * @return true if we harvested.
     */
    private boolean harvestIfAble(final BlockPos position)
    {
        if (shouldHarvest(position))
        {
            worker.addExperience(XP_PER_HARVEST);
            if (mineBlock(position.up()))
            {
                world.setBlockState(position, Blocks.DIRT.getDefaultState());
                return true;
            }
        }
        return false;
    }

    @Override
    protected int getLevelDelay()
    {
        return (int) Math.max(SMALLEST_DELAY, STANDARD_DELAY - (this.worker.getLevel() * DELAY_DIVIDER));
    }

    /**
     * Handles the offset of the field for the farmer.
     * Skips to the next harvestable crop, returns true if one was found.
     *
     * @param field the field object.
     * @return true if a harvestable crop was found.
     */
    private boolean handleOffsetHarvest(@NotNull final Paddock field)
    {
        if (workingOffset == null)
        {
            handleOffset(field);
        }

        BlockPos position = field.getLocation().down().south(workingOffset.getZ()).east(workingOffset.getX());

        while (!shouldHarvest(position))
        {
            if (!handleOffset(field))
            {
                return false;
            }
            position = field.getLocation().down().south(workingOffset.getZ()).east(workingOffset.getX());
        }
        return true;
    }

    /**
     * Resets the basic variables of the class.
     */
    private void resetVariables()
    {
        requestFood = true;
        shouldTryToGetFood = true;
    }

    /**
     * Checks if the crop should be harvested.
     *
     * @param position the position to check.
     * @return true if should be hoed.
     */
    private boolean shouldHarvest(@NotNull final BlockPos position)
    {
        final IBlockState state = world.getBlockState(position.up());

        if (state.getBlock() instanceof IGrowable && state.getBlock() instanceof BlockCrops)
        {
            @NotNull final BlockCrops block = (BlockCrops) state.getBlock();
            return !block.canGrow(world, position.up(), state, false);
        }

        return false;
    }

    /**
     * This (re)initializes a field.
     * Checks the block above to see if it is a plant, if so, breaks it. Then tills.
     */
    private AIState feed()
    {
        @Nullable final BuildingShepherd buildingFarmer = getOwnBuilding();
        if (buildingFarmer == null || buildingFarmer.getCurrentPaddock() == null)
        {
            return AIState.PREPARING;
        }

        @Nullable final Paddock field = buildingFarmer.getCurrentPaddock();
        MinecraftServer tServer = DimensionManager.getWorld(0).getMinecraftServer();
        int matchingEntities = 0;
        int inlove = 0;
        int child = 0;
        MineColonies.getLogger().info("Feeding");
        for(Entity entity : tServer.getEntityWorld().getLoadedEntityList())
        {
          if (entity instanceof EntityPig)
          {
            double distance = entity.getDistanceSq(field.getLocation());
            // :TODO: rbenning : remove me
            //MineColonies.getLogger().info("Find pig at distance " + distance);
            if (distance < 250)
            {
              matchingEntities++;
              EntityPig pig = (EntityPig) entity;
              ItemStack tFood = field.getFood();
              pig.isBreedingItem(tFood);
              if (pig.isInLove())
              {
                inlove++;
              }
              if (pig.isChild())
              {
                child++;
              }
              Item tItem = tFood.getItem();
              final int slot = worker.findFirstSlotInInventoryWith(tItem, tFood.getItemDamage());
              
              if (slot != -1)
              {
                  if (pig.isBreedingItem(tFood) && pig.getGrowingAge() == 0 && !pig.isInLove())
                  {
                    MineColonies.getLogger().info("feeding adult " + pig.getUniqueID());
                    getInventory().decrStackSize(slot, 1);
                    pig.setInLove(null);
                    setDelay(STANDARD_DELAY);
                    return matchingEntities < MAX_ENTITIES ? AIState.SHEPHERD_FEED : AIState.SHEPHERD_SLAUGHTER;
                  }
                  else if (pig.isChild() && pig.isBreedingItem(tFood))
                  {
                    MineColonies.getLogger().info("feeding child " + pig.getUniqueID());
                    getInventory().decrStackSize(slot, 1);
                    pig.ageUp((int)((float)(-pig.getGrowingAge() / 20) * 0.1F), true);
                    setDelay(STANDARD_DELAY);
                    return matchingEntities < MAX_ENTITIES ? AIState.SHEPHERD_FEED : AIState.SHEPHERD_SLAUGHTER;
                  }
              }
              else
              {
                setDelay(STANDARD_DELAY);
                return AIState.IDLE;
              }
            }
          }
        }
        MineColonies.getLogger().info("pigs " + matchingEntities + ", " + inlove + ", " + child);
        if (matchingEntities < 2)
        {
          MineColonies.getLogger().info("to few pigs " + matchingEntities);
          return AIState.PREPARING;
        }
        setDelay(STANDARD_DELAY);
        return AIState.SHEPHERD_FEED;
    }

    /**
     * Checks if we can hoe, and does so if we can.
     *
     * @param position the position to check
     * @param field    the field that we are working with.
     */
    private boolean hoeIfAble(final BlockPos position, final Paddock field)
    {
        if (shouldHoe(position, field) && !checkForHoe())
        {
            equipHoe();
            worker.swingArm(worker.getActiveHand());
            world.setBlockState(position, Blocks.FARMLAND.getDefaultState());
            worker.damageItemInHand(1);
            mineBlock(position.up());
            return true;
        }
        return false;
    }

    /**
     * Checks if the ground should be planted.
     *
     * @param position the position to check.
     * @param field    the field close to this position.
     * @return true if should be hoed.
     */
    private boolean shouldPlant(@NotNull final BlockPos position, @NotNull final Paddock field)
    {
        @Nullable final ItemStack itemStack = BlockUtils.getItemStackFromBlockState(world.getBlockState(position.up()));

        if (itemStack != null && itemStack.getItem() == field.getFood().getItem())
        {
            requestFood = false;
        }

        return !field.isNoPartOfField(world, position) && !(world.getBlockState(position.up()).getBlock() instanceof BlockCrops)
                 && !(world.getBlockState(position).getBlock() instanceof BlockHutField) && world.getBlockState(position).getBlock() == Blocks.FARMLAND;
    }

    /**
     * Plants the crop at a given location.
     *
     * @param item     the crop.
     * @param position the location.
     */
    private boolean plantCrop(final ItemStack item, @NotNull final BlockPos position)
    {
        final int slot = worker.findFirstSlotInInventoryWith(item.getItem(), item.getItemDamage());
        if (slot == -1)
        {
            return false;
        }
        else
        {
            @NotNull final IPlantable seed = (IPlantable) item.getItem();
            world.setBlockState(position.up(), seed.getPlant(world, position));
            getInventory().decrStackSize(slot, 1);
            requestFood = false;
            //Flag 1+2 is needed for updates
            return true;
        }
    }

    /**
     * Checks if the ground should be hoed and the block above removed.
     *
     * @param position the position to check.
     * @param field    the field close to this position.
     * @return true if should be hoed.
     */
    private boolean shouldHoe(@NotNull final BlockPos position, @NotNull final Paddock field)
    {
        return !field.isNoPartOfField(world, position)
                 && !BlockUtils.isBlockSeed(world, position.up())
                 && !(world.getBlockState(position).getBlock() instanceof BlockHutField)
                 && (world.getBlockState(position).getBlock() == Blocks.DIRT || world.getBlockState(position).getBlock() == Blocks.GRASS);
    }

    /**
     * Sets the hoe as held item.
     */
    private void equipHoe()
    {
        worker.setHeldItem(getHoeSlot());
    }

    /**
     * Get's the slot in which the hoe is in.
     *
     * @return slot number
     */
    private int getHoeSlot()
    {
        return InventoryUtils.getFirstSlotContainingTool(getInventory(), Utils.HOE);
    }

    /**
     * Farmer looks at field to see if it's harvestable.
     * Checks to see if there are any harvestable crops,
     * if so go to FARMER_WORK, if not, set needs work to false and go to IDLE.
     */
    private AIState prepareForSlaughter()
    {
        @Nullable final BuildingShepherd buildingFarmer = getOwnBuilding();

        if (buildingFarmer == null || checkForWeapon() || buildingFarmer.getCurrentPaddock() == null)
        {
            return AIState.PREPARING;
        }

        return AIState.PREPARING;
    }

    /**
     * Returns the farmer's worker instance. Called from outside this class.
     *
     * @return citizen object
     */
    @Nullable
    public EntityCitizen getCitizen()
    {
        return worker;
    }
}


package com.minecolonies.coremod.entity.ai.citizen.shepherd;

import static com.minecolonies.coremod.entity.ai.util.AIState.IDLE;
import static com.minecolonies.coremod.entity.ai.util.AIState.PREPARING;
import static com.minecolonies.coremod.entity.ai.util.AIState.SHEPHERD_FEED;
import static com.minecolonies.coremod.entity.ai.util.AIState.SHEPHERD_SLAUGHTER;
import static com.minecolonies.coremod.entity.ai.util.AIState.START_WORKING;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.minecolonies.coremod.MineColonies;
import com.minecolonies.coremod.colony.Colony;
import com.minecolonies.coremod.colony.buildings.BuildingShepherd;
import com.minecolonies.coremod.colony.jobs.JobShepherd;
import com.minecolonies.coremod.entity.EntityCitizen;
import com.minecolonies.coremod.entity.ai.basic.AbstractEntityAIInteract;
import com.minecolonies.coremod.entity.ai.util.AIState;
import com.minecolonies.coremod.entity.ai.util.AITarget;
import com.minecolonies.coremod.util.InventoryUtils;
import com.minecolonies.coremod.util.Utils;

import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.EntityPig;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.common.DimensionManager;

/**
 * Shepard AI class.
 */
public class EntityAIWorkShepherd extends AbstractEntityAIInteract<JobShepherd>
{
  /**
   * The standard delay the farmer should have.
   */
  private static final int STANDARD_DELAY = 40;
  /**
   * The smallest delay the farmer should have.
   */
  private static final int SMALLEST_DELAY = 5;
  /**
   * The bonus the farmer gains each update is level/divider.
   */
  private static final double DELAY_DIVIDER = 1;
  /**
   * The EXP Earned per harvest.
   */
  private static final double XP_PER_FEED = 0.5;
  /**
   * How far to check for animals.
   */
  private static final double PADDOCK_SIZE = 150;
  /**
   * Minimal number of animals.
   */
  private static final double MIN_ENTITIES = 5;
  /**
   * Maximal number of animals.
   */
  private static final double MAX_ENTITIES = 10;
  /**
   * How long to wait after looking to decide what to do.
   */
  private static final int LOOK_WAIT = 100;
  /**
   * Changed after finished harvesting in order to dump the inventory.
   */
  private boolean shouldDumpInventory = false;

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
  private int totalDis;
  private int dist;
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
        new AITarget(IDLE, () -> START_WORKING), //
        new AITarget(START_WORKING, this::startWorkingAtOwnBuilding), //
        new AITarget(PREPARING, this::prepareForFeeding), //
        new AITarget(SHEPHERD_FEED, this::feed), //
        new AITarget(SHEPHERD_SLAUGHTER, this::slaughter) //
    );
    // :TODO: rbenning : Link Skill from BuildingShepard.View to this place, do not duplicate information
    worker.setSkillModifier(2 * worker.getCitizenData().getEndurance() +
        worker.getCitizenData().getDexterity());
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
    @Nullable
    final BuildingShepherd building = getOwnBuilding();
    if (building == null || building.getBuildingLevel() < 1)
    {
      return AIState.PREPARING;
    }

    building.syncWithColony(world);

    if (building.getPaddocks().size() < getOwnBuilding().getBuildingLevel() &&
        !building.assignManually())
    {
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

    @Nullable
    final Paddock currentField = building.getCurrentPaddock();
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
      @Nullable
      final Paddock newPaddock = colony.getFreePaddock(worker.getName());

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
  private boolean canGoFeeding(@NotNull final Paddock currentField,
      @NotNull final BuildingShepherd buildingFarmer)
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
          MineColonies.getLogger().info("entity.shepherd.NeedFood");
          chatSpamFilter.talkWithoutSpam("entity.shepherd.NeedFood",
              food.getItem().getItemStackDisplayName(food));
        }
      }
    }

    return !shouldTryToGetFood;
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

  @Override
  protected int getLevelDelay()
  {
    return (int) Math.max(SMALLEST_DELAY, STANDARD_DELAY - (this.worker.getLevel() *
        DELAY_DIVIDER));
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
   * This (re)initializes a field.
   * Checks the block above to see if it is a plant, if so, breaks it. Then tills.
   */
  private AIState feed()
  {
    @Nullable
    final BuildingShepherd buildingFarmer = getOwnBuilding();
    if (buildingFarmer == null || buildingFarmer.getCurrentPaddock() == null)
    {
      return AIState.PREPARING;
    }

    @Nullable
    final Paddock field = buildingFarmer.getCurrentPaddock();
    MinecraftServer tServer = DimensionManager.getWorld(0).getMinecraftServer();
    int matchingEntities = 0;
    int inlove = 0;
    int child = 0;

    EntityPig matchingPig = null;
    MineColonies.getLogger().info("Feeding");
    for (Entity entity : tServer.getEntityWorld().getLoadedEntityList())
    {
      if (entity instanceof EntityPig)
      {
        double distance = entity.getDistanceSq(field.getLocation());
        if (distance < PADDOCK_SIZE)
        {
          matchingEntities++;
          EntityPig pig = (EntityPig) entity;
          if (pig.isInLove())
          {
            inlove++;
          }
          else if (pig.isChild())
          {
            child++;
          }
          else if (matchingEntities < MAX_ENTITIES)
          {
            if (matchingPig == null)
            {
              MineColonies.getLogger().info("match " + pig.getUniqueID());
              matchingPig = pig;
            }
          }
          else
          {
            return AIState.SHEPHERD_SLAUGHTER;
          }
        }
      }
    }

    if (matchingPig != null)
    {
      if (walkToBlock(matchingPig.getPosition()))
      {
        return AIState.SHEPHERD_FEED;
      }
      else
      {
        MineColonies.getLogger().info("feed status " + matchingEntities + ", " + inlove + ", " +
            child);
        return feedAdult(field, matchingPig);
      }
    }
    else
    {
      MineColonies.getLogger().info("no match " + matchingEntities + ", " + inlove + ", " +
          child);
      return AIState.PREPARING;
    }
  }

  private AIState feedAdult(Paddock field, EntityPig pig)
  {
    ItemStack tFood = field.getFood();
    Item tItem = tFood.getItem();
    final int slot = worker.findFirstSlotInInventoryWith(tItem, tFood.getItemDamage());
    if (slot != -1 && pig.isBreedingItem(tFood) && pig.getGrowingAge() == 0 && !pig.isInLove())
    {
      MineColonies.getLogger().info("feeding adult " + pig.getUniqueID());
      equipFood(slot);
      worker.swingArm(worker.getActiveHand());
      worker.addExperience(XP_PER_FEED);
      getInventory().decrStackSize(slot, 1);
      pig.setInLove(null);
      setDelay(STANDARD_DELAY);
      return AIState.SHEPHERD_FEED;
    }
    else
    {
      return AIState.PREPARING;
    }
  }

  private AIState feedChild(Paddock field, EntityPig pig)
  {
    ItemStack tFood = field.getFood();
    Item tItem = tFood.getItem();
    final int slot = worker.findFirstSlotInInventoryWith(tItem, tFood.getItemDamage());
    if (slot != -1 && pig.isBreedingItem(tFood) && pig.isChild())
      {
        MineColonies.getLogger().info("feeding child " + pig.getUniqueID());
        equipFood(slot);
        worker.swingArm(worker.getActiveHand());
        worker.addExperience(XP_PER_FEED);
        getInventory().decrStackSize(slot, 1);
        pig.ageUp((int) ((float) (-pig.getGrowingAge() / 20) * 0.1F), true);
        setDelay(STANDARD_DELAY);
        return AIState.SHEPHERD_FEED;
    }
    else
    {
      return AIState.PREPARING;
    }
  }
  
  /**
   * Sets the hoe as held item.
   */
  private void equipFood(int slot)
  {
    worker.setHeldItem(slot);
  }

  /**
   * Farmer looks at field to see if it's harvestable.
   * Checks to see if there are any harvestable crops,
   * if so go to FARMER_WORK, if not, set needs work to false and go to IDLE.
   */
  private AIState slaughter()
  {
    @Nullable
    final BuildingShepherd buildingFarmer = getOwnBuilding();
    if (buildingFarmer == null || checkForWeapon() || buildingFarmer.getCurrentPaddock() == null)
    {
      return AIState.PREPARING;
    }
    
    @Nullable
    final Paddock field = buildingFarmer.getCurrentPaddock();
    MinecraftServer tServer = DimensionManager.getWorld(0).getMinecraftServer();
    int matchingEntities = 0;
    int inlove = 0;
    int child = 0;

    EntityPig matchingPig = null;
    MineColonies.getLogger().info("Slaughtering");
    for (Entity entity : tServer.getEntityWorld().getLoadedEntityList())
    {
      if (entity instanceof EntityPig)
      {
        double distance = entity.getDistanceSq(field.getLocation());
        if (distance < PADDOCK_SIZE)
        {
          EntityPig pig = (EntityPig) entity;
          if (pig.isInLove())
          {
            inlove++;
          }
          else if (pig.isChild())
          {
            child++;
          }
          else
          {
            matchingEntities++;
            if (matchingPig == null)
            {
              MineColonies.getLogger().info("match " + pig.getUniqueID());
              matchingPig = pig;
            }
          }
        }
      }
    }
    
    if (matchingEntities > MIN_ENTITIES && matchingPig != null)
    {
      if (walkToBlock(matchingPig.getPosition()))
      {
        return AIState.SHEPHERD_SLAUGHTER;
      }
      else
      {
        MineColonies.getLogger().info("slaughter status " + matchingEntities + ", " + inlove + ", " +
            child);
        return slaughter(matchingPig);
      }
    }
    else
    {
      MineColonies.getLogger().info("no match for slaughter " + matchingEntities + ", " + inlove + ", " +
          child);
      return AIState.PREPARING;
    }
  }
  
  /**
   * Checks if we can hoe, and does so if we can.
   *
   * @param position the position to check
   * @param field    the field that we are working with.
   */
  private AIState slaughter(final EntityPig pig)
  {
    // :TODO: beim Prepare eine Waffe einstecken
    if (checkForWeapon())
    {
      equipWeapon();
      worker.swingArm(worker.getActiveHand());
      worker.damageItemInHand(1);
      worker.attackEntityAsMob(pig);
      pig.attackEntityAsMob(worker);
      pig.setDead();
      return AIState.SHEPHERD_SLAUGHTER;
    }
    return AIState.PREPARING;
  }

  /**
   * Sets the hoe as held item.
   */
  private void equipWeapon()
  {
    worker.setHeldItem(getWeaponSlot());
  }

  /**
   * Get's the slot in which the hoe is in.
   *
   * @return slot number
   */
  private int getWeaponSlot()
  {
    return InventoryUtils.getFirstSlotContainingTool(getInventory(), Utils.WEAPON);
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

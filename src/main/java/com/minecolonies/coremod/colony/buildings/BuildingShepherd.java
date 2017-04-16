/*
 * BuildingShepherd.java
 * 
 * Copyright (c) 2013 - 2017 next level software,
 * Nikolaus-Heilmann-Straﬂe 20, 97447 Frankenwinheim, Germany.
 * All rights reserved.
 *
 * This software is the confidential and proprietary information
 * of www.nl-soft.com ("Confidential Information").
 * You shall not disclose such Confidential Information and shall use
 * it only in accordance with the terms of the license agreement you
 * entered into with www.nl-soft.com.
 */

package com.minecolonies.coremod.colony.buildings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.minecolonies.blockout.views.Window;
import com.minecolonies.coremod.MineColonies;
import com.minecolonies.coremod.client.gui.WindowHutShepherd;
import com.minecolonies.coremod.colony.CitizenData;
import com.minecolonies.coremod.colony.Colony;
import com.minecolonies.coremod.colony.ColonyView;
import com.minecolonies.coremod.colony.jobs.AbstractJob;
import com.minecolonies.coremod.colony.jobs.JobShepherd;
import com.minecolonies.coremod.entity.ai.citizen.shepherd.Paddock;
import com.minecolonies.coremod.entity.ai.citizen.shepherd.PaddockView;
import com.minecolonies.coremod.network.messages.AssignPaddockMessage;
import com.minecolonies.coremod.network.messages.AssignmentModePaddockMessage;
import com.minecolonies.coremod.tileentities.PaddockTileEntity;
import com.minecolonies.coremod.tileentities.ScarecrowTileEntity;
import com.minecolonies.coremod.util.LanguageHandler;
import com.minecolonies.coremod.util.Utils;

import io.netty.buffer.ByteBuf;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.common.util.Constants;
import net.minecraftforge.fml.common.network.ByteBufUtils;

/**
 * The shepherds building.
 * 
 * @author Roland
 */
public class BuildingShepherd extends AbstractBuildingWorker
{

  /**
   * The job description.
   */
  private static final String SHEPHERD = "Shepherd";

  /**
   * The maximum upgrade of the building.
   */
  public static final int MAX_BUILDING_LEVEL = 1;

  /**
   * NBTTag to store the fields.
   */
  private static final String TAG_FIELDS = "fields";

  /**
   * NBT tag to store assign manually.
   */
  private static final String TAG_ASSIGN_MANUALLY = "assign";

  /**
   * Flag used to be notified about block updates.
   */
  private static final int BLOCK_UPDATE_FLAG = 3;

  /**
   * The list of the fields the farmer manages.
   */
  private final ArrayList<Paddock> farmerFields = new ArrayList<>();

  /**
   * The field the farmer is currently working on.
   */
  @Nullable
  private Paddock currentField;

  /**
   * Fields should be assigned manually to the farmer.
   */
  private boolean assignManually = false;

  /**
   * Public constructor of the building, creates an object of the building.
   *
   * @param aColony the colony.
   * @param aPosition the position.
   */
  public BuildingShepherd(Colony aColony, BlockPos aPosition)
  {
    super(aColony, aPosition);
  }

  /**
   * Returns list of fields of the farmer.
   *
   * @return a list of field objects.
   */
  @NotNull
  public List<Paddock> getFarmerFields()
  {
    return Collections.unmodifiableList(farmerFields);
  }

  /**
   * Checks if the farmer has any fields.
   *
   * @return true if he has none.
   */
  public boolean hasNoFields()
  {
    return farmerFields.isEmpty();
  }

  /**
   * Assigns a field list to the field list.
   *
   * @param field the field to add.
   */
  public void addFarmerFields(final Paddock field)
  {
    field.calculateSize(getColony().getWorld(), field.getLocation().down());
    farmerFields.add(field);
  }

  /**
   * Getter of the current field.
   *
   * @return a field object.
   */
  @Nullable
  public Paddock getCurrentField()
  {
    return currentField;
  }

  /**
   * Sets the field the farmer is currently working on.
   *
   * @param currentField the field to work on.
   */
  public void setCurrentField(@Nullable final Paddock currentField)
  {
    this.currentField = currentField;
  }

  /**
   * Retrieves a random field to work on for the farmer.
   *
   * @return a field to work on.
   */
  @Nullable
  public Paddock getFieldToWorkOn()
  {
    Collections.shuffle(farmerFields);
    for (@NotNull
    final Paddock field : farmerFields)
    {
      if (field.needsWork())
      {
        currentField = field;
        return field;
      }
    }
    return null;
  }

  /**
   * @{inheritDoc}
   */
  @Override
  public AbstractJob createJob(CitizenData citizen)
  {
    if (!farmerFields.isEmpty())
    {
        for (@NotNull final Paddock field : farmerFields)
        {
            final Paddock colonyField = getColony().getPaddock(field.getID());
            if (colonyField != null)
            {
                colonyField.setOwner(citizen.getName());
            }
            field.setOwner(citizen.getName());
        }
    }
    return new JobShepherd(citizen);
  }

  /**
   * @{inheritDoc}
   */
  @NotNull
  @Override
  public String getJobName()
  {
    return SHEPHERD;
  }

  /**
   * @{inheritDoc}
   */
  @NotNull
  @Override
  public String getSchematicName()
  {
    return SHEPHERD;
  }

  /**
   * @{inheritDoc}
   */
  @Override
  public int getMaxBuildingLevel()
  {
    return MAX_BUILDING_LEVEL;
  }

  /**
   * Override this method if you want to keep some items in inventory.
   * When the inventory is full, everything get's dumped into the building chest.
   * But you can use this method to hold some stacks back.
   *
   * @param stack the stack to decide on
   * @return true if the stack should remain in inventory
   */
  @Override
  public boolean neededForWorker(@Nullable final ItemStack stack)
  {
    return stack != null && Utils.isShears(stack);
  }

  //we have to update our field from the colony!
  @Override
  public void readFromNBT(@NotNull final NBTTagCompound compound)
  {
    super.readFromNBT(compound);
    final NBTTagList fieldTagList = compound.getTagList(TAG_FIELDS, Constants.NBT.TAG_COMPOUND);
    for (int i = 0; i < fieldTagList.tagCount(); ++i)
    {
      final NBTTagCompound fieldCompound = fieldTagList.getCompoundTagAt(i);
      final Paddock f = Paddock.createFromNBT(getColony(), fieldCompound);
      if (f != null)
      {
        farmerFields.add(f);
      }
    }
    assignManually = compound.getBoolean(TAG_ASSIGN_MANUALLY);
  }

  @Override
  public void writeToNBT(@NotNull final NBTTagCompound compound)
  {
    super.writeToNBT(compound);
    @NotNull
    final NBTTagList fieldTagList = new NBTTagList();
    for (@NotNull
    final Paddock f : farmerFields)
    {
      @NotNull
      final NBTTagCompound fieldCompound = new NBTTagCompound();
      f.writeToNBT(fieldCompound);
      fieldTagList.appendTag(fieldCompound);
    }
    compound.setTag(TAG_FIELDS, fieldTagList);
    compound.setBoolean(TAG_ASSIGN_MANUALLY, assignManually);
  }

  @Override
  public void onDestroyed()
  {
    super.onDestroyed();
          for (@NotNull final Paddock field : farmerFields)
          {
              final Paddock tempField = getColony().getPaddock(field.getID());
    
              if (tempField != null)
              {
                  tempField.setTaken(false);
                  tempField.setOwner("");
                  @NotNull final PaddockTileEntity paddockTileEntity = (PaddockTileEntity) getColony().getWorld().getTileEntity(field.getID());
    
                  if (getColony() != null && getColony().getWorld() != null)
                  {
                      getColony().getWorld()
                        .notifyBlockUpdate(paddockTileEntity.getPos(),
                          getColony().getWorld().getBlockState(paddockTileEntity.getPos()),
                          getColony().getWorld().getBlockState(paddockTileEntity.getPos()),
                          BLOCK_UPDATE_FLAG);
                      paddockTileEntity.setName(LanguageHandler.format("com.minecolonies.coremod.gui.paddock.user",
                        LanguageHandler.format("com.minecolonies.coremod.gui.paddock.user.noone")));
                  }
              }
          }
  }

  /**
   * Method to serialize data to send it to the view.
  *
  * @param buf the used ByteBuffer.
  */
  @Override
  public void serializeToView(@NotNull final ByteBuf buf)
  {
    super.serializeToView(buf);
         buf.writeBoolean(assignManually);
    
         int size = 0;
    
         for (@NotNull final Paddock field : getColony().getPaddocks().values())
         {
             if (field.isTaken())
             {
                 if (getWorker() == null || field.getOwner().equals(getWorker().getName()))
                 {
                     size++;
                 }
             }
             else
             {
                 size++;
             }
         }
    
         buf.writeInt(size);
    
         for (@NotNull final Paddock field : getColony().getPaddocks().values())
         {
             if (field.isTaken())
             {
                 if (getWorker() == null || field.getOwner().equals(getWorker().getName()))
                 {
                     @NotNull final PaddockView fieldView = new PaddockView(field);
                     fieldView.serializeViewNetworkData(buf);
                 }
             }
             else
             {
                 @NotNull final PaddockView fieldView = new PaddockView(field);
                 fieldView.serializeViewNetworkData(buf);
             }
         }

    if (getWorker() == null)
    {
      ByteBufUtils.writeUTF8String(buf, "");
    }
    else
    {
      ByteBufUtils.writeUTF8String(buf, getWorker().getName());
    }
  }

  /**
  * Synchronize field list with colony.
  *
  * @param world the world the building is in.
  */
  public void syncWithColony(@NotNull final World world)
  {
    if (!farmerFields.isEmpty())
    {
      @NotNull
      final ArrayList<Paddock> tempFields = new ArrayList<>(farmerFields);

      for (@NotNull
      final Paddock field : tempFields)
      {
        @NotNull
        final PaddockTileEntity scarecrow = (PaddockTileEntity) world.getTileEntity(
            field.getID());
        if (scarecrow == null)
        {
          farmerFields.remove(field);
          if (currentField != null && currentField.getID() == field.getID())
          {
            currentField = null;
          }
        }
        else
        {
          scarecrow.setName(LanguageHandler.format("com.minecolonies.coremod.gui.paddock.user",
              getWorker().getName()));
          getColony().getWorld().notifyBlockUpdate(scarecrow.getPos(),
              getColony().getWorld().getBlockState(scarecrow.getPos()),
              getColony().getWorld().getBlockState(scarecrow.getPos()), BLOCK_UPDATE_FLAG);
          field.setInventoryField(scarecrow.getInventoryField());
          if (currentField != null && currentField.getID() == field.getID())
          {
            currentField.setInventoryField(scarecrow.getInventoryField());
          }
        }
      }
    }
  }

  /**
  * Resets the fields to need work again.
  */
  public void resetFields()
  {
    for (@NotNull
    final Paddock field : farmerFields)
    {
      field.setNeedsWork(true);
      field.calculateSize(getColony().getWorld(), field.getLocation().down());
    }
  }

  /**
  * Getter for the assign manually.
  *
  * @return true if he should.
  */
  public boolean assignManually()
  {
    return assignManually;
  }

  /**
  * Switches the assignManually of the farmer.
  *
  * @param assignManually true if assignment should be manual.
  */
  public void setAssignManually(final boolean assignManually)
  {
    this.assignManually = assignManually;
  }

  /**
   * Method called to free a field.
   *
   * @param position id of the field.
   */
  public void freeField(final BlockPos position)
  {
    //Get the field with matching id, if none found return null.
    final Paddock tempField = farmerFields.stream().filter(field -> field.getID().equals(
        position)).findFirst().orElse(null);

    if (tempField != null)
    {
      farmerFields.remove(tempField);
      final Paddock field = getColony().getPaddock(position);
      field.setTaken(false);
      field.setOwner("");
      @NotNull
      final PaddockTileEntity paddockTileEntity = (PaddockTileEntity) getColony().getWorld().getTileEntity(
          field.getID());
      getColony().getWorld().notifyBlockUpdate(paddockTileEntity.getPos(),
          getColony().getWorld().getBlockState(paddockTileEntity.getPos()),
          getColony().getWorld().getBlockState(paddockTileEntity.getPos()), BLOCK_UPDATE_FLAG);
      paddockTileEntity.setName(LanguageHandler.format(
          "com.minecolonies.coremod.gui.paddock.user", LanguageHandler.format(
              "com.minecolonies.coremod.gui.paddock.user.noone")));
    }
  }

  /**
   * Method called to assign a field to the farmer.
   *
   * @param position id of the field.
   */
  public void assignField(final BlockPos position)
  {
    final Paddock field = getColony().getPaddock(position);
    field.setTaken(true);
    field.setOwner(getWorker().getName());
    farmerFields.add(field);
  }

  /**
   * Provides a view of the shepherd building class.
   */
  public static class View extends AbstractBuildingWorker.View
  {

    /**
     * Checks if fields should be assigned manually.
     */
    private boolean assignFieldManually;

    /**
     * Contains a view object of all the fields in the colony.
     */
    @NotNull
    private List<PaddockView> fields = new ArrayList<>();

    /**
     * Name of the worker of the building.
     */
    private String workerName;

    /**
     * The amount of fields the farmer owns.
     */
    private int amountOfFields;

    /**
     * Creates the view representation of the building.
     *
     * @param c the colony.
     * @param l the location.
     */
    public View(final ColonyView c, @NotNull final BlockPos l)
    {
      super(c, l);
    }

    @NotNull
    public Skill getPrimarySkill()
    {
      return Skill.ENDURANCE;
    }

    @NotNull
    public Skill getSecondarySkill()
    {
      return Skill.DEXTERITY;
    }

    @Override
    @NotNull
    public Window getWindow()
    {
      return new WindowHutShepherd(this);
    }

    @Override
    public void deserialize(@NotNull final ByteBuf buf)
    {
      fields = new ArrayList<>();
      super.deserialize(buf);
      assignFieldManually = buf.readBoolean();
      final int size = buf.readInt();
      for (int i = 1; i <= size; i++)
      {
        @NotNull
        final PaddockView fieldView = new PaddockView();
        fieldView.deserialize(buf);
        fields.add(fieldView);
        if (fieldView.isTaken())
        {
          amountOfFields++;
        }
      }
      workerName = ByteBufUtils.readUTF8String(buf);
    }

    /**
     * Should the farmer be assigned manually to the fields.
     *
     * @return true if yes.
     */
    public boolean assignFieldManually()
    {
      return assignFieldManually;
    }

    /**
     * Getter of the fields list.
     *
     * @return an unmodifiable List.
     */
    @NotNull
    public List<PaddockView> getFields()
    {
      return Collections.unmodifiableList(fields);
    }

    /**
     * Getter of the worker name.
     *
     * @return the name of the worker.
     */
    public String getWorkerName()
    {
      return workerName;
    }

    /**
     * Getter for amount of fields.
     *
     * @return the amount of fields.
     */
    public int getAmountOfFields()
    {
      return amountOfFields;
    }

    /**
     * Sets the assignedFieldManually in the view.
     *
     * @param assignFieldManually variable to set.
     */
    public void setAssignFieldManually(final boolean assignFieldManually)
    {
      MineColonies.getNetwork().sendToServer(new AssignmentModePaddockMessage(this, assignFieldManually));
      this.assignFieldManually = assignFieldManually;
    }

    /**
     * Change a field at a certain position.
     *
     * @param id          the position of the field.
     * @param addNewField should new field be added.
     * @param row         the row of the field.
     */
    public void changeFields(final BlockPos id, final boolean addNewField, final int row)
    {
      MineColonies.getNetwork().sendToServer(new AssignPaddockMessage(this, addNewField, id));
      fields.get(row).setTaken(addNewField);

      if (addNewField)
      {
        fields.get(row).setOwner(workerName);
        amountOfFields++;
      }
      else
      {
        fields.get(row).setOwner("");
        amountOfFields--;
      }
    }

  }

}

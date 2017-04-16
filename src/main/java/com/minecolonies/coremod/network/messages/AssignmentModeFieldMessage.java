package com.minecolonies.coremod.network.messages;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.minecolonies.coremod.colony.Colony;
import com.minecolonies.coremod.colony.ColonyManager;
import com.minecolonies.coremod.colony.buildings.BuildingFarmer;
import com.minecolonies.coremod.colony.permissions.Permissions;
import com.minecolonies.coremod.util.BlockPosUtil;

import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;

/**
 * Message to change the assignmentMode of the fields of the farmer.
 */
public class AssignmentModeFieldMessage extends AbstractMessage<AssignmentModeFieldMessage, IMessage>
{

    private int      colonyId;
    private BlockPos buildingId;
    private boolean  assignmentMode;

    /**
     * Empty standard constructor.
     */
    public AssignmentModeFieldMessage()
    {
        super();
    }

    /**
     * Creates object for the assignmentMode message.
     *
     * @param building       View of the building to read data from.
     * @param assignmentMode assignmentMode of the particular farmer.
     */
    public AssignmentModeFieldMessage(@NotNull final BuildingFarmer.View building, final boolean assignmentMode)
    {
        super();
        this.colonyId = building.getColony().getID();
        this.buildingId = building.getID();
        this.assignmentMode = assignmentMode;
    }
    
    @Override
    public void fromBytes(@NotNull final ByteBuf buf)
    {
        colonyId = buf.readInt();
        buildingId = BlockPosUtil.readFromByteBuf(buf);
        assignmentMode = buf.readBoolean();
    }

    @Override
    public void toBytes(@NotNull final ByteBuf buf)
    {
        buf.writeInt(colonyId);
        BlockPosUtil.writeToByteBuf(buf, buildingId);
        buf.writeBoolean(assignmentMode);
    }

    @Override
    public void messageOnServerThread(final AssignmentModeFieldMessage message, final EntityPlayerMP player)
    {
        final Colony colony = ColonyManager.getColony(message.colonyId);
        if (colony != null)
        {
            //Verify player has permission to change this huts settings
            if (!colony.getPermissions().hasPermission(player, Permissions.Action.MANAGE_HUTS))
            {
                return;
            }

            @Nullable final BuildingFarmer building = colony.getBuilding(message.buildingId, BuildingFarmer.class);
            if (building != null)
            {
                building.setAssignManually(message.assignmentMode);
            }
        }
    }
}

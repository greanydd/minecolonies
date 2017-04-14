package com.minecolonies.coremod.network.messages;

import com.minecolonies.coremod.colony.Colony;
import com.minecolonies.coremod.colony.ColonyManager;
import com.minecolonies.coremod.colony.buildings.BuildingFarmer;
import com.minecolonies.coremod.colony.buildings.BuildingShepherd;
import com.minecolonies.coremod.colony.permissions.Permissions;
import com.minecolonies.coremod.util.BlockPosUtil;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Message which handles the assignment of paddock to shepherds.
 */
public class AssignPaddockMessage extends AbstractMessage<AssignPaddockMessage, IMessage>
{

    private int      colonyId;
    private BlockPos buildingId;
    private boolean  assign;
    private BlockPos field;

    /**
     * Empty standard constructor.
     */
    public AssignPaddockMessage()
    {
        super();
    }

    /**
     * Creates the message to assign a paddock.
     *
     * @param building the farmer to assign to or release from.
     * @param assign   assign if true, free if false.
     * @param field    the field to assign or release.
     */
    public AssignPaddockMessage(@NotNull final BuildingShepherd.View building, final boolean assign, final BlockPos field)
    {
        super();
        this.colonyId = building.getColony().getID();
        this.buildingId = building.getID();
        this.assign = assign;
        this.field = field;
    }

    @Override
    public void fromBytes(@NotNull final ByteBuf buf)
    {
        colonyId = buf.readInt();
        buildingId = BlockPosUtil.readFromByteBuf(buf);
        assign = buf.readBoolean();
        field = BlockPosUtil.readFromByteBuf(buf);
    }

    @Override
    public void toBytes(@NotNull final ByteBuf buf)
    {
        buf.writeInt(colonyId);
        BlockPosUtil.writeToByteBuf(buf, buildingId);
        buf.writeBoolean(assign);
        BlockPosUtil.writeToByteBuf(buf, field);
    }

    @Override
    public void messageOnServerThread(final AssignPaddockMessage message, final EntityPlayerMP player)
    {
        final Colony colony = ColonyManager.getColony(message.colonyId);
        if (colony != null)
        {
            //Verify player has permission to change this huts settings
            if (!colony.getPermissions().hasPermission(player, Permissions.Action.MANAGE_HUTS))
            {
                return;
            }

            @Nullable final BuildingShepherd building = colony.getBuilding(message.buildingId, BuildingShepherd.class);
            if (building != null)
            {
                if (message.assign)
                {
                    building.assignField(message.field);
                }
                else
                {
                    building.freeField(message.field);
                }
            }
        }
    }
}


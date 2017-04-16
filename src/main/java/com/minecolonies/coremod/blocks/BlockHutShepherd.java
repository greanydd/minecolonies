
package com.minecolonies.coremod.blocks;

import org.jetbrains.annotations.NotNull;

/**
 * Hut for the Shepherd.
 * No different from {@link AbstractBlockHut}
 */

public class BlockHutShepherd extends AbstractBlockHut
{
  protected BlockHutShepherd()
  {
    //No different from Abstract parent
    super();
  }

  @NotNull
  @Override
  public String getName()
  {
    return "blockHutShepherd";
  }
  
}

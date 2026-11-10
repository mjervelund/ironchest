package com.progwml6.ironchest.common.block.entity;

import com.progwml6.ironchest.common.block.IronChestsTypes;
import com.progwml6.ironchest.common.network.TopStacksSyncPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jspecify.annotations.Nullable;

public interface ICrystalChest {

  default NonNullList<ItemStack> buildItemStackDataList() {
    if (this.getChestType().isTransparent()) {
      NonNullList<ItemStack> sortList = NonNullList.<ItemStack>withSize(this.getTopItems().size(), ItemStack.EMPTY);

      int pos = 0;

      for (ItemStack is : this.getTopItems()) {
        if (!is.isEmpty()) {
          sortList.set(pos, is);
        } else {
          sortList.set(pos, ItemStack.EMPTY);
        }

        pos++;
      }

      return sortList;
    }

    return NonNullList.<ItemStack>withSize(this.getTopItems().size(), ItemStack.EMPTY);
  }

  IronChestsTypes getChestType();

  default void sendTopStacksPacket() {
    NonNullList<ItemStack> stacks = this.buildItemStackDataList();

    if (this.getChestLevel() != null && this.getChestLevel() instanceof ServerLevel serverLevel && !this.getChestLevel().isClientSide()) {
      PacketDistributor.sendToPlayersTrackingChunk(serverLevel, serverLevel.getChunkAt(this.getChestWorldPosition()).getPos(), new TopStacksSyncPacket(this.getChestWorldPosition(), stacks));
    }
  }

  NonNullList<ItemStack> getTopItems();

  @Nullable Level getChestLevel();

  BlockPos getChestWorldPosition();

  void receiveMessageFromServer(NonNullList<ItemStack> topStacks);

  /**
   * The chest's top stacks computed from its current items (merged by item and components, largest first), without
   * changing any state. Used by {@link #sortTopStacks()} and for the block entity's update tag.
   */
  default NonNullList<ItemStack> computeTopStacks() {
    NonNullList<ItemStack> topStacks = NonNullList.<ItemStack>withSize(this.getTopItems().size(), ItemStack.EMPTY);

    if (!this.getChestType().isTransparent()) {
      return topStacks;
    }

    NonNullList<ItemStack> tempCopy = NonNullList.<ItemStack>withSize(IronChestsTypes.CRYSTAL.size, ItemStack.EMPTY);

    int compressedIdx = 0;

    mainLoop:
    for (int i = 0; i < IronChestsTypes.CRYSTAL.size; i++) {
      ItemStack itemStack = this.getCurrentItems().get(i);

      if (!itemStack.isEmpty()) {
        for (int j = 0; j < compressedIdx; j++) {
          ItemStack tempCopyStack = tempCopy.get(j);

          if (ItemStack.isSameItemSameComponents(tempCopyStack, itemStack)) {
            if (itemStack.getCount() != tempCopyStack.getCount()) {
              tempCopyStack.grow(itemStack.getCount());
            }

            continue mainLoop;
          }
        }

        tempCopy.set(compressedIdx, itemStack.copy());

        compressedIdx++;
      }
    }

    tempCopy.sort((stack1, stack2) -> {
      if (stack1.isEmpty()) {
        return 1;
      } else if (stack2.isEmpty()) {
        return -1;
      } else {
        return stack2.getCount() - stack1.getCount();
      }
    });

    int slot = 0;

    for (ItemStack itemStack : tempCopy) {
      if (!itemStack.isEmpty() && itemStack.getCount() > 0) {
        if (slot == topStacks.size()) {
          break;
        }

        topStacks.set(slot, itemStack);

        slot++;
      }
    }

    return topStacks;
  }

  default void sortTopStacks() {
    if (!this.getChestType().isTransparent() || (this.getChestLevel() != null && this.getChestLevel().isClientSide())) {
      return;
    }

    boolean hasStuff = false;

    for (ItemStack itemStack : this.getCurrentItems()) {
      if (!itemStack.isEmpty()) {
        hasStuff = true;
        break;
      }
    }

    if (!hasStuff && this.getHadStuff()) {
      this.setHadStuff(false);

      for (int i = 0; i < this.getTopItems().size(); i++) {
        this.getTopItems().set(i, ItemStack.EMPTY);
      }

      if (this.getChestLevel() != null) {
        BlockState blockState = this.getChestLevel().getBlockState(this.getChestWorldPosition());

        this.getChestLevel().sendBlockUpdated(this.getChestWorldPosition(), blockState, blockState, 3);
      }

      // The top stacks reach clients only through this packet (no update packet), so the now empty list must be sent too.
      this.sendTopStacksPacket();

      return;
    }

    if (!hasStuff) {
      // Still empty: clients already show no top stacks. (Falling through would flip hadStuff and empty the chest
      // again on the next poll, a sync every other tick.)
      return;
    }

    this.setHadStuff(true);

    NonNullList<ItemStack> topStacks = this.computeTopStacks();

    // getItem() marks the chest touched, so automation polling it (hoppers, pipes) re-sorts it every tick: only update
    // and resend the top stacks when they changed.
    boolean changed = false;

    for (int i = 0; i < this.getTopItems().size(); i++) {
      if (!ItemStack.matches(this.getTopItems().get(i), topStacks.get(i))) {
        changed = true;
        break;
      }
    }

    if (!changed) {
      return;
    }

    for (int i = 0; i < this.getTopItems().size(); i++) {
      this.getTopItems().set(i, topStacks.get(i));
    }

    if (this.getChestLevel() != null) {
      BlockState blockState = this.getChestLevel().getBlockState(this.getChestWorldPosition());

      this.getChestLevel().sendBlockUpdated(this.getChestWorldPosition(), blockState, blockState, 3);
    }

    this.sendTopStacksPacket();
  }

  NonNullList<ItemStack> getCurrentItems();

  void setHadStuff(boolean hadStuff);

  boolean getHadStuff();
}

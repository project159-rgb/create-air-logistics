package com.createairlogistics.content.dock;

import net.minecraft.core.Direction;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.block.Rotation;
import org.jetbrains.annotations.Nullable;

/**
 * Sky Dock'un wrench ile açılan tek şaft soketinin konumu (mutlak yön).
 * Ön yüz (gemi bağlantı ağzı) hiçbir zaman soket olamaz.
 */
public enum DockShaftSocket implements StringRepresentable {
    NONE("none", null),
    DOWN("down", Direction.DOWN),
    UP("up", Direction.UP),
    NORTH("north", Direction.NORTH),
    SOUTH("south", Direction.SOUTH),
    WEST("west", Direction.WEST),
    EAST("east", Direction.EAST);

    private final String name;
    @Nullable
    private final Direction direction;

    DockShaftSocket(String name, @Nullable Direction direction) {
        this.name = name;
        this.direction = direction;
    }

    @Nullable
    public Direction direction() {
        return direction;
    }

    public boolean isOpen() {
        return direction != null;
    }

    public static DockShaftSocket of(@Nullable Direction direction) {
        if (direction == null) {
            return NONE;
        }
        return switch (direction) {
            case DOWN -> DOWN;
            case UP -> UP;
            case NORTH -> NORTH;
            case SOUTH -> SOUTH;
            case WEST -> WEST;
            case EAST -> EAST;
        };
    }

    public DockShaftSocket rotate(Rotation rotation) {
        if (direction == null || direction.getAxis().isVertical()) {
            return this;
        }
        return of(rotation.rotate(direction));
    }

    @Override
    public String getSerializedName() {
        return name;
    }
}

package dev.kristian.nullmod.entity.nullroot;

import java.util.Locale;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.EntityDimensions;

/**
 * The shapes a NULLROOT grows into. Every variant is rooted in a block face and never moves;
 * they differ in size, toughness, how far they notice players and whether (and how far) they
 * reach for them.
 */
public enum NullrootVariant {
    /** A small black plant. Only watches. */
    SMALL("small", 0.5F, 0.7F, 8.0F, Behaviour.OBSERVER, 20.0, 0.0F, 0.0F, 0, 0.45F),
    /** A tall black plant with a heavy crown. Grabs whoever walks right up to it. */
    LARGE("large", 1.1F, 2.2F, 30.0F, Behaviour.HUNTER, 16.0, 3.2F, 0.035F, 220, 1.7F),
    /** A knot of long tendrils. The hunter. */
    TENDRIL("tendril", 0.7F, 1.5F, 20.0F, Behaviour.HUNTER, 18.0, 7.0F, 0.055F, 200, 0.75F),
    /** Hangs from cave ceilings and lets something down. */
    CEILING("ceiling", 0.8F, 1.4F, 16.0F, Behaviour.HUNTER, 12.0, 5.5F, 0.06F, 190, 1.0F),
    /** Grows sideways out of cave walls. */
    WALL("wall", 0.9F, 0.9F, 18.0F, Behaviour.HUNTER, 14.0, 4.5F, 0.05F, 190, 0.5F),
    /** A lumpy cluster deep underground. */
    UNDERGROUND("underground", 1.0F, 1.3F, 26.0F, Behaviour.HUNTER, 16.0, 6.0F, 0.05F, 210, 0.85F),
    /** The large mass at the centre of a colony; captured players are pulled into it. */
    CENTRAL("central", 2.4F, 2.6F, 80.0F, Behaviour.ANCHOR, 32.0, 4.0F, 0.04F, 230, 1.6F);

    /** What a variant does when a player comes near. */
    public enum Behaviour {
        /** Only turns toward players. */
        OBSERVER,
        /** Slowly reaches for players in range and grabs them. */
        HUNTER,
        /** Pulses, anchors captures made by its colony and grabs players who come too close. */
        ANCHOR
    }

    private static final NullrootVariant[] VALUES = values();

    private final String id;
    private final float width;
    private final float height;
    private final float maxHealth;
    private final Behaviour behaviour;
    private final double detectRange;
    private final float maxReach;
    private final float reachSpeed;
    private final int captureTicks;
    private final float rootOffset;
    private final EntityDimensions dimensions;

    NullrootVariant(String id, float width, float height, float maxHealth, Behaviour behaviour, double detectRange,
                    float maxReach, float reachSpeed, int captureTicks, float rootOffset) {
        this.id = id;
        this.width = width;
        this.height = height;
        this.maxHealth = maxHealth;
        this.behaviour = behaviour;
        this.detectRange = detectRange;
        this.maxReach = maxReach;
        this.reachSpeed = reachSpeed;
        this.captureTicks = captureTicks;
        this.rootOffset = rootOffset;
        this.dimensions = EntityDimensions.scalable(width, height);
    }

    public String id() {
        return id;
    }

    public float width() {
        return width;
    }

    public float height() {
        return height;
    }

    public EntityDimensions dimensions() {
        return dimensions;
    }

    public float maxHealth() {
        return maxHealth;
    }

    public Behaviour behaviour() {
        return behaviour;
    }

    /** Players within this many blocks of the organism get its attention. */
    public double detectRange() {
        return detectRange;
    }

    /** Longest reach of its tendril in blocks; 0 for observers. */
    public float maxReach() {
        return maxReach;
    }

    /** Blocks per tick a reaching tendril grows. */
    public float reachSpeed() {
        return reachSpeed;
    }

    /** Base length of a capture in ticks before it ends (8-12 seconds). */
    public int captureTicks() {
        return captureTicks;
    }

    /** How far from the block face (in blocks, fully grown) the tendrils come out. */
    public float rootOffset() {
        return rootOffset;
    }

    public boolean reaches() {
        return behaviour != Behaviour.OBSERVER && maxReach > 0.0F;
    }

    /** Damage it has to take while holding someone before it lets go. */
    public float releaseDamage() {
        return Math.max(5.0F, maxHealth * (behaviour == Behaviour.ANCHOR ? 0.15F : 0.3F));
    }

    /** The block face this variant grows out of when nothing else says otherwise. */
    public Direction defaultFace() {
        return switch (this) {
            case CEILING -> Direction.DOWN;
            case WALL -> Direction.NORTH;
            default -> Direction.UP;
        };
    }

    /** True if this variant can grow out of the given face (UP = floor, DOWN = ceiling, else walls). */
    public boolean fitsFace(Direction face) {
        return switch (this) {
            case CEILING -> face == Direction.DOWN;
            case WALL -> face.getAxis().isHorizontal();
            default -> face == Direction.UP;
        };
    }

    public static NullrootVariant byIndex(int index) {
        return index >= 0 && index < VALUES.length ? VALUES[index] : SMALL;
    }

    public static NullrootVariant byId(String id) {
        if (id != null) {
            String key = id.toLowerCase(Locale.ROOT);
            for (NullrootVariant variant : VALUES) {
                if (variant.id.equals(key)) {
                    return variant;
                }
            }
        }
        return SMALL;
    }
}

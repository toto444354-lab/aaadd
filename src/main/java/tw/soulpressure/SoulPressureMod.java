package tw.soulpressure;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.ProjectileUtil;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 魂壓 / Soul Pressure. The server owns ALL targeting, state, poses and damage.
 * Area fields are anchored in the world, not bound to the player's position.
 */
public final class SoulPressureMod implements ModInitializer {
    public static final Identifier AREA_PACKET = new Identifier("soulpressure", "area_toggle");
    public static final Identifier SINGLE_PACKET = new Identifier("soulpressure", "single_toggle");

    public static final double AREA_RADIUS = 10.0D;
    public static final double AREA_HALF_HEIGHT = 10.0D;
    public static final double SINGLE_RANGE = 20.0D;

    private static final DustParticleEffect GRAY_DUST =
        new DustParticleEffect(new Vector3f(0.38F, 0.38F, 0.41F), 1.25F);
    private static final DustParticleEffect DARK_DUST =
        new DustParticleEffect(new Vector3f(0.20F, 0.20F, 0.23F), 1.0F);

    private static final Map<UUID, AreaField> AREA_FIELDS = new HashMap<>();
    private static final Map<UUID, SingleField> SINGLE_FIELDS = new HashMap<>();
    private static final Map<UUID, FrozenPose> FROZEN = new HashMap<>();
    private static int tickCounter;

    private record AreaField(UUID owner, RegistryKey<World> dimension, Vec3d center) {}
    private record SingleField(UUID owner, RegistryKey<World> dimension, UUID target) {}

    private static final class FrozenPose {
        final RegistryKey<World> dimension;
        final Vec3d position;
        final float yaw;
        final float pitch;
        final float headYaw;
        final float bodyYaw;
        final boolean previousNoGravity;

        FrozenPose(LivingEntity entity) {
            dimension = entity.getWorld().getRegistryKey();
            position = entity.getPos();
            yaw = entity.getYaw();
            pitch = entity.getPitch();
            headYaw = entity.getHeadYaw();
            bodyYaw = entity.getBodyYaw();
            previousNoGravity = entity.hasNoGravity();
        }

        void hold(LivingEntity entity) {
            entity.setNoGravity(true);
            entity.setVelocity(Vec3d.ZERO);
            // Keep airborne entities at the EXACT height/position where they were caught.
            entity.refreshPositionAndAngles(position.x, position.y, position.z, yaw, pitch);
            entity.setHeadYaw(headYaw);
            entity.setBodyYaw(bodyYaw);
            entity.fallDistance = 0.0F;
        }

        void release(Entity entity) {
            entity.setNoGravity(previousNoGravity);
            entity.setVelocity(Vec3d.ZERO);
            entity.fallDistance = 0.0F;
        }
    }

    /** Mixin calls this on the server to block movement and turning. */
    public static boolean isFrozen(UUID entityId) {
        return FROZEN.containsKey(entityId);
    }

    @Override
    public void onInitialize() {
        // Older (1.20.1) networking callbacks execute off-thread: always schedule onto server.
        ServerPlayNetworking.registerGlobalReceiver(AREA_PACKET,
            (server, player, handler, buffer, responseSender) ->
                server.execute(() -> toggleArea(player))
        );
        ServerPlayNetworking.registerGlobalReceiver(SINGLE_PACKET,
            (server, player, handler, buffer, responseSender) ->
                server.execute(() -> toggleSingle(player))
        );

        ServerTickEvents.START_SERVER_TICK.register(SoulPressureMod::onServerTickStart);
        ServerTickEvents.END_SERVER_TICK.register(SoulPressureMod::onServerTickEnd);

        // A SINGLE regular left-click defeats a soul-pressured mob, regardless of armor/HP.
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (world.isClient || !(player instanceof ServerPlayerEntity)
                    || !(entity instanceof LivingEntity living)
                    || !FROZEN.containsKey(entity.getUuid())) {
                return ActionResult.PASS;
            }
            living.kill(); // Vanilla death pipeline and drops, not discard().
            return ActionResult.SUCCESS;
        });
    }

    private static void toggleArea(ServerPlayerEntity player) {
        if (player.isSpectator()) return;
        UUID id = player.getUuid();
        if (AREA_FIELDS.remove(id) != null) {
            actionbar(player, "範圍魂壓已解除");
        } else {
            // Snap only once at activation; moving the player never moves this circle.
            AREA_FIELDS.put(id, new AreaField(id, player.getWorld().getRegistryKey(), player.getPos()));
            actionbar(player, "範圍魂壓已展開 §7｜§f固定位置，半徑 10 格");
        }
    }

    private static void toggleSingle(ServerPlayerEntity player) {
        if (player.isSpectator()) return;
        UUID id = player.getUuid();
        if (SINGLE_FIELDS.remove(id) != null) {
            actionbar(player, "單體魂壓已解除");
            return;
        }
        LivingEntity target = findAimedMob(player);
        if (target == null) {
            actionbar(player, "請用準星瞄準 20 格內的生物（不能隔牆）");
            return;
        }
        SINGLE_FIELDS.put(id, new SingleField(id, player.getWorld().getRegistryKey(), target.getUuid()));
        actionbar(player, "單體魂壓已鎖定：" + target.getName().getString());
    }

    private static LivingEntity findAimedMob(ServerPlayerEntity player) {
        Vec3d eye = player.getCameraPosVec(1.0F);
        Vec3d direction = player.getRotationVec(1.0F);
        Vec3d reach = direction.multiply(SINGLE_RANGE);
        Vec3d end = eye.add(reach);

        // Do not select mobs behind walls; the player's block ray limits target distance.
        HitResult blockHit = player.raycast(SINGLE_RANGE, 1.0F, false);
        double maxDistanceSquared = SINGLE_RANGE * SINGLE_RANGE;
        if (blockHit.getType() == HitResult.Type.BLOCK) {
            maxDistanceSquared = eye.squaredDistanceTo(blockHit.getPos());
        }

        EntityHitResult hit = ProjectileUtil.raycast(
            player, eye, end,
            player.getBoundingBox().stretch(reach).expand(1.0D),
            entity -> entity instanceof LivingEntity
                && !(entity instanceof PlayerEntity)
                && entity.isAlive() && entity.canHit(),
            maxDistanceSquared
        );
        return hit != null && hit.getEntity() instanceof LivingEntity living ? living : null;
    }

    private static void onServerTickStart(MinecraftServer server) {
        tickCounter++;
        AREA_FIELDS.values().removeIf(area -> server.getPlayerManager().getPlayer(area.owner()) == null);
        SINGLE_FIELDS.values().removeIf(single -> server.getPlayerManager().getPlayer(single.owner()) == null);

        // Rebuild the desired set to support overlapping fields and instant release.
        Map<UUID, LivingEntity> desired = new HashMap<>();
        for (AreaField field : AREA_FIELDS.values()) {
            ServerWorld world = server.getWorld(field.dimension());
            if (world == null) continue;
            Vec3d pos = field.center();
            Box bounds = new Box(
                pos.x - AREA_RADIUS, pos.y - AREA_HALF_HEIGHT, pos.z - AREA_RADIUS,
                pos.x + AREA_RADIUS, pos.y + AREA_HALF_HEIGHT, pos.z + AREA_RADIUS
            );
            for (LivingEntity entity : world.getEntitiesByClass(LivingEntity.class, bounds,
                    e -> e.isAlive() && !(e instanceof PlayerEntity))) {
                double dx = entity.getX() - pos.x;
                double dz = entity.getZ() - pos.z;
                // Round 10-block circle in X/Z, vertical range ±10 from cast height.
                if (dx * dx + dz * dz <= AREA_RADIUS * AREA_RADIUS) {
                    desired.put(entity.getUuid(), entity);
                }
            }
        }
        for (SingleField field : SINGLE_FIELDS.values()) {
            ServerWorld world = server.getWorld(field.dimension());
            if (world == null) continue;
            Entity entity = world.getEntity(field.target());
            if (entity instanceof LivingEntity living && !(entity instanceof PlayerEntity) && living.isAlive()) {
                desired.put(living.getUuid(), living);
            }
        }

        // Any creature outside every active pressure field is completely released.
        Iterator<Map.Entry<UUID, FrozenPose>> iterator = FROZEN.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, FrozenPose> entry = iterator.next();
            if (desired.containsKey(entry.getKey())) continue;
            ServerWorld world = server.getWorld(entry.getValue().dimension);
            if (world != null) {
                Entity entity = world.getEntity(entry.getKey());
                if (entity != null) entry.getValue().release(entity);
            }
            iterator.remove();
        }
        for (LivingEntity entity : desired.values()) {
            FrozenPose pose = FROZEN.computeIfAbsent(entity.getUuid(), unused -> new FrozenPose(entity));
            pose.hold(entity);
        }
    }

    private static void onServerTickEnd(MinecraftServer server) {
        // Correct movements from knockback/teleport/physics happening later in the tick.
        for (Map.Entry<UUID, FrozenPose> entry : FROZEN.entrySet()) {
            ServerWorld world = server.getWorld(entry.getValue().dimension);
            if (world == null) continue;
            Entity entity = world.getEntity(entry.getKey());
            if (entity instanceof LivingEntity living && living.isAlive()) {
                entry.getValue().hold(living);
                if (tickCounter % 3 == 0) spawnDownwardWeight(world, living);
            }
        }
        // Static circle and single-target halo, visually separate from players.
        if (tickCounter % 8 == 0) {
            for (AreaField field : AREA_FIELDS.values()) {
                ServerWorld world = server.getWorld(field.dimension());
                if (world != null) spawnAreaRing(world, field.center());
            }
            for (SingleField field : SINGLE_FIELDS.values()) {
                ServerWorld world = server.getWorld(field.dimension());
                if (world == null) continue;
                Entity target = world.getEntity(field.target());
                if (target instanceof LivingEntity living && living.isAlive()) {
                    spawnSingleHalo(world, living);
                }
            }
        }
    }

    /** Small gray particle shafts fall from above the immobilized creature, never rise. */
    private static void spawnDownwardWeight(ServerWorld world, LivingEntity entity) {
        Vec3d p = entity.getPos();
        double height = entity.getHeight();
        double phase = tickCounter * 0.13D;
        for (int i = 0; i < 5; i++) {
            double angle = phase + i * Math.PI * 2.0D / 5.0D;
            double distance = Math.max(0.4D, entity.getWidth() * 0.7D);
            double x = p.x + Math.cos(angle) * distance;
            double z = p.z + Math.sin(angle) * distance;
            // count=0 makes xSpeed/ySpeed/zSpeed a specific velocity (visible downward fall).
            world.spawnParticles(i % 2 == 0 ? DARK_DUST : GRAY_DUST,
                x, p.y + height + 1.5D + i * 0.18D, z,
                0, 0.0D, -0.22D, 0.0D, 1.0D);
        }
        if (tickCounter % 6 == 0) {
            world.spawnParticles(ParticleTypes.SMOKE,
                p.x, p.y + height * 0.55D, p.z,
                3, 0.25D, height * 0.25D, 0.25D, 0.015D);
        }
    }

    /** Grey, fixed 10-block circumference; player movement does not change the center. */
    private static void spawnAreaRing(ServerWorld world, Vec3d center) {
        for (int i = 0; i < 48; i++) {
            double angle = 2.0D * Math.PI * i / 48.0D;
            double x = center.x + Math.cos(angle) * AREA_RADIUS;
            double z = center.z + Math.sin(angle) * AREA_RADIUS;
            world.spawnParticles(i % 3 == 0 ? DARK_DUST : GRAY_DUST,
                x, center.y + 0.15D, z, 1, 0.03D, 0.03D, 0.03D, 0.0D);
        }
        // Sparse downward motes inside the field convey an oppressive weight.
        for (int i = 0; i < 12; i++) {
            double angle = 2.0D * Math.PI * i / 12.0D + tickCounter * 0.03D;
            double radius = 2.0D + (i % 4) * 2.0D;
            world.spawnParticles(GRAY_DUST,
                center.x + Math.cos(angle) * radius, center.y + 3.0D, center.z + Math.sin(angle) * radius,
                0, 0.0D, -0.12D, 0.0D, 1.0D);
        }
    }

    private static void spawnSingleHalo(ServerWorld world, LivingEntity entity) {
        double y = entity.getY() + entity.getHeight() * 0.6D;
        double radius = Math.max(0.8D, entity.getWidth() * 0.9D);
        for (int i = 0; i < 18; i++) {
            double angle = 2.0D * Math.PI * i / 18.0D;
            world.spawnParticles(DARK_DUST,
                entity.getX() + Math.cos(angle) * radius, y,
                entity.getZ() + Math.sin(angle) * radius,
                1, 0.01D, 0.01D, 0.01D, 0.0D);
        }
    }

    private static void actionbar(ServerPlayerEntity player, String message) {
        player.sendMessage(Text.literal(message).formatted(Formatting.GRAY), true);
    }
}

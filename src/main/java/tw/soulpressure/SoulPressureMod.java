package tw.soulpressure;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.ProjectileUtil;
import net.minecraft.network.PacketByteBuf;
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
    public static final Identifier FROZEN_SYNC_PACKET = new Identifier("soulpressure", "frozen_sync");

    public static final double AREA_RADIUS = 30.0D;
    public static final double AREA_HALF_HEIGHT = 30.0D;
    public static final double SINGLE_RANGE = 50.0D;

    private static final DustParticleEffect GRAY_DUST =
        new DustParticleEffect(new Vector3f(0.52F, 0.52F, 0.56F), 1.4F);
    private static final DustParticleEffect DARK_DUST =
        new DustParticleEffect(new Vector3f(0.18F, 0.18F, 0.21F), 1.15F);
    private static final DustParticleEffect LIGHT_DUST =
        new DustParticleEffect(new Vector3f(0.70F, 0.72F, 0.76F), 1.0F);

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

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
            server.execute(() -> syncAllFrozenToPlayer(handler.player))
        );

        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            AREA_FIELDS.clear();
            SINGLE_FIELDS.clear();
            FROZEN.clear();
            tickCounter = 0;
        });

        AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (world.isClient || !(player instanceof ServerPlayerEntity)
                    || !(entity instanceof LivingEntity living)
                    || !FROZEN.containsKey(entity.getUuid())) {
                return ActionResult.PASS;
            }
            living.kill();
            return ActionResult.SUCCESS;
        });
    }

    private static void toggleArea(ServerPlayerEntity player) {
        if (player.isSpectator()) return;
        UUID id = player.getUuid();
        if (AREA_FIELDS.remove(id) != null) {
            actionbar(player, "範圍魂壓已解除");
        } else {
            AREA_FIELDS.put(id, new AreaField(id, player.getWorld().getRegistryKey(), player.getPos()));
            actionbar(player, "範圍魂壓已展開 §7｜§f固定位置，半徑 30 格");
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
            actionbar(player, "請用準星瞄準 50 格內的生物（不能隔牆）");
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

        Iterator<Map.Entry<UUID, FrozenPose>> iterator = FROZEN.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, FrozenPose> entry = iterator.next();
            if (desired.containsKey(entry.getKey())) continue;
            ServerWorld world = server.getWorld(entry.getValue().dimension);
            if (world != null) {
                Entity entity = world.getEntity(entry.getKey());
                if (entity != null) entry.getValue().release(entity);
            }
            broadcastFrozenState(server, entry.getKey(), false);
            iterator.remove();
        }
        for (LivingEntity entity : desired.values()) {
            if (!FROZEN.containsKey(entity.getUuid())) {
                FROZEN.put(entity.getUuid(), new FrozenPose(entity));
                broadcastFrozenState(server, entity.getUuid(), true);
            }
            FrozenPose pose = FROZEN.get(entity.getUuid());
            pose.hold(entity);
        }
    }

    private static void onServerTickEnd(MinecraftServer server) {
        for (Map.Entry<UUID, FrozenPose> entry : FROZEN.entrySet()) {
            ServerWorld world = server.getWorld(entry.getValue().dimension);
            if (world == null) continue;
            Entity entity = world.getEntity(entry.getKey());
            if (entity instanceof LivingEntity living && living.isAlive()) {
                entry.getValue().hold(living);
                if (tickCounter % 2 == 0) spawnDownwardWeight(world, living);
            }
        }
        if (tickCounter % 6 == 0) {
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

    private static void spawnDownwardWeight(ServerWorld world, LivingEntity entity) {
        Vec3d p = entity.getPos();
        double height = entity.getHeight();
        double phase = tickCounter * 0.16D;
        for (int i = 0; i < 10; i++) {
            double angle = phase + i * Math.PI * 2.0D / 10.0D;
            double distance = Math.max(0.45D, entity.getWidth() * (0.75D + (i % 2) * 0.2D));
            double x = p.x + Math.cos(angle) * distance;
            double z = p.z + Math.sin(angle) * distance;
            double topY = p.y + height + 2.6D + (i % 3) * 0.45D;
            world.spawnParticles(i % 2 == 0 ? DARK_DUST : GRAY_DUST,
                x, topY, z,
                0, 0.0D, -0.45D, 0.0D, 1.0D);
            world.spawnParticles(LIGHT_DUST,
                x, topY - 0.35D, z,
                0, 0.0D, -0.34D, 0.0D, 1.0D);
        }
        if (tickCounter % 4 == 0) {
            world.spawnParticles(ParticleTypes.SMOKE,
                p.x, p.y + height * 0.55D, p.z,
                6, 0.35D, height * 0.25D, 0.35D, 0.02D);
        }
    }

    private static void spawnAreaRing(ServerWorld world, Vec3d center) {
        for (int i = 0; i < 96; i++) {
            double angle = 2.0D * Math.PI * i / 96.0D;
            double x = center.x + Math.cos(angle) * AREA_RADIUS;
            double z = center.z + Math.sin(angle) * AREA_RADIUS;
            world.spawnParticles(i % 3 == 0 ? DARK_DUST : GRAY_DUST,
                x, center.y + 0.12D, z, 1, 0.04D, 0.02D, 0.04D, 0.0D);
        }
        for (int ring = 0; ring < 5; ring++) {
            double radius = 6.0D + ring * 5.0D;
            for (int i = 0; i < 14; i++) {
                double angle = 2.0D * Math.PI * i / 14.0D + tickCounter * 0.02D + ring * 0.17D;
                double x = center.x + Math.cos(angle) * radius;
                double z = center.z + Math.sin(angle) * radius;
                double topY = center.y + 7.0D + ring * 1.6D;
                world.spawnParticles(i % 2 == 0 ? DARK_DUST : LIGHT_DUST,
                    x, topY, z,
                    0, 0.0D, -0.42D, 0.0D, 1.0D);
            }
        }
        if (tickCounter % 12 == 0) {
            world.spawnParticles(ParticleTypes.LARGE_SMOKE,
                center.x, center.y + 1.2D, center.z,
                12, AREA_RADIUS * 0.35D, 0.5D, AREA_RADIUS * 0.35D, 0.01D);
        }
    }

    private static void spawnSingleHalo(ServerWorld world, LivingEntity entity) {
        double radius = Math.max(1.0D, entity.getWidth() * 1.05D);
        double midY = entity.getY() + entity.getHeight() * 0.6D;
        for (int i = 0; i < 22; i++) {
            double angle = 2.0D * Math.PI * i / 22.0D;
            world.spawnParticles(DARK_DUST,
                entity.getX() + Math.cos(angle) * radius, midY,
                entity.getZ() + Math.sin(angle) * radius,
                1, 0.01D, 0.01D, 0.01D, 0.0D);
        }
        for (int i = 0; i < 6; i++) {
            double angle = tickCounter * 0.08D + i * Math.PI / 3.0D;
            world.spawnParticles(LIGHT_DUST,
                entity.getX() + Math.cos(angle) * (radius + 0.15D), entity.getY() + entity.getHeight() + 1.8D,
                entity.getZ() + Math.sin(angle) * (radius + 0.15D),
                0, 0.0D, -0.4D, 0.0D, 1.0D);
        }
    }

    private static void syncAllFrozenToPlayer(ServerPlayerEntity player) {
        for (UUID uuid : FROZEN.keySet()) {
            sendFrozenState(player, uuid, true);
        }
    }

    private static void broadcastFrozenState(MinecraftServer server, UUID entityId, boolean frozen) {
        for (ServerPlayerEntity player : PlayerLookup.all(server)) {
            sendFrozenState(player, entityId, frozen);
        }
    }

    private static void sendFrozenState(ServerPlayerEntity player, UUID entityId, boolean frozen) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeUuid(entityId);
        buf.writeBoolean(frozen);
        ServerPlayNetworking.send(player, FROZEN_SYNC_PACKET, buf);
    }

    private static void actionbar(ServerPlayerEntity player, String message) {
        player.sendMessage(Text.literal(message).formatted(Formatting.GRAY), true);
    }
}

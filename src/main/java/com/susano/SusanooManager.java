package com.susano;

import net.minecraft.block.BlockState;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.damage.DamageTypes;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.tag.DamageTypeTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameRules;
import net.minecraft.world.RaycastContext;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Logique serveur : invocation, formes, attaques, bouclier de chakra, physique. */
public final class SusanooManager {
    private static final Map<UUID, SusanooEntity> ACTIVE = new HashMap<>();
    private static final UUID KB_ID = UUID.fromString("5b0f3f6e-2f4a-4c1e-9d55-2d6b7c1a8e01");

    // statistiques par forme (index = forme 1..4)
    private static final double[] REACH = {0, 4.5, 6, 9, 16};
    private static final float[] DMG = {0, 6, 10, 16, 30};
    private static final float[] KB = {0, 0.8f, 1.1f, 1.4f, 2.0f};
    private static final double[] ARC = {0, 0.35, 0.2, 0.0, -0.3};
    private static final float[] MELEE_BLOCK_HARDNESS = {0, 0f, 0.8f, 2.0f, 6.0f};
    private static final int[] MELEE_CD = {0, 4, 6, 8, 12};

    private static final double[] BEAM_RANGE = {0, 40, 55, 70, 110};
    private static final double[] BEAM_RADIUS = {0, 0.8, 1.0, 1.6, 3.0};
    private static final float[] BEAM_DMG = {0, 12, 18, 28, 60};
    private static final double[] BLAST_RADIUS = {0, 2, 3, 5, 9};
    private static final float[] BLAST_HARDNESS = {0, 1.5f, 3f, 6f, 6f};
    private static final int[] SPECIAL_CD = {0, 50, 60, 80, 140};

    private SusanooManager() { }

    // ------------------------------------------------------------ etat

    public static SusanooEntity get(ServerPlayerEntity p) {
        SusanooEntity e = ACTIVE.get(p.getUuid());
        if (e != null && e.isRemoved()) {
            ACTIVE.remove(p.getUuid());
            clearModifier(p);
            return null;
        }
        return e;
    }

    public static void clear() {
        ACTIVE.clear();
    }

    // ------------------------------------------------------------ invocation

    /** Clic droit sur un Mangekyo : invoque, change de couleur ou renvoie. */
    public static void toggleWith(ServerPlayerEntity p, SusanooColor color) {
        SusanooEntity e = get(p);
        if (e == null) {
            activate(p, color);
        } else if (e.getColorId() != color.id) {
            e.setColorId(color.id);
            playAt(p, SoundEvents.BLOCK_BEACON_POWER_SELECT, 1f, 1.2f);
        } else {
            dismiss(p);
        }
    }

    /** Touche G. */
    public static void toggleKey(ServerPlayerEntity p) {
        if (get(p) != null) {
            dismiss(p);
            return;
        }
        SusanooColor c = findColor(p);
        if (c == null) {
            p.sendMessage(Text.translatable("message.susanoo.need_mangekyo"), true);
            return;
        }
        activate(p, c);
    }

    private static SusanooColor findColor(ServerPlayerEntity p) {
        SusanooColor c = colorOf(p.getMainHandStack());
        if (c == null) c = colorOf(p.getOffHandStack());
        if (c == null) {
            for (ItemStack s : p.getInventory().main) {
                c = colorOf(s);
                if (c != null) break;
            }
        }
        return c;
    }

    private static SusanooColor colorOf(ItemStack s) {
        return s.getItem() instanceof SusanooItem si ? si.getColor() : null;
    }

    private static void activate(ServerPlayerEntity p, SusanooColor color) {
        ServerWorld w = p.getServerWorld();
        SusanooEntity e = SusanooMod.SUSANOO.create(w);
        if (e == null) return;
        e.setOwnerUuid(p.getUuid());
        e.setColorId(color.id);
        e.setForm(1);
        e.setShield(SusanooEntity.MAX_SHIELD[1]);
        e.refreshPositionAndAngles(p.getX(), p.getY(), p.getZ(), p.getYaw(), 0f);
        w.spawnEntity(e);
        ACTIVE.put(p.getUuid(), e);
        applyModifier(p, 1);
        playAt(p, SoundEvents.BLOCK_BEACON_ACTIVATE, 1.5f, 0.7f);
        p.sendMessage(Text.translatable("message.susanoo.summoned"), true);
    }

    public static void dismiss(ServerPlayerEntity p) {
        SusanooEntity e = ACTIVE.remove(p.getUuid());
        if (e != null) {
            finish(p, e);
        }
    }

    private static void finish(ServerPlayerEntity p, SusanooEntity e) {
        if (p != null) {
            clearModifier(p);
            playAt(p, SoundEvents.BLOCK_BEACON_DEACTIVATE, 1.5f, 0.8f);
        }
        e.discard();
    }

    /** Touche H : forme suivante (delta = +1) ou precedente (-1). */
    public static void changeForm(ServerPlayerEntity p, int delta) {
        SusanooEntity e = get(p);
        if (e == null) return;
        int old = e.getForm();
        int nf = Math.max(1, Math.min(SusanooEntity.MAX_FORM, old + delta));
        if (nf == old) return;
        float ratio = e.getShield() / SusanooEntity.MAX_SHIELD[old];
        e.setForm(nf);
        e.setShield(Math.max(1f, ratio * SusanooEntity.MAX_SHIELD[nf]));
        e.setAttackTicks(0);
        applyModifier(p, nf);
        playAt(p, nf > old ? SoundEvents.ENTITY_WITHER_SPAWN : SoundEvents.BLOCK_BEACON_DEACTIVATE, 0.6f, nf > old ? 1.4f : 1.0f);
        p.sendMessage(Text.translatable("message.susanoo.form." + nf), true);
    }

    // ------------------------------------------------------------ physique

    private static void applyModifier(ServerPlayerEntity p, int form) {
        EntityAttributeInstance a = p.getAttributeInstance(EntityAttributes.GENERIC_KNOCKBACK_RESISTANCE);
        if (a == null) return;
        a.removeModifier(KB_ID);
        double v = form >= 3 ? 1.0 : form == 2 ? 0.5 : 0.0;
        if (v > 0) {
            a.addTemporaryModifier(new EntityAttributeModifier(KB_ID, "susanoo", v, EntityAttributeModifier.Operation.ADDITION));
        }
    }

    private static void clearModifier(ServerPlayerEntity p) {
        EntityAttributeInstance a = p.getAttributeInstance(EntityAttributes.GENERIC_KNOCKBACK_RESISTANCE);
        if (a != null) a.removeModifier(KB_ID);
    }

    /** Appele par ALLOW_DAMAGE. Retourne true si les degats doivent passer. */
    public static boolean absorb(ServerPlayerEntity p, DamageSource src, float amount) {
        SusanooEntity e = get(p);
        if (e == null) return true;
        if (src.isIn(DamageTypeTags.BYPASSES_INVULNERABILITY)) return true;
        if (src.isOf(DamageTypes.FALL) || src.isOf(DamageTypes.FLY_INTO_WALL)) return false;
        if (src.isOf(DamageTypes.IN_FIRE) || src.isOf(DamageTypes.ON_FIRE)
                || src.isOf(DamageTypes.LAVA) || src.isOf(DamageTypes.HOT_FLOOR)) {
            p.extinguish();
            return false;
        }
        e.lastHitTime = p.getWorld().getTime();
        float left = e.getShield() - amount;
        playAt(p, SoundEvents.ENTITY_IRON_GOLEM_DAMAGE, 1f, 0.6f);
        if (left <= 0f) {
            e.setShield(0f);
            ACTIVE.remove(p.getUuid());
            playAt(p, SoundEvents.ITEM_SHIELD_BREAK, 1.5f, 0.6f);
            p.sendMessage(Text.translatable("message.susanoo.broken"), true);
            finish(p, e);
        } else {
            e.setShield(left);
        }
        return false;
    }

    // ------------------------------------------------------------ tick

    public static void tick(MinecraftServer server) {
        Iterator<Map.Entry<UUID, SusanooEntity>> it = ACTIVE.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, SusanooEntity> en = it.next();
            SusanooEntity e = en.getValue();
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(en.getKey());
            if (p == null || !p.isAlive() || p.isSpectator() || e.isRemoved() || e.getWorld() != p.getWorld()) {
                it.remove();
                finish(p, e);
                continue;
            }
            int form = e.getForm();
            if (!p.isCreative()) {
                p.addExhaustion(0.003f * form);
                if (p.getHungerManager().getFoodLevel() <= 0) {
                    it.remove();
                    p.sendMessage(Text.translatable("message.susanoo.exhausted"), true);
                    finish(p, e);
                    continue;
                }
            }
            long now = p.getWorld().getTime();
            float max = e.getMaxShield();
            if (now - e.lastHitTime > 100 && e.getShield() < max) {
                e.setShield(Math.min(max, e.getShield() + 0.05f * form));
            }
            int t = e.getAttackTicks();
            if (t > 0) {
                t--;
                e.setAttackTicks(t);
                int kind = e.getAttackKind();
                int len = SusanooEntity.ANIM_LEN[form][kind];
                int impact = kind == 0 ? len / 2 : len - 4;
                if (len - t == impact) {
                    if (kind == 0) meleeImpact(p, e); else beamImpact(p, e);
                }
            }
        }
    }

    // ------------------------------------------------------------ attaques (touches R et V)

    public static void attack(ServerPlayerEntity p) {
        SusanooEntity e = get(p);
        if (e == null || e.getAttackTicks() > 0) return;
        long now = p.getWorld().getTime();
        if (now < e.cooldownUntil) return;
        int f = e.getForm();
        int len = SusanooEntity.ANIM_LEN[f][0];
        e.setAttackKind(0);
        e.setAttackTicks(len);
        e.cooldownUntil = now + len + MELEE_CD[f];
        playAt(p, f >= 3 ? SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP : SoundEvents.ENTITY_PLAYER_ATTACK_STRONG, 1.2f, 1.2f - f * 0.15f);
    }

    public static void special(ServerPlayerEntity p) {
        SusanooEntity e = get(p);
        if (e == null || e.getAttackTicks() > 0) return;
        long now = p.getWorld().getTime();
        if (now < e.cooldownUntil) return;
        int f = e.getForm();
        int len = SusanooEntity.ANIM_LEN[f][1];
        e.setAttackKind(1);
        e.setAttackTicks(len);
        e.cooldownUntil = now + len + SPECIAL_CD[f];
        playAt(p, SoundEvents.BLOCK_BEACON_POWER_SELECT, 1.5f, 0.6f);
    }

    private static void meleeImpact(ServerPlayerEntity p, SusanooEntity e) {
        ServerWorld w = p.getServerWorld();
        int f = e.getForm();
        double reach = REACH[f];
        Vec3d look = Vec3d.fromPolar(0f, p.getYaw());
        Vec3d origin = p.getPos().add(0, 1.0, 0);
        Box box = new Box(origin, origin).expand(reach, reach * 0.7, reach);
        for (LivingEntity t : w.getEntitiesByClass(LivingEntity.class, box, x -> canTarget(p, x))) {
            Vec3d to = t.getPos().add(0, t.getHeight() / 2.0, 0).subtract(origin);
            Vec3d flat = new Vec3d(to.x, 0, to.z);
            double fl = flat.length();
            if (fl - t.getWidth() / 2.0 > reach) continue;
            if (fl > 0.8 && flat.normalize().dotProduct(look) < ARC[f]) continue;
            strike(w, p, t, DMG[f], KB[f]);
        }
        breakSector(w, p, look, reach, ARC[f], MELEE_BLOCK_HARDNESS[f]);
        Vec3d fx = p.getPos().add(look.multiply(Math.min(reach, 6) * 0.6)).add(0, 1.0 + f * 0.3, 0);
        w.spawnParticles(ParticleTypes.SWEEP_ATTACK, fx.x, fx.y, fx.z, 3 + f * 2, reach * 0.3, 0.4, reach * 0.3, 0);
        dust(w, SusanooColor.byId(e.getColorId()), fx, 12 + f * 10, reach * 0.35);
        playAt(p, SoundEvents.ENTITY_GENERIC_EXPLODE, 0.6f + f * 0.2f, 1.4f - f * 0.2f);
    }

    private static void beamImpact(ServerPlayerEntity p, SusanooEntity e) {
        ServerWorld w = p.getServerWorld();
        int f = e.getForm();
        SusanooColor col = SusanooColor.byId(e.getColorId());
        Vec3d start = p.getEyePos();
        Vec3d dir = p.getRotationVec(1f);
        Vec3d end = start.add(dir.multiply(BEAM_RANGE[f]));
        BlockHitResult hit = w.raycast(new RaycastContext(start, end,
                RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, p));
        boolean hitBlock = hit.getType() == HitResult.Type.BLOCK;
        Vec3d stop = hitBlock ? hit.getPos() : end;
        double len = start.distanceTo(stop);
        double rad = BEAM_RADIUS[f];
        Set<LivingEntity> done = new HashSet<>();
        for (double d = 1.5; d <= len; d += 0.8) {
            Vec3d c = start.add(dir.multiply(d));
            dust(w, col, c, 2 + f * 2, rad * 0.3);
            Box b = new Box(c, c).expand(rad);
            for (LivingEntity t : w.getEntitiesByClass(LivingEntity.class, b, x -> canTarget(p, x))) {
                if (done.add(t)) {
                    strike(w, p, t, BEAM_DMG[f], 1.2f);
                }
            }
        }
        if (hitBlock || f >= 3) {
            blast(w, p, stop, BLAST_RADIUS[f], BEAM_DMG[f] * 0.6f, BLAST_HARDNESS[f], col);
        }
        playAt(p, SoundEvents.ENTITY_WARDEN_SONIC_BOOM, 1.5f, 0.8f + 0.1f * (4 - f));
    }

    private static boolean canTarget(ServerPlayerEntity owner, LivingEntity x) {
        if (x == owner || !x.isAlive()) return false;
        return !(x instanceof TameableEntity te && te.isOwner(owner));
    }

    private static void strike(ServerWorld w, ServerPlayerEntity p, LivingEntity t, float dmg, float kb) {
        t.damage(w.getDamageSources().playerAttack(p), dmg);
        t.takeKnockback(kb, p.getX() - t.getX(), p.getZ() - t.getZ());
        t.addVelocity(0, 0.25 + 0.05 * kb, 0);
        t.velocityModified = true;
    }

    private static void blast(ServerWorld w, ServerPlayerEntity p, Vec3d c, double radius, float dmg, float hardness, SusanooColor col) {
        Box b = new Box(c, c).expand(radius);
        for (LivingEntity t : w.getEntitiesByClass(LivingEntity.class, b, x -> canTarget(p, x))) {
            double d = t.getPos().distanceTo(c);
            if (d <= radius + t.getWidth()) {
                float k = (float) (1.0 - Math.min(1.0, d / (radius + 0.01)) * 0.6);
                t.damage(w.getDamageSources().playerAttack(p), dmg * k);
                Vec3d push = t.getPos().subtract(c).normalize().multiply(0.9 + radius * 0.15);
                t.addVelocity(push.x, 0.5, push.z);
                t.velocityModified = true;
            }
        }
        w.spawnParticles(ParticleTypes.EXPLOSION_EMITTER, c.x, c.y, c.z, radius > 4 ? 3 : 1, radius * 0.3, radius * 0.3, radius * 0.3, 0);
        dust(w, col, c, 40 + (int) (radius * 12), radius * 0.6);
        w.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_GENERIC_EXPLODE, SoundCategory.PLAYERS, 3f, 0.7f);
        if (!w.getGameRules().getBoolean(GameRules.DO_MOB_GRIEFING)) return;
        BlockPos center = BlockPos.ofFloored(c);
        int r = (int) Math.ceil(radius);
        int count = 0;
        for (int x = -r; x <= r; x++) {
            for (int y = -r; y <= r; y++) {
                for (int z = -r; z <= r; z++) {
                    if (x * x + y * y + z * z > radius * radius) continue;
                    if (destroy(w, p, center.add(x, y, z), hardness) && ++count > 2500) return;
                }
            }
        }
    }

    private static void breakSector(ServerWorld w, ServerPlayerEntity p, Vec3d look, double reach, double arc, float hardness) {
        if (hardness <= 0f || !w.getGameRules().getBoolean(GameRules.DO_MOB_GRIEFING)) return;
        BlockPos base = p.getBlockPos();
        int r = (int) Math.ceil(reach);
        int up = (int) (reach * 0.7);
        int count = 0;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d > reach) continue;
                if (d > 1.0 && (dx * look.x + dz * look.z) / d < arc) continue;
                for (int dy = 0; dy <= up; dy++) {
                    if (destroy(w, p, base.add(dx, dy, dz), hardness) && ++count > 1500) return;
                }
            }
        }
    }

    private static boolean destroy(ServerWorld w, ServerPlayerEntity p, BlockPos pos, float hardnessLimit) {
        BlockState s = w.getBlockState(pos);
        if (s.isAir()) return false;
        float h = s.getHardness(w, pos);
        if (h < 0f || h > hardnessLimit) return false;
        if (w.getBlockEntity(pos) != null) return false;
        if (!w.canPlayerModifyAt(p, pos)) return false;
        return w.breakBlock(pos, false, p);
    }

    // ------------------------------------------------------------ utilitaires

    private static void dust(ServerWorld w, SusanooColor c, Vec3d at, int count, double spread) {
        DustParticleEffect fx = new DustParticleEffect(new Vector3f(c.glow[0] / 255f, c.glow[1] / 255f, c.glow[2] / 255f), 2.0f);
        w.spawnParticles(fx, at.x, at.y, at.z, count, spread, spread, spread, 0.02);
    }

    private static void playAt(ServerPlayerEntity p, SoundEvent s, float vol, float pitch) {
        p.getWorld().playSound(null, p.getX(), p.getY(), p.getZ(), s, SoundCategory.PLAYERS, vol, pitch);
    }
}

package com.susano;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.world.World;
import org.joml.Vector3f;

import java.util.Optional;
import java.util.UUID;

/**
 * Le Susanoo : entite purement "visuelle + etat", collee au joueur proprietaire.
 * Toute la logique de jeu est dans {@link SusanooManager}.
 */
public class SusanooEntity extends Entity {
    public static final int MAX_FORM = 4;
    /** Echelle du modele par forme (index = forme). */
    public static final float[] SCALE = {1f, 1.25f, 1.9f, 2.8f, 8.0f};
    public static final float[] MAX_SHIELD = {0f, 20f, 40f, 80f, 160f};
    /** Duree des animations (ticks) : [forme][0 = melee, 1 = speciale]. */
    public static final int[][] ANIM_LEN = {{0, 0}, {10, 16}, {12, 18}, {14, 22}, {20, 40}};

    private static final TrackedData<Optional<UUID>> OWNER =
            DataTracker.registerData(SusanooEntity.class, TrackedDataHandlerRegistry.OPTIONAL_UUID);
    private static final TrackedData<Integer> FORM =
            DataTracker.registerData(SusanooEntity.class, TrackedDataHandlerRegistry.INTEGER);
    private static final TrackedData<Integer> COLOR =
            DataTracker.registerData(SusanooEntity.class, TrackedDataHandlerRegistry.INTEGER);
    private static final TrackedData<Integer> ATTACK_TICKS =
            DataTracker.registerData(SusanooEntity.class, TrackedDataHandlerRegistry.INTEGER);
    private static final TrackedData<Integer> ATTACK_KIND =
            DataTracker.registerData(SusanooEntity.class, TrackedDataHandlerRegistry.INTEGER);
    private static final TrackedData<Float> SHIELD =
            DataTracker.registerData(SusanooEntity.class, TrackedDataHandlerRegistry.FLOAT);

    // cote client : animation de croissance
    public float scaleAnim = -1f;
    public float prevScaleAnim = -1f;

    // cote serveur
    public long cooldownUntil;
    public long lastHitTime;

    public SusanooEntity(EntityType<? extends SusanooEntity> type, World world) {
        super(type, world);
        this.noClip = true;
        this.setNoGravity(true);
    }

    @Override
    protected void initDataTracker() {
        this.dataTracker.startTracking(OWNER, Optional.empty());
        this.dataTracker.startTracking(FORM, 1);
        this.dataTracker.startTracking(COLOR, 0);
        this.dataTracker.startTracking(ATTACK_TICKS, 0);
        this.dataTracker.startTracking(ATTACK_KIND, 0);
        this.dataTracker.startTracking(SHIELD, 20f);
    }

    // ---- acces aux donnees synchronisees
    public UUID getOwnerUuid() { return this.dataTracker.get(OWNER).orElse(null); }
    public void setOwnerUuid(UUID id) { this.dataTracker.set(OWNER, Optional.ofNullable(id)); }
    public int getForm() { return this.dataTracker.get(FORM); }
    public void setForm(int f) { this.dataTracker.set(FORM, Math.max(1, Math.min(MAX_FORM, f))); }
    public int getColorId() { return this.dataTracker.get(COLOR); }
    public void setColorId(int c) { this.dataTracker.set(COLOR, c); }
    public int getAttackTicks() { return this.dataTracker.get(ATTACK_TICKS); }
    public void setAttackTicks(int t) { this.dataTracker.set(ATTACK_TICKS, t); }
    public int getAttackKind() { return this.dataTracker.get(ATTACK_KIND); }
    public void setAttackKind(int k) { this.dataTracker.set(ATTACK_KIND, k); }
    public float getShield() { return this.dataTracker.get(SHIELD); }
    public void setShield(float s) { this.dataTracker.set(SHIELD, s); }
    public float getMaxShield() { return MAX_SHIELD[getForm()]; }

    public PlayerEntity getOwner() {
        UUID id = getOwnerUuid();
        return id == null ? null : this.getWorld().getPlayerByUuid(id);
    }

    @Override
    public void tick() {
        super.tick();
        PlayerEntity owner = getOwner();
        if (owner != null) {
            this.setPosition(owner.getX(), owner.getY(), owner.getZ());
        }
        if (this.getWorld().isClient) {
            clientTick(owner);
        }
    }

    private void clientTick(PlayerEntity owner) {
        float target = SCALE[getForm()];
        prevScaleAnim = scaleAnim < 0 ? target * 0.3f : scaleAnim;
        if (scaleAnim < 0) {
            scaleAnim = target * 0.3f;
            prevScaleAnim = scaleAnim;
        }
        scaleAnim += (target - scaleAnim) * 0.14f;
        if (owner == null) {
            return;
        }
        SusanooColor c = SusanooColor.byId(getColorId());
        DustParticleEffect dust = new DustParticleEffect(
                new Vector3f(c.glow[0] / 255f, c.glow[1] / 255f, c.glow[2] / 255f), 1.4f);
        float s = scaleAnim;
        int count = 1 + getForm();
        for (int i = 0; i < count; i++) {
            double a = random.nextDouble() * Math.PI * 2;
            double r = (0.35 + random.nextDouble() * 0.5) * s;
            this.getWorld().addParticle(dust,
                    owner.getX() + Math.cos(a) * r,
                    owner.getY() + 1.15 + (random.nextDouble() - 0.35) * s * 1.2,
                    owner.getZ() + Math.sin(a) * r,
                    0, 0.04 + random.nextDouble() * 0.04, 0);
        }
    }

    // ---- une entite "fantome" : jamais ciblee, jamais sauvegardee
    @Override public boolean canHit() { return false; }
    @Override public boolean isPushable() { return false; }
    @Override public boolean shouldSave() { return false; }
    @Override public boolean isInvulnerableTo(DamageSource source) { return true; }
    @Override public boolean damage(DamageSource source, float amount) { return false; }
    @Override public boolean isAttackable() { return false; }

    @Override
    protected void readCustomDataFromNbt(NbtCompound nbt) { }

    @Override
    protected void writeCustomDataToNbt(NbtCompound nbt) { }

    @Override
    public Packet<ClientPlayPacketListener> createSpawnPacket() {
        return new EntitySpawnS2CPacket(this);
    }
}

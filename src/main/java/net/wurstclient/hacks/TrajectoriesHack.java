/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Predicate;

import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.projectile.ProjectileUtil;
import net.minecraft.item.*;
import net.minecraft.util.Arm;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext.FluidHandling;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.RenderListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.ColorSetting;
import net.wurstclient.util.BlockUtils;
import net.wurstclient.util.EntityUtils;
import net.wurstclient.util.RenderUtils;
import net.wurstclient.util.RotationUtils;

@SearchTags({"ArrowTrajectories", "ArrowPrediction", "aim assist",
	"arrow trajectories", "bow trajectories"})
public final class TrajectoriesHack extends Hack implements RenderListener
{
	/*
	 * TrajectoriesHack Design Notes
	 *
	 * 1) Core rendering output
	 * - The hack renders one physically simulated projectile path (the "main"
	 *   path), plus one visual companion path offset by -1.5 blocks on Y.
	 * - The companion path is not independently simulated with its own velocity;
	 *   instead it is derived from the main path points and then raycasted segment
	 *   by segment for block collisions.
	 *
	 * 2) Main projectile simulation model
	 * - The simulation advances in fixed steps:
	 *   - Position update uses arrowMotion * 0.1 (distance per step).
	 *   - Drag multiplies velocity by 0.999 each step.
	 *   - Gravity subtracts gravity * 0.1 from Y velocity each step.
	 * - Collision checks are done in this order for each segment:
	 *   - Block collision via BlockUtils.raycast(...).
	 *   - Entity collision via ProjectileUtil.raycast(...).
	 * - On first collision:
	 *   - The current path point is replaced with the exact impact coordinate.
	 *   - The loop stops immediately.
	 * - impactTimeSeconds stores the time of first collision (if any), using
	 *   SIMULATION_STEP_SECONDS per simulation step.
	 *
	 * 3) Lowered companion path behavior
	 * - The lowered path is generated from the main path points with yOffset=-1.5.
	 * - Each lowered segment is raycasted against blocks using FluidHandling.NONE.
	 * - If a lowered segment hits a block, the hit coordinate is appended and the
	 *   lowered path is truncated there.
	 * - Lowered-path color is independent from main-path collision type:
	 *   - If lowered path hits a block: use entityHitColor (red).
	 *   - If lowered path does not hit: use blockHitColor (green).
	 *   This ensures the lowered path only changes color from its own collision
	 *   state, not from the main path's hit result.
	 *
	 * 4) Movement prediction scope and filtering
	 * - Movement prediction boxes are generated when holding a bow item.
	 * - Candidate entities are filtered by:
	 *   - canHit() and !isSpectator().
	 *   - Not the local player.
	 *   - Range cap: MAX_PREDICTION_RANGE_SQUARED.
	 *   - View-cone test: inside a 120-degree total cone centered on camera view
	 *     direction (implemented as +/-60 degrees via XZ-plane dot product).
	 *
	 * 5) Future box model per entity
	 * - Base future-box extrapolation uses the entity's current velocity and an
	 *   estimated time horizon.
	 * - The resulting box preserves the entity's current Y extents (minY/maxY), so
	 *   prediction is horizontal-only in practice even if velocity includes Y.
	 *
	 * 6) Iterative time-consistency solver
	 * - The prediction system uses a fixed-point style iteration so predicted box
	 *   position and projectile flight time agree with each other.
	 * - For each entity:
	 *   a) Start with an initial time guess (current trajectory flight estimate).
	 *   b) Build a hypothetical future box at that time.
	 *   c) Compute projectile intercept time against that box by simulating the
	 *      projectile and raycasting segment-vs-box along the path.
	 *   d) Rebuild the future box with the new intercept time.
	 *   e) Repeat until |newTime - oldTime| < INTERCEPT_TIME_EPSILON or until
	 *      MAX_INTERCEPT_ITERATIONS is reached.
	 * - If a blocking block collision occurs before intercepting the target box,
	 *   the solver rejects that prediction for the entity.
	 *
	 * 7) Why this structure exists
	 * - Earlier versions could produce different-looking hit vs miss prediction
	 *   boxes because they relied on different timing paths.
	 * - The iterative solver unifies timing behavior by enforcing consistency
	 *   between flight time and the target's predicted location.
	 *
	 * 8) Practical limitations (expected)
	 * - Entity motion is still predicted from current velocity only; sudden
	 *   acceleration, jumps, knockback, AI turns, and server corrections can cause
	 *   visual deviation.
	 * - The projectile model is step-based, so results are approximation quality,
	 *   not an analytical closed-form ballistic intercept.
	 */
	private static final double SIMULATION_STEP_TICKS = 0.1;
	private static final double SIMULATION_STEP_SECONDS = 0.005;
	private static final double MAX_PREDICTION_RANGE_SQUARED = 64 * 64;
	private static final int MAX_INTERCEPT_ITERATIONS = 6;
	private static final double INTERCEPT_TIME_EPSILON = 0.002;
	private static final double VIEW_CONE_HALF_ANGLE_DEGREES = 60;
	private static final double VIEW_CONE_COSINE = Math.cos(Math.toRadians(
		VIEW_CONE_HALF_ANGLE_DEGREES));
	
	private final ColorSetting missColor = new ColorSetting("Miss Color",
		"Color of the trajectory when it doesn't hit anything.", Color.GRAY);
	
	private final ColorSetting entityHitColor =
		new ColorSetting("Entity Hit Color",
			"Color of the trajectory when it hits an entity.", Color.RED);
	
	private final ColorSetting blockHitColor =
		new ColorSetting("Block Hit Color",
			"Color of the trajectory when it hits a block.", Color.GREEN);
	
	private String defaultImpactTime = "--";
	
	public TrajectoriesHack()
	{
		super("Trajectories");
		setCategory(Category.RENDER);
		addSetting(missColor);
		addSetting(entityHitColor);
		addSetting(blockHitColor);
	}
	
	@Override
	protected void onEnable()
	{
		EVENTS.add(RenderListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(RenderListener.class, this);
	}
	
	@Override
	public void onRender(MatrixStack matrixStack, float partialTicks)
	{
		Trajectory defaultTrajectory = getTrajectory(partialTicks, 0);
		updateImpactEstimate(defaultTrajectory);
		
		drawTrajectory(matrixStack, defaultTrajectory);
	}
	
	@Override
	public String getRenderName()
	{
		if(defaultImpactTime.equals("--"))
			return "Trajectories";
		
		return "Trajectories [" + defaultImpactTime + "]";
	}
	
	private void updateImpactEstimate(Trajectory defaultTrajectory)
	{
		defaultImpactTime = formatImpactTime(defaultTrajectory);
	}
	
	private String formatImpactTime(Trajectory trajectory)
	{
		if(!trajectory.hasImpact())
			return "--";
		
		return String.format(Locale.ROOT, "%.2fs",
			trajectory.impactTimeSeconds());
	}
	
	private void drawTrajectory(MatrixStack matrixStack, Trajectory trajectory)
	{
		if(trajectory.isEmpty())
			return;
		
		ColorSetting color = getColor(trajectory);
		int lineColor = color.getColorI(0xC0);
		int quadColor = color.getColorI(0x40);
		
		Box endBox = trajectory.getEndBox();
		ArrayList<Vec3d> path = trajectory.path();
		LoweredPath loweredPath = getLoweredPath(path, -1.5);
		
		RenderUtils.drawSolidBox(matrixStack, endBox, quadColor, false);
		RenderUtils.drawOutlinedBox(matrixStack, endBox, lineColor, false);
		RenderUtils.drawCurvedLine(matrixStack, path, lineColor, false);
		
		ColorSetting loweredColor = loweredPath.blocked() ? entityHitColor
			: blockHitColor;
		int loweredLineColor = loweredColor.getColorI(0xC0);
		RenderUtils.drawCurvedLine(matrixStack, loweredPath.path(),
			loweredLineColor, false);
		
		for(Box predictionBox : trajectory.predictedTargetBoxes())
		{
			RenderUtils.drawSolidBox(matrixStack, predictionBox, quadColor, false);
			RenderUtils.drawOutlinedBox(matrixStack, predictionBox, lineColor,
				false);
		}
	}
	
	private LoweredPath getLoweredPath(ArrayList<Vec3d> path,
		double yOffset)
	{
		ArrayList<Vec3d> loweredPath = new ArrayList<>(path.size());
		if(path.isEmpty())
			return new LoweredPath(loweredPath, false);
		
		boolean blocked = false;
		Vec3d firstPoint = path.get(0).add(0, yOffset, 0);
		loweredPath.add(firstPoint);
		Vec3d previousPoint = null;
		previousPoint = firstPoint;
		for(int i = 1; i < path.size(); i++)
		{
			Vec3d loweredPoint = path.get(i).add(0, yOffset, 0);
			BlockHitResult blockResult = BlockUtils.raycast(previousPoint,
				loweredPoint, FluidHandling.NONE);
			if(blockResult.getType() != HitResult.Type.MISS)
			{
				loweredPath.add(blockResult.getPos());
				blocked = true;
				break;
			}
			
			loweredPath.add(loweredPoint);
			previousPoint = loweredPoint;
		}
		
		return new LoweredPath(loweredPath, blocked);
	}
	
	private Trajectory getTrajectory(float partialTicks, double yOffset)
	{
		ClientPlayerEntity player = MC.player;
		ArrayList<Vec3d> path = new ArrayList<>();
		HitResult.Type type = HitResult.Type.MISS;
		double impactTimeSeconds = -1;
		ArrayList<Box> predictedTargetBoxes = new ArrayList<>();
		
		// Find the hand with a throwable item
		Hand hand = Hand.MAIN_HAND;
		ItemStack stack = player.getMainHandStack();
		if(!isThrowable(stack))
		{
			hand = Hand.OFF_HAND;
			stack = player.getOffHandStack();
			
			// If neither hand has a throwable item, return empty path
			if(!isThrowable(stack))
				return new Trajectory(path, type, impactTimeSeconds,
					predictedTargetBoxes);
		}
		
		// Calculate item-specific values
		Item item = stack.getItem();
		double throwPower = getThrowPower(item);
		double gravity = getProjectileGravity(item);
		FluidHandling fluidHandling = getFluidHandling(item);
		boolean drawMovementPrediction = item instanceof BowItem;
		double predictionTimeSeconds = 0;
		
		// Prepare yaw and pitch
		double yaw = Math.toRadians(player.getYaw());
		double pitch = Math.toRadians(player.getPitch());
		
		// Calculate starting position
		Vec3d arrowPos = EntityUtils.getLerpedPos(player, partialTicks)
			.add(getHandOffset(hand, yaw)).add(0, yOffset, 0);
		
		// Calculate starting motion
		Vec3d arrowMotion = getStartingMotion(yaw, pitch, throwPower);
		Vec3d initialArrowPos = arrowPos;
		Vec3d predictionArrowMotion = arrowMotion;
		if(item instanceof BowItem)
			predictionArrowMotion = getStartingMotion(yaw, pitch, 3.0);
		
		// Build the path
		for(int i = 0; i < 1000; i++)
		{
			// Add to path
			path.add(arrowPos);
			predictionTimeSeconds = (i + 1) * SIMULATION_STEP_SECONDS;
			
			// Apply motion
			arrowPos = arrowPos.add(arrowMotion.multiply(0.1));
			
			// Apply air friction
			arrowMotion = arrowMotion.multiply(0.999);
			
			// Apply gravity
			arrowMotion = arrowMotion.add(0, -gravity * 0.1, 0);
			
			Vec3d lastPos = path.size() > 1 ? path.get(path.size() - 2)
				: RotationUtils.getEyesPos().add(0, yOffset, 0);
			
			// Check for block collision
			BlockHitResult bResult =
				BlockUtils.raycast(lastPos, arrowPos, fluidHandling);
			if(bResult.getType() != HitResult.Type.MISS)
			{
				// Replace last pos with the collision point
				type = HitResult.Type.BLOCK;
				impactTimeSeconds = (i + 1) * SIMULATION_STEP_SECONDS;
				path.set(path.size() - 1, bResult.getPos());
				break;
			}
			
			// Check for entity collision
			Box box = new Box(lastPos, arrowPos);
			Predicate<Entity> predicate = e -> !e.isSpectator() && e.canHit();
			double maxDistSq = 64 * 64;
			EntityHitResult eResult = ProjectileUtil.raycast(player, lastPos,
				arrowPos, box, predicate, maxDistSq);
			if(eResult != null && eResult.getType() != HitResult.Type.MISS)
			{
				// Replace last pos with the collision point
				type = HitResult.Type.ENTITY;
				impactTimeSeconds = (i + 1) * SIMULATION_STEP_SECONDS;
				path.set(path.size() - 1, eResult.getPos());
				break;
			}
		}
		
		if(drawMovementPrediction)
			predictedTargetBoxes = getProjectedTargetBoxes(player, partialTicks,
				initialArrowPos, predictionArrowMotion, gravity, fluidHandling,
				predictionTimeSeconds);
		
		return new Trajectory(path, type, impactTimeSeconds,
			predictedTargetBoxes);
	}
	
	private Box getProjectedTargetBox(Entity entity, double impactTimeSeconds)
	{
		if(entity == null || impactTimeSeconds < 0)
			return null;
		
		Box currentBox = entity.getBoundingBox();
		double impactTimeTicks = impactTimeSeconds / SIMULATION_STEP_SECONDS
			* SIMULATION_STEP_TICKS;
		Vec3d velocity = entity.getVelocity();
		Vec3d predictedOffset = velocity.multiply(impactTimeTicks);
		Box predictedBox = currentBox.offset(predictedOffset);
		return new Box(predictedBox.minX, currentBox.minY, predictedBox.minZ,
			predictedBox.maxX, currentBox.maxY, predictedBox.maxZ);
	}
	
	private ArrayList<Box> getProjectedTargetBoxes(ClientPlayerEntity player,
		float partialTicks, Vec3d startPos, Vec3d startMotion, double gravity,
		FluidHandling fluidHandling, double impactTimeSeconds)
	{
		ArrayList<Box> boxes = new ArrayList<>();
		if(MC.world == null || impactTimeSeconds < 0)
			return boxes;
		
		Vec3d cameraPos = EntityUtils.getLerpedPos(player, partialTicks)
			.add(0, player.getStandingEyeHeight(), 0);
		Vec3d viewVec = player.getRotationVec(partialTicks).normalize();
		for(Entity entity : MC.world.getEntities())
		{
			if(entity == null || entity == player || !entity.canHit()
				|| entity.isSpectator())
				continue;
			
			if(entity.squaredDistanceTo(cameraPos) > MAX_PREDICTION_RANGE_SQUARED)
				continue;
			
			if(!isWithinViewCone(cameraPos, viewVec, entity))
				continue;
			
			Box predictedBox =
				getConsistentPredictedTargetBox(entity, startPos, startMotion,
					gravity, fluidHandling, impactTimeSeconds);
			if(predictedBox != null)
				boxes.add(predictedBox);
		}
		
		return boxes;
	}
	
	private Box getConsistentPredictedTargetBox(Entity entity, Vec3d startPos,
		Vec3d startMotion, double gravity, FluidHandling fluidHandling,
		double initialTimeGuess)
	{
		double timeSeconds = initialTimeGuess;
		Box targetBox = getProjectedTargetBox(entity, timeSeconds);
		if(targetBox == null)
			return null;
		double launchSpeed = startMotion.length();
		if(launchSpeed <= 0)
			return null;
		
		for(int i = 0; i < MAX_INTERCEPT_ITERATIONS; i++)
		{
			Vec3d aimedMotion = getAimedMotionToBox(startPos, targetBox,
				launchSpeed);
			double interceptTime = getProjectileInterceptTime(startPos,
				aimedMotion, gravity, fluidHandling, targetBox);
			if(interceptTime < 0)
				return null;
			
			if(Math.abs(interceptTime - timeSeconds) < INTERCEPT_TIME_EPSILON)
				return targetBox;
			
			timeSeconds = interceptTime;
			targetBox = getProjectedTargetBox(entity, timeSeconds);
			if(targetBox == null)
				return null;
		}
		
		return targetBox;
	}
	
	private Vec3d getAimedMotionToBox(Vec3d startPos, Box targetBox,
		double launchSpeed)
	{
		Vec3d toTarget = targetBox.getCenter().subtract(startPos);
		if(toTarget.lengthSquared() == 0)
			return new Vec3d(0, 0, launchSpeed);
		
		return toTarget.normalize().multiply(launchSpeed);
	}
	
	private double getProjectileInterceptTime(Vec3d startPos, Vec3d startMotion,
		double gravity, FluidHandling fluidHandling, Box targetBox)
	{
		Vec3d arrowPos = startPos;
		Vec3d arrowMotion = startMotion;
		for(int i = 0; i < 1000; i++)
		{
			Vec3d nextPos = arrowPos.add(arrowMotion.multiply(0.1));
			
			BlockHitResult blockResult =
				BlockUtils.raycast(arrowPos, nextPos, fluidHandling);
			if(blockResult.getType() != HitResult.Type.MISS)
				return -1;
			
			Optional<Vec3d> entityHit = targetBox.raycast(arrowPos, nextPos);
			if(entityHit.isPresent())
				return (i + 1) * SIMULATION_STEP_SECONDS;
			
			arrowPos = nextPos;
			arrowMotion = arrowMotion.multiply(0.999);
			arrowMotion = arrowMotion.add(0, -gravity * 0.1, 0);
		}
		
		return -1;
	}
	
	private boolean isWithinViewCone(Vec3d cameraPos, Vec3d viewVec, Entity entity)
	{
		Vec3d toEntity = entity.getBoundingBox().getCenter().subtract(cameraPos);
		Vec3d toEntityHorizontal = new Vec3d(toEntity.x, 0, toEntity.z);
		if(toEntityHorizontal.lengthSquared() == 0)
			return true;
		
		Vec3d viewHorizontal = new Vec3d(viewVec.x, 0, viewVec.z);
		if(viewHorizontal.lengthSquared() == 0)
			return true;
		
		return viewHorizontal.normalize().dotProduct(toEntityHorizontal.normalize())
			>= VIEW_CONE_COSINE;
	}
	
	private boolean shouldDrawMovementPrediction(Item item)
	{
		return item instanceof BowItem;
	}
	
	private boolean isThrowable(ItemStack stack)
	{
		if(stack.isEmpty())
			return false;
		
		Item item = stack.getItem();
		return item instanceof RangedWeaponItem || item instanceof SnowballItem
			|| item instanceof EggItem || item instanceof EnderPearlItem
			|| item instanceof ThrowablePotionItem
			|| item instanceof FishingRodItem || item instanceof TridentItem;
	}
	
	private double getThrowPower(Item item)
	{
		// Use a static 1.5x for snowballs and such
		if(!(item instanceof RangedWeaponItem))
			return 1.5;
		
		// Calculate bow power
		float bowPower = (72000 - MC.player.getItemUseTimeLeft()) / 20F;
		bowPower = bowPower * bowPower + bowPower * 2F;
		
		// Clamp value if fully charged or not charged at all
		if(bowPower > 3 || bowPower <= 0.3F)
			bowPower = 3;
		
		return bowPower;
	}
	
	private double getProjectileGravity(Item item)
	{
		if(item instanceof RangedWeaponItem)
			return 0.05;
		
		if(item instanceof ThrowablePotionItem)
			return 0.4;
		
		if(item instanceof FishingRodItem)
			return 0.15;
		
		if(item instanceof TridentItem)
			return 0.015;
		
		return 0.03;
	}
	
	private FluidHandling getFluidHandling(Item item)
	{
		if(item instanceof FishingRodItem)
			return FluidHandling.ANY;
		
		return FluidHandling.NONE;
	}
	
	private Vec3d getHandOffset(Hand hand, double yaw)
	{
		Arm mainArm = MC.options.getMainArm().getValue();
		
		boolean rightSide = mainArm == Arm.RIGHT && hand == Hand.MAIN_HAND
			|| mainArm == Arm.LEFT && hand == Hand.OFF_HAND;
		
		double sideMultiplier = rightSide ? -1 : 1;
		double handOffsetX = Math.cos(yaw) * 0.16 * sideMultiplier;
		double handOffsetY = MC.player.getStandingEyeHeight() - 0.1;
		double handOffsetZ = Math.sin(yaw) * 0.16 * sideMultiplier;
		
		return new Vec3d(handOffsetX, handOffsetY, handOffsetZ);
	}
	
	private Vec3d getStartingMotion(double yaw, double pitch, double throwPower)
	{
		double cosOfPitch = Math.cos(pitch);
		
		double arrowMotionX = -Math.sin(yaw) * cosOfPitch;
		double arrowMotionY = -Math.sin(pitch);
		double arrowMotionZ = Math.cos(yaw) * cosOfPitch;
		
		return new Vec3d(arrowMotionX, arrowMotionY, arrowMotionZ).normalize()
			.multiply(throwPower);
	}
	
	private ColorSetting getColor(Trajectory trajectory)
	{
		return switch(trajectory.type())
		{
			case MISS -> missColor;
			case ENTITY -> entityHitColor;
			case BLOCK -> blockHitColor;
		};
	}
	
	private record Trajectory(ArrayList<Vec3d> path, HitResult.Type type,
		double impactTimeSeconds, ArrayList<Box> predictedTargetBoxes)
	{
		public boolean isEmpty()
		{
			return path.isEmpty();
		}
		
		public boolean hasImpact()
		{
			return type != HitResult.Type.MISS;
		}
		
		public Box getEndBox()
		{
			Vec3d end = path.get(path.size() - 1);
			return new Box(end.subtract(0.5), end.add(0.5));
		}
	}
	
	private record LoweredPath(ArrayList<Vec3d> path, boolean blocked)
	{
	}
}

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
	private static final double SIMULATION_STEP_TICKS = 0.1;
	private static final double SIMULATION_STEP_SECONDS = 0.005;
	private static final double MAX_PREDICTION_RANGE_SQUARED = 64 * 64;
	
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
		ArrayList<Vec3d> loweredPath = getLoweredPath(path, -1.5);
		
		RenderUtils.drawSolidBox(matrixStack, endBox, quadColor, false);
		RenderUtils.drawOutlinedBox(matrixStack, endBox, lineColor, false);
		RenderUtils.drawCurvedLine(matrixStack, path, lineColor, false);
		RenderUtils.drawCurvedLine(matrixStack, loweredPath, lineColor, false);
		
		Box projectedTargetBox = trajectory.projectedTargetBox();
		if(projectedTargetBox != null)
		{
			RenderUtils.drawSolidBox(matrixStack, projectedTargetBox, quadColor,
				false);
			RenderUtils.drawOutlinedBox(matrixStack, projectedTargetBox,
				lineColor, false);
		}
		
		for(Box predictionBox : trajectory.predictedTargetBoxes())
		{
			RenderUtils.drawSolidBox(matrixStack, predictionBox, quadColor, false);
			RenderUtils.drawOutlinedBox(matrixStack, predictionBox, lineColor,
				false);
		}
	}
	
	private ArrayList<Vec3d> getLoweredPath(ArrayList<Vec3d> path,
		double yOffset)
	{
		ArrayList<Vec3d> loweredPath = new ArrayList<>(path.size());
		if(path.isEmpty())
			return loweredPath;
		
		Vec3d previousPoint = null;
		for(Vec3d point : path)
		{
			Vec3d loweredPoint = point.add(0, yOffset, 0);
			if(previousPoint != null)
			{
				BlockHitResult blockResult = BlockUtils.raycast(previousPoint,
					loweredPoint, FluidHandling.NONE);
				if(blockResult.getType() != HitResult.Type.MISS)
				{
					loweredPath.add(blockResult.getPos());
					break;
				}
			}
			
			loweredPath.add(loweredPoint);
			previousPoint = loweredPoint;
		}
		
		return loweredPath;
	}
	
	private Trajectory getTrajectory(float partialTicks, double yOffset)
	{
		ClientPlayerEntity player = MC.player;
		ArrayList<Vec3d> path = new ArrayList<>();
		HitResult.Type type = HitResult.Type.MISS;
		double impactTimeSeconds = -1;
		Box projectedTargetBox = null;
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
					projectedTargetBox, predictedTargetBoxes);
		}
		
		// Calculate item-specific values
		Item item = stack.getItem();
		double throwPower = getThrowPower(item);
		double gravity = getProjectileGravity(item);
		FluidHandling fluidHandling = getFluidHandling(item);
		boolean drawMovementPrediction = shouldDrawMovementPrediction(item);
		double predictionTimeSeconds = 0;
		
		// Prepare yaw and pitch
		double yaw = Math.toRadians(player.getYaw());
		double pitch = Math.toRadians(player.getPitch());
		
		// Calculate starting position
		Vec3d arrowPos = EntityUtils.getLerpedPos(player, partialTicks)
			.add(getHandOffset(hand, yaw)).add(0, yOffset, 0);
		
		// Calculate starting motion
		Vec3d arrowMotion = getStartingMotion(yaw, pitch, throwPower);
		
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
				if(drawMovementPrediction)
					projectedTargetBox =
						getProjectedTargetBox(eResult.getEntity(), impactTimeSeconds);
				break;
			}
		}
		
		if(drawMovementPrediction)
			predictedTargetBoxes = getProjectedTargetBoxes(player,
				predictionTimeSeconds);
		
		return new Trajectory(path, type, impactTimeSeconds,
			projectedTargetBox, predictedTargetBoxes);
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
		double impactTimeSeconds)
	{
		ArrayList<Box> boxes = new ArrayList<>();
		if(MC.world == null || impactTimeSeconds < 0)
			return boxes;
		
		Vec3d cameraPos = RotationUtils.getEyesPos();
		for(Entity entity : MC.world.getEntities())
		{
			if(entity == null || entity == player || !entity.canHit()
				|| entity.isSpectator())
				continue;
			
			if(entity.squaredDistanceTo(cameraPos) > MAX_PREDICTION_RANGE_SQUARED)
				continue;
			
			Box predictedBox = getProjectedTargetBox(entity, impactTimeSeconds);
			if(predictedBox != null)
				boxes.add(predictedBox);
		}
		
		return boxes;
	}
	
	private boolean shouldDrawMovementPrediction(Item item)
	{
		if(!(item instanceof BowItem))
			return false;
		
		float useProgress = (72000 - MC.player.getItemUseTimeLeft()) / 20F;
		float bowProgress = (useProgress * useProgress + useProgress * 2F) / 3F;
		return bowProgress >= 0.6F;
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
		double impactTimeSeconds, Box projectedTargetBox,
		ArrayList<Box> predictedTargetBoxes)
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
}

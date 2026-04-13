/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.text.Text;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.ai.PathFinder;
import net.wurstclient.ai.PathPos;
import net.wurstclient.ai.PathProcessor;
import net.wurstclient.commands.PathCmd;
import net.wurstclient.events.RenderListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.DontSaveState;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.AttackSpeedSliderSetting;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.PauseAttackOnContainersSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.settings.SwingHandSetting;
import net.wurstclient.settings.SwingHandSetting.SwingHand;
import net.wurstclient.settings.filterlists.EntityFilterList;
import net.wurstclient.util.EntityUtils;

@SearchTags({"fight bot"})
@DontSaveState
public final class FightBotHack extends Hack
	implements UpdateListener, RenderListener
{
	private final SliderSetting range = new SliderSetting("Range",
		"Attack range (like Killaura)", 4.25, 1, 6, 0.05, ValueDisplay.DECIMAL);
	
	private final AttackSpeedSliderSetting speed =
		new AttackSpeedSliderSetting();
	
	private final SwingHandSetting swingHand = new SwingHandSetting(
		SwingHandSetting.genericCombatDescription(this), SwingHand.CLIENT);
	
	private final SliderSetting distance = new SliderSetting("Distance",
		"How closely to follow the target.\n"
			+ "This should be set to a lower value than Range.",
		3, 1, 6, 0.05, ValueDisplay.DECIMAL);
	
	private final CheckboxSetting useAi =
		new CheckboxSetting("Use AI (experimental)", false);
	
	private final PauseAttackOnContainersSetting pauseOnContainers =
		new PauseAttackOnContainersSetting(true);
	
	private final EntityFilterList entityFilters =
		EntityFilterList.genericCombat();
	
	private final CheckboxSetting debugSetting =
		new CheckboxSetting("Debug", false);
	
	private EntityPathFinder pathFinder;
	private PathProcessor processor;
	private int ticksProcessing;
	private static final MinecraftClient client = MinecraftClient.getInstance();

	public FightBotHack()
	{
		super("FightBot");
		
		setCategory(Category.COMBAT);
		addSetting(range);
		addSetting(speed);
		addSetting(swingHand);
		addSetting(distance);
		addSetting(useAi);
		addSetting(pauseOnContainers);
		addSetting(debugSetting);
		
		entityFilters.forEach(this::addSetting);
	}
	
	private boolean hasDiamondGear(PlayerEntity player)
	{
		try
		{
			// check armor using equipment slots
			ItemStack head = player.getEquippedStack(EquipmentSlot.HEAD);
			ItemStack chest = player.getEquippedStack(EquipmentSlot.CHEST);
			ItemStack legs = player.getEquippedStack(EquipmentSlot.LEGS);
			ItemStack feet = player.getEquippedStack(EquipmentSlot.FEET);
			
			if((head != null && !head.isEmpty())
				&& (head.getItem() == Items.DIAMOND_HELMET))
				return true;
			if((chest != null && !chest.isEmpty())
				&& (chest.getItem() == Items.DIAMOND_CHESTPLATE))
				return true;
			if((legs != null && !legs.isEmpty())
				&& (legs.getItem() == Items.DIAMOND_LEGGINGS))
				return true;
			if((feet != null && !feet.isEmpty())
				&& (feet.getItem() == Items.DIAMOND_BOOTS))
				return true;
			
			// check hands for diamond tools/weapons
			ItemStack main = player.getMainHandStack();
			ItemStack off = player.getOffHandStack();
			Item m = main != null ? main.getItem() : null;
			Item o = off != null ? off.getItem() : null;
			if(m == Items.DIAMOND_SWORD || m == Items.DIAMOND_AXE
				|| m == Items.DIAMOND_PICKAXE || m == Items.DIAMOND_SHOVEL
				|| m == Items.DIAMOND_HOE)
				return true;
			if(o == Items.DIAMOND_SWORD || o == Items.DIAMOND_AXE
				|| o == Items.DIAMOND_PICKAXE || o == Items.DIAMOND_SHOVEL
				|| o == Items.DIAMOND_HOE)
				return true;
		}catch(Throwable t)
		{
			// be defensive in case the player entity implementation differs
			return false;
		}
		
		return false;
	}
	
	private void chatDbg(String msg)
	{
		if(!debugSetting.isChecked())
			return;
		
		try
		{
			if(MC.inGameHud != null && MC.inGameHud.getChatHud() != null)
				MC.inGameHud.getChatHud()
					.addMessage(Text.literal("[FightBot] " + msg));
			else
				System.out.println("[FightBot] " + msg);
		}catch(Throwable t)
		{
			// fallback to stdout if anything goes wrong
			System.out.println("[FightBot] " + msg);
		}
	}
	
	@Override
	protected void onEnable()
	{
		// disable other killauras
		WURST.getHax().aimAssistHack.setEnabled(false);
		WURST.getHax().clickAuraHack.setEnabled(false);
		WURST.getHax().crystalAuraHack.setEnabled(false);
		WURST.getHax().killauraLegitHack.setEnabled(false);
		WURST.getHax().killauraHack.setEnabled(false);
		WURST.getHax().multiAuraHack.setEnabled(false);
		WURST.getHax().protectHack.setEnabled(false);
		WURST.getHax().triggerBotHack.setEnabled(false);
		WURST.getHax().tpAuraHack.setEnabled(false);
		WURST.getHax().tunnellerHack.setEnabled(false);
		
		pathFinder = new EntityPathFinder(MC.player);
		
		speed.resetTimer();
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(RenderListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		// remove listener
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(RenderListener.class, this);
		
		pathFinder = null;
		processor = null;
		ticksProcessing = 0;
		PathProcessor.releaseControls();
	}
	
	@Override
	public void onUpdate()
	{
		speed.updateTimer();
		
		if(pauseOnContainers.shouldPause())
			return;
			
		// if we're too far from the reference point (0,82,0), skip updates
		// using squaredDistance so this compares squared units (15*15 = 15
		// blocks)
		if(MC.player.getPos().y > 85 || MC.player.getPos().y < 80 || MC.player.squaredDistanceTo(0, 83, 0) > 15*15)
		// if(MC.player.getPos().y > 50)
		{
			// chatDbg(
			// "Player too far from origin (0,82,0), skipping update. dist="
			// + String.format("%.2f",
			// Math.sqrt(MC.player.squaredDistanceTo(0, 82, 0))));
			if (MC.player.getPos().y < 85) {
				MC.options.forwardKey.setPressed(false);
			}
			return;
		}
		
		// set entity: filter for PlayerEntity within 25 blocks
		List<Entity> entities =
			entityFilters.applyTo(EntityUtils.getAttackableEntities())
				.filter(entity -> entity instanceof PlayerEntity)
				.filter(entity -> MC.player.squaredDistanceTo(entity) <= 25 * 25)
				.collect(Collectors.toList());
		
		// return early if less than 10 players
		if(entities.size() < 10){
			return;
		}
		
		// chatDbg("Total attackable entities after filters: " +
		// entities.size());
		//
		Entity entity = null;
		while(!entities.isEmpty())
		{
			// pick nearest
			Entity candidate = entities.stream()
				.min(Comparator
					.comparingDouble(e -> MC.player.squaredDistanceTo(e)))
				.orElse(null);
			if(candidate == null)
				break;
			// remove candidate from list so we don't pick it again
			entities.remove(candidate);
			
			// must be a player
			if(!(candidate instanceof PlayerEntity))
			{
				// chatDbg("Skipping candidate (not a player): "
				// + candidate.toString());
				continue;
			}
			
			// check diamond gear only if the nearest player is within 6 blocks
			if(MC.player.squaredDistanceTo((PlayerEntity)candidate) < 5*5
				&& hasDiamondGear((PlayerEntity)candidate))
			{
				// chatDbg("Skipping candidate (has diamond gear): "
				// + ((PlayerEntity)candidate).getName().getString());
				continue;
			}
			
			// passed all checks
			entity = candidate;
			break;
		}
		
		if(entity == null)
		{
			// If we previously locked controls while using the AI path
			// processor,
			// make sure to release them when there's no target. Otherwise the
			// client can keep receiving control/rotation updates and appear to
			// spin around with no entities present.
			
			// PathProcessor.releaseControls();
			// processor = null;
			// // reset pathFinder base to the player so renderPath won't
			// operate on
			// // a stale target (optional, safe fallback).
			// if (pathFinder == null)
			// pathFinder = new EntityPathFinder(MC.player);
			return;
		}
		
		WURST.getHax().autoSwordHack.setSlot(entity);
		
		if(useAi.isChecked())
		{
			// reset pathfinder
			if((processor == null || processor.isDone() || ticksProcessing >= 10
				|| !pathFinder.isPathStillValid(processor.getIndex()))
				&& (pathFinder.isDone() || pathFinder.isFailed()))
			{
				pathFinder = new EntityPathFinder(entity);
				processor = null;
				ticksProcessing = 0;
			}
			
			// find path
			if(!pathFinder.isDone() && !pathFinder.isFailed())
			{
				PathProcessor.lockControls();
				WURST.getRotationFaker().faceVectorClient(
					entity.getBoundingBox().getCenter().add(0, 0, 0.55));
				pathFinder.think();
				pathFinder.formatPath();
				processor = pathFinder.getProcessor();
			}
			
			// process path
			if(!processor.isDone())
			{
				processor.process();
				ticksProcessing++;
			}
		}else
		{
			// jump if necessary
			if(MC.player.horizontalCollision && MC.player.isOnGround())
				MC.player.jump();
			
			// swim up if necessary
			if(MC.player.isTouchingWater() && MC.player.getY() < entity.getY())
				MC.player.addVelocity(0, 0.04, 0);
			
			// control height if flying
			if(!MC.player.isOnGround()
				&& (MC.player.getAbilities().flying
					|| WURST.getHax().flightHack.isEnabled())
				&& MC.player.squaredDistanceTo(entity.getX(), MC.player.getY(),
					entity.getZ()) <= MC.player.squaredDistanceTo(
						MC.player.getX(), entity.getY(), MC.player.getZ()))
			{
				if(MC.player.getY() > entity.getY() + 1D)
					MC.options.sneakKey.setPressed(true);
				else if(MC.player.getY() < entity.getY() - 1D)
					MC.options.jumpKey.setPressed(true);
			}else
			{
				MC.options.sneakKey.setPressed(false);
				MC.options.jumpKey.setPressed(false);
			}
			
			// follow entity
			MC.options.forwardKey.setPressed(
				MC.player.distanceTo(entity) > distance.getValueF() && MC.player.distanceTo(entity) < 10.0);
			WURST.getRotationFaker().faceVectorClient(
				entity.getBoundingBox().getCenter().add(0, 0.55, 0.0));
		}
		
		// check cooldown
		if(!speed.isTimeToAttack())
			return;
		
		// check range
		if(MC.player.squaredDistanceTo(entity) > Math.pow(range.getValue(), 2))
		{
			// If out of range, 1% chance to still attack (random fallback)
			// boolean fallback = Math.random() <= 0.01;
			
			// if (!fallback)
			
			return;
		}

		HitResult hit = client.crosshairTarget;

    	switch (hit.getType()) {
			case ENTITY -> {
				EntityHitResult entityHit = (EntityHitResult) hit;
				client.interactionManager.attackEntity(client.player, entityHit.getEntity());
				client.player.swingHand(Hand.MAIN_HAND);
				speed.resetTimer();
			}
			default -> {
				return;
			}
		}
	}
	
	@Override
	public void onRender(MatrixStack matrixStack, float partialTicks)
	{
		PathCmd pathCmd = WURST.getCmds().pathCmd;
		pathFinder.renderPath(matrixStack, pathCmd.isDebugMode(),
			pathCmd.isDepthTest());
	}
	
	private class EntityPathFinder extends PathFinder
	{
		private final Entity entity;
		
		public EntityPathFinder(Entity entity)
		{
			super(BlockPos.ofFloored(entity.getPos()));
			this.entity = entity;
			setThinkTime(1);
		}
		
		@Override
		protected boolean checkDone()
		{
			return done =
				entity.squaredDistanceTo(Vec3d.ofCenter(current)) <= Math
					.pow(distance.getValue(), 2);
		}
		
		@Override
		public ArrayList<PathPos> formatPath()
		{
			if(!done)
				failed = true;
			
			return super.formatPath();
		}
	}
}

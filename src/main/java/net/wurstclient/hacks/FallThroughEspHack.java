/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.ArrayList;

import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.RenderListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.util.BlockUtils;
import net.wurstclient.util.RenderUtils;

@SearchTags({"fall through esp", "hole esp", "void esp"})
public final class FallThroughEspHack extends Hack implements RenderListener
{
	private int lastGroundY = Integer.MIN_VALUE;

	public FallThroughEspHack()
	{
		super("HoleESP");
		setCategory(Category.RENDER);
	}

	@Override
	protected void onEnable()
	{
		if(MC.player != null)
			lastGroundY = MathHelper.floor(MC.player.getY()) - 1;

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
		if(MC.player == null || MC.world == null)
			return;

		BlockPos playerPos = MC.player.getBlockPos();
		int minY = Math.max(MC.world.getBottomY(), playerPos.getY() - 10);
		if(MC.player.isOnGround() || lastGroundY == Integer.MIN_VALUE)
			lastGroundY = MathHelper.floor(MC.player.getY()) - 1;

		int renderY = lastGroundY;

		double y = renderY - 2D / 16D;
		ArrayList<Box> boxes = new ArrayList<>(144);

		// If player is moving diagonally (has significant X and Z velocity),
		// check a full 12x12 area. Otherwise check a 12 (forward) x 9 (sideways)
		// front-facing rectangle to save computation.
		Vec3d vel = MC.player.getVelocity();
		// Raised threshold so small micro-movements don't trigger full-scan.
		final double DIAGONAL_THRESHOLD = 0.2;
		boolean movingDiagonally = Math.abs(vel.x) > DIAGONAL_THRESHOLD && Math.abs(vel.z) > DIAGONAL_THRESHOLD;

		if(movingDiagonally)
		{
			// Use horizontal facing to cull blocks behind the player.
			Direction forwardDir = MC.player.getHorizontalFacing();
			int fx = forwardDir.getOffsetX();
			int fz = forwardDir.getOffsetZ();
			final double COS70 = Math.cos(Math.toRadians(70.0));

			for(int dx = -6; dx < 6; dx++)
				for(int dz = -6; dz < 6; dz++)
				{
					// Cull tiles outside ±45° cone in front of the player
					// using dot product with the cardinal forward vector.
					int dot = dx * fx + dz * fz;
					double len = Math.sqrt((double)(dx * dx + dz * dz));
					if(len > 0.0 && (dot / len) < COS70)
						continue;

					int x = playerPos.getX() + dx;
					int z = playerPos.getZ() + dz;
					if(!isFallThroughSpot(x, z, minY, playerPos.getY()))
						continue;

					boxes.add(new Box(x, y, z, x + 1, y + 0.05D, z + 1));
				}
		}
		else
		{
			// Simpler axis math: compute integer forward vector (fx, fz)
			// and perpendicular (px, pz). This avoids rotating BlockPos
			// and keeps the area checks aligned to world axes.
			Direction forward = MC.player.getHorizontalFacing();
			int fx = forward.getOffsetX();
			int fz = forward.getOffsetZ();
			int px = -fz;
			int pz = fx;

			for(int forwardOffset = 0; forwardOffset < 7; forwardOffset++)
				for(int sidewaysOffset = -4; sidewaysOffset <= 4; sidewaysOffset++)
				{
					int x = playerPos.getX() + forwardOffset * fx + sidewaysOffset * px;
					int z = playerPos.getZ() + forwardOffset * fz + sidewaysOffset * pz;
					if(!isFallThroughSpot(x, z, minY, playerPos.getY()))
						continue;

					boxes.add(new Box(x, y, z, x + 1, y + 0.05D, z + 1));
				}
		}

		if(!boxes.isEmpty())
			RenderUtils.drawOutlinedBoxes(matrixStack, boxes, 0x80FF0000, false);
	}

	private boolean isFallThroughSpot(int x, int z, int minY, int maxY)
	{
		BlockPos.Mutable pos = new BlockPos.Mutable();
		for(int y = minY; y <= maxY; y++)
		{
			pos.set(x, y, z);
			if(!BlockUtils.getState(pos).isAir())
				return false;
		}

		return true;
	}
}
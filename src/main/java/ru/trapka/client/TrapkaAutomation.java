
package ru.trapka.client;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

public class TrapkaAutomation {

    private enum Phase {
        IDLE,
        DIGGING,
        WAITING,
        ROOFING,
        COBWEBBING
    }

    private static Phase phase = Phase.IDLE;
    private static AbstractClientPlayer target;
    private static BlockPos center;
    private static BlockPos webPos;

    private static final List<BlockPos> digPositions = new ArrayList<>();
    private static final List<BlockPos> roofPositions = new ArrayList<>();

    private static int playerStartY;
    private static int targetStartY;
    private static int originalSlot = -1;
    private static int pickaxeSlot = -1;
    private static int webSlot = -1;

    private static int digIndex;
    private static int digTicks;
    private static int waitTicks;
    private static int roofIndex;
    private static int placeTicks;
    private static int webTicks;
    private static boolean breaking;

    private static final int DEPTH = 3;
    private static final int REQUIRED_BLOCKS = 9;

    public static void tryStart(Minecraft client) {
        if (phase != Phase.IDLE || client.player == null
                || client.level == null || client.gameMode == null) {
            return;
        }

        var player = client.player;
        AbstractClientPlayer closest = null;
        double closestDistance = 4.0;

        for (AbstractClientPlayer other : client.level.players()) {
            if (other == player || !other.isAlive()) continue;

            double distance = player.distanceToSqr(other);
            if (distance <= closestDistance) {
                closestDistance = distance;
                closest = other;
            }
        }

        if (closest == null) return;

        BlockPos playerPos = player.blockPosition();
        BlockPos targetPos = closest.blockPosition();

        if (Math.abs(playerPos.getY() - targetPos.getY()) > 1
                || Math.abs(playerPos.getX() - targetPos.getX()) > 2
                || Math.abs(playerPos.getZ() - targetPos.getZ()) > 2) {
            return;
        }

        int foundPickaxe = -1;
        int foundWeb = -1;
        int blockCount = 0;

        for (int i = 0; i < 9; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.isEmpty()) continue;

            if (foundPickaxe == -1 && stack.is(ItemTags.PICKAXES)) {
                foundPickaxe = i;
            }

            if (foundWeb == -1 && stack.is(Items.COBWEB)) {
                foundWeb = i;
            }

            if (isBuildingBlock(stack)) {
                blockCount += stack.getCount();
            }
        }

        if (foundPickaxe == -1 || foundWeb == -1
                || blockCount < REQUIRED_BLOCKS) {
            return;
        }

        target = closest;
        originalSlot = player.getInventory().getSelectedSlot();
        pickaxeSlot = foundPickaxe;
        webSlot = foundWeb;
        playerStartY = playerPos.getY();
        targetStartY = targetPos.getY();

        int centerX = (int) Math.floor(
                (playerPos.getX() + targetPos.getX()) / 2.0);
        int centerZ = (int) Math.floor(
                (playerPos.getZ() + targetPos.getZ()) / 2.0);

        center = new BlockPos(centerX, playerStartY, centerZ);

        digPositions.clear();
        roofPositions.clear();

        // Dig only inside the 3x3 area, three blocks deep.
        for (int layer = 0; layer < DEPTH; layer++) {
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) {
                    digPositions.add(new BlockPos(
                            center.getX() + x,
                            playerStartY - 1 - layer,
                            center.getZ() + z));
                }
            }
        }

        // Roof: all nine blocks above the pit.
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                roofPositions.add(new BlockPos(
                        center.getX() + x,
                        playerStartY - 1,
                        center.getZ() + z));
            }
        }

        digIndex = 0;
        digTicks = 0;
        waitTicks = 0;
        roofIndex = 0;
        placeTicks = 0;
        webTicks = 0;
        webPos = null;
        breaking = false;

        player.getInventory().setSelectedSlot(pickaxeSlot);
        phase = Phase.DIGGING;
    }

    public static void tick(Minecraft client) {
        if (phase == Phase.IDLE) return;

        if (client.player == null || client.level == null
                || client.gameMode == null) {
            finish(client);
            return;
        }

        switch (phase) {
            case DIGGING -> tickDigging(client);
            case WAITING -> tickWaiting(client);
            case ROOFING -> tickRoofing(client);
            case COBWEBBING -> tickCobwebbing(client);
            default -> { }
        }
    }

    private static void tickDigging(Minecraft client) {
        while (digIndex < digPositions.size()) {
            BlockPos pos = digPositions.get(digIndex);
            BlockState state = client.level.getBlockState(pos);

            if (state.isAir()) {
                if (breaking) client.gameMode.stopDestroyBlock();

                breaking = false;
                digTicks = 0;
                digIndex++;
                continue;
            }

            if (state.getDestroySpeed(client.level, pos) < 0.0F
                    || digTicks++ > 1200) {
                finish(client);
                return;
            }

            client.player.getInventory().setSelectedSlot(pickaxeSlot);

            if (!breaking) {
                client.gameMode.startDestroyBlock(pos, Direction.UP);
                breaking = true;
            } else {
                client.gameMode.continueDestroyBlock(pos, Direction.UP);
            }

            return;
        }

        if (breaking) client.gameMode.stopDestroyBlock();

        breaking = false;
        phase = Phase.WAITING;
        waitTicks = 0;
    }

    private static void tickWaiting(Minecraft client) {
        if (target == null || !target.isAlive()) {
            finish(client);
            return;
        }

        boolean playerFell =
                client.player.blockPosition().getY() <= playerStartY - DEPTH;

        boolean targetFell =
                target.blockPosition().getY() <= targetStartY - DEPTH;

        if (playerFell && targetFell
                && isInsideHole(client.player.blockPosition())
                && isInsideHole(target.blockPosition())) {
            phase = Phase.ROOFING;
            roofIndex = 0;
            placeTicks = 0;
            return;
        }

        if (waitTicks++ > 240) finish(client);
    }

    private static boolean isInsideHole(BlockPos pos) {
        return Math.abs(pos.getX() - center.getX()) <= 1
                && Math.abs(pos.getZ() - center.getZ()) <= 1;
    }

    private static boolean isBuildingBlock(ItemStack stack) {
        if (!(stack.getItem() instanceof BlockItem blockItem)) return false;

        return blockItem.getBlock()
                .defaultBlockState()
                .canOcclude();
    }

    private static int randomBlockSlot(Minecraft client) {
        List<Integer> slots = new ArrayList<>();

        for (int i = 0; i < 9; i++) {
            ItemStack stack = client.player.getInventory().getItem(i);

            if (isBuildingBlock(stack)) slots.add(i);
        }

        if (slots.isEmpty()) return -1;

        return slots.get(
                ThreadLocalRandom.current().nextInt(slots.size()));
    }

    private static BlockHitResult findPlacementHit(
            Minecraft client, BlockPos placePos) {

        for (Direction direction : Direction.values()) {
            BlockPos supportPos = placePos.relative(direction);
            BlockState support = client.level.getBlockState(supportPos);

            if (support.isAir() || !support.canOcclude()) continue;

            Direction face = direction.getOpposite();

            Vec3 hit = new Vec3(
                    supportPos.getX() + 0.5 + face.getStepX() * 0.5,
                    supportPos.getY() + 0.5 + face.getStepY() * 0.5,
                    supportPos.getZ() + 0.5 + face.getStepZ() * 0.5);

            return new BlockHitResult(hit, face, supportPos, false);
        }

        return null;
    }

    private static void tickRoofing(Minecraft client) {
        if (roofIndex >= roofPositions.size()) {
            for (BlockPos pos : roofPositions) {
                if (client.level.getBlockState(pos).isAir()) {
                    finish(client);
                    return;
                }
            }

            phase = Phase.COBWEBBING;
            webPos = null;
            webTicks = 0;
            return;
        }

        BlockPos placePos = roofPositions.get(roofIndex);

        if (!client.level.getBlockState(placePos).isAir()) {
            roofIndex++;
            placeTicks = 0;
            return;
        }

        if (placeTicks++ > 100) {
            finish(client);
            return;
        }

        if (placeTicks % 5 != 0) return;

        int slot = randomBlockSlot(client);

        if (slot == -1) {
            finish(client);
            return;
        }

        client.player.getInventory().setSelectedSlot(slot);

        BlockHitResult hit = findPlacementHit(client, placePos);

        if (hit == null) return;

        InteractionResult result = client.gameMode.useItemOn(
                client.player,
                InteractionHand.MAIN_HAND,
                hit);

        if (result.consumesAction()) placeTicks = 0;
    }

    private static void tickCobwebbing(Minecraft client) {
        if (target == null || !target.isAlive()) {
            finish(client);
            return;
        }

        if (webPos == null) {
            webPos = target.blockPosition();

            // Do not place a cobweb where the local player is standing.
            if (webPos.equals(client.player.blockPosition())
                    || !isInsideHole(webPos)
                    || !client.level.getBlockState(webPos).isAir()) {
                finish(client);
                return;
            }
        }

        if (client.level.getBlockState(webPos).is(Blocks.COBWEB)) {
            finish(client);
            return;
        }

        if (webTicks++ > 100) {
            finish(client);
            return;
        }

        if (webTicks % 5 != 0) return;

        client.player.getInventory().setSelectedSlot(webSlot);

        BlockHitResult hit = findPlacementHit(client, webPos);

        if (hit == null) return;

        InteractionResult result = client.gameMode.useItemOn(
                client.player,
                InteractionHand.MAIN_HAND,
                hit);

        if (result.consumesAction()) webTicks = 0;
    }

    private static void finish(Minecraft client) {
        if (breaking && client.gameMode != null) {
            client.gameMode.stopDestroyBlock();
        }

        if (client.player != null && originalSlot >= 0) {
            client.player.getInventory().setSelectedSlot(originalSlot);
        }

        phase = Phase.IDLE;
        target = null;
        center = null;
        webPos = null;

        originalSlot = -1;
        pickaxeSlot = -1;
        webSlot = -1;

        breaking = false;
        digPositions.clear();
        roofPositions.clear();
    }
}

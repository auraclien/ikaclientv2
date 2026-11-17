
package ru.trapka.client;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

public class TrapkaAutomation {

    private enum Phase {
        IDLE,
        DIGGING,
        WAITING,
        ROOFING
    }

    private static Phase phase = Phase.IDLE;

    private static AbstractClientPlayer target;
    private static BlockPos center;

    private static final List<BlockPos> digPositions =
            new ArrayList<>();

    private static final List<BlockPos> roofPositions =
            new ArrayList<>();

    private static int playerStartY;
    private static int targetStartY;

    private static int originalSlot = -1;
    private static int pickaxeSlot = -1;

    private static int digIndex;
    private static int digTicks;
    private static int waitTicks;
    private static int roofIndex;
    private static int placeTicks;

    private static boolean breaking;

    private static final int DEPTH = 3;
    private static final int WIDTH = 3;
    private static final int REQUIRED_BLOCKS = 9;

    // Запуск ловушки по клавише V

    public static void tryStart(Minecraft client) {

        if (phase != Phase.IDLE) return;

        if (client.player == null ||
                client.level == null ||
                client.gameMode == null) {
            return;
        }

        var player = client.player;

        AbstractClientPlayer closest = null;
        double closestDistance = 4.0;

        // Поиск игрока в радиусе 2 блоков

        for (AbstractClientPlayer other :
                client.level.players()) {

            if (other == player || !other.isAlive()) {
                continue;
            }

            double distance =
                    player.distanceToSqr(other);

            if (distance <= closestDistance) {
                closestDistance = distance;
                closest = other;
            }
        }

        if (closest == null) return;

        // Не запускаем ловушку, если игроки сильно
        // отличаются по высоте

        if (Math.abs(
                player.blockPosition().getY()
                - closest.blockPosition().getY()
        ) > 1) {
            return;
        }

        int foundPickaxe = -1;
        int blockCount = 0;

        // Проверяем хотбар

        for (int i = 0; i < 9; i++) {

            ItemStack stack =
                    player.getInventory().getItem(i);

            if (stack.isEmpty()) continue;

            if (foundPickaxe == -1 &&
                    stack.getItem() instanceof PickaxeItem) {

                foundPickaxe = i;
            }

            if (isBuildingBlock(stack)) {
                blockCount += stack.getCount();
            }
        }

        if (foundPickaxe == -1 ||
                blockCount < REQUIRED_BLOCKS) {
            return;
        }

        target = closest;

        originalSlot =
                player.getInventory().getSelectedSlot();

        pickaxeSlot = foundPickaxe;

        playerStartY =
                player.blockPosition().getY();

        targetStartY =
                target.blockPosition().getY();

        center = BlockPos.containing(
                (player.getX() + target.getX()) / 2.0,
                playerStartY,
                (player.getZ() + target.getZ()) / 2.0
        );

        digPositions.clear();
        roofPositions.clear();

        // Создаём яму 3x3 глубиной 3 блока

        for (int layer = 0; layer < DEPTH; layer++) {

            for (int x = -1; x <= 1; x++) {

                for (int z = -1; z <= 1; z++) {

                    digPositions.add(
                            new BlockPos(
                                    center.getX() + x,
                                    playerStartY - 1 - layer,
                                    center.getZ() + z
                            )
                    );
                }
            }
        }

        // Сначала края крыши, затем центр

        for (int x = -1; x <= 1; x++) {

            for (int z = -1; z <= 1; z++) {

                if (Math.max(
                        Math.abs(x),
                        Math.abs(z)
                ) == 1) {

                    roofPositions.add(
                            new BlockPos(
                                    center.getX() + x,
                                    playerStartY - 1,
                                    center.getZ() + z
                            )
                    );
                }
            }
        }

        roofPositions.add(
                new BlockPos(
                        center.getX(),
                        playerStartY - 1,
                        center.getZ()
                )
        );

        digIndex = 0;
        digTicks = 0;
        waitTicks = 0;
        roofIndex = 0;
        placeTicks = 0;
        breaking = false;

        player.getInventory().setSelectedSlot(pickaxeSlot);

        phase = Phase.DIGGING;
    }

    // Обработка каждого игрового тика

    public static void tick(Minecraft client) {

        if (phase == Phase.IDLE) return;

        if (client.player == null ||
                client.level == null ||
                client.gameMode == null) {

            finish(client);
            return;
        }

        switch (phase) {

            case DIGGING:
                tickDigging(client);
                break;

            case WAITING:
                tickWaiting(client);
                break;

            case ROOFING:
                tickRoofing(client);
                break;

            default:
                break;
        }
    }

    // Копаем все 27 блоков

    private static void tickDigging(Minecraft client) {

        while (digIndex < digPositions.size()) {

            BlockPos pos = digPositions.get(digIndex);

            BlockState state =
                    client.level.getBlockState(pos);

            // Если блок уже сломан зачарованием 3x3
            // или другим действием, пропускаем его

            if (state.isAir()) {

                if (breaking) {
                    client.gameMode.stopDestroyBlock();
                }

                breaking = false;
                digTicks = 0;
                digIndex++;

                continue;
            }

            // Не пытаемся бесконечно ломать
            // неразрушаемые блоки

            if (state.getDestroySpeed(
                    client.level,
                    pos
            ) < 0.0F) {

                finish(client);
                return;
            }

            if (digTicks++ > 1200) {
                finish(client);
                return;
            }

            client.player.getInventory()
                    .setSelectedSlot(pickaxeSlot);

            if (!breaking) {

                client.gameMode.startDestroyBlock(
                        pos,
                        Direction.UP
                );

                breaking = true;

            } else {

                client.gameMode.continueDestroyBlock(
                        pos,
                        Direction.UP
                );
            }

            return;
        }

        // Все слои выкопаны

        if (breaking) {
            client.gameMode.stopDestroyBlock();
        }

        breaking = false;
        phase = Phase.WAITING;
        waitTicks = 0;
    }

    // Ждём, пока оба игрока упадут на 3 блока

    private static void tickWaiting(Minecraft client) {

        if (target == null || !target.isAlive()) {
            finish(client);
            return;
        }

        boolean playerFell =
                client.player.blockPosition().getY()
                        <= playerStartY - 3;

        boolean targetFell =
                target.blockPosition().getY()
                        <= targetStartY - 3;

        boolean playerInside =
                isInsideHole(client.player.blockPosition());

        boolean targetInside =
                isInsideHole(target.blockPosition());

        if (playerFell && targetFell &&
                playerInside && targetInside) {

            phase = Phase.ROOFING;
            roofIndex = 0;
            placeTicks = 0;
            return;
        }

        // Если оба не оказались внутри,
        // не закрываем яму только над одним игроком

        if (waitTicks++ > 200) {
            finish(client);
        }
    }

    private static boolean isInsideHole(BlockPos pos) {

        return Math.abs(pos.getX() - center.getX()) <= 1
                && Math.abs(pos.getZ() - center.getZ()) <= 1;
    }

    // Проверка подходящих строительных блоков

    private static boolean isBuildingBlock(ItemStack stack) {

        if (!(stack.getItem() instanceof BlockItem blockItem)) {
            return false;
        }

        return blockItem.getBlock()
                .defaultBlockState()
                .canOcclude();
    }

    // Выбираем случайный блок из хотбара

    private static int randomBlockSlot(Minecraft client) {

        List<Integer> slots = new ArrayList<>();

        for (int i = 0; i < 9; i++) {

            ItemStack stack =
                    client.player.getInventory().getItem(i);

            if (isBuildingBlock(stack)) {
                slots.add(i);
            }
        }

        if (slots.isEmpty()) {
            return -1;
        }

        return slots.get(
                ThreadLocalRandom.current().nextInt(
                        slots.size()
                )
        );
    }

    // Находим соседний блок для установки крыши

    private static BlockHitResult findPlacementHit(
            Minecraft client,
            BlockPos placePos
    ) {

        for (Direction direction : Direction.values()) {

            BlockPos supportPos =
                    placePos.relative(direction);

            BlockState support =
                    client.level.getBlockState(supportPos);

            if (support.isAir() || !support.canOcclude()) {
                continue;
            }

            Direction face = direction.getOpposite();

            Vec3 hit = new Vec3(
                    supportPos.getX() + 0.5
                            + face.getStepX() * 0.5,

                    supportPos.getY() + 0.5
                            + face.getStepY() * 0.5,

                    supportPos.getZ() + 0.5
                            + face.getStepZ() * 0.5
            );

            return new BlockHitResult(
                    hit,
                    face,
                    supportPos,
                    false
            );
        }

        return null;
    }

    // Закрываем яму блоками из хотбара

    private static void tickRoofing(Minecraft client) {

        if (roofIndex >= roofPositions.size()) {
            finish(client);
            return;
        }

        BlockPos placePos =
                roofPositions.get(roofIndex);

        // Уже заполненное место пропускаем

        if (!client.level.getBlockState(placePos).isAir()) {

            roofIndex++;
            placeTicks = 0;
            return;
        }

        if (placeTicks++ > 60) {
            finish(client);
            return;
        }

        // Не пытаемся ставить блок каждый тик

        if (placeTicks % 5 != 0) {
            return;
        }

        int slot = randomBlockSlot(client);

        if (slot == -1) {
            finish(client);
            return;
        }

        client.player.getInventory().setSelectedSlot(slot);

        BlockHitResult hit =
                findPlacementHit(client, placePos);

        if (hit == null) {
            return;
        }

        InteractionResult result =
                client.gameMode.useItemOn(
                        client.player,
                        InteractionHand.MAIN_HAND,
                        hit
                );

        // Если установка принята, проверим блок
        // на следующем тике

        if (result.consumesAction()) {
            placeTicks = 0;
        }
    }

    // Завершение работы

    private static void finish(Minecraft client) {

        if (breaking && client.gameMode != null) {
            client.gameMode.stopDestroyBlock();
        }

        if (client.player != null && originalSlot >= 0) {

            client.player.getInventory()
                    .setSelectedSlot(originalSlot);
        }

        phase = Phase.IDLE;

        target = null;
        center = null;

        originalSlot = -1;
        pickaxeSlot = -1;

        breaking = false;

        digPositions.clear();
        roofPositions.clear();
    }
}

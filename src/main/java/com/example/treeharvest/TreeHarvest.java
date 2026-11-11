package com.example.treeharvest;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Tree Harvest: quebrou um tronco com um machado, a arvore inteira cai.
 *
 * Regras:
 *  - precisa estar segurando um machado (tag minecraft:axes)
 *  - agachado (shift) = quebra normal, so um bloco
 *  - so derruba se o grupo de troncos encostar em folhas (evita destruir casas de madeira)
 *  - cada tronco gasta 1 de durabilidade; o machado nunca quebra por causa do mod
 */
public class TreeHarvest implements ModInitializer {
    public static final String MOD_ID = "treeharvest";

    /** Limite de troncos por arvore, pra nao travar o servidor. */
    private static final int MAX_LOGS = 256;

    /** Evita o evento disparar de novo enquanto a gente mesmo quebra os blocos. */
    private static boolean felling = false;

    @Override
    public void onInitialize() {
        PlayerBlockBreakEvents.AFTER.register(TreeHarvest::onBlockBroken);
    }

    private static void onBlockBroken(Level level, Player player, BlockPos pos, BlockState state, BlockEntity blockEntity) {
        if (felling) return;
        if (!(level instanceof ServerLevel serverLevel)) return;
        if (!(player instanceof ServerPlayer)) return;
        if (player.isShiftKeyDown()) return;
        if (!state.is(BlockTags.LOGS)) return;

        ItemStack tool = player.getMainHandItem();
        if (!tool.is(ItemTags.AXES)) return;

        // O bloco original ja foi quebrado; procura os troncos conectados a ele.
        List<BlockPos> logs = findConnectedLogs(serverLevel, pos, state);
        if (logs.isEmpty()) return;

        boolean creative = player.isCreative();

        felling = true;
        try {
            for (BlockPos logPos : logs) {
                if (!creative && tool.isDamageableItem()
                        && tool.getDamageValue() >= tool.getMaxDamage() - 1) {
                    break; // deixa o machado com 1 de durabilidade
                }

                // destroyBlock(pos, dropItems, entity) solta os itens e toca o efeito de quebra
                serverLevel.destroyBlock(logPos, !creative, player);

                if (!creative) {
                    tool.hurtAndBreak(1, player, EquipmentSlot.MAINHAND);
                }
            }
        } finally {
            felling = false;
        }
    }

    /**
     * Flood fill (26 vizinhos, pega galhos na diagonal) a partir do bloco quebrado,
     * so passando por troncos do mesmo tipo do original.
     * Devolve lista vazia se nao achar folhas encostadas na arvore.
     */
    private static List<BlockPos> findConnectedLogs(ServerLevel level, BlockPos origin, BlockState originState) {
        Set<BlockPos> visited = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        List<BlockPos> result = new ArrayList<>();

        visited.add(origin);
        queue.add(origin);

        boolean touchesLeaves = hasAdjacentLeaves(level, origin);

        while (!queue.isEmpty() && result.size() < MAX_LOGS) {
            BlockPos current = queue.poll();

            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;

                        BlockPos next = current.offset(dx, dy, dz);
                        if (!visited.add(next)) continue;

                        BlockState nextState = level.getBlockState(next);
                        if (!nextState.is(originState.getBlock())) continue;

                        result.add(next);
                        queue.add(next);

                        if (!touchesLeaves && hasAdjacentLeaves(level, next)) {
                            touchesLeaves = true;
                        }

                        if (result.size() >= MAX_LOGS) break;
                    }
                }
            }
        }

        return touchesLeaves ? result : List.of();
    }

    private static boolean hasAdjacentLeaves(ServerLevel level, BlockPos pos) {
        for (Direction dir : Direction.values()) {
            if (level.getBlockState(pos.relative(dir)).is(BlockTags.LEAVES)) {
                return true;
            }
        }
        return false;
    }
}

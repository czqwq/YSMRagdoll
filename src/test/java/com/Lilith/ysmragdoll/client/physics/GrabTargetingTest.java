package com.Lilith.ysmragdoll.client.physics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

/**
 * 牵引模式的兜底选取：只要准星大致对着模型，就应该选中正前方的那具布娃娃。
 *
 * <p>
 * 现场日志里射线起点与终点完全相同（长度为零），说明进入
 * {@code ClientPhysicsWorld#grab} 的线段已经退化成一次点查询，
 * JBullet 的 {@code rayTest} 在这种输入下永远不会命中任何刚体。这里的用例固定住
 * "射线退化时由调用方传入有效触及距离、兜底仍能选中视线附近最近肢体"这条行为，
 * 防止再次回归。
 * </p>
 */
public class GrabTargetingTest {

    private static final double TOLERANCE = 0.9D;
    /** 调用方（{@code ClientPhysicsWorld#nearestTarget}）传下来的有效触及距离。 */
    private static final double EFFECTIVE_REACH = 12.0D;

    @Test
    public void degenerateRayStillSelectsTheBodyInFront() {
        List<ClientPhysicsWorld.Candidate> candidates = new ArrayList<>();
        candidates.add(candidate(0.0D, 0.0D, 4.0D));
        // 与现场日志一致：起点与终点相同，线段本身长度为零；有效距离由调用方另给。
        int selected = ClientPhysicsWorld.selectNearest(candidates, 0, 0, 0, 0, 0, 1, EFFECTIVE_REACH);
        assertEquals(0, selected);
        assertTrue(candidates.get(0).perpendicular <= TOLERANCE);
        assertEquals(4.0D, candidates.get(0).along, 1.0E-6D);
    }

    @Test
    public void picksTheClosestPerpendicularCandidateNotTheFurthest() {
        List<ClientPhysicsWorld.Candidate> candidates = new ArrayList<>();
        candidates.add(candidate(0.75D, 0.0D, 5.0D));
        candidates.add(candidate(0.10D, 0.0D, 3.0D));
        candidates.add(candidate(-0.40D, 0.0D, 8.0D));
        int selected = ClientPhysicsWorld.selectNearest(candidates, 0, 0, 0, 0, 0, 1, EFFECTIVE_REACH);
        assertEquals(1, selected);
        assertEquals(0.10D, candidates.get(1).perpendicular, 1.0E-6D);
    }

    @Test
    public void candidatesOutsideToleranceOrBehindTheEyeAreRejected() {
        List<ClientPhysicsWorld.Candidate> candidates = new ArrayList<>();
        candidates.add(candidate(1.5D, 0.0D, 3.0D));
        candidates.add(candidate(0.5D, 0.0D, -2.0D));
        assertEquals(-1, ClientPhysicsWorld.selectNearest(candidates, 0, 0, 0, 0, 0, 1, EFFECTIVE_REACH));
    }

    @Test
    public void candidateBeyondEffectiveReachIsRejected() {
        List<ClientPhysicsWorld.Candidate> candidates = new ArrayList<>();
        candidates.add(candidate(0.0D, 0.0D, 40.0D));
        assertEquals(-1, ClientPhysicsWorld.selectNearest(candidates, 0, 0, 0, 0, 0, 1, EFFECTIVE_REACH));
        // 方块把射线裁剪到 2 格时，墙后的尸体必须被拒绝。
        assertEquals(-1, ClientPhysicsWorld.selectNearest(candidates, 0, 0, 0, 0, 0, 1, 2.0D));
    }

    @Test
    public void zeroEffectiveReachSelectsNothing() {
        List<ClientPhysicsWorld.Candidate> candidates = new ArrayList<>();
        candidates.add(candidate(0.0D, 0.0D, 3.0D));
        assertEquals(-1, ClientPhysicsWorld.selectNearest(candidates, 0, 0, 0, 0, 0, 1, 0.0D));
    }

    @Test
    public void zeroDirectionOrEmptyCandidateListIsRejected() {
        List<ClientPhysicsWorld.Candidate> candidates = new ArrayList<>();
        candidates.add(candidate(0.0D, 0.0D, 3.0D));
        assertEquals(-1, ClientPhysicsWorld.selectNearest(candidates, 0, 0, 0, 0, 0, 0, EFFECTIVE_REACH));
        assertEquals(-1, ClientPhysicsWorld.selectNearest(new ArrayList<>(), 0, 0, 0, 0, 0, 1, EFFECTIVE_REACH));
        assertEquals(-1, ClientPhysicsWorld.selectNearest(null, 0, 0, 0, 0, 0, 1, EFFECTIVE_REACH));
    }

    @Test
    public void perpendicularDistanceIsMeasuredAgainstTheRayNotTheEye() {
        // 视线沿 +X，候选位于正前方 5 格、横向偏 0.3 格：垂距应为 0.3。
        List<ClientPhysicsWorld.Candidate> candidates = new ArrayList<>();
        candidates.add(candidate(5.0D, 0.3D, 0.0D));
        int selected = ClientPhysicsWorld.selectNearest(candidates, 0, 0, 0, 1, 0, 0, EFFECTIVE_REACH);
        assertEquals(0, selected);
        assertEquals(0.3D, candidates.get(0).perpendicular, 1.0E-6D);
        assertEquals(5.0D, candidates.get(0).along, 1.0E-6D);
    }

    private static ClientPhysicsWorld.Candidate candidate(double x, double y, double z) {
        return new ClientPhysicsWorld.Candidate(x, y, z, 0.35D);
    }
}

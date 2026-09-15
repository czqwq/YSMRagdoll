package com.Lilith.ysmragdoll.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 牵引模式的瞄准几何。
 *
 * <p>
 * 现场日志（1.7.10 / GTNH）里射线起点与终点完全重合、长度为零，选中部位永远是“无”。
 * 根因不是坐标算错，而是 {@code World#rayTraceBlocks} 会<b>就地改写</b>传入的起点向量：
 * {@code func_147447_a} 直接对参数 {@code p_147447_1_} 的 xCoord/yCoord/zCoord 做增减，
 * 把它沿射线一路推到命中点。于是调用方随后读到的 {@code Aim.eye} 已经是“地面命中点”，
 * 用同一个点当起点和终点，JBullet 的 {@code rayTest} 就退化成一次点查询。
 * </p>
 *
 * <p>
 * 这里的用例固定住两条行为：一是原版那种就地改写绝不能影响我们的眼位、终点和有效距离；
 * 二是交给 JBullet 的线段永远不能是零长度。
 * </p>
 */
public class GravityGunAimTest {

    private static final double EPS = 1.0E-9D;
    private static final double REACH = 12.0D;

    /** 现场日志：眼位、方向取自 00:03:01 那一帧，地面在 y = 2。 */
    private static final double EYE_X = -1424.61D;
    private static final double EYE_Y = 3.66D;
    private static final double EYE_Z = -380.70D;
    private static final double GROUND_Y = 2.0D;

    @Test
    public void vanillaInPlaceMutationCannotCorruptTheAim() {
        double[] eye = { EYE_X, EYE_Y, EYE_Z };
        double[] direction = { -0.16D, -0.83D, 0.54D };
        GravityGunController.Aim aim = GravityGunController
            .computeAim(eye, direction, REACH, (from, to) -> traceGround(from, to, GROUND_Y));

        // 眼位必须还是眼睛，而不是被射线推到地面的命中点。
        assertEquals(EYE_X, aim.eyeX, EPS);
        assertEquals(EYE_Y, aim.eyeY, EPS);
        assertEquals(EYE_Z, aim.eyeZ, EPS);
        // 传给 tracer 的也是副本：原版改成什么样都不影响我们保存的坐标。
        assertEquals(EYE_X, eye[0], EPS);
        assertEquals(EYE_Y, eye[1], EPS);
        assertEquals(EYE_Z, eye[2], EPS);
    }

    @Test
    public void clippedSegmentIsNeverADegeneratePoint() {
        double[] eye = { EYE_X, EYE_Y, EYE_Z };
        double[] direction = { -0.16D, -0.83D, 0.54D };
        GravityGunController.Aim aim = GravityGunController
            .computeAim(eye, direction, REACH, (from, to) -> traceGround(from, to, GROUND_Y));

        // 眼睛到地面大约 2 格（3.66 -> 2.00，俯角约 56 度），回退 0.05 后仍是有效线段。
        assertTrue("线段长度必须大于零，现场 bug 时是 0.00", aim.clippedReach > 1.0D);
        assertTrue(aim.clippedReach < aim.reach);
        // end 必须由裁剪后的长度重新算出来，长度恒等于有效距离。
        assertEquals(aim.clippedReach, segmentLength(aim), 1.0E-6D);
        assertEquals(GROUND_Y, aim.endY, 0.1D);
    }

    @Test
    public void noBlockHitKeepsTheFullReach() {
        GravityGunController.Aim aim = GravityGunController.computeAim(
            new double[] { 0.0D, 64.0D, 0.0D },
            new double[] { 0.0D, 0.0D, 1.0D },
            REACH,
            (from, to) -> null);
        assertEquals(REACH, aim.clippedReach, EPS);
        assertEquals(REACH, segmentLength(aim), 1.0E-6D);
        // 眼位在 y = 64，方向是 +Z：终点只能是 (0, 64, 12)。
        assertEquals(64.0D, aim.endY, EPS);
        assertEquals(REACH, aim.endZ, 1.0E-6D);
    }

    @Test
    public void eyeInsideABlockStillGetsAUsableSegment() {
        // 命中点就在眼睛里（头卡在方块里）：照常裁剪会得到零长度，必须退回完整触及距离。
        GravityGunController.Aim aim = GravityGunController.computeAim(
            new double[] { 1.0D, 2.0D, 3.0D },
            new double[] { 0.0D, 1.0D, 0.0D },
            REACH,
            (from, to) -> new double[] { from[0], from[1], from[2] });
        assertEquals(REACH, aim.clippedReach, EPS);
        assertTrue(segmentLength(aim) > 1.0D);
    }

    @Test
    public void clippedRayLengthOnlyShortensForRealHits() {
        assertEquals(REACH, GravityGunController.clippedRayLength(0.0D, REACH), EPS);
        assertEquals(REACH, GravityGunController.clippedRayLength(-0.05D, REACH), EPS);
        assertEquals(REACH, GravityGunController.clippedRayLength(0.4D, REACH), EPS);
        assertEquals(REACH, GravityGunController.clippedRayLength(Double.NaN, REACH), EPS);
        assertEquals(REACH, GravityGunController.clippedRayLength(Double.POSITIVE_INFINITY, REACH), EPS);
        // 真正的遮挡照常生效，且不会超过触及距离。
        assertEquals(3.4D, GravityGunController.clippedRayLength(3.4D, REACH), EPS);
        assertEquals(REACH, GravityGunController.clippedRayLength(40.0D, REACH), EPS);
    }

    @Test
    public void eyeOffsetIsTheDifferenceFromTheDefault() {
        // 1.7.10 的 posY 已经包含默认眼高，原版只补差值；输入是 float，容差按 float 精度给。
        double tolerance = 1.0E-6D;
        assertEquals(0.0D, GravityGunController.eyeOffset(1.62F, 1.62F), EPS);
        assertEquals(0.12D, GravityGunController.eyeOffset(1.62F, 1.5F), tolerance);
        assertEquals(-0.08D, GravityGunController.eyeOffset(1.54F, 1.62F), tolerance);
        // 客户端 EntityPlayer 的默认眼高实际是 0.12，所以站立时修正为 0。
        assertEquals(0.0D, GravityGunController.eyeOffset(0.12F, 0.12F), EPS);
    }

    @Test
    public void brokenEyeHeightsDoNotMoveTheCamera() {
        assertEquals(0.0D, GravityGunController.eyeOffset(Float.NaN, 1.62F), EPS);
        assertEquals(0.0D, GravityGunController.eyeOffset(1.62F, Float.NaN), EPS);
        assertEquals(0.0D, GravityGunController.eyeOffset(Float.POSITIVE_INFINITY, 0.0F), EPS);
        // 差值离谱到不可能时也退回原版约定，而不是把视线甩到天上。
        assertEquals(0.0D, GravityGunController.eyeOffset(1000.0F, 0.0F), EPS);
    }

    @Test
    public void unitVectorNormalizesAndFallsBackToPlusZ() {
        double[] unit = GravityGunController.unitVector(0.0D, 0.0D, 5.0D);
        assertEquals(1.0D, length(unit), EPS);
        assertEquals(1.0D, unit[2], EPS);
        // 零向量与非法值都回退到 +Z：长度为零的方向会让 JBullet 的 rayTest 直接失效。
        for (double[] fallback : new double[][] { GravityGunController.unitVector(0.0D, 0.0D, 0.0D),
            GravityGunController.unitVector(Double.NaN, 0.0D, 0.0D),
            GravityGunController.unitVector(0.0D, Double.POSITIVE_INFINITY, 0.0D) }) {
            assertNotNull(fallback);
            assertEquals(0.0D, fallback[0], EPS);
            assertEquals(0.0D, fallback[1], EPS);
            assertEquals(1.0D, fallback[2], EPS);
        }
    }

    @Test
    public void invalidReachFallsBackToTheConfiguredMaximum() {
        GravityGunController.Aim aim = GravityGunController
            .computeAim(new double[] { 0.0D, 0.0D, 0.0D }, new double[] { 0.0D, 0.0D, 1.0D }, 0.0D, (from, to) -> null);
        assertEquals(12.0D, aim.reach, EPS);
        assertEquals(12.0D, aim.clippedReach, EPS);
    }

    /**
     * 模拟 {@code World#func_147447_a}：把 from 就地推到射线与地面的交点，然后返回该点。
     *
     * <p>
     * 这正是现场日志里“起点 = 终点 = 地面命中点”的成因，测试必须能复现它。
     * </p>
     */
    private static double[] traceGround(double[] from, double[] to, double groundY) {
        double deltaY = to[1] - from[1];
        if (Math.abs(deltaY) < 1.0E-9D) {
            return null;
        }
        double fraction = (groundY - from[1]) / deltaY;
        if (!(fraction > 0.0D) || fraction > 1.0D) {
            return null;
        }
        double hitX = from[0] + (to[0] - from[0]) * fraction;
        double hitZ = from[2] + (to[2] - from[2]) * fraction;
        from[0] = hitX;
        from[1] = groundY;
        from[2] = hitZ;
        return new double[] { hitX, groundY, hitZ };
    }

    private static double segmentLength(GravityGunController.Aim aim) {
        return Math.sqrt(square(aim.endX - aim.eyeX) + square(aim.endY - aim.eyeY) + square(aim.endZ - aim.eyeZ));
    }

    private static double length(double[] vector) {
        return Math.sqrt(square(vector[0]) + square(vector[1]) + square(vector[2]));
    }

    private static double square(double value) {
        return value * value;
    }
}

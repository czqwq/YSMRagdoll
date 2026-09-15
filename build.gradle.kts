plugins {
    id("com.github.ElytraServers.elytra-conventions") version "v1.1.1"
    id("com.gtnewhorizons.gtnhconvention")
}

// 自动化测试使用 JUnit 4（与上游 1.20.1 工程的测试代码保持一致），
// 只覆盖与平台无关的骨骼数学、关节防拉伸和玩家碰撞响应。
tasks.named<Test>("test") {
    useJUnit()
}

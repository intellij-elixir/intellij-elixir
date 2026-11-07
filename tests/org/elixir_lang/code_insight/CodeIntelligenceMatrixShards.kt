package org.elixir_lang.code_insight

import com.intellij.testFramework.junit5.TestApplication

// One class per shard, up to `Shards.MAX`: Gradle forks by test class. `testFullMatrix` runs those below `-PmatrixShards`.
@TestApplication class CodeIntelligenceMatrixTestShard0 : CodeIntelligenceMatrixTest(0)
@TestApplication class CodeIntelligenceMatrixTestShard1 : CodeIntelligenceMatrixTest(1)
@TestApplication class CodeIntelligenceMatrixTestShard2 : CodeIntelligenceMatrixTest(2)
@TestApplication class CodeIntelligenceMatrixTestShard3 : CodeIntelligenceMatrixTest(3)
@TestApplication class CodeIntelligenceMatrixTestShard4 : CodeIntelligenceMatrixTest(4)
@TestApplication class CodeIntelligenceMatrixTestShard5 : CodeIntelligenceMatrixTest(5)
@TestApplication class CodeIntelligenceMatrixTestShard6 : CodeIntelligenceMatrixTest(6)
@TestApplication class CodeIntelligenceMatrixTestShard7 : CodeIntelligenceMatrixTest(7)
@TestApplication class CodeIntelligenceMatrixTestShard8 : CodeIntelligenceMatrixTest(8)
@TestApplication class CodeIntelligenceMatrixTestShard9 : CodeIntelligenceMatrixTest(9)
@TestApplication class CodeIntelligenceMatrixTestShard10 : CodeIntelligenceMatrixTest(10)
@TestApplication class CodeIntelligenceMatrixTestShard11 : CodeIntelligenceMatrixTest(11)

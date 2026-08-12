package com.adguard.filter

class NativeFilterRule {
    enum class RuleType

    @JvmField var ruleId: Long = 0
    @JvmField var ruleText: String? = null
    @JvmField var filterId: Int = 0
}

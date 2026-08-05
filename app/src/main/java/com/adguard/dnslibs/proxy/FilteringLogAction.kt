package com.adguard.dnslibs.proxy

class FilteringLogAction {
    @JvmField var rules: List<Any>? = null
    @JvmField var code: Int = 0
    @JvmField var filterListId: Int = 0
    @JvmField var allowed: Boolean = false

    constructor(rules: List<Any>?, code: Int, filterListId: Int, allowed: Boolean) {
        this.rules = rules
        this.code = code
        this.filterListId = filterListId
        this.allowed = allowed
    }

    class RuleTemplate {
        @JvmField var text: String? = null

        constructor(text: String?) {
            this.text = text
        }
    }
    
    enum class Option(val value: Int) {
        NONE(0)
    }
}

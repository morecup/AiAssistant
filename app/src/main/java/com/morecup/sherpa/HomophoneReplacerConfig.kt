package com.morecup.sherpa

data class HomophoneReplacerConfig(
    var dictDir: String = "",
    var lexicon: String = "",
    var ruleFsts: String = "",
)
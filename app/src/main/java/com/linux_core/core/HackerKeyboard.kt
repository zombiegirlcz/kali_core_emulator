package com.linux_core.core

/**
 * "Hackerská klávesnice" extrahovaná z Termius (com.server.auditor.ssh.client)
 * Zdroj: AdditionalPanelKeys.smali
 *
 * Sekce:
 *   row1_control   - Ovládací klávesy
 *   row2_modifiers - Modifikátory (Alt, Ctrl)
 *   row3_symbols   - Speciální znaky (30 symbolů)
 *   row4_navigation - Navigační klávesy
 *   row5_ctrl_combo - Ctrl kombinace (^C, ^Z, atd.)
 *   row6_function  - F-klávesy F1–F20
 */

enum class KeyType(val label: String) {
    // Row 1 - Ovládací
    ESC("Esc"),
    TAB("Tab"),
    ENTER("Enter"),
    BACK_SPACE("Back Space"),
    INSERT("Insert"),
    DELETE("Delete"),
    PASTE("Paste"),
    SHIFT_TAB("Shift+Tab"),

    // Row 2 - Modifikátory
    ALT("Alt"),
    CTRL("Ctrl"),
    SHIFT("Shift"),

    // Row 3 - Speciální znaky
    BACKSLASH("\\"),
    QUESTION("?"),
    PIPE("|"),
    SLASH("/"),
    COLON(":"),
    DASH("-"),
    UNDERSCORE("_"),
    AMPERSAND("&"),
    TILDE("~"),
    PLUS("+"),
    EQUALS("="),
    SEMICOLON(";"),
    DOLLAR("$"),
    ASTERISK("*"),
    CARET("^"),
    AT("@"),
    PERCENT("%"),
    HASH("#"),
    EXCLAMATION("!"),
    BACKTICK("`"),
    LESS("<"),
    GREATER(">"),
    PAREN_OPEN("("),
    PAREN_CLOSE(")"),
    BRACE_OPEN("{"),
    BRACE_CLOSE("}"),
    BRACKET_OPEN("["),
    BRACKET_CLOSE("]"),
    QUOTE("'"),
    DOT("."),

    // Row 4 - Navigace
    PAGE_UP("Pg Up"),
    PAGE_DOWN("Pg Dn"),
    ARROW_LEFT("Left"),
    ARROW_RIGHT("Right"),
    ARROW_UP("Up"),
    ARROW_DOWN("Down"),
    HOME("Home"),
    END("End"),

    // Row 5 - Ctrl kombinace
    CTRL_UNDERSCORE("^_"),
    CTRL_XX("^XX"),
    CTRL_Z("^Z"),
    CTRL_R("^R"),
    CTRL_G("^G"),
    CTRL_A("^A"),
    CTRL_B("^B"),
    CTRL_X("^X"),
    CTRL_F("^F"),
    CTRL_P("^P"),
    CTRL_N("^N"),
    CTRL_C("^C"),
    CTRL_H("^H"),
    CTRL_S("^S"),
    CTRL_Q("^Q"),
    CTRL_U("^U"),
    CTRL_W("^W"),
    CTRL_L("^L"),
    CTRL_D("^D"),

    // Row 6 - F-klávesy
    F1("F1"), F2("F2"), F3("F3"), F4("F4"),
    F5("F5"), F6("F6"), F7("F7"), F8("F8"),
    F9("F9"), F10("F10"), F11("F11"), F12("F12"),
    F13("F13"), F14("F14"), F15("F15"), F16("F16"),
    F17("F17"), F18("F18"), F19("F19"), F20("F20");
}

/**
 * Rozdělení kláves do řad podle původní struktury Termius
 */
object HackerKeyboardRows {
    val row1Control = listOf(
        KeyType.ESC, KeyType.TAB, KeyType.ENTER,
        KeyType.BACK_SPACE, KeyType.INSERT, KeyType.DELETE,
        KeyType.PASTE, KeyType.SHIFT_TAB
    )

    val row2Modifiers = listOf(
        KeyType.ALT, KeyType.CTRL, KeyType.SHIFT
    )

    val row3Symbols = listOf(
        KeyType.BACKSLASH, KeyType.QUESTION, KeyType.PIPE,
        KeyType.SLASH, KeyType.COLON, KeyType.DASH,
        KeyType.UNDERSCORE, KeyType.AMPERSAND, KeyType.TILDE,
        KeyType.PLUS, KeyType.EQUALS, KeyType.SEMICOLON,
        KeyType.DOLLAR, KeyType.ASTERISK, KeyType.CARET,
        KeyType.AT, KeyType.PERCENT, KeyType.HASH,
        KeyType.EXCLAMATION, KeyType.BACKTICK,
        KeyType.LESS, KeyType.GREATER,
        KeyType.PAREN_OPEN, KeyType.PAREN_CLOSE,
        KeyType.BRACE_OPEN, KeyType.BRACE_CLOSE,
        KeyType.BRACKET_OPEN, KeyType.BRACKET_CLOSE,
        KeyType.QUOTE, KeyType.DOT
    )

    val row4Navigation = listOf(
        KeyType.PAGE_UP, KeyType.PAGE_DOWN,
        KeyType.ARROW_LEFT, KeyType.ARROW_RIGHT,
        KeyType.ARROW_UP, KeyType.ARROW_DOWN,
        KeyType.HOME, KeyType.END
    )

    val row5CtrlCombos = listOf(
        KeyType.CTRL_UNDERSCORE, KeyType.CTRL_XX, KeyType.CTRL_Z,
        KeyType.CTRL_R, KeyType.CTRL_G, KeyType.CTRL_A,
        KeyType.CTRL_B, KeyType.CTRL_X, KeyType.CTRL_F,
        KeyType.CTRL_P, KeyType.CTRL_N, KeyType.CTRL_C,
        KeyType.CTRL_H, KeyType.CTRL_S, KeyType.CTRL_Q,
        KeyType.CTRL_U, KeyType.CTRL_W, KeyType.CTRL_L,
        KeyType.CTRL_D
    )

    val row6Function = listOf(
        KeyType.F1, KeyType.F2, KeyType.F3, KeyType.F4,
        KeyType.F5, KeyType.F6, KeyType.F7, KeyType.F8,
        KeyType.F9, KeyType.F10, KeyType.F11, KeyType.F12,
        KeyType.F13, KeyType.F14, KeyType.F15, KeyType.F16,
        KeyType.F17, KeyType.F18, KeyType.F19, KeyType.F20
    )

    /** Všechny klávesy v jednom seznamu */
    val allKeys: List<KeyType> =
        row1Control + row2Modifiers + row3Symbols +
        row4Navigation + row5CtrlCombos + row6Function
}

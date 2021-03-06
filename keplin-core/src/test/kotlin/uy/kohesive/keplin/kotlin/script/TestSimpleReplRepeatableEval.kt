@file:Suppress("DEPRECATION")

package uy.kohesive.keplin.kotlin.script

import org.jetbrains.kotlin.cli.common.repl.ReplRepeatingMode
import org.jetbrains.kotlin.cli.common.repl.ScriptArgsWithTypes
import org.junit.Test
import kotlin.script.templates.standard.ScriptTemplateWithBindings
import kotlin.test.assertEquals
import kotlin.test.junit.JUnitAsserter


class TestRepeatableEval {
    @Test
    fun testRepeatableLastNotAllowed() {
        SimplifiedRepl(repeatingMode = ReplRepeatingMode.NONE).use { repl ->
            val line1 = repl.compile(repl.nextCodeLine("""val x = 1"""))
            val line2 = repl.compile(repl.nextCodeLine("""val y = 2"""))
            val line3 = repl.compile(repl.nextCodeLine("""x+y"""))

            repl.eval(line1)
            repl.eval(line2)
            repl.eval(line3)
            try {
                repl.eval(line3)
                JUnitAsserter.fail("Expecting history mismatch error")
            } catch (ex: ReplException) {
                NO_ACTION()
            }
        }
    }

    @Test
    fun testRepeatableAnyNotAllowedInModeNONE() {
        SimplifiedRepl(repeatingMode = ReplRepeatingMode.NONE).use { repl ->
            val line1 = repl.compile(repl.nextCodeLine("""val x = 1"""))
            val line2 = repl.compile(repl.nextCodeLine("""val y = 2"""))
            val line3 = repl.compile(repl.nextCodeLine("""x+y"""))

            repl.eval(line1)
            repl.eval(line2)
            repl.eval(line3)

            try {
                repl.eval(line2)
                JUnitAsserter.fail("Expecting history mismatch error")
            } catch (ex: ReplException) {
                NO_ACTION()
            }
        }
    }

    @Test(expected = ReplException::class)
    fun testRepeatableAnyNotAllowedInModeMOSTRECENT() {
        SimplifiedRepl(repeatingMode = ReplRepeatingMode.REPEAT_ONLY_MOST_RECENT).use { repl ->
            val line1 = repl.compile(repl.nextCodeLine("""val x = 1"""))
            val line2 = repl.compile(repl.nextCodeLine("""val y = 2"""))
            val line3 = repl.compile(repl.nextCodeLine("""x+y"""))

            repl.eval(line1)
            repl.eval(line2)
            repl.eval(line3)

            // then BOOM
            repl.eval(line2)
        }
    }

    @Test
    fun testRepeatableExecutionsMOSTRECENT() {
        SimplifiedRepl(repeatingMode = ReplRepeatingMode.REPEAT_ONLY_MOST_RECENT).use { repl ->
            val line1 = repl.compile(repl.nextCodeLine("""val x = 1"""))
            val line2 = repl.compile(repl.nextCodeLine("""val y = 2"""))
            val line3 = repl.compile(repl.nextCodeLine("""x+y"""))

            repl.eval(line1)
            repl.eval(line1)
            repl.eval(line1)

            repl.eval(line2)
            repl.eval(line2)
            repl.eval(line2)
            repl.eval(line2)

            val result = repl.eval(line3)
            assertEquals(3, result.resultValue)
        }
    }

    @Test
    fun testRepeatableExecutionsREPEATANYPREVIOUS() {
        SimplifiedRepl(repeatingMode = ReplRepeatingMode.REPEAT_ANY_PREVIOUS).use { repl ->
            val line1 = repl.compile(repl.nextCodeLine("""val x = 1"""))
            val line2 = repl.compile(repl.nextCodeLine("""val y = 2"""))
            val line3 = repl.compile(repl.nextCodeLine("""x+y"""))

            repl.eval(line1)

            repl.eval(line2)

            repl.eval(line1)

            repl.eval(line2)

            val resultFirstTime = repl.eval(line3)
            assertEquals(3, resultFirstTime.resultValue)

            repl.eval(line2)

            val resultSecondTime = repl.eval(line3)
            assertEquals(3, resultSecondTime.resultValue)

            repl.eval(line1)
            repl.eval(line1)
            repl.eval(line1)
            repl.eval(line1)
        }
    }

    @Test
    fun testRepeatableChangesValues() {
        val scriptArgs = ScriptArgsWithTypes(arrayOf(emptyMap<String, Any?>()), arrayOf(Map::class))
        SimplifiedRepl(repeatingMode = ReplRepeatingMode.REPEAT_ANY_PREVIOUS,
                scriptDefinition = KotlinScriptDefinitionEx(ScriptTemplateWithBindings::class, scriptArgs)).use { repl ->
            val line1 = repl.compile(repl.nextCodeLine("""val something = bindings.get("x") as Int"""))
            val line2 = repl.compile(repl.nextCodeLine("""val somethingElse = something + (bindings.get("y") as Int)"""))
            val line3 = repl.compile(repl.nextCodeLine("""somethingElse + 10"""))

            val firstArgs = ScriptArgsWithTypes(arrayOf(mapOf<String, Any?>("x" to 100, "y" to 50)), arrayOf(Map::class))
            repl.eval(line1, firstArgs)
            repl.eval(line2, firstArgs)
            val result1 = repl.eval(line3)
            assertEquals(160, result1.resultValue)

            // same thing twice, same results
            repl.eval(line1, firstArgs)
            repl.eval(line2, firstArgs)
            val result2 = repl.eval(line3)
            assertEquals(160, result2.resultValue)

            val secondArgs = ScriptArgsWithTypes(arrayOf(mapOf<String, Any?>("x" to 200, "y" to 70)), arrayOf(Map::class))

            // eval line1 with different args affects it and would only affect line2
            repl.eval(line1, secondArgs)
            repl.eval(line2, secondArgs)
            val result3 = repl.eval(line3)
            assertEquals(280, result3.resultValue)
        }
    }
}
package uy.kohesive.keplin.kotlin.core.scripting

import com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.cli.common.CLIConfigurationKeys
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageLocation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.common.messages.MessageRenderer
import org.jetbrains.kotlin.cli.common.messages.PrintingMessageCollector
import org.jetbrains.kotlin.cli.common.repl.InvokeWrapper
import org.jetbrains.kotlin.cli.common.repl.ReplCodeLine
import org.jetbrains.kotlin.cli.jvm.config.addJvmClasspathRoots
import org.jetbrains.kotlin.cli.jvm.config.jvmClasspathRoots
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.script.KotlinScriptDefinition
import org.jetbrains.kotlin.utils.PathUtil
import uy.kohesive.keplin.kotlin.core.scripting.templates.ScriptTemplateWithArgs
import uy.kohesive.keplin.kotlin.util.scripting.findRequiredScriptingJarFiles
import java.io.Closeable
import java.io.File
import java.net.URLClassLoader
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.reflect.KClass

// TODO:  GenericRepl compatible constructor
open class ResettableRepl(val moduleName: String = "kotlin-script-module-${System.currentTimeMillis()}",
                          val additionalClasspath: List<File> = emptyList(),
                          val scriptDefinition: KotlinScriptDefinition = KotlinScriptDefinitionEx(ScriptTemplateWithArgs::class, ScriptArgsWithTypes(EMPTY_SCRIPT_ARGS, EMPTY_SCRIPT_ARGS_TYPES)),
                          val repeatingMode: ReplRepeatingMode = ReplRepeatingMode.NONE,
                          private val sharedHostClassLoader: ClassLoader? = null,
                          private val emptyArgsProvider: DefaultEmptyArgsProvider = (scriptDefinition as? DefaultEmptyArgsProvider) ?: SimpleEmptyArgs(null),
                          private val stateLock: ReentrantReadWriteLock = ReentrantReadWriteLock()) : Closeable {

    private val disposable = Disposer.newDisposable()

    private val messageCollector = PrintingMessageCollector(System.out, MessageRenderer.WITHOUT_PATHS, false)

    private val compilerConfiguration = CompilerConfiguration().apply {
        addJvmClasspathRoots(PathUtil.getJdkClassesRoots())
        addJvmClasspathRoots(findRequiredScriptingJarFiles(scriptDefinition.template,
                includeScriptEngine = false,
                includeKotlinCompiler = false,
                includeStdLib = true,
                includeRuntime = true))
        addJvmClasspathRoots(additionalClasspath)
        put(CommonConfigurationKeys.MODULE_NAME, moduleName)
        put<MessageCollector>(CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY, messageCollector)
    }

    private val compilerClasspath = compilerConfiguration.jvmClasspathRoots
    private val trackedClasspath = compilerClasspath.toMutableList()

    /*
       For reporting to the user the current known classpath as modified by evaluations
     */
    val currentEvalClassPath: List<File> get() = stateLock.read { evaluator.currentClasspath }
    var codeLineNumber = AtomicInteger(0)

    private val baseClassloader = URLClassLoader(compilerClasspath.map { it.toURI().toURL() }
            .toTypedArray(), sharedHostClassLoader)

    var defaultScriptArgs: ScriptArgsWithTypes? = emptyArgsProvider.defaultEmptyArgs
        get() = stateLock.read { field }
        set(value) = stateLock.write { field = value }

    private val compiler: DefaultResettableReplCompiler by lazy {
        DefaultResettableReplCompiler(disposable = disposable,
                scriptDefinition = scriptDefinition,
                compilerConfiguration = compilerConfiguration,
                messageCollector = PrintingMessageCollector(System.out, MessageRenderer.WITHOUT_PATHS, false),
                stateLock = stateLock)
    }

    private val evaluator: DefaultResettableReplEvaluator by lazy {
        DefaultResettableReplEvaluator(baseClasspath = compilerClasspath,
                baseClassloader = baseClassloader,
                repeatingMode = repeatingMode,
                stateLock = stateLock)
    }

    fun nextCodeLine(code: String) = stateLock.write { ReplCodeLine(codeLineNumber.incrementAndGet(), code) }

    /***
     * Resets back to a give line number, dropping higher lines
     * Returns the removed lines.
     */
    fun resetToLine(lineNumber: Int): List<ReplCodeLine> {
        stateLock.write {
            codeLineNumber.set(lineNumber)
            val removedCompiledLines = compiler.resetToLine(lineNumber)
            val removedEvaluatorLines = evaluator.resetToLine(lineNumber)

            removedCompiledLines.zip(removedEvaluatorLines).forEach {
                if (it.first != it.second) {
                    throw IllegalStateException("History mistmatch when resetting lines")
                }
            }

            return removedCompiledLines
        }
    }

    fun resetToLine(line: ReplCodeLine): List<ReplCodeLine> = resetToLine(line.no)

    fun compilationHistory(): List<ReplCodeLine> = stateLock.read { compiler.compilationHistory }
    fun evaluationHistory(): List<ReplCodeLine> = stateLock.read { evaluator.evaluationHistory }

    fun check(codeLine: ReplCodeLine): CheckResult {
        stateLock.write {
            val result = compiler.check(codeLine)
            return when (result) {
                is ResettableReplChecker.Response.Error -> throw ReplCompilerException(result)
                is ResettableReplChecker.Response.Ok -> CheckResult(codeLine, true)
                is ResettableReplChecker.Response.Incomplete -> CheckResult(codeLine, false)
                else -> throw IllegalStateException("Unknown check result type ${result}")
            }
        }
    }

    fun compileAndEval(codeLine: ReplCodeLine,
                       overrideScriptArgs: ScriptArgsWithTypes? = null,
                       wrapper: InvokeWrapper? = null): EvalResult {
        return stateLock.write {
            try {
                @Suppress("DEPRECATION")
                eval(compile(codeLine), overrideScriptArgs ?: defaultScriptArgs, wrapper)
            } catch (ex: ReplEvalRuntimeException) {
                ex.errorResult.completedEvalHistory.lastOrNull()?.let { compiler.resetToLine(it) }
                throw ex
            }
        }
    }

    fun compileAndEval(code: String,
                       overrideScriptArgs: ScriptArgsWithTypes? = null,
                       wrapper: InvokeWrapper? = null): EvalResult {
        return stateLock.write {
            compileAndEval(nextCodeLine(code), overrideScriptArgs ?: defaultScriptArgs, wrapper)
        }
    }

    @Deprecated("Unsafe to use individual compile/eval methods which may leave history state inconsistent across threads.", ReplaceWith("compileAndEval(codeLine)"))
    fun compile(codeLine: ReplCodeLine): CompileResult {
        stateLock.write {
            val result = compiler.compile(codeLine, null)
            return when (result) {
                is ResettableReplCompiler.Response.Error -> throw ReplCompilerException(result)
                is ResettableReplCompiler.Response.HistoryMismatch -> throw ReplCompilerException(result)
                is ResettableReplCompiler.Response.Incomplete -> throw ReplCompilerException(result)
                is ResettableReplCompiler.Response.CompiledClasses -> {
                    CompileResult(codeLine, result)
                }
                else -> throw IllegalStateException("Unknown compiler result type ${result}")
            }
        }
    }

    /***
     * Allowed evaluation later without keeping the full engine around in memory, good for compile and cache / run later models.
     */
    @Deprecated("Unsafe to use individual compile/eval methods which may leave history state inconsistent across threads and this one does not update known classpath.", ReplaceWith("compileAndEval(codeLine)"))
    fun delayEval(compileResult: CompileResult): DelayedEvaluation {
        return DelayedEvaluation(evaluator, compileResult, defaultScriptArgs, stateLock, trackedClasspath)
    }

    @Deprecated("Unsafe to use individual compile/eval methods which may leave history state inconsistent across threads.", ReplaceWith("compileAndEval(codeLine)"))
    fun eval(compileResult: CompileResult,
             overrideScriptArgs: ScriptArgsWithTypes? = null,
             wrapper: InvokeWrapper? = null): EvalResult {
        stateLock.write {
            val (result, classpathAddendum) = evalWithSpecialResult(evaluator, compileResult, overrideScriptArgs, defaultScriptArgs, wrapper)
            trackedClasspath.addAll(classpathAddendum)
            return result
        }
    }

    val lastEvaluatedScripts: List<EvalClassWithInstanceAndLoader> get() = evaluator.lastEvaluatedScripts

    override fun close() {
        disposable.dispose()
    }
}

/**
 * Allowed evaluation later without keeping the full engine around in memory, good for compile and cache / run later models.
 */
data class DelayedEvaluation(val evaluator: ResettableReplEvaluator,
                             val compileResult: CompileResult,
                             val defaultScriptArgs: ScriptArgsWithTypes?,
                             val stateLock: ReentrantReadWriteLock,
                             val currentClasspath: MutableList<File>) {
    fun eval(overrideScriptArgs: ScriptArgsWithTypes? = null,
             wrapper: InvokeWrapper? = null): EvalResult {
        stateLock.write {
            val (result, classpathAddendum) = evalWithSpecialResult(evaluator, compileResult, overrideScriptArgs, defaultScriptArgs, wrapper)
            currentClasspath.addAll(classpathAddendum)
            return result
        }
    }
}

private fun evalWithSpecialResult(evaluator: ResettableReplEvaluator,
                                  compileResult: CompileResult,
                                  overrideScriptArgs: ScriptArgsWithTypes? = null,
                                  defaultScriptArgs: ScriptArgsWithTypes? = null,
                                  wrapper: InvokeWrapper? = null): Pair<EvalResult, List<File>> {
    val result = evaluator.eval(compileResult.compilerData, wrapper ?: EvalInvoker(),
            scriptArgs = overrideScriptArgs ?: defaultScriptArgs)
    return when (result) {
        is ResettableReplEvaluator.Response.Error.CompileTime -> throw ReplCompilerException(result)
        is ResettableReplEvaluator.Response.Error.Runtime -> throw ReplEvalRuntimeException(result)
        is ResettableReplEvaluator.Response.HistoryMismatch -> throw ReplCompilerException(result)
        is ResettableReplEvaluator.Response.Incomplete -> throw ReplCompilerException(result)
        is ResettableReplEvaluator.Response.UnitResult -> {
            Pair(EvalResult(compileResult.codeLine, Unit), compileResult.compilerData.classpathAddendum)
        }
        is ResettableReplEvaluator.Response.ValueResult -> {
            Pair(EvalResult(compileResult.codeLine, result.value), compileResult.compilerData.classpathAddendum)
        }
        else -> throw IllegalStateException("Unknown compiler result type ${result}")
    }
}

private class EvalInvoker : InvokeWrapper {
    override fun <T> invoke(body: () -> T): T {
        return body()
    }
}

class ReplCompilerException(val errorResult: ResettableReplCompiler.Response.Error) : Exception(errorResult.message) {
    constructor (checkResult: ResettableReplChecker.Response.Error) : this(ResettableReplCompiler.Response.Error(emptyList(), checkResult.message, checkResult.location))
    constructor (incompleteResult: ResettableReplCompiler.Response.Incomplete) : this(ResettableReplCompiler.Response.Error(incompleteResult.compiledHistory, "Incomplete Code", CompilerMessageLocation.NO_LOCATION))
    constructor (historyMismatchResult: ResettableReplCompiler.Response.HistoryMismatch) : this(ResettableReplCompiler.Response.Error(historyMismatchResult.compiledHistory, "History Mismatch", CompilerMessageLocation.create(null, historyMismatchResult.lineNo, 0, null)))
    constructor (checkResult: ResettableReplEvaluator.Response.Error.CompileTime) : this(ResettableReplCompiler.Response.Error(checkResult.completedEvalHistory, checkResult.message, checkResult.location))
    constructor (incompleteResult: ResettableReplEvaluator.Response.Incomplete) : this(ResettableReplCompiler.Response.Error(incompleteResult.completedEvalHistory, "Incomplete Code", CompilerMessageLocation.NO_LOCATION))
    constructor (historyMismatchResult: ResettableReplEvaluator.Response.HistoryMismatch) : this(ResettableReplCompiler.Response.Error(historyMismatchResult.completedEvalHistory, "History Mismatch", CompilerMessageLocation.create(null, historyMismatchResult.lineNo, 0, null)))
}

class ReplEvalRuntimeException(val errorResult: ResettableReplEvaluator.Response.Error.Runtime) : Exception(errorResult.message, errorResult.cause)

data class CheckResult(val codeLine: ReplCodeLine, val isComplete: Boolean = true)
data class CompileResult(val codeLine: ReplCodeLine,
                         val compilerData: ResettableReplCompiler.Response.CompiledClasses)

data class EvalResult(val codeLine: ReplCodeLine, val resultValue: Any?)

class DefaultScriptDefinition(template: KClass<out Any>, scriptArgsWithTypes: ScriptArgsWithTypes?) :
        KotlinScriptDefinitionEx(template, scriptArgsWithTypes)
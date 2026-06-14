package com.mhxx.snipe

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Arduino 自動操作プログラムマネージャー
 * .ino .py .sh などのスクリプトファイルをインポート・実行管理
 */
class ArduinoAutomationManager(private val context: Context) {

    companion object {
        private const val TAG = "ArduinoAutomationManager"
        private const val PROGRAMS_DIR = "arduino_programs"
        
        // サポートするファイル形式
        val SUPPORTED_EXTENSIONS = setOf("ino", "py", "sh", "json", "txt")
    }

    private val programsDir: File = File(context.filesDir, PROGRAMS_DIR)
    private val executor: ExecutorService = Executors.newFixedThreadPool(2)
    private var executionListener: ExecutionListener? = null
    private val executingPrograms = mutableMapOf<String, Process>()

    interface ExecutionListener {
        fun onImportSuccess(fileName: String)
        fun onImportError(fileName: String, message: String)
        fun onExecutionStart(programName: String)
        fun onExecutionStop(programName: String, exitCode: Int)
        fun onExecutionError(programName: String, message: String)
        fun onOutputReceived(programName: String, output: String)
    }

    init {
        if (!programsDir.exists()) {
            programsDir.mkdirs()
        }
    }

    fun setListener(listener: ExecutionListener) {
        this.executionListener = listener
    }

    /**
     * ファイルをインポート
     */
    fun importProgram(sourceFile: File, customName: String? = null): Boolean {
        if (!sourceFile.exists()) {
            executionListener?.onImportError(sourceFile.name, "ファイルが見つかりません")
            return false
        }

        val extension = sourceFile.extension.lowercase()
        if (!SUPPORTED_EXTENSIONS.contains(extension)) {
            executionListener?.onImportError(
                sourceFile.name,
                "サポートされていないファイル形式: $extension"
            )
            return false
        }

        return try {
            val targetName = customName ?: sourceFile.name
            val targetFile = File(programsDir, targetName)

            sourceFile.copyTo(targetFile, overwrite = true)
            Log.d(TAG, "Program imported: $targetName")
            executionListener?.onImportSuccess(targetName)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Import error: ${e.message}", e)
            executionListener?.onImportError(sourceFile.name, e.message ?: "不明なエラー")
            false
        }
    }

    /**
     * インポート済みプログラム一覧を取得
     */
    fun getImportedPrograms(): List<String> {
        return programsDir.listFiles()?.map { it.name } ?: emptyList()
    }

    /**
     * プログラムを実行
     */
    fun executeProgram(programName: String, args: List<String> = emptyList()) {
        if (executingPrograms.containsKey(programName)) {
            executionListener?.onExecutionError(programName, "既に実行中です")
            return
        }

        executor.execute {
            val programFile = File(programsDir, programName)
            if (!programFile.exists()) {
                executionListener?.onExecutionError(programName, "プログラムが見つかりません")
                return@execute
            }

            try {
                executionListener?.onExecutionStart(programName)

                // ファイル形式に応じて実行方法を決定
                val command = buildCommand(programFile, args)
                val process = Runtime.getRuntime().exec(command.toTypedArray())
                executingPrograms[programName] = process

                // 出力をリアルタイムで読み取る
                readProcessOutput(programName, process)

                // プロセスの終了を待つ
                val exitCode = process.waitFor()
                executingPrograms.remove(programName)

                Log.d(TAG, "Program finished: $programName (exit code: $exitCode)")
                executionListener?.onExecutionStop(programName, exitCode)

            } catch (e: Exception) {
                Log.e(TAG, "Execution error: ${e.message}", e)
                executionListener?.onExecutionError(programName, e.message ?: "実行エラー")
                executingPrograms.remove(programName)
            }
        }
    }

    /**
     * コマンドを構築 (ファイル形式に基づいて)
     */
    private fun buildCommand(programFile: File, args: List<String>): List<String> {
        val extension = programFile.extension.lowercase()

        return when (extension) {
            "py" -> listOf("python3", programFile.absolutePath) + args
            "sh" -> {
                programFile.setExecutable(true)
                listOf(programFile.absolutePath) + args
            }
            "json" -> {
                // JSONスクリプト形式（カスタム処理）
                listOf("python3", programFile.absolutePath) + args
            }
            else -> {
                // テキスト/その他 - 内容に基づいて処理
                val content = programFile.readText()
                when {
                    content.startsWith("#!") -> {
                        programFile.setExecutable(true)
                        listOf(programFile.absolutePath) + args
                    }
                    content.contains("import ") || content.contains("def ") -> {
                        listOf("python3", programFile.absolutePath) + args
                    }
                    else -> listOf(programFile.absolutePath) + args
                }
            }
        }
    }

    /**
     * プロセス出力をリアルタイム読み取り
     */
    private fun readProcessOutput(programName: String, process: Process) {
        // 標準出力を読む
        process.inputStream.bufferedReader().use { reader ->
            reader.forEachLine { line ->
                Log.d(TAG, "[$programName] $line")
                executionListener?.onOutputReceived(programName, line)
            }
        }

        // エラー出力を読む
        process.errorStream.bufferedReader().use { reader ->
            reader.forEachLine { line ->
                Log.e(TAG, "[$programName] Error: $line")
                executionListener?.onOutputReceived(programName, "ERROR: $line")
            }
        }
    }

    /**
     * 実行中のプログラムを停止
     */
    fun stopProgram(programName: String) {
        executingPrograms[programName]?.apply {
            destroy()
            executingPrograms.remove(programName)
            Log.d(TAG, "Program stopped: $programName")
        }
    }

    /**
     * プログラムを削除
     */
    fun deleteProgram(programName: String): Boolean {
        // 実行中の場合は停止
        if (executingPrograms.containsKey(programName)) {
            stopProgram(programName)
        }

        val programFile = File(programsDir, programName)
        return programFile.delete().also {
            Log.d(TAG, "Program deleted: $programName (success: $it)")
        }
    }

    /**
     * プログラムの内容を取得
     */
    fun getProgramContent(programName: String): String? {
        return try {
            File(programsDir, programName).readText()
        } catch (e: Exception) {
            Log.e(TAG, "Read error: ${e.message}", e)
            null
        }
    }

    /**
     * プログラムの内容を更新
     */
    fun updateProgramContent(programName: String, content: String): Boolean {
        return try {
            File(programsDir, programName).writeText(content)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Write error: ${e.message}", e)
            false
        }
    }

    /**
     * リソース解放
     */
    fun cleanup() {
        // すべての実行中プログラムを停止
        executingPrograms.values.forEach { it.destroy() }
        executingPrograms.clear()
        executor.shutdown()
    }
}

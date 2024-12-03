package net.earthcomputer.clientcommands.buildscript

import com.google.gson.Gson
import com.google.gson.JsonObject
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import javax.inject.Inject

abstract class GenerateBuildInfoTask : DefaultTask() {
    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @get:Inject
    protected abstract val execOperations: ExecOperations

    init {
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun run() {
        val gitCommitHashBytes = ByteArrayOutputStream()
        execOperations.exec {
            commandLine("git", "rev-parse", "HEAD")
            standardOutput = gitCommitHashBytes
        }.rethrowFailure()
        val commitHash = gitCommitHashBytes.toByteArray().decodeToString().trim()

        val json = JsonObject()
        json.addProperty("commit_hash", commitHash)

        outputFile.asFile.get().writer().use {
            Gson().toJson(json, it)
        }
    }
}
